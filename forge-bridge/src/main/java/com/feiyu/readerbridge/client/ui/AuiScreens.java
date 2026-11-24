package com.feiyu.readerbridge.client.ui;

import com.feiyu.readerbridge.client.fq.FqApiClient;
import com.feiyu.readerbridge.client.fq.LibraryStore;
import com.feiyu.readerbridge.client.fq.ReaderEngine;
import com.feiyu.readerbridge.client.source.BookSources;
import com.feiyu.readerbridge.client.source.LocalBooks;
import com.sighs.apricityui.event.Event;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 设置页要读十几个配置项，全限定名会把 switch 写成天书；静态导入只用于读值与写回。
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.anchorPreset;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.anchorSpreadDeg;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.builtinSources;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.enabled;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.heartbeatIntervalMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.hudMode;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.hudModeLabel;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.hudScale;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.hudScaleLabel;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.logStateChanges;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.pauseOnGui;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.pauseOnLostFocus;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.reconnectIntervalMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.showHud;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.showWorldText;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.speedMode;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.subtitleFx;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.subtitleFxAmplitude;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.subtitleFxMsPerChar;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.ttsVolume;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.uiMode;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.uiModeLabel;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.worldTextDistance;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.worldTextFadeInMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.worldTextFadeOutMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.wsPort;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyAnchorPreset;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyAnchorSpreadDeg;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyBuiltinSources;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyEnabled;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyFadeMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyHeartbeatIntervalMs;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyLogStateChanges;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyPauseOnGui;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyPauseOnLostFocus;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyReconnectInterval;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applySpeedMode;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applySubtitleFx;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applySubtitleFxAmplitude;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applySubtitleFxMsPerChar;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyTtsVolume;
import static com.feiyu.readerbridge.config.ReaderBridgeConfig.applyWorldTextDistance;

/**
 * 晴雪UI（ApricityUI）面板桥。
 *
 * <p>设计取舍：页面里的结构、配色、视图切换、列表填充全部由 {@code main.html} 里的
 * JS 完成，Java 侧只做两件事——</p>
 * <ol>
 *   <li><b>喂数据</b>：把整份状态拼成一行文本写进隐藏元素 {@code #rb-data}，
 *       页面 JS 每 80ms 读一次，内容变了才重渲染（AUI 的排版开销大，不能每帧重排）；</li>
 *   <li><b>收指令</b>：所有点击都是 Java 侧 {@code Element.addEventListener} 绑定，
 *       搜索框内容用 {@code Element.getValue()} 读。</li>
 * </ol>
 * <p>为什么不让 JS 直接调 Java：AUI 的 Rhino 环境没有稳定的双向绑定入口，
 * 而 DOM 查询与事件监听两边都验证过，走这条单向数据流最稳。</p>
 *
 * <p>线程规则（AUI 硬要求）：任何 DOM 操作都必须在客户端线程。
 * 本类的 {@link #refresh()} 由守护线程 {@code rb-aui-pump} 触发，但真正干活前
 * 一定先 {@code Minecraft.getInstance().execute(...)} 切回客户端线程。</p>
 */
public final class AuiScreens {

    /** 逻辑路径，绝不能带冒号：带冒号时 AUI 的 parseLocation 会判非法并静默返回空文档。 */
    static final String PAGE = "readerbridge/ui/main.html";

    /** 字段分隔符（␟）与行内主/副文本分隔符（␞），两个都是可见字符，不会被文本节点规范化吃掉。 */
    private static final String SEP = "\u241F";
    private static final String SUB = "\u241E";

    /**
     * 每页行槽位数，<b>必须与 hud/main.html 里的 {@code var ROWS} 完全一致</b>。
     *
     * <p>改这个数字要同步三处，缺一处就整页空白或字段错位：
     * <ol>
     *   <li>{@code main.html} 的 {@code var ROWS}（页面用它推导全部 f[] 下标与字段数守卫）；</li>
     *   <li>{@code main.html} 的 CSS {@code #rowN/#delN { top }} 与 body 里的静态节点
     *       —— AUI 只给 bind() 时已存在的节点派发事件，运行时创建的 DOM 收不到点击；</li>
     *   <li>{@link #buildBody} 的字段数（由 ROWS 自动推导，见 {@link #FIELD_COUNT}）。</li>
     * </ol>
     *
     * <p>为什么是 24：AUI 的 CSS 引擎不支持 calc()/flex，行位置只能写死像素，无法按
     * 视口高度自适应。24 行 × 28px 步长 + 66px 起始 = 最后一行（row23）下沿 738px；
     * 底部固定件（#kwbar 742 下沿 768 / #vfoot 770 下沿 788 / #pgprev 768 高 22 下沿 790），
     * 播放条上沿 = 视口高 - 120，所以窗口逻辑高需要 ≥ 910px 才不重叠（1920×1080 全屏安全；
     * 1280×720 只够 16 行，24 行在 720p 会被播放条压住）。
     * 24 是本轮的实测定值（用户实测小窗口不受影响，要求 +8 行）；
     * 再往上加必须同步重算 main.html 的 #row* / #del* / #kwbar / #vfoot / #pgprev /
     * #pgnext / #sctrack / #scbot 八处位置，并把上面的窗口下限一起抬高。
     */
    private static final int ROWS = 24;

    /**
     * 设置项总数（{@link #settingsRow(int)} / {@link #clickSetting(int)} 的 switch 分支数）。
     * 设置页的 {@link #pages()} 用它算页数，翻页键因此也能在设置页使用。
     */
    private static final int SETTINGS_ITEMS = 23;

    /**
     * {@link #buildBody} 产出的字段数（含首位的版本号字段，版本号由 writeState 追加）。
     *
     * <p>顺序：ver ␟ tab ␟ headline ␟ 占位 ␟ ROWS 个行槽位 ␟ foot ␟ 播放条标题 ␟
     * 进度% ␟ 播放按钮文字 ␟ 语速 ␟ 音色 ␟ 状态行 ␟ 关键词 ␟ 音量% ␟ 待删除槽位
     * = 14 + ROWS。
     *
     * <p>这个数字只用于日志和断言；页面侧的同名常量是 {@code main.html} 的 {@code F_MIN}。
     * 历史上这两处差 1（守卫 21 而实际 22），页面 JS 直接 return，表现为整页空白且日志零报错。
     */
    private static final int FIELD_COUNT = 14 + ROWS;

    private static final int TAB_SHELF = 0;
    private static final int TAB_SEARCH = 1;
    private static final int TAB_CHAPTERS = 2;
    private static final int TAB_READ = 3;
    private static final int TAB_SETTINGS = 4;

    private static ApricityScreen screen;
    private static Object boundDoc;
    private static Thread pump;

    private static int tab = TAB_SHELF;
    private static int page;
    private static String keyword = "";
    private static String note = "";
    private static List<FqApiClient.BookInfo> results = Collections.emptyList();
    private static boolean searching;
    private static int searchToken;

    // ---- 视图筛选 ----
    // 各页的「搜索」只搜<b>本页内容</b>：书架筛书名/作者、目录筛章节名、设置筛设置项，
    // 只有「搜索」页才是联网找书（见 doSearch）。筛选走「原列表下标表」，
    // 页内槽位和原下标解耦，否则筛完再点行会打开错的那本/那一章。
    private static String viewFilter = "";
    private static final List<Integer> viewIdx = new ArrayList<>();
    private static String viewSig = "";
    private static boolean viewAll = true;

    private static String lastBody = "";
    private static long ver;

    /**
     * 书架兜底加载是否已经尝试过。
     *
     * <p>{@code ReaderEngine.prewarm()} 用的是相对路径 {@code new File("config","readerbridge")}，
     * 它跟随 JVM 的工作目录；启动器把工作目录放在别处时，{@code library.json}
     * 明明有书却读不到，面板上的书架就是空白。这里在面板首次打开时用 Forge 的
     * {@code FMLPaths.CONFIGDIR}（绝对路径）补一次，且只补一次，不打扰正常路径。</p>
     */
    private static boolean libraryRescueTried;

