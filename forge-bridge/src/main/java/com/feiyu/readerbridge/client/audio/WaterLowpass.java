package com.feiyu.readerbridge.client.audio;

/**
 * 水下闷声：在 PCM 交给 {@code alBufferData} 之前先做一遍二阶 Butterworth 低通
 * （RBJ biquad，直接形式 I），每声道一套独立状态。
 *
 * <p><b>为什么不走 OpenAL EFX</b>：{@code EXTEfx.AL_FILTER_LOWPASS} 能否真的听到，取决于驱动对
 * {@code ALC_EXT_EFX} 的实现；实测某些环境挂上 direct filter 后完全没有变化。软件滤波只依赖 JDK
 * 的 double 运算，与驱动无关，必然生效，也便于回归测试。</p>
 *
 * <p><b>常数与公式出处</b>（只读参考 {@code .research/MuoniumPlayer}，仓库内
 * {@code muonium-common/src/main/java/com/muoniumplayer/core/ncm/music/acoustics/}）：</p>
 * <ul>
 *   <li>截止频率 420 Hz：{@code SceneResponse.java:418} {@code BAND_SUBMERGED_HZ = 420.0}</li>
 *   <li>响度 ×0.82：{@code SceneResponse.java:420} {@code SUBMERGED_LOUDNESS = 0.82f}，
 *       应用点 {@code SceneResponse.java:56-57}（{@code band = Math.min(band, BAND_SUBMERGED_HZ);
 *       loud *= SUBMERGED_LOUDNESS;}）</li>
 *   <li>差分方程：{@code SceneAcoustics.java:609-615} {@code biquad()}：
 *       {@code out = B0*in + B1*x1 + B2*x2 - A1*y1 - A2*y2}</li>
 *   <li>RBJ 低通系数：{@code SceneAcoustics.java:617-626} {@code designLowpass()}
 *       （含 {@code Math.min(fc, rate * 0.475)} 的 Nyquist 保护）</li>
 *   <li>Q = 1/√2：{@code SceneAcoustics.java:36} {@code BUTTERWORTH_Q = 0.7071067811865476}</li>
 *   <li>每声道一套状态：{@code SceneAcoustics.java:630} {@code bandState = new double[channels][4]}，
 *       左右各滤一次见 {@code SceneAcoustics.java:332-335}</li>
 *   <li>软限幅防瞬态过冲：{@code SceneAcoustics.java:599-603} {@code softLimit()}，
 *       拐点 {@code LIMITER_KNEE = 0.89} 见 {@code SceneAcoustics.java:37}</li>
 * </ul>
 *
 * <p><b>线程约束</b>：本类是纯函数工具类，没有静态可变状态，任何线程调用结果都一样；
 * 本 mod 里只在渲染主线程（{@link OpenAlTtsPlayer} 的水陆切换路径）调用，单次全片处理，
 * 处理完即缓存，不会每帧重算。</p>
 */
public final class WaterLowpass {

    /** 水下低通截止频率（Hz）。 */
    public static final int CUTOFF_HZ = 420;

    /** 低通后的整体响度（1.0 = 不衰减）；对照 MuoniumPlayer 的 SUBMERGED_LOUDNESS。 */
    public static final float GAIN = 0.82f;

    /** 二阶 Butterworth 的 Q（=1/√2），对应 RBJ 低通里的 {@code sin(w)/(2Q)}。 */
    private static final double BUTTERWORTH_Q = 0.7071067811865476d;

    /** 软限幅拐点；低于它的样本原样通过，高于它的瞬态平滑压到 1.0 以下。 */
    private static final double LIMITER_KNEE = 0.89d;

    /** 声道状态上限：mp3 只有 mono/stereo，多余声道折算到这两套状态。 */
    private static final int MAX_CHANNELS = 2;

    /** PCM16 → [-1, 1) 的归一化因子。 */
    private static final double INV_PCM16 = 1.0d / 32768.0d;

