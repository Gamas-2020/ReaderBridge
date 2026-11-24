package com.feiyu.readerbridge.client.ui;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import java.util.HashMap;
import java.util.Map;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * HUD 的晴雪UI（ApricityUI）渲染分支 —— <b>隔离类</b>。
 *
 * <p>晴雪UI 是可选前置（build.gradle 里 {@code compileOnly}，不 embed）。因此
 * {@code com.sighs.apricityui.*} 的类型只允许出现在本类（以及既有的
 * {@code AuiBridge} / {@code AuiScreens}）内部，绝不写进字段类型、方法签名、
 * {@code extends}/{@code implements} 子句或 {@code static} 初始化块 ——
 * 否则没装晴雪UI 的客户端会在 mod 扫描阶段就抛 {@code NoClassDefFoundError}。</p>
 *
 * <h2>为什么是「注册一个 Document」而不是自己画</h2>
 * <p>ApricityUI 1.3.1 自带屏幕外 overlay 通路，第三方不需要自己拿 {@code RenderGuiEvent}：</p>
 * <ul>
 *   <li>{@code client/Client.java:93-117} 的 {@code drawOverlay(RenderGuiEvent.Post)}：
 *       当 {@code mc.screen == null && !hideGui} 时把 {@code Base.planFlatLayers()} 里所有
 *       flat document 画出来 —— 条件是 {@code Base.isFlatDocument(doc) = !doc.inWorld && !doc.isManuallyRendered()}
 *       （{@code render/Base.java:244}）。</li>
 *   <li>{@code client/Client.java:72-91} 的 {@code drawScreen(ScreenEvent.Render.Post)}：
 *       任何非 ApricityScreen 的界面打开时，把 {@code isReloadPersistent()} 的 document
 *       画在界面之上（{@code Base.drawPersistentScreenDocuments}）。</li>
 * </ul>
 * <p>所以本类只做三件事：{@code Document.create("readerbridge/ui/hud.html")} +
 * {@code setReloadPersistent(true)}（让 H 键拖拽界面打开时 HUD 仍然可见，便于对齐）+
 * 把状态写进页面里的隐藏数据块。绘制完全交给 AUI 引擎。</p>
 *
 * <h2>两套渲染共用一套坐标</h2>
 * <p>位置仍然来自 {@code [hud] hudX / hudY}（GUI 像素）。{@code aui-viewport=mode=browser}
 * 下 1 CSS px = 1 物理像素，而 1 GUI px = 1/guiScale 物理像素，所以这里用
 * {@code Document.getViewportScaleX()}（= AUI 的 renderScale）把 GUI px 换算成 CSS px：
 * {@code cssX = round(guiX / renderScale)}。窗口大小 / GUI 缩放变化时 renderScale 跟着变，
 * 换算自动纠正，{@code 拖拽界面} 与 AUI HUD 因此永远指向同一个点。</p>
 */
