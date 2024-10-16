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

import io.netty.util.internal.ObjectPool.Handle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    private final Handle<PooledByteBuf<T>> recyclerHandle;

    protected PoolChunk<T> chunk;
    protected long handle;
    protected T memory;
    protected int offset;
    protected int length;
    int maxLength;
    PoolThreadCache cache;
    ByteBuffer tmpNioBuf;
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        // 持有recyclerHandle，决定了ByteBuf的回收策略
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, 0, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;
        assert !PoolChunk.isSubpage(handle) || chunk.arena.size2SizeIdx(maxLength) <= chunk.arena.smallMaxSizeIdx:
                "Allocated small sub-page handle for a buffer size that isn't \"small.\"";

        // 将chunk中记录分配过的内存 加上 maxLength
        chunk.incrementPinnedMemory(maxLength);
        // 设置chunk
        this.chunk = chunk;
        // 设置memory为chunk中持有的memory
        memory = chunk.memory;
        // 设置tmpNioBuffer为传入的nioBuffer，可能为null
        tmpNioBuf = nioBuffer;
        // 设置allocator为arena中持有的allocator
        allocator = chunk.arena.parent;
        // 设置自身的cache为传入的PoolThreadCache
        this.cache = cache;
        // 设置自身的handle
        this.handle = handle;
        // 设置内存的偏移量
        this.offset = offset;
        // 设置请求分配的内存长度
        this.length = length;
        // 设置实际分配的内存长度
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        // 重置refCnt属性
        resetRefCnt();
        // 将readerIndex和writerIndex都置为0
        setIndex0(0, 0);
        // 将mark标记置为0
        discardMarks();
    }

    @Override
    public final int capacity() {
        // 返回ByteBuf的length作为capacity
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        // 使用maxLength 和 maxCapacity的最小值 减去 writerIndex，表示能够写入的最大字节数
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    @Override
    // 调整ByteBuf的capacity的方法
    public final ByteBuf capacity(int newCapacity) {
        // 如果新的容量就等于length，直接返回
        if (newCapacity == length) {
            ensureAccessible();
            return this;
        }
        // 检查新的容量不能比maxCapacity大
        checkNewCapacity(newCapacity);
        // 如果chunk不是unpooled类型的
        if (!chunk.unpooled) {
            // If the request capacity does not require reallocation, just update the length of the memory.
            // 如果新的容量大于了length
            if (newCapacity > length) {
                // 判断是否小于maxLength，如果是，将length设置为新的容量
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
                // 否则就要进行扩容
            }
            // 如果新的容量比length还小
            // 判断新容量是否大于 maxLength/2 并且 (maxLength大于512 或者 新容量大于 maxLength - 16)
            else if (newCapacity > maxLength >>> 1 &&
                    (maxLength > 512 || newCapacity > maxLength - 16)) {
                // here newCapacity < length
                // 将length调整为新的容量大小
                length = newCapacity;
                // 重新设置writerIndex 和 readerIndex的位置
                trimIndicesToCapacity(newCapacity);
                return this;
            }
        }

        // Reallocation required.
        // 将chunk中统计的已分配的内存大小减去maxLength
        chunk.decrementPinnedMemory(maxLength);
        // 然后重新进行分配，大小为新的容量
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    protected final ByteBuffer internalNioBuffer() {
        // 获取ByteBuf持有的临时NioBuffer
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        // 如果为null的话，调用newInternalNioBuffer，根据memory创建一个新的临时Buffer对象
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        // 如果不为null，将buffer clear
        else {
            tmpNioBuf.clear();
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    @Override
    protected final void deallocate() {
        // 回收内存，如果handle大于等于0，说明是被初始化过的ByteBuf，里面已经持有了ByteBuffer
        if (handle >= 0) {
            final long handle = this.handle;
            // 将自身的handle设置为-1
            this.handle = -1;
            // 将memory设置为null
            memory = null;
            // 将chunk记录的已分配内存 减去maxLength对应的值
            chunk.decrementPinnedMemory(maxLength);
            // 调用arena的free方法 释放内存
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            // 将chunk cache都设置为null
            tmpNioBuf = null;
            chunk = null;
            cache = null;
            // 调用recycle方法，对自身进行回收
            recycle();
        }
    }

    private void recycle() {
        // 调用recyclerHandle的recycle方法，根据不同的回收策略决定自身是否应该被回收到对象池
        recyclerHandle.recycle(this);
    }

    protected final int idx(int index) {
        // 将offset + index返回
        return offset + index;
    }

    final ByteBuffer _internalNioBuffer(int index, int length, boolean duplicate) {
        // 将index加上offset
        index = idx(index);
        // 根据是否需要复制，决定是否创建一个新的ByteBuffer
        ByteBuffer buffer = duplicate ? newInternalNioBuffer(memory) : internalNioBuffer();
        // 将buffer的limit限制为index + length的位置，然后将position设置为index的位置
        buffer.limit(index + length).position(index);
        // 返回ByteBuffer
        return buffer;
    }

    ByteBuffer duplicateInternalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, true);
    }

    @Override
    public final ByteBuffer internalNioBuffer(int index, int length) {
        // 检查index和length
        checkIndex(index, length);
        // 获取ByteBuf内部持有的nio的ByteBuffer对象的复制，然后将position设置为offset+index，limit设置为position+length
        return _internalNioBuffer(index, length, false);
    }

    @Override
    public final int nioBufferCount() {
        return 1;
    }

    @Override
    public final ByteBuffer nioBuffer(int index, int length) {
        return duplicateInternalNioBuffer(index, length).slice();
    }

    @Override
    public final ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public final boolean isContiguous() {
        return true;
    }

    @Override
    public final int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length));
    }

    @Override
    public final int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false));
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length), position);
    }

    @Override
    public final int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false), position);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        try {
            // 调用channel的read方法，获取ByteBuf自身持有的nio的ByteBuffer传入，并且设置写入ByteBuffer的位置index和长度length
            return in.read(internalNioBuffer(index, length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public final int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length), position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }
}
