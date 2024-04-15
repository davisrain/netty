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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > run   - a run is a collection of pages
 * > chunk - a chunk is a collection of runs
 * > in this code chunkSize = maxPages * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#size2SizeIdx(int)} method.
 * This ensures that when we request for memory segments of size > pageSize the normalizedCapacity
 * equals the next nearest size in {@link SizeClasses}.
 *
 *
 *  A chunk has the following layout:
 *
 *     /-----------------\
 *     | run             |
 *     |                 |
 *     |                 |
 *     |-----------------|
 *     | run             |
 *     |                 |
 *     |-----------------|
 *     | unalloctated    |
 *     | (freed)         |
 *     |                 |
 *     |-----------------|
 *     | subpage         |
 *     |-----------------|
 *     | unallocated     |
 *     | (freed)         |
 *     | ...             |
 *     | ...             |
 *     | ...             |
 *     |                 |
 *     |                 |
 *     |                 |
 *     \-----------------/
 *
 *
 * handle:
 * -------
 * a handle is a long number, the bit layout of a run looks like:
 *
 * oooooooo ooooooos ssssssss ssssssue bbbbbbbb bbbbbbbb bbbbbbbb bbbbbbbb
 *
 * o: runOffset (page offset in the chunk), 15bit
 * s: size (number of pages) of this run, 15bit
 * u: isUsed?, 1bit
 * e: isSubpage?, 1bit
 * b: bitmapIdx of subpage, zero if it's not subpage, 32bit
 *
 * runsAvailMap:
 * ------
 * a map which manages all runs (used and not in used).
 * For each run, the first runOffset and last runOffset are stored in runsAvailMap.
 * key: runOffset
 * value: handle
 *
 * runsAvail:
 * ----------
 * an array of {@link PriorityQueue}.
 * Each queue manages same size of runs.
 * Runs are sorted by offset, so that we always allocate runs with smaller offset.
 *
 *
 * Algorithm:
 * ----------
 *
 *   As we allocate runs, we update values stored in runsAvailMap and runsAvail so that the property is maintained.
 *
 * Initialization -
 *  In the beginning we store the initial run which is the whole chunk.
 *  The initial run:
 *  runOffset = 0
 *  size = chunkSize
 *  isUsed = no
 *  isSubpage = no
 *  bitmapIdx = 0
 *
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) find the first avail run using in runsAvails according to size
 * 2) if pages of run is larger than request pages then split it, and save the tailing run
 *    for later using
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) find a not full subpage according to size.
 *    if it already exists just return, otherwise allocate a new PoolSubpage and call init()
 *    note that this subpage object is added to subpagesPool in the PoolArena when we init() it
 * 2) call subpage.allocate()
 *
 * Algorithm: [free(handle, length, nioBuffer)]
 * ----------
 * 1) if it is a subpage, return the slab back into this subpage
 * 2) if the subpage is not used or it is a run, then start free this run
 * 3) merge continuous avail runs
 * 4) save the merged run
 *
 */
final class PoolChunk<T> implements PoolChunkMetric {
    // handle的位组成
    // 表示run中存在多少个page
    private static final int SIZE_BIT_LENGTH = 15;
    // 表示是否被使用
    private static final int INUSED_BIT_LENGTH = 1;
    // 表示是否是subpage
    private static final int SUBPAGE_BIT_LENGTH = 1;
    // subpage的bitMap
    private static final int BITMAP_IDX_BIT_LENGTH = 32;

    // 判断 是否是subpage 需要右移的位数
    static final int IS_SUBPAGE_SHIFT = BITMAP_IDX_BIT_LENGTH;
    // 判断 是否使用 需要右移的位数
    static final int IS_USED_SHIFT = SUBPAGE_BIT_LENGTH + IS_SUBPAGE_SHIFT;
    // 判断 run中page的数量 需要右移的位数
    static final int SIZE_SHIFT = INUSED_BIT_LENGTH + IS_USED_SHIFT;
    // 判断 runOffset 需要右移的位数
    static final int RUN_OFFSET_SHIFT = SIZE_BIT_LENGTH + SIZE_SHIFT;

    final PoolArena<T> arena;
    final Object base;
    final T memory;
    final boolean unpooled;

    /**
     * store the first page and last page of each avail run
     */
    private final LongLongHashMap runsAvailMap;

