package com.feiyu.readerbridge.client.ui;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.client.gui.LocalReaderScreen;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.ModList;

/**
 * 晴雪UI（ApricityUI）面板的门面。
 *
 * <p><b>本类刻意不含任何 {@code com.sighs.apricityui.*} 类型</b> —— 一个都不能出现在
 * 字段、方法签名、{@code static {}} 块或泛型里。理由：晴雪UI 是<b>可选</b>前置，
 * 没装的时候 JVM 一旦在验证 {@code AuiBridge} 时看到无法解析的符号引用，就会抛
 * {@code NoClassDefFoundError} / {@code VerifyError}，把整个客户端事件处理拖垮。
 *
 * <p>真正的 AUI 调用全部关在 {@link AuiScreens} 里。JVM 对类加载是惰性的：
 * {@link #available()} 为 false 时永不触碰 {@code AuiScreens}，它就不会被加载。
 *
 * <p>面板状态也只用原版 {@link Screen} 持有，因此 {@link #isOurs(Screen)}
 * 在主线程自动暂停判定里是安全的（不会强制加载 AUI）。
 */
public final class AuiBridge {

    /** 晴雪UI 的 modId。与 mods.toml 里的可选依赖保持同一个字面量。 */
    public static final String AUI_MOD_ID = "apricityui";

    /** {@code [hud] uiMode} 的三个取值，与 {@code ReaderBridgeConfig.UI_MODE} 的 defineInList 候选一致。 */
    public static final String MODE_AUTO = "auto";
    /** 明确使用晴雪UI 界面（未安装时回退原版并打日志）。 */
    public static final String MODE_APRICITY = "apricity";
    /** 强制原版界面（即使装了晴雪UI 也不接管）。 */
    public static final String MODE_VANILLA = "vanilla";

    /** 当前由本桥打开的 AUI 界面；原版 Screen 类型，不引入 AUI 依赖。 */
    private static Screen active;
    /** 打开时记下的 Screen 实现类名：AUI 会替换实例，引用比对不够用。 */
    private static String activeClass;
    /** 晴雪UI 的包前缀：只用来做类名字符串判断，不触发类加载。 */
    private static final String AUI_PACKAGE_PREFIX = "com.sighs.apricityui";

    private AuiBridge() {
    }

    /** 晴雪UI 是否已安装。用 ModList 判断，不触发任何类加载。 */
    public static boolean available() {
        return ModList.get().isLoaded(AUI_MOD_ID);
    }

    /**
     * 打开 AUI 阅读面板。
     *
     * @return 成功返回界面实例，应当在主线程里交给 {@code Minecraft#setScreen}；
     *         晴雪UI 未安装、或页面加载抛异常时返回 {@code null}，
     *         调用方据此回退到原版 {@code LocalReaderScreen}。
     */
    public static Screen open() {
        String mode = ReaderBridgeConfig.uiMode();
        if (MODE_VANILLA.equals(mode)) {
            // 玩家明确选了原版界面：直接返回 null，由调用方 new LocalReaderScreen()。
            // 注意这里<b>不</b>触碰 AuiScreens —— 晴雪UI 没装时也不能让 AUI 类型进类加载。
            return null;
        }
        if (!available()) {
            if (MODE_APRICITY.equals(mode)) {
                // 明确指定了晴雪UI 界面却没装：仍然回退原版，但要在日志里说清楚为什么，
                // 否则玩家会以为「设置里选了晴雪UI 却没效果」是配置没写进去。
                ReaderBridgeMod.LOGGER.warn("[readerbridge] 已配置晴雪UI 界面，但未检测到 apricityui，回退原版");
            }
            return null;
        }
        try {
            Screen screen = AuiScreens.open();
            active = screen;
            activeClass = screen == null ? null : screen.getClass().getName();
            return screen;
        } catch (Throwable error) {
            // 宁可回退原版界面也不能让热键抛异常 —— 那会直接崩客户端。
            active = null;
            activeClass = null;
            ReaderBridgeMod.LOGGER.warn("[readerbridge] 晴雪UI 面板打开失败，回退原版界面: {}", error.toString());
            return null;
        }
    }

