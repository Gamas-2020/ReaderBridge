package com.feiyu.readerbridge.client.gui;

/**
 * 阅读面板的配色皮肤：深色 / 浅色 / 护眼三套。
 *
 * <p>为什么单独一层而不是直接改 {@link UiTheme}：{@code UiTheme} 还被
 * {@code ReaderBridgeHud} 使用（HUD 字幕），面板换肤不该连带把 HUD 的对比度也换掉。
 * 所以这里只给「面板自己」提供色板，绘制入口仍复用 {@link UiTheme#fillRounded} /
 * {@link UiTheme#strokeRounded} / {@link UiTheme#progressBar} 这些与颜色无关的形状工具。
 *
 * <p>深色皮肤直接沿用 {@link UiTheme} 的既有常量，保证默认观感与之前逐像素一致；
 * 浅色与护眼是本轮新增的取值。所有色值都是 ARGB。
 */
final class PanelSkin {

    /** 主题编号：与 {@code PanelPrefs.theme} 持久化值一致，不要随意调序。 */
    static final int DARK = 0;
    static final int LIGHT = 1;
    static final int EYE_CARE = 2;
    static final int COUNT = 3;

    /** 全屏遮罩（三套主题共用，黑色半透明）。 */
    final int scrim;
    /** 面板主底。 */
    final int panel;
    /** 内容区（body）底。 */
    final int bodyBg;
    /** 卡片底（设置卡片、弹层内容块）。 */
    final int cardBg;
    /** 最弱一档的斑马底。 */
    final int cardFaint;
    /** 分割线 / 描边。 */
    final int stroke;
    /** 悬停高亮。 */
    final int hover;
    /** 选中态（列表当前项、当前音色底）。 */
    final int selected;
    /** 强调色（进度、当前章标、按钮）。 */
    final int accent;
    /** 强调色的低透明度版本（正文当前句高亮）。 */
    final int accentDim;
    final int text;
    final int textDim;
    final int textFaint;
    /** 正文文字色。深色=白，浅色=近黑，护眼=墨绿。 */
    final int readerText;
    final int ok;
    final int error;

    /** 主题编号（{@link #DARK} / {@link #LIGHT} / {@link #EYE_CARE}）。 */
    final int theme;
    /** 中文名，直接进设置界面。 */
    final String label;

    private PanelSkin(int theme, String label, int panel, int bodyBg, int cardBg,
                      int stroke, int hover, int selected, int accent,
                      int text, int textDim, int textFaint, int readerText,
                      int ok, int error) {
        this.theme = theme;
        this.label = label;
        this.scrim = 0x80000000;
        this.panel = panel;
        this.bodyBg = bodyBg;
        this.cardBg = cardBg;
        // 斑马底统一由卡片底派生：换主题时不必再逐套调一个几乎看不见的 alpha
        this.cardFaint = (cardBg & 0x00FFFFFF) | 0x0A000000;
        this.stroke = stroke;
        this.hover = hover;
        this.selected = selected;
        this.accent = accent;
        this.accentDim = (accent & 0x00FFFFFF) | 0x66000000;
        this.text = text;
        this.textDim = textDim;
        this.textFaint = textFaint;
        this.readerText = readerText;
        this.ok = ok;
        this.error = error;
    }

    /** 深色（默认，等价于原 UiTheme 观感）。 */
    static final PanelSkin DARK_SKIN = new PanelSkin(DARK, "深色",
            UiTheme.PANEL, UiTheme.CARD_FAINT, UiTheme.CARD_SOFT,
            UiTheme.STROKE, UiTheme.HOVER, UiTheme.SELECTED, UiTheme.ACCENT,
            UiTheme.TEXT, UiTheme.TEXT_DIM, UiTheme.TEXT_FAINT, UiTheme.TEXT,
            UiTheme.OK, UiTheme.ERROR);

    /** 浅色（纸白底 + 近黑字）。 */
    static final PanelSkin LIGHT_SKIN = new PanelSkin(LIGHT, "浅色",
            0xF5F7F8FA, 0x0A000000, 0x14000000,
            0x33000000, 0x1A000000, 0x1F1A73E8, 0xFFD93025,
            0xFF1B1C1E, 0xFF5F6368, 0xFF8A9098, 0xFF1B1C1E,
            0xFF1E8E3E, 0xFFC5221F);

    /** 护眼（豆沙绿底 + 墨绿字）。 */
    static final PanelSkin EYE_SKIN = new PanelSkin(EYE_CARE, "护眼",
            0xF4EEF5EC, 0x0F1B5E20, 0x142E4A38,
            0x332E4A38, 0x1A2E4A38, 0x222E7D4F, 0xFF2E7D4F,
            0xFF24342A, 0xFF4C6255, 0xFF77897C, 0xFF1F3327,
            0xFF3B8C56, 0xFFB3402F);

    /** 编号 → 皮肤；非法值一律回落到深色。 */
    static PanelSkin of(int theme) {
        switch (theme) {
            case LIGHT:
                return LIGHT_SKIN;
            case EYE_CARE:
                return EYE_SKIN;
            case DARK:
            default:
                return DARK_SKIN;
        }
    }

    /** 编号 → 中文名（设置界面的主题 chip 用）。 */
    static String labelOf(int theme) {
        return of(theme).label;
    }
}
