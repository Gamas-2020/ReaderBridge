package com.feiyu.readerbridge.client.world;

import java.util.Locale;

/**
 * 世界文字的「停留方式」预设（配置项 {@code anchorPreset}）。
 *
 * <p>三档语义：</p>
 *
 * <ul>
 *   <li><b>{@link #FOLLOW}</b>（默认）—— 抄自 MuoniumPlayer 的 {@code WorldLyricsState}：
 *       首次 / 传送 / 越界时在世界坐标上 spawn 一次，之后整段跟随眼位平移以保持距离，
 *       转头飘出视野时由临界阻尼弹簧沿视线拉回。实现全部在 {@link LyricAnchor}。</li>
 *   <li><b>{@link #STAY}</b> —— 原地停留：spawn 一次后<b>完全不跟随</b>（不调
 *       {@link LyricAnchor#follow}、不调 {@link LyricAnchor#clampIntoView}）。
 *       文字钉在那个世界坐标上，玩家走动、转头、转身都不动，走出视野就看不见了。
 *       只在「首次出现」与「锚点越界」（{@link LyricAnchor#isSane} 判假，通常意味着传送）
 *       两种情况重新 spawn。</li>
 *   <li><b>{@link #HEAD}</b> —— 视线跟随：每帧把锚点钉在相机空间正前方固定距离处，
 *       因为 renderer 已经把 modelview 置成单位阵，顶点就在相机空间，锚点坐标直接取
 *       {@code (0, 0, -distance)}（再加一点向下沉降）。永远在视野正中，不需要世界坐标
 *       缓存、不需要弹簧、不需要回屏。</li>
 * </ul>
 *
 * <p>本枚举刻意<b>不引用配置类</b>：解析入口 {@link #fromId(String)} 由调用方
 * {@code WorldTextRenderer} 传入 {@code ReaderBridgeConfig.anchorPreset()} 的结果，
 * 于是这里保持纯逻辑、零 Minecraft 依赖，可单独做单元测试（与 {@link TextFade} 同一约定）。</p>
 */
public enum AnchorMode {

    /** 跟随（默认）：spawn 后整段跟随眼位并回屏。 */
    FOLLOW("follow"),

    /** 原地停留：spawn 后钉死在那个世界坐标上。 */
    STAY("stay"),

    /** 视线跟随：每帧贴在相机空间正前方固定距离。 */
    HEAD("head");

    private final String id;

    AnchorMode(String id) {
        this.id = id;
    }

    /** 配置里的字符串写法（小写驼峰，与 {@code ReaderBridgeConfig.anchorPreset()} 返回值一致）。 */
    public String id() {
        return id;
    }

    /**
     * 字符串 → 枚举。无法识别（{@code null} / 空白 / 拼错）时回落到 {@link #FOLLOW}，
     * 与 {@code ReaderBridgeConfig.anchorPreset()} 的兜底策略保持一致。
     */
    public static AnchorMode fromId(String raw) {
        if (raw != null) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            for (AnchorMode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode;
                }
            }
        }
        return FOLLOW;
    }
}
