/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jctools.queues.MessagePassingQueue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.PlatformDependent.newMpscQueue;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);
    private static final Handle<?> NOOP_HANDLE = new Handle<Object>() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }

        @Override
        public String toString() {
            return "NOOP_HANDLE";
        }
    };
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int RATIO;
    private static final int DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD;
    private static final boolean BLOCKING_POOL;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        // 获取每个线程最大的容量，默认4kb
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        // 设置默认的每个线程最大容量 默认为4 * 1024
        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;
        // 获取每个线程默认的queueChunkSize 默认为32
        DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD = SystemPropertyUtil.getInt("io.netty.recycler.chunkSize", 32);

        // By default, we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        //
        // 默认的recyclerRatio为8
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        // recycler的blocking属性默认为false
        BLOCKING_POOL = SystemPropertyUtil.getBoolean("io.netty.recycler.blocking", false);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.chunkSize: disabled");
                logger.debug("-Dio.netty.recycler.blocking: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.chunkSize: {}", DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
                logger.debug("-Dio.netty.recycler.blocking: {}", BLOCKING_POOL);
            }
        }
    }

    private final int maxCapacityPerThread;
    private final int interval;
    private final int chunkSize;
    // 每次创建Recycler实例的时候，都会创建一个FastThreadLocal对象，其中存储的是LocalPool类型的对象
    private final FastThreadLocal<LocalPool<T>> threadLocal = new FastThreadLocal<LocalPool<T>>() {

        @Override
        protected LocalPool<T> initialValue() {
            // 将Recycler自身持有的maxCapacityPerThread interval 和chunkSize传入LocalPool的构造方法中
            return new LocalPool<T>(maxCapacityPerThread, interval, chunkSize);
        }

        @Override
        protected void onRemoval(LocalPool<T> value) throws Exception {
            super.onRemoval(value);
            MessagePassingQueue<DefaultHandle<T>> handles = value.pooledHandles;
            value.pooledHandles = null;
            handles.clear();
        }
    };

    protected Recycler() {
        // 将每个线程默认的最大容量传入 默认4096
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        // 调用重载的构造方法，传入RATIO 默认为8 和 每个线程默认的queueChunkSize，默认为32
        this(maxCapacityPerThread, RATIO, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, RATIO, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, ratio, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    /**
     * @deprecated Use one of the following instead:
     * {@link #Recycler()}, {@link #Recycler(int)}, {@link #Recycler(int, int, int)}.
     */
    @Deprecated
    @SuppressWarnings("unused") // Parameters we can't remove due to compatibility.
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        this(maxCapacityPerThread, ratio, DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int ratio, int chunkSize) {
        // 设置interval为0和ratio的最大值
        // 默认为max(0, 8) = 8
        interval = max(0, ratio);
        // 如果maxCapacityPerThread小于等于0，将chunkSize和自身的maxCapacityPerThread属性都置为0
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.chunkSize = 0;
        } else {
            // 否则将自身的maxCapacityPerThread属性设置为 maxCapacityPerThread参数和 4的最大值
            // 默认为max(4, 4*1024) = 4096
            this.maxCapacityPerThread = max(4, maxCapacityPerThread);
            // 将自身的chunkSize属性设置为 (chunkSize参数 和 maxCapacityPerThread / 2 的最小值) 和 2 的最大值
            // 默认为max(2, min(32, 4*1024/2)) = 32
            this.chunkSize = max(2, min(chunkSize, this.maxCapacityPerThread >> 1));
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        // 如果自身的maxCapacityPerThread为0，表示每个线程能缓存的最大容量为0，即localPool中的handle队列容量为0，
        // 那么直接调用newObject方法，将NOOP_HANDLE作为handle传入，
        // 该handle的recycle方法不会进行任何操作
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // 否则，获取fastThreadLocal中持有的LocalPool对象
        LocalPool<T> localPool = threadLocal.get();
        // 调用localPool对象的claim方法获取localPool的队列里的第一个DefaultHandle对象，
        // 并将其状态转换为claimed，表示这个handle已经被占用
        DefaultHandle<T> handle = localPool.claim();
        T obj;
        // 如果handle为null的话，说明没有对象池里没有handle
        if (handle == null) {
            // 通过localPool的newHandle方法去创建handle，
            // 有几率会返回null，取决于ratio的值，
            // ratio表示的就是每多少次调用newHandle能创建出一个不为null的handle对象。
            // 这样可以降低对象池容量的增长速度
            handle = localPool.newHandle();
            // 如果handle不为null
            if (handle != null) {
                // 调用newObject方法，将handle传入
                obj = newObject(handle);
                // 将生成的对象设置进handle中缓存起来
                handle.set(obj);
            } else {
                // 如果handle仍为null，使用NOOP_HANDLE
                obj = newObject((Handle<T>) NOOP_HANDLE);
            }
        }
        // 如果从对象池里获取的handle不为null的话，那么直接获取handle持有的对象进行复用
        else {
            obj = handle.get();
        }

        return obj;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        handle.recycle(o);
        return true;
    }

    final int threadLocalSize() {
        LocalPool<T> localPool = threadLocal.getIfExists();
        return localPool == null ? 0 : localPool.pooledHandles.size();
    }

    /**
     * @param handle can NOT be null.
     */
    protected abstract T newObject(Handle<T> handle);

    @SuppressWarnings("ClassNameSameAsAncestorName") // Can't change this due to compatibility.
    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    private static final class DefaultHandle<T> implements Handle<T> {
        private static final int STATE_CLAIMED = 0;
        private static final int STATE_AVAILABLE = 1;
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> STATE_UPDATER;
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(DefaultHandle.class, "state");
            //noinspection unchecked
            STATE_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        private volatile int state; // State is initialised to STATE_CLAIMED (aka. 0) so they can be released.
        private final LocalPool<T> localPool;
        private T value;

        DefaultHandle(LocalPool<T> localPool) {
            // 持有创建自己的这个localPool
            this.localPool = localPool;
        }

        @Override
        public void recycle(Object object) {
            // 如果传入的对象不等于自身持有的对象的话，报错
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            // 调用创建自己的这个localPool的release方法
            // 将自身的状态转换为available，表示是可以被使用的，
            // 然后将自身添加进localPool的handles队列中，即将自身重新添加回对象池中，等待重复使用
            localPool.release(this);
        }

        T get() {
            return value;
        }

        void set(T value) {
            // 设置handle持有的value
            this.value = value;
        }

        void toClaimed() {
            // 判断状态是否是available的
            assert state == STATE_AVAILABLE;
            // 将状态转换为claimed
            state = STATE_CLAIMED;
        }

        void toAvailable() {
            int prev = STATE_UPDATER.getAndSet(this, STATE_AVAILABLE);
            if (prev == STATE_AVAILABLE) {
                throw new IllegalStateException("Object has been recycled already.");
            }
        }
    }

    private static final class LocalPool<T> {
        private final int ratioInterval;
        private volatile MessagePassingQueue<DefaultHandle<T>> pooledHandles;
        private int ratioCounter;

        @SuppressWarnings("unchecked")
        LocalPool(int maxCapacity, int ratioInterval, int chunkSize) {
            // 设置自身的ratioInterval属性
            this.ratioInterval = ratioInterval;
            // 如果BLOCKING_POOL属性为true的话，创建一个阻塞队列赋值给pooledHandles属性
            if (BLOCKING_POOL) {
                pooledHandles = new BlockingMessageQueue<DefaultHandle<T>>(maxCapacity);
            } else {
                // 否则创建一个mpsc队列赋值给pooledHandles
                pooledHandles = (MessagePassingQueue<DefaultHandle<T>>) newMpscQueue(chunkSize, maxCapacity);
            }
            // 设置ratioCounter属性
            ratioCounter = ratioInterval; // Start at interval so the first one will be recycled.
        }

        DefaultHandle<T> claim() {
            // 获取pooledHandles队列
            MessagePassingQueue<DefaultHandle<T>> handles = pooledHandles;
            // 如果队列为null，直接返回null
            if (handles == null) {
                return null;
            }
            // 获取队列中队首的handle
            DefaultHandle<T> handle = handles.relaxedPoll();
            // 如果handle不为null，调用toClaimed方法进行声明
            if (null != handle) {
                handle.toClaimed();
            }
            // 返回handle
            return handle;
        }

        void release(DefaultHandle<T> handle) {
            // 将handle的状态设置为available，表示是可用的
            handle.toAvailable();
            // 获取localPool持有的handle队列
            MessagePassingQueue<DefaultHandle<T>> handles = pooledHandles;
            // 如果队列不为null，将handle添加进队列中，即重新放回对象池中，等待重复使用
            if (handles != null) {
                handles.relaxedOffer(handle);
            }
        }

        DefaultHandle<T> newHandle() {
            // 先将ratioCounter自增1，然后判断ratioCounter是否大于了ratioInterval，如果是，
            // 将ratioCounter置为0，创建一个DefaultHandle返回。
            // 因此每ratioInterval次调用newHandle才会创建一个DefaultHandle对象
            if (++ratioCounter >= ratioInterval) {
                ratioCounter = 0;
                return new DefaultHandle<T>(this);
            }
            // 如果自增之后的ratioCounter比interval小，直接返回null
            return null;
        }
    }

    /**
     * This is an implementation of {@link MessagePassingQueue}, similar to what might be returned from
     * {@link PlatformDependent#newMpscQueue(int)}, but intended to be used for debugging purpose.
     * The implementation relies on synchronised monitor locks for thread-safety.
     * The {@code drain} and {@code fill} bulk operations are not supported by this implementation.
     */
    private static final class BlockingMessageQueue<T> implements MessagePassingQueue<T> {
        private final Queue<T> deque;
        private final int maxCapacity;

        BlockingMessageQueue(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            // This message passing queue is backed by an ArrayDeque instance,
            // made thread-safe by synchronising on `this` BlockingMessageQueue instance.
            // Why ArrayDeque?
            // We use ArrayDeque instead of LinkedList or LinkedBlockingQueue because it's more space efficient.
            // We use ArrayDeque instead of ArrayList because we need the queue APIs.
            // We use ArrayDeque instead of ConcurrentLinkedQueue because CLQ is unbounded and has O(n) size().
            // We use ArrayDeque instead of ArrayBlockingQueue because ABQ allocates its max capacity up-front,
            // and these queues will usually have large capacities, in potentially great numbers (one per thread),
            // but often only have comparatively few items in them.
            deque = new ArrayDeque<T>();
        }

        @Override
        public synchronized boolean offer(T e) {
            if (deque.size() == maxCapacity) {
                return false;
            }
            return deque.offer(e);
        }

        @Override
        public synchronized T poll() {
            return deque.poll();
        }

        @Override
        public synchronized T peek() {
            return deque.peek();
        }

        @Override
        public synchronized int size() {
            return deque.size();
        }

        @Override
        public synchronized void clear() {
            deque.clear();
        }

        @Override
        public synchronized boolean isEmpty() {
            return deque.isEmpty();
        }

        @Override
        public int capacity() {
            return maxCapacity;
        }

        @Override
        public boolean relaxedOffer(T e) {
            return offer(e);
        }

        @Override
        public T relaxedPoll() {
            return poll();
        }

        @Override
        public T relaxedPeek() {
            return peek();
        }

        @Override
        public int drain(Consumer<T> c, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fill(Supplier<T> s, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int drain(Consumer<T> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fill(Supplier<T> s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void drain(Consumer<T> c, WaitStrategy wait, ExitCondition exit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fill(Supplier<T> s, WaitStrategy wait, ExitCondition exit) {
            throw new UnsupportedOperationException();
        }
    }
}
