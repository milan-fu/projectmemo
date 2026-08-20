package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.Consumer;

/**
 * 长文本编辑屏：大框多行编辑（自动换行 + 上下滚动），用于描述/搭建说明/子任务说明。
 * 允许保存空内容（=清除）。
 */
public final class MemoTextScreen extends MemoScreenBase {

    private final String heading;
    private final String initial;
    private final Consumer<String> onConfirm;
    private final net.minecraft.client.gui.screens.Screen backTo;
    private MemoMultilineEdit editor;

    public MemoTextScreen(String heading, String initial, net.minecraft.client.gui.screens.Screen backTo,
                          Consumer<String> onConfirm) {
        super(heading);
        this.heading = heading;
        this.initial = initial == null ? "" : initial;
        this.backTo = backTo;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void buildWidgets() {
        int panelW = Math.min(400, this.width - 20);
        int panelH = Math.min(260, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        editor = new MemoMultilineEdit(this.font, x0 + 12, y0 + 26, panelW - 24, panelH - 62);
        editor.setText(initial);
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = Math.min(400, this.width - 20);
        int panelH = Math.min(260, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        g.drawString(this.font, heading, x0 + 10, y0 + 8, UiKit.TEXT, false);
        g.drawString(this.font, L10n.get("projectmemo.textscreen.hint"), x0 + 10, y0 + 16, UiKit.FAINT, false);
        editor.render(g, mouseX, mouseY);

        int by = y0 + panelH - 24;
        UiKit.UiButton save = new UiKit.UiButton(x0 + 10, by, 52, 16, L10n.get("projectmemo.common.save"), () -> {
            onConfirm.accept(editor.getText().trim());
            this.minecraft.setScreen(backTo);
        });
        uiButtons.add(save);
        UiKit.UiButton cancel = new UiKit.UiButton(x0 + panelW - 52, by, 44, 16, L10n.get("projectmemo.common.back"), () ->
                this.minecraft.setScreen(backTo));
        uiButtons.add(cancel);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256 && editor.focused) { // ESC 在编辑器内先失焦，再按一次退出
            editor.focused = false;
            return true;
        }
        if (editor.focused && editor.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (editor.focused && event.isAllowedChatCharacter() && editor.charTyped(event.codepoint())) return true;
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (editor.mouseClicked(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (editor != null && mouseX >= editor.x && mouseX <= editor.x + editor.w
                && mouseY >= editor.y && mouseY <= editor.y + editor.h) {
            editor.mouseScrolled(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
