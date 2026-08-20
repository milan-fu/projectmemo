package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** 确认弹窗；danger=true 用红色（删除/归档类操作）。 */
public final class MemoConfirmDialog extends Screen {

    private final Screen backTo;
    private final String heading;
    private final String message;
    private final boolean danger;
    private final Runnable onConfirm;
    private final List<UiKit.UiButton> buttons = new ArrayList<>();

    public MemoConfirmDialog(Screen backTo, String heading, String message, boolean danger, Runnable onConfirm) {
        super(Component.literal(heading));
        this.backTo = backTo;
        this.heading = heading;
        this.message = message;
        this.danger = danger;
        this.onConfirm = onConfirm;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.21.11: renderBackground 会重复应用帧模糊导致崩溃 -> 自绘深色遮罩
        g.fill(0, 0, this.width, this.height, 0xD0080810);
        int panelW = Math.min(300, this.width - 40);
        int panelH = 80;
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        g.drawString(this.font, heading, x0 + 10, y0 + 10, danger ? UiKit.RED : UiKit.TEXT, false);
        // 简单换行
        List<net.minecraft.util.FormattedCharSequence> lines =
                this.font.split(Component.literal(message == null ? "" : message), panelW - 20);
        int ly = y0 + 26;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(this.font, lines.get(i), x0 + 10, ly, UiKit.DIM, false);
            ly += 11;
        }

        buttons.clear();
        UiKit.UiButton ok = new UiKit.UiButton(x0 + panelW - 108, y0 + panelH - 24, 46, 16, "确认", () -> {
            Minecraft.getInstance().setScreen(backTo);
            onConfirm.run();
        });
        if (danger) ok.danger();
        UiKit.UiButton cancel = new UiKit.UiButton(x0 + panelW - 56, y0 + panelH - 24, 46, 16, "取消", this::onClose);
        buttons.add(ok);
        buttons.add(cancel);
        for (UiKit.UiButton b : buttons) UiKit.button(g, this.font, b, mouseX, mouseY);
        MemoToast.render(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() == 0) {
            double mx = event.x(), my = event.y();
            for (UiKit.UiButton b : buttons) {
                if (b.enabled && b.onClick != null && UiKit.inRect(mx, my, b.x, b.y, b.w, b.h)) {
                    b.onClick.run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(backTo);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
