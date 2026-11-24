package com.feiyu.readerbridge.client;

import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.client.gui.UiTheme;
import com.feiyu.readerbridge.client.ui.AuiBridge;
import com.feiyu.readerbridge.client.ui.AuiHud;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 阅读状态 HUD：照 MuoniumPlayer 的「灵动岛」胶囊语言重做。
 *
 * <p>参考 {@code core/settings/HudSetting.java:35-46} 的 DYNAMIC_ISLAND_* 系列
 * （胶囊大小/字体/基础宽度/进度条粗细 0.75~4 设计像素），用 Minecraft 原生
 * {@link GuiGraphics#fill} 还原：一枚深色圆角胶囊，左侧状态点、中间书名+当前句、
 * 底部一条极细进度条；未连接时压缩成单行灰字，绝不抛异常。</p>
 *
 * <p>位置来自配置 {@code hud.hudX / hud.hudY}，可用 H 键拖拽调整。</p>
 */
public final class ReaderBridgeHud implements IGuiOverlay {

    public static final ReaderBridgeHud INSTANCE = new ReaderBridgeHud();

    /**
     * 上一次记进日志的 HUD 风格。分流结果只在<b>变化时</b>打一条 INFO，不会刷屏。
     *
     * <p>这条日志是「AUI HUD 为什么不出现」的唯一直接证据：{@code hudMode=auto} 会跟随
     * {@code uiMode}，而 {@code uiMode=vanilla} 时 {@link ReaderBridgeConfig#effectiveHudMode()}
     * 返回 {@code vanilla} —— {@link AuiHud#render} 一次都不会被调用，日志里也就永远没有
     * 「AUI HUD 文档已创建」那一行。此时用户看到的「HUD」其实是本类下面的原版胶囊
     * （像素字形），数据同步正常，但那不是 AUI。</p>
     */
    private static String loggedHudStyle;

    private static void logStyleOnce(String style) {
        if (style.equals(loggedHudStyle)) {
            return;
        }
        loggedHudStyle = style;
        boolean available = AuiBridge.available();
        com.feiyu.readerbridge.ReaderBridgeMod.LOGGER.info(
                "[readerbridge] HUD 风格分流 style={} auiAvailable={}（hudMode={} uiMode={}）",
                style, available, ReaderBridgeConfig.hudMode(), ReaderBridgeConfig.uiMode());
    }

    private static final int PAD_X = 8;
    private static final int PAD_Y = 4;
    private static final int LINE_H = 10;
    private static final int PROGRESS_H = 2;
    private static final int DOT = 3;
    private static final int MAX_WIDTH = 240;

    private ReaderBridgeHud() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        try {
            // 配置是总开关，热键是临时开关。每帧把配置同步进来：
            // 改 toml / AUI 设置页 / Configured 界面都立刻生效；
            // 配置值没变时不会覆盖热键 J 的临时切换。
            if (!ClientHudState.effectiveVisible(
                    com.feiyu.readerbridge.config.ReaderBridgeConfig.showHud())) {
                // AUI 版 HUD 是独立文档，不归这条 IGuiOverlay 管，必须显式收起来
                AuiHud.hide();
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.font == null || graphics == null) {
                return;
            }
            if (minecraft.options != null && minecraft.options.hideGui) {
                AuiHud.hide();
                return;
            }

            Font font = minecraft.font;
            ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
            boolean connected = api != null && api.isConnected();
            ReadingState state = api == null ? null : api.getState();
            boolean active = state != null && state.isActive();

            // ---- HUD 风格分流：hudMode 显式配置优先，auto 跟随有效 UI 风格（见 effectiveHudMode）----
            // 判断「装了晴雪UI」只走 AuiBridge.available()（内部 ModList 查询），
            // 这里不出现任何 AUI 类型，未装晴雪UI 时也不会加载 AuiHud 的 AUI 引用。
            String hudStyle = ReaderBridgeConfig.effectiveHudMode();
            logStyleOnce(hudStyle);
            if ("apricity".equals(hudStyle) && AuiBridge.available()) {
                if (AuiHud.render(true, auiStateKey(connected, active, state), auiTitle(connected, active, state),
                        auiChapter(active, state), auiSentence(active, state), auiPermille(active, state),
                        ReaderBridgeConfig.hudX(), ReaderBridgeConfig.hudY())) {
                    // AUI 已经接管绘制，不再重复画原版胶囊
                    return;
                }
                // 文档还没建好（资源包加载中 / 页面缺失）：本帧退回原版绘制，避免 HUD 整块消失
            } else if (AuiHud.exists()) {
                // 切回原版风格（或卸载前置）时立刻把 AUI 文档隐掉，不等它自己刷新
                AuiHud.hide();
            }

            // 文本行：胶囊宽度按最长行自适应，上限 MAX_WIDTH
            String title = !connected ? "App 未连接"
                    : (!active ? "阅读器 · 空闲" : "《" + orDash(state.bookTitle()) + "》");
            String line2 = !connected ? "" : (!active ? "" : orDash(state.currentSentence()));
            String line3 = !connected ? "" : (!active ? "" : buildProgressLine(state));

            int width = Math.min(MAX_WIDTH, Math.max(font.width(title), Math.max(font.width(line2), font.width(line3))) + PAD_X * 2 + DOT + 6);
            int height = PAD_Y * 2 + LINE_H + (line3.isEmpty() ? 0 : LINE_H) + (line2.isEmpty() ? 0 : LINE_H);
            height += PROGRESS_H + 2;

            int x = ReaderBridgeConfig.hudX();
            int y = ReaderBridgeConfig.hudY();

            // 胶囊底 + 1px 描边（灵动岛层次）
            UiTheme.fillCapsule(graphics, x, y, width, height, 0xD9121216);
            UiTheme.strokeRounded(graphics, x, y, width, height, Math.min(height / 2, UiTheme.RADIUS_CAPSULE), 0x33FFFFFF);

            // 状态点
            int dotColor = !connected ? UiTheme.ERROR : (active ? (state.isPaused() ? UiTheme.ACCENT : UiTheme.OK) : UiTheme.TEXT_FAINT);
            UiTheme.fillCapsule(graphics, x + PAD_X, y + PAD_Y + 3, DOT + 2, DOT + 2, dotColor);

            int textX = x + PAD_X + DOT + 6;
            int textW = width - (textX - x) - PAD_X;
            int textY = y + PAD_Y;
            graphics.drawString(font, trim(title, font, textW), textX, textY,
                    connected ? UiTheme.TEXT : UiTheme.TEXT_DIM, true);
            textY += LINE_H;
            if (!line2.isEmpty()) {
                graphics.drawString(font, trim(line2, font, textW), textX, textY, UiTheme.TEXT, false);
                textY += LINE_H;
            }
            if (!line3.isEmpty()) {
                graphics.drawString(font, trim(line3, font, textW), textX, textY, UiTheme.TEXT_DIM, false);
            }

            // 底部极细进度条（DYNAMIC_ISLAND_PROGRESS_HEIGHT 0.75~4 设计像素）
            if (active) {
                double progress = state.progress();
                int barY = y + height - PROGRESS_H - 1;
                UiTheme.progressBar(graphics, x + PAD_X, barY, width - PAD_X * 2, PROGRESS_H,
                        progress, 0x33FFFFFF, UiTheme.ACCENT);
            }
        } catch (Throwable t) {
            // HUD 渲染异常绝不能影响游戏主循环
            com.feiyu.readerbridge.ReaderBridgeMod.LOGGER.error("[readerbridge] HUD 渲染异常（已忽略）", t);
        }
    }

    // ---------------------------------------------------------------- AUI 分支的数据整理
    // 这几个方法只处理纯 Java 值，方法签名里没有任何 AUI 类型。

    /** 状态点的语义：off=前置/App 没连上，idle=连上了但没在阅读，paused=暂停，ok=正在读。 */
    private static String auiStateKey(boolean connected, boolean active, ReadingState state) {
        if (!connected) {
            return "off";
        }
        if (!active) {
            return "idle";
        }
        return state.isPaused() ? "paused" : "ok";
    }

    /** 书名栏：未连接 / 空闲沿用原版胶囊的文案，读起来两套风格一致。 */
    private static String auiTitle(boolean connected, boolean active, ReadingState state) {
        if (!connected) {
            return "App 未连接";
        }
        if (!active) {
            return "阅读器 · 空闲";
        }
        return orDash(state.bookTitle());
    }

    private static String auiChapter(boolean active, ReadingState state) {
        return active ? orDash(state.chapterTitle()) : "";
    }

    private static String auiSentence(boolean active, ReadingState state) {
        return active ? orDash(state.currentSentence()) : "";
    }

    /** 进度千分比 0~1000（整数传参，页面用整数避免小数解析差异）。 */
    private static int auiPermille(boolean active, ReadingState state) {
        if (!active) {
            return 0;
        }
        double progress = state.progress();
        if (!(progress > 0.0D)) {
            return 0;
        }
        if (progress > 1.0D) {
            return 1000;
        }
        return (int) Math.round(progress * 1000.0D);
    }

    private static String buildProgressLine(ReadingState state) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("%.0f%%", state.progress() * 100.0));
        if (state.isPaused()) {
            line.append(" · 已暂停");
        }
        String chapter = state.chapterTitle();
        if (chapter != null && !chapter.isEmpty()) {
            line.append(" · ").append(chapter);
        }
        return line.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String trim(String text, Font font, int maxWidth) {
        if (text == null) {
            return "";
        }
        return font.plainSubstrByWidth(text, Math.max(10, maxWidth));
    }
}
