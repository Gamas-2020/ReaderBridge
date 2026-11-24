package com.feiyu.testaddon;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 主线程判定工具（两端通用）。
 *
 * <p>客户端分支通过 {@link DistExecutor} 间接引用 {@code net.minecraft.client.Minecraft}，
 * 保证专用服务端不会因加载客户端类而失败。</p>
 */
final class ThreadCheck {

    private ThreadCheck() {
    }

    /**
     * 当前线程是否为主线程。
     *
     * @return 是主线程返回 {@code true}
     */
    static boolean isOnMainThread() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 必须用 unsafe 变体：safe 变体会调用 DistExecutor.validateSafeReferent，
            // 它用 SerializedLambda 比较捕获类与实现类，本类的 lambda 会被判为 unsafe 并抛异常。
            // 服务端分支由前置的 dist 判断保证不会执行该 lambda。
            Boolean clientResult = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                    () -> ClientThreadCheck::isOnMainThread);
            if (clientResult != null) {
                return clientResult;
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }

    /** 诊断用：给出当前线程身份描述。 */
    static String describeThread() {
        String side = FMLEnvironment.dist == Dist.CLIENT ? "CLIENT" : "SERVER";
        return Thread.currentThread().getName() + " (" + side + ")";
    }

    /** 仅用于避免未使用告警。 */
    static Optional<String> emptyDiagnostic() {
        return Optional.empty();
    }
}
