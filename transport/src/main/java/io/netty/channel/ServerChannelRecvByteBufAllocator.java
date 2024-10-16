/*
 * Copyright 2021 The Netty Project
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
package io.netty.channel;

/**
 * {@link MaxMessagesRecvByteBufAllocator} implementation which should be used for {@link ServerChannel}s.
 */
public final class ServerChannelRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {
    public ServerChannelRecvByteBufAllocator() {
        // 调用父类的构造方法，传入每次读取的最大消息为1，ignoreBytesRead为true
        super(1, true);
    }

    @Override
    public Handle newHandle() {
        // 创建一个MaxMessageHandle的匿名子类返回，重写guess方法返回128
        return new MaxMessageHandle() {
            @Override
            public int guess() {
                return 128;
            }
        };
    }
}
