package com.feiyu.readerbridge.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读面板的公共绘制 / 命中工具。
 *
 * <p>配色与圆角全部走 {@link UiTheme}（面板这套视觉语言来自 MuoniumPlayer 的层次），
 * 这里只做「文字裁剪 / 折行 / 胶囊按钮 / 圆形书封 / 滚动条」这些组合动作，
 * 不引入任何新的配色体系，也不依赖原版 {@code Button}。
 *
 * <p>所有方法都对 null / 负数宽度 / 越界参数做了兜底：面板是每一帧重绘的，
 * 任何一处 NPE 都会直接把客户端带下去。
 */
final class PanelUi {

    private PanelUi() {
    }

    /** 正文行高（中文字形高 8，行距 10 读起来不挤）。 */
    static final int LINE_H = 10;
    /** 段落之间的空隙。 */
    static final int PARA_GAP = 4;
    /** 列表行高。 */
    static final int ROW_H = 18;

    // ---------------------------------------------------------------- 命中区

    /**
     * 轻量命中区表：每帧重建，点击时按注册顺序匹配第一条。
     *
     * <p>原骨架用 {@code List<int[]>} + 视图判断下标，扩展出五个视图后下标语义会分叉；
     * 这里直接登记 {@link Runnable}，视图不需要在点击时再判断自己是不是当前视图。
     */
    static final class Hits {

        private static final class Entry {
            final int x;
            final int y;
            final int w;
            final int h;
            final Runnable action;

            Entry(int x, int y, int w, int h, Runnable action) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
                this.action = action;
            }
        }

        private final List<Entry> entries = new ArrayList<>();

        void clear() {
            entries.clear();
        }

        void add(int x, int y, int w, int h, Runnable action) {
            if (action == null || w <= 0 || h <= 0) {
                return;
            }
            entries.add(new Entry(x, y, w, h, action));
        }

        boolean click(double mouseX, double mouseY) {
            int n = entries.size();
            for (int i = 0; i < n; i++) {
                if (i >= entries.size()) {
                    break;
                }
                Entry e = entries.get(i);
                if (in(mouseX, mouseY, e.x, e.y, e.w, e.h)) {
                    try {
                        e.action.run();
                    } catch (Throwable ignored) {
                        // 单个控件的动作异常不该让整块面板失效
                    }
                    return true;
                }
            }
            return false;
        }

        boolean contains(double mouseX, double mouseY) {
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (in(mouseX, mouseY, e.x, e.y, e.w, e.h)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- 基础判断

    static boolean in(double mouseX, double mouseY, int x, int y, int w, int h) {
        return w > 0 && h > 0
                && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + h;
    }

    static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return value < min ? min : Math.min(value, max);
    }

    // ---------------------------------------------------------------- 文本

    /** 超宽时截断并补省略号（按码点走，不会切坏代理对）。 */
    static String ellipsis(Font font, String text, int maxPx) {
        if (text == null || text.isEmpty() || maxPx <= 0) {
            return "";
        }
        if (font.width(text) <= maxPx) {
            return text;
        }
        String dots = "…";
        int budget = Math.max(0, maxPx - font.width(dots));
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int cc = Character.charCount(cp);
            String glyph = text.substring(i, Math.min(text.length(), i + cc));
            int gw = font.width(glyph);
            if (width + gw > budget) {
                break;
            }
            sb.append(glyph);
            width += gw;
            i += cc;
        }
        return sb + dots;
    }

    /** 左对齐单行绘制（自动省略）。 */
    static void text(GuiGraphics g, Font font, String s, int x, int y, int color, int maxPx) {
        if (maxPx <= 0) {
            return;
        }
        g.drawString(font, ellipsis(font, s, maxPx), x, y, color, false);
    }

    /** 右对齐单行绘制（自动省略）。 */
    static void textRight(GuiGraphics g, Font font, String s, int rightX, int y, int color, int maxPx) {
        if (maxPx <= 0) {
            return;
        }
        String t = ellipsis(font, s, maxPx);
        g.drawString(font, t, rightX - font.width(t), y, color, false);
    }

    /** 折行后的屏幕行：{@code text} 是要画的字，{@code srcStart/srcEnd} 是它在源文本里的区间。 */
    static final class TextLine {
        final String text;
        final int srcStart;
        final int srcEnd;

