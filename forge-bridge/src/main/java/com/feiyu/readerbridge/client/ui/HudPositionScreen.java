package com.feiyu.readerbridge.client.ui;

import com.feiyu.readerbridge.ReaderBridgeMod;
import com.feiyu.readerbridge.config.ReaderBridgeConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD 位置编辑器（H 键进入）。
 *
 * <p>为什么做成 {@link Screen} 而不是在游戏内直接接管鼠标：Minecraft 在游戏内会把鼠标指针锁住
 * （{@code MouseHandler} 抓取光标），没有指针就无法"拖拽"。打开一个 Screen 会释放光标，
 * 这样拖拽才稳定可靠。拖动 / 松手后立即把坐标写回配置并保存到磁盘。</p>
 */
public class HudPositionScreen extends Screen {

    /** HUD 方框的估算尺寸（真实大小随文字长度变化，这里给一个够用的空框）。 */
    private static final int HUD_W = 240;
    private static final int HUD_H = 96;

    private final Screen parent;

    private int boxX;
    private int boxY;
    private int dragOffsetX;
    private int dragOffsetY;
    private boolean dragging;
    private String hint = "拖动方框到目标位置，松开鼠标即保存";

    public HudPositionScreen(Screen parent) {
        super(Component.literal("阅读 HUD 位置"));
        this.parent = parent;
        this.boxX = ReaderBridgeConfig.hudX();
        this.boxY = ReaderBridgeConfig.hudY();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected void init() {
        super.init();
        // 屏幕尺寸变化后保证方框仍在可视区域内
        boxX = clampX(boxX);
        boxY = clampY(boxY);

        int bottom = this.height - 36;
        addRenderableWidget(Button.builder(Component.literal("保存并关闭"), b -> {
            savePosition();
            this.minecraft.setScreen(parent);
        }).bounds(this.width / 2 - 102, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重置到左上角"), b -> {
            boxX = 6;
            boxY = 6;
            savePosition();
        }).bounds(this.width / 2 + 2, bottom, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, "阅读 HUD 位置（X=" + boxX + ", Y=" + boxY + "）",
                this.width / 2, 16, 0xFFD700);
        graphics.drawCenteredString(this.font, hint, this.width / 2, 30, 0xAAAAAA);
        graphics.drawCenteredString(this.font, "ESC 取消（不保存）", this.width / 2, 44, 0x777777);

        // 方框：模拟 HUD 的半透明底板
        int color = dragging ? 0xCC2A6BD8 : 0x99264F8A;
        graphics.fill(boxX - 4, boxY - 4, boxX - 4 + HUD_W, boxY - 4 + HUD_H, color);
        graphics.drawString(this.font, "阅读 HUD（示例区域）", boxX + 4, boxY, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "拖住这块区域移动", boxX + 4, boxY + 12, 0xFFDDDDDD, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideBox(mouseX, mouseY)) {
            dragging = true;
            dragOffsetX = (int) (mouseX - boxX);
            dragOffsetY = (int) (mouseY - boxY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            boxX = clampX((int) (mouseX - dragOffsetX));
            boxY = clampY((int) (mouseY - dragOffsetY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            savePosition();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // ESC：不保存，直接回到上一个界面（游戏内 parent 为 null 即恢复游戏）。
        this.minecraft.setScreen(parent);
    }

    private void savePosition() {
        ReaderBridgeConfig.setHudPosition(boxX, boxY);
        boolean saved = ReaderBridgeConfig.save();
        hint = saved
                ? "已保存：X=" + boxX + ", Y=" + boxY
                : "已写入内存，但保存到磁盘失败（配置未加载？）";
        ReaderBridgeMod.LOGGER.info("[readerbridge] HUD 位置更新为 ({}, {})，保存{}",
                boxX, boxY, saved ? "成功" : "失败");
    }

    private boolean insideBox(double mouseX, double mouseY) {
        return mouseX >= boxX - 4 && mouseX <= boxX - 4 + HUD_W
                && mouseY >= boxY - 4 && mouseY <= boxY - 4 + HUD_H;
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(Math.max(0, this.width - HUD_W), x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(Math.max(0, this.height - HUD_H - 40), y));
    }
}
