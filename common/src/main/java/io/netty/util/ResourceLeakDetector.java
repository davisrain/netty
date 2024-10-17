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

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static io.netty.util.internal.StringUtil.simpleClassName;

public class ResourceLeakDetector<T> {

    private static final String PROP_LEVEL_OLD = "io.netty.leakDetectionLevel";
    private static final String PROP_LEVEL = "io.netty.leakDetection.level";
    private static final Level DEFAULT_LEVEL = Level.SIMPLE;

    private static final String PROP_TARGET_RECORDS = "io.netty.leakDetection.targetRecords";
    private static final int DEFAULT_TARGET_RECORDS = 4;

    private static final String PROP_SAMPLING_INTERVAL = "io.netty.leakDetection.samplingInterval";
    // There is a minor performance benefit in TLR if this is a power of 2.
    private static final int DEFAULT_SAMPLING_INTERVAL = 128;

    private static final int TARGET_RECORDS;
    static final int SAMPLING_INTERVAL;

    /**
     * Represents the level of resource leak detection.
     */
    public enum Level {
        /**
         * Disables resource leak detection.
         */
        DISABLED,
        /**
         * Enables simplistic sampling resource leak detection which reports there is a leak or not,
         * at the cost of small overhead (default).
         */
        SIMPLE,
        /**
         * Enables advanced sampling resource leak detection which reports where the leaked object was accessed
         * recently at the cost of high overhead.
         */
        ADVANCED,
        /**
         * Enables paranoid resource leak detection which reports where the leaked object was accessed recently,
         * at the cost of the highest possible overhead (for testing purposes only).
         */
        PARANOID;

        /**
         * Returns level based on string value. Accepts also string that represents ordinal number of enum.
         *
         * @param levelStr - level string : DISABLED, SIMPLE, ADVANCED, PARANOID. Ignores case.
         * @return corresponding level or SIMPLE level in case of no match.
         */
        static Level parseLevel(String levelStr) {
            String trimmedLevelStr = levelStr.trim();
            for (Level l : values()) {
                if (trimmedLevelStr.equalsIgnoreCase(l.name()) || trimmedLevelStr.equals(String.valueOf(l.ordinal()))) {
                    return l;
                }
            }
            return DEFAULT_LEVEL;
        }
    }