    /**
     * manage all avail runs
     */
    private final LongPriorityQueue[] runsAvail;

    private final ReentrantLock runsAvailLock;

    /**
     * manage all subpages in this chunk
     */
    private final PoolSubpage<T>[] subpages;

    /**
     * Accounting of pinned memory – memory that is currently in use by ByteBuf instances.
     */
    private final LongCounter pinnedBytes = PlatformDependent.newLongCounter();

    private final int pageSize;
    private final int pageShifts;
    private final int chunkSize;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    @SuppressWarnings("unchecked")
    PoolChunk(PoolArena<T> arena, Object base, T memory, int pageSize, int pageShifts, int chunkSize, int maxPageIdx) {
        unpooled = false;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        // 初始状态，将空闲的字节数 设置为 chunkSize
        freeBytes = chunkSize;

        runsAvail = newRunsAvailqueueArray(maxPageIdx);
        // 创建一个用于runsAvail的锁
        runsAvailLock = new ReentrantLock();
        // 创建一个用于保存runsAvail的map
        runsAvailMap = new LongLongHashMap(-1);
        // 创建一个PoolSubpage类型的数组，长度为chunkSize / pageSize
        subpages = new PoolSubpage[chunkSize >> pageShifts];

        //insert initial run, offset = 0, pages = chunkSize / pageSize
        // 计算出chunkSize可以容纳多少个page
        int pages = chunkSize >> pageShifts;
        // 创建初始的run的handle，runOffset为0，包含了pages数量的page，因此handle为pages直接左移SIZE_SHIFT
        long initHandle = (long) pages << SIZE_SHIFT;
        // 将run添加进runsAvailMap中
        insertAvailRun(0, pages, initHandle);

        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, Object base, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.base = base;
        this.memory = memory;
        pageSize = 0;
        pageShifts = 0;
        runsAvailMap = null;
        runsAvail = null;
        runsAvailLock = null;
        subpages = null;
        chunkSize = size;
        cachedNioBuffers = null;
    }

    private static LongPriorityQueue[] newRunsAvailqueueArray(int size) {
        // 创建一个size长度的Long优先队列数组
        LongPriorityQueue[] queueArray = new LongPriorityQueue[size];
        // 为每个数组元素填充一个LongPriorityQueue
        for (int i = 0; i < queueArray.length; i++) {
            queueArray[i] = new LongPriorityQueue();
        }
        // 返回优先队列数组
        return queueArray;
    }

    private void insertAvailRun(int runOffset, int pages, long handle) {
        // 找到不大于pages总大小的那个sizeClass在pageIdx2SizeTab里面的下标
        int pageIdxFloor = arena.pages2pageIdxFloor(pages);
        // 获取pageIdxFloor对应的那个 优先队列
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        // 将handle存入优先队列中
        queue.offer(handle);

        //insert first page of run
        // 将第一个和最后一个page添加进初始的run中
        insertAvailRun0(runOffset, handle);
        if (pages > 1) {
            //insert last page of run
            insertAvailRun0(lastPage(runOffset, pages), handle);
        }
    }

    private void insertAvailRun0(int runOffset, long handle) {
        long pre = runsAvailMap.put(runOffset, handle);
        assert pre == -1;
    }

    private void removeAvailRun(long handle) {
        int pageIdxFloor = arena.pages2pageIdxFloor(runPages(handle));
        LongPriorityQueue queue = runsAvail[pageIdxFloor];
        removeAvailRun(queue, handle);
    }

    private void removeAvailRun(LongPriorityQueue queue, long handle) {
        // 将handle从优先队列中删除
        queue.remove(handle);

        // 计算当前可用run的offset，页的偏移量
        int runOffset = runOffset(handle);
        // 计算当前可用的run所包含的pages
        int pages = runPages(handle);
        //remove first page of run
        // 删除runsAvailMap中的可用run的第一个页所对应的k-v
        runsAvailMap.remove(runOffset);
        if (pages > 1) {
            //remove last page of run
            // 删除runsAvailMap中的可用run的最后一个页所对应的k-v
            runsAvailMap.remove(lastPage(runOffset, pages));
        }
    }

    private static int lastPage(int runOffset, int pages) {
        return runOffset + pages - 1;
    }

    private long getAvailRunByOffset(int runOffset) {
        return runsAvailMap.get(runOffset);
    }

