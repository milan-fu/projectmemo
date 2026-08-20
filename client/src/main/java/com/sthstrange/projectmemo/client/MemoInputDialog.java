package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * GUI 内文本输入弹窗（标题/描述/数量/玩家名…）。
 * 数量输入支持服务端同款语法：64 / 3*64 / 2*1728+64（服务端二次解析校验）。
 */
public final class MemoInputDialog extends Screen {

    private final Screen backTo;
    private final String heading;
    private final String hint;
    private final String initial;
    private final int maxLength;
    private final boolean allowEmpty;
    private final Consumer<String> onConfirm;
    private final List<UiKit.UiButton> buttons = new ArrayList<>();
    private EditBox input;

    public MemoInputDialog(Screen backTo, String heading, String hint, String initial, int maxLength, Consumer<String> onConfirm) {
        this(backTo, heading, hint, initial, maxLength, false, onConfirm);
    }

    public MemoInputDialog(Screen backTo, String heading, String hint, String initial, int maxLength,
                           boolean allowEmpty, Consumer<String> onConfirm) {
        super(Component.literal(heading));
        this.backTo = backTo;
        this.heading = heading;
        this.hint = hint;
        this.initial = initial == null ? "" : initial;
        this.maxLength = maxLength;
        this.allowEmpty = allowEmpty;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        super.init();
        int panelW = Math.min(300, this.width - 40);
        int panelH = 86;
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        input = new EditBox(this.font, x0 + 10, y0 + 30, panelW - 20, 16, Component.literal("input"));
        input.setMaxLength(maxLength);
        input.setValue(initial);
        addRenderableWidget(input);
        setFocused(input);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.21.11: renderBackground 会重复应用帧模糊导致崩溃 -> 自绘深色遮罩
        g.fill(0, 0, this.width, this.height, 0xD0080810);
        int panelW = Math.min(300, this.width - 40);
        int panelH = 86;
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        g.drawString(this.font, heading, x0 + 10, y0 + 10, UiKit.TEXT, false);
        if (hint != null && !hint.isEmpty()) {
            g.drawString(this.font, UiKit.truncPx(this.font, hint, panelW - 20), x0 + 10, y0 + 52, UiKit.DIM, false);
        }
        input.render(g, mouseX, mouseY, partialTick);

        buttons.clear();
        UiKit.UiButton ok = new UiKit.UiButton(x0 + panelW - 108, y0 + panelH - 24, 46, 16, "确定", this::confirm);
        UiKit.UiButton cancel = new UiKit.UiButton(x0 + panelW - 56, y0 + panelH - 24, 46, 16, "取消", this::onClose);
        buttons.add(ok);
        buttons.add(cancel);
        for (UiKit.UiButton b : buttons) UiKit.button(g, this.font, b, mouseX, mouseY);
        MemoToast.render(g);
    }

    private void confirm() {
        String value = input.getValue().trim();
        if (value.isEmpty() && !allowEmpty) {
            MemoToast.push("输入不能为空", MemoToast.RED);
            return;
        }
        Minecraft.getInstance().setScreen(backTo);
        onConfirm.accept(value);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(backTo);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(event);
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
    public boolean isPauseScreen() {
        return false;
    }
}