    private static Level level;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ResourceLeakDetector.class);

    static {
        final boolean disabled;
        // 如果io.netty.noResourceLeakDetection环境属性不为null，获取属性对应的value设置给disabled变量，默认为false
        if (SystemPropertyUtil.get("io.netty.noResourceLeakDetection") != null) {
            disabled = SystemPropertyUtil.getBoolean("io.netty.noResourceLeakDetection", false);
            logger.debug("-Dio.netty.noResourceLeakDetection: {}", disabled);
            logger.warn(
                    "-Dio.netty.noResourceLeakDetection is deprecated. Use '-D{}={}' instead.",
                    PROP_LEVEL, DEFAULT_LEVEL.name().toLowerCase());
        }
        // 如果为null，将disabled设置为false
        else {
            disabled = false;
        }

        // 默认的级别 根据disabled的值，设置为DISABLED 或者 DEFAULT_LEVEL = SIMPLE
        Level defaultLevel = disabled? Level.DISABLED : DEFAULT_LEVEL;

        // First read old property name
        // 获取环境变量中配置的资源泄漏检测级别
        String levelStr = SystemPropertyUtil.get(PROP_LEVEL_OLD, defaultLevel.name());

        // If new property name is present, use it
        levelStr = SystemPropertyUtil.get(PROP_LEVEL, levelStr);
        Level level = Level.parseLevel(levelStr);

        // 设置targetRecords的值，默认为4
        TARGET_RECORDS = SystemPropertyUtil.getInt(PROP_TARGET_RECORDS, DEFAULT_TARGET_RECORDS);
        // 设置samplingInterval，默认为128
        SAMPLING_INTERVAL = SystemPropertyUtil.getInt(PROP_SAMPLING_INTERVAL, DEFAULT_SAMPLING_INTERVAL);

        // 将资源泄漏检测级别设置到ResourceLeakDetector的静态变量中
        ResourceLeakDetector.level = level;
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_LEVEL, level.name().toLowerCase());
            logger.debug("-D{}: {}", PROP_TARGET_RECORDS, TARGET_RECORDS);
        }
    }

    /**
     * @deprecated Use {@link #setLevel(Level)} instead.
     */
    @Deprecated
    public static void setEnabled(boolean enabled) {
        setLevel(enabled? Level.SIMPLE : Level.DISABLED);
    }

    /**
     * Returns {@code true} if resource leak detection is enabled.
     */
    public static boolean isEnabled() {
        // 如果资源泄漏检测级别大于DISABLED，表明是开启的，默认为SIMPLE级别
        return getLevel().ordinal() > Level.DISABLED.ordinal();
    }

    /**
     * Sets the resource leak detection level.
     */
    public static void setLevel(Level level) {
        ResourceLeakDetector.level = ObjectUtil.checkNotNull(level, "level");
    }

    /**
     * Returns the current resource leak detection level.
     */
    public static Level getLevel() {
        return level;
    }

    /** the collection of active resources */
    private final Set<DefaultResourceLeak<?>> allLeaks =
            Collections.newSetFromMap(new ConcurrentHashMap<DefaultResourceLeak<?>, Boolean>());

    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<Object>();
    private final Set<String> reportedLeaks =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final String resourceType;
    private final int samplingInterval;

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType) {
        this(simpleClassName(resourceType));
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType) {
        this(resourceType, DEFAULT_SAMPLING_INTERVAL, Long.MAX_VALUE);
    }

    /**
     * @deprecated Use {@link ResourceLeakDetector#ResourceLeakDetector(Class, int)}.
     * <p>
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     *
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval, long maxActive) {
        this(resourceType, samplingInterval);
    }

    /**
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     */
    @SuppressWarnings("deprecation")
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        this(simpleClassName(resourceType), samplingInterval, Long.MAX_VALUE);
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     * <p>
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType, int samplingInterval, long maxActive) {
        this.resourceType = ObjectUtil.checkNotNull(resourceType, "resourceType");
        this.samplingInterval = samplingInterval;
    }

    /**
     * Creates a new {@link ResourceLeak} which is expected to be closed via {@link ResourceLeak#close()} when the
     * related resource is deallocated.
     *
     * @return the {@link ResourceLeak} or {@code null}
     * @deprecated use {@link #track(Object)}
     */
    @Deprecated
    public final ResourceLeak open(T obj) {
        return track0(obj);
    }

    /**
     * Creates a new {@link ResourceLeakTracker} which is expected to be closed via
     * {@link ResourceLeakTracker#close(Object)} when the related resource is deallocated.
     *
     * @return the {@link ResourceLeakTracker} or {@code null}
     */
    @SuppressWarnings("unchecked")
    public final ResourceLeakTracker<T> track(T obj) {
        return track0(obj);
    }

    @SuppressWarnings("unchecked")
    private DefaultResourceLeak track0(T obj) {
        // 首先判断ResourceLeakDetector的级别，如果是DISABLED的，直接返回null
        Level level = ResourceLeakDetector.level;
        if (level == Level.DISABLED) {
            return null;
        }

        // 如果等级是低于PARANOID的
        if (level.ordinal() < Level.PARANOID.ordinal()) {
            // 获取0到samplingInterval之间的一个随机值，如果等于0的话
            // 因此并不是每次都会创建出对应的tracker对象的，samplingInterval的作用就是如此，
            // 平均情况每samplingInterval次才会创建一个tracker对象
            if ((PlatformDependent.threadLocalRandom().nextInt(samplingInterval)) == 0) {
                // 调用reportLeak方法，打印之前存在的资源泄露，即refQueue中保存的那些tracker
                reportLeak();
                // 创建一个DefaultResourceLeak对象返回
                return new DefaultResourceLeak(obj, refQueue, allLeaks, getInitialHint(resourceType));
            }
            // 否则返回null
            return null;
        }
        // 如果等级等于PARANOID，samplingInterval不起作用，每次都会创建tracker对象
        // 调用reportLeak方法
        reportLeak();
        // 然后创建一个DefaultResourceLead对象返回
        return new DefaultResourceLeak(obj, refQueue, allLeaks, getInitialHint(resourceType));
    }

    private void clearRefQueue() {
        // 遍历refQueue
        for (;;) {
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            // 调用tracker的dispose方法
            ref.dispose();
        }
    }

    /**
     * When the return value is {@code true}, {@link #reportTracedLeak} and {@link #reportUntracedLeak}
     * will be called once a leak is detected, otherwise not.
     *
     * @return {@code true} to enable leak reporting.
     */
    protected boolean needReport() {
        return logger.isErrorEnabled();
    }

    private void reportLeak() {
        // 如果日志的error等级都没开启的话，不用打印
        if (!needReport()) {
            // 直接清除refQueue里面的内容
            clearRefQueue();
            return;
        }

        // Detect and report previous leaks.
        // 检测并且报告之前的泄露
        for (;;) {
            // 遍历refQueue，获取之前没有正确释放的资源的leakTracker
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            // 如果队列已经空了，跳出循环
            if (ref == null) {
                break;
            }

            // 如果tracker已经从allLeaks这个set中删除了，那么说明有成功调用leakTracker的close方法，
            // 是正确释放的，跳过。这种情况会出现在close方法还没执行的时候，referent就已经开始gc了，所以会导致
            // leakTracker被添加进了refQueue中，然后close执行结束，leakTracker从allLeaks中被删除。
            // 所以之后在leakTracker的close方法中添加了reachabilityFence0方法来避免这种情况
            if (!ref.dispose()) {
                continue;
            }

            // 走到这一步说明确实是没有正确释放资源的对象，生成要打印的日志内容以及清除掉TraceRecord链表
            String records = ref.getReportAndClearRecords();
            // 将要报告的内容添加进reportedLeaks这个set中，如果添加失败，说明已经报告过
            if (reportedLeaks.add(records)) {
                // 如果内容为空的话，打印error日志，说明leakTracker没有记录TraceRecord相关的信息
                if (records.isEmpty()) {
                    reportUntracedLeak(resourceType);
                }
                // 如果不为空，将要报告的内容通过error日志打印
                else {
                    reportTracedLeak(resourceType, records);
                }
            }
        }
    }

    /**
     * This method is called when a traced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportTracedLeak(String resourceType, String records) {
        logger.error(
                "LEAK: {}.release() was not called before it's garbage-collected. " +
                "See https://netty.io/wiki/reference-counted-objects.html for more information.{}",
                resourceType, records);
    }

    /**
     * This method is called when an untraced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportUntracedLeak(String resourceType) {
        logger.error("LEAK: {}.release() was not called before it's garbage-collected. " +
                "Enable advanced leak reporting to find out where the leak occurred. " +
                "To enable advanced leak reporting, " +
                "specify the JVM option '-D{}={}' or call {}.setLevel() " +
                "See https://netty.io/wiki/reference-counted-objects.html for more information.",
                resourceType, PROP_LEVEL, Level.ADVANCED.name().toLowerCase(), simpleClassName(this));
    }

    /**
     * @deprecated This method will no longer be invoked by {@link ResourceLeakDetector}.
     */
    @Deprecated
    protected void reportInstancesLeak(String resourceType) {
    }

    /**
     * Create a hint object to be attached to an object tracked by this record. Similar to the additional information
     * supplied to {@link ResourceLeakTracker#record(Object)}, will be printed alongside the stack trace of the
     * creation of the resource.
     */
    protected Object getInitialHint(String resourceType) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private static final class DefaultResourceLeak<T>
            extends WeakReference<Object> implements ResourceLeakTracker<T>, ResourceLeak {

        // 创建一个AtomicReferenceFieldUpdater对象，用于更新自身的head属性
        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicReferenceFieldUpdater<DefaultResourceLeak<?>, TraceRecord> headUpdater =
                (AtomicReferenceFieldUpdater)
                        AtomicReferenceFieldUpdater.newUpdater(DefaultResourceLeak.class, TraceRecord.class, "head");

        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        // 创建droppedRecords属性的AtomicIntegerFieldUpdater对象
        private static final AtomicIntegerFieldUpdater<DefaultResourceLeak<?>> droppedRecordsUpdater =
                (AtomicIntegerFieldUpdater)
                        AtomicIntegerFieldUpdater.newUpdater(DefaultResourceLeak.class, "droppedRecords");

        @SuppressWarnings("unused")
        private volatile TraceRecord head;
        @SuppressWarnings("unused")
        private volatile int droppedRecords;

        private final Set<DefaultResourceLeak<?>> allLeaks;
        private final int trackedHash;

        DefaultResourceLeak(
                Object referent,
                ReferenceQueue<Object> refQueue,
                Set<DefaultResourceLeak<?>> allLeaks,
                Object initialHint) {
            // 将referent和refQueue传入父类构造器中，父类是弱引用。
            // 因此referent在被gc之后，当前对象，即tracker会被添加进refQueue中。
            // 但如果referent在被回收之前，正确释放了资源，那么就会调用到当前对象的close方法，
            // close方法会将referent从WeakReference的referent属性中清除，那么就不会触发tracker被添加进队列中的操作。
            // 所以我们可以通过tracker被添加进队列来实现referent未正确释放资源的回调，然后进行告警操作
            super(referent, refQueue);

            assert referent != null;

            // Store the hash of the tracked object to later assert it in the close(...) method.
            // It's important that we not store a reference to the referent as this would disallow it from
            // be collected via the WeakReference.
            // 计算referent的一致性哈希，将其设置为trackedHash属性，
            // 这里使用hash是因为不能持有referent的强引用，否则弱引用就没有意义了
            trackedHash = System.identityHashCode(referent);
            // 将自身添加到allLeaks这个set中，这个set是检查资源是否泄露的关键
            allLeaks.add(this);
            // Create a new Record so we always have the creation stacktrace included.
            // 将自身的head设置为新创建的TraceRecord对象，如果initialHint不为null，会被添加进TraceRecord中。
            // TraceRecord是一个Throwable类型的对象，目的是为了记录当前时刻的栈帧信息。
            // 因为Throwable构造的时候就会调用fillInStackTrace将当前栈帧信息保存起来
            headUpdater.set(this, initialHint == null ?
                    new TraceRecord(TraceRecord.BOTTOM) : new TraceRecord(TraceRecord.BOTTOM, initialHint));
            // 将allLeaks赋值给自身属性持有
            this.allLeaks = allLeaks;
        }

        @Override
        // 调用record方法可以记录一个时刻，会生成一个新的TraceRecord对象添加到链表里面，新的TraceRecord会保存当前线程的栈帧信息
        public void record() {
            record0(null);
        }

        @Override
        public void record(Object hint) {
            record0(hint);
        }

        /**
         * This method works by exponentially backing off as more records are present in the stack. Each record has a
         * 1 / 2^n chance of dropping the top most record and replacing it with itself. This has a number of convenient
         * properties:
         *
         * <ol>
         * <li>  The current record is always recorded. This is due to the compare and swap dropping the top most
         *       record, rather than the to-be-pushed record.
         * <li>  The very last access will always be recorded. This comes as a property of 1.
         * <li>  It is possible to retain more records than the target, based upon the probability distribution.
         * <li>  It is easy to keep a precise record of the number of elements in the stack, since each element has to
         *     know how tall the stack is.
         * </ol>
         *
         * In this particular implementation, there are also some advantages. A thread local random is used to decide
         * if something should be recorded. This means that if there is a deterministic access pattern, it is now
         * possible to see what other accesses occur, rather than always dropping them. Second, after
         * {@link #TARGET_RECORDS} accesses, backoff occurs. This matches typical access patterns,
         * where there are either a high number of accesses (i.e. a cached buffer), or low (an ephemeral buffer), but
         * not many in between.
         *
         * The use of atomics avoids serializing a high number of accesses, when most of the records will be thrown
         * away. High contention only happens when there are very few existing records, which is only likely when the
         * object isn't shared! If this is a problem, the loop can be aborted and the record dropped, because another
         * thread won the race.
         */
        private void record0(Object hint) {
            // Check TARGET_RECORDS > 0 here to avoid similar check before remove from and add to lastRecords
            // targetRecords默认为4，表示的是链表里面的元素个数超过多少就需要通过策略来判断是新增进链表还是直接替换链表的头节点
            if (TARGET_RECORDS > 0) {
                TraceRecord oldHead;
                TraceRecord prevHead;
                TraceRecord newHead;
                boolean dropped;
                do {
                    // 获取DefaultResourceLeak对象里面维护的TraceRecord链表的头节点，并且赋值给prevHead oldHead
                    // 如果头节点为null，直接返回，说明该tracker已经被关闭了
                    if ((prevHead = oldHead = headUpdater.get(this)) == null) {
                        // already closed.
                        return;
                    }
                    // 计算出新增一个record之后链表里面的元素个数
                    final int numElements = oldHead.pos + 1;
                    // 如果个数大于等于了 targetRecords
                    if (numElements >= TARGET_RECORDS) {
                        // 取系数n为numElements - targetRecords 和 30的最小值
                        final int backOffFactor = Math.min(numElements - TARGET_RECORDS, 30);
                        // 然后有 1 / 2^n的概率将新的record添加到头节点，换句话说，也就是有很大的概率是替换头节点，防止链表长度的增长
                        if (dropped = PlatformDependent.threadLocalRandom().nextInt(1 << backOffFactor) != 0) {
                            // 将prevHead指向oldHead的next节点，并且将dropped设置为true，表示会丢弃原本的头节点
                            prevHead = oldHead.next;
                        }
                    }
                    // 如果个数小于 targetRecords，dropped为false
                    else {
                        dropped = false;
                    }
                    // 创建新的头节点，其中next节点为prevHead
                    newHead = hint != null ? new TraceRecord(prevHead, hint) : new TraceRecord(prevHead);
                    // 通过cas替换head指针指向新的头节点
                } while (!headUpdater.compareAndSet(this, oldHead, newHead));
                // 如果是替换了头节点，那么原本的头节点就被丢弃了，记录替换头节点的次数
                if (dropped) {
                    droppedRecordsUpdater.incrementAndGet(this);
                }
            }
        }

        boolean dispose() {
            // 将WeakReference中的referent属性置为null，如果referent已经被gc了话，该字段应该就是null
            clear();
            // 将自身从allLeaks这个set中删除
            return allLeaks.remove(this);
        }

        @Override
        public boolean close() {
            // 将当前这个tracker从allLeaks这个set中移除
            if (allLeaks.remove(this)) {
                // Call clear so the reference is not even enqueued.
                // 调用clear清除WeakReference对referent的引用，这样在referent被回收的时候，自身这个tracker作为WeakReference的实现类，
                // 就不会被添加进ReferenceQueue队列中了
                clear();
                // 并且将自身的head链表头置为null，帮助TraceRecord链表的gc
                headUpdater.set(this, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean close(T trackedObject) {
            // Ensure that the object that was tracked is the same as the one that was passed to close(...).
            // 判断传入的对象是否和tracker跟踪的对象一致
            assert trackedHash == System.identityHashCode(trackedObject);

            try {
                // 调用close方法
                return close();
            } finally {
                // This method will do `synchronized(trackedObject)` and we should be sure this will not cause deadlock.
                // It should not, because somewhere up the callstack should be a (successful) `trackedObject.release`,
                // therefore it is unreasonable that anyone else, anywhere, is holding a lock on the trackedObject.
                // (Unreasonable but possible, unfortunately.)
                // 这里只添加一个对trackedObject的synchronized是因为防止指令进行重排序，
                // 因为垃圾收集器可能在某个对象方法执行的时候就进行gc了，如果是这样的话，close方法可能会出现问题
                reachabilityFence0(trackedObject);
            }
        }

         /**
         * Ensures that the object referenced by the given reference remains
         * <a href="package-summary.html#reachability"><em>strongly reachable</em></a>,
         * regardless of any prior actions of the program that might otherwise cause
         * the object to become unreachable; thus, the referenced object is not
         * reclaimable by garbage collection at least until after the invocation of
         * this method.
         *
         * <p> Recent versions of the JDK have a nasty habit of prematurely deciding objects are unreachable.
         * see: https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
         * The Java 9 method Reference.reachabilityFence offers a solution to this problem.
         *
         * <p> This method is always implemented as a synchronization on {@code ref}, not as
         * {@code Reference.reachabilityFence} for consistency across platforms and to allow building on JDK 6-8.
         * <b>It is the caller's responsibility to ensure that this synchronization will not cause deadlock.</b>
         *
         * @param ref the reference. If {@code null}, this method has no effect.
         * @see java.lang.ref.Reference#reachabilityFence
         */
        private static void reachabilityFence0(Object ref) {
            if (ref != null) {
                synchronized (ref) {
                    // Empty synchronized is ok: https://stackoverflow.com/a/31933260/1151521
                }
            }
        }

        @Override
        public String toString() {
            TraceRecord oldHead = headUpdater.get(this);
            return generateReport(oldHead);
        }

        String getReportAndClearRecords() {
            // 获取TraceRecord链表的头节点，并且将头节点置为null
            TraceRecord oldHead = headUpdater.getAndSet(this, null);
            // 生成要报告的内容
            return generateReport(oldHead);
        }

        private String generateReport(TraceRecord oldHead) {
            if (oldHead == null) {
                // Already closed
                return EMPTY_STRING;
            }

            final int dropped = droppedRecordsUpdater.get(this);
            int duped = 0;

            // 计算链表中有多少个record
            int present = oldHead.pos + 1;
            // Guess about 2 kilobytes per stack trace
            // 假设一个record的stacktrace占用2kb
            StringBuilder buf = new StringBuilder(present * 2048).append(NEWLINE);
            buf.append("Recent access records: ").append(NEWLINE);

            int i = 1;
            Set<String> seen = new HashSet<String>(present);
            // 遍历record链表，直到record等于BOTTOM
            for (; oldHead != TraceRecord.BOTTOM; oldHead = oldHead.next) {
                // 调用record的toString方法打印出栈帧
                String s = oldHead.toString();
                // 将内容添加进set中
                if (seen.add(s)) {
                    // 如果链表中的下一个record是BOTTOM
                    if (oldHead.next == TraceRecord.BOTTOM) {
                        // 在buf中添加created at内容，表示该资源是在哪个位置创建的
                        buf.append("Created at:").append(NEWLINE).append(s);
                    } else {
                        // 其他情况只在栈帧前添加#i的编号
                        buf.append('#').append(i++).append(':').append(NEWLINE).append(s);
                    }
                }
                // 如果遇见相同的内容
                else {
                    // 将重复的次数+1
                    duped++;
                }
            }

            // 如果存在重复的次数
            if (duped > 0) {
                // 在最后添加内容显示有多少个record因为重复被丢弃了
                buf.append(": ")
                        .append(duped)
                        .append(" leak records were discarded because they were duplicates")
                        .append(NEWLINE);
            }

            // 如果dropped大于0，说明在记录record的时候存在丢弃的record
            if (dropped > 0) {
                // 那么添加内容说明有多少个record在记录时就因为targetRecords的限制被丢弃了
                buf.append(": ")
                   .append(dropped)
                   .append(" leak records were discarded because the leak record count is targeted to ")
                   .append(TARGET_RECORDS)
                   .append(". Use system property ")
                   .append(PROP_TARGET_RECORDS)
                   .append(" to increase the limit.")
                   .append(NEWLINE);
            }

            buf.setLength(buf.length() - NEWLINE.length());
            return buf.toString();
        }
    }

    private static final AtomicReference<String[]> excludedMethods =
            new AtomicReference<String[]>(EmptyArrays.EMPTY_STRINGS);

    public static void addExclusions(Class clz, String ... methodNames) {
        // 检查传入的methodNames是否都是clz里面的方法
        Set<String> nameSet = new HashSet<String>(Arrays.asList(methodNames));
        // Use loop rather than lookup. This avoids knowing the parameters, and doesn't have to handle
        // NoSuchMethodException.
        for (Method method : clz.getDeclaredMethods()) {
            if (nameSet.remove(method.getName()) && nameSet.isEmpty()) {
                break;
            }
        }
        if (!nameSet.isEmpty()) {
            throw new IllegalArgumentException("Can't find '" + nameSet + "' in " + clz.getName());
        }
        String[] oldMethods;
        String[] newMethods;
        do {
            oldMethods = excludedMethods.get();
            // 复制old数组，并且延长new数组的长度
            newMethods = Arrays.copyOf(oldMethods, oldMethods.length + 2 * methodNames.length);
            // 该数组内容两两为一组，第一个为类名，第二个为方法名
            for (int i = 0; i < methodNames.length; i++) {
                newMethods[oldMethods.length + i * 2] = clz.getName();
                newMethods[oldMethods.length + i * 2 + 1] = methodNames[i];
            }
        } while (!excludedMethods.compareAndSet(oldMethods, newMethods));
    }

    private static class TraceRecord extends Throwable {
        private static final long serialVersionUID = 6065153674892850720L;

        private static final TraceRecord BOTTOM = new TraceRecord() {
            private static final long serialVersionUID = 7396077602074694571L;

            // Override fillInStackTrace() so we not populate the backtrace via a native call and so leak the
            // Classloader.
            // See https://github.com/netty/netty/pull/10691
            @Override
            public Throwable fillInStackTrace() {
                return this;
            }
        };

        private final String hintString;
        private final TraceRecord next;
        private final int pos;

        TraceRecord(TraceRecord next, Object hint) {
            // This needs to be generated even if toString() is never called as it may change later on.
            hintString = hint instanceof ResourceLeakHint ? ((ResourceLeakHint) hint).toHintString() : hint.toString();
            this.next = next;
            this.pos = next.pos + 1;
        }

        TraceRecord(TraceRecord next) {
           hintString = null;
           this.next = next;
           this.pos = next.pos + 1;
        }

        // Used to terminate the stack
        private TraceRecord() {
            hintString = null;
            next = null;
            pos = -1;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(2048);
            // 如果存在hintString，在内容前添加Hint
            if (hintString != null) {
                buf.append("\tHint: ").append(hintString).append(NEWLINE);
            }

            // Append the stack trace.
            // 获取创建record时的栈帧信息
            StackTraceElement[] array = getStackTrace();
            // Skip the first three elements.
            // 跳过栈顶的前三个栈帧，因为前三个是跟ResourceLeakDetector相关的，不用展示
            out: for (int i = 3; i < array.length; i++) {
                StackTraceElement element = array[i];
                // Strip the noisy stack trace elements.
                // 获取应该排除的方法集合
                String[] exclusions = excludedMethods.get();
                // 如果栈帧的方法名命中了应该排除的方法，也跳过
                for (int k = 0; k < exclusions.length; k += 2) {
                    // Suppress a warning about out of bounds access
                    // since the length of excludedMethods is always even, see addExclusions()
                    if (exclusions[k].equals(element.getClassName())
                            && exclusions[k + 1].equals(element.getMethodName())) { // lgtm[java/index-out-of-bounds]
                        continue out;
                    }
                }

                // 否则将栈帧内容添加进buf中
                buf.append('\t');
                buf.append(element.toString());
                buf.append(NEWLINE);
            }
            return buf.toString();
        }
    }
}
