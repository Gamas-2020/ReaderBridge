package com.feiyu.readerbridge.client;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.client.fq.LocalReaderApi;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import com.feiyu.readerbridge.impl.ReaderBridgeApiImpl;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端 mod 事件。
 *
 * <p>通过 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT, ...)} 限定只在客户端注册，
 * 因此本类及其引用的 {@code IGuiOverlay} / {@code KeyMapping} 等客户端类在专用服务端不会被加载。</p>
 */
@Mod.EventBusSubscriber(modid = ReaderBridgeMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientEvents {

    private ClientEvents() {
    }

    /** 客户端初始化：启动本地朗读引擎、接管对外 API、初始化 HUD 可见状态。 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ReaderBridgeApiImpl api = ReaderBridgeApiImpl.instanceOrNull();
            if (api != null) {
                api.start();
            }
            // 朗读现在跑在本进程内，用本地引擎适配器接管对外 API：
            // HUD、世界文字、第三方模组读到的都是真实播放状态，不再依赖外部 App。
            // WS 客户端保留运行，只是兼容旧的外部核心（连不上完全不影响本地朗读）。
            ReaderBridgeProvider.install(LocalReaderApi.INSTANCE);
            ReaderEngine.get().prewarm(new java.io.File("config", "readerbridge").toPath());
            ClientHudState.init(ReaderBridgeConfig.showHud());
            ReaderBridgeMod.LOGGER.info("[readerbridge] 客户端初始化完成（本地朗读引擎就绪），HUD 可见={}",
                    ClientHudState.visible());
        });
    }

    /** 注册 HUD 覆盖层，绘制在所有原版 HUD 之上。 */
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("readerbridge_hud", ReaderBridgeHud.INSTANCE);
    }

    /**
     * 注册热键：M 打开阅读面板、G 播放/暂停、B 下一章、N 上一章、J 切换 HUD、H 编辑 HUD 位置。
     *
     * <p>键位在 {@link ClientKeys} 里从配置读取；Forge 不支持运行期重注册，
     * 因此修改配置里的键名需要重启游戏。</p>
     */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientKeys.OPEN_PANEL);
        event.register(ClientKeys.PLAY_PAUSE);
        event.register(ClientKeys.NEXT_CHAPTER);
        event.register(ClientKeys.PREV_CHAPTER);
        event.register(ClientKeys.TOGGLE_HUD);
        event.register(ClientKeys.HUD_POSITION);
    }
}