        TextLine(String text, int srcStart, int srcEnd) {
            this.text = text;
            this.srcStart = srcStart;
            this.srcEnd = srcEnd;
        }
    }

    /** 按像素宽度折行，保留每一行在源文本里的字符区间（正文高亮定位靠它）。 */
    static List<TextLine> wrapLines(Font font, String src, int maxPx) {
        List<TextLine> out = new ArrayList<>();
        if (src == null || src.isEmpty() || maxPx <= 0) {
            return out;
        }
        int n = src.length();
        int i = 0;
        while (i < n) {
            int start = i;
            int width = 0;
            while (i < n) {
                int cp = src.codePointAt(i);
                int cc = Character.charCount(cp);
                String glyph = src.substring(i, Math.min(n, i + cc));
                int gw = font.width(glyph);
                if (i > start && width + gw > maxPx) {
                    break;
                }
                width += gw;
                i += cc;
            }
            if (i == start) {
                // 单字比整行还宽：硬吃一个码点，避免死循环
                i = start + Character.charCount(src.codePointAt(start));
            }
            out.add(new TextLine(src.substring(start, i), start, i));
        }
        return out;
    }

    /** 纯文本折行（只关心文字，不关心源区间）。 */
    static List<String> wrap(Font font, String src, int maxPx) {
        List<PanelUi.TextLine> lines = wrapLines(font, src, maxPx);
        List<String> out = new ArrayList<>(lines.size());
        for (PanelUi.TextLine line : lines) {
            out.add(line.text);
        }
        return out;
    }

    // ---------------------------------------------------------------- 控件

