package com.feiyu.readerbridge;

import com.feiyu.readerbridge.impl.ReaderBridgeApiImpl;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * mod 事件总线上的通用生命周期处理（客户端 / 专用服务端都会走到这里）。
 *
 * <p>注意：本类不引用任何客户端专属类，专用服务端加载安全。</p>
 */
public final class CommonModEvents {

    private CommonModEvents() {
    }

    /**
     * 专用服务端初始化完成：启动 WebSocket 客户端。
     *
     * <p>使用 {@code enqueueWork} 切到主线程，保证初始化与后续回调的线程语义一致。</p>
     */
    @SubscribeEvent
    public static void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
        event.enqueueWork(() -> {
            ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
            if (api != null) {
                api.start();
            }
        });
    }

    /**
     * 配置首次加载完成：如果之前因为配置尚未就绪而没有启动，这里补一次 start（幂等）。
     */
    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (!ReaderBridgeMod.MOD_ID.equals(event.getConfig().getModId())) {
            return;
        }
        ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
        if (api != null) {
            api.start();
        }
    }

    /**
     * 配置重载：端口 / enabled 等可能已改变，需要重新读取并重连。
     */
    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (!ReaderBridgeMod.MOD_ID.equals(event.getConfig().getModId())) {
            return;
        }
        ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
        if (api != null) {
            api.restart(event.getConfig().getType().name());
        }
    }

    /**
     * 所有模组加载完成（主线程）：flush 早先排队的派发任务，并确保连接已启动。
     */
    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        com.feiyu.readerbridge.impl.MainThreadDispatcher.flushPending();
        ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
        if (api != null) {
            api.start();
        }
        ReaderBridgeMod.LOGGER.info("[readerbridge] 加载完成：connected={}, wsUrl={}",
                api != null && api.isConnected(), api == null ? "" : api.wsUrl());
    }
}
