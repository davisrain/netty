/*
 * Copyright 2020 The Netty Project
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

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxClass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    // 每组个数的log2的值，2表示每组有2的2次方个sizeClass
    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    // 表示属于lookup的sizeClass的最大内存值的log2，12表示 内存大小 小于等于2的12次方的sizeClass都属于lookup
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected final int pageSize;
    protected final int pageShifts;
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    final int nSubpages;
    final int nPSizes;
    final int lookupMaxSize;
    final int smallMaxSizeIdx;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxClass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxClass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        // 默认的chunkSize是4mb = 2的22次方，22 + 1 - 4 = 19，因此默认的group为19
        int group = log2(chunkSize) + 1 - LOG2_QUANTUM;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        short[][] sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];

        int normalMaxSize = -1;
        int nSizes = 0;
        int size = 0;

        // 表示组的内存大小的log2，quantum = 4，因此第0组中的所有sizeClass的baseSize为1<<4=16
        int log2Group = LOG2_QUANTUM;
        // 表示当前sizeClass和上一个sizeClass差值的log2，quantum = 4，因此第0组中的sizeClass同前一个sizeClass内存相差1<<4=16
        int log2Delta = LOG2_QUANTUM;
        // 表示每个组中的sizeClass与组的baseSize差几倍的delta，这个等同于每组的个数，1<<2=4
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        // 第0组的nDelta是从0开始的，并且第0组的baseSize是1<<4 = 16
        for (int nDelta = 0; nDelta < ndeltaLimit; nDelta++, nSizes++) {
            // 根据各个参数生成一个sizeClass的short数组
            short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
            // 然后放入sizeClasses的nSizes下标位置，nSizes即为index
            sizeClasses[nSizes] = sizeClass;
            // 计算出sizeClass对应的内存大小size
            size = sizeOf(sizeClass, directMemoryCacheAlignment);
        }

        // 将log2Group加上LOG2_SIZE_CLASS_GROUP，默认为4+2=6
        log2Group += LOG2_SIZE_CLASS_GROUP;

        //All remaining groups, nDelta start at 1.
        // 以log2Group为6 log2Delta为4开始遍历，直到size大于等于chunkSize
        for (; size < chunkSize; log2Group++, log2Delta++) {
            // nDelta从1开始
            for (int nDelta = 1; nDelta <= ndeltaLimit && size < chunkSize; nDelta++, nSizes++) {
                // 根据参数创建出sizeClass
                short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
                // 将sizeClass设置进sizeClasses的nSizes下标
                sizeClasses[nSizes] = sizeClass;
                // 计算sizeClass对应的内存大小size，然后赋值给normalMaxSize以及size变量
                size = normalMaxSize = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        int smallMaxSizeIdx = 0;
        int lookupMaxSize = 0;
        int nPSizes = 0;
        int nSubpages = 0;
        // 从0开始遍历到nSizes
        for (int idx = 0; idx < nSizes; idx++) {
            // 获取对应index的sizeClass
            short[] sz = sizeClasses[idx];
            // 如果sizeClass的isMultiPageSize标识为yes，将nPSizes+1
            if (sz[PAGESIZE_IDX] == yes) {
                nPSizes++;
            }
            // 如果sizeClass的isSubpage的标识为yes，将nSubpages+1，并且将smallMaxSizeIdx设置为当前index
            if (sz[SUBPAGE_IDX] == yes) {
                nSubpages++;
                smallMaxSizeIdx = idx;
            }
            // 如果sizeClass的log2DeltaLookup不为no，将lookupMaxSize设置为当前sizeClass计算出来的内存大小
            if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
                lookupMaxSize = sizeOf(sz, directMemoryCacheAlignment);
            }
        }
        // 将计算出来的各个参数赋值给SizeClasses的属性
        this.smallMaxSizeIdx = smallMaxSizeIdx;
        this.lookupMaxSize = lookupMaxSize;
        this.nPSizes = nPSizes;
        this.nSubpages = nSubpages;
        this.nSizes = nSizes;

        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        //generate lookup tables
        // 创建sizeIndex 对应 内存大小size的数组，赋值给sizeClasses的sizeIdx2SizeTab属性
        sizeIdx2sizeTab = newIdx2SizeTab(sizeClasses, nSizes, directMemoryCacheAlignment);
        // 创建isMultiPageSize标识为true的那些sizeClass对应的内存大小size 的数组，赋值给sizeClasses的pageIdx2SizeTab属性
        pageIdx2sizeTab = newPageIdx2sizeTab(sizeClasses, nSizes, nPSizes, directMemoryCacheAlignment);
        //
        size2idxTab = newSize2idxTab(lookupMaxSize, sizeClasses);
    }

    //calculate size class
    private static short[] newSizeClass(int index, int log2Group, int log2Delta, int nDelta, int pageShifts) {
        short isMultiPageSize;
        // 如果log2Delta都已经大于等于pageShifts了，那么一定是multiPageSize，将isMultiPageSize设置为yes
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        }
        //  否则，计算出pageSize
        else {
            int pageSize = 1 << pageShifts;
            // 然后算出当前sizeClass的size，公式为 1 << log2Group + nDelta * (1 << log2Delta)
            int size = calculateSize(log2Group, nDelta, log2Delta);

            // 如果size是pageSize的整数倍，设置为yes，否则设置为no
            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        //  计算log2Ndelta的值
        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        // 如果1 << log2Ndelta的值小于nDelta的话，将remove标记为yes，否则为no
        // 这句的含义就是 nDelta不是2的幂次方的，返回yes，否则返回no。
        // 比如nDelta为1的时候，是2的0次方，1 << 0 == 1 no
        // nDelta为2的时候，是2的1次方，1 << 1 == 2 no
        // nDelta为4的时候，是2的2次方，1 << 2 == 4 no
        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        // 计算log2Size，如果log2Delta加上log2Ndelta等于log2Group的话，
        // 说明sizeClass减去group的baseSize已经等于group的baseSize了，即二倍的baseSize，因此将log2Group + 1;
        // 否则还是返回log2Group
        int log2Size = log2Delta + log2Ndelta == log2Group ? log2Group + 1 : log2Group;
        // 如果log2Size等于log2Group，将remove设置为yes
        // 这句的含义就是，当sizeClass对应的size没有超过当前group的baseSize两倍的时候，置为yes
        // 那么当nDelta为1、2、3的时候，都没有超过，remove为yes
        // 因此只有当nDelta为4的时候，sizeClass的内存大小是当前group的baseSize的两倍，remove为no
        if (log2Size == log2Group) {
            remove = yes;
        }

        // 所以，通过上面的条件，可以判断出remove为no的只有每组nDelta为4的sizeClass

        // 如果log2Size 小于 pageShifts + LOG2_SIZE_CLASS_GROUP(2)，将isSubPage设置为yes，否则为no
        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        // 如果log2Size小于LOG2_MAX_LOOKUP_SIZE(默认为12) 或者 log2Size等于LOG2_MAX_LOOKUP_SIZE且remove为no，
        // 将log2DeltaLoopup设置为log2Delta，否则为no
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        // 然后根据上面所计算的这些参数，创建一个short类型数组作为sizeClass返回
        return new short[] {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };
    }

    private static int[] newIdx2SizeTab(short[][] sizeClasses, int nSizes, int directMemoryCacheAlignment) {
        int[] sizeIdx2sizeTab = new int[nSizes];

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            sizeIdx2sizeTab[i] = sizeOf(sizeClass, directMemoryCacheAlignment);
        }
        return sizeIdx2sizeTab;
    }

    private static int calculateSize(int log2Group, int nDelta, int log2Delta) {
        return (1 << log2Group) + (nDelta << log2Delta);
    }

    private static int sizeOf(short[] sizeClass, int directMemoryCacheAlignment) {
        int log2Group = sizeClass[LOG2GROUP_IDX];
        int log2Delta = sizeClass[LOG2DELTA_IDX];
        int nDelta = sizeClass[NDELTA_IDX];

        // 计算出sizeClass对应的内存大小size
        int size = calculateSize(log2Group, nDelta, log2Delta);

        // 将内存对齐之后返回
        return alignSizeIfNeeded(size, directMemoryCacheAlignment);
    }

    private static int[] newPageIdx2sizeTab(short[][] sizeClasses, int nSizes, int nPSizes,
                                            int directMemoryCacheAlignment) {
        // 根据nPSizes作为长度创建一个数组
        int[] pageIdx2sizeTab = new int[nPSizes];
        int pageIdx = 0;
        for (int i = 0; i < nSizes; i++) {
            // 遍历sizeClasses，找到是isMultiPageSize为yes的那些sizeClass，计算其内存大小size，然后存入到数组中
            short[] sizeClass = sizeClasses[i];
            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }
        return pageIdx2sizeTab;
    }

    private static int[] newSize2idxTab(int lookupMaxSize, short[][] sizeClasses) {
        // 根据lookupMaxSize右移LOG2_QUANTUM作为长度，创建一个数组。
        // sizeClass的内存大小一定是 1<<LOG2_QUANTUM(1<<4 = 16) 的倍数，因为log2Delta是以LOG2_QUANTUM开始的，
        // 所以以size作为下标的时候，可以直接右移LOG2_QUANTUM位。即每增加一个下标，表示内存大小增加1<<LOG2_QUANTUM(1<<4 = 16)
        // lookupMaxSize默认为2的12次方，LOG2_QUANTUM为4，那么数组长度为2的8次方
        int[] size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        int idx = 0;
        int size = 0;

        // 遍历，当size小于lookupMaxSize的时候
        for (int i = 0; size <= lookupMaxSize; i++) {
            // 获取对应sizeClass的log2Delta
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            // log2Delta - LOG2_QUANTUM，然后让1左移这么多位，作为times。
            // 这句的含义是 当前i下标对应的 sizeClass 比 上一个sizeClass 多了times个 1 << LOG2_QUANTUM(1 << 4 = 16) 的内存。
            // 由于多了times个16的内存，因此需要分配times个size2idxTab的下标给(每个size2idxTab的下标代表16字节内存) i这个下标的sizeClass，处于这个区间的内存都会分配i下标对应的sizeClass的size内存大小。
            int times = 1 << log2Delta - LOG2_QUANTUM;

            // 第0组的时候 log2Delta为4，那么times为1，执行循环
            // 第1组的时候，log2Delta为4，那么times为1，执行循环
            // 第2组的时候，log2Delta为5，那么times为2，执行循环
            while (size <= lookupMaxSize && times-- > 0) {
                // 第0组的情况:
                // times = 1，idx = 0, i = 0, size = 16;
                // times = 1, idx = 1, i = 1, size = 32;
                // times = 1, idx = 2, i = 2, size = 48;
                // times = 1, idx = 3, i = 3, size = 64;

                // 第1组的情况:
                // times = 1, idx = 4，i = 4, size = 80;
                // times = 1, idx = 5, i = 5, size = 96;
                // times = 1, idx = 6, i = 6, size = 112;
                // times = 1, idx = 7, i = 7, size = 128;

                // 第2组的情况：times为2，因此每个i对应的sizeClass需要对应2个size2idxTab的下标
                // times = 2, idx = 8, i = 8, size = 144;
                // times = 1, idx = 9, i = 8, size = 160;
                // times = 2, idx = 10, i = 9, size = 176;
                // times = 1, idx = 11, i = 9, size = 192;
                // times = 2, idx = 12, i = 10, size = 208;
                // times = 1, idx = 13, i = 10, size = 224;
                // times = 2, idx = 14, i = 11, size = 240;
                // times = 1, idx = 15, i = 11, size = 256;
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
        return size2idxTab;
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    @Override
    public int size2SizeIdx(int size) {
        // 如果size等于0，直接返回index为0
        if (size == 0) {
            return 0;
        }
        // 如果size大于了chunkSize，默认4mb，直接返回nSizes，即sizeClass的数量
        if (size > chunkSize) {
            return nSizes;
        }

        // 将size同alignment对齐
        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);

        // 如果size小于等于lookupMaxSize，默认是2的12次方，即4096字节，那么直接从size2idxTab中获取对应的sizeClass的索引
        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            // 将size - 1 然后向右移LOG2_QUANTUM位，因为sizeClass中的size大小都是1 << LOG2_QUANTUM的倍数，
            // 因此size2idxTab中的下标都是size除以了2的LOG2_QUANTUM次方的，这样可以节省数组空间。
            // 将size-1的目的是数组的下标是从0开始的，因此等于1 << LOG2_QUANTUM大小的内存应该在0下标
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        // 将size左移1位 - 1，再取log2，获取的是size所在组的log2Group + 1，即size所在group的最后一个sizeClass。
        // 比如size = 2^12 + y(y <= 2^12)，log2Group = 12;
        // size << 1 = 2^13 + 2y(2y <= 2^13);
        // 对(size << 1) - 1取log2，等于13 = log2Group + 1。
        // 这里-1的作用是防止size正好是group的最后一个sizeClass，那么获取到的x就等于log2Group + 2了。
        int x = log2((size << 1) - 1);
        // 如果x小于LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1(=7)，说明size小于第1组，那么只能在第0组了；
        // 否则的话，将x减去LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM(=6)，得到size所在的组的序号。
        // 即shift是size所在组的序号
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        // 计算出size所在组的index，当shift为0时，位于第0组，那么起始的sizeClass的index就为0；
        // 以此类推，当shift为1时，位于第1组，那么第1组起始的sizeClass的index为4...
        int group = shift << LOG2_SIZE_CLASS_GROUP;

        // 如果x小于LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1，说明在第0组，第0组的log2Delta等于LOG2_QUANTUM(4);
        // 否则，其他组的log2Delta = log2Group - LOG2_SIZE_CLASS_GROUP(2) = x - 1 - LOG2_SIZE_CLASS_GROUP(2)。
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        // 获取delta对应的mask，比如log2Delta等于4，那么mask的二进制就等于1111...110000
        int deltaInverseMask = -1 << log2Delta;
        // 将size - 1 同mask进行位与操作，然后再右移log2Delta位，就等于计算出size至少包含多少个delta。
        // 然后将其与(1<<LOG2_SIZE_CLASS_GROUP) - 1 = 3 位与，就得到了在当前group中，size相对于group的baseSize偏移了多少个delta。
        // 将size - 1的目的也是为了防止size就是group中最后一个sizeClass，导致mod计算出来为0。
        int mod = (size - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        // 将group + mod，就是size应该分配的sizeClass的下标indexß
        return group + mod;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pageSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaInverseMask = -1 << log2Delta;
        int mod = (pageSize - 1 & deltaInverseMask) >> log2Delta &
                  (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private static int alignSizeIfNeeded(int size, int directMemoryCacheAlignment) {
        // 如果缓存对齐小于等于0，直接返回size
        if (directMemoryCacheAlignment <= 0) {
            return size;
        }
        // 计算size同alignment未对齐的值delta
        int delta = size & directMemoryCacheAlignment - 1;
        // 如果delta等于0，说明是对齐的，直接返回size；否则将size加上alignment再减去delta
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);
        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
