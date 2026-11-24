package com.feiyu.readerbridge.client.world;

/**
 * 换句时间轴：在 {@link TextFade}（交叉淡化状态机）之上补一项
 * 「当前句是从哪一刻开始显示的」以及「这一句是不是首次出现」。
 *
 * <p>为什么需要它：七档动效里只有 {@code crossfade} / {@code slideUp} 关心 alpha 曲线，
 * 其余五档（打字机、跳动、波浪、文闪、硬切）都只关心<b>时间</b>——</p>
 *
 * <ul>
 *   <li>打字机的第 i 个字在 {@code i * subtitleFxMsPerChar} 毫秒后出现；</li>
 *   <li>跳动的每个字要知道「自己出现后过了多久」（弹一下 200ms 落回）；</li>
 *   <li>上滑要知道整句滑了多久（时长读 {@code worldTextFadeInMs}）。</li>
 * </ul>
 *
 * <p>所以这里把 {@link TextFade} 包一层：句子变化的判定、上一句的峰值 alpha、淡出回收
 * 全部沿用 {@link TextFade} 的既有语义（保证 {@code crossfade} 与改动前逐项一致），
 * 额外只记录一个起始纳秒。检测用「update 前后 {@code currentText()} 是否变化」，
 * 而不是再写一份字符串比较，避免两处判定出现微妙不一致。</p>
 *
 * <p><b>首句不做动效</b>：状态机首次收到句子时 {@link #isInstant()} 为 {@code true}，
 * 各档据此直接按「已经播放完」处理（打字机整句立刻全显、上滑直接到位）。
 * 理由与 {@link TextFade} 一致：上游状态偶尔抖动（{@code isActive} 短暂为 false、句子短暂为空）
 * 都会把状态机清空，若首句也播放动画，恢复时就会看到整句重打一遍。</p>
 *
 * <p>时间同样只接受 {@link System#nanoTime()} 的单调纳秒值；线程约束：只在客户端渲染线程调用。</p>
 */
public final class SubtitleAnimator {

    private static final double NANOS_PER_MS = 1_000_000.0D;

    /** 交叉淡化状态机：句子切换、上一句峰值、淡出回收都在里面。 */
    private final TextFade fade = new TextFade();

    /** 当前句开始显示的时刻（纳秒）。只在句子发生变化时刷新。 */
    private long currentStartNanos;

    /** 当前句是否是「状态机里的第一句」（不播放动画）。 */
    private boolean currentInstant = true;

    /**
     * 推进状态机：句子变化时把旧句降级为上一句，并刷新本帧的起始时间。
     *
     * <p>本方法每帧<b>必须</b>调用一次（在渲染前），否则淡出残影不会被回收。</p>
     *
     * @param sentence  当前句子（调用方已 strip 并保证非空）
     * @param nowNanos  {@link System#nanoTime()}
     * @param fadeInMs  新句淡入时长（毫秒），{@code <= 0} 表示硬切；对逐字档无意义
     * @param fadeOutMs 旧句淡出时长（毫秒），{@code <= 0} 表示立即丢弃旧句
     *                  （逐字档一律传 0：「旧句直接消失」）
     */
    public void update(String sentence, long nowNanos, int fadeInMs, int fadeOutMs) {
        String before = fade.currentText();
        fade.update(sentence, nowNanos, fadeInMs, fadeOutMs);
        String after = fade.currentText();
        boolean changed = before == null ? after != null : !before.equals(after);
        if (changed) {
            currentStartNanos = nowNanos;
            // 首次出现：TextFade 也是在这个分支里把 currentInstant 置为 true 的。
            currentInstant = before == null;
        }
    }

    /** 清空全部状态（停止朗读、退出世界、关闭世界文字、切换动效预设时调用）。 */
    public void clear() {
        fade.clear();
        currentStartNanos = 0L;
        currentInstant = true;
    }

    /** 当前句文本；{@code null} 表示还没有句子。 */
    public String currentText() {
        return fade.currentText();
    }

    /** 正在淡出的上一句文本；{@code null} 表示没有残影要画。 */
    public String previousText() {
        return fade.previousText();
    }

    /** 当前句本帧的 alpha（语义见 {@link TextFade#currentAlpha(long, int)}）。 */
    public float currentAlpha(long nowNanos, int fadeInMs) {
        return fade.currentAlpha(nowNanos, fadeInMs);
    }

    /** 上一句本帧的 alpha（语义见 {@link TextFade#previousAlpha(long, int)}）。 */
    public float previousAlpha(long nowNanos, int fadeOutMs) {
        return fade.previousAlpha(nowNanos, fadeOutMs);
    }

    /** 当前句是否「瞬间到位」（首次出现，不播放动画）。 */
    public boolean isInstant() {
        return currentInstant;
    }

    /**
     * 当前句从开始显示到现在经过的毫秒数（首帧为 0，单调不减）。
     *
     * @param nowNanos {@link System#nanoTime()}
     */
    public double elapsedMs(long nowNanos) {
        if (fade.currentText() == null) {
            return 0.0D;
        }
        double elapsed = (double) (nowNanos - currentStartNanos) / NANOS_PER_MS;
        return elapsed > 0.0D ? elapsed : 0.0D;
    }

    /**
     * 线性进度，夹到 {@code [0, 1]}，{@code durationMs <= 0} 直接返回 1。
     *
     * <p>与 {@link TextFade} 内部的 {@code ratio} 同语义（这里不直接调用它，因为它是包内可见的
     * 实现细节；两处都必须遵守「时长 ≤ 0 即硬切」这一条，任何一档都靠它退化）。</p>
     *
     * @param elapsedMs  已经过去的毫秒数
     * @param durationMs 总时长（毫秒）
     */
    public static float progress(double elapsedMs, int durationMs) {
        if (durationMs <= 0) {
            return 1.0F;
        }
        if (!(elapsedMs > 0.0D)) {
            return 0.0F;
        }
        double t = elapsedMs / (double) durationMs;
        return t >= 1.0D ? 1.0F : (float) t;
    }
}