    /** [-1, 1] → PCM16 的回缩放因子。 */
    private static final double PCM16_SCALE = 32767.0d;

    private WaterLowpass() {
    }

    /**
     * 对交错 PCM16 做水下低通，返回新数组（不修改入参）。
     *
     * <p>每次调用滤波器状态都从零开始（不做跨批次记忆），因此同一段音频反复处理结果一致；
     * 开头几个样本有极短的起振过渡（时间常数约 1/(2π·420Hz) ≈ 0.38ms），可忽略。</p>
     *
     * <p>纯计算、无锁、无共享状态；但它是 O(样本数) 的整片扫描，别在高频路径里反复调用——
     * {@link OpenAlTtsPlayer} 会在水陆首次切换时算一次并缓存。</p>
     *
     * @param interleaved 交错样本（L,R,L,R…）；{@code null} 或空数组返回等长空数组
     * @param channels    声道数（1 或 2；&lt;=0 按 1 处理，&gt;2 按 2 处理）
     * @param sampleRate  采样率（Hz）；&lt;=0 时退化为"只做增益"，不滤波
     * @return 新的 PCM16 数组，长度与入参一致
     */
    public static short[] apply(short[] interleaved, int channels, int sampleRate) {
        final int length = interleaved == null ? 0 : interleaved.length;
        final short[] out = new short[length];
        if (length == 0) {
            return out;
        }
        final int ch = Math.min(Math.max(1, channels), MAX_CHANNELS);
        final boolean filtering = sampleRate > 0;

        // ---- RBJ 低通系数（SceneAcoustics.java:617-626）
        double b0 = 1.0d;
        double b1 = 0.0d;
        double b2 = 0.0d;
        double a1 = 0.0d;
        double a2 = 0.0d;
        if (filtering) {
            final double w = 2.0d * Math.PI * Math.min(CUTOFF_HZ, sampleRate * 0.475d) / sampleRate;
            final double cosW = Math.cos(w);
            final double alpha = Math.sin(w) / (2.0d * BUTTERWORTH_Q);
            final double a0 = 1.0d + alpha;
            b0 = (1.0d - cosW) * 0.5d / a0;
            b1 = (1.0d - cosW) / a0;
            b2 = b0;
            a1 = -2.0d * cosW / a0;
            a2 = (1.0d - alpha) / a0;
        }

        // 每声道一套状态，布局 = [x1, x2, y1, y2]（对照 SceneAcoustics.java:630 / 609-615）
        final double[][] state = new double[ch][4];

        for (int i = 0; i < length; i++) {
            final double x = interleaved[i] * INV_PCM16;
            double y = x;
            if (filtering) {
                final double[] s = state[ch == 1 ? 0 : i % ch];
                // y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]
                y = b0 * x + b1 * s[0] + b2 * s[1] - a1 * s[2] - a2 * s[3];
                s[1] = s[0];
                s[0] = x;
                s[3] = s[2];
                s[2] = y;
            }
            out[i] = toPcm16(softLimit(y * GAIN));
        }
        return out;
    }

    /** 软限幅：拐点以下原样通过，以上按 {@code knee + headroom·over/(headroom+over)} 压向 1.0。 */
    private static double softLimit(double value) {
        final double magnitude = Math.abs(value);
        if (magnitude <= LIMITER_KNEE) {
            return value;
        }
        final double over = magnitude - LIMITER_KNEE;
        final double headroom = 1.0d - LIMITER_KNEE;
        final double limited = LIMITER_KNEE + headroom * over / (headroom + over);
        return value < 0.0d ? -limited : limited;
    }

    /** 归一化浮点 → PCM16，超出 ±1 的（理论上已被 softLimit 压住）先夹紧再四舍五入。 */
    private static short toPcm16(double value) {
        final double clamped = Math.max(-1.0d, Math.min(1.0d, value));
        return (short) Math.round(clamped * PCM16_SCALE);
    }
}
