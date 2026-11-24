package com.feiyu.readerbridge.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * 面板的两块「增量」界面：阅读外观卡片（嵌在设置视图里）与书签视图。
 *
 * <p>单独成类的理由：{@code ReaderViews} 已经近千行，塞进去会继续膨胀；这两块只依赖
 * 宿主暴露的包级字段，不需要动 {@code View} 基类；也让「阅读外观」的排版细节集中在一处。
 */
final class PanelExtras {

    private static final int PAD = 8;
    private static final int ROW_H = 22;

    private PanelExtras() {
    }

    /** 阅读外观卡片的固定高度：标题 24 + 6 行 × 22 + 底部留白 8。 */
    static int readingCardHeight() {
        return 24 + 6 * ROW_H + 8;
    }

    /**
     * 画「阅读外观」卡片：字号 / 行距 / 内容宽度 / 主题 / 自动跟随 / 恢复默认。
     *
     * @return 卡片占用的下一行 y（调用方接着往下排）
     */
    static int renderReadingCard(GuiGraphics g, Font font, int x, int y, int w, int h,
                                 LocalReaderScreen host, int mouseX, int mouseY) {
        if (w < 80 || h < 40) {
            return y;
        }
        UiTheme.fillRounded(g, x, y, w, h, UiTheme.RADIUS_CARD, UiTheme.CARD);
        UiTheme.strokeRounded(g, x, y, w, h, UiTheme.RADIUS_CARD, UiTheme.STROKE);
        g.drawString(font, "阅读外观", x + PAD, y + 7, UiTheme.TEXT, false);
        PanelUi.textRight(g, font, "即时生效", x + w - PAD, y + 7, UiTheme.TEXT_FAINT, 56);

        int rowX = x + PAD;
        int rowW = Math.max(40, w - PAD * 2);
        int rowY = y + 24;

        rowY = PanelUi.stepperRow(g, font, rowX, rowY, rowW, ROW_H, "字号",
                String.valueOf(host.prefs.fontSize()),
                () -> {
                    host.prefs.adjustFontSize(-1);
                    host.toast("字号 " + host.prefs.fontSize());
                },
                () -> {
                    host.prefs.adjustFontSize(1);
                    host.toast("字号 " + host.prefs.fontSize());
                }, host.hits, mouseX, mouseY);

        rowY = PanelUi.stepperRow(g, font, rowX, rowY, rowW, ROW_H, "行距",
                String.format("%.1f", host.prefs.lineSpacing()),
                () -> {
                    host.prefs.adjustLineSpacing(-1);
                    host.toast("行距 " + String.format("%.1f", host.prefs.lineSpacing()));
                },
                () -> {
                    host.prefs.adjustLineSpacing(1);
                    host.toast("行距 " + String.format("%.1f", host.prefs.lineSpacing()));
                }, host.hits, mouseX, mouseY);

        rowY = PanelUi.stepperRow(g, font, rowX, rowY, rowW, ROW_H, "宽度",
                host.prefs.contentWidthPct() + "%",
                () -> {
                    host.prefs.adjustContentWidth(-10);
                    host.toast("内容宽度 " + host.prefs.contentWidthPct() + "%");
                },
                () -> {
                    host.prefs.adjustContentWidth(10);
                    host.toast("内容宽度 " + host.prefs.contentWidthPct() + "%");
                }, host.hits, mouseX, mouseY);

        // -------------------------------------------------- 主题三选一
        g.drawString(font, "主题", rowX, rowY + 4, UiTheme.TEXT_DIM, false);
        int chipW = 42;
        int cx = rowX + font.width("主题") + 10;
        for (int i = 0; i < PanelSkin.COUNT; i++) {
            boolean on = host.prefs.theme() == i;
            PanelUi.chip(g, font, PanelSkin.labelOf(i), cx, rowY, chipW, 16, on, mouseX, mouseY);
            final int idx = i;
            host.hits.add(cx, rowY, chipW, 16, () -> {
                host.prefs.setTheme(idx);
                host.toast("主题：" + PanelSkin.labelOf(idx));
            });
            cx += chipW + 4;
        }
        rowY += ROW_H;

        // -------------------------------------------------- 自动跟随朗读滚动
        boolean follow = host.prefs.autoFollow();
        String followLabel = follow ? "跟随朗读滚动：开" : "跟随朗读滚动：关";
        int followW = Math.min(rowW, font.width(followLabel) + 16);
        PanelUi.chip(g, font, followLabel, rowX, rowY, followW, 16, follow, mouseX, mouseY);
        host.hits.add(rowX, rowY, followW, 16, () -> {
            host.prefs.toggleAutoFollow();
            host.readerFollow = host.prefs.autoFollow();
            host.toast(host.prefs.autoFollow() ? "正文将跟随朗读滚动" : "正文不再自动滚动");
        });
        rowY += ROW_H;

        // -------------------------------------------------- 恢复默认
        PanelUi.chip(g, font, "恢复默认", rowX, rowY, 66, 16, false, mouseX, mouseY);
        host.hits.add(rowX, rowY, 66, 16, () -> {
            host.prefs.resetDefaults();
            host.readerFollow = true;
            host.toast("已恢复默认阅读外观");
        });
        return y + h + 8;
    }

