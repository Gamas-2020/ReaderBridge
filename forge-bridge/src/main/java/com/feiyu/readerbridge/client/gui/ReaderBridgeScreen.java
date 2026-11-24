package com.feiyu.readerbridge.client.gui;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.api.ReaderBridgeApi;
import com.feiyu.readerbridge.api.ReaderBridgeProvider;
import com.feiyu.readerbridge.api.ReadingState;
import com.feiyu.readerbridge.client.ClientHudState;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.client.http.ReaderApiClient;
import com.feiyu.readerbridge.client.ui.HudPositionScreen;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 阅读面板：照 MuoniumPlayer 的「封面流」设计语言重做（不设按钮阵列）。
 *
 * <pre>
 * ┌───────────────────────────────────────────────┐
 * │ ┌────┐《书名》                       ● 已连接  │  ← 封面 + 书名 + 状态
 * │ │封面│ 作者 · 第三章 设酒杀鸡 · 12/87 章       │
 * │ └────┘                                       │
 * ├────┬──────────────────────────────────────────┤
 * │书  │ 中部列表：书架 / 章节 + 正文预览（可滚动）│
 * │章  │ 悬停淡白高亮、选中左侧强调条              │
 * │设  │                                          │
 * ├────┴──────────────────────────────────────────┤
 * │  ⏮   ▶   ⏭   ━━━━━━━━━━━━━━━━━━━━━  44% 已暂停 │  ← 播放条 + 进度
 * └───────────────────────────────────────────────┘
 * </pre>
 *
 * <p>视觉层全部是 Minecraft 原生 {@code GuiGraphics.fill/drawString}，圆角由
 * {@link UiTheme} 逐行近似；层次语言（全屏半透明遮罩、悬停白 0.2、条目间距）
 * 对齐参考项目 {@code CoverflowOverlay}。没有任何 {@code Button}：所有点击区
 * 都是自绘 + 命中测试。</p>
 */
public class ReaderBridgeScreen extends Screen {

    private enum View {
        BOOKS, CHAPTERS, SETTINGS
    }

    // ---------------------------------------------------------------- 几何
    private static final int HEADER_H = 56;
    private static final int PLAYBAR_H = 34;
    private static final int RAIL_W = 26;
    private static final int ROW_H = 22;
    private static final int LINE_H = 10;

    // ---------------------------------------------------------------- 状态
    private final Screen parent;
    private View view = View.BOOKS;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;
    private int railX;

    private List<ReaderApiClient.BookInfo> books = List.of();
    private List<ReaderApiClient.ChapterInfo> chapters = List.of();
    private List<String> previewLines = new ArrayList<>();
    private String selectedBookId;
    private String selectedBookTitle;
    private String selectedBookAuthor;
    private int selectedChapterIndex = -1;
    private int hoveredRow = -1;
    private int scrollOff;
    private String currentVoice = "";
    private List<ReaderApiClient.VoiceInfo> voices = List.of();
    private String statusMessage = "";
    private boolean loading;

    private EditBox searchBox;
    private EditBox portBox;
    private EditBox intervalBox;

    /** 播放条三键 + 关闭 + 侧栏 + 状态点的命中区（[x, y, w, h]）。 */
    private final int[] hitPrev = new int[4];
    private final int[] hitPlay = new int[4];
    private final int[] hitNext = new int[4];
    private final int[] hitStop = new int[4];
    private final int[] hitClose = new int[4];
    private final int[] hitStatus = new int[4];
    private final int[] hitRailBooks = new int[4];
    private final int[] hitRailChapters = new int[4];
    private final int[] hitRailSettings = new int[4];

    public ReaderBridgeScreen() {
        this(null);
    }

    public ReaderBridgeScreen(Screen parent) {
        super(Component.literal("阅读器"));
        this.parent = parent;
    }

