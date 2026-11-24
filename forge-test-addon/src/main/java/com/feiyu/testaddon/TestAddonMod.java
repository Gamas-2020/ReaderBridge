package com.feiyu.testaddon;

import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReadingState;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

/**
 * 最小测试附属模组。
 *
 * <p>验证目标：第三方模组只需依赖 {@code forge-bridge}（classes 目录或 jar），
 * 就能通过 {@link ReaderBridgeProvider} 取得 API 并注册监听器，且收到的回调
 * <b>一定运行在 Minecraft 主线程</b>。</p>
 *
 * <p>每次回调都会打印带标记的一行日志，便于在日志里 grep：</p>
 * <ul>
 *   <li>{@code [MAIN-THREAD-OK]} —— 回调在主线程（预期结果）</li>
 *   <li>{@code [MAIN-THREAD-VIOLATION]} —— 回调不在主线程（实现错误）</li>
 * </ul>
 */
@Mod(TestAddonMod.MOD_ID)
public final class TestAddonMod {

    /** 模组 id。 */
    public static final String MOD_ID = "readerbridge_testaddon";

    private static final Logger LOGGER = LogUtils.getLogger();

    public TestAddonMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        LOGGER.info("[test-addon] 模组构造完成");
    }

    /**
     * 注册状态监听器。
     *
     * <p>放在 {@code FMLCommonSetupEvent} 的 {@code enqueueWork} 里执行，
     * 保证在 bridge 已安装 API 单例之后注册。</p>
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ReaderBridgeApi api = ReaderBridgeProvider.get();
                api.addListener(TestAddonMod::onReadingStateChanged);
                LOGGER.info("[test-addon] 监听器注册成功: connected={}, wsUrl={}",
                        api.isConnected(), api.wsUrl());
                LOGGER.info("[test-addon] 当前状态快照: active={}, book={}, sentence={}",
                        api.getState().isActive(), api.getState().bookTitle(),
                        api.getState().currentSentence());
            } catch (Throwable t) {
                LOGGER.error("[test-addon] 监听器注册失败（bridge 未安装？）", t);
            }
        });
    }

    /**
     * 状态回调。bridge 保证本方法在主线程被调用。
     *
     * @param state 不可变状态快照
     */
    private static void onReadingStateChanged(ReadingState state) {
        boolean mainThread = ThreadCheck.isOnMainThread();
        String tag = mainThread ? "[MAIN-THREAD-OK]" : "[MAIN-THREAD-VIOLATION]";
        String threadName = Thread.currentThread().getName();

        // System.out 与 LOGGER 各打一份，便于不同日志抓取方式验证
        System.out.println(tag + " [test-addon] listener thread=" + threadName
                + " isMainThread=" + mainThread
                + " active=" + state.isActive()
                + " book=" + state.bookTitle()
                + " chapter=" + state.chapterTitle()
                + " sentence=" + state.currentSentence()
                + " progress=" + state.progress()
                + " paused=" + state.isPaused());

        LOGGER.info("{} [test-addon] 回调 thread={} active={} book={} sentence={}",
                tag, threadName, state.isActive(), state.bookTitle(), state.currentSentence());
    }
}
