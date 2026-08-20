package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 子任务详情页。
 * 自定义任务：标题/状态/认领人/时间 + 说明（可滚动）。
 * 收集任务：无说明区，直接显示可滚动的实时收集进度（材料齐了核验时自动完成）。
 */
public final class TaskDetailScreen extends MemoScreenBase {

    private final int taskId;
    private int knownProjectId = -1;
    private double noteScroll;
    private int noteX, noteY, noteW, noteH;
    private double progScroll;
    private int progX, progY, progW, progH;
    private boolean isCollect;

    public TaskDetailScreen(int taskId) {
        super("子任务详情");
        this.taskId = taskId;
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        MemoData data = MemoClientState.data();
        MemoData.Task t = data.taskById(taskId);
        int panelW = Math.min(380, this.width - 20);
        int panelH = Math.min(280, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        int by = y0 + panelH - 24;
        noteH = 0;
        progH = 0;

        if (t == null) {
            g.drawString(this.font, "子任务不存在（可能已被删除）", x0 + 12, y0 + 20, UiKit.RED, false);
            UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 62, by, 52, 16, "返回", () -> {
                if (knownProjectId >= 0) this.minecraft.setScreen(new ProjectDetailScreen(knownProjectId, 1));
                else this.minecraft.setScreen(new MemoMainScreen());
            });
            uiButtons.add(back);
            return;
        }
        knownProjectId = t.projectId;
        MemoData.Project pr = data.projectById(t.projectId);
        if (pr == null) {
            g.drawString(this.font, "所属工程不存在", x0 + 12, y0 + 20, UiKit.RED, false);
            UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 62, by, 52, 16, "返回", () ->
                    this.minecraft.setScreen(new MemoMainScreen()));
            uiButtons.add(back);
            return;
        }

        boolean mgr = MemoClientState.manages(pr.id);
        boolean editable = "planning".equals(pr.status) || "active".equals(pr.status);
        boolean canEdit = (MemoClientState.canCreate() || mgr) && editable;
        isCollect = "collect".equals(t.type);
        String self = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().name() : "";

        // ── 标题行 ──
        String sym;
        int color;
        String statusText;
        if ("done".equals(t.status)) { sym = "✔"; color = UiKit.GREEN; statusText = "已完成"; }
        else if ("claimed".equals(t.status)) { sym = "◔"; color = UiKit.YELLOW; statusText = "已认领"; }
        else { sym = "○"; color = UiKit.DIM; statusText = "待认领"; }
        g.drawString(this.font, sym, x0 + 10, y0 + 8, color, false);
        g.drawString(this.font, UiKit.truncPx(this.font, t.title, panelW - 120), x0 + 22, y0 + 8, UiKit.TEXT, false);
        g.drawString(this.font, statusText, x0 + panelW - 10 - this.font.width(statusText), y0 + 8, color, false);

        int y = y0 + 24;
        g.drawString(this.font, "所属工程: 《" + UiKit.trunc(pr.title, 16) + "》"
                + (isCollect ? "  ·  材料收集任务" : ""), x0 + 10, y, UiKit.DIM, false);
        y += 13;
        if (!t.assignee.isEmpty()) {
            g.drawString(this.font, "认领人: " + t.assignee + "（" + UiKit.fmtDate(t.claimedAt) + "）", x0 + 10, y, UiKit.DIM, false);
            y += 13;
        }
        if (t.doneAt > 0) {
            g.drawString(this.font, "完成时间: " + UiKit.fmtDate(t.doneAt), x0 + 10, y, UiKit.GREEN, false);
            y += 13;
        }
        y += 3;