    // ---------------------------------------------------------------- 生命周期
    @Override
    protected void init() {
        layout();

        searchBox = new EditBox(this.font, contentX + 2, contentY + 2, Math.max(40, contentW - 8), 14,
                Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("搜书名，回车搜索"));
        searchBox.setVisible(view == View.BOOKS);
        addRenderableWidget(searchBox);

        portBox = new EditBox(this.font, contentX + 52, contentY + 3, 44, 14, Component.literal("端口"));
        portBox.setMaxLength(5);
        portBox.setValue(String.valueOf(ReaderBridgeConfig.wsPort()));
        portBox.setVisible(view == View.SETTINGS);
        addRenderableWidget(portBox);

        intervalBox = new EditBox(this.font, contentX + 52, contentY + 25, 58, 14, Component.literal("重连间隔"));
        intervalBox.setMaxLength(6);
        intervalBox.setValue(String.valueOf(ReaderBridgeConfig.reconnectIntervalMs()));
        intervalBox.setVisible(view == View.SETTINGS);
        addRenderableWidget(intervalBox);

        // 首次进入异步拉取（回调经 MainThreadDispatcher 回主线程）
        reload();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 全屏半透明遮罩（CoverflowOverlay:207 同款层次）
        graphics.fill(0, 0, this.width, this.height, UiTheme.SCRIM);

        layout();
        hoveredRow = -1;

        UiTheme.fillRounded(graphics, panelX, panelY, panelW, panelH, UiTheme.RADIUS_PANEL, UiTheme.PANEL);
        UiTheme.strokeRounded(graphics, panelX, panelY, panelW, panelH, UiTheme.RADIUS_PANEL, UiTheme.STROKE);

        renderHeader(graphics);
        renderRail(graphics, mouseX, mouseY);
        switch (view) {
            case BOOKS -> renderBookList(graphics, mouseX, mouseY);
            case CHAPTERS -> renderChapters(graphics, mouseX, mouseY);
            case SETTINGS -> renderSettings(graphics, mouseX, mouseY);
        }
        renderPlayBar(graphics, mouseX, mouseY);

        // 顶部搜索/端口输入框由 Screen 统一绘制（必须在自绘之后）
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 不要原版泥土/模糊背景：我们自带遮罩层
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ---------------------------------------------------------------- 布局
    private void layout() {
        panelW = Math.max(300, Math.min(430, this.width - 40));
        // 最小高度 236 而不是 220：设置页改成 8 行（音量/界面风格/诊断各占一行）后
        // contentH = panelH - 56(HEADER) - 34(PLAYBAR) - 4，236 时 contentH=142，
        // 刚好容下最后一行诊断（cy+128 + 9px 字高）；低于它会被播放条压住。
        panelH = Math.max(236, Math.min(280, this.height - 40));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        railX = panelX + 6;
        contentX = panelX + RAIL_W + 10;
        contentY = panelY + HEADER_H;
        contentW = panelX + panelW - 8 - contentX;
        contentH = panelY + panelH - PLAYBAR_H - 4 - contentY;

        int barY = panelY + panelH - PLAYBAR_H;
        int cx = panelX + panelW / 2;
        set(hitPrev, cx - 48, barY + 4, 18, 18);
        set(hitPlay, cx - 9, barY + 2, 20, 20);
        set(hitNext, cx + 30, barY + 4, 18, 18);
        set(hitStop, cx + 54, barY + 6, 14, 14);
        set(hitClose, panelX + panelW - 20, panelY + 6, 14, 14);
        set(hitStatus, panelX + panelW - 102, panelY + 6, 78, 12);
        set(hitRailBooks, railX + 4, panelY + HEADER_H + 2, 18, 18);
        set(hitRailChapters, railX + 4, panelY + HEADER_H + 24, 18, 18);
        set(hitRailSettings, railX + 4, panelY + HEADER_H + 46, 18, 18);
    }

    private static void set(int[] box, int x, int y, int w, int h) {
        box[0] = x;
        box[1] = y;
        box[2] = w;
        box[3] = h;
    }

    private static boolean in(int[] box, double mx, double my) {
        return mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3];
    }

    // ---------------------------------------------------------------- 头部
    private void renderHeader(GuiGraphics graphics) {
        int cover = 40;
        int coverX = panelX + 10;
        int coverY = panelY + 8;
        int tint = coverTint(selectedBookTitle != null ? selectedBookTitle : stateTitle());
        UiTheme.fillRounded(graphics, coverX, coverY, cover, cover, UiTheme.RADIUS_CARD, tint);
        // 封面上的首字（无封面图时保持视觉重量）
        String glyph = firstGlyph(selectedBookTitle != null ? selectedBookTitle : stateTitle());
        int gw = this.font.width(glyph);
        graphics.drawString(this.font, glyph, coverX + (cover - gw) / 2, coverY + cover / 2 - 4,
                0xFFFFFFFF, true);

        int textX = coverX + cover + 8;
        String title = selectedBookTitle != null ? selectedBookTitle : orDash(stateTitle());
        graphics.drawString(this.font, trimTo(title, this.font, contentW - 40), textX, panelY + 12,
                UiTheme.TEXT, true);

        ReadingState state = currentState();
        StringBuilder sub = new StringBuilder();
        if (selectedBookAuthor != null && !selectedBookAuthor.isEmpty()) {
            sub.append(selectedBookAuthor).append(" · ");
        }
        if (state != null && state.chapterTitle() != null && !state.chapterTitle().isEmpty()) {
            sub.append(state.chapterTitle());
        } else if (!chapters.isEmpty()) {
            sub.append("共 ").append(chapters.size()).append(" 章");
        } else {
            sub.append("未开始朗读");
        }
        graphics.drawString(this.font, trimTo(sub.toString(), this.font, contentW - 40), textX,
                panelY + 24, UiTheme.TEXT_DIM, false);

        // 连接三态：已连接(绿) / 重连中·第N次(黄) / App 未启动(红)；点击 = 重试
        boolean connected = isConnected();
        int attempts = connectionAttempts();
        int dotColor;
        String status;
        int statusColor;
        if (connected) {
            dotColor = loading ? UiTheme.ACCENT : UiTheme.OK;
            status = loading ? "同步中" : "已连接";
            statusColor = UiTheme.TEXT_DIM;
        } else if (attempts > 0) {
            dotColor = 0xFFFFC53D;
            status = "重连中·第" + attempts + "次";
            statusColor = 0xFFFFC53D;
        } else {
            dotColor = UiTheme.ERROR;
            status = "App 未启动";
            statusColor = UiTheme.ERROR;
        }
        graphics.fill(hitStatus[0], panelY + 8, hitStatus[0] + 5, panelY + 13, dotColor);
        graphics.drawString(this.font, status, hitStatus[0] + 8, panelY + 8, statusColor, false);

        // 关闭（× 用文本）
        graphics.drawString(this.font, "x", hitClose[0] + 3, hitClose[1] - 1,
                in(hitClose, lastMouseX(), lastMouseY()) ? UiTheme.TEXT : UiTheme.TEXT_DIM, false);

        if (statusMessage != null && !statusMessage.isEmpty()) {
            graphics.drawString(this.font, trimTo(statusMessage, this.font, contentW), textX,
                    panelY + 38, UiTheme.ACCENT, false);
        }
    }

