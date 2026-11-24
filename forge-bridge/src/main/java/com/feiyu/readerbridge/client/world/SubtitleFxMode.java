package com.feiyu.readerbridge.client.world;

/**
 * 换句动效预设（配置项 {@code subtitleFx}）。七档全部工作在「相机空间 + 单位 modelview」下，
 * 只影响「怎么把已排好版的行/字写进顶点缓冲」，不影响锚点、GL 状态与 {@code endBatch} 次数。
 *
 * <table border="1">
 *   <caption>七档对照</caption>
 *   <tr><th>档</th><th>逐字</th><th>旧句残影</th><th>观感</th></tr>
 *   <tr><td>{@link #CROSSFADE}</td><td>否</td><td>是（淡出）</td><td>旧句淡出 + 新句淡入同时进行</td></tr>
 *   <tr><td>{@link #TYPEWRITER}</td><td>是</td><td>否（硬切）</td><td>逐字显现，末尾闪烁光标</td></tr>
 *   <tr><td>{@link #TYPEWRITER_BOUNCE}</td><td>是</td><td>否（硬切）</td><td>逐字显现 + 每个新字向上弹一下</td></tr>
 *   <tr><td>{@link #WAVE}</td><td>是</td><td>否（硬切）</td><td>整句常驻，每字按正弦上下漂浮</td></tr>
 *   <tr><td>{@link #SLIDE_UP}</td><td>否</td><td>是（淡出）</td><td>新句从下方滑入并淡入</td></tr>
 *   <tr><td>{@link #GLOW}</td><td>是</td><td>否（硬切）</td><td>整句常驻，逐字亮度脉动（DangoMusic「文闪」）</td></tr>
 *   <tr><td>{@link #NONE}</td><td>否</td><td>否（硬切）</td><td>直接替换，连淡入淡出都当 0</td></tr>
 * </table>
 *
 * <p>同样刻意不引用配置类：解析入口 {@link #fromId(String)} 由 {@code WorldTextRenderer}
 * 传入 {@code ReaderBridgeConfig.subtitleFx()}，保持纯逻辑可测。</p>
 */
public enum SubtitleFxMode {

    /** 交叉淡化（默认）：沿用 {@link TextFade} 语义。 */
    CROSSFADE("crossfade", false, true),

    /** 打字机：逐字显现，末尾闪烁光标。 */
    TYPEWRITER("typewriter", true, false),

    /** 打字机 + 跳动。 */
    TYPEWRITER_BOUNCE("typewriterBounce", true, false),

    /** 波浪：整句常驻，逐字正弦浮动。 */
    WAVE("wave", true, false),

    /** 上滑：整句从下方向上滑入。 */
    SLIDE_UP("slideUp", false, true),

    /** 文闪：整句常驻，逐字亮度脉动。 */
    GLOW("glow", true, false),

    /** 硬切：淡入淡出都按 0 处理。 */
    NONE("none", false, false);

    private final String id;
    private final boolean perChar;
    private final boolean previousSentence;

    SubtitleFxMode(String id, boolean perChar, boolean previousSentence) {
        this.id = id;
        this.perChar = perChar;
        this.previousSentence = previousSentence;
    }

    /** 配置里的字符串写法，与 {@code ReaderBridgeConfig.subtitleFx()} 的返回值一致（区分大小写）。 */
    public String id() {
        return id;
    }

    /** 是否逐字绘制（每个 code point 一次 {@code Font.drawInBatch}）。 */
    public boolean perChar() {
        return perChar;
    }

    /**
     * 是否需要「上一句残影」。
     *
     * <p>{@code false} 的档在推进换句状态机时把淡出时长按 0 传下去，
     * {@link TextFade#update} 会立刻丢弃上一句 —— 也就是「旧句直接消失」。</p>
     */
    public boolean usesPreviousSentence() {
        return previousSentence;
    }

    /**
     * 字符串 → 枚举。无法识别时回落到 {@link #CROSSFADE}，与
     * {@code ReaderBridgeConfig.subtitleFx()} 的兜底策略一致。
     *
     * <p>注意：配置类是精确匹配（{@code "typewriterBounce"} 区分大小写），这里先精确匹配，
     * 匹配不到再尝试小写比较，容忍 {@code "TypewriterBounce"} 这类手改配置。</p>
     */
    public static SubtitleFxMode fromId(String raw) {
        if (raw == null) {
            return CROSSFADE;
        }
        String id = raw.trim();
        for (SubtitleFxMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        for (SubtitleFxMode mode : values()) {
            if (mode.id.toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return mode;
            }
        }
        return CROSSFADE;
    }
}
