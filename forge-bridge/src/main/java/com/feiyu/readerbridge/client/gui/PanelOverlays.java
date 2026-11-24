package com.feiyu.readerbridge.client.gui;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import com.feiyu.readerbridge.client.fq.LibraryStore;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 面板上的三种弹层：书详情 / 音色选择 / 快捷键帮助。
 *
 * <p>信息架构照 MuoniumPlayer 的「列表 → 详情 → 子面板」：点书不再直接开读，
 * 而是先看详情；音色与帮助也不再挤在设置卡片里，各自独立一层。弹层由
 * {@link LocalReaderScreen} 统一管理（同一时刻最多一个），布局每帧重算，
 * 所以窗口 resize / GUI Scale 变化不会错位。
 *
 * <p>所有绘制仍走 {@link PanelUi} + {@link UiTheme}（自动跟随 {@link PanelSkin}），
 * 命中区仍登记在宿主的 {@code hits} 表里，没有引入新的控件体系。
 */
final class PanelOverlays {

    private PanelOverlays() {
    }

    /** 弹层基类。矩形由宿主算好传入。 */
    abstract static class Overlay {

        final LocalReaderScreen host;

        Overlay(LocalReaderScreen host) {
            this.host = host;
        }

        abstract String title();

        /** 画内容（底框与标题由 {@link #frame} 负责）。 */
        abstract void render(GuiGraphics g, Font font, int x, int y, int w, int h,
                             int mouseX, int mouseY);

        /** @return 是否消费 */
        boolean mouseClicked(double mouseX, double mouseY) {
            return false;
        }

        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            return false;
        }

        /** 打开时的准备工作。 */
        void onShow() {
        }

