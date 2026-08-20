package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 备忘录屏幕基类：深色背景 + 每帧重建的自绘按钮（masa 风格）+ EditBox 手动渲染。
 * 数据刷新：render 里比对 MemoClientState.dataVersion，变更即回调 onDataChanged。
 * 1.21.11 输入 API：MouseButtonEvent/KeyEvent 事件对象。
 */
public abstract class MemoScreenBase extends Screen {

    protected final List<UiKit.UiButton> uiButtons = new ArrayList<>();
    protected final List<EditBox> editBoxes = new ArrayList<>();
    private long seenVersion = -1;

    protected MemoScreenBase(String title) {
        super(Component.literal(title));
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets(); // 窗口缩放重建时防控件重复
        editBoxes.clear();
        buildWidgets();
        seenVersion = MemoClientState.dataVersion();
    }

    /** 子类创建持久控件（EditBox 用 addEditBox 注册） */
    protected void buildWidgets() { }

    protected <T extends EditBox> T addEditBox(T box) {
        addRenderableWidget(box);   // 注册进 children，键鼠事件走原版分发
        editBoxes.add(box);         // 渲染由本类手动控制（避免被面板盖住）
        return box;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.21.11: renderBackground repeats per-frame blur (crash) -> draw dark overlay
        g.fill(0, 0, this.width, this.height, 0xB0101018);
        long version = MemoClientState.dataVersion();
        if (seenVersion != version) {
            seenVersion = version;
            onDataChanged();
        }
        uiButtons.clear();
        layoutAndDraw(g, mouseX, mouseY);
        for (UiKit.UiButton b : uiButtons) {
            UiKit.button(g, this.font, b, mouseX, mouseY);
        }
        for (EditBox box : editBoxes) {
            if (box.isVisible()) box.render(g, mouseX, mouseY, partialTick);
        }
        drawOverlay(g, mouseX, mouseY);
        drawHoverTooltip(g, mouseX, mouseY);
        MemoToast.render(g); // HUD 层的 toast 会被界面面板盖住，这里在顶层再画一次
    }

    /** 数据（init/sync）到达后回调：子类可重置选中/滚动等 */
    protected void onDataChanged() { }

    /** 覆盖层：在所有按钮之上绘制（下拉菜单等），tooltip 之下 */
    protected void drawOverlay(GuiGraphics g, int mouseX, int mouseY) { }

    /** 子类布局+绘制，并把自绘按钮登记进 uiButtons */
    protected abstract void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY);

    /** 手动 tooltip（1.21.11 renderTooltip 需要新管道参数，自绘更稳） */
    protected void drawHoverTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (UiKit.UiButton b : uiButtons) {
            if (b.tooltip != null && !b.tooltip.isEmpty() && b.enabled
                    && UiKit.inRect(mouseX, mouseY, b.x, b.y, b.w, b.h)) {
                int w = this.font.width(b.tooltip) + 10;
                int tx = Math.min(mouseX + 8, this.width - w - 4);
                int ty = Math.min(mouseY + 12, this.height - 16);
                g.fill(tx - 2, ty - 2, tx + w + 2, ty + 12, 0xFF000000);
                g.fill(tx - 1, ty - 1, tx + w + 1, ty + 11, 0xF0181820);
                g.drawString(this.font, b.tooltip, tx + 3, ty + 1, 0xFFE0E0E0, false);
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true; // EditBox 焦点等
        if (event.button() == 0) {
            double mx = event.x(), my = event.y();
            for (UiKit.UiButton b : uiButtons) {
                if (b.enabled && b.onClick != null && UiKit.inRect(mx, my, b.x, b.y, b.w, b.h)) {
                    b.onClick.run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (super.keyPressed(event)) return true;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { // 兜底
            onClose();
            return true;
        }
        return false;
    }

    /** 滚动位置夹取 */
    protected double clampScroll(double scroll, double maxScroll) {
        return Math.max(0, Math.min(Math.max(0, maxScroll), scroll));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
