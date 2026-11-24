package com.feiyu.readerbridge;

import com.feiyu.readerbridge.impl.MainThreadDispatcher;
import com.feiyu.readerbridge.impl.ReaderBridgeApiImpl;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge 事件总线上的通用生命周期处理（两端都可用）。
 *
 * <p>这里是「主线程可用时 flush 排队任务」的兜底点之一：服务端 tick 一定在
 * Server thread 上执行。</p>
 */
public final class CommonForgeEvents {

    private CommonForgeEvents() {
    }

    /**
     * 服务端 tick：在主线程 flush 早先排队的派发任务。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MainThreadDispatcher.flushPending();
        }
    }

    /**
     * 服务端停止：优雅关闭 WebSocket 与线程池。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
        if (api != null) {
            api.shutdown();
        }
    }
}