    @Override
    public int usage() {
        final int freeBytes;
        arena.lock();
        try {
            freeBytes = this.freeBytes;
        } finally {
            arena.unlock();
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache cache) {
        final long handle;
        // 如果sizeIdx小于等于arena中的smallMaxSizeIdx，即log2Size小于pageShifts(13) + 2的那些sizeClass中最大的sizeIdx
        if (sizeIdx <= arena.smallMaxSizeIdx) {
            // small
            // 分配small类型的内存
            handle = allocateSubpage(sizeIdx);
            // 如果handle小于0，说明分配失败，返回false
            if (handle < 0) {
                return false;
            }
            // 判断handle中的subpage标识为1
            assert isSubpage(handle);
        }
        // 如果不是small类型的，那么必定是pageSize的倍数，因为非subpage的那些sizeClass的log2Delta已经大于等于pageShifts了
        else {
            // normal
            // runSize must be multiple of pageSize
            // 根据sizeIdx获取到对应的sizeClass的size，作为runSize
            int runSize = arena.sizeIdx2size(sizeIdx);
            // 分配normal类型的内存
            handle = allocateRun(runSize);
            // 如果handle小于0，说明分配失败，返回false
            if (handle < 0) {
                return false;
            }
            // 判断handle中的subpage标识不为1
            assert !isSubpage(handle);
        }

        // 如果缓存的nioBuffers不为null的话，获取队尾的元素
        ByteBuffer nioBuffer = cachedNioBuffers != null? cachedNioBuffers.pollLast() : null;
        // 调用initBuf对PooledByteBuf进行初始化
        initBuf(buf, nioBuffer, handle, reqCapacity, cache);
        return true;
    }

    private long allocateRun(int runSize) {
        // 计算runSize是pageSize的多少倍，即包含的page的数量
        int pages = runSize >> pageShifts;
        // 计算出pages数量的页对应的pageIdx
        int pageIdx = arena.pages2pageIdx(pages);

        runsAvailLock.lock();
        try {
            //find first queue which has at least one big enough run
            // 找到第一个有足够空间的run的队列下标
            int queueIdx = runFirstBestFit(pageIdx);
            if (queueIdx == -1) {
                return -1;
            }

            //get run with min offset in this queue
            // 获取queueIdx对应的优先队列
            LongPriorityQueue queue = runsAvail[queueIdx];
            // 然后获取优先队列里面的第一个元素，即handle的最小值，因为runOffset处于handle里面的高位，
            // 因此获取到的是runOffset最小的那个handle
            long handle = queue.poll();

            // 确保handle不为-1且handle的used标识为0
            assert handle != LongPriorityQueue.NO_VALUE && !isUsed(handle) : "invalid handle: " + handle;

            // 将handle从优先队列中删除
            removeAvailRun(queue, handle);

            if (handle != -1) {
                // 将largeRun进行分割
                handle = splitLargeRun(handle, pages);
            }

            // 根据handle计算出占用的runSize
            int pinnedSize = runSize(pageShifts, handle);
            // 将占用的runSize从空闲字节中删除
            freeBytes -= pinnedSize;
            return handle;
        } finally {
            runsAvailLock.unlock();
        }
    }

    private int calculateRunSize(int sizeIdx) {
        // 获取最大的元素个数
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        int runSize = 0;
        int nElements;

        // 根据sizeIdx获取size，作为单个元素的size
        final int elemSize = arena.sizeIdx2size(sizeIdx);

        //find lowest common multiple of pageSize and elemSize
        // 找到pageSize 和 elemSize的最小公倍数，作为runSize
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        // 当runSize所包含的元素个数大于了最大元素个数，将runSize以pageSize为单位缩小，并且计算对应的元素个数
        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }

        // 检验元素个数大于0，runSize小于等于chunkSize，runSize大于等于elemSize
        assert nElements > 0;
        assert runSize <= chunkSize;
        assert runSize >= elemSize;

        // 返回runSize
        return runSize;
    }