        if (isCollect) {
            // ── 收集任务：可滚动的实时收集进度（无说明区） ──
            g.drawString(this.font, "收集进度（材料齐了会在核验时自动完成）:", x0 + 10, y, UiKit.DIM, false);
            if (canEdit) {
                UiKit.UiButton rename = new UiKit.UiButton(x0 + panelW - 66, y - 2, 56, 14, "改标题", () ->
                        this.minecraft.setScreen(new MemoInputDialog(this, "修改标题", "子任务标题（≤40 字）", t.title, 40,
                                v -> MemoClientState.sendAction("task_rename",
                                        MemoClientState.argsOf("task", t.id, "title", v)))));
                uiButtons.add(rename);
            }
            y += 14;
            progX = x0 + 10;
            progY = y;
            progW = panelW - 20;
            progH = by - 8 - y;
            // 按物品合并（同物品多个来源聚合为一行显示）
            java.util.Map<String, long[]> agg = new java.util.LinkedHashMap<>();   // item -> [need, delivered]
            java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
            for (int mid : t.materialIds) {
                MemoData.MaterialRow m = data.materialById(mid);
                if (m == null) continue;
                long[] nd = agg.computeIfAbsent(m.item, k -> new long[2]);
                nd[0] += m.need;
                nd[1] += m.delivered;
                names.putIfAbsent(m.item, MemoItemPickerScreen.zhName(m.item, m.itemName));
            }
            int lineH = 12;
            int total = agg.size();
            double maxProgScroll = Math.max(0, total * lineH - progH);
            progScroll = clampScroll(progScroll, maxProgScroll);
            if (total == 0) {
                g.drawString(this.font, "（该任务没有关联材料）", x0 + 14, y, UiKit.FAINT, false);
            } else {
                g.enableScissor(progX, progY, progX + progW, progY + progH);
                int py = progY - (int) progScroll;
                for (java.util.Map.Entry<String, long[]> e : agg.entrySet()) {
                    if (py + lineH >= progY && py <= progY + progH) {
                        long need = e.getValue()[0], delivered = e.getValue()[1];
                        boolean ok = delivered >= need;
                        g.drawString(this.font, (ok ? "✔ " : "· ") + UiKit.truncPx(this.font, names.get(e.getKey()), 150),
                                x0 + 14, py, ok ? UiKit.GREEN : UiKit.TEXT, false);
                        g.drawString(this.font, delivered + " / " + need + " 个",
                                x0 + 170, py, ok ? UiKit.GREEN : UiKit.DIM, false);
                    }
                    py += lineH;
                }
                g.disableScissor();
                if (maxProgScroll > 0) {
                    UiKit.scrollbar(g, progX + progW + 1, progY, progH, progScroll, maxProgScroll);
                }
            }
        } else {
            // ── 自定义任务：说明区（可滚动） ──
            g.drawString(this.font, "说明:", x0 + 10, y, UiKit.DIM, false);
            if (canEdit) {
                UiKit.UiButton editNote = new UiKit.UiButton(x0 + panelW - 66, y - 2, 56, 14, "编辑说明", () ->
                        this.minecraft.setScreen(new MemoTextScreen("子任务说明", t.note, this,
                                v -> MemoClientState.sendAction("task_set_note",
                                        MemoClientState.argsOf("task", t.id, "note", v)))));
                uiButtons.add(editNote);
                UiKit.UiButton rename = new UiKit.UiButton(x0 + panelW - 128, y - 2, 56, 14, "改标题", () ->
                        this.minecraft.setScreen(new MemoInputDialog(this, "修改标题", "子任务标题（≤40 字）", t.title, 40,
                                v -> MemoClientState.sendAction("task_rename",
                                        MemoClientState.argsOf("task", t.id, "title", v)))));
                uiButtons.add(rename);
            }
            y += 14;
            noteX = x0 + 10;
            noteY = y;
            noteW = panelW - 20;
            noteH = by - 8 - y;
            if (t.note.isEmpty()) {
                g.drawString(this.font, "（无说明）", x0 + 14, y, UiKit.FAINT, false);
            } else {
                List<FormattedCharSequence> lines = this.font.split(
                        net.minecraft.network.chat.Component.literal(t.note), panelW - 28);
                double maxNoteScroll = Math.max(0, lines.size() * 11 - noteH);
                noteScroll = clampScroll(noteScroll, maxNoteScroll);
                g.enableScissor(noteX, noteY, noteX + noteW, noteY + noteH);
                int ny = noteY - (int) noteScroll;
                for (FormattedCharSequence line : lines) {
                    if (ny + 11 >= noteY && ny <= noteY + noteH) {
                        g.drawString(this.font, line, x0 + 14, ny, UiKit.TEXT, false);
                    }
                    ny += 11;
                }
                g.disableScissor();
                if (maxNoteScroll > 0) {
                    UiKit.scrollbar(g, noteX + noteW + 1, noteY, noteH, noteScroll, maxNoteScroll);
                }
            }
        }

