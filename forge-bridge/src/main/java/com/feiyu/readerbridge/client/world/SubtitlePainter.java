package com.feiyu.readerbridge.client.world;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 世界内 3D 字幕的「落笔层」：只负责把已经排好版的行/字送进 {@link Font#drawInBatch}，
 * 不参与锚点计算、不参与时间轴、不碰任何 GL 状态，也<b>绝不调用</b> {@code endBatch()}
 * （整帧只有 {@code WorldTextRenderer} 里那一次 flush）。
 *
 * <p>两种落笔方式：</p>
 *
 * <ol>
 *   <li>{@link #drawSolidLines} —— 整行一次 {@code drawInBatch}。用于 {@code crossfade} /
 *       {@code slideUp} / {@code none}：这些档整行共用同一个 alpha 与位移，逐字拆开没有收益。</li>
 *   <li>{@link #drawCharLines} —— 每个 code point 一次 {@code drawInBatch}，由
 *       {@link CharShader} 逐字给出 alpha 与竖直偏移。用于打字机 / 跳动 / 波浪 / 文闪。</li>
 * </ol>
 *
 * <p>关键约定：</p>
 *
 * <ul>
 *   <li><b>矩阵只有一个</b>：整帧复用调用方传进来的 {@link Matrix4f}
 *       （{@code poseStack.last().pose()} 的同一个实例），逐字绘制时绝不 push/pop pose。
 *       每个字的差异只体现在 x / y 两个 float 上。</li>
 *   <li><b>行内居中按整行宽度算</b>：起点 x 用 {@code -font.width(整行) / 2}，
 *       逐字绘制只负责沿 x 累加 {@code font.width(该字)}。用逐字宽度累加来算居中会
 *       因为末字间距的处理方式不同而偏移 1~2px。</li>
 *   <li><b>按 code point 迭代</b>（{@link String#codePointAt(int)} +
 *       {@link Character#charCount(int)}）：中文都在 BMP 内，但上游偶尔混入 emoji 时
 *       按 char 迭代会拆坏代理对，画出两个缺字方块。</li>
 *   <li><b>不绘制也推进 x</b>：打字机未到时间的字直接跳过绘制（alpha 量化后为 0 时不提交顶点），
 *       但仍然累加它的宽度 —— 于是每个字都出现在最终排版位置上，观感才是「打字」，而不是
 *       「句尾一个个挤出来」。</li>
 * </ul>
 *
 * <p>线程约束：只在客户端渲染线程调用；实例本身可复用（{@link #begin} 每帧刷新一次字段）。</p>
 */
public final class SubtitlePainter {

    /**
     * 全亮光照贴图坐标（{@code LightTexture.FULL_BRIGHT}），避免文字在暗处变黑。
     * 与 {@code WorldTextRenderer.FULL_BRIGHT} 同值，两处都是同一个原版常量。
     */
    public static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 打字机末尾的光标字形。
     *
     * <p>为什么用 ASCII {@code "|"} 而不是方块字符 {@code "▍"}（U+258D）：
     * 纯原版资源包的字形图集（{@code minecraft:font/ascii.png} + {@code accented.png}）
     * 只覆盖 ASCII 与部分拉丁扩展，方块字符要靠第三方字体包（汉化包 / Modern UI 之类）才有字形，
     * 缺失时会画成「豆腐块」。{@code "|"} 宽 2 设计像素、竖条形状，正好符合「2~3 单位宽的矩形光标」。</p>
     */
    public static final String CURSOR_GLYPH = "|";

    /** 光标与末字之间留的横向间隙（设计像素），避免紧贴最后一行最后一个字。 */
    private static final float CURSOR_GAP = 1.0F;

    /** 原版格式符号 {@code §}：逐字绘制时必须按「格式码」吞掉，否则会画出字形。 */
    private static final int FORMAT_CHAR = 0x00A7;

    /**
     * 逐字着色器：给出第 {@code index} 个字（按 code point 计，跨行连续编号）本帧的
     * alpha 与竖直偏移。
     *
     * <p>只有一个抽象方法，因此简单的档可以用 lambda 只实现 {@link #alpha}；
     * 需要逐字位移的档（跳动 / 波浪 / 文闪）用匿名类覆盖 {@link #dy}。</p>
     */
    public interface CharShader {

        /**
         * 该字符本帧的 alpha（0..1）。量化到 8 位后为 0 时该字不提交任何顶点。
         *
         * @param codePoint 该字符的 Unicode code point
         * @param index     全局字符序号（从 0 开始，跨行连续）
         * @param total     全部可见行加起来的字符总数
         */
        float alpha(int codePoint, int index, int total);

        /**
         * 该字符本帧相对行基线的竖直偏移（设计像素）。
         *
         * <p>注意 {@code Font.drawInBatch} 的 y 轴<b>向下为正</b>：要「向上弹」必须返回负数。</p>
         */
        default float dy(int codePoint, int index, int total) {
            return 0.0F;
        }
    }

    private Font font;
    private Matrix4f matrix;
    private MultiBufferSource buffers;
    private float lineHeight = 11.0F;

    /**
     * 每帧调用一次，刷新落笔上下文。
     *
     * @param font       客户端字体
     * @param matrix     整帧唯一的 pose 矩阵（{@code poseStack.last().pose()} 的实例本身）
     * @param buffers    与其它绘制共用的同一个 BufferSource
     * @param lineHeight 行高（设计像素），与调用方的折行常量同源
     * @return {@code this}，便于链式调用
     */
    public SubtitlePainter begin(Font font, Matrix4f matrix, MultiBufferSource buffers, float lineHeight) {
        this.font = font;
        this.matrix = matrix;
        this.buffers = buffers;
        this.lineHeight = lineHeight;
        return this;
    }

    /**
     * 整行绘制：每行一次 {@code drawInBatch}，所有行共用同一个 alpha 与竖直偏移。
     *
     * @param lines     已经折好行的文本（至少一行）
     * @param baseColor 基色（{@code 0xAARRGGBB}，A 通道会被 {@code alpha} 覆盖）
     * @param alpha     整体不透明度 0..1；量化后为 0 时整段跳过，不提交不可见顶点
     * @param dy        竖直偏移（设计像素，向下为正）：上滑档用它做整体滑入
     */
    public void drawSolidLines(List<String> lines, int baseColor, float alpha, float dy) {
        int color = withAlpha(baseColor, alpha);
        if ((color >>> 24) == 0) {
            return;
        }
        float y = firstLineY(lines) + dy;
        for (String line : lines) {
            // 整行交给原版：字形位置、字距、阴影全由 drawInBatch 内部处理。
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, y, color, true, matrix, buffers,
                    Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, 0);
            y += lineHeight;
        }
    }

    /**
     * 逐字绘制：每个 code point 一次 {@code drawInBatch}。
     *
     * @param lines     已经折好行的文本（至少一行）
     * @param baseColor 基色（{@code 0xAARRGGBB}）
     * @param shader    逐字 alpha / 位移
     * @return 全部可见行的字符总数（= 最后一个字符的 {@code index + 1}），
     *         打字机拿它判断「整句是否已经打完」
     */
    public int drawCharLines(List<String> lines, int baseColor, CharShader shader) {
        int total = charCount(lines);
        int index = 0;
        float y = firstLineY(lines);
        for (String line : lines) {
            // 居中基准：整行宽度（与 drawSolidLines 完全一致），不是逐字累加。
            float x = -font.width(line) / 2.0F;
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                int next = i + Character.charCount(codePoint);
                if (codePoint == FORMAT_CHAR) {
                    // 原版格式码：整行绘制时会被 Font 内部吞掉且不占宽度，
                    // 逐字绘制必须做同样处理，否则居中和字形都会和整行不一致。
                    i = skipFormatTarget(line, next);
                    continue;
                }
                i = next;

                String glyph = new String(Character.toChars(codePoint));
                int color = withAlpha(baseColor, shader.alpha(codePoint, index, total));
                if ((color >>> 24) != 0) {
                    float offsetY = y + shader.dy(codePoint, index, total);
                    font.drawInBatch(glyph, x, offsetY, color, true, matrix, buffers,
                            Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, 0);
                }
                // 无论是否提交顶点都要推进：未显现的字符必须占住最终排版位置。
                x += font.width(glyph);
                index++;
            }
            y += lineHeight;
        }
        return total;
    }

    /**
     * 在最后一行右端画打字机光标（调用方负责判断「该不该亮」）。
     *
     * @param lines     已经折好行的文本
     * @param baseColor 基色
     * @param alpha     光标不透明度（通常 1，暂停时仍是灰的，因为基色已经压暗）
     */
    public void drawCursor(List<String> lines, int baseColor, float alpha) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int color = withAlpha(baseColor, alpha);
        if ((color >>> 24) == 0) {
            return;
        }
        String last = lines.get(lines.size() - 1);
        float y = firstLineY(lines) + (lines.size() - 1) * lineHeight;
        // 行起点是 -width/2，右端就是 +width/2。
        float x = font.width(last) / 2.0F + CURSOR_GAP;
        font.drawInBatch(CURSOR_GLYPH, x, y, color, true, matrix, buffers,
                Font.DisplayMode.SEE_THROUGH, FULL_BRIGHT, 0);
    }

    /** 全部可见行的字符总数（按 code point 计，跳过格式码），与 {@link #drawCharLines} 的编号一致。 */
    public static int charCount(List<String> lines) {
        int total = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                int next = i + Character.charCount(codePoint);
                if (codePoint == FORMAT_CHAR) {
                    i = skipFormatTarget(line, next);
                    continue;
                }
                i = next;
                total++;
            }
        }
        return total;
    }

    /** 多行文字块的顶部 y：按行数自身垂直居中（两段文字各自居中在锚点上）。 */
    private float firstLineY(List<String> lines) {
        return -(lines.size() * lineHeight) / 2.0F;
    }

    /**
     * 已经读到 {@code §} 时，返回「连同其后格式字符」的下一读取位置。
     *
     * <p>只处理「吞掉」这一件事：真正的颜色/样式控制没有实现 —— 小说正文不会含 {@code §}，
     * 这里纯粹是「万一上游混进来，排版不能比整行绘制更差」的边界兜底。</p>
     */
    private static int skipFormatTarget(String line, int afterSection) {
        return afterSection < line.length()
                ? afterSection + Character.charCount(line.codePointAt(afterSection))
                : afterSection;
    }

    /**
     * 只替换 ARGB 的最高字节（alpha 通道），RGB 原样保留。
     *
     * <p>{@code Font.drawInBatch} 的 {@code color} 是 {@code 0xAARRGGBB}：所以暂停态的
     * {@code 0xB4B4B4} 不会被洗白，透明态也不会染色。与
     * {@code WorldTextRenderer.withAlpha} 逐字相同（两个类不在同一个包，
     * 那一份是包内可见的，无法跨包复用；改动其中一处时另一处必须同步）。</p>
     */
    private static int withAlpha(int argb, float alpha) {
        int rgb = argb & 0x00FFFFFF;
        // 用 !(alpha > 0) 而不是 alpha <= 0：NaN 也走这条，直接判成完全透明。
        if (!(alpha > 0.0F)) {
            return rgb;
        }
        if (alpha >= 1.0F) {
            return 0xFF000000 | rgb;
        }
        int a = Math.round(alpha * 255.0F);
        return (a << 24) | rgb;
    }
}
