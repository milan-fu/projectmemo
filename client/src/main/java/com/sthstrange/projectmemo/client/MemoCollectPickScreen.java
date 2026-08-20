package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「添加材料收集」选择屏：批量勾选材料条目 + 自取任务名 → 确认后创建收集任务并自动认领。
 * 列表只含未完成且无进行中收集任务的材料，按缺口从大到小排序。
 */
public final class MemoCollectPickScreen extends MemoScreenBase {

    private static final int ROW_H = 22;
    private final int projectId;
    private final Set<Integer> selected = new HashSet<>();
    private double scroll;
    private int listTop, listBottom, listLeft, listRight;
    private List<MemoData.MaterialRow> pickable = new ArrayList<>();
    private EditBox nameBox;

    public MemoCollectPickScreen(int projectId) {
        super(L10n.get("projectmemo.collect.title"));
        this.projectId = projectId;
    }

    @Override
    protected void buildWidgets() {
        int panelW = Math.min(400, this.width - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - Math.min(320, this.height - 20)) / 2;
        nameBox = addEditBox(new EditBox(this.font, x0 + 60, y0 + 24, panelW - 72, 16, Component.literal(L10n.get("projectmemo.collect.nameField"))));
        nameBox.setMaxLength(40);
        nameBox.setFocused(true);
    }

    /** 可认领的材料：未完成（delivered<need）且无进行中的收集任务 */
    private void refresh() {
        MemoData data = MemoClientState.data();
        Set<Integer> taken = new HashSet<>();
        for (MemoData.Task t : data.tasksOf(projectId)) {
            if ("collect".equals(t.type) && !"done".equals(t.status)) taken.addAll(t.materialIds);
        }
        pickable = new ArrayList<>();
        for (MemoData.MaterialRow m : data.materialsOf(projectId)) {
            if (m.delivered < m.need && !taken.contains(m.id)) pickable.add(m);
        }
        pickable.sort((a, b) -> Long.compare(Math.max(0, b.need - b.delivered), Math.max(0, a.need - a.delivered)));
        selected.removeIf(id -> pickable.stream().noneMatch(m -> m.id == id));
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        refresh();
        int panelW = Math.min(400, this.width - 20);
        int panelH = Math.min(320, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        g.drawString(this.font, L10n.get("projectmemo.collect.hint"), x0 + 10, y0 + 8, UiKit.TEXT, false);
        g.drawString(this.font, L10n.get("projectmemo.collect.nameLabel"), x0 + 10, y0 + 28, UiKit.DIM, false);

        listLeft = x0 + 8;
        listRight = x0 + panelW - 8;
        listTop = y0 + 48;
        listBottom = y0 + panelH - 30;
        int contentH = pickable.size() * ROW_H;
        double maxScroll = Math.max(0, contentH - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);

        if (pickable.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.collect.noneLeft"), x0 + 12, listTop + 8, UiKit.FAINT, false);
        }

        g.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < pickable.size(); i++) {
            int rowY = listTop + i * ROW_H - (int) scroll;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            MemoData.MaterialRow m = pickable.get(i);
            boolean sel = selected.contains(m.id);
            boolean hover = UiKit.inRect(mouseX, mouseY, listLeft, rowY, listRight - listLeft, ROW_H);
            if (sel) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x30FFFFA0);
            else if (hover) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x40FFFFFF);
            else if (i % 2 == 0) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x14FFFFFF);

            int x = listLeft + 6;
            g.drawString(this.font, sel ? "☑" : "☐", x, rowY + 6, sel ? UiKit.GOLD : UiKit.FAINT, false);
            x += 14;
            String zh = MemoItemPickerScreen.zhName(m.item, m.itemName);
            int st = MemoItemPickerScreen.maxStackOf(m.item);
            g.drawString(this.font, UiKit.truncPx(this.font, zh, 130), x, rowY + 6, UiKit.TEXT, false);
            x += 136;
            long rem = Math.max(0, m.need - m.delivered);
            g.drawString(this.font, L10n.get("projectmemo.collect.remaining", UiKit.fmtAmount(rem, st)), x, rowY + 6, UiKit.DIM, false);
        }
        g.disableScissor();
        UiKit.scrollbar(g, listRight + 1, listTop, listBottom - listTop, scroll, maxScroll);

        int by = y0 + panelH - 24;
        UiKit.UiButton all = new UiKit.UiButton(x0 + 10, by, 44, 16,
                selected.size() == pickable.size() && !pickable.isEmpty() ? L10n.get("projectmemo.common.selectNone") : L10n.get("projectmemo.common.selectAll"), () -> {
                    if (selected.size() == pickable.size()) selected.clear();
                    else for (MemoData.MaterialRow m : pickable) selected.add(m.id);
                });
        uiButtons.add(all);
        UiKit.UiButton confirm = new UiKit.UiButton(x0 + panelW - 160, by, 104, 16,
                L10n.get("projectmemo.collect.confirmN", selected.size()), selected.isEmpty() ? null : this::confirm);
        if (selected.isEmpty()) confirm.disabled();
        uiButtons.add(confirm);
        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 52, by, 44, 16, L10n.get("projectmemo.common.back"), () ->
                this.minecraft.setScreen(new ProjectDetailScreen(projectId, 1)));
        uiButtons.add(back);
    }

    private void confirm() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.isEmpty()) {
            MemoToast.push(L10n.get("projectmemo.collect.needName"), MemoToast.RED);
            return;
        }
        com.google.gson.JsonObject args = MemoClientState.argsOf("project", projectId);
        args.addProperty("title", name);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int id : selected) arr.add(id);
        args.add("materials", arr);
        MemoClientState.sendAction("create_collect_task", args);
        this.minecraft.setScreen(new ProjectDetailScreen(projectId, 1));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() == 0
                && UiKit.inRect(event.x(), event.y(), listLeft, listTop, listRight - listLeft, listBottom - listTop)) {
            int idx = (int) ((event.y() - listTop + scroll) / ROW_H);
            if (idx >= 0 && idx < pickable.size()) {
                int id = pickable.get(idx).id;
                if (selected.contains(id)) selected.remove(id); else selected.add(id);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - scrollY * 20);
        return true;
    }
}
