package com.feiyu.readerbridge.client.gui;

import com.google.gson.JsonParseException;

import java.nio.file.Path;

/**
 * 阅读外观设置（字号 / 行距 / 内容宽度 / 背景主题 / 自动跟随滚动），落
 * {@code config/readerbridge/panel_settings.json}。
 *
 * <p>存在理由：{@code config/readerbridge-common.toml}（{@link com.feiyu.readerbridge.config.ReaderBridgeConfig}）
 * 管的是「世界内字幕」的外观，且改动要重进世界；面板的阅读排版属于即时生效的界面状态，
 * 又有多档取值，塞进 ForgeConfigSpec 会变成一堆手工维护的枚举。所以单独一份 JSON。
 *
 * <p>线程约定：字段全部 volatile。{@link #loadAsync()} 在面板构造时提交到
 * {@code rb-panel-io}，读完经 {@code Minecraft.execute} 回主线程写入字段；
 * 落盘同理异步，写盘前先读最新字段，所以连点 +/- 不会丢最后一次值。
 */
final class PanelPrefs {

    /**
     * 字号上下限（设计像素，9px = 原版字面高）。
     *
     * <p>渲染系数见 {@link #textScale()}：{@code textScale() = fontSize / 9}，
     * 所以下限 <b>1 ≈ 0.11 倍</b>字形（极小档，给「字太大挡住画面」的场景用），
     * 上限 72 = <b>8 倍</b>字形。上限从原来的 26（2.9 倍）放到 72，是因为 26 在 2K/4K 屏上依然偏小。</p>
     *
     * <p>下限为什么能到 1 而不是 13：{@link #textScale()} 的下界必须一起松到 0.1，
     * 否则 1~9 号字会被 {@code Math.max(1.0f, ...)} 全夹成 1.0，
     * 用户看到的是「调到 1 号字但字一点没变小」。</p>
     *
     * <p>放到 8 倍不会再把正文右侧切掉：正文裁剪走 {@code LocalReaderScreen#scissorLogical()}，
     * 它先把逻辑矩形按 {@code bodyScale()} 做「逻辑 → 屏幕」换算、再夹回面板矩形
     * （1.20.1 的 scissor 栈是替换而非求交，夹回是必须的），所以放大到任意档都不会被切掉右侧。</p>
     */
    static final int FONT_MIN = 1;
    static final int FONT_MAX = 72;
    static final int FONT_DEFAULT = 14;

    /** 行距按十分位存整数，避免浮点序列化后出现 1.4999999。 */
    static final int SPACING_MIN_TENTHS = 12;
    static final int SPACING_MAX_TENTHS = 24;
    static final int SPACING_DEFAULT_TENTHS = 15;

    /** 内容宽度百分比：100% = 铺满正文区，50% = 居中窄栏（长行更好读）。 */
    static final int WIDTH_MIN_PCT = 50;
    static final int WIDTH_MAX_PCT = 100;
    static final int WIDTH_DEFAULT_PCT = 100;

    private static final String FILE_NAME = "panel_settings.json";
    private static final int VERSION = 1;

    /** JSON 结构；字段名即键名。 */
    static final class Data {
        int version = VERSION;
        int fontSize = FONT_DEFAULT;
        int lineSpacingTenths = SPACING_DEFAULT_TENTHS;
        int contentWidthPct = WIDTH_DEFAULT_PCT;
        int theme = PanelSkin.DARK;
        boolean autoFollow = true;
    }

    private volatile int fontSize = FONT_DEFAULT;
    private volatile int lineSpacingTenths = SPACING_DEFAULT_TENTHS;
    private volatile int contentWidthPct = WIDTH_DEFAULT_PCT;
    private volatile int theme = PanelSkin.DARK;
    private volatile boolean autoFollow = true;

    private volatile boolean saveQueued;

    // ---------------------------------------------------------------- 读

    int fontSize() {
        return fontSize;
    }

    /** 行距倍率（1.2 ~ 2.4）。 */
    float lineSpacing() {
        return lineSpacingTenths / 10.0f;
    }

    int contentWidthPct() {
        return contentWidthPct;
    }

    int theme() {
        return theme;
    }

    PanelSkin skin() {
        return PanelSkin.of(theme);
    }

    boolean autoFollow() {
        return autoFollow;
    }

    /**
     * 正文字形缩放系数：原版字形 9px 高，字号 13 即 1.44 倍；字号 1 = 0.11 倍。
     *
     * <p>正文渲染在 {@code pose().scale(s, s, 1)} 下按「逻辑像素」排版，
     * 物理宽度 = 逻辑宽度 × s，所以折行宽度要先除以 s。</p>
     *
     * <p><b>下界必须是 0.1 而不是 1.0</b>：{@link #FONT_MIN} 已经是 1，
     * 旧实现 {@code Math.max(1.0f, fontSize / 9.0f)} 会把 1~9 号字全部夹到 1.0，
     * 等于「调到 1 号字但字一点没变小」。0.1 只用来挡住 0 / 负数（防除零），
     * 实际最低档 1/9 = 0.111 远在它之上，不会被夹。</p>
     */
    float textScale() {
        return Math.max(0.1f, fontSize / 9.0f);
    }

