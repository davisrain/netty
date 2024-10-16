/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="https://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<byte[]> heapArena;
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are small and normal.
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int smallCacheSize, int normalCacheSize, int maxCachedBufferCapacity,
                    int freeSweepAllocationThreshold) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        // 设置分配多少次之后进行缓存清理的阈值
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        // 设置堆内存的arena
        this.heapArena = heapArena;
        // 设置直接内存的arena
        this.directArena = directArena;

        if (directArena != null) {
            // 创建subPage的直接内存缓存数组
            // 将directArena的numSmallSubpagePools作为缓存数组的长度。
            // 缓存类型为：MemoryRegionCache
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools);

            // 创建normal类型的直接缓存数组
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
        }
        // 对堆arena作同样的操作
        if (heapArena != null) {
            // Create the caches for the heap allocations
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools);

            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
        }

        // Only check if there are caches in use.
        // 如果存在使用的缓存，但是清除缓存的阈值小于1，报错
        if ((smallSubPageDirectCaches != null || normalDirectCaches != null
                || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches) {
        // 如果 cacheSize 和 numCaches都大于0
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            // 根据缓存数量 创建一个MemoryRegionCache类型的数组
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                // 为数组每个位置创建一个SubPageMemoryRegionCache的实现类，将cacheSize作为每个RegionCache能缓存的ByteBuf的数量传入。
                // subpage默认的cacheSize是256
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        }
        // 如果不是，返回null
        else {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        // 如果cacheSize 和 maxCachedBufferCapacity都大于0
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            // 获取arena中的chunkSize 和 maxCachedBufferCapacity中更小的值作为内存的最大值
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            // Create as many normal caches as we support based on how many sizeIdx we have and what the upper
            // bound is that we want to cache in general.
            // 创建一个cache的集合，cache的数量取决于arena中不是subpage且内存不大于max那些的sizeClass的数量
            List<MemoryRegionCache<T>> cache = new ArrayList<MemoryRegionCache<T>>() ;
            // 从subpage的数量作为idx开始遍历
            for (int idx = area.numSmallSubpagePools; idx < area.nSizes && area.sizeIdx2size(idx) <= max; idx++) {
                // 创建一个NormalMemoryRegionCache添加进集合中，其中，将cacheSize传入，作为能缓存的ByteBuf的数量。
                // 默认的normalCacheSize为64
                cache.add(new NormalMemoryRegionCache<T>(cacheSize));
            }
            // 然后转换为数组返回
            return cache.toArray(new MemoryRegionCache[0]);
        }
        // 否则，返回null
        else {
            return null;
        }
    }

    // val > 0
    static int log2(int val) {
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        // 首先根据sizeIdx和arena的类型拿到对应的MemoryRegionCache对象
        // 调用allocate方法，将刚才获取到的MemoryRegionCache传入
        return allocate(cacheForSmall(area, sizeIdx), buf, reqCapacity);
    }

    /**
     * Try to allocate a normal buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int sizeIdx) {
        return allocate(cacheForNormal(area, sizeIdx), buf, reqCapacity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        // 如果传入的MemoryRegionCache为null，直接返回false，表示通过缓存分配内存失败
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        // 调用MemoryRegionCache的allocate方法进行分配
        boolean allocated = cache.allocate(buf, reqCapacity, this);
        // 如果分配次数大于了 清理缓存的阈值
        if (++allocations >= freeSweepAllocationThreshold) {
            // 将分配次数置为0，并且清理缓存
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        // 获取size对应的sizeClass的index
        int sizeIdx = area.size2SizeIdx(normCapacity);
        // 根据内存的类型获取arena对应的MemoryRegionCache
        MemoryRegionCache<?> cache = cache(area, sizeIdx, sizeClass);
        // 如果cache为null，直接返回false
        if (cache == null) {
            return false;
        }
        // 获取freed的值，如果是true的话，也直接返回false
        if (freed.get()) {
            return false;
        }
        // 调用cache的add方法，将chunk和handle存入
        return cache.add(chunk, nioBuffer, handle, normCapacity);
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int sizeIdx, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, sizeIdx);
        case Small:
            return cacheForSmall(area, sizeIdx);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free(true);
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free(boolean finalizer) {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(smallSubPageDirectCaches, finalizer) +
                    free(normalDirectCaches, finalizer) +
                    free(smallSubPageHeapCaches, finalizer) +
                    free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        } else {
            // See https://github.com/netty/netty/issues/12749
            checkCacheMayLeak(smallSubPageDirectCaches, "SmallSubPageDirectCaches");
            checkCacheMayLeak(normalDirectCaches, "NormalDirectCaches");
            checkCacheMayLeak(smallSubPageHeapCaches, "SmallSubPageHeapCaches");
            checkCacheMayLeak(normalHeapCaches, "NormalHeapCaches");
        }
    }

    private static void checkCacheMayLeak(MemoryRegionCache<?>[] caches, String type) {
        for (MemoryRegionCache<?> cache : caches) {
            if (cache.queue.size() > 0) {
                logger.debug("{} memory may leak.", type);
                return;
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    void trim() {
        // 分别清理各个类型的缓存
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        // 遍历缓存数组，清理每一个MemoryRegionCache
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        // 调用MemoryRegionCache的trim进行缓存清理
        cache.trim();
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int sizeIdx) {
        // 如果arena是direct的
        if (area.isDirect()) {
            // 使用smallSubPageDirectCaches
            // 根据sizeIdx拿到smallSubpageDirectCaches数组对应下标的MemoryRegionCache对象
            return cache(smallSubPageDirectCaches, sizeIdx);
        }
        return cache(smallSubPageHeapCaches, sizeIdx);
    }

    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int sizeIdx) {
        // We need to substract area.numSmallSubpagePools as sizeIdx is the overall index for all sizes.
        // 需要将sizeIdx 减去 arena的numSmallSubpagePools，才是对应在normalDirectCaches数组的下标位置
        int idx = sizeIdx - area.numSmallSubpagePools;
        if (area.isDirect()) {
            // 根据idx获取到数组中对应下标的MemoryRegionCache对象
            return cache(normalDirectCaches, idx);
        }
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int sizeIdx) {
        if (cache == null || sizeIdx > cache.length - 1) {
            return null;
        }
        // 获取对应的sizeIdx的MemoryRegionCache
        return cache[sizeIdx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size) {
            super(size, SizeClass.Small);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            // 调用chunk的initBufWithSubpage进行small类型的内存分配
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity,
                PoolThreadCache threadCache) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity, threadCache);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        private final int size;
        private final Queue<Entry<T>> queue;
        private final SizeClass sizeClass;
        private int allocations;

        MemoryRegionCache(int size, SizeClass sizeClass) {
            // 找到size的下一个2的幂次方的数作为实际的size
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            // 创建一个固定大小的mpsc队列
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            // 设置sizeClass的类型
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache);

        /**
         * Add to cache if not already full.
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            // 创建一个Entry用于保存chunk和handle以及normCapacity的信息
            Entry<T> entry = newEntry(chunk, nioBuffer, handle, normCapacity);
            // 将entry入队，queue是有大小限制的，small类型的queue默认256长度，normal类型的queue默认64长度
            boolean queued = queue.offer(entry);
            // 如果入队失败，调用entry的recycle方法进行回收
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }

            // 返回是否入队成功
            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity, PoolThreadCache threadCache) {
            // 从队列中取出Entry
            Entry<T> entry = queue.poll();
            // 如果为null，返回false，表示分配失败
            if (entry == null) {
                return false;
            }
            // 否则调用initBuf方法，
            // 不同类型的MemoryRegionCache主要体现在initBuf方法的不同，SubpageMemoryRegionCache的调用的是chunk的initBufWithSubpage方法
            // 而NormalMemoryRegionCache调用的是entry缓存的chunk的initBuf方法
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity, threadCache);
            // 调用entry的recycle方法将缓存节点Entry回收回对象池
            // 即将entry持有的chunk handle nioBuffer属性置为null，然后将entry对象回收到对象池中
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // 将分配次数+1，这里只会有一个线程进行调用，所以不用同步
            ++ allocations;
            // 返回true
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         * 清除这个缓存并且释放所有之前缓存的PoolChunk和handle，因为传入的max为Integer.MAX_VALUE
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        private int free(int max, boolean finalizer) {
            int numFreed = 0;
            // 根据max进行遍历
            for (; numFreed < max; numFreed++) {
                // 从队列中获取entry进行free
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    // 调用freeEntry进行具体的释放操作
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    // 如果队列中已经没有元素了，说明已经全部被清理了，返回numFreed
                    // 表示清理的entry的数量
                    return numFreed;
                }
            }
            // 返回清理的entry的数量
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         * 如果没有频繁的分配该MemoryRegionCache里面的entry，就释放缓存的PoolChunk
         */
        public final void trim() {
            // 根据MemoryRegionCache的总容量 和 已经分配的数量
            // 计算出当前空闲的容量
            int free = size - allocations;
            // 将统计的分配数量置为0
            allocations = 0;

            // We not even allocated all the number that are
            // 如果空闲的容量大于0，调用free方法，并且将free传入
            // 表示最大释放 free数量的缓存的entry
            if (free > 0) {
                free(free, false);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry, boolean finalizer) {
            // Capture entry state before we recycle the entry object.
            // 获取entry持有的chunk handle nioBuffer normCapacity等属性
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;
            int normCapacity = entry.normCapacity;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                entry.recycle();
            }

            // 调用arena的freeChunk释放chunk中handle对应的内存块
            chunk.arena.freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, finalizer);
        }

        static final class Entry<T> {
            final Handle<Entry<?>> recyclerHandle;
            // entry作为缓存节点 缓存的内存chunk
            PoolChunk<T> chunk;
            // 缓存的nioBuffer
            ByteBuffer nioBuffer;
            // 默认的缓存的handle为-1
            long handle = -1;
            //
            int normCapacity;

            Entry(Handle<Entry<?>> recyclerHandle) {
                // 持有回收handle，决定了entry的回收策略
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                // 调用回收策略进行回收
                recyclerHandle.recycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle, int normCapacity) {
            // 通过对象池去获取Entry对象
            Entry entry = RECYCLER.get();
            // 将chunk nioBuffer handle和normCapacity设置进Entry中
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            entry.normCapacity = normCapacity;
            // 然后返回
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });
    }
}