        /** 底框 + 标题 + 右上关闭；返回内容区顶部 y。 */
        final int frame(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
            Font font = host.font();
            UiTheme.fillRounded(g, x - 2, y - 2, w + 4, h + 4, UiTheme.RADIUS_PANEL, 0xE6101014);
            UiTheme.fillRounded(g, x, y, w, h, UiTheme.RADIUS_PANEL, UiTheme.PANEL);
            UiTheme.strokeRounded(g, x, y, w, h, UiTheme.RADIUS_PANEL, UiTheme.STROKE);
            g.drawString(font, title(), x + 10, y + 7, UiTheme.TEXT, false);
            int closeW = 34;
            int closeX = x + w - closeW - 6;
            PanelUi.chip(g, font, "×", closeX, y + 4, closeW, 14, false, mouseX, mouseY);
            host.hits.add(closeX, y + 4, closeW, 14, host::closeOverlay);
            g.fill(x + 10, y + 20, x + w - 10, y + 21, UiTheme.STROKE);
            return y + 24;
        }
    }

    // ================================================================ 书详情

    static final class BookDetail extends Overlay {

        private final FqApiClient.BookInfo book;
        private int scroll;

        BookDetail(LocalReaderScreen host, FqApiClient.BookInfo book) {
            super(host);
            this.book = book;
        }

        @Override
        String title() {
            return "书籍详情";
        }

        @Override
        void render(GuiGraphics g, Font font, int x, int y, int w, int h,
                    int mouseX, int mouseY) {
            int top = frame(g, x, y, w, h, mouseX, mouseY);
            int innerW = w - 20;
            ReaderEngine e = host.engine;
            LibraryStore library = LibraryStore.get();
            LibraryStore.Entry entry = book == null ? null : library.find(book.bookId);

            // 章节数：优先看存档，其次看引擎当前书
            int totalChapters = entry == null ? 0 : entry.totalChapters;
            if (totalChapters <= 0 && book != null && book.bookId.equals(e.bookId())) {
                totalChapters = e.chapters().size();
            }

            int coverSize = 40;
            PanelUi.cover(g, font, PanelUi.initial(book == null ? "书" : book.name),
                    x + 10, top + 4, coverSize,
                    PanelUi.coverColor(book == null ? "" : book.bookId));

            int tx = x + 10 + coverSize + 10;
            int tw = Math.max(40, innerW - coverSize - 10);
            PanelUi.text(g, font, book == null ? "" : book.name, tx, top + 4, UiTheme.TEXT, tw);
            PanelUi.text(g, font, book == null || book.author.isEmpty() ? "未知作者" : book.author,
                    tx, top + 18, UiTheme.TEXT_DIM, tw);
            String meta = totalChapters > 0 ? ("共 " + totalChapters + " 章") : "章节数未知";
            if (entry != null && entry.hasProgress()) {
                meta += " · 上次第 " + (entry.chapterIndex + 1) + " 章";
            }
            PanelUi.text(g, font, meta, tx, top + 31, UiTheme.TEXT_FAINT, tw);

            int bodyTop = top + coverSize + 12;
            int btnH = 18;
            int btnY = y + h - btnH - 10;
            int bodyH = Math.max(20, btnY - bodyTop - 6);

            // 简介：折行 + 滚动
            String intro = book == null || book.intro == null || book.intro.isEmpty()
                    ? "这本书没有简介。" : book.intro;
            List<String> lines = PanelUi.wrap(font, intro, Math.max(20, innerW - 4));
            int lineH = PanelUi.LINE_H + 2;
            int contentH = lines.size() * lineH;
            int maxScroll = Math.max(0, contentH - bodyH);
            scroll = PanelUi.clamp(scroll, 0, maxScroll);
            g.enableScissor(x + 10, bodyTop, x + 10 + innerW, bodyTop + bodyH);
            try {
                int yy = bodyTop - scroll;
                for (int i = 0; i < lines.size(); i++) {
                    int ly = yy + i * lineH;
                    if (ly + lineH < bodyTop || ly > bodyTop + bodyH) {
                        continue;
                    }
                    g.drawString(font, lines.get(i), x + 10, ly, UiTheme.TEXT_DIM, false);
                }
            } finally {
                g.disableScissor();
            }
            PanelUi.scrollBar(g, x + w - 6, bodyTop, bodyH, bodyH, contentH, scroll);

            // 底部三个动作
            int gap = 6;
            int wFromStart = Math.max(52, (innerW - gap * 2) / 3);
            int bx = x + 10;
            PanelUi.chip(g, font, "从头读", bx, btnY, wFromStart, btnH, false, mouseX, mouseY);
            host.hits.add(bx, btnY, wFromStart, btnH, () -> host.readFromStart(book));
            bx += wFromStart + gap;
            boolean canResume = entry != null && entry.hasProgress();
            PanelUi.chip(g, font, "继续上次", bx, btnY, wFromStart, btnH, canResume, mouseX, mouseY);
            host.hits.add(bx, btnY, wFromStart, btnH, () -> host.resumeFromDetail(book, entry));
            bx += wFromStart + gap;
            int wClose = Math.max(40, innerW - (bx - x - 10));
            PanelUi.chip(g, font, "关闭", bx, btnY, wClose, btnH, false, mouseX, mouseY);
            host.hits.add(bx, btnY, wClose, btnH, host::closeOverlay);
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            scroll -= (int) Math.round(delta * 2.0 * 10.0);
            if (scroll < 0) {
                scroll = 0;
            }
            return true;
        }
    }

    // ================================================================ 音色

    static final class VoicePicker extends Overlay {

        private int scroll;

        VoicePicker(LocalReaderScreen host) {
            super(host);
        }

        @Override
        String title() {
            return "选择音色";
        }

        /** {@code edge:} / {@code sapi:} 前缀视为显式分组；旧的无前缀 id 按是否含 Neural 猜。 */
        static boolean isLocal(String id) {
            if (id == null) {
                return false;
            }
            String s = id.toLowerCase(Locale.ROOT);
            if (s.startsWith("sapi:")) {
                return true;
            }
            if (s.startsWith("edge:")) {
                return false;
            }
            return !s.contains("neural");
        }

        /** 去掉分组前缀后的显示名。 */
        static String displayName(String id) {
            if (id == null) {
                return "";
            }
            int colon = id.indexOf(':');
            if (colon > 0 && colon <= 5) {
                return id.substring(colon + 1);
            }
            return id;
        }

        @Override
        void render(GuiGraphics g, Font font, int x, int y, int w, int h,
                    int mouseX, int mouseY) {
            int top = frame(g, x, y, w, h, mouseX, mouseY);
            ReaderEngine e = host.engine;
            List<String> voices = e.voices();
            String current = e.voiceShortName();

            List<String> cloud = new ArrayList<>();
            List<String> local = new ArrayList<>();
            for (String id : voices) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (isLocal(id)) {
                    local.add(id);
                } else {
                    cloud.add(id);
                }
            }

            int rowH = 16;
            int groupGap = 6;
            int headH = 14;
            int contentH = (headH + cloud.size() * rowH) + groupGap
                    + (headH + local.size() * rowH);
            int viewH = Math.max(20, (y + h - 8) - top);
            int maxScroll = Math.max(0, contentH - viewH);
            scroll = PanelUi.clamp(scroll, 0, maxScroll);

            int innerX = x + 8;
            int innerW = w - 16;
            g.enableScissor(innerX, top, x + w - 4, y + h - 4);
            try {
                int yy = top - scroll;
                yy = group(g, font, innerX, yy, innerW, rowH, headH, "云端（Edge 在线）", cloud, current, mouseX, mouseY);
                yy += groupGap;
                group(g, font, innerX, yy, innerW, rowH, headH, "本地（离线系统音色）", local, current, mouseX, mouseY);
            } finally {
                g.disableScissor();
            }
            PanelUi.scrollBar(g, x + w - 6, top, viewH, viewH, contentH, scroll);

            if (voices.isEmpty()) {
                PanelUi.emptyState(g, font, x + 8, top, w - 16, viewH,
                        "还没有可用音色", "引擎初始化后会列出");
            }
        }

        private int group(GuiGraphics g, Font font, int x, int y, int w, int rowH, int headH,
                          String label, List<String> ids, String current,
                          int mouseX, int mouseY) {
            g.drawString(font, label + "（" + ids.size() + "）", x, y + 2, UiTheme.TEXT_FAINT, false);
            int yy = y + headH;
            for (String id : ids) {
                boolean active = id.equals(current);
                boolean hover = PanelUi.in(mouseX, mouseY, x, yy, w, rowH - 1);
                if (active || hover) {
                    UiTheme.fillRounded(g, x, yy, w, rowH - 1, UiTheme.RADIUS_CARD,
                            active ? UiTheme.SELECTED : UiTheme.HOVER);
                }
                PanelUi.text(g, font, displayName(id), x + 6, yy + 4,
                        active ? UiTheme.TEXT : UiTheme.TEXT_DIM, w - 26);
                if (active) {
                    PanelUi.dot(g, x + w - 14, yy + 5, 6, true);
                }
                final String voice = id;
                host.hits.add(x, yy, w, rowH - 1, () -> {
                    host.engine.setVoice(voice);
                    host.toast("音色：" + displayName(voice));
                });
                yy += rowH;
            }
            return yy;
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            scroll -= (int) Math.round(delta * 2.0 * 10.0);
            if (scroll < 0) {
                scroll = 0;
            }
            return true;
        }
    }

    // ================================================================ 快捷键

    static final class Help extends Overlay {

        /** {按键, 说明}。Tab/1-6/空格/方向键沿用既有约定，本轮新增的排在后面。 */
        private static final String[][] KEYS = {
                {"Esc", "关闭弹层；没有弹层时关闭面板"},
                {"Tab / Shift+Tab", "下一个 / 上一个视图"},
                {"1 ~ 6", "书架 / 搜索 / 目录 / 正文 / 设置 / 书签"},
                {"空格", "播放 / 暂停"},
                {"← / →", "上一章 / 下一章"},
                {"PageUp / PageDown", "正文翻页（其它视图滚动列表）"},
                {"Enter", "搜索框内搜索；目录过滤框内跳到该章"},
                {"F1", "打开本帮助页"},
                {"B", "给当前句加书签"},
                {"V", "打开音色选择弹层"},
                {"F", "跳到目录并聚焦过滤框"},
                {"鼠标拖动进度条", "松手后跳到对应句子"},
                {"滚轮", "列表 / 正文 / 弹层滚动"},
        };

        Help(LocalReaderScreen host) {
            super(host);
        }

        @Override
        String title() {
            return "快捷键";
        }

        @Override
        void render(GuiGraphics g, Font font, int x, int y, int w, int h,
                    int mouseX, int mouseY) {
            int top = frame(g, x, y, w, h, mouseX, mouseY);
            int rowH = 15;
            int keyW = Math.min(96, Math.max(60, w / 3));
            int yy = top + 2;
            for (String[] row : KEYS) {
                if (yy + rowH > y + h - 6) {
                    break;
                }
                PanelUi.text(g, font, row[0], x + 10, yy, UiTheme.TEXT, keyW);
                PanelUi.text(g, font, row[1], x + 10 + keyW + 6, yy, UiTheme.TEXT_DIM,
                        Math.max(20, w - 20 - keyW - 6));
                yy += rowH;
            }
        }
    }
}