@Mod.EventBusSubscriber(modid = ReaderBridgeMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AuiHud {

    /** 页面逻辑路径。物理位置 assets/apricityui/apricity/readerbridge/ui/hud.html（根被硬编码成 apricityui）。 */
    static final String PAGE = "readerbridge/ui/hud.html";

    /** 字段分隔符 U+241F，行内主/副文本分隔符 U+241E —— 与 main.html 同一套约定。 */
    private static final char SEP = '\u241F';
    private static final char SUB = '\u241E';

    // ---- 设计尺寸（游戏 GUI 像素）。hud.html 的 CSS 默认值必须与这里一一对应。----
    private static final int W = 240;
    /**
     * 完整态高度：状态点 + 书名 + 章名/进度 + 3 行当前句 + 进度条。
     *
     * <p>只在 {@code paused} / {@code ok} 用。没有当前句的状态（{@code off} / {@code idle}）
     * 用 {@link #H_COMPACT}，否则胶囊下方会留一大片纯色空白 —— 那正是「100×40 空壳块」的观感来源。</p>
     */
    private static final int H = 96;
    /** 紧凑态高度：比 {@link #CHAP_Y} + {@link #CHAP_H} 多 6px（含上下 border），刚好包住文字。 */
    private static final int H_COMPACT = 46;
    private static final int BORDER = 2;
    private static final int RADIUS = 8;

    private static final int DOT_X = 8;
    private static final int DOT_Y = 9;
    private static final int DOT = 8;

    private static final int BOOK_X = 22;
    private static final int BOOK_Y = 6;
    private static final int BOOK_W = 210;
    private static final int BOOK_H = 18;
    private static final int BOOK_FS = 14;

    private static final int CHAP_X = 22;
    private static final int CHAP_Y = 26;
    private static final int CHAP_W = 120;
    private static final int CHAP_H = 14;
    private static final int CHAP_FS = 11;

    private static final int PCT_X = 146;
    private static final int PCT_Y = 26;
    private static final int PCT_W = 86;
    private static final int PCT_H = 14;
    private static final int PCT_FS = 11;

    private static final int SENT_X = 8;
    private static final int SENT_Y = 42;
    private static final int SENT_W = 224;
    private static final int SENT_H = 45;
    private static final int SENT_FS = 12;
    private static final int SENT_LH = 15;

    private static final int BAR_X = 8;
    private static final int BAR_Y = 90;
    private static final int BAR_W = 224;
    private static final int BAR_H = 4;

    /**
     * 当前 Document。声明成 {@link Object} 而不是 {@code Document}：字段类型会被写进类文件，
     * 未装晴雪UI 时类加载器解析字段类型就会炸。真正用到 AUI 类型的地方全在方法体内（懒解析）。
     */
    private static Object doc;

    /** 是否装了晴雪UI。安装状态运行期不变，缓存一次。 */
    private static Boolean auiAvailable;

    /** 上一次写进 #rb-hud-data 的正文（不含版本号），内容不变就不碰 DOM。 */
    private static String lastBody = "";
    private static int ver;

    /** 内联样式（位置 / 尺寸 / 显隐）上一次写入时的参数，全等就跳过。 */
    private static boolean layoutValid;
    private static double lastScale = -1.0D;
    private static int lastGuiX = -1;
    private static int lastGuiY = -1;
    private static boolean lastShow;
    /** 上一次布局用的是不是紧凑档（{@code off} / {@code idle}）。变化时必须重写内联样式。 */
    private static boolean lastCompact;

    /**
     * 页面当前的 display 是否已经是 none。
     *
     * <p>用「已经藏好了吗」而不是「现在是显示着吗」来记账：凡是状态不确定的时刻（文档刚建、
     * DOM 未就绪、文档被重建）都把它复位成 false，下一次 {@link #hide()} 会无条件写一次
     * {@code display:none} —— 宁可多写一次内联样式，也不能在主菜单留一块 HUD 残影。</p>
     */
    private static boolean displayIsNone;

    /** 创建失败后的重试冷却（毫秒）：模板还没被 AUI 扫到时不至于每帧刷日志。 */
    private static long nextCreateAttemptMs;

    // ------------------------------------------------------------------ 可验证日志
    //
    // 这组字段只为「用户重启后看 latest.log 就能定位」而存在。三种可能的现象必须能区分开：
    //   1) 根本没有 "AUI HUD 文档已创建" —— AUI HUD 没被启用（hudMode 解析成了 vanilla）
    //      或 hud.html 没注册（路径/资源包问题）；
    //   2) 有创建日志、没有同步日志 —— 文档建好了但数据没写进去（#rb-hud-data 缺失 /
    //      文档 inactive / body 与 lastBody 相同），对应 WARN 行会指出具体原因；
    //   3) 创建 + 同步日志都有 —— Java 侧写入正常，问题在页面侧的解析/渲染。

    /** 文档代数：每建一个新 Document +1。用来区分「换了个新 DOM」和「同一个 DOM 没数据」。 */
    private static int generation;

    /** 上一次 INFO 同步日志的时间戳与状态签名，实现「最多每 3 秒一条」。 */
    private static long lastSyncLogMs;
    private static String lastSyncLogKey = "";

    /**
     * 上一次真正写进元素的内联样式，按元素 id 记忆；{@link #lastStyleDoc} 记录它属于哪个文档。
     *
     * <p>这不是性能优化，是<b>稳定性措施</b>。AUI 1.3.1 把「有可变内联样式的元素」装在一个
     * {@code Collections.newSetFromMap(new WeakHashMap<>())} 里，每帧
     * {@code Document.commitMutableInlineStyles()} 都做 {@code new ArrayList<>(set)} 遍历它。
     * WeakHashMap 的迭代期间一旦有 stale entry 被 expunge（GC 回收掉旧文档留下的死元素后，
     * 下一次 size/get/put 就会清理），{@code modCount} 立刻变化，迭代器抛
     * {@code ConcurrentModificationException} —— 2026-11-25 05:57:12 的客户端崩溃正是这条路径，
     * 崩溃报告里 {@code Suspected Mod: ApricityUI}、{@code com.feiyu} 栈帧数为 0。</p>
     *
     * <p>修不了 AUI 内部，就减少喂给它的写入次数：原来拖 HUD 时 {@link #applyLayout} 每帧
     * 对 8 个元素各写一次 style（位置缓存只挡得住「什么都没变」的帧，拖拽时 guiX/guiY 每帧都在变，
     * 缓存必然失效）；而这 8 个元素里有 7 个的内联样式只依赖 sizeScale 与 compact，
     * 拖拽时内容<b>逐字节相同</b>。比对后跳过，每帧写入从 8 次降到 1 次。</p>
     */
    private static final Map<String, String> lastStyleCss = new HashMap<>();
    /** {@link #lastStyleCss} 属于哪个文档；文档换了必须清空，否则新文档的元素拿不到样式。 */
    private static Object lastStyleDoc;

    private AuiHud() {
    }

    /** 是否装了晴雪UI（纯 ModList 查询，不碰任何 AUI 类型）。 */
    public static boolean available() {
        Boolean cached = auiAvailable;
        if (cached == null) {
            boolean value;
            try {
                value = AuiBridge.available();
            } catch (Throwable t) {
                value = false;
            }
            cached = Boolean.valueOf(value);
            auiAvailable = cached;
        }
        return cached.booleanValue();
    }

    /** HUD 的 AUI 文档是否已经建好（供非隔离类判断要不要额外调 {@link #hide()}）。 */
    public static boolean exists() {
        return doc != null;
    }

    /**
     * 把当前状态喂给页面（每帧由 {@code ReaderBridgeHud} 调用）。
     *
     * <p>方法签名里没有任何 AUI 类型，所以 {@code ReaderBridgeHud} 可以安全调用它；
     * 真正的 AUI 解析全部发生在方法体内。</p>
     *
     * @param show     是否显示（配置总开关 + 热键 J 的结果）
     * @param stateKey {@code off} / {@code idle} / {@code paused} / {@code ok}
     * @param book     书名
     * @param chapter  章名（可为空）
     * @param sentence 当前句（可为空）
     * @param permille 进度千分比 0~1000
     * @param guiX     HUD 左上角 X（游戏 GUI 像素，直接来自 hudX）
     * @param guiY     HUD 左上角 Y（游戏 GUI 像素，直接来自 hudY）
     * @return {@code true} 表示 AUI 已经接管本帧的绘制（调用方不要再画原版胶囊）；
     *         {@code false} 表示文档还没就绪 / 前置不在，调用方可以退回原版绘制
     */
    public static boolean render(boolean show, String stateKey, String book, String chapter,
                                 String sentence, int permille, int guiX, int guiY) {
        if (!available()) {
            doc = null;
            return false;
        }
        try {
            Object document = ensureDocument();
            if (document == null) {
                return false;
            }
            double scale = cssScale(document);
            applyLayout(document, scale, Math.max(0, guiX), Math.max(0, guiY), show, stateKey, permille);
            if (!show) {
                return true;
            }
            int cssX = (int) Math.round(Math.max(0, guiX) / scale);
            int cssY = (int) Math.round(Math.max(0, guiY) / scale);
            writeData(document, show, stateKey, book, chapter, sentence, permille, cssX, cssY);
            return true;
        } catch (Throwable t) {
            // HUD 出问题绝不能影响游戏主循环，也不能每帧刷屏
            quietWarn("渲染状态写入失败", t);
            return false;
        }
    }

    /** 隐藏 HUD（热键 J 关掉 / F1 隐藏界面 / 离开世界）。不销毁文档，下次直接用。 */
    public static void hide() {
        Object cached = doc;
        if (cached == null) {
            // 未装晴雪UI / 还没建文档：直接返回，绝不触碰 AUI 类型（连 instanceof 都不做）
            return;
        }
        try {
            // doc 只会由本类赋成 Document，所以这里能安全地直接强转
            Document document = (Document) cached;
            if (!document.isActive()) {
                doc = null;
                layoutValid = false;
                displayIsNone = false;
                return;
            }
            if (!displayIsNone) {
                Element hud = document.getElementById("rb-hud");
                if (hud != null) {
                    // 隐藏态用紧凑档的高度：反正不可见，主要是别在 display:none 期间把
                    // 高度缓存写成完整态（否则下次 show 时若 compact 判定没变，会留着 96 的盒子）。
                    // 两个 scale 都传 1.0：隐藏态不需要真实换算，尺寸只要是个合法值即可。
                    hud.setAttribute("style", hudStyle(1.0D, 1.0D, 0, 0, false, true));
                    displayIsNone = true;
                }
                layoutValid = false;
                lastBody = "";
            }
        } catch (Throwable t) {
            quietWarn("隐藏失败", t);
        }
    }

    /**
     * 离开世界 / 回主菜单时把 AUI HUD 收起来。
     *
     * <p>必须自己收：HUD 文档设了 {@code setReloadPersistent(true)}（这样 H 键拖拽界面里
     * 它仍在上面，便于和模拟框对齐），而 {@code Client.drawScreen} 在主菜单也会画 persistent
     * 文档 —— 不收就会在主菜单留一块 HUD 残影。</p>
     *
     * <p>刻意<b>不</b>在 {@code mc.screen != null} 时隐藏：拖拽界面（{@code HudPositionScreen}）
     * 是原版 Screen，HUD 留在上面才能对着它调位置。</p>
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (doc == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean inWorld = minecraft != null && minecraft.level != null && minecraft.player != null;
        if (!inWorld) {
            hide();
        }
    }

    // ------------------------------------------------------------------ 文档

    /**
     * 拿到当前可用的 Document，拿不到返回 {@code null}。
     *
     * <p><b>返回类型为什么是 {@link Object}</b>：本类挂着
     * {@link Mod.EventBusSubscriber}，Forge 往事件总线注册订户时会
     * {@code Class#getDeclaredMethods()} 一遍，那会把每个方法的参数类型和返回类型都解析成
     * Class 对象 —— 只要签名里出现 {@code com.sighs.apricityui.*}，没装晴雪UI 的客户端就会
     * 在 mod 加载阶段抛 {@code NoClassDefFoundError} 直接秒退。
     * 所以本类所有方法签名一律只用 JDK 类型和 Minecraft 类型，AUI 类型只在方法体里出现
     * （JVM 对方法体内的类型引用是懒解析的，没装前置就永远走不到那些指令）。</p>
     */
    private static Object ensureDocument() {
        Object cached = doc;
        if (cached != null) {
            // doc 只会由本类赋成 Document；这里先判空再强转，未装晴雪UI 时 doc 恒为 null，
            // 不会执行到涉及 AUI 类型的字节码
            Document document = (Document) cached;
            if (document.isActive()) {
                return document;
            }
            doc = null;
            layoutValid = false;
            displayIsNone = false;
        }

        long now = System.currentTimeMillis();
        if (now < nextCreateAttemptMs) {
            return null;
        }

        Document fresh = ApricityUI.ensureDocument(PAGE);
        if (fresh == null) {
            // 模板还没被 AUI 的资源扫描注册（资源包加载中 / 页面放错位置）。5 秒后再试。
            nextCreateAttemptMs = now + 5000L;
            quietWarn("hud.html 还没就绪，稍后重试", null);
            return null;
        }
        // ensureDocument 命中已有实例时不会重设；Document.create 那条路会在 ApricityUI 里设好，
        // 这里再设一次是幂等的，且能兜住「玩家以前用别的方式创建过同路径文档」的情况。
        fresh.setReloadPersistent(true);
        doc = fresh;
        lastBody = "";
        layoutValid = false;
        displayIsNone = false;
        generation++;
        // 这条日志是「AUI HUD 到底有没有跑起来」的第一手证据：没有它 = 从未进入 AUI 分支。
        ReaderBridgeMod.LOGGER.info("[readerbridge] AUI HUD 文档已创建 path={} generation={}",
                PAGE, generation);
        return fresh;
    }

    /** AUI 的 renderScale：1 CSS px = renderScale 个 GUI px。取不到时按 1.0 处理。 */
    private static double cssScale(Object handle) {
        try {
            Document document = (Document) handle;
            double scale = document.getViewportScaleX();
            if (scale > 0.01D && scale < 100.0D) {
                return scale;
            }
        } catch (Throwable ignored) {
            // 落到 1.0
        }
        return 1.0D;
    }

    // ------------------------------------------------------------------ 内联样式（位置 / 尺寸 / 显隐）

    private static void applyLayout(Object handle, double scale, int guiX, int guiY, boolean show,
                                    String stateKey, int permille) {
        // 位置用 viewport scale 原值（GUI px → CSS px）；尺寸再乘用户放大系数。
        // 两者绝不能混用：位置若乘了系数，HUD 会按比例往左上角偏移。
        // 原版 HUD 不受这个系数影响，见 ReaderBridgeConfig.HUD_SCALE 的 javadoc。
        double sizeScale = scale * ReaderBridgeConfig.hudScale();
        // 没有当前句的状态（未连接 / 未在朗读）走紧凑档：只留状态点 + 书名 + 章名/进度。
        // 页面在 46 高度下不会画出句子区和进度条（见下面的 display:none）。
        boolean compact = "off".equals(stateKey) || "idle".equals(stateKey);
        // 文档换了（换风格 / 资源重载）：位置缓存必须作废，否则下面整段 styleOf 全被跳过，
        // 新文档的元素一个样式都拿不到，HUD 会叠在屏幕左上角。
        if (handle != lastStyleDoc) {
            layoutValid = false;
        }
        // 缓存键存 sizeScale：它同时反映 viewport 缩放与用户系数，改 hudScale 后会自动重排。
        if (layoutValid && lastScale == sizeScale && lastGuiX == guiX && lastGuiY == guiY
                && lastShow == show && lastCompact == compact) {
            return;
        }
        Document document = (Document) handle;
        Element hud = document.getElementById("rb-hud");
        if (hud == null) {
            // DOM 还没建完，下一帧再来
            layoutValid = false;
            return;
        }
        styleOf(document, "rb-hud", hudStyle(scale, sizeScale, guiX, guiY, show, compact));

        // 状态点：位置尺寸 + 底色。底色平时由页面 JS 按 state 设置，
        // 但 setAttribute("style", ...) 会整体替换内联样式、连带清掉 JS 写的 background，
        // 所以这里按同一个映射表再写一次（两边同源，谁后写结果都一样）。
        styleOf(document, "rb-dot", "position:absolute;left:" + px(DOT_X, sizeScale) + "px;top:" + px(DOT_Y, sizeScale)
                + "px;width:" + px(DOT, sizeScale) + "px;height:" + px(DOT, sizeScale)
                + "px;border-radius:" + px(DOT / 2.0D, sizeScale) + "px;"
                + "background:" + dotColor(stateKey) + ";");

        styleOf(document, "rb-book", "position:absolute;left:" + px(BOOK_X, sizeScale) + "px;top:" + px(BOOK_Y, sizeScale)
                + "px;width:" + px(BOOK_W, sizeScale) + "px;height:" + px(BOOK_H, sizeScale)
                + "px;line-height:" + px(BOOK_H, sizeScale) + "px;font-size:" + px(BOOK_FS, sizeScale) + "px;");

        styleOf(document, "rb-chap", "position:absolute;left:" + px(CHAP_X, sizeScale) + "px;top:" + px(CHAP_Y, sizeScale)
                + "px;width:" + px(CHAP_W, sizeScale) + "px;height:" + px(CHAP_H, sizeScale)
                + "px;line-height:" + px(CHAP_H, sizeScale) + "px;font-size:" + px(CHAP_FS, sizeScale) + "px;");

        styleOf(document, "rb-pct", "position:absolute;left:" + px(PCT_X, sizeScale) + "px;top:" + px(PCT_Y, sizeScale)
                + "px;width:" + px(PCT_W, sizeScale) + "px;height:" + px(PCT_H, sizeScale)
                + "px;line-height:" + px(PCT_H, sizeScale) + "px;font-size:" + px(PCT_FS, sizeScale) + "px;");

        // 紧凑档把句子区和进度条一起藏掉：它们的内联 top（42 / 90）会落到 46px 高的盒子外面，
        // 即使被裁剪也会把 AUI 的排版区撑大。display 只由 Java 写（JS 不做显示，只做隐藏兜底），
        // 所以这里和 hud.html 的 CSS 默认值必须一致。
        String sentDisplay = compact ? "display:none;" : "display:block;";
        styleOf(document, "rb-sent", "position:absolute;left:" + px(SENT_X, sizeScale) + "px;top:" + px(SENT_Y, sizeScale)
                + "px;width:" + px(SENT_W, sizeScale) + "px;height:" + px(SENT_H, sizeScale)
                + "px;line-height:" + px(SENT_LH, sizeScale) + "px;font-size:" + px(SENT_FS, sizeScale) + "px;"
                + sentDisplay);

        styleOf(document, "rb-bar", "position:absolute;left:" + px(BAR_X, sizeScale) + "px;top:" + px(BAR_Y, sizeScale)
                + "px;width:" + px(BAR_W, sizeScale) + "px;height:" + px(BAR_H, sizeScale) + "px;" + sentDisplay);

        // 填充条：高度在这里写；宽度平时由页面 JS 按千分比设置。
        // 但 setAttribute("style", ...) 是整体替换，会连带清掉 JS 写的 width，
        // 而 JS 只在数据变化时才重写 —— 所以这里必须把当前宽度也一并写上，
        // 两边算出的值同源同算法（round(permille / 10) %），谁后写都一致。
        int fillPercent = (int) Math.round(Math.max(0, Math.min(1000, permille)) / 10.0D);
        styleOf(document, "rb-fill", "height:" + px(BAR_H, sizeScale) + "px;"
                + "width:" + fillPercent + "%;");

        layoutValid = true;
        lastScale = sizeScale;
        lastGuiX = guiX;
        lastGuiY = guiY;
        lastShow = show;
        lastCompact = compact;
        displayIsNone = !show;
    }

    /**
     * HUD 外框的内联样式。
     *
     * @param posScale  viewport 缩放，<b>只用来换算位置</b>（GUI px → CSS px）
     * @param sizeScale viewport 缩放 × 用户放大系数（{@code hudScale}），<b>只用来换算尺寸</b>
     */
    private static String hudStyle(double posScale, double sizeScale, int guiX, int guiY,
                                   boolean show, boolean compact) {
        // 上界只有 posScale 需要 < 100（1 CSS px ≈ 1 物理 px，正常缩放不会到 100）；
        // sizeScale 是 posScale × hudScale（最大 6），所以这里只校验下限，不能再套 100 的上界，
        // 否则 4K 屏（renderScale ≈ 6）× 6 倍会被判成非法值、整个 HUD 缩回 1.0。
        double safePos = posScale > 0.01D && posScale < 100.0D ? posScale : 1.0D;
        double safeSize = sizeScale > 0.01D ? sizeScale : 1.0D;
        int cssX = (int) Math.round(Math.max(0, guiX) / safePos);
        int cssY = (int) Math.round(Math.max(0, guiY) / safePos);
        int designH = compact ? H_COMPACT : H;
        return "position:absolute;left:" + cssX + "px;top:" + cssY + "px;"
                + "width:" + px(W, safeSize) + "px;height:" + px(designH, safeSize) + "px;"
                + "border:" + borderPx(safeSize) + "px solid #1e1e1f;"
                + "border-radius:" + px(RADIUS, safeSize) + "px;"
                + "display:" + (show ? "block" : "none") + ";";
    }

    /**
     * 与 hud.html 里 JS 的 dotColor() 保持同一张映射表（JS 改名必须同步改这里）。
     *
     * <p>状态串由调用方 {@code ReaderBridgeHud.auiStateKey()} 生成：
     * {@code off} / {@code idle} / {@code paused} / {@code ok}。</p>
     */
    private static String dotColor(String stateKey) {
        if ("ok".equals(stateKey)) {
            return "#3c8527"; // green
        }
        if ("paused".equals(stateKey)) {
            return "#f0b92d"; // gold
        }
        if ("idle".equals(stateKey)) {
            return "#b6bac1"; // ink-muted
        }
        return "#b33b31"; // red
    }

    private static void styleOf(Object handle, String id, String css) {
        if (handle != lastStyleDoc) {
            // AUI 重建了文档（换 HUD 风格 / 资源重载 / ensureDocument 重新 create）：
            // 元素全是新对象，缓存里的「已写过」不再成立，必须清空并重写一遍。
            lastStyleCss.clear();
            lastStyleDoc = handle;
        }
        Document document = (Document) handle;
        Element element = document.getElementById(id);
        if (element == null) {
            // 元素还没建出来，不写缓存，下一帧还能补上
            return;
        }
        if (css.equals(lastStyleCss.put(id, css))) {
            // 与上次写入的内容完全一致：跳过。少一次 WeakHashMap 的 modCount 变动，
            // 也就少一个 commitMutableInlineStyles 抛 CME 的机会。
            return;
        }
        element.setAttribute("style", css);
    }

    private static int px(double design, double scale) {
        return Math.max(1, (int) Math.round(design * scale));
    }

    private static int borderPx(double scale) {
        return Math.max(1, (int) Math.round(BORDER * scale));
    }

    // ------------------------------------------------------------------ 数据块

    private static void writeData(Object handle, boolean visible, String stateKey, String book,
                                 String chapter, String sentence, int permille, int cssX, int cssY) {
        Document document = (Document) handle;
        if (!document.isActive()) {
            // render() 里 ensureDocument() 已经保证 active，理论上到不了这里；
            // 但资源包重载/文档被关掉的瞬间确实能进来 —— 明确打 WARN 而不是静默不写。
            lastBody = "";
            quietWarn("文档已 inactive，本帧数据未写入（等待重建）", null);
            return;
        }
        Element data = document.getElementById("rb-hud-data");
        if (data == null) {
            // DOM 未就绪：清掉缓存，下一帧再写。
            // 这条 WARN 是「创建了文档但数据进不去」的直接证据（页面契约变了 / id 被改名）。
            lastBody = "";
            quietWarn("找不到 #rb-hud-data（DOM 未就绪或 hud.html 的元素 id 变了）", null);
            return;
        }
        int pm = Math.max(0, Math.min(1000, permille));
        StringBuilder sb = new StringBuilder(192);
        // 字段表（必须与 hud.html 的注释和 JS 解析顺序逐位一致，共 9 个字段）：
        //   state ␟ book ␟ 章名␞进度 ␟ sentence ␟ permille ␟ cssX ␟ cssY（前面再加 ver / visible）
        sb.append(visible ? 1 : 0).append(SEP);
        sb.append(clean(stateKey, 8)).append(SEP);
        sb.append(clean(book, 64)).append(SEP);
        // 行内主/副文本：章名 ␞ 进度文案
        sb.append(clean(chapter, 64)).append(SUB).append(percentText(pm)).append(SEP);
        sb.append(clean(sentence, 240)).append(SEP);
        sb.append(pm).append(SEP);
        sb.append(cssX).append(SEP);
        // 末尾字段一定是数字：尾随分隔符会被文本规范化吃掉字段（main.html 踩过的坑）
        sb.append(cssY);
        String body = sb.toString();
        if (body.equals(lastBody)) {
            return;
        }
        lastBody = body;
        ver++;
        // setTextContent 内部会按 diff markDirty，不需要额外调 document.markDirty
        data.setTextContent(ver + String.valueOf(SEP) + body);
        // ---- 直写可见文本槽：不依赖页面 JS ----
        // AUI 的 Rhino 里 pull() 的 q()（document.getElementById）拿不到元素，返回 null 后
        // 直接 return，整条 JS 数据通道在本引擎上从未真正生效过，HUD 只能停在 hud.html
        // 写死的静态初始文本（「阅读器 · 空闲」）。原版 HUD 走 graphics 直接绘制，所以一直
        // 正常 —— 这就是「原版正常、AUI 永远空闲」的成因。
        // Java 侧已经算出全部文案与颜色，这里直接把文字灌进四个槽，不再依赖 JS。
        applyText(document, stateKey, book, chapter, sentence, pm);
        logSync(stateKey, book, chapter, pm, body.length());
    }

    /**
     * 把文案直接灌进四个可见文本槽。
     *
     * <p>兜底文案与 {@code hud.html} 的 {@code apply()} 逐条对应：Java 侧给出空值时按
     * 状态回填，保证任何状态下胶囊里都有字，不会出现只有尺寸没有文字的空壳。
     * 两者必须同步修改，否则一旦某天 JS 通道恢复，两边会互相覆盖。</p>
     *
     * @param permille 全书进度千分比（0~1000）
     */
    private static void applyText(Document document, String stateKey, String book,
                                 String chapter, String sentence, int permille) {
        boolean isOk = "ok".equals(stateKey);
        boolean isPaused = "paused".equals(stateKey);
        boolean isIdle = "idle".equals(stateKey);
        // 其余取值（含未连接的 "off"）一律走「断线」文案
        boolean isOff = !isOk && !isPaused && !isIdle;

        String bookText = clean(book, 26);
        if (bookText.isEmpty()) {
            bookText = isOff ? "App 未连接" : "ReaderBridge";
        }

        String chapText = clean(chapter, 14);
        if (chapText.isEmpty()) {
            if (isOff) {
                chapText = "等待连接";
            } else if (isIdle) {
                chapText = "未在朗读";
            } else if (isPaused) {
                chapText = "已暂停";
            } else {
                chapText = "朗读中";
            }
        }

        String pctText = percentText(permille);
        if (pctText.isEmpty()) {
            pctText = (isOk || isPaused) ? "0%" : "--";
        }
        // 暂停只在副文本里加两个字，主视觉靠状态点变金色
        if (isPaused && !"--".equals(pctText)) {
            pctText = pctText + " · 暂停";
        }

        String sentText = clean(sentence, 54);
        if (sentText.isEmpty()) {
            if (isOff) {
                sentText = "打开 ReaderBridge App 后自动同步";
            } else if (isIdle) {
                sentText = "选择一本书开始朗读，HUD 会自动跟随";
            } else if (isPaused) {
                sentText = "已暂停，按播放键继续";
            } else {
                sentText = "\u2026";
            }
        }

        setTextOf(document, "rb-book", bookText);
        setTextOf(document, "rb-chap", chapText);
        setTextOf(document, "rb-pct", pctText);
        setTextOf(document, "rb-sent", sentText);
    }

    private static void setTextOf(Document document, String id, String text) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(text);
        }
    }

    /**
     * 同步日志：<b>最多每 3 秒一条</b>（≈60 tick）。
     *
     * <p>这个频率是刻意的：HUD 每帧都会调 {@code writeData()}，逐帧打日志会把 latest.log
     * 刷成几百兆并拖慢主线程。3 秒一条足够判断「数据有没有进页面、进的是哪一句」，
     * 又不会盖住其它诊断信息。
     *
     * <p>格式固定为（用户会照抄给维护者）：
     * <pre>[readerbridge] AUI HUD 同步 state=ok ver=137 book=《萝莉暴君…》 chap=第210章 轮回者小队 permille=440 bytes=118</pre>
     */
    private static void logSync(String stateKey, String book, String chapter, int permille, int bytes) {
        long now = System.currentTimeMillis();
        if (now - lastSyncLogMs < 3000L) {
            return;
        }
        String key = stateKey + '|' + ver + '|' + permille;
        if (key.equals(lastSyncLogKey)) {
            // 内容完全没动（暂停中/同一句）也没必要每 3 秒重复一行
            return;
        }
        lastSyncLogMs = now;
        lastSyncLogKey = key;
        ReaderBridgeMod.LOGGER.info(
                "[readerbridge] AUI HUD 同步 state={} ver={} book={} chap={} permille={} bytes={}",
                stateKey, ver, book, chapter, permille, bytes);
    }

    private static String percentText(int permille) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", permille / 10.0D);
    }

    /**
     * 清洗一段文本：去掉换行/制表符（页面只有单行文本槽）与两个分隔符，
     * 并做长度截断，保证数据块永远是一行定长可控的字符串。
     */
    private static String clean(String raw, int maxLen) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length() && sb.length() < maxLen; i++) {
            char c = trimmed.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == SEP || c == SUB) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 日志

    private static long lastWarnMs;

    /** 同类错误最多每 10 秒打一行，避免渲染循环里刷屏。 */
    private static void quietWarn(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 10000L) {
            return;
        }
        lastWarnMs = now;
        if (t == null) {
            ReaderBridgeMod.LOGGER.warn("[readerbridge] AUI HUD：{}", what);
        } else {
            ReaderBridgeMod.LOGGER.warn("[readerbridge] AUI HUD：{}（已忽略）: {}", what, t.toString());
        }
    }
}
