package com.feiyu.readerbridge.client.audio;

/**
 * 朗读音量的 <b>PCM 数字增益</b>：在 PCM 交给 {@code alBufferData} 之前，对每个样本乘一遍增益。
 *
 * <p><b>为什么不在 OpenAL 层放大</b>：{@code AL_GAIN = 4.0} 只是把混音后的信号整体乘 4，
 * 而 Edge TTS 出来的 mp3 解码后本来就接近满幅 int16（峰值动辄 28000~32700）——
 * 乘 4 之后 {@code |sample| > 8192} 的部分全部撞上 mixer 输出的 ±1.0 硬 clamp，
 * 波形被削成方波。听感上要么「几乎没变响」，要么「只是变糊、刺耳」，
 * 这正是上一轮把 {@code AL_GAIN} 上界从 1.0 放到 4.0 之后依然听不出差别的原因。
 * 数字增益在限幅<b>之前</b>处理：{@code gain <= 1} 纯线性、{@code gain > 1} 走软限幅，
 * 波形连续，同样的响度提升下不会出现方波。</p>
 *
 * <p><b>处理顺序（与 {@link WaterLowpass} 的关系）</b>：调用方必须<b>先低通、后增益</b>。
 * {@link WaterLowpass} 自带 ×0.82 的整体衰减，若先增益再低通，低通会把刚放大上去的幅度
 * 重新压回 0.82 倍，"水下时调音量听不出变化"。增益放在最后一步，任何设定值都完整作用在
 * 水下版本上。</p>
 *
 * <p><b>软限幅公式</b>：{@code out = 32767 * tanh(in * gain / 32767)}。tanh 在 0 附近
 * 与线性重合（{@code tanh(x) ≈ x}），所以小音量段保持原比例放大；幅度越大压缩越强，
 * 渐近到 ±32767 而永不越界，波形始终连续（一阶导数连续，不会有硬削波的折点）。
 * 与线性的偏差随幅度单调增大，在 {@code gain = 4} 时典型值：输入 8192（满幅的 1/4）
 * 线性应为 32768（已削平），tanh 给 24957，即<b>保留 76% 而非削成 100% 方波</b>。</p>
 *
 * <p><b>实现方式（精确、无近似误差）</b>：int16 只有 32769 个可能的绝对值，
 * tanh 映射是「输入幅度 → 输出幅度」的一维函数，所以按当前增益先算一张
 * {@code short[32769]} 查找表（32769 次 {@link Math#tanh}，约 1~3ms），再逐样本查表。
 * 结果与逐个样本直接算 {@code Math.tanh} <b>完全一致</b>（同一个公式、同样四舍五入），
 * 没有分段近似或插值带来的误差；1.44M 样本的查表耗时在数毫秒量级，实测值见
 * {@code [readerbridge] TTS 数字增益 应用} 日志。</p>
 *
 * <p><b>线程约束</b>：纯函数工具类，没有静态可变状态；本 mod 里只在渲染主线程
 * （{@link OpenAlTtsPlayer} 的上传路径）调用。</p>
 */
public final class PcmGain {

    /** PCM16 满幅值（正侧最大值）。 */
    private static final double PCM16_MAX = 32767.0d;

    /** 查找表长度：绝对值 0 ~ 32768（{@link Short#MIN_VALUE} 的绝对值是 32768）。 */
    private static final int TABLE_SIZE = 32769;

    private PcmGain() {
    }

    /**
     * 增益是否等价于「原样通过」（1.0 附近，容忍浮点误差）。
     *
     * <p>为 {@code true} 时调用方应当直接复用原数组，不起新数组、不做逐样本运算。</p>
     */
    public static boolean isIdentity(float gain) {
        return gain >= 0.9999f && gain <= 1.0001f;
    }

    /**
     * 对交错 PCM16 乘增益，返回新数组（不修改入参）。
     *
     * <ul>
     *   <li>{@code gain == 1.0}：原样拷贝（调用方一般会先判 {@link #isIdentity(float)} 直接复用）；</li>
     *   <li>{@code gain < 1.0}：线性相乘 —— 纯衰减，{@code |in * gain| <= 32768} 恒成立，
     *       数学上不可能溢出，因此<b>不需要任何限幅</b>；</li>
     *   <li>{@code gain > 1.0}：tanh 软限幅（见类注释）。</li>
     * </ul>
     *
     * @param interleaved 交错样本（L,R,L,R…）；{@code null} 或空数组返回等长空数组
     * @param gain        目标增益，实现里再夹一次 {@code [0, OpenAlTtsPlayer.MAX_GAIN]}
     * @return 新的 PCM16 数组，长度与入参一致
     */
    public static short[] apply(short[] interleaved, float gain) {
        final int length = interleaved == null ? 0 : interleaved.length;
        final short[] out = new short[length];
        if (length == 0) {
            return out;
        }
        final float g = clamp(gain);
        if (g <= 0.0f) {
            // 0% = 全静音；out 初始化时已经是全 0
            return out;
        }
        if (g == 1.0f) {
            System.arraycopy(interleaved, 0, out, 0, length);
            return out;
        }
        if (g < 1.0f) {
            for (int i = 0; i < length; i++) {
                out[i] = (short) Math.round(interleaved[i] * g);
            }
            return out;
        }
        final short[] table = softClipTable(g);
        for (int i = 0; i < length; i++) {
            final int v = interleaved[i];
            out[i] = v < 0 ? (short) -table[-v] : table[v];
        }
        return out;
    }

    /** 增益夹紧到 {@code [0, MAX_GAIN]}；{@code NaN} 视为 1.0。 */
    private static float clamp(float gain) {
        if (Float.isNaN(gain)) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(OpenAlTtsPlayer.MAX_GAIN, gain));
    }

    /**
     * 建一张「幅度 → 软限幅后幅度」的查找表（只覆盖非负侧，负侧取负）。
     *
     * <p>{@code table[i] = round(32767 * tanh(i * gain / 32767))}，{@code i = 0..32768}。
     * 单调递增、上界 {@code table[32768] < 32767}，所以 {@code (short)} 强转不会溢出。</p>
     */
    private static short[] softClipTable(float gain) {
        final short[] table = new short[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            final double shaped = PCM16_MAX * Math.tanh(i * (double) gain / PCM16_MAX);
            int v = (int) Math.round(shaped);
            if (v < 0) {
                v = 0;
            } else if (v > (int) PCM16_MAX) {
                v = (int) PCM16_MAX;
            }
            table[i] = (short) v;
        }
        return table;
    }
}
