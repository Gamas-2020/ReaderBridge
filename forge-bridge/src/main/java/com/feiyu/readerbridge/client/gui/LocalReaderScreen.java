package com.feiyu.readerbridge.client.gui;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import com.feiyu.readerbridge.client.fq.LibraryStore;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.client.source.LocalBooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内阅读面板（M 键打开）。
 *
 * <p>信息架构照成熟阅读器（什锦铺 App / MuoniumPlayer）：左侧竖向导航
 * （书架 / 搜索 / 目录 / 正文 / 设置 / 书签）、顶部标题栏、底部常驻播放条，
 * 外加三个弹层（书详情 / 音色 / 快捷键）。外壳沿用旧骨架：自绘 + 原版
 * {@link EditBox} 承担中文输入法；{@link #isPauseScreen()} 按存档类型决定：
 * <b>单人暂停世界、联机不暂停</b>（判定统一走 AuiBridge.shouldPauseGame()）。
 *
 * <p>线程约定：{@link ReaderEngine} 的读方法都能在渲染线程直接调；只有
 * {@code search(String)} 会阻塞，它和所有面板落盘一起走 {@link PanelIo} 的
 * {@code rb-panel-io} 守护线程池，结果经 {@code Minecraft.execute} 回主线程。
 * 本类里不允许再出现阻塞调用。
 *
 * <p>渲染顺序约定：主界面先画，弹层最后画；<b>弹层打开时主界面的命中区被清空</b>
 * （{@code drawOverlay} 前的 {@code hits.clear()}），否则会点到被遮住的按钮。
 */
public class LocalReaderScreen extends Screen {

    private static final int HEADER_H = 22;
    private static final int PAD = 8;

    /**
     * 播放条音量行的步进量：每次 ±10%。
     *
     * <p>范围 0% ~ 400%：上界与 {@code ReaderBridgeConfig.defineInRange("ttsVolume", 1.0D, 0.0D, 4.0D)}
     * 严格一致。超过 100% 走的是 <b>PCM 数字增益 + tanh 软限幅</b>（不是 {@code AL_GAIN} 放大，
     * 那条路对接近满幅的 TTS mp3 无效），属于用户主动选择，不做限制。</p>
     */
    private static final int VOLUME_STEP_PERCENT = 10;

    /** 音量行整块宽度（宽面板下的上限）：{@code -} 按钮 + 百分比 + {@code +} 按钮。 */
    private static final int VOLUME_WIDTH = 84;

    /**
     * 音量行最窄宽度：仍能放下两个 18px 按钮与三位百分比（极窄时省略 {@code %}）。
     *
     * <p>它只是 {@link #VOLUME_WIDTH} 之外的一个<b>取宽下限</b>，<b>不再是"画不画"的门槛</b>。
     * 旧实现用 {@code restW >= VOLUME_MIN_WIDTH + 40} 决定整块要不要画，面板一窄音量行就整个
     * 消失，用户连可点目标都看不到 —— 那道门槛已经删掉，音量行现在独占一行、必然可见。</p>
     */
    private static final int VOLUME_MIN_WIDTH = 64;

    /** 音量行 {@code -}/{@code +} 两格的宽度。14px 在 1080p 下极难点中，放宽到 18。 */
    private static final int VOLUME_BTN_W = 18;

    /** 本次会话打开过的书（新的在前），只作会话内记忆，不进存档。 */
    static final List<FqApiClient.BookInfo> RECENT = new ArrayList<>();
    private static final int MAX_RECENT = 20;

    /** 六个视图（数字键 1~6 直选）。 */
    enum Tab {
        SHELF("书架"),
        SEARCH("搜索"),
        CHAPTERS("目录"),
        READER("正文"),
        SETTINGS("设置"),
        BOOKMARKS("书签");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    // ---------------------------------------------------------------- 引擎与共享状态

    final ReaderEngine engine = ReaderEngine.get();
    final PanelUi.Hits hits = new PanelUi.Hits();
    final List<FqApiClient.BookInfo> results = new ArrayList<>();

    /** 阅读外观（字号 / 行距 / 宽度 / 主题 / 跟随），落 panel_settings.json。 */
    final PanelPrefs prefs = new PanelPrefs();
    /** 搜索历史，落 panel_history.json。 */
    final PanelHistory history = new PanelHistory();
    /** 书签，落 bookmarks.json。 */
    final BookmarkStore bookmarks = new BookmarkStore();

    Tab tab = Tab.SHELF;
    boolean readerFollow = true;
    boolean busy;
    String searchMsg = "";

    int shelfScroll;
    int searchScroll;
    int chapterScroll;
    int settingsScroll;
    int readerScroll;
    int bookmarkScroll;

    // ---------------------------------------------------------------- 输入框

    private EditBox searchBox;
    private String searchText = "";
    private long searchToken;

    private EditBox chapterBox;
    private String chapterText = "";

    // ---------------------------------------------------------------- 弹层

    private PanelOverlays.Overlay overlay;
    private int overlayX;
    private int overlayY;
    private int overlayW;
    private int overlayH;

    // ---------------------------------------------------------------- 进度条拖动

    private int seekX;
    private int seekY;
    private int seekW;
    private int seekH;
    private boolean seekDragging;
    private double seekPreview = -1.0;

    // ---------------------------------------------------------------- 延迟动作

    /** 「从头读」在等目录：引擎异步取完目录后由 render 里的 tick 落地，不新开线程。 */
    private String pendingFirstChapterBookId;
    private long pendingFirstChapterDeadlineMs;

    // ---------------------------------------------------------------- 删除二次确认

    /**
     * 书架「×」的二次确认状态：第一下把 bookId 和时间戳记在这里，3 秒内再点同一本才真删。
     *
     * <p>删除会连阅读进度一起丢掉（{@code LibraryStore} 里存的 chapterIndex 一并没了），
     * 而书架每行才 28px 高、点错一行就丢一本书 —— 所以不做单击直删。
     * 同一份状态也给 AUI 面板用（那里按 bookId 把该行渲染成红色）。
     */
    String pendingDeleteId = "";
    long pendingDeleteAtMs;

    /** 清空待确认删除。点别的书 / 切 tab / 超时 / 真删完 都走这里。 */
    void clearPendingDelete() {
        pendingDeleteId = "";
        pendingDeleteAtMs = 0L;
    }

    /** 当前是否有一本书处于「待确认删除」且在 3 秒有效期内。 */
    boolean isPendingDelete(String bookId) {
        return !pendingDeleteId.isEmpty()
                && pendingDeleteId.equals(bookId)
                && System.currentTimeMillis() - pendingDeleteAtMs <= 3000L;
    }

    // ---------------------------------------------------------------- 提示

    private long toastUntil;
    private String toastText = "";
    private boolean renderFaulted;

    // ---------------------------------------------------------------- 布局缓存

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int navW;
    private int bodyX;
    private int bodyY;
    private int bodyW;
    private int bodyH;
    private int playX;
    private int playY;
    private int playW;
    private int playH;

    // ---------------------------------------------------------------- 视图实例

    private final ReaderViews.Shelf shelfView = new ReaderViews.Shelf(this);
    private final ReaderViews.Search searchView = new ReaderViews.Search(this);
    private final ReaderViews.Chapters chaptersView = new ReaderViews.Chapters(this);
    private final ReaderViews.Reader readerView = new ReaderViews.Reader(this);
    private final ReaderViews.Settings settingsView = new ReaderViews.Settings(this);
    private final ReaderViews.View bookmarksView = new PanelExtras.Bookmarks(this);

    public LocalReaderScreen() {
        super(Component.literal("阅读面板"));
        // 三个存档都在后台读，首帧用默认值；读完回主线程改字段，下一帧自然生效
        prefs.loadAsync();
        history.loadAsync();
        bookmarks.loadAsync();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void init() {
        super.init();
        if (searchBox != null) {
            searchText = searchBox.getValue();
        }
        searchBox = new EditBox(this.font, 0, 0, 120, 18, Component.literal("搜索书库"));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchText == null ? "" : searchText);
        searchBox.setHint(Component.literal("书名 / 作者，回车搜索"));
        searchBox.setVisible(tab == Tab.SEARCH);
        this.addRenderableWidget(searchBox);

        if (chapterBox != null) {
            chapterText = chapterBox.getValue();
        }
        chapterBox = new EditBox(this.font, 0, 0, 120, 18, Component.literal("过滤章节"));
        chapterBox.setMaxLength(24);
        chapterBox.setValue(chapterText == null ? "" : chapterText);
        chapterBox.setHint(Component.literal("章节名 / 章号"));
        chapterBox.setVisible(tab == Tab.CHAPTERS);
        this.addRenderableWidget(chapterBox);

        if (tab == Tab.SEARCH) {
            this.setInitialFocus(searchBox);
        } else if (tab == Tab.CHAPTERS) {
            this.setInitialFocus(chapterBox);
        }
        layout();
        syncEditBoxes();
    }

    @Override
    public void removed() {
        captureInputs();
        super.removed();
    }

    @Override
    public void onClose() {
        captureInputs();
        super.onClose();
    }

    private void captureInputs() {
        if (searchBox != null) {
            searchText = searchBox.getValue();
        }
        if (chapterBox != null) {
            chapterText = chapterBox.getValue();
        }
    }

    /**
     * 单人存档暂停、联机不暂停。
     *
     * <p>原先恒返回 {@code false}（面板全屏却让世界继续跑）。改成按存档类型判定：
     * <ul>
     *   <li>单人：{@code true} —— 读正文时世界停下来，不会一边读一边被怪打断；</li>
     *   <li>联机：{@code false} —— 客户端暂停只会冻住自己，服务端照跑，等于白挨打。</li>
     * </ul>
     * 判定复用 {@code AuiBridge.shouldPauseGame()}（{@code hasSingleplayerServer()}），
     * 与晴雪UI 面板的 {@code setPauseGame(...)} 保持同一语义，换界面不会换行为。
     *
     * <p>注意：本界面里朗读进度由 {@code ReaderEngine} 自己的守护线程 {@code rb-tick} 推进
     * （不挂客户端 tick），所以暂停世界<b>不会</b>冻住句子高亮/音频调度；
     * 反过来「暂停时高亮继续走」是预期行为，详见交付报告。
     */
    @Override
    public boolean isPauseScreen() {
        return com.feiyu.readerbridge.client.ui.AuiBridge.shouldPauseGame();
    }

    /**
     * 背景由本类自绘（scrim + 圆角面板），这里刻意不画。
     *
     * <p>必须显式覆写：{@link Screen#render} 会调用它，默认实现会铺一层模糊/暗色，
     * 那层会盖在已经画好的面板内容上（EditBox 走 super.render 绘制，所以 super.render
     * 不能省）。
     *
     * <p>签名只能是单参数：1.20.1 的 {@code Screen#renderBackground(GuiGraphics)} 就这一个，
     * 四参数版本（含 mouseX/mouseY/partialTick）是 1.20.2 才引入的，在这里写四参数会报
     * 「方法不会覆盖或实现超类型的方法」。
     */
    @Override
    public void renderBackground(GuiGraphics g) {
        // 画在 render 里了，故意留空
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        hits.clear();
        syncTheme();
        tickPending();

        g.fill(0, 0, this.width, this.height, UiTheme.SCRIM);
        if (panelW > 0 && panelH > 0) {
            UiTheme.fillRounded(g, panelX, panelY, panelW, panelH, UiTheme.RADIUS_PANEL, UiTheme.PANEL);
            UiTheme.strokeRounded(g, panelX, panelY, panelW, panelH, UiTheme.RADIUS_PANEL, UiTheme.STROKE);
        }

        try {
            drawHeader(g, mouseX, mouseY);
            drawNav(g, mouseX, mouseY);
            if (bodyW > 12 && bodyH > 12) {
                UiTheme.fillRounded(g, bodyX, bodyY, bodyW, bodyH, UiTheme.RADIUS_CARD, skin().bodyBg);
                UiTheme.strokeRounded(g, bodyX, bodyY, bodyW, bodyH, UiTheme.RADIUS_CARD, UiTheme.STROKE);
                renderBody(g, mouseX, mouseY, partialTick);
            }
            drawPlayBar(g, mouseX, mouseY);
            drawToast(g);
            drawOverlay(g, mouseX, mouseY);
        } catch (Throwable t) {
            // 画崩了也不能把客户端带走：本帧剩余内容跳过，下一帧继续
            if (!renderFaulted) {
                renderFaulted = true;
                System.err.println("[readerbridge] panel render failed: " + t);
            }
        }

        // EditBox 由 Screen 统一绘制（自带 IME），这里只同步位置/可见性
        syncEditBoxes();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private ReaderViews.View view() {
        switch (tab) {
            case SHELF:
                return shelfView;
            case SEARCH:
                return searchView;
            case CHAPTERS:
                return chaptersView;
            case READER:
                return readerView;
            case SETTINGS:
                return settingsView;
            case BOOKMARKS:
            default:
                return bookmarksView;
        }
    }

    /** 面板配色：主题切换后同步给 {@link UiTheme}，视图里的既有色引用自动跟随。 */
    private void syncTheme() {
        UiTheme.applySkin(prefs.skin());
    }

    PanelSkin skin() {
        return prefs.skin();
    }

    /** 正文缩放系数（只有正文视图在缩放坐标系里排版，其它视图恒为 1）。 */
    private float bodyScale() {
        return tab == Tab.READER ? prefs.textScale() : 1.0f;
    }

    /**
     * 正文当前是否真的处在「缩放坐标系」里。
     *
     * <p><b>必须用「距离 1 的差值」判断，不能写 {@code textScale() > 1.001f}。</b>
     * 字号下限已经放到 {@code FONT_MIN = 1}（{@code textScale() = 0.111}），
     * 旧条件对 1~9 号字恒为 false ⇒ {@link #renderBody} 走直通分支不缩放、
     * {@link #scissorLogical} 也走直通不换算，用户调到 1 号字会看到「字一点没变」。
     * 只有 s 恰为 1（字号 9）时才允许直通：那时缩放与不缩放等价，省一次 pose 栈操作。</p>
     */
    private boolean isScaledBody() {
        return tab == Tab.READER && Math.abs(prefs.textScale() - 1.0f) > 0.001f;
    }

    /** 正文内容区左边界（内容宽度 < 100% 时居中收窄，长行更好读）。 */
    private int contentOriginX() {
        int fullX = bodyX + 1;
        int fullW = bodyW - 2;
        if (tab != Tab.READER) {
            return fullX;
        }
        int pct = prefs.contentWidthPct();
        if (pct >= 100) {
            return fullX;
        }
        int vw = Math.max(60, Math.round(fullW * pct / 100.0f));
        return fullX + Math.max(0, (fullW - vw) / 2);
    }

    private int contentWidth() {
        int fullW = bodyW - 2;
        if (tab != Tab.READER) {
            return fullW;
        }
        int pct = prefs.contentWidthPct();
        if (pct >= 100) {
            return fullW;
        }
        return Math.max(60, Math.round(fullW * pct / 100.0f));
    }

    /**
     * 画正文视图。
     *
     * <p>字号/行距改的是「逻辑像素」排版：先在物理坐标开 scissor，再
     * translate 到内容区 + scale(s)，视图内部照旧用 (vx, vy, lw, lh) 逻辑坐标排版，
     * 于是 Reader 视图一行都不用改；它的命中区仍是逻辑坐标，靠
     * {@link #toContentX(double)} / {@link #toContentY(double)} 把鼠标换算回去。
     * pose 必须在 finally 里 pop，否则这一帧之后的所有 GUI 都会带着缩放。
     */
    /**
     * 在「正文逻辑坐标系」里开裁剪区 —— 正文视图的每一处 scissor 都必须走这里。
     *
     * <p><b>为什么不能直接调 {@code g.enableScissor}：</b>1.20.1 的
     * {@code GuiGraphics.enableScissor(x1, y1, x2, y2)} 把四个数当成<b>屏幕坐标</b>直接乘 GUI scale
     * 交给 {@code RenderSystem.enableScissor}，<b>完全不经过 PoseStack</b>（变换感知的裁剪栈是
     * 1.20.5+ 才有的）。而正文视图是在 {@link #renderBody} 里 {@code pose().scale(s)} 之后绘制的，
     * 它拿到的 {@code x/y/w/h} 全是「逻辑像素」：1 逻辑 px = s 屏幕 px。
     *
     * <p>于是内层裁剪区按逻辑坐标裁（右界 {@code vx + (lw - PAD)}），正文却按 s 倍画到
     * {@code vx + (lw - PAD*2 - gutter) * s} —— 字号 14（s≈1.556）时右侧约 182 屏幕 px 被切掉。
     * 这正是「离线算下来数学上不溢出、实机右侧依然被遮挡」的根因：漏掉的变量就是
     * <b>s</b>，scissor 用的坐标系比绘制用的坐标系小 s 倍。
     *
     * @param x1,y1,x2,y2 逻辑坐标矩形（与 View.render 收到的 x/y/w/h 同一坐标系）
     */
    void scissorLogical(GuiGraphics g, int x1, int y1, int x2, int y2) {
        float s = bodyScale();
        // 直通条件与 renderBody / toContentX 同源（见 isScaledBody 的注释）：
        // 只有 s 恰为 1 才不换算。极小字号（s = 0.111）必须走换算路径，
        // 否则裁剪区按逻辑坐标裁、正文按 s 倍画，右侧/底部会被大面积切掉。
        if (!isScaledBody()) {
            g.enableScissor(x1, y1, x2, y2);
            return;
        }
        int ox = contentOriginX();
        int oy = bodyY + 1;
        int sx1 = Math.round(ox + (x1 - ox) * s);
        int sy1 = Math.round(oy + (y1 - oy) * s);
        int sx2 = Math.round(ox + (x2 - ox) * s);
        int sy2 = Math.round(oy + (y2 - oy) * s);
        // 1.20.1 的 scissor 栈是「替换」而不是「求交」：内层矩形一旦比正文面板大，
        // 面板外的内容就会漏画出来，所以这里自己夹回面板矩形（renderBody 的外层 scissor 语义）。
        int cx1 = Math.max(bodyX, sx1);
        int cy1 = Math.max(bodyY, sy1);
        int cx2 = Math.min(bodyX + bodyW, sx2);
        int cy2 = Math.min(bodyY + bodyH, sy2);
        // 必须始终 push 一个矩形：调用方（View.render 内部）一定会 disableScissor，
        // 而 1.20.1 的 disableScissor 是「弹栈顶」，这里不 push 就会把外层面板裁剪一起弹掉。
        // 夹没了就退化成 1px，效果等同于看不见（也是原版对空矩形的自然行为）。
        if (cx2 <= cx1) {
            cx2 = cx1 + 1;
        }
        if (cy2 <= cy1) {
            cy2 = cy1 + 1;
        }
        g.enableScissor(cx1, cy1, cx2, cy2);
    }

    private void renderBody(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int vx = contentOriginX();
        int vy = bodyY + 1;
        int vw = contentWidth();
        int vh = bodyH - 2;
        ReaderViews.View v = view();
        float s = bodyScale();
        if (!isScaledBody()) {
            v.render(g, bodyX + 1, bodyY + 1, bodyW - 2, vh, mouseX, mouseY, partialTick);
            return;
        }
        // 逻辑宽度 = 物理宽度 / s。s 可以小到 0.1（字号 1），此时 vw/s ≈ 5000，
        // 上一行的 16 下限保护的是 s 很大（字号 72 → s = 8）的情况；
        // 这里的 8192 上限保护极小 s 时折行宽度爆炸（Font.split 按字符遍历，宽度过大会让
        // 单行塞进上千字、排版开销陡增），逻辑上限 8192 已远超任何真实面板宽度。
        int lw = Math.max(16, Math.min(8192, Math.round(vw / s)));
        int lh = Math.max(16, Math.min(8192, Math.round(vh / s)));
        int lmx = Math.round(vx + (mouseX - vx) / s);
        int lmy = Math.round(vy + (mouseY - vy) / s);
        g.enableScissor(bodyX, bodyY, bodyX + bodyW, bodyY + bodyH);
        g.pose().pushPose();
        try {
            g.pose().translate(vx, vy, 0.0);
            g.pose().scale(s, s, 1.0f);
            g.pose().translate(-vx, -vy, 0.0);
            v.render(g, vx, vy, lw, lh, lmx, lmy, partialTick);
        } finally {
            g.pose().popPose();
            g.disableScissor();
        }
    }

    private double toContentX(double mouseX) {
        if (!isScaledBody()) {
            return mouseX;
        }
        int ox = contentOriginX();
        return ox + (mouseX - ox) / prefs.textScale();
    }

    private double toContentY(double mouseY) {
        if (!isScaledBody()) {
            return mouseY;
        }
        int oy = bodyY + 1;
        return oy + (mouseY - oy) / prefs.textScale();
    }

    private void layout() {
        panelW = Math.min(Math.max(220, this.width - 24), 660);
        panelH = Math.min(Math.max(150, this.height - 24), 440);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        navW = PanelUi.clamp(panelW / 6, 56, 88);
        // 播放条改成四行：进度条 / 控制按钮 / 音量+语速+音色 / 状态行。
        // 高度按面板高度取 2/7：最低 76 刚好放得下四行（进度 5..17、按钮 20..38、混音 40..58、
        // 状态 60..68），最高 96（原来上限 72，多吃 24px；panelH=440 时正文仍有 300px）。
        playH = PanelUi.clamp(panelH * 2 / 7, 76, 96);
        playX = panelX + PAD;
        playW = panelW - PAD * 2;
        playY = panelY + panelH - PAD - playH;

        bodyX = panelX + PAD + navW + 6;
        bodyY = panelY + PAD + HEADER_H;
        bodyW = (panelX + panelW - PAD) - bodyX;
        bodyH = (playY - 6) - bodyY;
    }

    /** 顶部标题栏：面板名 + 当前书名 + 帮助 + 关闭。 */
    private void drawHeader(GuiGraphics g, int mouseX, int mouseY) {
        int hx = panelX + PAD;
        int hy = panelY + PAD;
        int hw = panelW - PAD * 2;
        if (hw <= 0) {
            return;
        }
        String title = "什锦铺 · 阅读";
        g.drawString(this.font, title, hx, hy + 3, UiTheme.TEXT, false);
        int closeW = 48;
        int helpW = 20;
        int closeX = hx + hw - closeW;
        int helpX = closeX - helpW - 4;

        // 本地 txt 的「上传」「刷新」：只在书架视图出现，从右往左排在「?」左边。
        // 命中区必须走 hits（render 每帧 clear 后由本方法重新登记），否则点击落不到这两个按钮；
        // 坐标先算好再登记，和「?」「Esc ×」不重叠；面板被拉到最窄（220）时整组不画，而不是叠着画。
        int actionW = 40;
        int uploadX = helpX - 4 - actionW;
        int refreshX = uploadX - 4 - actionW;
        int titleW = this.font.width(title);
        boolean showActions = tab == Tab.SHELF && (refreshX - hx) >= titleW + 10;
        int bookX = hx + titleW + 10;
        int bookW = Math.max(1, (showActions ? refreshX - 6 : helpX - 20) - bookX);
        PanelUi.text(g, this.font, safe(engine.bookTitle()), bookX, hy + 3, UiTheme.TEXT_DIM, bookW);
        if (showActions) {
            PanelUi.chip(g, this.font, "刷新", refreshX, hy, actionW, 15, false, mouseX, mouseY);
            hits.add(refreshX, hy, actionW, 15, this::rescanLocalBooks);
            PanelUi.chip(g, this.font, "上传", uploadX, hy, actionW, 15, false, mouseX, mouseY);
            hits.add(uploadX, hy, actionW, 15, this::uploadLocalBook);
        }
        PanelUi.chip(g, this.font, "?", helpX, hy, helpW, 15, false, mouseX, mouseY);
        hits.add(helpX, hy, helpW, 15, () -> openOverlay(new PanelOverlays.Help(this)));
        PanelUi.chip(g, this.font, "Esc ×", closeX, hy, closeW, 15, false, mouseX, mouseY);
        hits.add(closeX, hy, closeW, 15, this::onClose);
        g.fill(hx, hy + HEADER_H - 9, hx + hw, hy + HEADER_H - 8, UiTheme.STROKE);
    }

    /** 左侧竖向导航（6 项）。 */
    private void drawNav(GuiGraphics g, int mouseX, int mouseY) {
        int x = panelX + PAD;
        int top = bodyY;
        int bottom = playY - 8;
        int avail = bottom - top;
        if (avail < 60 || navW <= 0) {
            return;
        }
        Tab[] tabs = Tab.values();
        int gap = 4;
        int itemH = PanelUi.clamp((avail - gap * (tabs.length - 1)) / tabs.length, 18, 34);
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int iy = top + i * (itemH + gap);
            if (iy + itemH > bottom + itemH) {
                break;
            }
            boolean active = t == tab;
            boolean hover = PanelUi.in(mouseX, mouseY, x, iy, navW, itemH);
            if (active) {
                UiTheme.fillRounded(g, x, iy, navW, itemH, UiTheme.RADIUS_CARD, UiTheme.SELECTED);
                UiTheme.fillRounded(g, x + 1, iy + itemH / 3, 2, itemH / 3, 1, UiTheme.ACCENT);
            } else if (hover) {
                UiTheme.fillRounded(g, x, iy, navW, itemH, UiTheme.RADIUS_CARD, UiTheme.HOVER);
            }
            g.drawString(this.font, t.label, x + 10, iy + 5,
                    active ? UiTheme.TEXT : UiTheme.TEXT_DIM, false);
            PanelUi.textRight(g, this.font, String.valueOf(i + 1), x + navW - 6,
                    iy + itemH - 13, UiTheme.TEXT_FAINT, 12);
            final Tab target = t;
            hits.add(x, iy, navW, itemH, () -> switchTab(target));
        }
    }

    /** 底部常驻播放条：可拖进度条 + 动作行 + 状态行。 */
    private void drawPlayBar(GuiGraphics g, int mouseX, int mouseY) {
        if (playW <= 0 || playH <= 0) {
            return;
        }
        UiTheme.fillRounded(g, playX, playY, playW, playH, UiTheme.RADIUS_PANEL, UiTheme.CARD_SOFT);
        UiTheme.strokeRounded(g, playX, playY, playW, playH, UiTheme.RADIUS_PANEL, UiTheme.STROKE);

        int innerX = playX + PAD;
        int innerW = playW - PAD * 2;
        if (innerW < 80) {
            return;
        }

        boolean active = engine.isActive();
        boolean paused = engine.isPaused();
        double progress = Math.max(0.0, Math.min(1.0, engine.progress()));
        double shown = seekDragging && seekPreview >= 0.0 ? seekPreview : progress;
        String pct = String.format("%.1f%%", shown * 100.0);
        int pctW = this.font.width(pct);

        seekX = innerX;
        seekY = playY + 5;
        seekW = Math.max(16, innerW - pctW - 10);
        seekH = 12;
        boolean seekHover = PanelUi.in(mouseX, mouseY, seekX, seekY, seekW, seekH);
        PanelUi.seekBar(g, seekX, seekY + 4, seekW, 4, shown, seekHover, seekDragging);
        PanelUi.textRight(g, this.font, pct, playX + playW - PAD, playY + 3, UiTheme.TEXT_DIM, 44);

        int rowY = playY + 20;
        int bh = 18;
        int gap = 4;
        int cx = innerX;

        int wPrev = 22;
        int wPlay = 46;
        int wNext = 22;
        int wStop = 22;
        int wStar = 24;

        button(g, "◀", cx, rowY, wPrev, bh, false, mouseX, mouseY, () -> {
            engine.prevChapter();
            resetFollow();
        });
        cx += wPrev + gap;

        String playLabel = active && !paused ? "暂停" : (active ? "继续" : "播放");
        button(g, playLabel, cx, rowY, wPlay, bh, true, mouseX, mouseY, engine::togglePlay);
        cx += wPlay + gap;

        button(g, "▶", cx, rowY, wNext, bh, false, mouseX, mouseY, () -> {
            engine.nextChapter();
            resetFollow();
        });
        cx += wNext + gap;

        button(g, "■", cx, rowY, wStop, bh, false, mouseX, mouseY, () -> engine.stop());
        cx += wStop + gap;

        button(g, "★", cx, rowY, wStar, bh, false, mouseX, mouseY, this::addBookmark);
        cx += wStar + gap;

        // ---------------------------------------------------- 混音行：音量 + 语速 + 音色
        // 独立一行（playY + 40，高 18），<b>不再</b>和上面五个控制按钮争同一行的剩余宽度。
        // 旧实现是「按钮行剩下的宽度够 60 才画语速、够 VOLUME_MIN_WIDTH+40 才画音量」，
        // 面板一窄音量块整块不画 —— 用户看不到任何可点目标。现在音量块有固定下限，
        // 只要播放条本身画得出来（innerW >= 80）它就一定在。
        final int mixerY = playY + 40;
        if (innerW >= VOLUME_MIN_WIDTH + gap) {
            int volW = PanelUi.clamp(innerW / 3, VOLUME_MIN_WIDTH, VOLUME_WIDTH);
            drawVolumeRow(g, innerX, mixerY, volW, bh, mouseX, mouseY);
            int mx = innerX + volW + gap;

            int speedW = PanelUi.clamp(innerW / 6, 34, 52);
            PanelUi.chip(g, this.font, safe(engine.speedLabel()), mx, mixerY, speedW, bh,
                    false, mouseX, mouseY);
            hits.add(mx, mixerY, speedW, bh, () -> {
                engine.cycleSpeed();
                toast("语速 " + engine.speedLabel());
            });
            mx += speedW + gap;

            // 音色 chip：吃剩下的宽度，但封顶 180 —— 宽面板下 innerW 有 644，不封顶会拉成
            // 一条几百 px 的长横条，视觉上比原来挤在按钮行时更难看。
            int voiceW = Math.min(180, innerX + innerW - mx);
            if (voiceW >= 40) {
                String voice = safe(engine.voiceLabel());
                if (voice.isEmpty()) {
                    voice = safe(engine.voiceShortName());
                }
                PanelUi.chip(g, this.font, voice, mx, mixerY, voiceW, bh, false, mouseX, mouseY);
                hits.add(mx, mixerY, voiceW, bh,
                        () -> openOverlay(new PanelOverlays.VoicePicker(this)));
            }
        }

        // ---------------------------------------------------- 状态行
        final int statusY = playY + 60;
        String err = safe(engine.errorLine());
        String status = safe(engine.statusLine());
        String line;
        int color;
        if (!err.isEmpty()) {
            line = "⚠ " + err;
            color = UiTheme.ERROR;
        } else if (!status.isEmpty()) {
            line = status;
            color = UiTheme.TEXT_DIM;
        } else if (!active) {
            line = "未在朗读 · 空格开始";
            color = UiTheme.TEXT_FAINT;
        } else if (paused) {
            line = "已暂停 · 空格继续";
            color = UiTheme.TEXT_DIM;
        } else {
            line = "朗读中 · " + PanelUi.ellipsis(this.font, safe(engine.sentence()), 120);
            color = UiTheme.TEXT_DIM;
        }
        int chapterInfoW = 96;
        PanelUi.text(g, this.font, line, innerX, statusY, color, Math.max(20, innerW - chapterInfoW - 6));
        String chapterInfo;
        int idx = engine.chapterIndex();
        if (idx >= 0 && !engine.chapters().isEmpty()) {
            chapterInfo = "章 " + (idx + 1) + "/" + engine.chapters().size()
                    + " · 句 " + (Math.max(0, engine.sentenceIndex()) + 1);
        } else {
            chapterInfo = "未开章";
        }
        PanelUi.textRight(g, this.font, chapterInfo, playX + playW - PAD, statusY,
                UiTheme.TEXT_FAINT, chapterInfoW);
    }

    /**
     * 播放条里的音量控件：{@code [-] 240% [+]}，独占一行。
     *
     * <p>视觉与同一行的语速/音色 chip 完全一致（同一个 {@link PanelUi#chip}、同样的高度与圆角，
     * 文本同样按 {@code (h - 8) / 2} 垂直居中），不引入新的绘制风格。左/右两格各登记一个
     * 命中区（{@code hits.add}），中间百分比是纯文本、不响应点击。两个命中区严格不重叠：
     * {@code -} 占 {@code [x, x + 18)}，{@code +} 占 {@code [x + w - 18, x + w)}，
     * 而 {@code w >= VOLUME_MIN_WIDTH = 64}，两者之间至少留 28px 文本区。</p>
     *
     * <p>两格宽度从 14px 放宽到 {@link #VOLUME_BTN_W}（18px）：14px 在 1080p 全屏下
     * 只有 14 个物理像素宽（界面缩放 2 时约 28px），点击容错极小。极窄时先省略 {@code %}、
     * 只画数字，再窄才交给 {@link PanelUi#ellipsis}。</p>
     *
     * @param w 整块宽度，由调用方在 {@link #VOLUME_MIN_WIDTH} ~ {@link #VOLUME_WIDTH} 之间取
     */
    private void drawVolumeRow(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        final int innerGap = 2;
        final int textW = Math.max(8, w - VOLUME_BTN_W * 2 - innerGap * 2);
        final int pct = volumePercent();
        String label = pct + "%";
        if (this.font.width(label) > textW) {
            // 极窄：省掉百分号，保证三位数字完整（"400" 比 "400%" 短一个字符）
            label = Integer.toString(pct);
        }

        PanelUi.chip(g, this.font, "-", x, y, VOLUME_BTN_W, h, false, mouseX, mouseY);
        hits.add(x, y, VOLUME_BTN_W, h, () -> stepVolume(-VOLUME_STEP_PERCENT));

        final int tx = x + VOLUME_BTN_W + innerGap;
        final String shown = PanelUi.ellipsis(this.font, label, textW);
        g.drawString(this.font, shown, tx + (textW - this.font.width(shown)) / 2,
                y + (h - 8) / 2, UiTheme.TEXT, false);

        final int px = x + w - VOLUME_BTN_W;
        PanelUi.chip(g, this.font, "+", px, y, VOLUME_BTN_W, h, false, mouseX, mouseY);
        hits.add(px, y, VOLUME_BTN_W, h, () -> stepVolume(VOLUME_STEP_PERCENT));
    }

    /** 当前音量百分比（0 ~ 400，四舍五入到 1%），优先反映磁盘上的 {@code ttsVolume}。 */
    int volumePercent() {
        double v = Math.max(0.0D, Math.min(4.0D, engine.volume()));
        return (int) Math.round(v * 100.0D);
    }

    /**
     * 按步进量调整 TTS 音量并立刻生效。
     *
     * <p>{@link ReaderEngine#setVolume(double)} 一次做完三件事：把增益交给
     * {@code OpenAlTtsPlayer} 重算 PCM（并重传当前 buffer + seek 回原位置，所以当场就能听出变化）、
     * 写回配置文件落盘、{@code fire()} 通知重绘。所以这里不需要再补任何东西。</p>
     *
     * <p>上下界 0% / 400% 与 {@code defineInRange} 保持一致；超过 100% 由
     * {@code PcmGain} 做 tanh 软限幅（保持波形连续，不是硬削波），不加限制、不弹警告。</p>
     */
    void stepVolume(int deltaPercent) {
        final int pct = PanelUi.clamp(volumePercent() + deltaPercent, 0, 400);
        engine.setVolume(pct / 100.0D);
        toast("音量 " + pct + "%");
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int h,
                        boolean active, int mouseX, int mouseY, Runnable action) {
        PanelUi.chip(g, this.font, label, x, y, w, h, active, mouseX, mouseY);
        hits.add(x, y, w, h, action);
    }

    private void drawToast(GuiGraphics g) {
        if (toastText.isEmpty() || System.currentTimeMillis() > toastUntil) {
            return;
        }
        int w = Math.min(Math.max(80, panelW - 24), this.font.width(toastText) + 20);
        int x = panelX + (panelW - w) / 2;
        int y = playY - 20;
        UiTheme.fillRounded(g, x, y, w, 16, UiTheme.RADIUS_CARD, 0xE6202126);
        UiTheme.strokeRounded(g, x, y, w, 16, UiTheme.RADIUS_CARD, UiTheme.STROKE);
        PanelUi.text(g, this.font, toastText, x + 8, y + 4, UiTheme.TEXT, w - 16);
    }

    // ---------------------------------------------------------------- 弹层

    void openOverlay(PanelOverlays.Overlay next) {
        if (next == null) {
            return;
        }
        overlay = next;
        overlay.onShow();
        if (searchBox != null) {
            searchBox.setFocused(false);
        }
        if (chapterBox != null) {
            chapterBox.setFocused(false);
        }
    }

    void closeOverlay() {
        overlay = null;
    }

    boolean hasOverlay() {
        return overlay != null;
    }

    private void drawOverlay(GuiGraphics g, int mouseX, int mouseY) {
        if (overlay == null) {
            return;
        }
        // 弹层在上层：先把它下面那层的命中区清掉，避免点到被遮住的按钮
        hits.clear();
        overlayW = Math.min(Math.max(200, panelW - 40), 420);
        overlayH = Math.min(Math.max(120, panelH - 56), 300);
        overlayX = panelX + (panelW - overlayW) / 2;
        overlayY = panelY + (panelH - overlayH) / 2;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0x99000000);
        try {
            overlay.render(g, this.font, overlayX, overlayY, overlayW, overlayH, mouseX, mouseY);
        } catch (Throwable t) {
            if (!renderFaulted) {
                renderFaulted = true;
                System.err.println("[readerbridge] panel overlay render failed: " + t);
            }
        }
    }

    // ---------------------------------------------------------------- EditBox 同步

    private void syncEditBoxes() {
        boolean hidden = overlay != null;
        if (searchBox != null) {
            boolean visible = !hidden && tab == Tab.SEARCH;
            searchBox.setVisible(visible);
            if (!visible) {
                searchBox.setFocused(false);
            } else if (bodyW > 40) {
                searchBox.setX(bodyX + 9);
                searchBox.setY(bodyY + 9);
                searchBox.setWidth(searchBoxWidth(bodyW));
                searchBox.setHeight(18);
            }
        }
        if (chapterBox != null) {
            boolean visible = !hidden && tab == Tab.CHAPTERS;
            chapterBox.setVisible(visible);
            if (!visible) {
                chapterBox.setFocused(false);
            } else if (bodyW > 40) {
                chapterBox.setX(bodyX + 9);
                chapterBox.setY(bodyY + 9);
                chapterBox.setWidth(chapterBoxWidth(bodyW));
                chapterBox.setHeight(18);
            }
        }
    }

    /** 搜索框宽度（视图与宿主共用同一公式，避免输入框和按钮错位）。 */
    int searchBoxWidth(int viewW) {
        return Math.max(40, viewW - 16 - searchButtonWidth(viewW) - 8);
    }

    /** 搜索按钮宽度。 */
    int searchButtonWidth(int viewW) {
        return Math.min(56, Math.max(36, Math.max(0, viewW - 2) / 5));
    }

    /** 目录过滤框宽度（右侧留一个 34px 的「跳」按钮）。 */
    int chapterBoxWidth(int viewW) {
        return Math.max(40, viewW - 16 - 38);
    }

    /** 视图里要用的字体（Screen#font 是 protected，跨类走这个包级访问器）。 */
    Font font() {
        return this.font;
    }

    // ---------------------------------------------------------------- 交互：鼠标

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (!PanelUi.in(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
            if (overlay != null) {
                closeOverlay();
                return true;
            }
            onClose();
            return true;
        }
        // EditBox 先吃点击（点输入框聚焦），再轮到自绘控件
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (overlay != null) {
            if (hits.click(mouseX, mouseY)) {
                return true;
            }
            if (!PanelUi.in(mouseX, mouseY, overlayX, overlayY, overlayW, overlayH)) {
                closeOverlay();
            }
            return true;
        }
        // 进度条拖动优先：它的命中区不能用 hits 注册（否则会被播放条按钮抢先）
        if (PanelUi.in(mouseX, mouseY, seekX, seekY, seekW, seekH)) {
            seekDragging = true;
            seekPreview = ratioAt(mouseX);
            return true;
        }
        if (hits.click(mouseX, mouseY)) {
            return true;
        }
        if (!PanelUi.in(mouseX, mouseY, bodyX, bodyY, bodyW, bodyH)) {
            return false;
        }
        // 正文视图在缩放坐标系里排版，它注册的命中区是逻辑坐标；导航/播放条是物理坐标，
        // 所以先按物理试一次（上面已做），再按逻辑补一次。
        double lx = toContentX(mouseX);
        double ly = toContentY(mouseY);
        if (isScaledBody() && hits.click(lx, ly)) {
            return true;
        }
        // 命中表只能表达按钮矩形，正文视图要按坐标把点击换算成"第几句"，这里补一次派发。
        return view().mouseClicked(lx, ly);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (seekDragging) {
            seekPreview = ratioAt(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (seekDragging) {
            double ratio = seekPreview;
            seekDragging = false;
            seekPreview = -1.0;
            applySeek(ratio);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (overlay != null) {
            overlay.mouseScrolled(mouseX, mouseY, delta);
            return true;
        }
        if (PanelUi.in(mouseX, mouseY, bodyX, bodyY, bodyW, bodyH)) {
            return view().mouseScrolled(toContentX(mouseX), toContentY(mouseY), delta);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private double ratioAt(double mouseX) {
        double r = (mouseX - seekX) / Math.max(1.0, seekW);
        return r < 0.0 ? 0.0 : Math.min(1.0, r);
    }

    /** 松手才 seek：拖动过程中反复 seek 会不停 stopPlayback + 重新合成，代价太高。 */
    private void applySeek(double ratio) {
        int count = engine.sentenceCount();
        if (count <= 0) {
            toast("先在目录里打开一章");
            return;
        }
        if (!engine.isActive()) {
            toast("先点「播放」，再拖动进度");
            return;
        }
        int idx = PanelUi.clamp((int) Math.round(ratio * (count - 1)), 0, count - 1);
        engine.seekSentence(idx);
        readerFollow = true;
        toast("跳到第 " + (idx + 1) + " 句");
    }

    // ---------------------------------------------------------------- 交互：键盘

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (overlay != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeOverlay();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                overlay.mouseScrolled(0, 0, 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                overlay.mouseScrolled(0, 0, -1);
                return true;
            }
            // 弹层打开时吃掉其它键，避免误触底层的空格/方向键
            return true;
        }

        boolean searchFocus = searchBox != null && searchBox.isFocused();
        boolean chapterFocus = chapterBox != null && chapterBox.isFocused();
        if (searchFocus || chapterFocus) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (chapterFocus) {
                    jumpToChapterFromInput();
                } else {
                    doSearch(searchBox.getValue());
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (chapterFocus) {
                    chapterBox.setFocused(false);
                } else {
                    searchBox.setFocused(false);
                }
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE:
                onClose();
                return true;
            case GLFW.GLFW_KEY_SPACE:
                engine.togglePlay();
                return true;
            case GLFW.GLFW_KEY_LEFT:
                engine.prevChapter();
                resetFollow();
                return true;
            case GLFW.GLFW_KEY_RIGHT:
                engine.nextChapter();
                resetFollow();
                return true;
            case GLFW.GLFW_KEY_TAB:
                cycleTab(hasShiftDown() ? -1 : 1);
                return true;
            case GLFW.GLFW_KEY_PAGE_UP:
                pageBy(-1);
                return true;
            case GLFW.GLFW_KEY_PAGE_DOWN:
                pageBy(1);
                return true;
            case GLFW.GLFW_KEY_F1:
                openOverlay(new PanelOverlays.Help(this));
                return true;
            case GLFW.GLFW_KEY_V:
                openOverlay(new PanelOverlays.VoicePicker(this));
                return true;
            case GLFW.GLFW_KEY_B:
                addBookmark();
                return true;
            case GLFW.GLFW_KEY_F:
                focusChapterFilter();
                return true;
            default:
                break;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_6) {
            int index = keyCode - GLFW.GLFW_KEY_1;
            Tab[] tabs = Tab.values();
            if (index < tabs.length) {
                switchTab(tabs[index]);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** PageUp/PageDown：正文按行翻，其它视图交给视图自己滚列表。 */
    private void pageBy(int dir) {
        if (tab == Tab.READER) {
            readerScroll += dir * 4 * readerLineStep();
            if (readerScroll < 0) {
                readerScroll = 0;
            }
            readerFollow = false;
            return;
        }
        view().mouseScrolled(0, 0, -dir * 2.0);
    }

    /** 正文一行的逻辑像素高（供宿主翻页用，与 Reader 视图内部算法一致）。 */
    int readerLineStep() {
        return prefs.lineHeight();
    }

    // ---------------------------------------------------------------- 动作

    void switchTab(Tab next) {
        if (next == null) {
            return;
        }
        captureInputs();
        tab = next;
        // 切视图一律丢弃「待确认删除」状态：留在别的视图里没有意义，
        // 而在同一页切回书架时它会立刻变成「第二下」，误删一本玩家早已忘记点过的书。
        clearPendingDelete();
        if (next == Tab.SEARCH) {
            syncEditBoxes();
            if (searchBox != null) {
                this.setInitialFocus(searchBox);
            }
        } else if (next == Tab.CHAPTERS) {
            syncEditBoxes();
            if (chapterBox != null) {
                this.setInitialFocus(chapterBox);
            }
        } else {
            if (searchBox != null) {
                searchBox.setFocused(false);
            }
            if (chapterBox != null) {
                chapterBox.setFocused(false);
            }
        }
        if (next == Tab.READER) {
            readerFollow = true;
        }
        view().onShow();
    }

    private void cycleTab(int dir) {
        Tab[] tabs = Tab.values();
        int next = Math.floorMod(tab.ordinal() + dir, tabs.length);
        switchTab(tabs[next]);
    }

    private void focusChapterFilter() {
        switchTab(Tab.CHAPTERS);
        if (chapterBox != null) {
            this.setInitialFocus(chapterBox);
            toast("输入章名过滤，或输章号回车跳转");
        }
    }

    /** 打开一本书的详情弹层（书架 / 搜索结果的统一入口）。 */
    void openBookDetail(FqApiClient.BookInfo book) {
        if (book == null) {
            return;
        }
        openOverlay(new PanelOverlays.BookDetail(this, book));
    }

    /** 兼容旧入口：直接打开一本书的目录（不再从详情走时用）。 */
    void openBook(FqApiClient.BookInfo book) {
        if (book == null) {
            return;
        }
        remember(book);
        engine.openBook(book);
        chapterScroll = 0;
        switchTab(Tab.CHAPTERS);
        toast("已打开：" + safe(book.name));
    }

    /**
     * 书详情「从头读」：先 openBook 取目录，等目录到手后自动 openChapter(0)。
     *
     * <p>不用「注册一次性引擎监听器」：监听器可能跑在合成线程，回调里改 UI 状态要自己
     * 切主线程，容易漏。这里改成主线程每帧检查（{@link #tickPending()}），
     * 8 秒没等到目录就放弃并提示，逻辑简单且不会有悬挂监听。
     */
    void readFromStart(FqApiClient.BookInfo book) {
        if (book == null) {
            return;
        }
        closeOverlay();
        remember(book);
        engine.openBook(book);
        chapterScroll = 0;
        pendingFirstChapterBookId = book.bookId;
        pendingFirstChapterDeadlineMs = System.currentTimeMillis() + 8000L;
        switchTab(Tab.CHAPTERS);
        toast("正在取目录，随后从第 1 章开始");
    }

    private void tickPending() {
        if (pendingFirstChapterBookId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > pendingFirstChapterDeadlineMs) {
            pendingFirstChapterBookId = null;
            toast("目录还没回来，可在目录里手动选章");
            return;
        }
        if (!pendingFirstChapterBookId.equals(safe(engine.bookId()))) {
            return;
        }
        if (engine.chapters().isEmpty()) {
            return;
        }
        pendingFirstChapterBookId = null;
        engine.openChapter(0);
        resetFollow();
        switchTab(Tab.READER);
    }

    /** 书详情「继续上次」：有进度就续听，没有就从第 1 章。 */
    void resumeFromDetail(FqApiClient.BookInfo book, LibraryStore.Entry entry) {
        closeOverlay();
        if (entry != null && entry.hasProgress()) {
            remember(book);
            engine.resumeEntry(entry);
            resetFollow();
            switchTab(Tab.READER);
            toast("继续：" + safe(entry.chapterTitle));
            return;
        }
        readFromStart(book);
    }

    void openChapterFromList(int index) {
        engine.openChapter(index);
        resetFollow();
        switchTab(Tab.READER);
    }

    private void resetFollow() {
        readerScroll = 0;
        readerFollow = true;
    }

    /** 目录过滤框的值。 */
    String chapterFilterValue() {
        if (chapterBox != null) {
            return chapterBox.getValue();
        }
        return chapterText == null ? "" : chapterText;
    }

    /** 目录「跳」按钮 / 过滤框回车：解析章号并跳转。 */
    void jumpToChapterFromInput() {
        String raw = chapterFilterValue() == null ? "" : chapterFilterValue().trim();
        int total = engine.chapters().size();
        if (total <= 0) {
            toast("还没有目录，先打开一本书");
            return;
        }
        if (raw.isEmpty()) {
            toast("输入章号（如 12）后回车");
            return;
        }
        int number;
        try {
            number = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            toast("「" + raw + "」不是章号，点列表里的章节即可");
            return;
        }
        int idx = PanelUi.clamp(number - 1, 0, total - 1);
        openChapterFromList(idx);
        toast("跳到第 " + (idx + 1) + " 章");
    }

    /** 加书签：当前书 + 当前章 + 当前句。 */
    void addBookmark() {
        String bookId = safe(engine.bookId());
        if (bookId.isEmpty() || engine.chapterIndex() < 0) {
            toast("还没有在读书，先打开一本");
            return;
        }
        BookmarkStore.Bookmark b = new BookmarkStore.Bookmark();
        b.bookId = bookId;
        b.bookTitle = safe(engine.bookTitle());
        b.chapterIndex = engine.chapterIndex();
        b.chapterTitle = safe(engine.chapterTitle());
        b.sentenceIndex = Math.max(0, engine.sentenceIndex());
        b.preview = PanelUi.ellipsis(this.font, safe(engine.sentence()), 180);
        LibraryStore.Entry entry = LibraryStore.get().find(bookId);
        if (entry != null) {
            b.author = safe(entry.author);
            b.intro = safe(entry.intro);
        }
        b.createdAtMs = System.currentTimeMillis();
        boolean existed = bookmarks.has(b.bookId, b.chapterIndex, b.sentenceIndex);
        bookmarks.add(b);
        toast(existed ? "书签已更新（本章这一句）" : "已加书签：" + b.headline());
    }

    /** 书签列表点击：重建一条 resumeEntry 跳过去（跨书也能跳）。 */
    void jumpToBookmark(BookmarkStore.Bookmark bookmark) {
        if (bookmark == null || bookmark.bookId == null || bookmark.bookId.isEmpty()) {
            return;
        }
        LibraryStore.Entry entry = new LibraryStore.Entry();
        entry.bookId = bookmark.bookId;
        entry.title = safe(bookmark.bookTitle);
        entry.author = safe(bookmark.author);
        entry.intro = safe(bookmark.intro);
        entry.chapterIndex = Math.max(0, bookmark.chapterIndex);
        entry.chapterTitle = safe(bookmark.chapterTitle);
        entry.sentenceIndex = Math.max(0, bookmark.sentenceIndex);
        entry.totalChapters = 0;
        engine.resumeEntry(entry);
        resetFollow();
        switchTab(Tab.READER);
        toast("跳到：" + bookmark.headline());
    }

    /** 搜索框当前值。 */
    String searchBoxValue() {
        if (searchBox != null) {
            return searchBox.getValue();
        }
        return searchText == null ? "" : searchText;
    }

    void doSearch(String keyword) {
        final String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            searchMsg = "输入书名或作者再搜";
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        searchMsg = "";
        final long token = ++searchToken;

        PanelIo.background(() -> {
            List<FqApiClient.BookInfo> found = new ArrayList<>();
            String error = null;
            try {
                List<FqApiClient.BookInfo> r = engine.search(kw);
                if (r != null) {
                    found.addAll(r);
                }
            } catch (Throwable t) {
                String m = t.getMessage();
                error = m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
            }
            final List<FqApiClient.BookInfo> resultList = found;
            final String err = error;
            PanelIo.onMain(() -> {
                if (token != searchToken) {
                    return;
                }
                busy = false;
                searchScroll = 0;
                if (err != null) {
                    searchMsg = "搜索失败：" + err;
                } else if (resultList.isEmpty()) {
                    results.clear();
                    searchMsg = "没有找到「" + kw + "」";
                    history.add(kw);
                } else {
                    results.clear();
                    results.addAll(resultList);
                    for (int i = 0; i < resultList.size(); i++) {
                        remember(resultList.get(i));
                    }
                    searchMsg = "找到 " + resultList.size() + " 本";
                    history.add(kw);
                }
            });
        });
    }

    // ---------------------------------------------------------------- 本地 txt（拖拽 / 上传 / 刷新）

    /**
     * 拖文件到窗口：复制进 {@code config/readerbridge/books/}。
     *
     * <p>1.20.1 的 {@link Screen} 自带 {@code onFilesDrop(List<Path>)}，GLFW 的 drop 回调最终落到
     * 当前 Screen 上。复制而不是移动（原文件要留着），重名加 {@code _1}/{@code _2}，不覆盖已有文件。
     */
    @Override
    public void onFilesDrop(List<Path> paths) {
        importDroppedBooks(paths);
    }

    void importDroppedBooks(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        final List<Path> copy = new ArrayList<>(paths);
        toast("正在导入 " + copy.size() + " 个文件…");
        Thread worker = new Thread(() -> {
            LocalBooks.ImportReport report = LocalBooks.get().importFiles(copy);
            final String message = importMessage(report);
            Minecraft.getInstance().execute(() -> toast(message));
        }, "rb-local-drop");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 「上传」：先试系统文件选择器（{@link java.awt.FileDialog}），起不来就退化成
     * {@code explorer.exe} 打开 books 文件夹让用户自己放。
     *
     * <p>整条链路在后台线程：{@code FileDialog.setVisible(true)} 是阻塞调用，
     * 放在渲染线程会把游戏冻住直到用户关掉对话框。
     */
    void uploadLocalBook() {
        toast("正在打开文件选择器…");
        Thread worker = new Thread(() -> {
            LocalBooks.ImportReport report = LocalBooks.get().pickAndImportBlocking();
            final String message;
            if (report == null) {
                boolean opened = LocalBooks.openBooksFolder();
                message = opened
                        ? "文件选择器不可用，已打开 books 文件夹：放好 txt 后点「刷新」"
                        : "文件选择器不可用，请手动把 txt 放进 " + LocalBooks.booksDirIfKnown() + " 后点「刷新」";
            } else {
                message = importMessage(report);
            }
            Minecraft.getInstance().execute(() -> toast(message));
        }, "rb-local-upload");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 「刷新」：重扫 books 目录。用户手工丢文件进来后点它就能看到新书，不用重启游戏。
     *
     * <p>本地书源本身没有「注册表」——{@link LocalBooks#search} 每次现扫目录，缓存只缓存
     * 「解析后的章节内容」。所以重扫 = 清内容缓存 + 再扫一遍目录，不碰 library.json。
     */
    void rescanLocalBooks() {
        LocalBooks.ensureDir();
        LocalBooks.get().clearCache();
        int count;
        String err = null;
        try {
            count = LocalBooks.get().search("").size();
        } catch (Throwable t) {
            count = -1;
            err = LocalBooks.reason(t);
        }
        if (count < 0) {
            toast("重扫失败：" + err);
        } else {
            toast("已重扫：books 目录 " + count + " 本 txt，去「搜索」里输书名即可找到");
        }
    }

    /** 导入结果 → 一句 toast。非 txt 的跳过原因必须说出来，不能静默丢弃。 */
    static String importMessage(LocalBooks.ImportReport report) {
        if (report == null) {
            return "导入失败：结果为空";
        }
        StringBuilder sb = new StringBuilder("已导入 ").append(report.copied).append(" 本");
        if (!report.skipped.isEmpty()) {
            sb.append("；忽略 ").append(report.skipped.size()).append(" 个非 txt 文件：")
                    .append(report.skipped.get(0));
            if (report.skipped.size() > 1) {
                sb.append(" 等");
            }
        }
        if (!report.failed.isEmpty()) {
            sb.append("；失败 ").append(report.failed.size()).append(" 个：").append(report.failed.get(0));
        }
        return sb.toString();
    }

    void toast(String message) {
        toastText = message == null ? "" : message;
        toastUntil = System.currentTimeMillis() + 2400L;
    }

    static void remember(FqApiClient.BookInfo book) {
        if (book == null || book.bookId == null || book.bookId.isEmpty()) {
            return;
        }
        for (int i = 0; i < RECENT.size(); i++) {
            FqApiClient.BookInfo b = RECENT.get(i);
            if (b != null && book.bookId.equals(b.bookId)) {
                RECENT.remove(i);
                break;
            }
        }
        RECENT.add(0, book);
        while (RECENT.size() > MAX_RECENT) {
            RECENT.remove(RECENT.size() - 1);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