    private static List<LibraryStore.Entry> shelf = Collections.emptyList();
    private static List<FqApiClient.ChapterInfo> chapters = Collections.emptyList();
    private static int readBase;

    /**
     * 书架「待确认删除」的书 id（空串 = 没有待确认）。
     *
     * <p>删除要防误触：点一次 {@code ×} 只把 bookId 记在这里，3 秒内再点同一本才真删。
     * 点其它行、切 tab、超时都会清空。{@link #pendingDeleteSlot()} 再把它折算成
     * 「当前页的第几行」传给页面，让 JS 把那一行染成 #b33b31。
     */
    private static String pendingDeleteId = "";
    /** 上面那次点击的时间戳（ms，{@code System.currentTimeMillis()}）。 */
    private static long pendingDeleteAtMs;
    /** 二次确认的有效窗口：3 秒。 */
    private static final long DELETE_CONFIRM_MS = 3000L;

    private AuiScreens() {
    }

    /** 打开面板；装不了晴雪UI 时返回 null，由调用方回退原版界面。 */
    public static Screen open() {
        if (!AuiBridge.available()) {
            return null;
        }
        if (screen == null) {
            screen = new DropScreen(PAGE);
            // 单人存档：打开面板就暂停游戏 —— 阅读时村民/怪物/作物都停下来，
            // 也顺带避免玩家在读正文时被苦力怕摸背。
            // 联机：绝不暂停 —— setPauseGame(true) 在客户端只会把玩家自己冻住，
            // 服务端照跑，等于白挨打。判定统一走 AuiBridge.shouldPauseGame()，
            // 原版回退界面 LocalReaderScreen.isPauseScreen() 用同一个判定，两套界面语义一致。
            screen.setPauseGame(AuiBridge.shouldPauseGame());
            boundDoc = null;
            lastBody = "";
            // tab / page 是跨会话的静态字段：上次停在「目录第 3 页」时重开面板会直接
            // 停在那一页，而共享的 page 一旦超出当前列表长度，rowText() 的
            // page*ROWS+at 全部越界返回 null —— 整页空白，玩家读作「书架没有书」。
            // 每次真正新建面板都回到书架第一页。
            tab = TAB_SHELF;
            page = 0;
            note = "";
            rescueLibraryIfEmpty();
        }
        // 这里绝不能调 refresh()：此刻 setScreen() 还没发生，mc.screen 不等于新建的 screen，
        // refresh() 会判定「玩家已离开面板」而把静态字段 screen 清成 null，于是 open() 返回 null，
        // 调用方回退原版界面 —— 表现就是「装了晴雪UI 却开出来还是原版面板」。
        // 首帧渲染与事件绑定全部交给 pump 的首个 tick（150ms 后，此时 init() 已完成）。
        final Screen opened = screen;
        startPump();
        return opened;
    }

    /** 面板关闭时清理引用，避免 pump 线程继续对失效文档写 DOM。 */
    public static void clear() {
        screen = null;
        boundDoc = null;
        lastBody = "";
    }