        // ── 操作按钮（镜像服全只读：认领/完工/撤销/重开/删除一律不出）──
        int bx = x0 + 10;
        if (editable && !MemoClientState.readOnly()) {
            if ("open".equals(t.status)) {
                UiKit.UiButton claim = new UiKit.UiButton(bx, by, 44, 16, "认领", () ->
                        MemoClientState.sendAction("claim", MemoClientState.argsOf("task", t.id)));
                uiButtons.add(claim);
                bx += 48;
            } else if ("claimed".equals(t.status)) {
                if (t.assignee.equals(self)) {
                    UiKit.UiButton done = new UiKit.UiButton(bx, by, 58, 16, "任务完工", () ->
                            MemoClientState.sendAction("task_done", MemoClientState.argsOf("task", t.id)));
                    uiButtons.add(done);
                    bx += 62;
                    UiKit.UiButton unclaim = new UiKit.UiButton(bx, by, 52, 16, "取消认领", () ->
                            MemoClientState.sendAction("unclaim", MemoClientState.argsOf("task", t.id)));
                    uiButtons.add(unclaim);
                    bx += 56;
                } else if (mgr) {
                    UiKit.UiButton done = new UiKit.UiButton(bx, by, 44, 16, "代完", () ->
                            MemoClientState.sendAction("task_done", MemoClientState.argsOf("task", t.id)));
                    uiButtons.add(done);
                    bx += 48;
                    // 撤销他人认领 = 破坏性操作（对方正在做的任务被释放），二次确认
                    UiKit.UiButton unclaim = new UiKit.UiButton(bx, by, 58, 16, "撤销认领", () ->
                            this.minecraft.setScreen(new MemoConfirmDialog(this, "撤销认领",
                                    "撤销 " + t.assignee + " 对「" + UiKit.trunc(t.title, 16) + "」的认领？任务将变回待认领。", false,
                                    () -> MemoClientState.sendAction("unclaim", MemoClientState.argsOf("task", t.id)))));
                    unclaim.tooltip("撤销他人的认领（需要确认）");
                    uiButtons.add(unclaim);
                    bx += 62;
                }
            } else if ("done".equals(t.status) && mgr) {
                UiKit.UiButton reopen = new UiKit.UiButton(bx, by, 44, 16, "重开", () ->
                        MemoClientState.sendAction("task_reopen", MemoClientState.argsOf("task", t.id)));
                uiButtons.add(reopen);
                bx += 48;
            }
            if (mgr) {
                final int backTo = pr.id;
                UiKit.UiButton del = new UiKit.UiButton(bx, by, 44, 16, "删除", () ->
                        this.minecraft.setScreen(new MemoConfirmDialog(this, "删除子任务",
                                "删除「" + t.title + "」？", true,
                                () -> {
                                    MemoClientState.sendAction("delete_task", MemoClientState.argsOf("task", t.id));
                                    this.minecraft.setScreen(new ProjectDetailScreen(backTo, 1));
                                })));
                del.danger();
                uiButtons.add(del);
            }
        }
        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 62, by, 52, 16, "返回工程", () ->
                this.minecraft.setScreen(new ProjectDetailScreen(pr.id, 1)));
        uiButtons.add(back);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isCollect && progH > 0
                && mouseX >= progX && mouseX <= progX + progW && mouseY >= progY && mouseY <= progY + progH) {
            progScroll = Math.max(0, progScroll - scrollY * 12);
            return true;
        }
        if (!isCollect && noteH > 0
                && mouseX >= noteX && mouseX <= noteX + noteW && mouseY >= noteY && mouseY <= noteY + noteH) {
            noteScroll = Math.max(0, noteScroll - scrollY * 11);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
