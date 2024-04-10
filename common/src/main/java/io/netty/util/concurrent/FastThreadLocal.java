/*
 * Copyright 2014 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static io.netty.util.internal.InternalThreadLocalMap.VARIABLES_TO_REMOVE_INDEX;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    public static void removeAll() {
        // 获取当前线程的InternalThreadLocalMap对象
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        // 如果为null的话，直接返回
        if (threadLocalMap == null) {
            return;
        }

        try {
            // 获取VARIABLES_TO_REMOVE_INDEX下标在InternalThreadLocalMap中的元素
            Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);
            // 如果v不为null 并且 不是UNSET
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                // 强转为Set类型
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                // 然后将其转换为FastThreadLocal类型的数组
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);
                // 遍历数组，依次调用FastThreadLocal的remove方法
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            // 最后调用InternalThreadLocalMap的remove方法，将map也从thread中删除
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        // 获取VARIABLES_TO_REMOVE_INDEX在对应threadLocalMap中的元素
        Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);
        Set<FastThreadLocal<?>> variablesToRemove;
        // 如果v是UNSET或者null
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            // 创建一个FastThreadLocal的set
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            // 设置VARIABLES_TO_REMOVE_INDEX下标为上一步创建的set
            threadLocalMap.setIndexedVariable(VARIABLES_TO_REMOVE_INDEX, variablesToRemove);
        }
        // 将v强转为set
        else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        // 将FastThreadLocal添加到set中
        variablesToRemove.add(variable);
    }

    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        // 获取threadLocalMap中下标为VARIABLES_TO_REMOVE_INDEX的元素
        Object v = threadLocalMap.indexedVariable(VARIABLES_TO_REMOVE_INDEX);

        // 如果是UNSET或者null，直接返回
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        // 否则强转为FastThreadLocal的set
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        // 将传入的FastThreadLocal从set中删除
        variablesToRemove.remove(variable);
    }

    private final int index;

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        // 获取当前线程的InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 获取对应index下标的元素
        Object v = threadLocalMap.indexedVariable(index);
        // 如果v不等于UNSET，直接返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        // 否则调用initialize方法
        return initialize(threadLocalMap);
    }

    /**
     * Returns the current value for the current thread if it exists, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    public final V getIfExists() {
        // 如果存在才返回，不会调用initialize方法
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap != null) {
            Object v = threadLocalMap.indexedVariable(index);
            if (v != InternalThreadLocalMap.UNSET) {
                return (V) v;
            }
        }
        return null;
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            // 调用initialize方法获取对象
            v = initialValue();
            // 如果v等于UNSET，报错
            if (v == InternalThreadLocalMap.UNSET) {
                throw new IllegalArgumentException("InternalThreadLocalMap.UNSET can not be initial value.");
            }
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        // 将v设置进threadLocalMap下标index的位置
        threadLocalMap.setIndexedVariable(index, v);
        // 然后将当前FastThreadLocal对象也设置进InternalThreadLocalMap中
        addToVariablesToRemove(threadLocalMap, this);
        // 返回v
        return v;
    }

    /**
     * Set the value for the current thread.
     */
    public final void set(V value) {
        // 如果value不等于InternalThreadLocalMap的UNSET对象
        if (value != InternalThreadLocalMap.UNSET) {
            // 获取对应的InternalThreadLocalMap
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            // 将value设置到InternalThreadLocalMap中
            setKnownNotUnset(threadLocalMap, value);
        }
        // 如果value为UNSET
        else {
            // 调用remove方法
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove(threadLocalMap);
        }
    }

    /**
     * @see InternalThreadLocalMap#setIndexedVariable(int, Object).
     */
    private void setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        // 将value设置到threadLocalMap的index位置
        if (threadLocalMap.setIndexedVariable(index, value)) {
            // 如果设置成功，将当前threadLocal添加到threadLocalMap中
            addToVariablesToRemove(threadLocalMap, this);
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        // 删除threadLocalMap中对应index的元素
        Object v = threadLocalMap.removeIndexedVariable(index);
        // 如果元素不等于UNSET
        if (v != InternalThreadLocalMap.UNSET) {
            // 那么将自身ThreadLocal也从threadLocalMap中删除
            removeFromVariablesToRemove(threadLocalMap, this);
            try {
                // 留给子类实现
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
