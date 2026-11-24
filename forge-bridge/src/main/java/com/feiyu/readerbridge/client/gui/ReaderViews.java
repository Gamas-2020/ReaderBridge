package com.feiyu.readerbridge.client.gui;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读面板的五个视图：书架 / 搜索 / 目录 / 正文 / 设置。
 *
 * <p>视图只做「画 + 登记命中区」，状态存在 {@link LocalReaderScreen} 上，
 * 这样切换视图不丢滚动位置、不丢搜索结果，也不会因为某个视图抛异常带崩整块面板。
 *
 * <p>视觉语言全部来自 {@link UiTheme}：深色卡片 + 圆形书封 + 顶部标题栏 + 底部播放条。
 */
final class ReaderViews {

    private ReaderViews() {
    }

    private static final int PAD = 8;

    /**
     * 正文右端给滚动条留的额外空隙（px）。
     *
     * <p>滚动条画在 {@code x + w - 4 .. x + w - 1}（宽 4px），正文宽度 {@code textW} 里再扣掉
     * 这 6px，于是正文右边界 = {@code x + w - PAD - 6 = x + w - 14}，与滚动条左沿之间留 10px，
     * 与面板内右边（{@code x + w}）留 14px。这个常量以前是写死在
     * {@code w - PAD * 2 - 6} 里的魔法数，提出来是为了让"正文右边界不得越过
     * {@code innerX + innerW}"这条判据有名字可对。</p>
     */
    private static final int SCROLLBAR_GUTTER = 6;

    /** 视图基类。body 矩形由宿主每帧算好传进来。 */
    abstract static class View {

        final LocalReaderScreen host;

        View(LocalReaderScreen host) {
            this.host = host;
        }

        abstract void render(GuiGraphics g, int x, int y, int w, int h,
                             int mouseX, int mouseY, float partialTick);

        /** @return 是否消费了本次点击 */
        boolean mouseClicked(double mouseX, double mouseY) {
            return false;
        }

        /** @return 是否消费了本次滚轮 */
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            return false;
        }

