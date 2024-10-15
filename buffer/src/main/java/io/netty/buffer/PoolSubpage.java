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

import java.util.concurrent.locks.ReentrantLock;

import static io.netty.buffer.PoolChunk.RUN_OFFSET_SHIFT;
import static io.netty.buffer.PoolChunk.SIZE_SHIFT;
import static io.netty.buffer.PoolChunk.IS_USED_SHIFT;
import static io.netty.buffer.PoolChunk.IS_SUBPAGE_SHIFT;
import static io.netty.buffer.SizeClasses.LOG2_QUANTUM;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    final int elemSize;
    private final int pageShifts;
    private final int runOffset;
    private final int runSize;
    private final long[] bitmap;

    // 整个PoolSubpage链表是维护在对应arena的PoolSubpage数组里面的，数组是根据sizeIdx作为下标的，
    // 也就是说数组的每个元素都是一个PoolSubpage链表的头节点，该链表里面的PoolSubpage都是elemSize是sizeIdx的那些PoolSubpage

    // PoolSubpage链表的前置节点
    PoolSubpage<T> prev;
    // PoolSubpage链表的后置节点
    PoolSubpage<T> next;

    boolean doNotDestroy;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    private final ReentrantLock lock = new ReentrantLock();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage() {
        chunk = null;
        pageShifts = -1;
        runOffset = -1;
        elemSize = -1;
        runSize = -1;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int pageShifts, int runOffset, int runSize, int elemSize) {
        // 设置自身chunk
        this.chunk = chunk;
        // 设置pageShifts
        this.pageShifts = pageShifts;
        // 设置runOffset，即这个PoolSubpage所代表的run内存在PoolChunk持有的内存中开始页的偏移量
        this.runOffset = runOffset;
        // 设置runSize，这个PoolSubpage所持有的内存大小
        this.runSize = runSize;
        // 设置elemSize，这个PoolSubpage每次分配的内存大小
        this.elemSize = elemSize;
        // 创建一个long类型的数组作为bitmap，length为runSize无符号右移6 + LOG2_QUANTUM(4)位
        // 因为sizeClasses中的sizeClass的size大小都是2^LOG2_QUANTUM的倍数，因此可以直接除以2^LOG2_QUANTUM的大小；
        // 又因为long是64位，除以64表示需要多少个long来表示内存的位图，即long数组的长度。

        // 这个bitmap中的所有long代表了一个内存位图，每一位代表的是16字节的内存，也就是 1 << LOG2_QUANTUM大小的内存
        // 这里以16字节作为单位是因为每次分配的一定是大于等于16字节的，因此计算出的long数组长度能够满足所有场景。
        // 而实际要用到多少长度的long数组，会由下面的bitmapLength计算得出
        bitmap = new long[runSize >>> 6 + LOG2_QUANTUM]; // runSize / 64 / QUANTUM

        // 设置doNotDestroy为true
        doNotDestroy = true;
        // 如果elemSize不为0
        if (elemSize != 0) {
            // 计算当前runSize包含elemSiz的数量，即这个PoolSubpage能够分配多少个elemSize大小的内存
            // 并且赋值给numAvail，表示初始状态下能分配的elemSize内存的数量
            maxNumElems = numAvail = runSize / elemSize;
            // 将下一个可用的elem的下标设置为0
            nextAvail = 0;
            // bitmap的长度为 最大的elem数量右移6位，代表要需要多少个long来表示elemSize的位图
            // 即maxNumElems / 64，表示的是如果long的每一位代表一个elemSize大小的内存，需要用多少个long来表示
            bitmapLength = maxNumElems >>> 6;
            // 如果maxNumElems位与63不为0的话，说明elem的数量不是64的整数倍，因此需要添加一个long来表示位图，将bitmapLength + 1
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }
        }
        // 将自身添加进PoolSubpage链表中，采用的是头插法，并且head头节点是保持不变的
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // 如果可用数量为0 或者 doNotDestroy为false的话，直接返回-1，表示分配失败
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获取到下一个可用的elem的位图index
        final int bitmapIdx = getNextAvail();
        // 如果bitmapIdx小于0
        if (bitmapIdx < 0) {
            // 将PoolSubpage从内存池中删除，避免重复报错
            removeFromPool(); // Subpage appear to be in an invalid state. Remove to prevent repeated errors.
            throw new AssertionError("No next available bitmap index found (bitmapIdx = " + bitmapIdx + "), " +
                    "even though there are supposed to be (numAvail = " + numAvail + ") " +
                    "out of (maxNumElems = " + maxNumElems + ") available indexes.");
        }
        // 获取bitmapIdx在bitmap数组中的下标
        int q = bitmapIdx >>> 6;
        // 获取bitmapIdx在long中的位置
        int r = bitmapIdx & 63;
        // 确保对应的位上面的值是0
        assert (bitmap[q] >>> r & 1) == 0;
        // 将bitmap中对应的位设置为1，表示占用
        bitmap[q] |= 1L << r;

        // 将numAvail - 1，并且判断如果等于0了，说明当前PoolSubpage对应的run已经用完了，将其从内存池中删除
        // 即从arena的PoolSubpage数组里维护的链表中删除
        if (-- numAvail == 0) {
            removeFromPool();
        }

        // 根据bitmapIdx生成handle返回
        // 根据runOffset pages isUsed isSubpage bitmapIdx生成一个handle返回
        // 其中isUsed和isSubpage固定为1
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 如果elemSize为0，直接返回true
        if (elemSize == 0) {
            return true;
        }
        // 根据bitmapIdx找到位图中的位置
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        // 判断对应位为1，表示是被占用的
        assert (bitmap[q] >>> r & 1) != 0;
        // 将位图上对应的位设置为0
        bitmap[q] ^= 1L << r;

        // 设置下一个可用的位置为刚才释放的位置
        setNextAvail(bitmapIdx);

        // 将PoolSubpage里面的可分配的elemSize个数 + 1
        // 如果之前可用数量为0，那么现在有可用空间了，因此需要添加进arena的对应sizeIdx的PoolSubpage的链表中
        if (numAvail ++ == 0) {
            addToPool(head);
            /* When maxNumElems == 1, the maximum numAvail is also 1.
             * Each of these PoolSubpages will go in here when they do free operation.
             * If they return true directly from here, then the rest of the code will be unreachable
             * and they will not actually be recycled. So return true only on maxNumElems > 1. */
            // 当maxNumElems > 1的时候，直接返回true
            if (maxNumElems > 1) {
                return true;
            }
        }

        // 如果可用数量 和 最大的elem的数量不相等的时候，返回true
        if (numAvail != maxNumElems) {
            return true;
        }
        // 否则，当可用数量就等于最大的elem数量的时候，说明该PoolSubpage的内存被全部释放，没有任何占用
        else {
            // Subpage not in use (numAvail == maxNumElems)
            // 如果prev等于next的时候，说明当前的PoolSubpage是链表里面唯一一个，那么直接返回true，不进行删除
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            // 将doNotDestroy设置为false
            doNotDestroy = false;
            // 将其从链表里面删除，因为链表里面存在其他PoolSubpage可以使用，然后返回false
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // 如果nextAvail大于等于0
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            // 将自身的nextAvail设置为-1，返回nextAvail
            this.nextAvail = -1;
            return nextAvail;
        }
        // 否则调用findNextAvail进行查找
        return findNextAvail();
    }

    private int findNextAvail() {
        // 获取自身持有的位图
        final long[] bitmap = this.bitmap;
        // 获取位图对应的长度
        final int bitmapLength = this.bitmapLength;
        // 遍历位图
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            // 如果将位图取反不为0，说明有未被占用的elem
            if (~bits != 0) {
                // 调用findNextAvail0找到未被占用的elem在位图中的index返回
                return findNextAvail0(i, bits);
            }
        }
        // 如果没找到，返回-1
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        // 获取该subpage能够容纳的最大elem的数量
        final int maxNumElems = this.maxNumElems;
        // 将i左移6位，获取到可用elem所在的index的基础位置，
        // 即将i乘以64，表示前面已经扫描过的没有可用位置的long所包含的位数
        final int baseVal = i << 6;
        // 然后从低到高依次遍历位图的每一位
        for (int j = 0; j < 64; j ++) {
            // 如果发现某一位为0
            if ((bits & 1) == 0) {
                // 将该位与baseVal做位或操作，得到可用的elem的index，即将j位置加上前面扫描过的所有long的位数，
                // 得到可占用的elemSize在位图中的实际位置
                int val = baseVal | j;
                // 如果val小于最大的elem数量，返回val
                if (val < maxNumElems) {
                    return val;
                }
                // 否则跳出循环，返回-1
                else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        int pages = runSize >> pageShifts;
        return (long) runOffset << RUN_OFFSET_SHIFT
               | (long) pages << SIZE_SHIFT
               | 1L << IS_USED_SHIFT
               | 1L << IS_SUBPAGE_SHIFT
               | bitmapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            chunk.arena.lock();
            try {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            } finally {
                chunk.arena.unlock();
            }
        }

        if (!doNotDestroy) {
            return "(" + runOffset + ": not in use)";
        }

        return "(" + runOffset + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + runSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }
        chunk.arena.lock();
        try {
            return maxNumElems;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        chunk.arena.lock();
        try {
            return numAvail;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        chunk.arena.lock();
        try {
            return elemSize;
        } finally {
            chunk.arena.unlock();
        }
    }

    @Override
    public int pageSize() {
        return 1 << pageShifts;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }
}
