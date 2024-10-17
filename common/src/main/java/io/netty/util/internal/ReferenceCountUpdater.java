/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;

/**
 * Common logic for {@link ReferenceCounted} implementations
 */
public abstract class ReferenceCountUpdater<T extends ReferenceCounted> {
    /*
     * Implementation notes:
     *
     * For the updated int field:
     *   Even => "real" refcount is (refCnt >>> 1)
     *   Odd  => "real" refcount is 0
     *
     * (x & y) appears to be surprisingly expensive relative to (x == y). Thus this class uses
     * a fast-path in some places for most common low values when checking for live (even) refcounts,
     * for example: if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) { ...
     */

    protected ReferenceCountUpdater() { }

    public static long getUnsafeOffset(Class<? extends ReferenceCounted> clz, String fieldName) {
        try {
            if (PlatformDependent.hasUnsafe()) {
                return PlatformDependent.objectFieldOffset(clz.getDeclaredField(fieldName));
            }
        } catch (Throwable ignore) {
            // fall-back
        }
        return -1;
    }

    protected abstract AtomicIntegerFieldUpdater<T> updater();

    protected abstract long unsafeOffset();

    public final int initialValue() {
        // 初始值为2
        return 2;
    }

    public void setInitialValue(T instance) {
        // 获取要更新字段的offset
        final long offset = unsafeOffset();
        // 如果offset为-1的话
        if (offset == -1) {
            // 使用自身持有的java的updater进行设置，初始值为2
            updater().set(instance, initialValue());
        } else {
            // 否则，使用unsafe通过offset设置初始值
            PlatformDependent.safeConstructPutInt(instance, offset, initialValue());
        }
    }

    private static int realRefCnt(int rawCnt) {
        return rawCnt != 2 && rawCnt != 4 && (rawCnt & 1) != 0 ? 0 : rawCnt >>> 1;
    }

    /**
     * Like {@link #realRefCnt(int)} but throws if refCnt == 0
     */
    private static int toLiveRealRefCnt(int rawCnt, int decrement) {
        // 如果rawCnt是偶数的话，将其右移1位，即除以2
        if (rawCnt == 2 || rawCnt == 4 || (rawCnt & 1) == 0) {
            return rawCnt >>> 1;
        }
        // odd rawCnt => already deallocated
        // 如果发现rawCnt已经是奇数了，说明已经释放过了，报错
        throw new IllegalReferenceCountException(0, -decrement);
    }

