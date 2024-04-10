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
package io.netty.util.internal;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;


/**
 * Allows to free direct {@link ByteBuffer} by using Cleaner. This is encapsulated in an extra class to be able
 * to use {@link PlatformDependent0} on Android without problems.
 *
 * For more details see <a href="https://github.com/netty/netty/issues/2604">#2604</a>.
 */
final class CleanerJava6 implements Cleaner {
    private static final long CLEANER_FIELD_OFFSET;
    private static final Method CLEAN_METHOD;
    private static final Field CLEANER_FIELD;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CleanerJava6.class);

    static {
        long fieldOffset;
        Method clean;
        Field cleanerField;
        Throwable error = null;
        final ByteBuffer direct = ByteBuffer.allocateDirect(1);
        try {
            Object mayBeCleanerField = AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    try {
                        Field cleanerField =  direct.getClass().getDeclaredField("cleaner");
                        if (!PlatformDependent.hasUnsafe()) {
                            // We need to make it accessible if we do not use Unsafe as we will access it via
                            // reflection.
                            cleanerField.setAccessible(true);
                        }
                        return cleanerField;
                    } catch (Throwable cause) {
                        return cause;
                    }
                }
            });
            if (mayBeCleanerField instanceof Throwable) {
                throw (Throwable) mayBeCleanerField;
            }

            cleanerField = (Field) mayBeCleanerField;

            final Object cleaner;

            // If we have sun.misc.Unsafe we will use it as its faster then using reflection,
            // otherwise let us try reflection as last resort.
            // 如果存在unsafe
            if (PlatformDependent.hasUnsafe()) {
                // 获取cleaner字段再内存中的位置
                fieldOffset = PlatformDependent0.objectFieldOffset(cleanerField);
                // 然后获取对应的Cleaner对象
                cleaner = PlatformDependent0.getObject(direct, fieldOffset);
            }
            // 如果不存在unsafe，通过反射获取对象
            else {
                fieldOffset = -1;
                cleaner = cleanerField.get(direct);
            }
            // 获取cleaner的clean方法，进行调用
            clean = cleaner.getClass().getDeclaredMethod("clean");
            clean.invoke(cleaner);
        } catch (Throwable t) {
            // We don't have ByteBuffer.cleaner().
            fieldOffset = -1;
            clean = null;
            error = t;
            cleanerField = null;
        }

        if (error == null) {
            logger.debug("java.nio.ByteBuffer.cleaner(): available");
        } else {
            logger.debug("java.nio.ByteBuffer.cleaner(): unavailable", error);
        }
        // 将cleaner字段 cleaner字段的内存位置 还有cleaner的clean方法赋值给自身持有
        CLEANER_FIELD = cleanerField;
        CLEANER_FIELD_OFFSET = fieldOffset;
        CLEAN_METHOD = clean;
    }

    static boolean isSupported() {
        return CLEANER_FIELD_OFFSET != -1 || CLEANER_FIELD != null;
    }

    @Override
    public void freeDirectBuffer(ByteBuffer buffer) {
        // 如果buffer不是直接内存，直接返回
        if (!buffer.isDirect()) {
            return;
        }
        if (System.getSecurityManager() == null) {
            try {
                // 调用freeDirectBuffer0方法释放直接内存
                freeDirectBuffer0(buffer);
            } catch (Throwable cause) {
                PlatformDependent0.throwException(cause);
            }
        } else {
            freeDirectBufferPrivileged(buffer);
        }
    }

    private static void freeDirectBufferPrivileged(final ByteBuffer buffer) {
        Throwable cause = AccessController.doPrivileged(new PrivilegedAction<Throwable>() {
            @Override
            public Throwable run() {
                try {
                    freeDirectBuffer0(buffer);
                    return null;
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });
        if (cause != null) {
            PlatformDependent0.throwException(cause);
        }
    }

    private static void freeDirectBuffer0(ByteBuffer buffer) throws Exception {
        final Object cleaner;
        // If CLEANER_FIELD_OFFSET == -1 we need to use reflection to access the cleaner, otherwise we can use
        // sun.misc.Unsafe.
        // 如果cleaner字段的内存位置为-1，那么只能通过反射获取cleaner对象
        if (CLEANER_FIELD_OFFSET == -1) {
            cleaner = CLEANER_FIELD.get(buffer);
        } else {
            // 否则通过unsafe获取
            cleaner = PlatformDependent0.getObject(buffer, CLEANER_FIELD_OFFSET);
        }
        // 如果cleaner不为null，调用其clean方法释放直接内存
        if (cleaner != null) {
            CLEAN_METHOD.invoke(cleaner);
        }
    }
}
