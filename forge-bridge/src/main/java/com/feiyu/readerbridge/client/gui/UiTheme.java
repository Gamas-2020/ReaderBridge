package com.feiyu.readerbridge.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 界面视觉语言：照抄 MuoniumPlayer 的层次（见 {@code .research/MuoniumPlayer/1.20.1 recode}
 * 的 {@code core/screens/ncm/CoverflowOverlay.java:207}（全屏黑 0.5 alpha 遮罩）、
 * {@code :467}（条目悬停用白色 0.2 alpha 叠加）、{@code :262}（条目间距 24 设计像素）），
 * 但全部用 Minecraft 原生 {@link GuiGraphics#fill} 还原——不引入 Skija。
 *
 * <p>圆角是「逐行 fill」近似：每一行按圆方程算内缩量，视觉上等价于 Skia 的
 * {@code roundRect(radius)}，只在角上产生 1px 阶梯（半径 ≤ 8 时几乎不可见）。</p>
 */
public final class UiTheme {

    private UiTheme() {
    }

    // 颜色不再是编译期常量：面板支持「深色 / 浅色 / 护眼」换肤，由
    // {@link #applySkin(PanelSkin)} 在渲染前写入。深色就是下面这些默认值，
    // 未调用 applySkin 时观感与换肤前逐像素一致。

    /** 全屏遮罩：黑 0.5 alpha（CoverflowOverlay:207 同款）。 */
    public static int SCRIM = 0x80000000;
    /** 面板底色：近黑、接近不透明，保证文字可读。 */
    public static int PANEL = 0xF0151519;
    /** 次级面（卡片、输入框底）。 */
    public static int CARD = 0x33FFFFFF;
    /** 内容卡片：比 PANEL 亮一档，弱于 {@link #HOVER}（阅读面板的卡片底）。 */
    public static int CARD_SOFT = 0x14FFFFFF;
    /** 列表行的斑马底，最弱一档。 */
    public static int CARD_FAINT = 0x0AFFFFFF;
    /** 分割线/描边。 */
    public static int STROKE = 0x40FFFFFF;
    /** 条目悬停高亮：白 0.2（CoverflowOverlay:467 的 hoverAlpha=0.2）。 */
    public static int HOVER = 0x33FFFFFF;
    /** 条目选中态：白 0.12 + 左侧强调条。 */
    public static int SELECTED = 0x1FFFFFFF;

    /** 强调色（封面/进度/选中条）：取视觉参考里最活跃的红，深色底上对比足够。 */
    public static int ACCENT = 0xFFFF3B30;
    public static int ACCENT_DIM = 0x66FF3B30;

    public static int TEXT = 0xFFFFFFFF;
    public static int TEXT_DIM = 0xFF9AA0A6;
    public static int TEXT_FAINT = 0xFF6B7075;
    public static int OK = 0xFF55D98C;
    public static int ERROR = 0xFFFF6B6B;

    /** 已经写进上面这些字段的皮肤编号；-1 = 还没套过（保持深色默认值）。 */
    private static int appliedTheme = -1;

    /**
     * 套用面板皮肤。
     *
     * <p>只在客户端主线程、面板渲染前调用（{@code LocalReaderScreen#syncTheme}）。
     * 同一个编号重复调用直接返回，避免每帧写 17 个静态字段。
     *
     * <p>注意：这是<b>全局</b>的 —— {@code ReaderBridgeHud} 也用这些常量，
     * 所以面板选了浅色时，游戏内 HUD 会一起变浅。这是刻意的（同一位用户在同一台机器上
     * 本来就希望统一观感），也是不新造第二套配色体系的原因。
     */
    static void applySkin(PanelSkin skin) {
        if (skin == null || skin.theme == appliedTheme) {
            return;
        }
        appliedTheme = skin.theme;
        SCRIM = skin.scrim;
        PANEL = skin.panel;
        CARD = skin.cardBg;
        CARD_SOFT = skin.cardBg;
        CARD_FAINT = skin.cardFaint;
        STROKE = skin.stroke;
        HOVER = skin.hover;
        SELECTED = skin.selected;
        ACCENT = skin.accent;
        ACCENT_DIM = skin.accentDim;
        TEXT = skin.text;
        TEXT_DIM = skin.textDim;
        TEXT_FAINT = skin.textFaint;
        OK = skin.ok;
        ERROR = skin.error;
    }

    /** 圆角半径（设计像素，与 CoverflowOverlay 的 24 间距成比例缩小到我们的面板尺度）。 */
    public static final int RADIUS_PANEL = 6;
    public static final int RADIUS_CARD = 4;
    public static final int RADIUS_CAPSULE = 11;

    /**
     * 填充圆角矩形。
     *
     * @param radius 圆角半径，内部按 min(radius, w/2, h/2) 裁剪
     */
    public static void fillRounded(GuiGraphics graphics, int x, int y, int w, int h,
                                   int radius, int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
        if (r == 0) {
            graphics.fill(x, y, x + w, y + h, argb);
            return;
        }
        // 中间大块
        graphics.fill(x, y + r, x + w, y + h - r, argb);
        // 上下两端：逐行收窄
        for (int i = 0; i < r; i++) {
            int inset = circleInset(i, r);
            graphics.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
            graphics.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, argb);
        }
    }

    /** 圆角矩形描边（1px，四角近似）。 */
    public static void strokeRounded(GuiGraphics graphics, int x, int y, int w, int h,
                                     int radius, int argb) {
        if (w <= 1 || h <= 1) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
        graphics.fill(x + r, y, x + w - r, y + 1, argb);
        graphics.fill(x + r, y + h - 1, x + w - r, y + h, argb);
        graphics.fill(x, y + r, x + 1, y + h - r, argb);
        graphics.fill(x + w - 1, y + r, x + w, y + h - r, argb);
        for (int i = 0; i < r; i++) {
            int inset = circleInset(i, r);
            graphics.fill(x + inset, y + i, x + inset + 1, y + i + 1, argb);
            graphics.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, argb);
            graphics.fill(x + inset, y + h - i - 1, x + inset + 1, y + h - i, argb);
            graphics.fill(x + w - inset - 1, y + h - i - 1, x + w - inset, y + h - i, argb);
        }
    }

    /** 胶囊（HUD/进度条底）的圆角矩形。 */
    public static void fillCapsule(GuiGraphics graphics, int x, int y, int w, int h, int argb) {
        fillRounded(graphics, x, y, w, h, Math.min(h / 2, RADIUS_CAPSULE), argb);
    }

    /**
     * 进度条：底槽 + 已完成部分，两端圆角。
     *
     * @param progress 0..1，越界会被裁剪
     */
    public static void progressBar(GuiGraphics graphics, int x, int y, int w, int h,
                                   double progress, int trackColor, int fillColor) {
        if (w <= 0 || h <= 0) {
            return;
        }
        fillCapsule(graphics, x, y, w, h, trackColor);
        double p = Math.max(0.0, Math.min(1.0, progress));
        int filled = (int) Math.round(w * p);
        if (filled > 0) {
            fillCapsule(graphics, x, y, Math.max(filled, h), h, fillColor);
        }
    }

    /** 第 {@code row} 行（自顶部算）在半径 r 圆角内的水平内缩量。 */
    private static int circleInset(int row, int radius) {
        double dy = radius - row - 0.5;
        double dx = Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy));
        return (int) Math.round(radius - dx);
    }
}
