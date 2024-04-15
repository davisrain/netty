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

import java.util.Arrays;

/**
 * Internal primitive priority queue, used by {@link PoolChunk}.
 * The implementation is based on the binary heap, as described in Algorithms by Sedgewick and Wayne.
 */
final class LongPriorityQueue {
    public static final int NO_VALUE = -1;
    private long[] array = new long[9];
    private int size;

    public void offer(long handle) {
        if (handle == NO_VALUE) {
            throw new IllegalArgumentException("The NO_VALUE (" + NO_VALUE + ") cannot be added to the queue.");
        }
        size++;
        if (size == array.length) {
            // Grow queue capacity.
            array = Arrays.copyOf(array, 1 + (array.length - 1) * 2);
        }
        array[size] = handle;
        lift(size);
    }

    public void remove(long value) {
        // 遍历所有存在的元素
        for (int i = 1; i <= size; i++) {
            // 如果发现和value相等
            if (array[i] == value) {
                // 将其设置为最后一个元素，并且将size--
                array[i] = array[size--];
                // 将i上浮
                lift(i);
                // 再将i下沉，维持堆的有序性
                sink(i);
                return;
            }
        }
    }

    public long peek() {
        if (size == 0) {
            return NO_VALUE;
        }
        return array[1];
    }

    public long poll() {
        if (size == 0) {
            return NO_VALUE;
        }
        // 获取第一个元素的值
        long val = array[1];
        // 将第一个元素替换为最后一个元素
        array[1] = array[size];
        // 将最后一个元素设置为0
        array[size] = 0;
        // 将size - 1
        size--;
        // 再将第1个元素下沉
        sink(1);
        return val;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private void lift(int index) {
        int parentIndex;
        // 当index大于1的时候 并且 当父节点的值大于index的值的时候
        while (index > 1 && subord(parentIndex = index >> 1, index)) {
            // 将index和父节点交换
            swap(index, parentIndex);
            // 将index设置为父节点，继续循环
            index = parentIndex;
        }
    }

    private void sink(int index) {
        int child;
        // 当index的子节点小于等于size的时候
        while ((child = index << 1) <= size) {
            // 并且另一个子节点也存在，并且另一个子节点更小，那么取另一个子节点的index
            if (child < size && subord(child, child + 1)) {
                child++;
            }
            // 如果自身已经不大于最小的那个子节点了，跳出循环
            if (!subord(index, child)) {
                break;
            }
            // 否则交换节点的值
            swap(index, child);
            // 将index设置为子节点的下标，继续循环
            index = child;
        }
    }

    private boolean subord(int a, int b) {
        return array[a] > array[b];
    }

    private void swap(int a, int b) {
        long value = array[a];
        array[a] = array[b];
        array[b] = value;
    }
}