    /**
     * 打开阅读面板时要不要暂停游戏：<b>单人存档 true，联机 false</b>。
     *
     * <p>{@code Minecraft#hasSingleplayerServer()} 正好是这个语义：
     * 集成服务器（单人存档，含「对局域网开放」）为 true，连别人的服务器 / Realms 为 false。
     * 联机下把 {@code setPauseGame(true)} 只会冻住本地玩家，服务端照跑 —— 白挨打，
     * 所以联机一律不暂停。两套界面（AUI 与 {@link LocalReaderScreen}）共用这一个判定，
     * 避免「换界面之后暂停行为变了」。
     *
     * <p>方法体包 try：类初始化早期 / 未进世界时 {@code getInstance()} 可能给出意外状态，
     * 这时保守地「不暂停」，绝不因为判定失败而卡住界面。
     */
    public static boolean shouldPauseGame() {
        try {
            return Minecraft.getInstance().hasSingleplayerServer();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 该界面是否由本桥打开（用于主线程自动暂停把「自己的面板」排除掉）。 */
    public static boolean isOurs(Screen screen) {
        if (screen == null) {
            return false;
        }
        if (screen == active) {
            return true;
        }
        // 晴雪UI 在 setScreen 之后可能把我们创建的 ApricityScreen 再包装/替换成另一个
        // Screen 实例（换 document、资源重载都会走这条路），只比对引用会把自家面板
        // 判成「别人的界面」，于是自动暂停把 AUI 面板当成「开着 GUI」而停下朗读。
        // AUI 是可选前置，这里只比对类名字符串，不写死 com.sighs.apricityui 的任何类型。
        String name = activeClass;
        return name != null && name.equals(screen.getClass().getName());
    }

    /**
     * 阅读面板是否处于打开状态。
     *
     * <p>自动暂停判定用它兜底：只要面板还开着（本桥持有 active），就不该把
     * 「面板开着」当成「玩家开着别的 GUI」。</p>
     */
    public static boolean isPanelOpen() {
        return active != null;
    }

    /** 界面已关闭：断开引用，避免持有已卸载的 Document。 */
    public static void close() {
        active = null;
        activeClass = null;
        // 只在装了晴雪UI 时触碰 AuiScreens，否则会把 AUI 类型提前拽进类加载。
        if (available()) {
            AuiScreens.clear();
        }
    }

    /**
     * 每客户端 tick 调用：面板开着的时候把 ReaderEngine 的状态推进页面。
     *
     * <p>只在「当前没有任何界面」时才认定面板已经关掉。旧实现要求
     * {@code mc.screen == active}，而 AUI 会替换 Screen 实例，于是面板明明开着、
     * 第一个 tick 就把状态清空 → 之后所有刷新全部提前返回 → 页面数据流冻结，
     * 书架与正文页看到的都是空白（只有页脚那几行静态文字）。</p>
     */
    public static void tick() {
        if (active == null) {
            return;
        }
        try {
            Screen current = Minecraft.getInstance().screen;
            if (current == null) {
                // 面板已经不在了（关界面/退出世界）。
                close();
                return;
            }
            if (!isOurs(current) && !current.getClass().getName().startsWith(AUI_PACKAGE_PREFIX)) {
                // 换成了别的界面（ESC 暂停菜单、背包、聊天…）：面板不在前台了，收摊。
                // 注意这里只比对类名字符串：AUI 是可选前置，不能写死它的类型。
                close();
                return;
            }
            AuiScreens.refresh();
        } catch (Throwable error) {
            // 单帧刷新失败不该刷屏，也不该拖垮客户端 tick：只记一次并关掉面板状态。
            ReaderBridgeMod.LOGGER.warn("[readerbridge] 晴雪UI 面板刷新失败: {}", error.toString());
            close();
        }
    }
}