    private int runFirstBestFit(int pageIdx) {
        // 如果chunk的空闲字节 就等同于chunkSize
        if (freeBytes == chunkSize) {
            // 直接返回nPSizes - 1
            return arena.nPSizes - 1;
        }
        // 否则以pageIdx作为起点，遍历runsAvail数组
        for (int i = pageIdx; i < arena.nPSizes; i++) {
            LongPriorityQueue queue = runsAvail[i];
            // 如果存在某个优先队列不为null且不为空，返回i
            if (queue != null && !queue.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private long splitLargeRun(long handle, int needPages) {
        assert needPages > 0;

        // 获取handle中所包含的pages数量
        int totalPages = runPages(handle);
        // 确认需要的page数量小于等于总的page数量
        assert needPages <= totalPages;

        // 计算出剩余的page数量
        int remPages = totalPages - needPages;

        // 如果剩余的page数量大于0
        if (remPages > 0) {
            // 获取原本handle的runOffset
            int runOffset = runOffset(handle);

            // keep track of trailing unused pages for later use
            // 计算出可用的runOffset的值
            int availOffset = runOffset + needPages;
            // 将可用的runOffset、剩余的page数量作为参数，构建剩余的可用runHandle的值
            long availRun = toRunHandle(availOffset, remPages, 0);
            // 然后将可用的runHandle插入到优先队列中
            insertAvailRun(availOffset, remPages, availRun);

            // not avail
            // 根据runOffset和需要的page数量，构建出占用的handle返回
            return toRunHandle(runOffset, needPages, 1);
        }

        //mark it as used
        // 如果剩余的page数量为0的话，那么将handle的isUsed标识设置为1
        handle |= 1L << IS_USED_SHIFT;
        return handle;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity. Any PoolSubpage created / initialized here is added to
     * subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param sizeIdx sizeIdx of normalized size
     *
     * @return index in memoryMap
     */
    private long allocateSubpage(int sizeIdx) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 根据sizeIdx获取arena的PoolSubpage数组对应下标的PoolSubpage对象，即subpage链表的头节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);
        // 并且对头节点加锁，加锁的原因是我们可能会对链表结构做修改
        head.lock();
        try {
            //allocate a new run
            // 计算出runSize
            int runSize = calculateRunSize(sizeIdx);
            //runSize must be multiples of pageSize
            // 分配run类型的内存
            long runHandle = allocateRun(runSize);
            // 如果handle小于0，说明分配失败，返回-1；
            if (runHandle < 0) {
                return -1;
            }

            // 获取handle中的runOffset
            int runOffset = runOffset(runHandle);
            // 确保runOffset对应的subpage数组中的元素为null
            assert subpages[runOffset] == null;
            // 根据sizeIdx获取对应的size大小
            int elemSize = arena.sizeIdx2size(sizeIdx);

            // 创建一个PoolSubpage对象
            PoolSubpage<T> subpage = new PoolSubpage<T>(head, this, pageShifts, runOffset,
                               runSize(pageShifts, runHandle), elemSize);

            // 将其放入subpages数组runOffset下标对应的位置
            subpages[runOffset] = subpage;
            // 调用subpage的allocate方法
            return subpage.allocate();
        } finally {
            head.unlock();
        }
    }

    /**
     * Free a subpage or a run of pages When a subpage is freed from PoolSubpage, it might be added back to subpage pool
     * of the owning PoolArena. If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize,
     * we can completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, int normCapacity, ByteBuffer nioBuffer) {
        int runSize = runSize(pageShifts, handle);
        if (isSubpage(handle)) {
            int sizeIdx = arena.size2SizeIdx(normCapacity);
            PoolSubpage<T> head = arena.findSubpagePoolHead(sizeIdx);

            int sIdx = runOffset(handle);
            PoolSubpage<T> subpage = subpages[sIdx];

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            head.lock();
            try {
                assert subpage != null && subpage.doNotDestroy;
                if (subpage.free(head, bitmapIdx(handle))) {
                    //the subpage is still used, do not free it
                    return;
                }
                assert !subpage.doNotDestroy;
                // Null out slot in the array as it was freed and we should not use it anymore.
                subpages[sIdx] = null;
            } finally {
                head.unlock();
            }
        }

        //start free run
        runsAvailLock.lock();
        try {
            // collapse continuous runs, successfully collapsed runs
            // will be removed from runsAvail and runsAvailMap
            long finalRun = collapseRuns(handle);

            //set run as not used
            finalRun &= ~(1L << IS_USED_SHIFT);
            //if it is a subpage, set it to run
            finalRun &= ~(1L << IS_SUBPAGE_SHIFT);

            insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun);
            freeBytes += runSize;
        } finally {
            runsAvailLock.unlock();
        }

        if (nioBuffer != null && cachedNioBuffers != null &&
            cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    private long collapseRuns(long handle) {
        return collapseNext(collapsePast(handle));
    }

    private long collapsePast(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long pastRun = getAvailRunByOffset(runOffset - 1);
            if (pastRun == -1) {
                return handle;
            }

            int pastOffset = runOffset(pastRun);
            int pastPages = runPages(pastRun);

            //is continuous
            if (pastRun != handle && pastOffset + pastPages == runOffset) {
                //remove past run
                removeAvailRun(pastRun);
                handle = toRunHandle(pastOffset, pastPages + runPages, 0);
            } else {
                return handle;
            }
        }
    }

    private long collapseNext(long handle) {
        for (;;) {
            int runOffset = runOffset(handle);
            int runPages = runPages(handle);

            long nextRun = getAvailRunByOffset(runOffset + runPages);
            if (nextRun == -1) {
                return handle;
            }

            int nextOffset = runOffset(nextRun);
            int nextPages = runPages(nextRun);

            //is continuous
            if (nextRun != handle && runOffset + runPages == nextOffset) {
                //remove next run
                removeAvailRun(nextRun);
                handle = toRunHandle(runOffset, runPages + nextPages, 0);
            } else {
                return handle;
            }
        }
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) runPages << SIZE_SHIFT
               | (long) inUsed << IS_USED_SHIFT;
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                 PoolThreadCache threadCache) {
        // 判断handle是否是subpage
        if (isSubpage(handle)) {
            // 如果是，调用initBufWithSubpage方法
            initBufWithSubpage(buf, nioBuffer, handle, reqCapacity, threadCache);
        } else {
            // 如果不是subpage，那么说明一次性分配run类型的内存，计算runSize
            int maxLength = runSize(pageShifts, handle);
            // 计算出要使用的内存在chunk持有的内存中的偏移量offset，计算出实际要分配的内存大小maxLength，
            // 调用init方法初始化ByteBuf
            buf.init(this, nioBuffer, handle, runOffset(handle) << pageShifts,
                    reqCapacity, maxLength, arena.parent.threadCache());
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity,
                            PoolThreadCache threadCache) {
        // 获取handle中的runOffset
        int runOffset = runOffset(handle);
        // 获取handle中的bitmapIdx
        int bitmapIdx = bitmapIdx(handle);

        // 获取runOffset对应的PoolSubpage
        PoolSubpage<T> s = subpages[runOffset];
        // 判断subpage没有被销毁
        assert s.doNotDestroy;
        // 并且请求的容量 小于等于 subpage的elemSize
        assert reqCapacity <= s.elemSize : reqCapacity + "<=" + s.elemSize;

        // 计算实际的内存偏移量 = 页的内存偏移量 + elem的内存偏移量
        int offset = (runOffset << pageShifts) + bitmapIdx * s.elemSize;
        // 调用buf的init方法，将chunk、实际的内偏移量、nioBuffer、handle、请求的容量、elemSize、threadCache都传入
        buf.init(this, nioBuffer, handle, offset, reqCapacity, s.elemSize, threadCache);
    }

    void incrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(delta);
    }

    void decrementPinnedMemory(int delta) {
        assert delta > 0;
        pinnedBytes.add(-delta);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        arena.lock();
        try {
            return freeBytes;
        } finally {
            arena.unlock();
        }
    }

    public int pinnedBytes() {
        return (int) pinnedBytes.value();
    }

    @Override
    public String toString() {
        final int freeBytes;
        arena.lock();
        try {
            freeBytes = this.freeBytes;
        } finally {
            arena.unlock();
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }

    static int runOffset(long handle) {
        return (int) (handle >> RUN_OFFSET_SHIFT);
    }

    static int runSize(int pageShifts, long handle) {
        return runPages(handle) << pageShifts;
    }

    static int runPages(long handle) {
        return (int) (handle >> SIZE_SHIFT & 0x7fff);
    }

    static boolean isUsed(long handle) {
        return (handle >> IS_USED_SHIFT & 1) == 1L;
    }

    static boolean isRun(long handle) {
        return !isSubpage(handle);
    }

    static boolean isSubpage(long handle) {
        return (handle >> IS_SUBPAGE_SHIFT & 1) == 1L;
    }

    static int bitmapIdx(long handle) {
        return (int) handle;
    }
}
