package io.netty.util;

import org.junit.jupiter.api.Test;
import sun.misc.VM;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

public class MaxDirectMemoryTest {

    @Test
    public void getMaxDirectMemory() {
        // 从vm中获取
        System.out.println(VM.maxDirectMemory() / 1024 / 1024);

        // 从jvm参数中获取
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String inputArgument : runtimeMXBean.getInputArguments()) {
            System.out.println(inputArgument);
        }

        // 获取堆的最大内存
        System.out.println(Runtime.getRuntime().maxMemory() / 1024 / 1024);
    }
}