    /** 侧栏：三个极简图标入口（文字字形 + 选中态强调条）。 */
    private void renderRail(GuiGraphics graphics, int mouseX, int mouseY) {
        drawRailItem(graphics, hitRailBooks, "书", view == View.BOOKS, mouseX, mouseY);
        drawRailItem(graphics, hitRailChapters, "章", view == View.CHAPTERS, mouseX, mouseY);
        drawRailItem(graphics, hitRailSettings, "设", view == View.SETTINGS, mouseX, mouseY);
    }

    private void drawRailItem(GuiGraphics graphics, int[] box, String label, boolean active,
                              int mouseX, int mouseY) {
        boolean hovered = in(box, mouseX, mouseY);
        if (hovered || active) {
            UiTheme.fillRounded(graphics, box[0], box[1], box[2], box[3], UiTheme.RADIUS_CARD,
                    active ? UiTheme.SELECTED : UiTheme.HOVER);
        }
        if (active) {
            graphics.fill(box[0] - 3, box[1] + 3, box[0] - 1, box[1] + box[3] - 3, UiTheme.ACCENT);
        }
        int color = active ? UiTheme.TEXT : (hovered ? UiTheme.TEXT : UiTheme.TEXT_DIM);
        graphics.drawString(this.font, label, box[0] + (box[2] - this.font.width(label)) / 2,
                box[1] + 5, color, false);
    }

