package com.feiyu.readerbridge.client.world;

/**
 * 世界内 3D 文字的「换句交叉淡化」状态机：纯逻辑，不依赖 Minecraft / GL / 配置类，
 * 因此可以单独做单元测试（{@link #ratio(long, int)} 与 alpha 计算完全不碰渲染）。
 *
 * <p>语义只有三条：</p>
 *
 * <ol>
 *   <li><b>当前句</b>（incoming）：{@link #update} 收到的句子。它是唯一「会一直留在屏幕上」
 *       的一份；句子没变时它的淡入进度继续累积，句子变了就换人。</li>
 *   <li><b>上一句</b>（outgoing {@code previous}）：被替换下来的那一句，从「被替换的那一刻」
 *       开始按淡出时长把 alpha 从它的<b>峰值亮度</b>降到 0，降完立即丢弃。
 *       刻意只保留一份 —— 连续快速换句时更早的句子直接扔掉，不会叠出一串残影。</li>
 *   <li><b>时长 ≤ 0 即硬切</b>：{@link #ratio(long, int)} 在 {@code durationMs <= 0} 时直接返回
 *       1（视为「进度已完成」），于是淡入 alpha 立刻为 1、淡出 alpha 立刻为 0，
 *       退化成改动前的行为，不会出现「淡出卡在中间」或者半透明残影。</li>
 * </ol>
 *
 * <p>首次出现（{@code current == null} 时收到第一句）<b>不做淡入</b>，直接 alpha = 1：
 * 上游状态偶尔抖动（{@code isActive} 短暂为 false、句子短暂为空）都会把状态机清空，
 * 若首次也淡入，恢复时就会看到一段忽明忽暗的重播；直接全亮最稳，也和改动前的观感一致。</p>
 *
 * <p>时间只接受 {@link System#nanoTime()} 的单调纳秒值：渲染帧率与 tick 无关，
 * 不能用 {@code currentTimeMillis}，也不能挂在 tick 事件上。</p>
 *
 * <p>线程约束：只在客户端渲染线程调用。</p>
 */
public final class TextFade {

    /** 毫秒 → 纳秒。 */
    private static final long NANOS_PER_MS = 1_000_000L;

    /** 当前应当完整显示的句子；{@code null} 表示状态机还没有任何句子。 */
    private String current;

    /** 正在淡出的上一句；{@code null} 表示没有（首次出现 / 已经淡完 / 硬切丢弃）。 */
    private String previous;

    /** 当前句开始显示的时刻。 */
    private long currentStartNanos;

    /** 上一句开始淡出的时刻（即它被替换掉的时刻）。 */
    private long previousStartNanos;

    /** 当前句是否「瞬间到位」（首次出现，不播放淡入）。 */
    private boolean currentInstant;

    /** 上一句开始淡出时的亮度峰值：快速连发时旧句可能还在淡入，不能让残影先跳亮再变暗。 */
    private float previousPeakAlpha = 1.0F;

    /**
     * 推进状态机：句子变化时把旧句降级为「上一句」，并回收已经淡完的上一句。
     *
     * <p>本方法每帧<b>必须</b>调用一次（在渲染前），否则淡出残影不会被回收。</p>
     *
     * @param sentence  当前应当显示的句子（调用方已 strip 并保证非空）；{@code null} 表示没有内容
     * @param nowNanos  {@link System#nanoTime()}
     * @param fadeInMs  新句淡入时长（毫秒），{@code <= 0} 表示硬切
     * @param fadeOutMs 旧句淡出时长（毫秒），{@code <= 0} 表示立即丢弃旧句
     */
    public void update(String sentence, long nowNanos, int fadeInMs, int fadeOutMs) {
        if (sentence != null && !sentence.equals(current)) {
            if (current != null) {
                // 旧句从「被替换的这一刻」开始淡出 —— 不能沿用 currentStartNanos，
                // 否则一句已经显示了十几秒的话，替换瞬间就会算出 alpha = 0，残影根本看不到。
                previousPeakAlpha = currentAlpha(nowNanos, fadeInMs);
                previous = current;
                previousStartNanos = nowNanos;
            }
            currentInstant = current == null;
            current = sentence;
            currentStartNanos = nowNanos;
        }
        prunePrevious(nowNanos, fadeOutMs);
    }

    /** 丢掉已经淡完（或时长为 0 ⇒ 硬切）的上一句。 */
    private void prunePrevious(long nowNanos, int fadeOutMs) {
        if (previous == null) {
            return;
        }
        if (fadeOutMs <= 0 || nowNanos - previousStartNanos >= fadeOutMs * NANOS_PER_MS) {
            previous = null;
            previousPeakAlpha = 1.0F;
        }
    }

    /**
     * 清空全部淡化状态（停止朗读、退出世界、关闭世界文字时调用）。
     *
     * <p>只做字段复位，不涉及锚点 —— 锚点由调用方自己 {@code reset()}。</p>
     */
    public void clear() {
        current = null;
        previous = null;
        currentStartNanos = 0L;
        previousStartNanos = 0L;
        currentInstant = false;
        previousPeakAlpha = 1.0F;
    }

    /** 当前句文本；{@code null} 表示还没有句子。 */
    public String currentText() {
        return current;
    }

    /** 正在淡出的上一句文本；{@code null} 表示没有残影要画。 */
    public String previousText() {
        return previous;
    }

    /**
     * 当前句本帧的 alpha。
     *
     * @param fadeInMs 淡入时长，{@code <= 0} ⇒ 直接 1
     */
    public float currentAlpha(long nowNanos, int fadeInMs) {
        if (current == null) {
            return 0.0F;
        }
        if (currentInstant) {
            return 1.0F;
        }
        return ratio(nowNanos - currentStartNanos, fadeInMs);
    }

    /**
     * 上一句本帧的 alpha（从 {@link #previousPeakAlpha} 线性降到 0）。
     *
     * @param fadeOutMs 淡出时长，{@code <= 0} ⇒ 直接 0（调用方应跳过绘制）
     */
    public float previousAlpha(long nowNanos, int fadeOutMs) {
        if (previous == null) {
            return 0.0F;
        }
        float remain = 1.0F - ratio(nowNanos - previousStartNanos, fadeOutMs);
        return previousPeakAlpha * remain;
    }

    /**
     * 线性进度，夹在 {@code [0, 1]}。
     *
     * <p>{@code durationMs <= 0} 一律返回 1：调用方据此得到「淡入 = 1 / 淡出 = 0」，
     * 也就是硬切。这是唯一的退化路径，不需要额外的分支。</p>
     *
     * @param elapsedNanos 已经过去的纳秒数
     * @param durationMs   总时长（毫秒）
     */
    static float ratio(long elapsedNanos, int durationMs) {
        if (durationMs <= 0) {
            return 1.0F;
        }
        if (elapsedNanos <= 0L) {
            return 0.0F;
        }
        double t = (double) elapsedNanos / ((double) durationMs * (double) NANOS_PER_MS);
        if (t >= 1.0D) {
            return 1.0F;
        }
        return (float) t;
    }
}
