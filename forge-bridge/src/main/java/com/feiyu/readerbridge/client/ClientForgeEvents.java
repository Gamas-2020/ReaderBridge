package com.feiyu.readerbridge.client;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.client.gui.LocalReaderScreen;
import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.client.gui.ReaderBridgeScreen;
import com.feiyu.readerbridge.client.http.ReaderApiClient;
import com.feiyu.readerbridge.client.ui.AuiBridge;
import com.feiyu.readerbridge.client.ui.HudPositionScreen;
import com.feiyu.readerbridge.impl.MainThreadDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端 Forge 事件总线处理：主线程 flush、热键轮询、标题界面入口按钮。
 *
 * <p>热键表：<b>M</b> 打开阅读面板、<b>G</b> 播放/暂停、<b>B</b> 下一章、<b>N</b> 上一章、
 * <b>J</b> 切换 HUD、<b>H</b> 拖拽编辑 HUD 位置。
 * 界面打开期间会把累积的点击丢掉，避免关闭界面瞬间触发一串命令。</p>
 *
 * <p>只在客户端注册，服务端不会加载本类。</p>
 */
@Mod.EventBusSubscriber(modid = ReaderBridgeMod.MOD_ID, value = Dist.CLIENT)
public final class ClientForgeEvents {

    /** 已因界面/失焦自动暂停过，避免每个 tick 重复调 pause 与刷日志。 */
    private static boolean autoPaused = false;

    private ClientForgeEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 必须在这里独立驱动：ReaderEngine.tick() 在停止/暂停时会提前 return，
        // 而 OpenAL 的 listener 位置、水下滤波、播放头都得每 tick 刷新，不能挂在朗读状态上。
        com.feiyu.readerbridge.client.audio.OpenAlTtsPlayer.get().tick();
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 客户端主线程可用：补发早先排队的派发任务（例如 FMLCommonSetupEvent 阶段收到的状态）。
        MainThreadDispatcher.flushPending();

        // 晴雪UI 面板开着时，每 tick 把朗读状态推进页面；没装晴雪UI 时是空操作。
        AuiBridge.tick();

        Minecraft minecraft = Minecraft.getInstance();
        autoPauseWhenBlocked(minecraft);
        boolean inGame = minecraft.screen == null && minecraft.player != null;
        if (!inGame) {
            drainHotkeys();
            return;
        }

        while (ClientKeys.OPEN_PANEL.consumeClick()) {
            // 装了晴雪UI 就用它的 ore 主题（基岩版观感）面板；没装或页面加载失败则回退原版界面。
            Screen auiPanel = AuiBridge.open();
            minecraft.setScreen(auiPanel != null ? auiPanel : new LocalReaderScreen());
        }
        while (ClientKeys.PLAY_PAUSE.consumeClick()) {
            ReaderEngine.get().togglePlay();
        }
        while (ClientKeys.NEXT_CHAPTER.consumeClick()) {
            ReaderEngine.get().nextChapter();
        }
        while (ClientKeys.PREV_CHAPTER.consumeClick()) {
            ReaderEngine.get().prevChapter();
        }
        while (ClientKeys.TOGGLE_HUD.consumeClick()) {
            boolean visible = ClientHudState.toggle();
            ReaderBridgeMod.LOGGER.info("[readerbridge] HUD 切换为 {}", visible ? "显示" : "隐藏");
        }
        while (ClientKeys.HUD_POSITION.consumeClick()) {
            minecraft.setScreen(new HudPositionScreen(null));
        }
    }

    /**
     * 按配置决定是否因为「打开界面 / 离开世界 / 窗口失焦」自动暂停朗读。
     *
     * <p>两类原因<b>分开开关</b>（{@code config/readerbridge-common.toml} 的 {@code behavior} 段）：
     * <ul>
     *   <li>{@code pauseOnGui}（默认 false）：背包、聊天、Esc 等界面。默认<b>不</b>暂停，
     *       翻背包看地图不会打断朗读；</li>
     *   <li>{@code pauseOnLostFocus}（默认 true）：离开世界，或游戏窗口失去焦点。</li>
     * </ul>
     * 只暂停、不自动恢复：切回游戏后由用户自己决定何时继续，否则一边翻背包一边被强制朗读。
     * 阅读面板自身永远不算「打开界面」——它本来就是用来操作朗读的，进去就停等于不能用。
     */
    private static void autoPauseWhenBlocked(Minecraft minecraft) {
        boolean blocked = false;
        if (com.feiyu.readerbridge.config.ReaderBridgeConfig.pauseOnGui()
                && minecraft.screen != null
                && !(minecraft.screen instanceof LocalReaderScreen)
                && !AuiBridge.isOurs(minecraft.screen)
                // 兜底：只要 AUI 阅读面板还开着（哪怕 AUI 把 Screen 实例换掉了、
                // isOurs 的类名比对也没命中），都不算「玩家开着别的 GUI」。
                && !AuiBridge.isPanelOpen()) {
            blocked = true;
        }
        if (com.feiyu.readerbridge.config.ReaderBridgeConfig.pauseOnLostFocus()
                && (minecraft.level == null || !minecraft.isWindowActive())) {
            blocked = true;
        }
        if (!blocked) {
            autoPaused = false;
            return;
        }
        if (autoPaused) {
            return;
        }
        ReaderEngine engine = ReaderEngine.get();
        if (engine.isActive() && !engine.isPaused()) {
            engine.pause();
            ReaderBridgeMod.LOGGER.info("[readerbridge] 自动暂停朗读（开关在 config/readerbridge-common.toml 的 behavior 段）");
        }
        autoPaused = true;
    }

    /** 界面打开时清空按键点击队列。 */
    private static void drainHotkeys() {
        while (ClientKeys.OPEN_PANEL.consumeClick()) {
            // discard
        }
        while (ClientKeys.PLAY_PAUSE.consumeClick()) {
            // discard
        }
        while (ClientKeys.NEXT_CHAPTER.consumeClick()) {
            // discard
        }
        while (ClientKeys.PREV_CHAPTER.consumeClick()) {
            // discard
        }
        while (ClientKeys.TOGGLE_HUD.consumeClick()) {
            // discard
        }
        while (ClientKeys.HUD_POSITION.consumeClick()) {
            // discard
        }
    }

    /** 当前在朗读则暂停，否则播放。 */
    private static void togglePlayPause() {
        ReadingState state = ReaderBridgeProvider.getIfPresent().map(ReaderBridgeApi::getState).orElse(null);
        boolean playing = state != null && state.isActive() && !state.isPaused();
        sendReaderAction(playing ? "pause" : "play");
    }

    private static void sendReaderAction(String action) {
        ReaderBridgeMod.LOGGER.debug("[readerbridge] 热键命令: {}", action);
        ReaderApiClient.INSTANCE.readerAction(action, new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(Void ignored) {
                // 成功无需额外动作：真实状态会由 WS 推回的 state 帧更新。
            }

            @Override
            public void onFailure(Throwable error) {
                ReaderBridgeMod.LOGGER.debug("[readerbridge] 热键命令 {} 失败: {}", action, error.toString());
            }
        });
    }
}