    // ================================================================== 书签视图

    /** 书签列表：时间倒序，点行跳转，右侧 × 删除。 */
    static final class Bookmarks extends ReaderViews.View {

        private static final int ROW = 28;

        Bookmarks(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            Font font = host.font();
            if (w < 80 || h < 40) {
                return;
            }
            List<BookmarkStore.Bookmark> list = host.bookmarks.all();

            int headY = y + PAD;
            String head = list.isEmpty() ? "还没有书签" : ("共 " + list.size() + " 条书签");
            int clearW = 40;
            int addW = 62;
            int clearX = x + w - PAD - clearW;
            int addX = clearX - addW - 4;
            PanelUi.text(g, font, head, x + PAD, headY + 1, UiTheme.TEXT_FAINT,
                    Math.max(20, addX - x - PAD - 6));
            PanelUi.chip(g, font, "★ 加当前", addX, headY, addW, 16, false, mouseX, mouseY);
            host.hits.add(addX, headY, addW, 16, host::addBookmark);
            PanelUi.chip(g, font, "清空", clearX, headY, clearW, 16, false, mouseX, mouseY);
            host.hits.add(clearX, headY, clearW, 16, () -> {
                host.bookmarks.clear();
                host.bookmarkScroll = 0;
                host.toast("书签已清空");
            });

            int listY = headY + 20;
            int listH = (y + h - PAD) - listY;
            if (listH < 20) {
                return;
            }
            if (list.isEmpty()) {
                PanelUi.emptyState(g, font, x + PAD, listY, w - PAD * 2, listH,
                        "还没有书签", "正文里按 B，或点底部播放条的 ★ 加一条");
                return;
            }

            int visible = Math.max(1, listH / ROW);
            host.bookmarkScroll = PanelUi.clamp(host.bookmarkScroll, 0, Math.max(0, list.size() - visible));
            for (int i = host.bookmarkScroll; i < list.size(); i++) {
                int rowY = listY + (i - host.bookmarkScroll) * ROW;
                if (rowY + ROW > listY + listH) {
                    break;
                }
                BookmarkStore.Bookmark b = list.get(i);
                if (b == null) {
                    continue;
                }
                boolean hover = PanelUi.in(mouseX, mouseY, x + PAD, rowY, w - PAD * 2, ROW - 2);
                if (hover) {
                    UiTheme.fillRounded(g, x + PAD, rowY, w - PAD * 2, ROW - 2,
                            UiTheme.RADIUS_CARD, UiTheme.HOVER);
                }
                int delW = 16;
                int delX = x + w - PAD - delW;
                int jumpW = 26;
                int jumpX = delX - jumpW - 4;
                UiTheme.fillRounded(g, x + PAD + 4, rowY + 5, 2, ROW - 12, 1, UiTheme.ACCENT);

                String headline = safe(b.headline());
                PanelUi.text(g, font, headline, x + PAD + 10, rowY + 4, UiTheme.TEXT,
                        Math.max(20, jumpX - x - PAD - 14));
                String preview = safe(b.preview);
                PanelUi.text(g, font, preview.isEmpty() ? "（无预览）" : preview,
                        x + PAD + 10, rowY + 15, UiTheme.TEXT_FAINT,
                        Math.max(20, jumpX - x - PAD - 14));

                PanelUi.chip(g, font, "跳", jumpX, rowY + 6, jumpW, 15, false, mouseX, mouseY);
                PanelUi.chip(g, font, "×", delX, rowY + 6, delW, 15, false, mouseX, mouseY);

                final BookmarkStore.Bookmark target = b;
                // 命中表按注册顺序匹配，先注册的两个小按钮才能抢在整行之前命中
                host.hits.add(jumpX, rowY + 6, jumpW, 15, () -> host.jumpToBookmark(target));
                host.hits.add(delX, rowY + 6, delW, 15, () -> {
                    host.bookmarks.remove(target);
                    host.toast("已删书签：" + safe(target.headline()));
                });
                host.hits.add(x + PAD, rowY, w - PAD * 2, ROW - 2, () -> host.jumpToBookmark(target));
            }
            PanelUi.scrollBar(g, x + w - 4, listY, listH, listH,
                    list.size() * ROW, host.bookmarkScroll * ROW);
        }

        private static String safe(String s) {
            return s == null ? "" : s;
        }
    }
}