    private static void startPump() {
        if (pump != null && pump.isAlive()) {
            return;
        }
        pump = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (screen == null) {
                    return;
                }
                Minecraft.getInstance().execute(AuiScreens::refresh);
            }
        }, "rb-aui-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /** 每 tick 调用：刷新缓存、必要时重绑事件、把状态写进页面。 */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (screen == null) {
            return;
        }
        if (mc.screen == null) {
            // 玩家已经离开界面（ESC 或退出到标题）：停下不再写 DOM。
            // 只在「当前没有界面」时才放弃，而不是要求 mc.screen 必须等于本实例：
            // AUI 在资源重载/换 document 时可能换掉 Screen 包装，硬比对引用会让
            // 数据流在面板还开着的时候被冻住 —— 之后所有视图都停在最后一次写入的
            // 旧内容上，正文页/书架页看起来就是「一直空着」。
            screen = null;
            boundDoc = null;
            return;
        }
        Object doc = screen.getLinkedDocument();
        if (doc == null || !active(doc)) {
            return;
        }
        if (boundDoc != doc) {
            boundDoc = doc;
            bind(doc);
        }
        writeState(doc);
    }

    /**
     * 书架为空时用 Forge 的绝对配置目录补读一次。
     *
     * <p>{@code ReaderEngine.prewarm(new File("config","readerbridge"))} 是相对路径，
     * 跟随 JVM 工作目录；工作目录与实例目录不一致时书架读不到书（面板一片空白）。
     * 这里在 {@code FMLPaths.CONFIGDIR} 上再试一次，只试一次，且只在内存里确实是空书架时试，
     * 不会覆盖已有进度。</p>
     */
    private static void rescueLibraryIfEmpty() {
        if (libraryRescueTried) {
            return;
        }
        libraryRescueTried = true;
        try {
            if (!LibraryStore.get().entries().isEmpty()) {
                return;
            }
            // 用实例目录拼绝对路径（Minecraft#gameDirectory 就是启动器给的实例根目录），
            // 不碰任何 FML 内部 API，也不依赖 JVM 的工作目录。
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) {
                return;
            }
            java.nio.file.Path dir = mc.gameDirectory.toPath().resolve("config").resolve("readerbridge");
            LibraryStore.get().load(dir);
            System.err.println("[readerbridge] 书架兜底加载（绝对路径）：" + dir
                    + " -> " + LibraryStore.get().entries().size() + " 本");
        } catch (Throwable t) {
            System.err.println("[readerbridge] 书架兜底加载失败: " + t);
        }
    }

    /** 资源重载会重建 Document，旧文档 isActive() 变 false；此时必须重绑事件，否则点不动。 */
    private static boolean active(Object doc) {
        try {
            Object r = doc.getClass().getMethod("isActive").invoke(doc);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 事件绑定

    private static void bind(Object doc) {
        on(doc, "rb-close", () -> AuiBridge.close());
        // 切视图必须把页码归零：page 是各视图共享的字段，带着上一个视图的页码切过去，
        // rowText() 的 page*ROWS+at 会整体越界，整页空白 —— 书架「看不到书」正是这条路。
        on(doc, "tab0", () -> switchTabReset(TAB_SHELF));
        on(doc, "tab1", () -> switchTabReset(TAB_SEARCH));
        on(doc, "tab2", () -> switchTabReset(TAB_CHAPTERS));
        on(doc, "tab3", () -> switchTabReset(TAB_READ));
        on(doc, "tab4", () -> switchTabReset(TAB_SETTINGS));
        // 侧边滚动条：AUI 的 Event 只有 type/target/detail，没有滚轮增量字段，
        // 滚轮方案不可靠，改用两个可点按钮（页面里 #sctop / #scbot）。
        on(doc, "sctop", () -> turnPage(-1));
        on(doc, "scbot", () -> turnPage(1));
        on(doc, "rb-go", AuiScreens::doSearch);
        on(doc, "pgprev", () -> turnPage(-1));
        on(doc, "pgnext", () -> turnPage(1));
        on(doc, "rb-play", () -> {
            ReaderEngine.get().togglePlay();
            refresh();
        });
        on(doc, "rb-prev", () -> {
            ReaderEngine.get().prevChapter();
            refresh();
        });
        on(doc, "rb-next", () -> {
            ReaderEngine.get().nextChapter();
            refresh();
        });
        on(doc, "rb-speed", () -> {
            ReaderEngine.get().cycleSpeed();
            refresh();
        });
        on(doc, "rb-voice", () -> {
            ReaderEngine.get().cycleVoice();
            refresh();
        });
        // 重新生成当前块：合成偶发失败（网络抖动 / 音色被限流）或换了音色想立刻重听时用。
        // 只重做当前这一块，不切章、不回到章头 —— 位置停在正在读的那一句上。
        on(doc, "rb-regen", () -> {
            ReaderEngine.get().regenerateCurrentBlock();
            refresh();
        });
        // 独立音量：−/+ 两个按钮步进 10%（clamp 0~100）。
        // 同样不用滑块：AUI 的 Event 只有 type/target/detail，拿不到滚轮/拖拽增量，
        // 与上面 sctop/scbot 是同一个理由。音量只作用于模组自己的 TTS，
        // 不碰 Minecraft 的主音量/音乐/语音。
        on(doc, "rb-vol-down", () -> stepVolume(-10));
        on(doc, "rb-vol-up", () -> stepVolume(10));
        // 书架行尾的 × ：固定在页面里的 #del0..#del7（AUI 只认 bind() 时已存在的元素，
        // 运行时创建的节点收不到事件）。第一下只记待确认，3 秒内再点同一本才真删。
        for (int i = 0; i < ROWS; i++) {
            final int at = i;
            on(doc, "del" + i, () -> clickDelete(at));
        }
        on(doc, "rb-hud", () -> {
            note = "3D 世界文字的开关是热键 J（面板里不改配置，避免写坏 toml）";
            refresh();
        });
        // 本地 txt：上传（系统文件选择器，起不来就退化成打开 books 文件夹）与刷新（重扫目录）。
        // 两个按钮只在书架视图可见；AUI 的 opacity:0 元素仍可能吃到点击，所以动作内部再判一次 tab。
        on(doc, "rb-upload", AuiScreens::uploadLocalBook);
        on(doc, "rb-refresh", AuiScreens::refreshLocalBooks);
        for (int i = 0; i < ROWS; i++) {
            final int at = i;
            on(doc, "row" + i, () -> clickRow(at));
        }
    }

    /** 切视图：先把页码归零，再交给原始的切换逻辑，避免共享的 page 把新视图顶到越界页。 */
    private static void switchTabReset(int next) {
        page = 0;
        // 筛选是「页内」概念：在目录页筛了「第4章」，切到书架不该还带着这个词
        // （否则书架会莫名其妙只剩几本，玩家会以为书被删了）。
        viewFilter = "";
        viewSig = "";
        switchTab(next);
    }

    /** 绑定点击；元素缺失时静默跳过（页面版本与 Java 版本不一致时不至于崩）。 */
    private static void on(Object doc, String id, Runnable action) {
        Element el = elementOf(doc, id);
        if (el == null) {
            System.err.println("[readerbridge] AUI 页面缺少元素 #" + id);
            return;
        }
        try {
            el.addEventListener("click", (Event event) -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    note = "操作失败：" + t.getClass().getSimpleName();
                    System.err.println("[readerbridge] AUI 面板操作失败: " + t);
                }
            });
        } catch (Throwable t) {
            System.err.println("[readerbridge] AUI 绑定 " + id + " 失败: " + t);
        }
    }

    /**
     * 取元素。这里对 {@code getElementById} 走反射是为了不把 {@code Document} 类型写进本类签名——
     * AUI 是可选前置，少一个类型依赖就少一处类加载风险。
     */
    private static Element elementOf(Object doc, String id) {
        if (doc == null) {
            return null;
        }
        try {
            Object r = doc.getClass().getMethod("getElementById", String.class).invoke(doc, id);
            return r instanceof Element e ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 交互逻辑

    private static void switchTab(int next) {
        tab = next;
        page = 0;
        note = "";
        // 切视图必须丢弃二次确认状态：留在别的视图里再切回书架，那个红色行没有意义，
        // 更糟的是回来同一页时「再点一次」会立刻删掉一本玩家早已忘记点过的书。
        clearPendingDelete();
        refresh();
    }

    private static void turnPage(int delta) {
        int pages = pages();
        if (pages <= 1) {
            return;
        }
        page = Math.max(0, Math.min(pages - 1, page + delta));
        refresh();
    }

    private static int pages() {
        int total;
        switch (tab) {
            // 一律用「筛选后的行数」：筛选生效时页数必须跟着缩，否则会翻到一页空白。
            // 设置项同理（SETTINGS_ITEMS(8) < ROWS(24) 时恒 1 页，点了不动是预期行为）。
            case TAB_SHELF, TAB_SEARCH, TAB_CHAPTERS, TAB_SETTINGS -> total = viewSize();
            default -> {
                return 1;
            }
        }
        return Math.max(1, (total + ROWS - 1) / ROWS);
    }

    private static void clickRow(int at) {
        // 筛选生效时「页内槽位」和「原列表下标」不是一回事，必须过 viewIndexOf 映射，
        // 否则筛出 3 本后点第 1 行会打开原始列表里第 1 本（完全不相干的书）。
        int index = viewIndexOf(page * ROWS + at);
        // 点任何一行（包括待确认删除的那一行本身）都先清掉「待确认」：
        // 需求要求「点其它行 / 切 tab / 超时都清空 pendingDeleteId」。
        clearPendingDelete();
        switch (tab) {
            case TAB_SHELF -> {
                if (index >= 0 && index < shelf.size()) {
                    ReaderEngine.get().resumeEntry(shelf.get(index));
                    note = "正在打开《" + shelf.get(index).title + "》";
                }
            }
            case TAB_SEARCH -> {
                if (index >= 0 && index < results.size()) {
                    FqApiClient.BookInfo book = results.get(index);
                    // true = 目录到手后自动开第 0 章：点搜索结果就是要马上读
                    ReaderEngine.get().openBook(book, true);
                    tab = TAB_CHAPTERS;
                    page = 0;
                    note = "已选《" + book.name + "》，正在取目录";
                }
            }
            case TAB_CHAPTERS -> {
                if (index >= 0 && index < chapters.size()) {
                    ReaderEngine.get().openChapter(chapters.get(index).index);
                    tab = TAB_READ;
                    page = 0;
                    note = "已打开第 " + (chapters.get(index).index + 1) + " 章";
                }
            }
            case TAB_READ -> {
                int target = readBase + at;
                if (target < ReaderEngine.get().sentenceCount()) {
                    ReaderEngine.get().seekSentence(target);
                }
            }
            // 设置页必须传全局下标：翻到第 2 页时页内第 0 行是第 ROWS 个设置项，
            // 传页内下标会重新触发第 1 页同一个位置的动作。
            case TAB_SETTINGS -> {
                if (index >= 0) {
                    clickSetting(index);
                }
            }
            default -> {
            }
        }
        refresh();
    }

    /**
     * 书架行尾 × 的点击：<b>二次确认删除</b>。
     *
     * <p>第一次点只把 bookId + 时间戳记进 {@link #pendingDeleteId}，状态行提示
     * 「再点一次确认删除《书名》」，{@link #pendingDeleteSlot()} 让页面把那一行染成 #b33b31；
     * 3 秒内再点同一本才真正 {@code LibraryStore.remove} + {@code flush}。
     * 防的是误触——书架上删掉一本就等于丢掉它的阅读进度，恢复不了。
     *
     * @param at 行槽位 0..{@code ROWS-1}（不是列表下标，要自己加 {@code page * ROWS}）
     */
    private static void clickDelete(int at) {
        if (tab != TAB_SHELF) {
            return; // × 只在书架视图可见；别的视图的行不是书架条目
        }
        int index = page * ROWS + at;
        if (index < 0 || index >= shelf.size()) {
            note = "";
            refresh();
            return;
        }
        LibraryStore.Entry entry = shelf.get(index);
        String bookId = entry.bookId == null ? "" : entry.bookId;
        if (bookId.isEmpty()) {
            note = "这本书没有 bookId，不能从书架删除";
            refresh();
            return;
        }
        String title = safe(entry.title);
        long now = System.currentTimeMillis();
        if (bookId.equals(pendingDeleteId) && now - pendingDeleteAtMs <= DELETE_CONFIRM_MS) {
            boolean removed = LibraryStore.get().remove(bookId);
            LibraryStore.get().flush();
            clearPendingDelete();
            // 立刻从内存列表里摘掉，不等下一次 refresh 重建：否则本次渲染还会画出那一行。
            shelf = LibraryStore.get().entries();
            clampPage();
            note = removed ? ("已删除《" + title + "》") : ("《" + title + "》已经不在书架里了");
            System.out.println("[readerbridge] 书架删除 " + (removed ? "成功" : "无变化") + "：" + title + " (" + bookId + ")");
            refresh();
            return;
        }
        pendingDeleteId = bookId;
        pendingDeleteAtMs = now;
        note = "再点一次确认删除《" + title + "》";
        refresh();
    }

    /**
     * 搜索按钮：<b>按当前页分派</b>。
     *
     * <ul>
     *   <li><b>搜索页</b> → 联网找书（原行为）；</li>
     *   <li><b>正文页</b> → 在<b>本章正文</b>里找下一处并跳过去（像阅读器的 Ctrl+F）；</li>
     *   <li><b>书架 / 目录 / 设置</b> → 筛本页列表，支持拼音首字母（书架按书名+作者、
     *       目录按章节名、设置按设置项文案）。</li>
     * </ul>
     *
     * <p>以前这里无条件 {@code tab = TAB_SEARCH}，于是在目录页输入「第4章」点搜索会被拽去
     * 联网搜小说 —— 玩家想找的那一章永远找不到。现在只有搜索页才联网。</p>
     */
    private static void doSearch() {
        Object doc = boundDoc;
        String kw = readKeyword(doc).trim();

        if (tab == TAB_READ) {
            seekKeyword(kw);
            return;
        }

        if (tab != TAB_SEARCH) {
            viewFilter = kw;
            viewSig = "";
            page = 0;
            note = kw.isEmpty() ? "已清除筛选" : ("筛选「" + kw + "」· 命中 " + viewSize() + " 条");
            refresh();
            return;
        }

        if (searching) {
            note = "上一轮搜索还没结束";
            return;
        }
        if (kw.isBlank()) {
            note = "请输入书名或作者";
            refresh();
            return;
        }
        keyword = kw;
        final int token = ++searchToken;
        searching = true;
        note = "搜索中…";
        tab = TAB_SEARCH;
        page = 0;
        refresh();
        Thread worker = new Thread(() -> {
            List<FqApiClient.BookInfo> found = Collections.emptyList();
            String error = null;
            try {
                found = ReaderEngine.get().search(keyword);
            } catch (IOException e) {
                error = "网络请求失败：" + e.getMessage();
            } catch (Throwable t) {
                error = "搜索异常：" + t.getClass().getSimpleName();
            }
            final List<FqApiClient.BookInfo> hit = found;
            final String err = error;
            Minecraft.getInstance().execute(() -> {
                if (token != searchToken) {
                    return;
                }
                searching = false;
                if (err != null) {
                    results = Collections.emptyList();
                    note = err;
                } else {
                    results = hit;
                    note = hit.isEmpty()
                            ? ("没有找到「" + keyword + "」——检查设置里的书源是否可用")
                            : ("找到 " + hit.size() + " 本");
                }
                refresh();
            });
        }, "rb-aui-search");
        worker.setDaemon(true);
        worker.start();
    }

    /** 搜索框的值必须走 Element.getValue()；AUI 的 input 是自绘元素，没有 DOM 属性存值。 */
    private static String readKeyword(Object doc) {
        Element el = elementOf(doc, "rb-kw");
        if (el == null) {
            return keyword;
        }
        String v = el.getValue();
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------ 页内筛选

    /**
     * 正文页的「搜索」= 在本章正文里找<b>下一处</b>并跳过去，连点即在本章内循环跳。
     *
     * <p>刻意不去筛正文列表：把正文筛成只剩匹配句会切断上下文，玩家要的是「跳到那里继续读」。
     * 所以正文页走「查找+定位」，其它页走「筛选列表」。</p>
     */
    private static void seekKeyword(String kw) {
        ReaderEngine engine = ReaderEngine.get();
        List<String> lines = engine.sentences();
        if (kw.isEmpty()) {
            note = "请输入要查找的词";
            refresh();
            return;
        }
        if (lines.isEmpty()) {
            note = "本章还没有正文";
            refresh();
            return;
        }
        String lk = kw.toLowerCase(java.util.Locale.ROOT);
        int cur = engine.sentenceIndex();
        int first = -1;
        int next = -1;
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null) {
                continue;
            }
            if (!s.toLowerCase(java.util.Locale.ROOT).contains(lk)) {
                continue;
            }
            total++;
            if (first < 0) {
                first = i;
            }
            if (next < 0 && i > cur) {
                next = i;
            }
        }
        if (total == 0) {
            note = "本章没有「" + kw + "」";
            refresh();
            return;
        }
        // 后面没有命中了就绕回第一处：连点搜索 = 本章内循环跳，不会「点了没反应」。
        int target = next >= 0 ? next : first;
        engine.seekSentence(target);
        note = "第 " + (target + 1) + " 句命中 · 本章共 " + total + " 处";
        refresh();
    }

    /** 当前页未筛选时的行数（原始列表长度）。 */
    private static int rawSize() {
        return switch (tab) {
            case TAB_SHELF -> shelf.size();
            case TAB_SEARCH -> results.size();
            case TAB_CHAPTERS -> chapters.size();
            case TAB_SETTINGS -> SETTINGS_ITEMS;
            default -> 0;
        };
    }

    /** 当前页筛选后剩几行。 */
    private static int viewSize() {
        ensureView();
        return viewAll ? rawSize() : viewIdx.size();
    }

    /**
     * 筛选后第 {@code slot} 行对应原列表的哪个下标；越界返回 -1。
     *
     * <p>所有「按行号取数据」的地方都必须经过它 —— 筛选生效时槽位与原下标不是同一个数。
     */
    private static int viewIndexOf(int slot) {
        ensureView();
        if (viewAll) {
            return slot;
        }
        return (slot >= 0 && slot < viewIdx.size()) ? viewIdx.get(slot) : -1;
    }

    /** 重建筛选表；签名不变就复用（每帧要查 24 次，不能每次重算 2000 章）。 */
    private static void ensureView() {
        String sig = tab + "|" + viewFilter + "|" + viewFingerprint();
        if (sig.equals(viewSig)) {
            return;
        }
        viewSig = sig;
        viewIdx.clear();
        // 搜索页是联网结果、正文页用 readBase 分页：两者都不参与页内筛选。
        viewAll = viewFilter.isEmpty() || tab == TAB_SEARCH || tab == TAB_READ;
        if (viewAll) {
            return;
        }
        int n = rawSize();
        for (int i = 0; i < n; i++) {
            if (PinyinMatch.matches(rowSearchText(i), viewFilter)) {
                viewIdx.add(i);
            }
        }
    }

    /** 参与筛选的文本：书架看书名+作者，目录看章节名，设置看整行文案（含当前值）。 */
    private static String rowSearchText(int i) {
        return switch (tab) {
            case TAB_SHELF -> i < shelf.size()
                    ? (safe(shelf.get(i).title) + " " + safe(shelf.get(i).author)) : "";
            case TAB_CHAPTERS -> i < chapters.size() ? safe(chapters.get(i).title) : "";
            case TAB_SETTINGS -> safe(settingsRow(i));
            default -> "";
        };
    }

    /**
     * 列表内容指纹：只取首尾 id，O(1)，够用来发现「书架换了书 / 目录换了书」。
     *
     * <p>光看 size 不够：删一本再加一本，size 不变但内容全变了，缓存不失效就会点到错书。
     */
    private static int viewFingerprint() {
        int h = rawSize() * 31 + tab;
        if (tab == TAB_SHELF && !shelf.isEmpty()) {
            h = h * 31 + safe(shelf.get(0).bookId).hashCode();
            h = h * 31 + safe(shelf.get(shelf.size() - 1).bookId).hashCode();
        } else if (tab == TAB_CHAPTERS && !chapters.isEmpty()) {
            h = h * 31 + safe(chapters.get(0).itemId).hashCode();
            h = h * 31 + safe(chapters.get(chapters.size() - 1).itemId).hashCode();
        }
        return h;
    }

    // ------------------------------------------------------------------ 状态打包

    private static void writeState(Object doc) {
        ReaderEngine engine = ReaderEngine.get();
        rescueLibraryIfEmpty();
        shelf = new ArrayList<>(LibraryStore.get().entries());
        chapters = engine.chapters();
        clampPage();
        readBase = computeReadBase(engine);

        String body = buildBody(engine);
        if (!body.equals(lastBody)) {
            lastBody = body;
            ver++;
        }
        Element data = elementOf(doc, "rb-data");
        if (data == null) {
            System.err.println("[readerbridge] AUI 页面缺少 #rb-data，状态无法注入");
            return;
        }
        try {
            data.setTextContent(ver + SEP + body);
        } catch (Throwable t) {
            System.err.println("[readerbridge] AUI 写入状态失败: " + t);
        }
    }

    /**
     * 页码夹进当前视图的合法范围。
     *
     * <p>{@code page} 是静态字段且各视图共享：换了视图 / 删了书 / 换了一本书之后列表长度变了，
     * 残留的 page 会让 {@code rowText()} 的 {@code page * ROWS + at} 整体越界，
     * 于是 8 行全部返回 null —— 页面看上去就是「一本都没有」。每次写状态都夹一次，成本为零。</p>
     */
    private static void clampPage() {
        int pages = pages();
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
    }

    private static int computeReadBase(ReaderEngine engine) {
        int total = engine.sentenceCount();
        if (total <= ROWS) {
            return 0;
        }
        int start = engine.sentenceIndex() - (ROWS / 2);
        return Math.max(0, Math.min(total - ROWS, start));
    }

    private static String buildBody(ReaderEngine engine) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(tab).append(SEP);
        sb.append(headline(engine)).append(SEP);
        sb.append(' ').append(SEP);
        for (int i = 0; i < ROWS; i++) {
            String row = rowText(engine, i);
            sb.append(row == null ? "" : row).append(SEP);
        }
        sb.append(foot()).append(SEP);
        sb.append(playTitle(engine)).append(SEP);
        sb.append(percent(engine.progress())).append(SEP);
        sb.append(engine.isActive() ? (engine.isPaused() ? "继续" : "暂停") : "播放").append(SEP);
        sb.append(engine.speedLabel()).append(SEP);
        sb.append(engine.voiceLabel() == null ? "音色" : engine.voiceLabel()).append(SEP);
        sb.append(statusLine(engine)).append(SEP);
        // 末尾字段不能是空串：AUI 的文本节点可能把尾随分隔符规范化掉，字段数从 FIELD_COUNT
        // 掉到 FIELD_COUNT-1，页面 JS 的字段数校验一旦不过就整页不渲染（所有视图一起空白，极难定位）。
        // 用一个空格占位，JS 侧见到空格就还原成空关键词。
        sb.append(keyword == null || keyword.isEmpty() ? " " : keyword).append(SEP);
        // 音量百分比<b>只能追加在末尾</b>：字段顺序是 AUI 页面的解析契约
        // （f[1]=tab … f[11+ROWS]=keyword，见 main.html 的 F_KW 注释）。
        // 插队会让 #rb-play/#rb-speed/#rb-voice 等所有按钮的文本整体错位。
        // 它也是 refresh 的状态签名的一部分（writeState 用 lastBody 比对），
        // 所以调音量必然触发一次重写，面板百分比不会停在旧值。
        sb.append(volumePercent(engine)).append(SEP);
        // 倒数第一位 = 「待确认删除」所在的行槽位（0..ROWS-1，-1 表示没有）。
        // 同样只能追加在末尾：字段顺序是页面的解析契约，插队会让所有按钮文本错位；
        // 它也在 lastBody 的比对里，所以点第一次 × 时面板必然立刻重绘出红色那一行。
        sb.append(pendingDeleteSlot());
        return sb.toString();
    }

    /** 播放条上的音量百分比（0~400）；四舍五入，避免 0.7 显示成 69%。 */
    private static int volumePercent(ReaderEngine engine) {
        int pct = (int) Math.round(engine.volume() * 100.0D);
        // 上限 400%：AL_GAIN > 1.0 是放大而非限制，超过约 200% 后母带已录满、
        // 多数音频会削波失真 —— 用户主动选择，不是 bug，所以只做显示层夹紧。
        return Math.max(0, Math.min(400, pct));
    }

    /** 音量步进（百分点），按整数百分比运算，避免 0.1 累加出 0.7000000000000001。 */
    private static void stepVolume(int deltaPct) {
        ReaderEngine engine = ReaderEngine.get();
        int pct = Math.max(0, Math.min(400, volumePercent(engine) + deltaPct));
        // setVolume 内部：立刻下发 AL_GAIN + 写回配置 + fire()（监听器刷新）
        engine.setVolume(pct / 100.0D);
        refresh();
    }

    /**
     * 待确认删除的书在当前页里的行槽位（0..7）；没有待确认、或不在当前页时返回 -1。
     *
     * <p>只对书架视图有意义：其它视图的行不是书架条目，页面那边也不显示 × 热区。
     */
    private static int pendingDeleteSlot() {
        if (pendingDeleteId.isEmpty() || tab != TAB_SHELF) {
            return -1;
        }
        if (System.currentTimeMillis() - pendingDeleteAtMs > DELETE_CONFIRM_MS) {
            pendingDeleteId = ""; // 超时：静默失效，下次渲染就没有红色行了
            return -1;
        }
        List<LibraryStore.Entry> list = shelf;
        for (int i = 0; i < list.size(); i++) {
            if (pendingDeleteId.equals(list.get(i).bookId)) {
                int slot = i - page * ROWS;
                return (slot >= 0 && slot < ROWS) ? slot : -1;
            }
        }
        pendingDeleteId = ""; // 书已经不在列表里（被删/被替换）：清掉残留状态
        return -1;
    }

    /** 清空二次确认状态（点别的行、切 tab、删除成功后都调）。 */
    private static void clearPendingDelete() {
        pendingDeleteId = "";
        pendingDeleteAtMs = 0L;
    }

    private static String headline(ReaderEngine engine) {
        String suffix = searching ? "  ·  搜索中" : "";
        // 筛选生效时把「筛出多少条」写进标题行：否则玩家看着列表变短，会以为书/章丢了。
        String filt = viewFilter.isEmpty() ? "" : ("  ·  筛「" + viewFilter + "」" + viewSize() + " 条");
        return switch (tab) {
            case TAB_SHELF -> "书架  ·  " + shelf.size() + " 本" + filt
                    + "  ·  第 " + (page + 1) + "/" + pages() + " 页";
            case TAB_SEARCH -> "搜索  ·  " + (keyword.isEmpty() ? "未搜索" : "「" + keyword + "」") 
                    + "  ·  " + results.size() + " 条" + suffix;
            case TAB_CHAPTERS -> {
                String title = engine.bookTitle() == null ? "未选书" : engine.bookTitle();
                yield "目录  ·  " + title + "  ·  " + chapters.size() + " 章" + filt
                        + "  ·  第 " + (page + 1) + "/" + pages() + " 页";
            }
            case TAB_READ -> {
                String title = engine.chapterTitle() == null ? "未打开章节" : engine.chapterTitle();
                yield "正文  ·  " + title + "  ·  第 " + (readBase + 1) + "-"
                        + Math.min(readBase + ROWS, engine.sentenceCount()) + "/" + engine.sentenceCount() + " 句";
            }
            default -> "设置  ·  书源 " + BookSources.get().totalSourceCount()
                    + "（规则 " + BookSources.get().ruleCount() + "）" + filt;
        };
    }

    private static String rowText(ReaderEngine engine, int at) {
        int index = viewIndexOf(page * ROWS + at);
        // 筛出来一条都没命中：第 0 行给明确提示，别让整页空白看着像卡死/丢数据。
        if (!viewAll && viewSize() == 0 && at == 0) {
            return "没有匹配「" + viewFilter + "」的内容" + SUB + "清空搜索框再点搜索即可恢复";
        }
        switch (tab) {
            case TAB_SHELF -> {
                if (shelf.isEmpty()) {
                    // 空书架不能只留空白：玩家分不清「书架坏了」和「书架就是空的」。
                    return at == 0 ? ("书架是空的" + SUB + "去「搜索」页找书，点结果会自动上书架") : null;
                }
                if (index < 0 || index >= shelf.size()) {
                    return null;
                }
                LibraryStore.Entry e = shelf.get(index);
                String sub = e.hasProgress()
                        ? ("第 " + (e.chapterIndex + 1) + " 章 · 共 " + Math.max(1, e.totalChapters) + " 章")
                        : "未开始";
                return safe(e.title) + SUB + sub + SUB + safe(e.author);
            }
            case TAB_SEARCH -> {
                if (results.isEmpty()) {
                    if (at != 0) {
                        return null;
                    }
                    return searching
                            ? ("搜索中…" + SUB + "正在向各书源请求")
                            : ("还没有搜索结果" + SUB + "输入书名或作者后点「搜索」");
                }
                if (index < 0 || index >= results.size()) {
                    return null;
                }
                FqApiClient.BookInfo b = results.get(index);
                return safe(b.name) + SUB + safe(b.author) + SUB + shortSource(b);
            }
            case TAB_CHAPTERS -> {
                if (chapters.isEmpty()) {
                    return at == 0 ? ("还没有目录" + SUB + "先在「搜索」页选中一本书") : null;
                }
                if (index < 0 || index >= chapters.size()) {
                    return null;
                }
                FqApiClient.ChapterInfo c = chapters.get(index);
                String mark = c.index == engine.chapterIndex() ? "▶ " : "  ";
                return mark + safe(c.title) + SUB + ("第 " + (c.index + 1) + " 章");
            }
            case TAB_READ -> {
                List<String> lines = engine.sentences();
                if (lines.isEmpty()) {
                    return at == 0
                            ? ("还没有正文" + SUB + "去「目录」页点一章，正文到手后这里逐句显示")
                            : null;
                }
                int target = readBase + at;
                if (target >= lines.size()) {
                    return null;
                }
                String mark = target == engine.sentenceIndex() ? "▶ " : "  ";
                // 补一个行内分隔符：其它视图的行都是「主文本␞副文本」，只有正文行没有分隔符，
                // 页面 JS 按分隔符切分时这条路径最好也走同一条分支，避免出现「只有正文不显示」。
                return mark + safe(lines.get(target)) + SUB + "";
            }
            case TAB_SETTINGS -> {
                // index 已由 viewIndexOf 换回「全局设置项下标」（筛选后槽位 ≠ 原下标，
                // 这里若还用 page*ROWS+at，筛完点一行会触发另一个设置项的动作）。
                // 越界返回 null（行清空），不会让整页变成空白。
                return index < 0 ? null : settingsRow(index);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 设置页的一行文案。
     *
     * @param index <b>全局设置项下标</b>（0..{@link #SETTINGS_ITEMS}-1），不是页内槽位；
     *              调用方已经加过 {@code page * ROWS}。越界返回 null，页面这一行清空。
     */
    /** 1 位小数，整数去掉 .0（3.0 → "3"，4.5 → "4.5"）。 */
    private static String fmt1(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 开关统一口径，避免各处写「开启/关闭/是/否」混用。 */
    private static String onOff(boolean v) {
        return v ? "已开启" : "已关闭";
    }

    /**
     * 档位取值：命中当前值就取下一档；没命中就取比当前值大的第一档；都不行取第一档。
     *
     * <p>为什么用「点一下换一档」而不是输入框：AUI 的 input 只管键盘焦点，值仍要 Java 侧
     * {@code getValue()} 读，而程序回填 value 会让光标失步、下一次按键崩渲染线程
     * （2026-11-25 05:13 的事故）。循环档位既躲开这个坑，也不需要额外的确定按钮。</p>
     */
    private static double laddDouble(double[] ladder, double cur) {
        for (int i = 0; i < ladder.length; i++) {
            if (Math.abs(ladder[i] - cur) < 1e-9) {
                return ladder[(i + 1) % ladder.length];
            }
        }
        for (double v : ladder) {
            if (v > cur) {
                return v;
            }
        }
        return ladder[0];
    }

    private static int laddInt(int[] ladder, int cur) {
        for (int i = 0; i < ladder.length; i++) {
            if (ladder[i] == cur) {
                return ladder[(i + 1) % ladder.length];
            }
        }
        for (int v : ladder) {
            if (v > cur) {
                return v;
            }
        }
        return ladder[0];
    }

    private static String laddStr(String[] ladder, String cur) {
        for (int i = 0; i < ladder.length; i++) {
            if (ladder[i].equals(cur)) {
                return ladder[(i + 1) % ladder.length];
            }
        }
        return ladder[0];
    }

    private static String settingsRow(int index) {
        ReaderEngine engine = ReaderEngine.get();
        return switch (index) {
            // 设置项恒为 SETTINGS_ITEMS 个（= 8），而每页有 ROWS（= 24）个槽位，
            // 所以永远是 1 页：第 0..7 行有内容，第 8..11 行是空格（与书架不满一页时一样）。
            // 这样 ROWS 变大不再需要「挤掉一行」这种补丁，新增设置项只是往 switch 里加 case。
            //
            // 第 3 项承载「HUD 缩放」（[hud] hudScale，只作用于晴雪UI 的 HUD）。
            // 原本的「语速」不在这里：播放条上有现成的 rb-speed 按钮。
            case 0 -> "界面风格" + SUB + com.feiyu.readerbridge.config.ReaderBridgeConfig
                    .uiModeLabel(com.feiyu.readerbridge.config.ReaderBridgeConfig.uiMode())
                    + "（点这一行循环切换，下次打开面板生效）";
            case 1 -> "HUD 风格" + SUB + com.feiyu.readerbridge.config.ReaderBridgeConfig
                    .hudModeLabel(com.feiyu.readerbridge.config.ReaderBridgeConfig.hudMode())
                    + "（点这一行循环切换，立刻生效；跟随界面 = 与界面风格一致）";
            case 2 -> "朗读音色" + SUB + safe(engine.voiceLabel()) + "（点这一行切换）";
            case 3 -> "HUD 缩放" + SUB + com.feiyu.readerbridge.config.ReaderBridgeConfig
                    .hudScaleLabel(com.feiyu.readerbridge.config.ReaderBridgeConfig.hudScale())
                    + "（点这一行循环 1.0→1.5→2→3→4→5→6 倍；只作用于晴雪UI 的 HUD，语速用播放条上的按钮切）";
            case 4 -> "3D 世界文字" + SUB
                    + (com.feiyu.readerbridge.config.ReaderBridgeConfig.showWorldText() ? "已开启" : "已关闭")
                    + "（点这一行切换，直接写回 toml）";
            case 5 -> "屏幕 HUD" + SUB
                    + (com.feiyu.readerbridge.config.ReaderBridgeConfig.showHud() ? "已开启" : "已关闭")
                    + "（点这一行切换；位置用热键 H 拖）";
            case 6 -> "重连间隔" + SUB + com.feiyu.readerbridge.config.ReaderBridgeConfig.reconnectIntervalMs()
                    + " ms（点这一行在 3/5/8/15 秒之间切换，会重建连接）";
            case 7 -> "什锦铺 / 热键" + SUB + "127.0.0.1:" + wsPort()
                    + " · M 面板 G 播放 B 下章 N 上章 J 3D文字 H 拖HUD";
            // ---- 以下各项是本轮从 config/readerbridge-common.toml 搬进来的 ----
            // 原则：只搬「改完能立刻生效或明确标注需重启」的项；热键要按键捕获，留在只读行里。
            case 8 -> "3D 文字距离" + SUB + fmt1(worldTextDistance())
                    + " 格（点这一行循环 3 / 4.5 / 6 / … / 48，立刻生效）";
            case 9 -> "3D 文字锚点" + SUB + anchorPreset()
                    + "（follow 跟随 / stay 每句换位 / head 钉在视线前，立刻生效）";
            case 10 -> "锚点散布角" + SUB + fmt1(anchorSpreadDeg())
                    + "°（stay 模式下每句偏开的角度，0 = 全叠在一处）";
            case 11 -> "字幕动效" + SUB + subtitleFx()
                    + "（点这一行循环 crossfade/typewriter/wave/…/none）";
            case 12 -> "字幕动效速度" + SUB + subtitleFxMsPerChar() + " ms/字（越小越快）";
            case 13 -> "字幕动效幅度" + SUB + fmt1(subtitleFxAmplitude())
                    + "（0 = 不动，只做淡入淡出）";
            case 14 -> "淡入 / 淡出" + SUB + worldTextFadeInMs() + " / " + worldTextFadeOutMs()
                    + " ms（换句时文字出现与消失的软化程度）";
            case 15 -> "TTS 音量" + SUB + fmt1(ttsVolume())
                    + " 倍（0~4；播放条上是 ±10% 微调，这里是整档）";
            case 16 -> "语速方式" + SUB + speedMode()
                    + "（direct = OpenAL 实时变速；bake = 烘进音频更准，但换档要重合成）";
            case 17 -> "开界面时暂停" + SUB + onOff(pauseOnGui()) + "（打开任何 GUI 就暂停朗读）";
            case 18 -> "失焦时暂停" + SUB + onOff(pauseOnLostFocus()) + "（切出游戏窗口时暂停）";
            case 19 -> "内置规则书源" + SUB + onOff(builtinSources()) + "（改了要重启游戏才生效）";
            case 20 -> "状态日志" + SUB + onOff(logStateChanges()) + "（排查用，开了会刷屏）";
            case 21 -> "WS 心跳间隔" + SUB + heartbeatIntervalMs() + " ms（点这一行循环，立刻生效）";
            case 22 -> "桥接总开关" + SUB + onOff(enabled()) + "（关掉即断开与 App 的连接）";
            default -> null;
        };
    }

    /**
     * 设置页的行点击：能安全热改的项直接写回 {@code config/readerbridge-common.toml}。
     *
     * <p>注意 {@code showHud()} 与 {@code showWorldText()} 是两个独立开关：
     * 前者是屏幕角落的 HUD，后者是世界里的 3D 悬浮文字，早先混用过一次，别再把文案和实现搞反。
     * 端口不开放交互 —— 改端口会让所有已连接的客户端失去目标，属于会话级设置。
     */
    private static void clickSetting(int index) {
        // 越界 = 翻页后尾部的空槽位（第 SETTINGS_ITEMS..ROWS-1 行）：什么都不做，
        // 也不改写 note —— 点空格位不该冒出「这一项请在 toml 里改」这种误导性提示。
        if (index < 0 || index >= SETTINGS_ITEMS) {
            return;
        }
        switch (index) {
            case 0 -> {
                // 两套界面都必须在自己的设置页里能切走/切回来，否则就是死锁：
                // 选了 vanilla 就再也进不了 AUI 设置页，选了 apricity 就再也回不到原版。
                String next = com.feiyu.readerbridge.config.ReaderBridgeConfig.cycleUiMode();
                note = "界面风格已切换为「" + com.feiyu.readerbridge.config.ReaderBridgeConfig.uiModeLabel(next)
                        + "」，下次打开面板生效";
            }
            case 1 -> {
                // HUD 风格：只写 [hud] hudMode，不碰界面风格。HUD 每帧都重新解析
                // effectiveHudMode()，所以写回后立刻换样子，不需要重开面板。
                String next = com.feiyu.readerbridge.config.ReaderBridgeConfig.cycleHudMode();
                note = "HUD 风格已切换为「" + com.feiyu.readerbridge.config.ReaderBridgeConfig.hudModeLabel(next)
                        + "」，立刻生效";
            }
            case 2 -> {
                ReaderEngine.get().cycleVoice();
                note = "音色已切换：" + safe(ReaderEngine.get().voiceLabel());
            }
            case 3 -> {
                // 语速从设置页挪走了：播放条上有现成的 rb-speed 按钮，设置页这一格用来放
                // 晴雪UI HUD 的放大系数（原版 HUD 不受影响，见 ReaderBridgeConfig.HUD_SCALE）。
                double next = com.feiyu.readerbridge.config.ReaderBridgeConfig.cycleHudScale();
                note = "HUD 缩放已设为 " + com.feiyu.readerbridge.config.ReaderBridgeConfig.hudScaleLabel(next)
                        + "（立刻生效）";
            }
            case 4 -> {
                boolean next = !com.feiyu.readerbridge.config.ReaderBridgeConfig.showWorldText();
                com.feiyu.readerbridge.config.ReaderBridgeConfig.setShowWorldText(next);
                note = "3D 世界文字已" + (next ? "开启" : "关闭") + "（已写入 toml）";
            }
            case 5 -> {
                boolean next = !com.feiyu.readerbridge.config.ReaderBridgeConfig.showHud();
                com.feiyu.readerbridge.config.ReaderBridgeConfig.setShowHud(next);
                note = "屏幕 HUD 已" + (next ? "开启" : "关闭") + "（已写入 toml；位置用热键 H 拖）";
            }
            case 6 -> {
                int cur = com.feiyu.readerbridge.config.ReaderBridgeConfig.reconnectIntervalMs();
                int next = cur < 5000 ? 5000 : (cur < 8000 ? 8000 : (cur < 15000 ? 15000 : 3000));
                int applied = com.feiyu.readerbridge.config.ReaderBridgeConfig.applyReconnectInterval(next);
                note = "重连间隔 " + cur + "ms → " + applied + "ms（已重建连接）";
            }
            case 7 -> note = "端口与热键是只读信息；改端口请编辑 config/readerbridge-common.toml 的 wsPort";
            case 8 -> {
                double cur = worldTextDistance();
                double next = applyWorldTextDistance(laddDouble(new double[]{
                        3.0, 4.5, 6.0, 7.5, 9.0, 12.0, 15.0, 18.0, 24.0, 30.0, 36.0, 42.0, 48.0}, cur));
                note = "3D 文字距离 " + fmt1(cur) + " → " + fmt1(next) + " 格";
            }
            case 9 -> {
                String cur = anchorPreset();
                String next = applyAnchorPreset(laddStr(new String[]{"stay", "follow", "head"}, cur));
                note = "3D 文字锚点 " + cur + " → " + next;
            }
            case 10 -> {
                double cur = anchorSpreadDeg();
                double next = applyAnchorSpreadDeg(laddDouble(new double[]{
                        0.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0, 24.0, 30.0}, cur));
                note = "锚点散布角 " + fmt1(cur) + "° → " + fmt1(next) + "°";
            }
            case 11 -> {
                String next = applySubtitleFx(laddStr(new String[]{
                        "crossfade", "typewriter", "typewriterBounce", "wave", "slideUp", "glow", "none"},
                        subtitleFx()));
                note = "字幕动效 → " + next;
            }
            case 12 -> {
                int next = applySubtitleFxMsPerChar(laddInt(new int[]{20, 30, 45, 60, 90, 120},
                        subtitleFxMsPerChar()));
                note = "字幕动效速度 → " + next + " ms/字";
            }
            case 13 -> {
                double next = applySubtitleFxAmplitude(laddDouble(new double[]{0.0, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0},
                        subtitleFxAmplitude()));
                note = "字幕动效幅度 → " + fmt1(next);
            }
            case 14 -> {
                // 两个键合成一项：用户感知上就是「换句时文字怎么出现/消失」。
                int in = worldTextFadeInMs();
                int out = worldTextFadeOutMs();
                int[][] ladder = {{220, 380}, {0, 0}, {400, 700}, {800, 1200}};
                int pick = 0;
                for (int i = 0; i < ladder.length; i++) {
                    if (ladder[i][0] == in && ladder[i][1] == out) {
                        pick = (i + 1) % ladder.length;
                        break;
                    }
                }
                int[] applied = applyFadeMs(ladder[pick][0], ladder[pick][1]);
                note = "淡入/淡出 " + in + "/" + out + " → " + applied[0] + "/" + applied[1] + " ms";
            }
            case 15 -> {
                double next = applyTtsVolume(laddDouble(new double[]{
                        0.3, 0.5, 0.8, 1.0, 1.2, 1.5, 2.0, 3.0}, ttsVolume()));
                note = "TTS 音量 → " + fmt1(next) + " 倍";
            }
            case 16 -> {
                String next = applySpeedMode("direct".equals(speedMode()) ? "bake" : "direct");
                note = "语速方式 → " + next + ("bake".equals(next)
                        ? "（换档会按档位重新合成，第一次会慢一点）"
                        : "（实时变速，不重新合成）");
            }
            case 17 -> {
                boolean next = !pauseOnGui();
                applyPauseOnGui(next);
                note = "开界面时暂停已" + (next ? "开启" : "关闭");
            }
            case 18 -> {
                boolean next = !pauseOnLostFocus();
                applyPauseOnLostFocus(next);
                note = "失焦时暂停已" + (next ? "开启" : "关闭");
            }
            case 19 -> {
                boolean next = !builtinSources();
                applyBuiltinSources(next);
                note = "内置规则书源已" + (next ? "开启" : "关闭") + "（重启游戏后生效）";
            }
            case 20 -> {
                boolean next = !logStateChanges();
                applyLogStateChanges(next);
                note = "状态日志已" + (next ? "开启（会刷屏）" : "关闭");
            }
            case 21 -> {
                int next = applyHeartbeatIntervalMs(laddInt(new int[]{3000, 5000, 10000, 15000, 30000, 60000},
                        heartbeatIntervalMs()));
                note = "WS 心跳间隔 → " + next + " ms";
            }
            case 22 -> {
                boolean next = !enabled();
                applyEnabled(next);
                note = "桥接已" + (next ? "开启" : "关闭") + (next ? "，正在重连 App" : "，连接已断开");
            }
            default -> note = "这一项请在 config/readerbridge-common.toml 里改";
        }
    }

    /**
     * 面板底部那行文字。
     *
     * <p>按用户要求去掉全部「点击 XX 可以 YY」式的操作提示（原来的 hint 文案），
     * 只保留 Java 侧的即时反馈 {@link #note}：切换成功的回执、正在打开某书、
     * 二次确认删除的警告等 —— 这些是状态而不是操作教学，删了用户就看不见反馈了。
     *
     * <p>没有反馈时返回一个空格而不是空串：空串在 AUI 的文本节点里可能被规范化掉，
     * 一旦某个字段被吃掉，页面的字段数守卫 {@code f.length < F_MIN} 会挡住整页渲染。
     */
    private static String foot() {
        return note.isEmpty() ? " " : note;
    }

    private static String playTitle(ReaderEngine engine) {
        if (!engine.isActive()) {
            return "未在朗读  ·  从书架或目录里选一章开始";
        }
        String book = safe(engine.bookTitle());
        String chapter = safe(engine.chapterTitle());
        String line = engine.sentence();
        String shortLine = line == null ? "" : (line.length() > 40 ? line.substring(0, 40) + "…" : line);
        return book + "  ·  " + chapter + "  ·  " + shortLine;
    }

    private static String statusLine(ReaderEngine engine) {
        String err = engine.errorLine();
        if (err != null && !err.isEmpty()) {
            return err;
        }
        String st = engine.statusLine();
        if (st != null && !st.isEmpty()) {
            return st;
        }
        if (searching) {
            return "搜索中…";
        }
        return engine.isActive() ? ("句 " + (engine.sentenceIndex() + 1) + "/" + engine.sentenceCount()) : "就绪";
    }

    private static String percent(double progress) {
        double p = Math.max(0.0D, Math.min(1.0D, progress)) * 100.0D;
        return String.valueOf((int) Math.round(p));
    }

    private static String shortSource(FqApiClient.BookInfo book) {
        String id = book.sourceId;
        if (id == null || id.isEmpty()) {
            return "内置源";
        }
        if (BookSources.FQAPI.equals(id)) {
            return "什锦铺";
        }
        return id;
    }

    // ------------------------------------------------------------------ 本地书（拖拽 / 上传 / 刷新）

    /**
     * 拖拽进来的文件：复制进 {@code config/readerbridge/books/}。
     *
     * <p>由 {@link DropScreen#onFilesDrop(List)} 在客户端线程回调。复制可能很慢（几十 MB 的 txt），
     * 所以真正干活在后台线程，回调只负责把结果写回 {@link #note}（f18 状态行）并触发重绘。
     */
    static void importDroppedFiles(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        final List<Path> copy = new ArrayList<>(paths);
        note = "正在导入 " + copy.size() + " 个文件…";
        refresh();
        Thread worker = new Thread(() -> {
            LocalBooks.ImportReport report = LocalBooks.get().importFiles(copy);
            final String message = reportMessage(report);
            Minecraft.getInstance().execute(() -> {
                note = message;
                refresh();
            });
        }, "rb-local-drop");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 「上传」按钮：优先弹系统文件选择器（{@code java.awt.FileDialog}），
     * 起不来就退化成 {@code explorer.exe} 打开 books 文件夹。
     *
     * <p>整条链路都在后台线程：FileDialog 的 {@code setVisible(true)} 是阻塞调用，
     * 放渲染线程会把游戏冻住直到用户关掉对话框。
     */
    private static void uploadLocalBook() {
        if (tab != TAB_SHELF) {
            return;
        }
        note = "正在打开文件选择器…";
        refresh();
        Thread worker = new Thread(() -> {
            LocalBooks.ImportReport report = LocalBooks.get().pickAndImportBlocking();
            final String message;
            if (report == null) {
                boolean opened = LocalBooks.openBooksFolder();
                message = opened
                        ? "文件选择器不可用，已打开 books 文件夹：把 txt 放进去后点「刷新」"
                        : "文件选择器不可用，请手动把 txt 放进 " + LocalBooks.booksDirIfKnown() + " 后点「刷新」";
            } else {
                message = reportMessage(report);
            }
            Minecraft.getInstance().execute(() -> {
                note = message;
                refresh();
            });
        }, "rb-local-upload");
        worker.setDaemon(true);
        worker.start();
    }

    /** 「刷新」：重扫 books 目录（确保目录存在 + 清解析缓存）。只碰 books/，不碰 library.json。 */
    private static void refreshLocalBooks() {
        if (tab != TAB_SHELF) {
            return;
        }
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
        note = count < 0
                ? ("重扫失败：" + err + "（" + LocalBooks.booksDirIfKnown() + "）")
                : ("已重扫：books 目录 " + count + " 本 txt，去「搜索」里输书名即可找到");
        refresh();
    }

    /** 导入结果 → 状态行的一句话。非 txt 的跳过原因必须说出来，不能静默丢弃。 */
    private static String reportMessage(LocalBooks.ImportReport report) {
        if (report == null) {
            return "导入失败：结果为空";
        }
        StringBuilder sb = new StringBuilder("已导入 ").append(report.copied).append(" 本");
        if (!report.skipped.isEmpty()) {
            sb.append("；忽略 ").append(report.skipped.size()).append(" 个非 txt 文件（只收 .txt）：")
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

    /**
     * 覆写了 {@code onFilesDrop} 的晴雪UI 屏幕。
     *
     * <p>为什么必须自己继承：反编译源码
     * {@code .reports/_aui_src/com/sighs/apricityui/screen/ApricityScreen.java}（共 224 行）
     * 只覆写了 {@code m_7856_}(init) / {@code m_6574_}(resize) / {@code m_88315_}(render) /
     * {@code m_6050_}(mouseScrolled) / {@code m_7933_}(keyPressed) / {@code m_7379_}(onClose) /
     * {@code m_7861_}(removed) / {@code m_7043_}(isPauseScreen)，<b>没有 onFilesDrop</b>，
     * 也没有遍历 children 转发的代码（它的 children 本来就是空表：AUI 元素是自绘的）。
     * 所以直接把 {@code ApricityScreen} 交给 {@code setScreen}，拖文件到窗口不会有任何反应。
     *
     * <p>只能作为本类的私有内部类：{@code com.sighs.apricityui.*} 的类型不允许漏到其它类，
     * 否则没装晴雪UI 的客户端会 {@code NoClassDefFoundError}。
     */
    private static final class DropScreen extends ApricityScreen {
        DropScreen(String templatePath) {
            super(templatePath);
        }

        @Override
        public void onFilesDrop(List<Path> paths) {
            try {
                importDroppedFiles(paths);
            } catch (Throwable t) {
                System.err.println("[readerbridge] AUI 拖拽导入失败: " + t);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(SEP, " ").replace(SUB, " ");
    }
}