    private int nonVolatileRawCnt(T instance) {
        // TODO: Once we compile against later versions of Java we can replace the Unsafe usage here by varhandles.
        // 获取refCnt在对象中的内存偏移量
        final long offset = unsafeOffset();
        // 如果内存偏移量不等于-1，使用unsafe进行读取，否则使用atomicUpdater进行读取
        return offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);
    }

    public final int refCnt(T instance) {
        return realRefCnt(updater().get(instance));
    }

    public final boolean isLiveNonVolatile(T instance) {
        // 获取要读取的属性在对象中的内存偏移量
        final long offset = unsafeOffset();
        // 如果偏移量不为-1的话，使用unsafe进行读取，否则使用atomicUpdater进行读取
        final int rawCnt = offset != -1 ? PlatformDependent.getInt(instance, offset) : updater().get(instance);

        // The "real" ref count is > 0 if the rawCnt is even.
        // 如果rawCnt是偶数，返回true
        return rawCnt == 2 || rawCnt == 4 || rawCnt == 6 || rawCnt == 8 || (rawCnt & 1) == 0;
    }

    /**
     * An unsafe operation that sets the reference count directly
     */
    public final void setRefCnt(T instance, int refCnt) {
        updater().set(instance, refCnt > 0 ? refCnt << 1 : 1); // overflow OK here
    }

    /**
     * Resets the reference count to 1
     */
    public final void resetRefCnt(T instance) {
        updater().set(instance, initialValue());
    }

    public final T retain(T instance) {
        return retain0(instance, 1, 2);
    }

    public final T retain(T instance, int increment) {
        // all changes to the raw count are 2x the "real" change - overflow is OK
        int rawIncrement = checkPositive(increment, "increment") << 1;
        return retain0(instance, increment, rawIncrement);
    }

    // rawIncrement == increment << 1
    private T retain0(T instance, final int increment, final int rawIncrement) {
        int oldRef = updater().getAndAdd(instance, rawIncrement);
        if (oldRef != 2 && oldRef != 4 && (oldRef & 1) != 0) {
            throw new IllegalReferenceCountException(0, increment);
        }
        // don't pass 0!
        if ((oldRef <= 0 && oldRef + rawIncrement >= 0)
                || (oldRef >= 0 && oldRef + rawIncrement < oldRef)) {
            // overflow case
            updater().getAndAdd(instance, -rawIncrement);
            throw new IllegalReferenceCountException(realRefCnt(oldRef), increment);
        }
        return instance;
    }

    // 该方法返回的是instance有没有被真正的释放，即refCnt属性是否被更新为奇数了
    public final boolean release(T instance) {
        // 读取instance中对应的refCnt的值，不通过volatile的形式
        int rawCnt = nonVolatileRawCnt(instance);
        // 如果读取到的refCnt的值为2的话，调用tryFinalRelease方法，进行最终的释放，如果释放失败的话，进行重试，尝试减少1个真实的refCnt，
        // 每个真实的refCnt对应2倍的rawRefCnt，其中refCnt字段保存的rawRefCnt，所以都是偶数
        // 否则，进行非最终的释放，减少一个真实的refCnt，即2个rawRefCnt，也就是将refCnt属性 - 2
        return rawCnt == 2 ? tryFinalRelease0(instance, 2) || retryRelease0(instance, 1)
                : nonFinalRelease0(instance, 1, rawCnt, toLiveRealRefCnt(rawCnt, 1));
    }

    public final boolean release(T instance, int decrement) {
        int rawCnt = nonVolatileRawCnt(instance);
        int realCnt = toLiveRealRefCnt(rawCnt, checkPositive(decrement, "decrement"));
        return decrement == realCnt ? tryFinalRelease0(instance, rawCnt) || retryRelease0(instance, decrement)
                : nonFinalRelease0(instance, decrement, rawCnt, realCnt);
    }

    private boolean tryFinalRelease0(T instance, int expectRawCnt) {
        // 将refCnt通过cas替换为1
        return updater().compareAndSet(instance, expectRawCnt, 1); // any odd number will work
    }

    private boolean nonFinalRelease0(T instance, int decrement, int rawCnt, int realCnt) {
        // 如果要减少的引用数量小于realCnt 并且 cas rawCnt成功了，返回false，表示不是最终的释放
        if (decrement < realCnt
                // all changes to the raw count are 2x the "real" change - overflow is OK
                && updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
            return false;
        }
        // 否则，调用retryRelease0方法，传入要减少的cnt的真实数量decrement
        return retryRelease0(instance, decrement);
    }

    private boolean retryRelease0(T instance, int decrement) {
        for (;;) {
            // 获取当前rawCnt的值 然后根据rawCnt获取真实refCnt的值，真实refCnt的值等于rawCnt >> 1
            int rawCnt = updater().get(instance), realCnt = toLiveRealRefCnt(rawCnt, decrement);
            // 如果要减少的数量 等于 真实的refCnt的值
            if (decrement == realCnt) {
                // 尝试使用cas将rawCnt替换为1，表示释放
                if (tryFinalRelease0(instance, rawCnt)) {
                    // 返回true，表示被最终释放
                    return true;
                }
            }
            // 如果要减少的数量 小于 真实的refCnt的值
            else if (decrement < realCnt) {
                // all changes to the raw count are 2x the "real" change
                // 通过cas将rawCnt替换为 rawCnt - 2 * decrement的值，所有的raw count的变动都是真实变动的2倍
                if (updater().compareAndSet(instance, rawCnt, rawCnt - (decrement << 1))) {
                    // 然后返回false，表示没有被最终释放
                    return false;
                }
            }
            // 其他情况报错
            else {
                throw new IllegalReferenceCountException(realCnt, -decrement);
            }
            // 当前线程让出cpu，之后再进行循环
            Thread.yield(); // this benefits throughput under high contention
        }
    }
}