    // ---------------------------------------------------------------- 列表：书架
    private void renderBookList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listTop = contentY + 20;
        int listH = contentH - 20;
        if (books.isEmpty()) {
            drawEmpty(graphics, listTop, isConnected() ? "书架为空：在主菜单或 App 里添加书源" : "App 未连接");
            return;
        }
        scrollOff = clampScroll(scrollOff, books.size(), listH);
        int first = scrollOff / ROW_H;
        int visible = listH / ROW_H + 1;
        for (int i = first; i < Math.min(books.size(), first + visible); i++) {
            ReaderApiClient.BookInfo book = books.get(i);
            int rowY = listTop + i * ROW_H - scrollOff;
            boolean selected = book.id() != null && book.id().equals(selectedBookId);
            drawRow(graphics, rowY, contentX, contentW, mouseX, mouseY, selected,
                    book.title(), book.author() == null ? "" : book.author(),
                    book.totalChapters() + " 章", i);
        }
        drawScrollHint(graphics, listTop, listH, books.size());
    }

    // ---------------------------------------------------------------- 列表：章节 + 预览
    private void renderChapters(GuiGraphics graphics, int mouseX, int mouseY) {
        if (chapters.isEmpty()) {
            drawEmpty(graphics, contentY + 2, selectedBookId == null
                    ? "先在「书」里选一本书" : (isConnected() ? "（没有章节）" : "App 未连接"));
            return;
        }
        int listH = Math.max(30, (int) (contentH * 0.52));
        scrollOff = clampScroll(scrollOff, chapters.size(), listH);
        int first = scrollOff / ROW_H;
        int visible = listH / ROW_H + 1;
        for (int i = first; i < Math.min(chapters.size(), first + visible); i++) {
            ReaderApiClient.ChapterInfo chapter = chapters.get(i);
            int rowY = contentY + i * ROW_H - scrollOff;
            boolean selected = chapter.index() == selectedChapterIndex;
            drawRow(graphics, rowY, contentX, contentW, mouseX, mouseY, selected,
                    "#" + (chapter.index() + 1) + " " + chapter.title(), "",
                    chapter.wordCount() + " 字", i);
        }

        int prevTop = contentY + listH + 4;
        UiTheme.fillRounded(graphics, contentX, prevTop, contentW,
                Math.max(10, contentY + contentH - prevTop), UiTheme.RADIUS_CARD, UiTheme.CARD);
        int lineY = prevTop + 4;
        int maxLines = Math.max(1, (contentY + contentH - prevTop - 8) / LINE_H);
        for (int i = 0; i < previewLines.size() && i < maxLines; i++) {
            graphics.drawString(this.font, previewLines.get(i), contentX + 4, lineY, UiTheme.TEXT, false);
            lineY += LINE_H;
        }
    }

    // ---------------------------------------------------------------- 设置
    private void renderSettings(GuiGraphics graphics, int mouseX, int mouseY) {
        int cy = contentY + 4;

        // 行 1：端口（EditBox 由 Screen 绘制，这里画标签与「保存」命中区）
        graphics.drawString(this.font, "端口", contentX, cy + 3, UiTheme.TEXT, false);
        UiTheme.fillRounded(graphics, contentX + 104, cy, 26, 14, UiTheme.RADIUS_CARD, UiTheme.CARD);
        graphics.drawString(this.font, "保存", contentX + 108, cy + 3, UiTheme.TEXT_DIM, false);

        // 行 2：重连间隔
        graphics.drawString(this.font, "重连", contentX, cy + 25, UiTheme.TEXT, false);
        UiTheme.fillRounded(graphics, contentX + 118, cy + 22, 26, 14, UiTheme.RADIUS_CARD, UiTheme.CARD);
        graphics.drawString(this.font, "保存", contentX + 122, cy + 25, UiTheme.TEXT_DIM, false);
        graphics.drawString(this.font, "ms (500~60000)", contentX + 150, cy + 25, UiTheme.TEXT_FAINT, false);

        // 行 3：两个开关
        drawToggle(graphics, contentX, cy + 44, "HUD", ClientHudState.visible());
        drawToggle(graphics, contentX + 130, cy + 44, "世界文字", ReaderBridgeConfig.showWorldText());

        // 行 4：HUD 位置
        graphics.drawString(this.font, "HUD 位置", contentX, cy + 70, UiTheme.TEXT, false);
        UiTheme.fillRounded(graphics, contentX + 70, cy + 67, 88, 14, UiTheme.RADIUS_CARD, UiTheme.CARD);
        graphics.drawString(this.font, "点击调整", contentX + 78, cy + 70, UiTheme.TEXT_DIM, false);

        // 行 5：音色（点击轮换）
        ReadingState state = currentState();
        String voice = state != null && state.ttsVoiceId() != null && !state.ttsVoiceId().isEmpty()
                ? state.ttsVoiceId() : currentVoice;
        graphics.drawString(this.font, "音色：" + (voice.isEmpty() ? "（未知）" : trimTo(voice, this.font, contentW - 70))
                + (voices.size() > 1 ? "  [点击切换]" : ""), contentX, cy + 92, UiTheme.TEXT_DIM, false);

        // 行 6：朗读音量（TTS 专属，独立于原版主音量/音乐/语音）
        // 最省事的入口：整行可点，每次循环 −20%，范围 0%~400%（0 再往前回到 400）。
        // 上限从 100% 放宽到 400%：AL_GAIN > 1.0 是放大而非限制，超过约 200% 后母带已录满、
        // 多数音频会削波失真 —— 用户主动选择，不是 bug，所以不另设安全上限、不弹警告。
        // 没装晴雪UI（AUI 面板不可用）时，这里是唯一的音量入口。
        int volPct = (int) Math.round(ReaderEngine.get().volume() * 100.0D);
        graphics.drawString(this.font, "音量", contentX, cy + 104, UiTheme.TEXT, false);
        UiTheme.fillRounded(graphics, contentX + 34, cy + 101, 34, 14, UiTheme.RADIUS_CARD, UiTheme.CARD);
        graphics.drawString(this.font, volPct + "%", contentX + 40, cy + 104, UiTheme.TEXT_DIM, false);
        graphics.drawString(this.font, "点击循环 -20%", contentX + 76, cy + 104, UiTheme.TEXT_FAINT, false);

        // 行 7：界面风格（晴雪UI / 原版 / 自动循环）。两套界面都必须能切走/切回来，
        // 否则选了 vanilla 就再也进不了 AUI 设置页，等于死锁。
        // 只写配置不重建面板：切换在「下次打开面板」生效，避免热替换 Screen 带来的焦点问题。
        String uiMode = "auto";
        try {
            uiMode = ReaderBridgeConfig.uiMode();
        } catch (Throwable ignored) {
            // 配置未加载时按 auto 显示
        }
        graphics.drawString(this.font, "界面", contentX, cy + 116, UiTheme.TEXT, false);
        UiTheme.fillRounded(graphics, contentX + 34, cy + 113, 52, 14, UiTheme.RADIUS_CARD, UiTheme.CARD);
        graphics.drawString(this.font, ReaderBridgeConfig.uiModeLabel(uiMode), contentX + 40, cy + 116,
                UiTheme.TEXT_DIM, false);
        graphics.drawString(this.font, "点击循环", contentX + 90, cy + 116, UiTheme.TEXT_FAINT, false);

        // 行 8：诊断 + 热键合并成一行
        graphics.drawString(this.font, "心跳 " + ReaderBridgeConfig.heartbeatIntervalMs() + "ms · 尝试 "
                + connectionAttempts() + " 次 · M G B N J H", contentX, cy + 128, UiTheme.TEXT_FAINT, false);
    }

    private void drawToggle(GuiGraphics graphics, int x, int y, String label, boolean on) {
        graphics.drawString(this.font, label, x, y + 2, UiTheme.TEXT, false);
        int bx = x + this.font.width(label) + 6;
        UiTheme.fillRounded(graphics, bx, y, 24, 12, 6, on ? UiTheme.ACCENT : 0x55FFFFFF);
        int knob = bx + (on ? 13 : 1);
        UiTheme.fillCapsule(graphics, knob, y + 1, 10, 10, 0xFFFFFFFF);
    }

    // ---------------------------------------------------------------- 播放条
    private void renderPlayBar(GuiGraphics graphics, int mouseX, int mouseY) {
        int barY = panelY + panelH - PLAYBAR_H;
        UiTheme.fillRounded(graphics, panelX + 2, barY, panelW - 4, PLAYBAR_H - 2, UiTheme.RADIUS_CARD, 0x22FFFFFF);

        ReadingState state = currentState();
        double progress = state == null ? 0.0 : state.progress();
        boolean playing = state != null && state.isActive() && !state.isPaused();

        // 上一章 / 播放暂停 / 下一章：几何图标
        drawGlyph(graphics, hitPrev, mouseX, mouseY, "«");
        drawGlyph(graphics, hitNext, mouseX, mouseY, "»");
        boolean hoverStop = in(hitStop, mouseX, mouseY);
        int stopColor = hoverStop ? UiTheme.TEXT : UiTheme.TEXT_DIM;
        graphics.fill(hitStop[0] + 3, hitStop[1] + 3, hitStop[0] + 11, hitStop[1] + 11, stopColor);
        boolean hoverPlay = in(hitPlay, mouseX, mouseY);
        int playColor = hoverPlay ? UiTheme.TEXT : UiTheme.TEXT_DIM;
        if (playing) {
            int px = hitPlay[0] + 6;
            int py = hitPlay[1] + 4;
            graphics.fill(px, py, px + 3, py + 12, playColor);
            graphics.fill(px + 6, py, px + 9, py + 12, playColor);
        } else {
            int px = hitPlay[0] + 5;
            int py = hitPlay[1] + 3;
            for (int i = 0; i < 11; i++) {
                graphics.fill(px + i, py + i / 2, px + i + 1, py + 12 - i / 2, playColor);
            }
        }

        int barX = hitPlay[0] + 30;
        int barW = Math.max(20, hitNext[0] - 8 - barX);
        UiTheme.progressBar(graphics, barX, barY + 14, barW, 4, progress, 0x33FFFFFF, UiTheme.ACCENT);
        if (state != null && progress > 0.001) {
            int dot = barX + (int) Math.round(barW * Math.max(0, Math.min(1, progress)));
            UiTheme.fillCapsule(graphics, dot - 2, barY + 12, 5, 5, 0xFFFFFFFF);
        }

        String pct = String.format("%.0f%%", progress * 100.0);
        String stateText = !isConnected() ? "未连接"
                : (state == null || !state.isActive() ? "空闲" : (state.isPaused() ? "已暂停" : "朗读中"));
        graphics.drawString(this.font, pct + " " + stateText, panelX + panelW - 8 - this.font.width(pct + " " + stateText),
                barY + 5, UiTheme.TEXT_DIM, false);

        // 当前句：播放条上方一行（书名/章节已在头部，这里只放句）
        if (state != null && state.currentSentence() != null && !state.currentSentence().isEmpty()) {
            graphics.drawString(this.font,
                    trimTo(state.currentSentence(), this.font, panelW - 20),
                    panelX + 10, barY - LINE_H - 2, UiTheme.TEXT, false);
        }
    }

    private void drawGlyph(GuiGraphics graphics, int[] box, int mouseX, int mouseY, String glyph) {
        int color = in(box, mouseX, mouseY) ? UiTheme.TEXT : UiTheme.TEXT_DIM;
        graphics.drawString(this.font, glyph, box[0] + (box[2] - this.font.width(glyph)) / 2, box[1] + 4,
                color, false);
    }

    // ---------------------------------------------------------------- 列表绘制
    private void drawRow(GuiGraphics graphics, int rowY, int x, int w, int mouseX, int mouseY,
                         boolean selected, String primary, String secondary, String trailing, int index) {
        boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_H && mouseX >= x && mouseX < x + w;
        if (hovered) {
            hoveredRow = index;
        }
        if (hovered) {
            UiTheme.fillRounded(graphics, x, rowY + 1, w, ROW_H - 2, UiTheme.RADIUS_CARD, UiTheme.HOVER);
        } else if (selected) {
            UiTheme.fillRounded(graphics, x, rowY + 1, w, ROW_H - 2, UiTheme.RADIUS_CARD, UiTheme.SELECTED);
        }
        if (selected) {
            graphics.fill(x, rowY + 4, x + 2, rowY + ROW_H - 4, UiTheme.ACCENT);
        }
        int trailingW = trailing == null || trailing.isEmpty() ? 0 : this.font.width(trailing) + 8;
        String name = trimTo(primary == null ? "" : primary, this.font, w - trailingW - 12);
        graphics.drawString(this.font, name, x + 6, rowY + 3, selected || hovered ? UiTheme.TEXT : 0xFFE8EAED, false);
        if (secondary != null && !secondary.isEmpty()) {
            String sub = trimTo(secondary, this.font, w - trailingW - 12);
            graphics.drawString(this.font, sub, x + 6, rowY + 12, UiTheme.TEXT_FAINT, false);
        }
        if (trailingW > 0) {
            graphics.drawString(this.font, trailing, x + w - trailingW + 2, rowY + 7, UiTheme.TEXT_FAINT, false);
        }
    }

    private void drawEmpty(GuiGraphics graphics, int y, String text) {
        graphics.drawString(this.font, trimTo(text, this.font, contentW), contentX + 4, y + 8,
                isConnected() ? UiTheme.TEXT_DIM : UiTheme.ERROR, false);
    }

    private void drawScrollHint(GuiGraphics graphics, int top, int height, int total) {
        int rows = Math.max(1, height / ROW_H);
        if (total <= rows) {
            return;
        }
        int trackX = panelX + panelW - 6;
        int trackH = height;
        UiTheme.fillCapsule(graphics, trackX, top, 2, trackH, 0x22FFFFFF);
        int thumbH = Math.max(12, trackH * rows / total);
        int maxOff = Math.max(1, total * ROW_H - height);
        int thumbY = top + (trackH - thumbH) * scrollOff / maxOff;
        UiTheme.fillCapsule(graphics, trackX, thumbY, 2, thumbH, 0x88FFFFFF);
    }

    // ---------------------------------------------------------------- 交互
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (in(hitClose, mouseX, mouseY)) {
            onClose();
            return true;
        }
        if (in(hitStatus, mouseX, mouseY) && !isConnected()) {
            retry();
            return true;
        }
        if (in(hitRailBooks, mouseX, mouseY)) {
            switchView(View.BOOKS);
            return true;
        }
        if (in(hitRailChapters, mouseX, mouseY)) {
            switchView(View.CHAPTERS);
            return true;
        }
        if (in(hitRailSettings, mouseX, mouseY)) {
            switchView(View.SETTINGS);
            return true;
        }
        if (in(hitPlay, mouseX, mouseY)) {
            togglePlayPause();
            return true;
        }
        if (in(hitPrev, mouseX, mouseY)) {
            readerAction("prev");
            return true;
        }
        if (in(hitNext, mouseX, mouseY)) {
            readerAction("next");
            return true;
        }
        if (in(hitStop, mouseX, mouseY)) {
            readerAction("stop");
            return true;
        }

        // 设置视图的命中区（与 renderSettings 的行高一一对应）
        if (view == View.SETTINGS) {
            int cy = contentY + 4;
            if (mouseY >= cy && mouseY < cy + 14) {
                if (mouseX >= contentX + 104 && mouseX < contentX + 130) {
                    applyPort();
                    return true;
                }
            }
            if (mouseY >= cy + 22 && mouseY < cy + 36) {
                if (mouseX >= contentX + 118 && mouseX < contentX + 144) {
                    applyInterval();
                    return true;
                }
            }
            if (mouseY >= cy + 44 && mouseY < cy + 58) {
                if (mouseX >= contentX && mouseX < contentX + 128) {
                    ClientHudState.toggle();
                    return true;
                }
                if (mouseX >= contentX + 130 && mouseX < contentX + 260) {
                    ReaderBridgeConfig.setShowWorldText(!ReaderBridgeConfig.showWorldText());
                    return true;
                }
            }
            if (mouseY >= cy + 67 && mouseY < cy + 83
                    && mouseX >= contentX + 70 && mouseX < contentX + 158) {
                if (this.minecraft != null) {
                    this.minecraft.setScreen(new HudPositionScreen(this));
                }
                return true;
            }
            if (mouseY >= cy + 90 && mouseY < cy + 102
                    && mouseX >= contentX && mouseX < contentX + contentW) {
                cycleVoice();
                return true;
            }
            // 音量行（与 renderSettings 的「行 6」一一对应）：点整行循环 −20%，范围 0~400%
            // 上界写 cy+113（不是 +116）：界面风格行从 cy+114 开始，留 1px 缝，
            // 否则两行的命中区重叠、上边那 1~2px 永远被音量行吃掉。
            if (mouseY >= cy + 102 && mouseY < cy + 113
                    && mouseX >= contentX && mouseX < contentX + contentW) {
                int pct = (int) Math.round(ReaderEngine.get().volume() * 100.0D) - 20;
                if (pct < 0) {
                    // 0 再往前回到上限：400% 必须可达，否则永远只能往下调
                    pct = 400;
                }
                ReaderEngine.get().setVolume(pct / 100.0D);
                return true;
            }
            // 界面风格行（与 renderSettings 的「行 7」一一对应）：点击循环 晴雪UI → 原版 → 自动
            if (mouseY >= cy + 114 && mouseY < cy + 128
                    && mouseX >= contentX && mouseX < contentX + contentW) {
                String next = ReaderBridgeConfig.cycleUiMode();
                statusMessage = "界面风格已切换为「" + ReaderBridgeConfig.uiModeLabel(next) + "」，下次打开面板生效";
                return true;
            }
        }

        // 列表行
        if (view == View.BOOKS) {
            int index = rowAt(mouseY, contentY + 20, contentH - 20, books.size());
            if (index >= 0) {
                onBookClicked(index);
                return true;
            }
        } else if (view == View.CHAPTERS && !chapters.isEmpty()) {
            int listH = Math.max(30, (int) (contentH * 0.52));
            int index = rowAt(mouseY, contentY, listH, chapters.size());
            if (index >= 0) {
                onChapterClicked(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中列表行（考虑滚动偏移）。 */
    private int rowAt(double mouseY, int top, int height, int count) {
        if (mouseY < top || mouseY >= top + height || count == 0) {
            return -1;
        }
        int index = (int) ((mouseY - top + scrollOff) / ROW_H);
        return index >= 0 && index < count ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (view == View.BOOKS) {
            scrollOff = clampScroll(scrollOff - (int) (delta * ROW_H * 2), books.size(), contentH - 20);
            return true;
        }
        if (view == View.CHAPTERS) {
            int listH = Math.max(30, (int) (contentH * 0.52));
            scrollOff = clampScroll(scrollOff - (int) (delta * ROW_H * 2), chapters.size(), listH);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.visible && searchBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                doSearch();
                return true;
            }
        }
        if (searchBox != null && searchBox.visible && !searchBox.isFocused()) {
            if (keyCode == 82) { // R = 刷新
                reload();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- 数据
    private void reload() {
        loading = true;
        ReaderApiClient.INSTANCE.fetchBooks(new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(List<ReaderApiClient.BookInfo> value) {
                books = value == null ? List.of() : value;
                loading = false;
                statusMessage = null;
                scrollOff = 0;
            }

            @Override
            public void onFailure(Throwable error) {
                loading = false;
                statusMessage = "读取书架失败：" + shortError(error);
            }
        });
        ReaderApiClient.INSTANCE.fetchVoices(new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(ReaderApiClient.VoicesResult value) {
                currentVoice = value == null || value.current() == null ? "" : value.current();
                voices = value == null || value.voices() == null ? List.of() : value.voices();
            }
        });
    }

    private void switchView(View target) {
        view = target;
        scrollOff = 0;
        if (searchBox != null) {
            searchBox.setVisible(view == View.BOOKS);
            if (view != View.BOOKS) {
                searchBox.setFocused(false);
            }
        }
        if (portBox != null) {
            portBox.setVisible(view == View.SETTINGS);
            portBox.setFocused(false);
        }
        if (intervalBox != null) {
            intervalBox.setVisible(view == View.SETTINGS);
            intervalBox.setFocused(false);
        }
        if (view == View.BOOKS && books.isEmpty()) {
            reload();
        }
    }

    private void onBookClicked(int index) {
        if (index < 0 || index >= books.size()) {
            return;
        }
        ReaderApiClient.BookInfo book = books.get(index);
        selectedBookId = book.id();
        selectedBookTitle = book.title();
        selectedBookAuthor = book.author();
        selectedChapterIndex = -1;
        chapters = List.of();
        previewLines.clear();
        switchView(View.CHAPTERS);
        ReaderApiClient.INSTANCE.fetchChapters(book.id(), new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(List<ReaderApiClient.ChapterInfo> value) {
                chapters = value == null ? List.of() : value;
                statusMessage = null;
            }

            @Override
            public void onFailure(Throwable error) {
                statusMessage = "读取章节失败：" + shortError(error);
            }
        });
    }

    private void onChapterClicked(int index) {
        if (index < 0 || index >= chapters.size()) {
            return;
        }
        ReaderApiClient.ChapterInfo chapter = chapters.get(index);
        selectedChapterIndex = chapter.index();
        // 单击 = 预览 + 打开该章并播放（与 GUIDE 描述一致）
        ReaderApiClient.INSTANCE.fetchChapterContent(selectedBookId, chapter.index(),
                new ReaderApiClient.Callback<>() {
                    @Override
                    public void onSuccess(ReaderApiClient.ChapterContent value) {
                        previewLines.clear();
                        if (value != null && value.paragraphs() != null) {
                            for (String paragraph : value.paragraphs()) {
                                if (paragraph == null) {
                                    continue;
                                }
                                previewLines.addAll(this.wrap(paragraph, contentW - 12));
                                if (previewLines.size() > 60) {
                                    break;
                                }
                            }
                        }
                    }

                    private List<String> wrap(String text, int maxWidth) {
                        List<String> out = new ArrayList<>();
                        String rest = text;
                        while (!rest.isEmpty() && out.size() < 40) {
                            String line = trimTo(rest, ReaderBridgeScreen.this.font, Math.max(20, maxWidth));
                            if (line.isEmpty()) {
                                break;
                            }
                            out.add(line);
                            rest = rest.substring(line.length());
                        }
                        return out;
                    }
                });
        ReaderApiClient.INSTANCE.openAndPlay(selectedBookId, chapter.index(),
                new ReaderApiClient.Callback<>() {
                    @Override
                    public void onSuccess(ReaderApiClient.ContentInfo value) {
                        statusMessage = "已打开：" + (value == null ? "" : orDash(value.chapterTitle()));
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        statusMessage = "打开失败：" + shortError(error);
                    }
                });
    }

    private void togglePlayPause() {
        ReadingState state = currentState();
        boolean playing = state != null && state.isActive() && !state.isPaused();
        readerAction(playing ? "pause" : "play");
    }

    private void readerAction(String action) {
        ReaderApiClient.INSTANCE.readerAction(action, new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(Void ignored) {
            }

            @Override
            public void onFailure(Throwable error) {
                statusMessage = action + " 失败：" + shortError(error);
            }
        });
    }

    private void retry() {
        statusMessage = "正在重试...";
        reload();
    }

    /** 轮换到下一个音色（POST /api/v1/reader/voice）。列表为空时不动。 */
    private void cycleVoice() {
        if (voices.isEmpty()) {
            statusMessage = "音色列表为空";
            return;
        }
        int current = -1;
        String active = currentVoice;
        ReadingState state = currentState();
        if (state != null && state.ttsVoiceId() != null && !state.ttsVoiceId().isEmpty()) {
            active = state.ttsVoiceId();
        }
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).id() != null && voices.get(i).id().equals(active)) {
                current = i;
                break;
            }
        }
        ReaderApiClient.VoiceInfo next = voices.get((current + 1) % voices.size());
        if (next.id() == null || next.id().isEmpty()) {
            statusMessage = "音色 id 为空";
            return;
        }
        ReaderApiClient.INSTANCE.setVoice(next.id(), new ReaderApiClient.Callback<>() {
            @Override
            public void onSuccess(Void ignored) {
                currentVoice = next.id();
                statusMessage = "音色已切到 " + next.name();
            }

            @Override
            public void onFailure(Throwable error) {
                statusMessage = "切换音色失败：" + shortError(error);
            }
        });
    }

    private void doSearch() {
        if (searchBox == null) {
            return;
        }
        String keyword = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
        if (keyword.isEmpty()) {
            reload();
            return;
        }
        loading = true;
        ReaderApiClient.INSTANCE.search("gushiwen-guwen", keyword,
                new ReaderApiClient.Callback<>() {
                    @Override
                    public void onSuccess(List<ReaderApiClient.SearchHit> value) {
                        loading = false;
                        List<ReaderApiClient.BookInfo> hits = new ArrayList<>();
                        if (value != null) {
                            for (ReaderApiClient.SearchHit hit : value) {
                                hits.add(new ReaderApiClient.BookInfo(
                                        hit.sourceId() + ":" + hit.bookKey(), hit.title(), hit.author(),
                                        hit.sourceId(), 0));
                            }
                        }
                        books = hits;
                        statusMessage = hits.isEmpty() ? "没有搜索结果" : ("搜索到 " + hits.size() + " 本");
                    }
                });
    }

    /** 写回重连间隔：clamp 由 API/配置层负责，这里只做输入解析。 */
    private void applyInterval() {
        if (intervalBox == null) {
            return;
        }
        try {
            long ms = Long.parseLong(intervalBox.getValue().trim());
            if (ms < 500 || ms > 60000) {
                statusMessage = "重连间隔需在 500~60000 ms";
                return;
            }
            ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
            if (api == null) {
                statusMessage = "API 未初始化";
                return;
            }
            api.setReconnectIntervalMs(ms);
            statusMessage = "重连间隔已设为 " + ms + " ms（已重连）";
        } catch (NumberFormatException e) {
            statusMessage = "重连间隔需为数字";
        } catch (Throwable t) {
            statusMessage = "重连间隔写入失败：" + shortError(t);
        }
    }

    /** 当前连接尝试次数（三态展示用）。 */
    private static int connectionAttempts() {
        ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
        return api == null ? 0 : api.connectionAttempts();
    }

    private void applyPort() {
        if (portBox == null) {
            return;
        }
        try {
            int port = Integer.parseInt(portBox.getValue().trim());
            if (port < 1024 || port > 65535) {
                statusMessage = "端口需在 1024~65535";
                return;
            }
            ReaderBridgeConfig.setWsPort(port);
            statusMessage = "端口已保存（重连中）";
        } catch (Throwable t) {
            statusMessage = "端口需为数字";
        }
    }

    // ---------------------------------------------------------------- 工具
    private ReadingState currentState() {
        ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
        return api == null ? null : api.getState();
    }

    private String stateTitle() {
        ReadingState state = currentState();
        return state == null ? "" : state.bookTitle();
    }

    private boolean isConnected() {
        ReaderBridgeApi api = ReaderBridgeProvider.getIfPresent().orElse(null);
        return api != null && api.isConnected();
    }

    private int lastMouseX() {
        return this.width / 2;
    }

    private int lastMouseY() {
        return this.height / 2;
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String trimTo(String text, net.minecraft.client.gui.Font font, int maxWidth) {
        if (text == null) {
            return "";
        }
        return font.plainSubstrByWidth(text, Math.max(10, maxWidth));
    }

    private static int clampScroll(int value, int count, int height) {
        int max = Math.max(0, count * ROW_H - height);
        return Math.max(0, Math.min(value, max));
    }

    /** 由书名生成一个稳定的封面底色（无封面图时的替代）。 */
    private static int coverTint(String title) {
        if (title == null || title.isEmpty()) {
            return 0xFF3A3A44;
        }
        int hash = title.hashCode();
        int r = 40 + Math.abs(hash & 0x3F);
        int g = 30 + Math.abs((hash >> 6) & 0x3F);
        int b = 60 + Math.abs((hash >> 12) & 0x5F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String firstGlyph(String title) {
        if (title == null || title.isBlank()) {
            return "书";
        }
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                return new String(Character.toChars(cp));
            }
            i += Character.charCount(cp);
        }
        return "书";
    }

    private static String shortError(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        if (error instanceof ReaderApiClient.HttpStatusException status) {
            return "HTTP " + status.status();
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 60 ? message.substring(0, 60) : message;
    }

    private static void log(String text) {
        ReaderBridgeMod.LOGGER.debug("[readerbridge] {}", text);
    }
}
