package com.feiyu.readerbridge;

import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import com.feiyu.readerbridge.impl.ReaderBridgeApiImpl;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Reader Bridge 桥接模组主类。
 *
 * <p>加载期职责：</p>
 * <ol>
 *   <li><b>构造期</b>就创建并安装 {@link ReaderBridgeApiImpl} 单例（线程安全的双检锁），
 *       这样其它模组在任何生命周期阶段都能通过 {@link ReaderBridgeProvider#get()} 拿到 API，
 *       即使桥接尚未连接也只会拿到 inactive 快照。</li>
 *   <li>{@link FMLCommonSetupEvent} 里用 {@code event.enqueueWork(...)} 在主线程再次确认安装，
 *       并注册配置重载 / 服务端停止等监听。</li>
 *   <li>WebSocket 客户端本身在 {@code FMLClientSetupEvent}（客户端）与
 *       {@code FMLDedicatedServerSetupEvent}（专用服务端）里启动，见 {@code CommonModEvents}
 *       与客户端专用事件类。</li>
 * </ol>
 *
 * <p>本类不引用任何客户端专属类，因此在纯客户端与专用服务端两种环境下都能安全加载。</p>
 */
@Mod(ReaderBridgeMod.MOD_ID)
public final class ReaderBridgeMod {

    /** 模组 id，必须与小写 mods.toml 中的 modId 一致。 */
    public static final String MOD_ID = "readerbridge";

    /** 统一日志器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    public ReaderBridgeMod() {
        // 1) 构造期即安装单例：纯 Java 单例，不触碰任何客户端/服务端专属类。
        ReaderBridgeApiImpl api = ReaderBridgeApiImpl.install();
        ReaderBridgeProvider.install(api);

        // 2) 注册 COMMON 配置（config/readerbridge-common.toml）
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ReaderBridgeConfig.SPEC,
                "readerbridge-common.toml");

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);

        // 3) 生命周期 / 配置重载监听（mod bus 与 game bus 各一组）
        modBus.register(CommonModEvents.class);
        MinecraftForge.EVENT_BUS.register(CommonForgeEvents.class);

        LOGGER.info("[readerbridge] 模组构造完成，API 单例已安装");
    }

    /**
     * 通用初始化：必须用 {@code enqueueWork} 切到主线程后再做「可能与主线程相关」的初始化。
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ReaderBridgeApiImpl api = ReaderBridgeApiImpl.install();
            ReaderBridgeProvider.install(api);
            LOGGER.info("[readerbridge] 通用初始化完成（主线程），API 可用，当前 wsUrl={}",
                    api.wsUrl());
        });
    }
}
