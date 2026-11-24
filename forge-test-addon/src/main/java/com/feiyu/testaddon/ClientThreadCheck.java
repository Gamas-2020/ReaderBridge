package com.feiyu.testaddon;

import net.minecraft.client.Minecraft;

/**
 * 客户端主线程判定。
 *
 * <p>直接引用 {@code net.minecraft.client.Minecraft}，因此只能通过
 * {@link net.minecraftforge.fml.DistExecutor} 在客户端加载。</p>
 */
final class ClientThreadCheck {

    private ClientThreadCheck() {
    }

    /**
     * 当前线程是否为客户端主线程（渲染线程）。
     *
     * @return 客户端未就绪返回 {@code false}
     */
    static boolean isOnMainThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isSameThread();
    }
}