    /** 正文逻辑行高（未乘缩放）：9 × 行距。 */
    int lineHeight() {
        return Math.max(8, Math.round(9.0f * lineSpacing()));
    }

    // ---------------------------------------------------------------- 改（只在主线程调用）

    /**
     * 调字号，{@code delta} 只取 ±1（调用方 {@code PanelExtras} 传 -1 / +1）。
     *
     * <p>步进按<b>当前字号</b>自适应：1 → 72 每档只 ±1 要按 71 次，不可用。
     * 小字号要精细（1），中大字号每按一次跨 2 / 4 才不磨人。阈值 24 / 44 使
     * 1→72 全程 23 + 10 + 7 = 40 次点击可达。</p>
     *
     * <p>极小档（{@code FONT_MIN = 1}）必须能一格格加上去：{@code fontSize < 24} 时
     * 步进恒为 1，所以 1→23 是 22 次点击、每次 +1，不会跳档。</p>
     *
     * <p>步进必须用 {@code fontSize} 而非 {@code next} 算：换成 next 的档位后，
     * 阈值附近会出现「加一次跳 2、减一次回 1」的抖动。向下时同样按当前字号取档，
     * 所以 24 减一次得 22、22 加一次得 23，不会在阈值上反复横跳。</p>
     */
    void adjustFontSize(int delta) {
        // 步进随字号自适应：小字号要精细，大字号每点一次跨 4 像素才不磨人。
        // 阈值取 24 / 44：从 1 到 72 全程按 1 / 2 / 4 递进，极小档恒为 1。
        int step = fontSize >= 44 ? 4 : (fontSize >= 24 ? 2 : 1);
        int next = clamp(fontSize + delta * step, FONT_MIN, FONT_MAX);
        if (next == fontSize) {
            return;
        }
        fontSize = next;
        saveAsync();
    }

    void adjustLineSpacing(int deltaTenths) {
        int next = clamp(lineSpacingTenths + deltaTenths, SPACING_MIN_TENTHS, SPACING_MAX_TENTHS);
        if (next == lineSpacingTenths) {
            return;
        }
        lineSpacingTenths = next;
        saveAsync();
    }

    void adjustContentWidth(int deltaPct) {
        int next = clamp(contentWidthPct + deltaPct, WIDTH_MIN_PCT, WIDTH_MAX_PCT);
        if (next == contentWidthPct) {
            return;
        }
        contentWidthPct = next;
        saveAsync();
    }

    void setTheme(int next) {
        int clamped = clamp(next, 0, PanelSkin.COUNT - 1);
        if (clamped == theme) {
            return;
        }
        theme = clamped;
        saveAsync();
    }

    void toggleAutoFollow() {
        autoFollow = !autoFollow;
        saveAsync();
    }

    /** 恢复默认（不动主题之外的持久化文件）。 */
    void resetDefaults() {
        fontSize = FONT_DEFAULT;
        lineSpacingTenths = SPACING_DEFAULT_TENTHS;
        contentWidthPct = WIDTH_DEFAULT_PCT;
        autoFollow = true;
        saveAsync();
    }

    // ---------------------------------------------------------------- 落盘

    void loadAsync() {
        PanelIo.background(() -> {
            String text = PanelIo.readText(file());
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            Data data;
            try {
                data = PanelIo.GSON.fromJson(text, Data.class);
            } catch (JsonParseException e) {
                System.err.println("[readerbridge] 面板设置存档损坏，按默认值处理：" + e);
                return;
            }
            if (data == null) {
                return;
            }
            PanelIo.onMain(() -> apply(data));
        });
    }

    private void apply(Data data) {
        fontSize = clamp(data.fontSize, FONT_MIN, FONT_MAX);
        lineSpacingTenths = clamp(data.lineSpacingTenths, SPACING_MIN_TENTHS, SPACING_MAX_TENTHS);
        contentWidthPct = clamp(data.contentWidthPct, WIDTH_MIN_PCT, WIDTH_MAX_PCT);
        theme = clamp(data.theme, 0, PanelSkin.COUNT - 1);
        autoFollow = data.autoFollow;
    }

    /** 同一次连点只排队一次写盘；写盘前重新取字段，最后一次的值一定落得下去。 */
    void saveAsync() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        PanelIo.background(() -> {
            saveQueued = false;
            PanelIo.writeText(file(), PanelIo.GSON.toJson(snapshot()));
        });
    }

    private Data snapshot() {
        Data data = new Data();
        data.fontSize = fontSize;
        data.lineSpacingTenths = lineSpacingTenths;
        data.contentWidthPct = contentWidthPct;
        data.theme = theme;
        data.autoFollow = autoFollow;
        return data;
    }

    private static Path file() {
        return PanelIo.configDir().resolve(FILE_NAME);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
