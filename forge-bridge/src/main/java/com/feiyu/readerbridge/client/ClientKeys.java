package com.feiyu.readerbridge.client;

import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

/**
 * 客户端按键绑定。
 *
 * <p>键位从配置（{@code keys.*}）读取，读取失败时回退到默认值：
 * <b>M</b> 打开阅读面板、<b>G</b> 播放/暂停、<b>B</b> 下一章、<b>N</b> 上一章、
 * <b>J</b> 切换 HUD、<b>H</b> 拖拽编辑 HUD 位置。</p>
 *
 * <p>注意：Forge 不支持运行期重新注册 {@link KeyMapping}，因此改配置文件里的按键名
 * <b>需要重启游戏</b>才生效；游戏内「控制」界面改键位不受影响（原版自己会持久化）。</p>
 */
public final class ClientKeys {

    /** 按键类别（会显示在「控制」界面的分组标题上）。 */
    public static final String CATEGORY = "key.categories.readerbridge";

    /** 打开阅读面板。 */
    public static final KeyMapping OPEN_PANEL = new KeyMapping(
            "key.readerbridge.open_panel",
            KeyConflictContext.IN_GAME,
            key(ReaderBridgeConfig.keyOpenPanel(), InputConstants.KEY_M),
            CATEGORY);

    /** 播放 / 暂停。 */
    public static final KeyMapping PLAY_PAUSE = new KeyMapping(
            "key.readerbridge.play_pause",
            KeyConflictContext.IN_GAME,
            key(ReaderBridgeConfig.keyPlayPause(), InputConstants.KEY_G),
            CATEGORY);

    /** 下一章。 */
    public static final KeyMapping NEXT_CHAPTER = new KeyMapping(
            "key.readerbridge.next_chapter",
            KeyConflictContext.IN_GAME,
            key(ReaderBridgeConfig.keyNextChapter(), InputConstants.KEY_B),
            CATEGORY);

    /** 上一章。 */
    public static final KeyMapping PREV_CHAPTER = new KeyMapping(
            "key.readerbridge.prev_chapter",
            KeyConflictContext.IN_GAME,
            key(ReaderBridgeConfig.keyPrevChapter(), InputConstants.KEY_N),
            CATEGORY);

    /** 切换 HUD 显示/隐藏。 */
    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.readerbridge.toggle_hud",
            KeyConflictContext.UNIVERSAL,
            key(ReaderBridgeConfig.keyToggleHud(), InputConstants.KEY_J),
            CATEGORY);

    /** 编辑 HUD 位置（进入拖拽界面）。UNIVERSAL：标题界面也能调，不要求进世界。 */
    public static final KeyMapping HUD_POSITION = new KeyMapping(
            "key.readerbridge.hud_position",
            KeyConflictContext.UNIVERSAL,
            key(ReaderBridgeConfig.keyHudPosition(), InputConstants.KEY_H),
            CATEGORY);

    private ClientKeys() {
    }

    /**
     * 把英文键名（{@code M} / {@code F9} / {@code SPACE} …）解析成 {@link InputConstants.Key}。
     *
     * <p>解析失败（拼错、空值、未知键名）时回退到 {@code fallbackCode}，绝不抛出异常。</p>
     */
    private static InputConstants.Key key(String name, int fallbackCode) {
        try {
            if (name != null && !name.isBlank()) {
                InputConstants.Key parsed =
                        InputConstants.getKey("key.keyboard." + name.trim().toLowerCase(Locale.ROOT));
                if (parsed != null && parsed.getValue() != InputConstants.UNKNOWN.getValue()) {
                    return parsed;
                }
            }
        } catch (Throwable ignored) {
            // 未知键名走回退分支
        }
        return InputConstants.Type.KEYSYM.getOrCreate(fallbackCode);
    }
}
