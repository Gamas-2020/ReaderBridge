package com.feiyu.readerbridge.impl;

import net.minecraft.client.Minecraft;

/**
 * 客户端主线程访问器。
 *
 * <p>该类直接引用 {@code net.minecraft.client.Minecraft}，因此<b>只允许在客户端通过
 * {@link net.minecraftforge.fml.DistExecutor} 间接加载</b>。专用服务端上这个类不会被加载，
 * 从而避免 {@code NoClassDefFoundError}。</p>
 */
final class ClientThreadAccess {

    private ClientThreadAccess() {
    }

    /**
     * 尝试在主线程执行任务。
     *
     * @param task 任务
     * @return 客户端实例已就绪（任务已直接执行或已排队）返回 {@code true}；
     *         客户端尚未初始化返回 {@code false}
     */
    static boolean tryRun(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
        return true;
    }

    /**
     * 判断当前线程是否为客户端主线程（渲染线程）。
     *
     * @return 是主线程返回 {@code true}；客户端未就绪返回 {@code false}
     */
    static boolean isOnMainThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isSameThread();
    }
}