        /** 视图被切入时调用。 */
        void onShow() {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ================================================================ 书架

    static final class Shelf extends View {

        Shelf(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            if (w < 60 || h < 40) {
                return;
            }
            ReaderEngine e = host.engine;
            Font font = host.font();
            java.util.List<com.feiyu.readerbridge.client.fq.LibraryStore.Entry> lib =
                    com.feiyu.readerbridge.client.fq.LibraryStore.get().entries();
            com.feiyu.readerbridge.client.fq.LibraryStore.Entry latest =
                    com.feiyu.readerbridge.client.fq.LibraryStore.get().latest();

            int cardX = x + PAD;
            int cardW = w - PAD * 2;
            int cardH = PanelUi.clamp(h / 3, 76, 108);
            int cardY = y + PAD;

            UiTheme.fillRounded(g, cardX, cardY, cardW, cardH, UiTheme.RADIUS_CARD, UiTheme.CARD_SOFT);

            // 主卡片放「上次在读」那本：正在播它时用引擎的实时进度，否则用存档里的章进度
            boolean playingThis = latest != null && safe(e.bookId()).equals(safe(latest.bookId));
            String title = latest == null ? "" : safe(latest.title);
            String bookId = latest == null ? "" : safe(latest.bookId);
            String author = latest == null ? "" : safe(latest.author);
            double ratio = playingThis
                    ? e.progress()
                    : (latest == null || latest.totalChapters <= 0
                            ? 0.0
                            : Math.max(0.0, (double) latest.chapterIndex / latest.totalChapters));

            int coverSize = PanelUi.clamp(cardH - 24, 32, 56);
            int coverX = cardX + 10;
            int coverY = cardY + (cardH - coverSize) / 2;
            PanelUi.cover(g, font, title.isEmpty() ? "书" : title, coverX, coverY, coverSize,
                    PanelUi.coverColor(bookId.isEmpty() ? title : bookId));

            int tx = coverX + coverSize + 12;
            int tw = cardX + cardW - 12 - tx;
            if (tw < 40) {
                return;
            }

            if (title.isEmpty()) {
                PanelUi.text(g, font, "书架是空的", tx, cardY + 10, UiTheme.TEXT, tw);
                g.drawString(font, "去「搜索」找一本，打开后就记在这里", tx, cardY + 26, UiTheme.TEXT_DIM, false);
                g.drawString(font, "读过的书会记住章节和句子，下次接着听", tx, cardY + 40, UiTheme.TEXT_FAINT, false);
            } else {
                // 大字书名：2 倍缩放画（App 书架的标题层级）
                String big = PanelUi.ellipsis(font, title, Math.max(20, tw / 2));
                g.pose().pushPose();
                g.pose().scale(2.0f, 2.0f, 1.0f);
                g.drawString(font, big, Math.round(tx / 2.0f), Math.round((cardY + 8) / 2.0f),
                        UiTheme.TEXT, false);
                g.pose().popPose();

                PanelUi.text(g, font, author.isEmpty() ? "未知作者" : author,
                        tx, cardY + 30, UiTheme.TEXT_DIM, Math.max(20, tw - 100));

                // 继续朗读
                int bw = 88;
                int bh = 16;
                int bx = cardX + cardW - 12 - bw;
                int by = cardY + 26;
                boolean running = playingThis && e.isActive() && !e.isPaused();
                PanelUi.chip(g, font, running ? "暂停朗读" : "继续朗读", bx, by, bw, bh,
                        latest.hasProgress(), mouseX, mouseY);
                host.hits.add(bx, by, bw, bh, this::onContinue);

                int barY = cardY + 46;
                UiTheme.progressBar(g, tx, barY, Math.max(20, tw - 4), 4,
                        Math.max(0.0, Math.min(1.0, ratio)), UiTheme.STROKE, UiTheme.ACCENT);
                String pct = String.format("%.1f%%", Math.max(0.0, Math.min(1.0, ratio)) * 100.0);
                g.drawString(font, pct, tx, barY + 8, UiTheme.TEXT_DIM, false);
                // 章名优先取存档的：引擎此刻可能在播别的书
                String chap = playingThis ? safe(e.chapterTitle()) : safe(latest.chapterTitle);
                if (chap.isEmpty()) {
                    chap = latest.hasProgress()
                            ? ("第 " + (latest.chapterIndex + 1) + " 章")
                            : "还没开始读";
                }
                PanelUi.text(g, font, chap, tx + font.width(pct) + 8, barY + 8,
                        UiTheme.TEXT_FAINT, tw - font.width(pct) - 8);
            }

            // ---------------------------------------------------- 书架列表
            int listTitleY = cardY + cardH + 8;
            if (listTitleY + 14 > y + h) {
                return;
            }
            g.drawString(font, "书架（" + lib.size() + " 本）", cardX, listTitleY,
                    UiTheme.TEXT_FAINT, false);
            int listY = listTitleY + 12;
            int listH = (y + h - PAD) - listY;
            if (listH < 20) {
                return;
            }

            int rowH = 22;
            int total = lib.size();
            if (total == 0) {
                PanelUi.emptyState(g, font, cardX, listY, cardW, listH,
                        "书架还是空的", "「搜索」里输入书名或作者，回车");
                return;
            }
            int visible = Math.max(1, listH / rowH);
            host.shelfScroll = PanelUi.clamp(host.shelfScroll, 0, Math.max(0, total - visible));
            for (int i = host.shelfScroll; i < total; i++) {
                int rowY = listY + (i - host.shelfScroll) * rowH;
                if (rowY + rowH > listY + listH) {
                    break;
                }
                com.feiyu.readerbridge.client.fq.LibraryStore.Entry en = lib.get(i);
                if (en == null) {
                    continue;
                }
                boolean hover = PanelUi.in(mouseX, mouseY, cardX, rowY, cardW, rowH - 2);
                if (hover) {
                    UiTheme.fillRounded(g, cardX, rowY, cardW, rowH - 2, UiTheme.RADIUS_CARD, UiTheme.HOVER);
                }
                PanelUi.cover(g, font, safe(en.title), cardX + 4, rowY + 2, 16,
                        PanelUi.coverColor(safe(en.bookId)));
                // 右侧是「第N/共M章」，从没读过章就写「未开始」
                String right = en.hasProgress()
                        ? ("第 " + (en.chapterIndex + 1) + "/"
                        + Math.max(en.totalChapters, en.chapterIndex + 1) + " 章")
                        : "未开始";
                int contW = 34;
                int contX = cardX + cardW - contW - 4;
                // 行尾再加一个「×」删除热区（14px 小方块），放在「续」胶囊左边。
                // 命中区注册顺序即优先级（PanelUi.Hits.click 取第一条匹配），
                // 所以「×」必须<b>先</b>注册、整行点击<b>后</b>注册，
                // 否则点 × 会先命中整行 → 打开书详情，永远删不掉。
                int delW = 14;
                int delX = contX - delW - 2;
                int rightW = 62;
                boolean pending = host.isPendingDelete(safe(en.bookId));
                if (pending) {
                    // 待确认：整行底色改成红色，提示「已经点过一次 ×」
                    UiTheme.fillRounded(g, cardX, rowY, cardW, rowH - 2, UiTheme.RADIUS_CARD, 0xFF3B2A2A);
                }
                PanelUi.text(g, font, safe(en.title), cardX + 26, rowY + 6, UiTheme.TEXT,
                        Math.max(20, cardW - 34 - rightW - delW - 12));
                PanelUi.textRight(g, font, right, delX - 4, rowY + 6,
                        UiTheme.TEXT_FAINT, rightW);
                final com.feiyu.readerbridge.client.fq.LibraryStore.Entry target = en;
                // × 的二次确认在 onDelete 里：第一下只记 pendingDeleteId，3 秒内再点才真删。
                PanelUi.chip(g, font, "×", delX, rowY + 3, delW, rowH - 8, pending, mouseX, mouseY);
                host.hits.add(delX, rowY + 3, delW, rowH - 8, () -> onDelete(target));
                // 行点击 → 书详情弹层（成熟阅读器的「列表 → 详情」路径）。
                // 点任何一行都算「换了目标」，先清掉待确认删除：否则在 A 上点过一次 ×、
                // 再点 B 的行、回来再点一次 A，就会在玩家完全没有「确认」意图时把 A 删掉。
                host.hits.add(cardX, rowY, cardW, rowH - 2, () -> {
                    host.clearPendingDelete();
                    host.openBookDetail(target.toBookInfo());
                });
                // 行尾小胶囊保留「一步续听」，不强迫每次都走详情
                PanelUi.chip(g, font, "续", contX, rowY + 3, contW, rowH - 8, false, mouseX, mouseY);
                host.hits.add(contX, rowY + 3, contW, rowH - 8, () -> {
                    host.clearPendingDelete();
                    resume(target);
                });
            }
            PanelUi.scrollBar(g, x + w - 4, listY, listH, listH, total * rowH, host.shelfScroll * rowH);
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            host.shelfScroll -= (int) Math.round(delta * 2.0);
            if (host.shelfScroll < 0) {
                host.shelfScroll = 0;
            }
            return true;
        }

        /**
         * 行尾「×」：<b>二次确认</b>删除一本书。
         *
         * <p>第一下只把 bookId + 时间戳记进宿主（{@code host.pendingDeleteId}），整行转红并提示
         * 「再点一次确认删除《书名》」；3 秒内再点同一本才 {@code LibraryStore.remove} + {@code flush}。
         * 防的是误触：删掉一本就等于丢掉它的阅读进度，恢复不了。
         * 点别的行 / 切 tab / 超时都会清空这个状态（见 {@code LocalReaderScreen.switchTab} 与
         * 行点击路径）。
         */
        private void onDelete(com.feiyu.readerbridge.client.fq.LibraryStore.Entry en) {
            if (en == null) {
                return;
            }
            String id = safe(en.bookId);
            String title = safe(en.title);
            if (id.isEmpty()) {
                host.toast("这本书没有 bookId，无法删除");
                return;
            }
            long now = System.currentTimeMillis();
            if (id.equals(host.pendingDeleteId) && now - host.pendingDeleteAtMs <= 3000L) {
                boolean removed = com.feiyu.readerbridge.client.fq.LibraryStore.get().remove(id);
                com.feiyu.readerbridge.client.fq.LibraryStore.get().flush();
                host.clearPendingDelete();
                host.toast(removed ? ("已删除《" + title + "》") : ("《" + title + "》已经不在书架里了"));
                return;
            }
            host.pendingDeleteId = id;
            host.pendingDeleteAtMs = now;
            host.toast("再点一次确认删除《" + title + "》");
        }

        /** 点书架某本：有进度就从存档那一句续听，没有就重开目录让他自己选章。 */
        private void resume(com.feiyu.readerbridge.client.fq.LibraryStore.Entry en) {
            if (en == null) {
                return;
            }
            if (!en.hasProgress()) {
                host.openBook(en.toBookInfo());
                return;
            }
            host.engine.resumeEntry(en);
            host.switchTab(LocalReaderScreen.Tab.READER);
        }

        private void onContinue() {
            com.feiyu.readerbridge.client.fq.LibraryStore.Entry latest =
                    com.feiyu.readerbridge.client.fq.LibraryStore.get().latest();
            if (latest == null) {
                host.switchTab(LocalReaderScreen.Tab.SEARCH);
                return;
            }
            if (!latest.hasProgress()) {
                resume(latest);
                return;
            }
            ReaderEngine e = host.engine;
            // 正在播这本书 → 按钮是暂停/继续；否则从存档点续听
            if (safe(e.bookId()).equals(safe(latest.bookId)) && e.isActive()) {
                e.togglePlay();
            } else {
                e.resumeEntry(latest);
            }
            host.switchTab(LocalReaderScreen.Tab.READER);
        }
    }

    // ================================================================ 搜索

    static final class Search extends View {

        Search(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            Font font = host.font();
            if (w < 60 || h < 30) {
                return;
            }
            // 输入框由宿主以原版 EditBox 绘制（自带 IME），这里只画触发按钮
            int btnW = host.searchButtonWidth(w);
            int bw = btnW;
            int bh = 18;
            int bx = x + w - PAD - bw;
            int by = y + PAD + 1;
            PanelUi.chip(g, font, "搜索", bx, by, bw, bh, false, mouseX, mouseY);
            host.hits.add(bx, by, bw, bh, () -> host.doSearch(host.searchBoxValue()));

            int infoY = y + PAD + 23;
            String info;
            int color = UiTheme.TEXT_FAINT;
            if (host.busy) {
                int dots = (int) ((System.currentTimeMillis() / 300L) % 4);
                info = "正在搜索" + ".".repeat(dots);
                color = UiTheme.TEXT_DIM;
            } else if (!host.searchMsg.isEmpty()) {
                info = host.searchMsg;
                color = host.searchMsg.startsWith("搜索失败") ? UiTheme.ERROR : UiTheme.TEXT_DIM;
            } else {
                info = "回车搜索 · 点结果即打开";
            }
            PanelUi.text(g, font, info, x + PAD, infoY, color, w - PAD * 2);

            int listY = infoY + 14;
            // 搜索历史：最近 10 条（panel_history.json）。点词重搜、点 × 单删、右侧清空。
            java.util.List<String> history = host.history.keywords();
            if (!history.isEmpty() && listY + 18 < y + h) {
                int hy = listY;
                String tag = "最近：";
                g.drawString(font, tag, x + PAD, hy + 3, UiTheme.TEXT_FAINT, false);
                int cx = x + PAD + font.width(tag) + 4;
                int limit = x + w - PAD - 44;
                for (int i = 0; i < history.size(); i++) {
                    String kw = history.get(i);
                    int chipW = Math.min(76, font.width(kw) + 16);
                    if (cx + chipW + 14 > limit) {
                        break;
                    }
                    PanelUi.chip(g, font, kw, cx, hy, chipW, 14, false, mouseX, mouseY);
                    final String kwf = kw;
                    host.hits.add(cx, hy, chipW, 14, () -> {
                        // 换目标就清掉待确认：否则在 A 上点过 × 又去点 B 再回头点 A 会误删
                        host.clearPendingDelete();
                        host.doSearch(kwf);
                    });
                    int delX = cx + chipW;
                    // 历史词删除同样走二次确认（与书架共用 pendingDeleteId，加 "kw:" 前缀隔离 id 空间）
                    boolean kwPending = host.isPendingDelete("kw:" + kw);
                    PanelUi.chip(g, font, "×", delX, hy, 13, 14, kwPending, mouseX, mouseY);
                    host.hits.add(delX, hy, 13, 14, () -> onHistoryDelete(kwf));
                    cx += chipW + 15;
                }
                int clearW = 36;
                int clearX = x + w - PAD - clearW;
                PanelUi.chip(g, font, "清空", clearX, hy, clearW, 14, false, mouseX, mouseY);
                host.hits.add(clearX, hy, clearW, 14, host.history::clear);
                listY += 20;
            }
            int listH = (y + h - PAD) - listY;
            if (listH < 16) {
                return;
            }

            int total = host.results.size();
            if (host.busy && total == 0) {
                PanelUi.emptyState(g, font, x + PAD, listY, w - PAD * 2, listH,
                        "正在等什锦铺回数据…", "首次搜索可能要 1~3 秒");
                return;
            }
            if (total == 0) {
                PanelUi.emptyState(g, font, x + PAD, listY, w - PAD * 2, listH,
                        "搜索什锦铺书库", "书名 / 作者都行，中文可直接输入");
                return;
            }

            int rowH = 22;
            int visible = Math.max(1, listH / rowH);
            host.searchScroll = PanelUi.clamp(host.searchScroll, 0, Math.max(0, total - visible));
            for (int i = host.searchScroll; i < total; i++) {
                int rowY = listY + (i - host.searchScroll) * rowH;
                if (rowY + rowH > listY + listH) {
                    break;
                }
                FqApiClient.BookInfo b = host.results.get(i);
                if (b == null) {
                    continue;
                }
                boolean hover = PanelUi.in(mouseX, mouseY, x + PAD, rowY, w - PAD * 2, rowH - 2);
                if (hover) {
                    UiTheme.fillRounded(g, x + PAD, rowY, w - PAD * 2, rowH - 2,
                            UiTheme.RADIUS_CARD, UiTheme.HOVER);
                }
                PanelUi.cover(g, font, safe(b.name), x + PAD + 4, rowY + 2, 16,
                        PanelUi.coverColor(safe(b.bookId)));
                PanelUi.text(g, font, safe(b.name), x + PAD + 26, rowY + 6, UiTheme.TEXT,
                        Math.max(20, w - PAD * 2 - 34 - 78));
                PanelUi.textRight(g, font, safe(b.author), x + w - PAD - 6, rowY + 6,
                        UiTheme.TEXT_FAINT, 74);
                final FqApiClient.BookInfo target = b;
                host.hits.add(x + PAD, rowY, w - PAD * 2, rowH - 2, () -> host.openBook(target));
            }
            PanelUi.scrollBar(g, x + w - 4, listY, listH, listH, total * rowH, host.searchScroll * rowH);
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            host.searchScroll -= (int) Math.round(delta * 2.0);
            if (host.searchScroll < 0) {
                host.searchScroll = 0;
            }
            return true;
        }

        /**
         * 历史关键词的「×」：与书架同一套二次确认（3 秒内再点同一个词才真的删）。
         *
         * <p>id 空间用 {@code "kw:"} 前缀隔开 —— 关键词理论上可能长得像某个 bookId，
         * 混用会让「在 A 词上确认、结果删掉同名字的书架条目」这种莫名其妙的事发生。
         */
        private void onHistoryDelete(String kw) {
            if (kw == null || kw.isEmpty()) {
                return;
            }
            String id = "kw:" + kw;
            long now = System.currentTimeMillis();
            if (id.equals(host.pendingDeleteId) && now - host.pendingDeleteAtMs <= 3000L) {
                host.history.remove(kw);
                host.clearPendingDelete();
                host.toast("已删除历史「" + kw + "」");
                return;
            }
            host.pendingDeleteId = id;
            host.pendingDeleteAtMs = now;
            host.toast("再点一次确认删除历史「" + kw + "」");
        }
    }

    // ================================================================ 目录

    static final class Chapters extends View {

        Chapters(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void onShow() {
            int cur = host.engine.chapterIndex();
            if (cur >= 0) {
                host.chapterScroll = Math.max(0, cur - 3);
            }
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            Font font = host.font();
            if (w < 60 || h < 40) {
                return;
            }
            List<FqApiClient.ChapterInfo> chapters = host.engine.chapters();
            int cur = host.engine.chapterIndex();

            // 过滤框由宿主以原版 EditBox 绘制（自带 IME），这里只画右侧「跳」按钮
            int jumpW = 34;
            int jumpX = x + w - PAD - jumpW;
            PanelUi.chip(g, font, "跳", jumpX, y + PAD, jumpW, 18, false, mouseX, mouseY);
            host.hits.add(jumpX, y + PAD, jumpW, 18, host::jumpToChapterFromInput);

            String filter = host.chapterFilterValue() == null ? "" : host.chapterFilterValue().trim();
            String head = chapters.isEmpty()
                    ? "还没有目录"
                    : ("共 " + chapters.size() + " 章"
                    + (cur >= 0 ? " · 当前第 " + (cur + 1) + " 章" : "")
                    + (filter.isEmpty() ? "" : " · 过滤「" + filter + "」"));
            PanelUi.text(g, font, head, x + PAD, y + PAD + 22, UiTheme.TEXT_FAINT,
                    Math.max(20, w - PAD * 2 - 2));

            int listY = y + PAD + 37;
            int listH = (y + h - PAD) - listY;
            if (listH < 16) {
                return;
            }
            if (chapters.isEmpty()) {
                PanelUi.emptyState(g, font, x + PAD, listY, w - PAD * 2, listH,
                        "先去书架或搜索打开一本书", "目录来自什锦铺的书籍详情");
                return;
            }

            // 「已读」基准取书架存档的章进度；当前章用引擎实时值，读到一半的书也能标对
            com.feiyu.readerbridge.client.fq.LibraryStore.Entry entry =
                    com.feiyu.readerbridge.client.fq.LibraryStore.get().find(safe(host.engine.bookId()));
            int readUpTo = entry != null && entry.hasProgress() ? entry.chapterIndex : -1;

            // 过滤：纯数字按章号前缀匹配（输入 12 → 第 12、120 章），其它按标题包含
            String q = filter.toLowerCase(java.util.Locale.ROOT);
            boolean numeric = !q.isEmpty() && q.matches("\\d{1,6}");
            List<Integer> visibleIdx = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                FqApiClient.ChapterInfo c = chapters.get(i);
                if (c == null) {
                    continue;
                }
                if (q.isEmpty()) {
                    visibleIdx.add(i);
                    continue;
                }
                String title = safe(c.title).toLowerCase(java.util.Locale.ROOT);
                if (numeric) {
                    if (String.valueOf(i + 1).startsWith(q) || title.contains(q)) {
                        visibleIdx.add(i);
                    }
                } else if (title.contains(q)) {
                    visibleIdx.add(i);
                }
            }

            int rowH = 18;
            int total = visibleIdx.size();
            if (total == 0) {
                PanelUi.emptyState(g, font, x + PAD, listY, w - PAD * 2, listH,
                        "没有匹配的章节", "换个关键词，或清空过滤框");
                return;
            }
            int visible = Math.max(1, listH / rowH);
            host.chapterScroll = PanelUi.clamp(host.chapterScroll, 0, Math.max(0, total - visible));
            for (int vi = host.chapterScroll; vi < total; vi++) {
                int rowY = listY + (vi - host.chapterScroll) * rowH;
                if (rowY + rowH > listY + listH) {
                    break;
                }
                int i = visibleIdx.get(vi);
                FqApiClient.ChapterInfo c = chapters.get(i);
                boolean active = i == cur;
                boolean read = readUpTo >= 0 && i < readUpTo;
                boolean hover = PanelUi.in(mouseX, mouseY, x + PAD, rowY, w - PAD * 2, rowH);
                if (active) {
                    UiTheme.fillRounded(g, x + PAD, rowY, w - PAD * 2, rowH - 1,
                            UiTheme.RADIUS_CARD, UiTheme.SELECTED);
                    UiTheme.fillRounded(g, x + PAD + 1, rowY + 4, 2, rowH - 9, 1, UiTheme.ACCENT);
                } else if (hover) {
                    UiTheme.fillRounded(g, x + PAD, rowY, w - PAD * 2, rowH - 1,
                            UiTheme.RADIUS_CARD, UiTheme.HOVER);
                }
                String no = String.valueOf(i + 1);
                int noW = font.width(no) + 6;
                int markW = 30;
                g.drawString(font, no, x + PAD + 8, rowY + 5,
                        active ? UiTheme.ACCENT : UiTheme.TEXT_FAINT, false);
                PanelUi.text(g, font, safe(c.title), x + PAD + 8 + noW, rowY + 5,
                        active ? UiTheme.TEXT : (read ? UiTheme.TEXT_FAINT : UiTheme.TEXT_DIM),
                        Math.max(20, w - PAD * 2 - 16 - noW - markW));
                String mark = active ? "在读" : (read ? "已读" : "");
                if (!mark.isEmpty()) {
                    PanelUi.textRight(g, font, mark, x + w - PAD - 8, rowY + 5,
                            active ? UiTheme.ACCENT : UiTheme.TEXT_FAINT, markW);
                }
                final int target = i;
                host.hits.add(x + PAD, rowY, w - PAD * 2, rowH, () -> host.openChapterFromList(target));
            }
            PanelUi.scrollBar(g, x + w - 4, listY, listH, listH, total * rowH, host.chapterScroll * rowH);
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            host.chapterScroll -= (int) Math.round(delta * 2.0);
            if (host.chapterScroll < 0) {
                host.chapterScroll = 0;
            }
            return true;
        }
    }

    // ================================================================ 正文

    static final class Reader extends View {

        /** 句子 → 段内字符区间。 */
        private static final class Seg {
            final int para;
            final int start;
            final int end;

            Seg(int para, int start, int end) {
                this.para = para;
                this.start = start;
                this.end = end;
            }
        }

        private static final class ParaLayout {
            final List<PanelUi.TextLine> lines;

            ParaLayout(List<PanelUi.TextLine> lines) {
                this.lines = lines;
            }
        }

        private static final class Layout {
            final List<ParaLayout> paras;
            final List<Seg> segs;
            final int width;
            /** 排版时用的逻辑行高（= prefs.lineHeight()），缓存键必须带上它，否则调行距不重排。 */
            final int lineH;
            final int chapter;
            final int paraCount;
            final int sentCount;
            final int contentHeight;

            Layout(List<ParaLayout> paras, List<Seg> segs, int width, int lineH, int chapter,
                   int paraCount, int sentCount, int contentHeight) {
                this.paras = paras;
                this.segs = segs;
                this.width = width;
                this.lineH = lineH;
                this.chapter = chapter;
                this.paraCount = paraCount;
                this.sentCount = sentCount;
                this.contentHeight = contentHeight;
            }
        }

        private Layout cache;

        // 点击定位用的几何快照：mouseClicked 拿不到 render 的局部变量，每帧画完把布局原样记下来，
        // 点击时按同一套坐标反算，避免两处各写一份公式后慢慢漂移。
        private int hitViewTop = Integer.MIN_VALUE;
        private int hitViewH;
        private int hitTextX;
        private int hitTextW;
        private int hitLineH = PanelUi.LINE_H;

        Reader(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            Font font = host.font();
            ReaderEngine e = host.engine;
            if (w < 60 || h < 44) {
                return;
            }
            int textX = x + PAD;
            int textW = w - PAD * 2 - SCROLLBAR_GUTTER;
            if (textW < 30) {
                return;
            }

            String book = safe(e.bookTitle());
            String chap = safe(e.chapterTitle());
            String head = chap.isEmpty() ? (book.isEmpty() ? "正文" : book)
                    : (book.isEmpty() ? chap : book + " · " + chap);

            int followW = 54;
            int followH = 14;
            int followX = x + w - PAD - followW;

            // 句数先算出来（右对齐到 followX - 6），标题再按它的实际左边界反推可用宽度。
            // 旧实现是「head 的 maxPx = followX - textX - 74、cnt 的 maxPx = 70、cnt 右端 followX - 6」：
            // 74 = 70 + 4 看着对得上，但 cnt 是<b>右对齐</b>到 followX - 6，实际占
            // [followX - 6 - 70, followX - 6)，于是 head 右界 followX - 4 恒在 cnt 左界
            // followX - 76 的右边 2px —— 标题尾部会压住句数首字符（用户反馈的「正文右侧被遮挡」
            // 就包含这一处；正文段落本身不越界，见 wrapLines 的逐码点裁剪）。
            String cnt = e.sentenceCount() > 0
                    ? (Math.max(0, e.sentenceIndex()) + 1) + "/" + e.sentenceCount()
                    : "";
            int cntW = Math.min(70, font.width(cnt));
            int cntX = followX - 6 - cntW;
            int headMax = Math.max(0, cntX - 6 - textX);
            if (headMax > 8) {
                PanelUi.text(g, font, head, textX, y + 3, UiTheme.TEXT, headMax);
            }
            boolean follow = host.readerFollow;
            PanelUi.chip(g, font, follow ? "跟随中" : "已暂停", followX, y + 1, followW, followH,
                    follow, mouseX, mouseY);
            host.hits.add(followX, y + 1, followW, followH, () -> host.readerFollow = true);

            PanelUi.textRight(g, font, cnt, followX - 6, y + 4, UiTheme.TEXT_FAINT, 70);

            int viewTop = y + 18;
            int viewH = (y + h) - viewTop;
            if (viewH < 12) {
                return;
            }

            // 行高必须跟「行距」设置走（prefs.lineHeight()），与宿主 readerLineStep()
            // （PageUp/PageDown 的翻页步长）同源。旧实现这里写死 PanelUi.LINE_H = 10，
            // 于是设置页里的「行距 1.2~2.4」只改了翻页步长、正文行距从不变化。
            int lineH = Math.max(PanelUi.LINE_H, host.prefs.lineHeight());
            Layout layout = layout(font, textW, lineH, e);
            if (layout.paras.isEmpty()) {
                String err = safe(e.errorLine());
                String status = safe(e.statusLine());
                if (!err.isEmpty()) {
                    PanelUi.emptyState(g, font, x + PAD, viewTop, w - PAD * 2, viewH, err, "");
                } else if (!status.isEmpty()) {
                    PanelUi.emptyState(g, font, x + PAD, viewTop, w - PAD * 2, viewH, status, "");
                } else {
                    PanelUi.emptyState(g, font, x + PAD, viewTop, w - PAD * 2, viewH,
                            "点章节开始朗读", "「目录」里选一章，或「书架」继续朗读");
                }
                return;
            }

            hitViewTop = viewTop;
            hitViewH = viewH;
            hitTextX = textX;
            hitTextW = textW;
            hitLineH = lineH;
            int maxScroll = Math.max(0, layout.contentHeight - viewH);
            host.readerScroll = PanelUi.clamp(host.readerScroll, 0, maxScroll);

            Seg seg = currentSeg(layout, e.sentenceIndex());
            int hlY0 = Integer.MIN_VALUE;
            int hlY1 = Integer.MIN_VALUE;

            // 裁剪区必须是「逻辑坐标 → 屏幕坐标」换算后的矩形，见 LocalReaderScreen#scissorLogical。
            //
            // 旧代码直接 g.enableScissor(textX, viewTop, x + w - PAD, viewTop + viewH) 并注释
            // 「1.20.1 的 enableScissor 会经过当前 PoseStack 变换」——那句话是错的：
            // 1.20.1 的 GuiGraphics.enableScissor 只把四个 int 当屏幕坐标乘 GUI scale 交给
            // RenderSystem，PoseStack 完全不参与（变换感知裁剪是 1.20.5+）。而这里是在
            // LocalReaderScreen#renderBody 的 pose().scale(s) 之后绘制的，x/w 是逻辑 px，
            // 于是裁剪右界只有 vx + (lw - PAD) 屏幕 px，正文却画到 vx + (lw - 14) * s。
            // 默认字号 14（s = 14/9 ≈ 1.556）时每行右侧约 182 屏幕 px 被裁掉 —— 这才是
            // 「离线算下来不溢出、实机正文右侧仍被遮挡」的真因。
            host.scissorLogical(g, textX, viewTop, x + w - PAD, viewTop + viewH);
            try {
                int yy = viewTop - host.readerScroll;
                for (int pi = 0; pi < layout.paras.size(); pi++) {
                    ParaLayout p = layout.paras.get(pi);
                    int ph = Math.max(lineH, p.lines.size() * lineH);
                    boolean visible = yy + ph >= viewTop - lineH && yy <= viewTop + viewH;
                    if (visible) {
                        // 高亮先画（文字下层）
                        if (seg != null && seg.para == pi) {
                            for (int li = 0; li < p.lines.size(); li++) {
                                PanelUi.TextLine line = p.lines.get(li);
                                if (line.srcEnd <= seg.start || line.srcStart >= seg.end) {
                                    continue;
                                }
                                int ly = yy + li * lineH;
                                UiTheme.fillRounded(g, textX - 2, ly - 1, textW + 4, lineH, 2,
                                        UiTheme.ACCENT_DIM);
                                if (hlY0 == Integer.MIN_VALUE) {
                                    hlY0 = ly - 1;
                                }
                                hlY1 = ly + lineH + 1;
                            }
                        }
                        for (int li = 0; li < p.lines.size(); li++) {
                            g.drawString(font, p.lines.get(li).text, textX, yy + li * lineH,
                                    UiTheme.TEXT, false);
                        }
                    }
                    yy += ph + PanelUi.PARA_GAP;
                    if (yy > viewTop + viewH + PanelUi.PARA_GAP) {
                        break;
                    }
                }
            } finally {
                g.disableScissor();
            }

            // 自动把当前句滚进可视区（手动滚动过的话等用户点「跟随」）
            if (follow && hlY0 != Integer.MIN_VALUE) {
                int delta = 0;
                if (hlY0 < viewTop + 2) {
                    delta = hlY0 - (viewTop + 2) - 1;
                } else if (hlY1 > viewTop + viewH - 2) {
                    delta = hlY1 - (viewTop + viewH - 2) + 1;
                }
                if (delta != 0) {
                    host.readerScroll = PanelUi.clamp(host.readerScroll + delta, 0, maxScroll);
                }
            }

            PanelUi.scrollBar(g, x + w - 4, viewTop, viewH, viewH, layout.contentHeight, host.readerScroll);
        }

        /**
         * 点正文的任意一行 → 从那一行的第一句开始朗读。
         *
         * <p>按"内容坐标"反算（{@code 鼠标 y - 视口顶 + 滚动量}），所以滚动之后点击依然准确；
         * 只取该行的起始字符，不按 x 猜字，避免中文宽度估算带来的偏移。</p>
         */
        @Override
        boolean mouseClicked(double mouseX, double mouseY) {
            Layout layout = cache;
            if (layout == null || layout.segs.isEmpty() || hitViewTop == Integer.MIN_VALUE) {
                return false;
            }
            int mx = (int) Math.round(mouseX);
            int my = (int) Math.round(mouseY);
            if (mx < hitTextX || mx > hitTextX + hitTextW
                    || my < hitViewTop || my > hitViewTop + hitViewH) {
                return false;
            }
            int rel = my - hitViewTop + host.readerScroll;
            int lineH = Math.max(1, hitLineH);
            int yy = 0;
            for (int pi = 0; pi < layout.paras.size(); pi++) {
                ParaLayout p = layout.paras.get(pi);
                int ph = Math.max(lineH, p.lines.size() * lineH);
                if (rel >= yy && rel < yy + ph && !p.lines.isEmpty()) {
                    int li = Math.min(p.lines.size() - 1, (rel - yy) / lineH);
                    int off = p.lines.get(li).srcStart;
                    int target = segAt(layout, pi, off);
                    if (target < 0) {
                        return false;
                    }
                    host.engine.seekSentence(target);
                    host.readerFollow = true;
                    return true;
                }
                yy += ph + PanelUi.PARA_GAP;
            }
            return false;
        }

        /** 某段内字符偏移落在哪个句子；落在句间空隙时归前一句，落在段首之前归第一句。 */
        private static int segAt(Layout layout, int para, int offset) {
            int best = -1;
            for (int i = 0; i < layout.segs.size(); i++) {
                Seg s = layout.segs.get(i);
                if (s.para != para) {
                    continue;
                }
                if (offset < s.start) {
                    return best >= 0 ? best : i;
                }
                if (offset <= s.end) {
                    return i;
                }
                best = i;
            }
            return best;
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            // 滚轮步长也跟随「行距」：写死 2*LINE_H(20) 时，行距调大后每格滚动的视觉距离
            // 比一行还短，读者会觉得「滚了没动」，而且翻页步长（host.readerLineStep）已经
            // 走 prefs.lineHeight() 了，两处必须同源。
            host.readerScroll -= (int) Math.round(delta * 2.0 * Math.max(1, hitLineH));
            if (host.readerScroll < 0) {
                host.readerScroll = 0;
            }
            host.readerFollow = false;
            return true;
        }

        private static Seg currentSeg(Layout layout, int sentenceIndex) {
            if (layout.segs.isEmpty() || layout.segs.size() != layout.sentCount) {
                // 句子到段落的映射不完整时宁可不标，也不要标错句
                return null;
            }
            if (sentenceIndex < 0 || sentenceIndex >= layout.segs.size()) {
                return null;
            }
            return layout.segs.get(sentenceIndex);
        }

        private Layout layout(Font font, int width, int lineH, ReaderEngine e) {
            List<String> paras = e.paragraphs();
            List<String> sents = e.sentences();
            int chapter = e.chapterIndex();
            int pc = paras.size();
            int sc = sents.size();
            Layout c = cache;
            if (c != null && c.width == width && c.lineH == lineH && c.chapter == chapter
                    && c.paraCount == pc && c.sentCount == sc) {
                return c;
            }

            List<ParaLayout> plist = new ArrayList<>(pc);
            int total = 0;
            for (int i = 0; i < pc; i++) {
                List<PanelUi.TextLine> lines = PanelUi.wrapLines(font, paras.get(i), width);
                plist.add(new ParaLayout(lines));
                total += Math.max(lineH, lines.size() * lineH) + PanelUi.PARA_GAP;
            }

            // 句子是段落的子串（切句只 trim + 超长硬切），按顺序 indexOf 就能拿到段内区间
            List<Seg> segs = new ArrayList<>(sc);
            int si = 0;
            for (int p = 0; p < pc && si < sc; p++) {
                String src = paras.get(p);
                if (src == null || src.isEmpty()) {
                    continue;
                }
                int from = 0;
                boolean matched = false;
                while (si < sc) {
                    String s = sents.get(si);
                    if (s == null || s.isEmpty()) {
                        si++;
                        continue;
                    }
                    int at = src.indexOf(s, from);
                    if (at < 0) {
                        break;
                    }
                    segs.add(new Seg(p, at, at + s.length()));
                    from = at + s.length();
                    si++;
                    matched = true;
                }
                if (!matched) {
                    // 这一句在本段里找不到（切句跨段 / trim 过），跳过它免得卡住整章映射
                    si++;
                }
            }

            Layout built = new Layout(plist, segs, width, lineH, chapter, pc, sc, total);
            cache = built;
            return built;
        }
    }

    // ================================================================ 设置

    static final class Settings extends View {

        Settings(LocalReaderScreen host) {
            super(host);
        }

        @Override
        void render(GuiGraphics g, int x, int y, int w, int h,
                    int mouseX, int mouseY, float partialTick) {
            Font font = host.font();
            ReaderEngine e = host.engine;
            if (w < 80 || h < 40) {
                return;
            }
            int cardX = x + PAD;
            int cardW = w - PAD * 2;
            int voices = Math.max(1, e.voices().size());
            int h0 = PanelExtras.readingCardHeight();
            int h1 = 28 + voices * 16;
            int h2 = 50;
            int h3 = 82;
            int h4 = 28 + 4 * 14;
            // 界面风格卡：标题行 + 一行「当前 / 循环切换」+ 一行说明
            int h5 = 28 + 2 * 14;
            // HUD 风格卡：与界面风格卡同构（标题行 + 当前值/循环切换 + 一句说明）
            int h6 = 28 + 2 * 14;
            // HUD 缩放卡：与 HUD 风格卡同构（标题行 + 当前值/循环切换 + 一句说明）
            int h7 = 28 + 2 * 14;
            int gap = 8;
            // 7 张卡之间共 7 段 gap（h0→h1→h2→h3→h5→h6→h7→h4），旧式子的 gap*4 少算过一段，
            // 这次一次补齐：内容高度只影响滚动条比例，但少算会让滚动条滑块偏大、贴不到底。
            int totalH = h0 + h1 + h2 + h3 + h4 + h5 + h6 + h7 + gap * 7 + PAD * 2;
            int maxScroll = Math.max(0, totalH - h);
            host.settingsScroll = PanelUi.clamp(host.settingsScroll, 0, maxScroll);

            g.enableScissor(x, y, x + w, y + h);
            try {
                int yy = y + PAD - host.settingsScroll;
                // 阅读外观（字号 / 行距 / 内容宽度 / 主题 / 跟随）排最前：打开设置最常改的就是这些
                PanelExtras.renderReadingCard(g, font, cardX, yy, cardW, h0, host, mouseX, mouseY);
                yy += h0 + gap;

                // -------------------------------------------------- 音色
                yy = card(g, font, cardX, yy, cardW, h1, "音色", safe(e.voiceShortName()));
                int rowY = yy - h1 + 24;
                for (int i = 0; i < e.voices().size(); i++) {
                    String id = e.voices().get(i);
                    if (id == null) {
                        rowY += 16;
                        continue;
                    }
                    boolean active = id.equals(e.voiceShortName());
                    boolean hover = PanelUi.in(mouseX, mouseY, cardX + 6, rowY, cardW - 12, 15);
                    if (hover || active) {
                        UiTheme.fillRounded(g, cardX + 6, rowY, cardW - 12, 15, UiTheme.RADIUS_CARD,
                                active ? UiTheme.SELECTED : UiTheme.HOVER);
                    }
                    g.drawString(font, PanelUi.ellipsis(font, id, Math.max(20, cardW - 44)),
                            cardX + 12, rowY + 4, active ? UiTheme.TEXT : UiTheme.TEXT_DIM, false);
                    if (active) {
                        PanelUi.dot(g, cardX + cardW - 18, rowY + 5, 6, true);
                    }
                    final String voice = id;
                    if (rowY + 15 > y && rowY < y + h) {
                        host.hits.add(cardX + 6, rowY, cardW - 12, 15, () -> {
                            e.setVoice(voice);
                            host.toast("音色：" + voice);
                        });
                    }
                    rowY += 16;
                }

                // -------------------------------------------------- 语速
                yy += gap;
                yy = card(g, font, cardX, yy, cardW, h2, "语速", safe(e.speedLabel()));
                rowY = yy - h2 + 26;
                g.drawString(font, safe(e.speedLabel()), cardX + 12, rowY, UiTheme.TEXT, false);
                int dotX = cardX + 12 + font.width(safe(e.speedLabel())) + 12;
                for (int i = 0; i < e.speedCount(); i++) {
                    PanelUi.dot(g, dotX + i * 10, rowY + 1, 6, i == e.speedIndex());
                }
                int sw = 76;
                int sx = cardX + cardW - 12 - sw;
                PanelUi.chip(g, font, "循环切换", sx, rowY - 2, sw, 16, false, mouseX, mouseY);
                host.hits.add(sx, rowY - 2, sw, 16, () -> {
                    e.cycleSpeed();
                    host.toast("语速 " + e.speedLabel() + "（下一段起生效）");
                });
                g.drawString(font, "左侧 ◀ ▶ 也能切章节，语速是循环式", cardX + 12, rowY + 18,
                        UiTheme.TEXT_FAINT, false);

                // -------------------------------------------------- 世界文字
                yy += gap;
                yy = card(g, font, cardX, yy, cardW, h3, "世界文字", "只读");
                rowY = yy - h3 + 24;
                double dist = 3.0;
                boolean showWorld = true;
                try {
                    dist = ReaderBridgeConfig.worldTextDistance();
                    showWorld = ReaderBridgeConfig.showWorldText();
                } catch (Throwable ignored) {
                    // 配置未加载时用兜底值，面板不崩
                }
                String distText = String.format("%.1f 格", dist);
                g.drawString(font, "距离", cardX + 12, rowY, UiTheme.TEXT_DIM, false);
                g.drawString(font, distText, cardX + 48, rowY, UiTheme.TEXT, false);
                int bx = cardX + 48 + font.width(distText) + 10;
                PanelUi.chip(g, font, "-", bx, rowY - 2, 16, 14, false, mouseX, mouseY);
                PanelUi.chip(g, font, "+", bx + 20, rowY - 2, 16, 14, false, mouseX, mouseY);
                host.hits.add(bx, rowY - 2, 36, 14, () -> host.toast(
                        "距离写在 config/readerbridge-common.toml，面板内暂时只读"));
                rowY += 16;
                g.drawString(font, "开关", cardX + 12, rowY, UiTheme.TEXT_DIM, false);
                g.drawString(font, showWorld ? "开" : "关", cardX + 48, rowY,
                        showWorld ? UiTheme.OK : UiTheme.TEXT_FAINT, false);
                rowY += 16;
                g.drawString(font, "改 worldTextDistance / showWorldText 后重进世界生效",
                        cardX + 12, rowY, UiTheme.TEXT_FAINT, false);
                rowY += 16;
                g.drawString(font, "3D 文字渲染由 WorldTextRenderer 负责，面板只显示状态",
                        cardX + 12, rowY, UiTheme.TEXT_FAINT, false);

                // -------------------------------------------------- 界面风格
                // 必须两套界面都能切：只在晴雪UI 里能切「原版」、回原版就切不回来，等于死锁。
                yy += gap;
                String uiMode = "auto";
                try {
                    uiMode = ReaderBridgeConfig.uiMode();
                } catch (Throwable ignored) {
                    // 配置未加载时按 auto 显示，设置页不崩
                }
                String uiLabel = ReaderBridgeConfig.uiModeLabel(uiMode);
                yy = card(g, font, cardX, yy, cardW, h5, "界面风格", uiLabel);
                rowY = yy - h5 + 24;
                g.drawString(font, "当前", cardX + 12, rowY, UiTheme.TEXT_DIM, false);
                g.drawString(font, uiLabel, cardX + 48, rowY, UiTheme.TEXT, false);
                int uw = 76;
                int ux = cardX + cardW - 12 - uw;
                PanelUi.chip(g, font, "循环切换", ux, rowY - 2, uw, 16, false, mouseX, mouseY);
                // 只写配置、不重建当前面板：AUI 面板与原版面板的切换都靠「下次打开」生效，
                // 比热替换 Screen 实例简单可靠（热替换要处理 AUI 的实例替换与焦点/输入框状态）。
                host.hits.add(ux, rowY - 2, uw, 16, () -> {
                    String next = ReaderBridgeConfig.cycleUiMode();
                    host.toast("界面风格已切换为「" + ReaderBridgeConfig.uiModeLabel(next)
                            + "」，下次打开面板生效");
                });
                g.drawString(font, "晴雪UI 需要安装 apricityui；未安装时自动回退原版", cardX + 12, rowY + 16,
                        UiTheme.TEXT_FAINT, false);

                // -------------------------------------------------- HUD 风格
                // 与「界面风格」平行但独立：面板可以是原版、HUD 用晴雪UI，反之亦然。
                // 配置键是 [hud] hudMode（auto / apricity / vanilla），auto = 跟随上面那张
                // 「界面风格」最终落到的那一套（ReaderBridgeConfig#effectiveHudMode）。
                yy += gap;
                String hudMode = "auto";
                try {
                    hudMode = ReaderBridgeConfig.hudMode();
                } catch (Throwable ignored) {
                    // 配置未加载时按 auto 显示，设置页不崩
                }
                String hudLabel = ReaderBridgeConfig.hudModeLabel(hudMode);
                yy = card(g, font, cardX, yy, cardW, h6, "HUD 风格", hudLabel);
                rowY = yy - h6 + 24;
                g.drawString(font, "当前", cardX + 12, rowY, UiTheme.TEXT_DIM, false);
                g.drawString(font, hudLabel, cardX + 48, rowY, UiTheme.TEXT, false);
                // 与「界面风格」共用同一组 x/宽（uw / ux）：两张卡的按钮必须严格对齐，
                // 否则上下两行的「循环切换」会错开几个像素，看起来像没对齐的补丁。
                PanelUi.chip(g, font, "循环切换", ux, rowY - 2, uw, 16, false, mouseX, mouseY);
                // HUD 每帧都重新解析 effectiveHudMode()，所以这里不需要重建屏幕，改完立刻生效。
                host.hits.add(ux, rowY - 2, uw, 16, () -> {
                    String next = ReaderBridgeConfig.cycleHudMode();
                    host.toast("HUD 风格已切换为「" + ReaderBridgeConfig.hudModeLabel(next) + "」，即时生效");
                });
                g.drawString(font, "跟随界面 = 与「界面风格」一致；晴雪UI 未安装时回退原版", cardX + 12,
                        rowY + 16, UiTheme.TEXT_FAINT, false);

                // -------------------------------------------------- HUD 缩放
                // 与「HUD 风格」同构，但作用对象不同：只放大晴雪UI 那套 CSS 渲染的 HUD。
                // 原版 HUD 走 Minecraft 自带字形、已经受游戏 GUI 缩放影响，再乘系数会糊成一片
                // （见 ReaderBridgeConfig.HUD_SCALE 的 javadoc）。键名 [hud] hudScale，范围 1.0~6.0。
                yy += gap;
                double hudScale = 3.0D;
                try {
                    hudScale = ReaderBridgeConfig.hudScale();
                } catch (Throwable ignored) {
                    // 配置未加载时按默认 3.0 显示，设置页不崩
                }
                String scaleLabel = ReaderBridgeConfig.hudScaleLabel(hudScale);
                yy = card(g, font, cardX, yy, cardW, h7, "HUD 缩放", scaleLabel);
                rowY = yy - h7 + 24;
                g.drawString(font, "当前", cardX + 12, rowY, UiTheme.TEXT_DIM, false);
                g.drawString(font, scaleLabel, cardX + 48, rowY, UiTheme.TEXT, false);
                // 与上面两张卡共用同一组 x/宽（uw / ux），三个「循环切换」按钮严格对齐。
                PanelUi.chip(g, font, "循环切换", ux, rowY - 2, uw, 16, false, mouseX, mouseY);
                // AuiHud 每帧按 hudScale() 重算尺寸（缓存键是 viewport × 系数），所以改完立刻生效。
                host.hits.add(ux, rowY - 2, uw, 16, () -> {
                    double next = ReaderBridgeConfig.cycleHudScale();
                    host.toast("HUD 缩放已设为 " + ReaderBridgeConfig.hudScaleLabel(next) + "，即时生效");
                });
                g.drawString(font, "只作用于晴雪UI 的 HUD；原版 HUD 用 Minecraft 字形不放大", cardX + 12,
                        rowY + 16, UiTheme.TEXT_FAINT, false);

                // -------------------------------------------------- 诊断
                yy += gap;
                yy = card(g, font, cardX, yy, cardW, h4, "当前会话", "调试信息");
                rowY = yy - h4 + 24;
                String bookId = safe(e.bookId());
                String[] rows = {
                        "书 ID：" + (bookId.isEmpty() ? "-" : bookId),
                        "章节：" + (e.chapters().isEmpty() ? "-"
                                : ((Math.max(0, e.chapterIndex()) + 1) + "/" + e.chapters().size())),
                        "句子：" + (Math.max(0, e.sentenceIndex()) + "/" + e.sentenceCount()),
                        "状态：" + (safe(e.statusLine()).isEmpty()
                                ? (safe(e.errorLine()).isEmpty() ? "就绪" : safe(e.errorLine()))
                                : safe(e.statusLine())),
                };
                for (String row : rows) {
                    g.drawString(font, PanelUi.ellipsis(font, row, cardW - 18), cardX + 12, rowY,
                            UiTheme.TEXT_FAINT, false);
                    rowY += 14;
                }
            } finally {
                g.disableScissor();
            }

            PanelUi.scrollBar(g, x + w - 4, y, h, h, totalH, host.settingsScroll);
        }

        /** 画一张卡片底 + 标题行，返回卡片底部的 y。 */
        private int card(GuiGraphics g, Font font, int x, int y, int w, int h,
                         String title, String right) {
            if (h <= 0) {
                return y;
            }
            UiTheme.fillRounded(g, x, y, w, h, UiTheme.RADIUS_CARD, UiTheme.CARD_SOFT);
            g.drawString(font, title, x + 10, y + 8, UiTheme.TEXT, false);
            if (right != null && !right.isEmpty()) {
                PanelUi.textRight(g, font, right, x + w - 10, y + 8, UiTheme.TEXT_FAINT,
                        Math.max(20, w / 2));
            }
            g.fill(x + 10, y + 20, x + w - 10, y + 21, UiTheme.STROKE);
            return y + h;
        }

        @Override
        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            host.settingsScroll -= (int) Math.round(delta * 2.0 * 10.0);
            if (host.settingsScroll < 0) {
                host.settingsScroll = 0;
            }
            return true;
        }
    }
}
