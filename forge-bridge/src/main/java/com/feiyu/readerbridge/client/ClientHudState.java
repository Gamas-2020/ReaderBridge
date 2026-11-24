package com.feiyu.readerbridge.client;

/**
 * HUD 可见性状态机。
 *
 * <p>这里有两层开关，语义刻意分开：</p>
 * <ul>
 *   <li><b>配置</b> {@code hud.showHud}（toml）是<b>总开关</b>：为 false 时永远不画，
 *       热键也翻不出来；</li>
 *   <li><b>本类</b>的热键切换是<b>本次会话内的临时开关</b>，不写回配置文件。</li>
 * </ul>
 *
 * <p>早期的实现只在客户端启动时读一次配置（{@code init(showHud())}），之后渲染只查
 * 本类的布尔值，于是「改 toml 里 showHud = false 但 HUD 照样画」「在设置页/AUI 面板里
 * 点开关也没反应」—— 现在渲染每帧调 {@link #syncConfig(boolean)}，配置一旦变化就把
 * 临时开关重置成新配置值，配置为 false 时直接判定不可见。</p>
 */
public final class ClientHudState {

    private static volatile boolean visible = true;
    private static volatile boolean initialized = false;

    /** 上一次看到的配置值；null 表示还没同步过。用来识别「配置变了」。 */
    private static volatile Boolean lastConfig;
    private static final Object LOCK = new Object();

    private ClientHudState() {
    }

    /** 客户端初始化时调用一次。 */
    public static void init(boolean defaultVisible) {
        synchronized (LOCK) {
            if (!initialized) {
                visible = defaultVisible;
                initialized = true;
            }
            lastConfig = defaultVisible;
        }
    }

    /**
     * 把当前配置值同步进来（HUD 每帧调用，纯内存比较，开销可忽略）。
     *
     * <p>只在<b>配置值发生变化</b>时才重置临时开关：玩家按热键关掉 HUD 之后，
     * 配置并没有变，下一次同步不会把 HUD 又打开。</p>
     */
    public static void syncConfig(boolean configured) {
        synchronized (LOCK) {
            Boolean previous = lastConfig;
            if (previous == null || previous.booleanValue() != configured) {
                lastConfig = Boolean.valueOf(configured);
                visible = configured;
                initialized = true;
            }
        }
    }

    /** 当前是否应该显示 HUD（不含配置总开关）。 */
    public static boolean visible() {
        Boolean configured = lastConfig;
        if (configured != null && !configured.booleanValue()) {
            return false;
        }
        return initialized ? visible : true;
    }

    /** 配置总开关 + 临时开关的最终结论。渲染只该看这个。 */
    public static boolean effectiveVisible(boolean configured) {
        syncConfig(configured);
        return visible();
    }

    /** 切换可见性，返回切换后的状态。 */
    public static boolean toggle() {
        synchronized (LOCK) {
            visible = !visible();
            initialized = true;
            return visible;
        }
    }

    /** 明确设置可见性（游戏内设置界面、HUD 位置界面用）。 */
    public static void setVisible(boolean value) {
        synchronized (LOCK) {
            visible = value;
            initialized = true;
        }
    }
}