    /**
     * 胶囊按钮。
     *
     * @param active 强调态（用 {@link UiTheme#ACCENT} 底）
     * @return 是否处于 hover
     */
    static boolean chip(GuiGraphics g, Font font, String label, int x, int y, int w, int h,
                        boolean active, int mouseX, int mouseY) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        boolean hover = in(mouseX, mouseY, x, y, w, h);
        int radius = Math.min(UiTheme.RADIUS_CARD, h / 2);
        int bg;
        if (active) {
            bg = UiTheme.ACCENT;
        } else {
            bg = hover ? UiTheme.HOVER : UiTheme.CARD_SOFT;
        }
        UiTheme.fillRounded(g, x, y, w, h, radius, bg);
        if (!active) {
            UiTheme.strokeRounded(g, x, y, w, h, radius, hover ? UiTheme.TEXT : UiTheme.STROKE);
        } else if (hover) {
            UiTheme.strokeRounded(g, x, y, w, h, radius, UiTheme.TEXT);
        }
        int color = active ? UiTheme.TEXT : (hover ? UiTheme.TEXT : UiTheme.TEXT_DIM);
        String text = ellipsis(font, label, w - 6);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, color, false);
        return hover;
    }

    /** 圆形书封（没有封面图时用首字 + 强调色明暗变化顶上）。 */
    static void cover(GuiGraphics g, Font font, String label, int x, int y, int size, int color) {
        if (size <= 0) {
            return;
        }
        UiTheme.fillRounded(g, x, y, size, size, Math.max(1, size / 2), color);
        String ch = initial(label);
        if (!ch.isEmpty() && size >= 10 && font.width(ch) <= size - 4) {
            g.drawString(font, ch, x + (size - font.width(ch)) / 2, y + (size - 8) / 2, UiTheme.TEXT, false);
        }
    }

    static String initial(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int cp = s.codePointAt(0);
        String ch = new String(Character.toChars(cp));
        return ch.trim().isEmpty() ? "" : ch;
    }

    /** 同一本书永远同一个色，靠书名哈希在强调色上做明暗变化。 */
    static int coverColor(String key) {
        int h = 0;
        if (key != null) {
            for (int i = 0; i < key.length(); i++) {
                h = h * 31 + key.charAt(i);
            }
        }
        float[] factors = {1.0f, 0.72f, 0.5f, 0.86f};
        return shade(UiTheme.ACCENT, factors[Math.floorMod(h, factors.length)]);
    }

    /** 按系数缩放 RGB，保留 alpha。 */
    static int shade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = clampByte((int) (((argb >> 16) & 0xFF) * factor));
        int g = clampByte((int) (((argb >> 8) & 0xFF) * factor));
        int b = clampByte((int) ((argb & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** 语速档位点。 */
    static void dot(GuiGraphics g, int x, int y, int size, boolean on) {
        UiTheme.fillRounded(g, x, y, size, size, Math.max(1, size / 2),
                on ? UiTheme.ACCENT : UiTheme.STROKE);
    }

    /**
     * 可拖动的进度条：底槽 + 已完成 + 拖动时的圆形把手。
     *
     * @param ratio  0..1（拖动中传预览值）
     * @param hover  鼠标是否在整条命中区上
     * @param active 是否正在拖动（把手放大并高亮）
     */
    static void seekBar(GuiGraphics g, int x, int y, int w, int h, double ratio,
                        boolean hover, boolean active) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double r = ratio < 0.0 ? 0.0 : Math.min(1.0, ratio);
        UiTheme.progressBar(g, x, y, w, h, r, UiTheme.STROKE,
                active || hover ? UiTheme.TEXT : UiTheme.ACCENT);
        int knob = active ? 7 : (hover ? 6 : 5);
        int knobX = x + (int) Math.round((w - knob) * r);
        UiTheme.fillCapsule(g, knobX, y + h / 2 - knob / 2, knob, knob, UiTheme.ACCENT);
    }

    /**
     * 设置行：左侧标签、右侧值、再右侧 [-][+]。
     *
     * @return 下一行的 y
     */
    static int stepperRow(GuiGraphics g, Font font, int x, int y, int w, int rowH,
                          String label, String value,
                          Runnable onMinus, Runnable onPlus,
                          Hits hits, int mouseX, int mouseY) {
        if (rowH <= 0) {
            return y;
        }
        g.drawString(font, label, x, y + 4, UiTheme.TEXT_DIM, false);
        int btnW = 14;
        int valueW = Math.min(64, Math.max(30, font.width(value) + 10));
        int plusX = x + w - btnW;
        int minusX = plusX - btnW - 2;
        int valueX = Math.max(x + font.width(label) + 8, minusX - valueW - 4);
        text(g, font, value, valueX, y + 4, UiTheme.TEXT, Math.max(12, valueW));
        chip(g, font, "-", minusX, y + 1, btnW, 14, false, mouseX, mouseY);
        chip(g, font, "+", plusX, y + 1, btnW, 14, false, mouseX, mouseY);
        if (hits != null) {
            hits.add(minusX, y + 1, btnW, 14, onMinus);
            hits.add(plusX, y + 1, btnW, 14, onPlus);
        }
        return y + rowH;
    }

    /** 纵向滚动条（内容比视口高时才画）。 */
    static void scrollBar(GuiGraphics g, int x, int y, int h, int viewH, int contentH, int scroll) {
        if (h <= 4 || viewH <= 0 || contentH <= viewH) {
            return;
        }
        int trackW = 3;
        UiTheme.fillCapsule(g, x, y, trackW, h, UiTheme.STROKE);
        int barH = Math.max(10, (int) ((long) h * viewH / Math.max(1, contentH)));
        int maxScroll = Math.max(1, contentH - viewH);
        int barY = y + (int) ((long) (h - barH) * clamp(scroll, 0, maxScroll) / maxScroll);
        UiTheme.fillCapsule(g, x, barY, trackW, barH, UiTheme.TEXT_FAINT);
    }

    /** 空态文案（居中一行 + 一行暗提示）。 */
    static void emptyState(GuiGraphics g, Font font, int x, int y, int w, int h,
                           String headline, String hint) {
        int midY = y + h / 2 - 6;
        if (w > 8) {
            g.drawString(font, ellipsis(font, headline, w - 8),
                    x + (w - font.width(ellipsis(font, headline, w - 8))) / 2, midY, UiTheme.TEXT_DIM, false);
        }
        if (hint != null && !hint.isEmpty() && w > 8) {
            String hintText = ellipsis(font, hint, w - 8);
            g.drawString(font, hintText, x + (w - font.width(hintText)) / 2, midY + 12,
                    UiTheme.TEXT_FAINT, false);
        }
    }
}
