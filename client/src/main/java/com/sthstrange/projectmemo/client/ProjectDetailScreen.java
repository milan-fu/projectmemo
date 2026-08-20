package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 工程详情 v0.8：标签页 = 信息/子任务/投影/材料收集/搭建说明。
 * 子任务点行进详情页；材料进度纯容器核验；选址锁/隐藏；投影管理。
 */
public final class ProjectDetailScreen extends MemoScreenBase {

    /** 标签页（本地化） */
    private static String[] tabs() {
        return new String[]{ L10n.get("projectmemo.tab.info"), L10n.get("projectmemo.tab.tasks"),
                L10n.get("projectmemo.tab.schematics"), L10n.get("projectmemo.tab.materials"), L10n.get("projectmemo.tab.notes") };
    }
    private static final int TASK_ROW_H = 20;
    private static final int MAT_ROW_H = 24;
    private static final int BTN_W = 30, BTN_GAP = 3;

    private final int projectId;
    private int tab;
    private double scroll;
    private int taskListTop, taskListBottom, taskListLeft, taskListRight;
    private boolean sortOpen;          // 排序下拉是否展开
    private int sortDdX, sortDdY;      // 排序下拉位置
    private final List<MatLine> matLines = new java.util.ArrayList<>();
    private int matListX, matListW, matListTop, matListBottom;
    private static String[] sortLabels() {
        return new String[]{ L10n.get("projectmemo.sort.default"), L10n.get("projectmemo.sort.needUp"),
                L10n.get("projectmemo.sort.needDown"), L10n.get("projectmemo.sort.remUp"), L10n.get("projectmemo.sort.remDown") };
    }
    private static final int SORT_DD_ROW = 16;
    private static String[] taskFilters() {
        return new String[]{ L10n.get("projectmemo.taskFilter.all"), L10n.get("projectmemo.taskFilter.claimed"),
                L10n.get("projectmemo.taskFilter.done"), L10n.get("projectmemo.taskFilter.open") };
    }
    private static final int TASK_DD_ROW = 14;
    private boolean taskFilterOpen;          // 子任务筛选下拉是否展开
    private int taskFilterDdX, taskFilterDdY;

    public ProjectDetailScreen(int projectId) {
        this(projectId, 0);
    }

    public ProjectDetailScreen(int projectId, int initialTab) {
        super(L10n.get("projectmemo.pd.screenTitle"));
        this.projectId = projectId;
        this.tab = Math.max(0, Math.min(initialTab, tabs().length - 1));
    }

    private int panelW() { return Math.min(480, this.width - 16); }

    private int panelH() { return Math.min(340, this.height - 24); }

    private String selfName() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().name() : "";
    }

    private void switchTab(int t) {
        if (tab != t) {
            tab = t;
            scroll = 0;
            taskFilterOpen = false;
            sortOpen = false;
        }
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = panelW();
        int panelH = panelH();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        MemoData data = MemoClientState.data();
        MemoData.Project pr = data.projectById(projectId);
        if (pr == null) {
            if (!MemoClientState.isReady()) {
                g.drawString(this.font, L10n.get("projectmemo.pd.syncing"), x0 + 12, y0 + 20, UiKit.DIM, false);
            } else {
                g.drawString(this.font, L10n.get("projectmemo.pd.projectGone"), x0 + 12, y0 + 20, UiKit.RED, false);
            }
            UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 66, y0 + panelH - 24, 58, 16, L10n.get("projectmemo.common.backToList"),
                    () -> this.minecraft.setScreen(new MemoMainScreen()));
            uiButtons.add(back);
            return;
        }

        boolean mgr = MemoClientState.manages(projectId);
        boolean editable = "planning".equals(pr.status) || "active".equals(pr.status);

        // ── 头部 ──
        String titleStr = L10n.get("projectmemo.pd.titleFormat", UiKit.trunc(pr.title, 18));
        g.drawString(this.font, titleStr, x0 + 10, y0 + 8, UiKit.TEXT, false);
        String st = UiKit.statusSym(pr.status) + " " + UiKit.statusCn(pr.status) + "  #" + pr.id;
        int stX = x0 + 10 + this.font.width(titleStr) + 8;
        g.drawString(this.font, st, stX, y0 + 8, UiKit.statusColor(pr.status), false);
        if (MemoClientState.mirror()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.mirrorMark"), stX + this.font.width(st) + 8, y0 + 8, UiKit.GOLD, false);
        }

        // ── 设为首页（按 J 默认打开当前工程的当前标签页） ──
        {
            boolean isHome = ("project:" + projectId + ":" + tab).equals(MemoHome.load());
            UiKit.UiButton home = new UiKit.UiButton(x0 + panelW - 8 - 60, y0 + 5, 60, 14,
                    isHome ? L10n.get("projectmemo.main.isHome") : L10n.get("projectmemo.main.setHome"), () -> {
                        MemoHome.save("project:" + projectId + ":" + tab);
                        MemoToast.push(L10n.get("projectmemo.pd.homeSet"), MemoToast.GREEN);
                    });
            home.tooltip(L10n.get("projectmemo.pd.homeTip"));
            if (isHome) home.disabled();
            uiButtons.add(home);
        }

        // ── 左侧标签列 ──
        int tx = x0 + 6, ty = y0 + 24;
        for (int i = 0; i < tabs().length; i++) {
            final int ti = i;
            boolean selected = tab == i;
            UiKit.UiButton tbtn = new UiKit.UiButton(tx, ty, 64, 18, tabs()[i], () -> switchTab(ti));
            uiButtons.add(tbtn);
            if (selected) g.fill(tx, ty, tx + 64, ty + 18, 0x50FFFFFF);
            ty += 20;
        }

        // ── 内容区 ──
        int cx = x0 + 76;
        int cw = panelW - 76 - 14;
        int cy0 = y0 + 24;
        int cy1 = y0 + panelH - 30;
        g.fill(cx - 4, cy0 - 2, cx - 3, cy1 + 2, 0xFF2A2A35);

        switch (tab) {
            case 0: drawInfoTab(g, pr, mgr, editable, cx, cw, cy0, cy1); break;
            case 1: drawTasksTab(g, data, pr, mgr, editable, cx, cw, cy0, cy1, mouseX, mouseY); break;
            case 2: drawSchematicsTab(g, pr, mgr, editable, cx, cw, cy0, cy1); break;
            case 3: drawMaterialsTab(g, data, pr, mgr, editable, cx, cw, cy0, cy1, mouseX, mouseY); break;
            default: drawNoteTab(g, pr, mgr, editable, cx, cw, cy0, cy1); break;
        }

        // ── 底部操作栏 ──
        int by = y0 + panelH - 24;
        int bx = x0 + 8;
        if (editable && mgr) {
            UiKit.UiButton finish = new UiKit.UiButton(bx, by, 52, 16, L10n.get("projectmemo.pd.finish"), () -> {
                List<MemoData.Task> ts = data.tasksOf(pr.id);
                long undone = ts.stream().filter(t -> !"done".equals(t.status)).count();
                if (undone > 0) {
                    this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.finishTitle"),
                            L10n.get("projectmemo.pd.finishMsg", undone),
                            false, this::doFinish));
                } else {
                    doFinish();
                }
            });
            uiButtons.add(finish);
            bx += 56;
        }
        if ("completed".equals(pr.status) && mgr) {
            UiKit.UiButton reopen = new UiKit.UiButton(bx, by, 44, 16, L10n.get("projectmemo.pd.unarchive"), () ->
                    MemoClientState.sendAction("project_reopen", MemoClientState.argsOf("project", pr.id)));
            reopen.tooltip(L10n.get("projectmemo.pd.unarchiveTip"));
            uiButtons.add(reopen);
            bx += 48;
        }
        if ("archived".equals(pr.status) && MemoClientState.isOp()) {
            UiKit.UiButton unarch = new UiKit.UiButton(bx, by, 52, 16, L10n.get("projectmemo.pd.unarchive"), () ->
                    MemoClientState.sendAction("unarchive", MemoClientState.argsOf("project", pr.id)));
            uiButtons.add(unarch);
            bx += 56;
        }
        if (MemoClientState.isOp()) {
            if (!"archived".equals(pr.status)) {
                UiKit.UiButton arch = new UiKit.UiButton(bx, by, 44, 16, L10n.get("projectmemo.pd.archive"), () ->
                        this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.archiveTitle"),
                                L10n.get("projectmemo.pd.archiveMsg", pr.title), false,
                                () -> MemoClientState.sendAction("archive", MemoClientState.argsOf("project", pr.id)))));
                uiButtons.add(arch);
                bx += 48;
            }
            UiKit.UiButton del = new UiKit.UiButton(bx, by, 44, 16, L10n.get("projectmemo.common.delete"), () ->
                    this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.deleteTitle"),
                            L10n.get("projectmemo.pd.deleteMsg", pr.title), true,
                            () -> {
                                MemoClientState.sendAction("delete_project",
                                        MemoClientState.argsOf("project", pr.id));
                                this.minecraft.setScreen(new MemoMainScreen());
                            })));
            del.danger();
            uiButtons.add(del);
        }
        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 8 - 58, by, 58, 16, L10n.get("projectmemo.common.backToList"),
                () -> this.minecraft.setScreen(new MemoMainScreen()));
        uiButtons.add(back);
    }

    private void doFinish() {
        MemoClientState.sendAction("project_done", MemoClientState.argsOf("project", projectId));
    }

    // ───────────────────────── 信息页 ─────────────────────────

    private void drawInfoTab(GuiGraphics g, MemoData.Project pr, boolean mgr, boolean editable,
                             int cx, int cw, int cy0, int cy1) {
        boolean canManageList = (MemoClientState.isOp() || isCreator(pr)) && editable;
        int y = cy0 + 2;

        g.drawString(this.font, L10n.get("projectmemo.pd.createdMeta", pr.creator, UiKit.fmtDate(pr.createdAt))
                + ("completed".equals(pr.status) && pr.completedAt > 0 ? L10n.get("projectmemo.pd.completedSuffix", UiKit.fmtDate(pr.completedAt)) : ""),
                cx, y, UiKit.DIM, false);
        y += 14;

        String mgrLabel = L10n.get("projectmemo.pd.managers");
        int mx = cx + this.font.width(mgrLabel);
        g.drawString(this.font, mgrLabel, cx, y, UiKit.DIM, false);
        for (String name : pr.managers) {
            g.drawString(this.font, name, mx, y, UiKit.TEXT, false);
            mx += this.font.width(name) + 2;
            if (canManageList) {
                boolean isCreator = pr.creator != null && name.equals(pr.creator);
                UiKit.UiButton rm = new UiKit.UiButton(mx, y - 2, 16, 13, "-", isCreator ? null : () ->
                        MemoClientState.sendAction("set_managers",
                                MemoClientState.argsOf("project", pr.id, "add", false, "name", name)));
                if (isCreator) { rm.disabled(); rm.tooltip(L10n.get("projectmemo.err.creatorManager")); }
                else rm.tooltip(L10n.get("projectmemo.pd.removeManagerTip", name));
                uiButtons.add(rm);
                mx += 16 + 6;
            } else {
                mx += 4;
            }
        }
        if (canManageList) {
            UiKit.UiButton addM = new UiKit.UiButton(mx, y - 2, 34, 13, L10n.get("projectmemo.common.add"), () ->
                    this.minecraft.setScreen(new MemoInputDialog(this, L10n.get("projectmemo.pd.addManagerTitle"), L10n.get("projectmemo.pd.playerNamePrompt"), "", 16,
                            name -> MemoClientState.sendAction("set_managers",
                                    MemoClientState.argsOf("project", pr.id, "add", true, "name", name)))));
            uiButtons.add(addM);
        }
        y += 15;

        // 选址行
        String locText;
                if (pr.locWorld.isEmpty()) locText = L10n.get("projectmemo.pd.locNone");
        else if (pr.locHidden && !mgr) locText = L10n.get("projectmemo.pd.locHidden");
        else locText = L10n.get("projectmemo.pd.locAt", UiKit.worldCn(pr.locWorld), pr.locX, pr.locY, pr.locZ)
                + (pr.locNote.isEmpty() ? "" : L10n.get("projectmemo.pd.locNote", pr.locNote));
        g.drawString(this.font, UiKit.truncPx(this.font, locText, cw - 130), cx, y, UiKit.DIM, false);
        // 标识区：锁 +（隐藏），不用进编辑页就能看出状态
        int markX = cx + this.font.width(UiKit.truncPx(this.font, locText, cw - 130)) + 4;
        if (pr.locLocked && mgr) {
            g.drawString(this.font, "🔒", markX, y, UiKit.GOLD, false);
            markX += 12;
        }
        if (pr.locHidden && mgr) {
            g.drawString(this.font, L10n.get("projectmemo.pd.hiddenMark"), markX, y, UiKit.GOLD, false);
        }
        if (mgr && editable) {
            UiKit.UiButton editLoc = new UiKit.UiButton(cx + cw - 64, y - 2, 62, 14, L10n.get("projectmemo.pd.editLoc"), () ->
                    this.minecraft.setScreen(new MemoLocationScreen(pr.id)));
            uiButtons.add(editLoc);
        }
        y += 15;

        g.drawString(this.font, L10n.get("projectmemo.pd.descLabel"), cx, y, UiKit.DIM, false);
        if (mgr && editable) {
            UiKit.UiButton editDesc = new UiKit.UiButton(cx + cw - 58, y - 2, 56, 14, L10n.get("projectmemo.pd.editDesc"), () ->
                    this.minecraft.setScreen(new MemoTextScreen(L10n.get("projectmemo.pd.editDesc"), pr.desc, this,
                            v -> MemoClientState.sendAction("edit_project",
                                    MemoClientState.argsOf("project", pr.id, "field", "desc", "value", v)))));
            uiButtons.add(editDesc);
        }
        y += 13;
        if (pr.desc.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.noDesc"), cx + 4, y, UiKit.FAINT, false);
            y += 13;
        } else {
            for (FormattedCharSequence line : this.font.split(net.minecraft.network.chat.Component.literal(pr.desc), cw - 8)) {
                if (y > cy1 - 26) break;
                g.drawString(this.font, line, cx + 4, y, UiKit.TEXT, false);
                y += 11;
            }
            y += 3;
        }

        // 参与玩家（管理者可手动增删——覆盖"参与了但没领任务"的玩家）
        {
            boolean canEditParts = mgr && !"archived".equals(pr.status);
            g.drawString(this.font, L10n.get("projectmemo.pd.participants"), cx, y, UiKit.DIM, false);
            int px = cx + this.font.width(L10n.get("projectmemo.pd.participants")) + 4;
            if (pr.participants.isEmpty()) {
                g.drawString(this.font, L10n.get("projectmemo.common.none"), px, y, UiKit.FAINT, false);
                px += this.font.width(L10n.get("projectmemo.common.none")) + 4;
            }
            for (String name : pr.participants) {
                if (px > cx + cw - 60) break;
                g.drawString(this.font, name, px, y, UiKit.YELLOW, false);
                px += this.font.width(name) + 2;
                if (canEditParts) {
                    UiKit.UiButton rm = new UiKit.UiButton(px, y - 2, 14, 13, "-", () ->
                            MemoClientState.sendAction("participants_remove",
                                    MemoClientState.argsOf("project", pr.id, "name", name)));
                    rm.tooltip(L10n.get("projectmemo.pd.removeParticipantTip", name));
                    uiButtons.add(rm);
                    px += 14 + 5;
                } else {
                    px += 4;
                }
            }
            if (canEditParts) {
                UiKit.UiButton addP = new UiKit.UiButton(px, y - 2, 34, 13, L10n.get("projectmemo.common.add"), () ->
                        this.minecraft.setScreen(new MemoInputDialog(this, L10n.get("projectmemo.pd.addParticipantTitle"), L10n.get("projectmemo.pd.playerNamePrompt"), "", 16,
                                name -> MemoClientState.sendAction("participants_add",
                                        MemoClientState.argsOf("project", pr.id, "name", name)))));
                uiButtons.add(addP);
            }
            y += 15;
        }

        if (mgr && editable) {
            UiKit.UiButton editTitle = new UiKit.UiButton(cx, cy1 - 16, 56, 14, L10n.get("projectmemo.task.rename"), () ->
                    this.minecraft.setScreen(new MemoInputDialog(this, L10n.get("projectmemo.task.renameTitle"), L10n.get("projectmemo.pd.titlePrompt"), pr.title, 30,
                            v -> MemoClientState.sendAction("edit_project",
                                    MemoClientState.argsOf("project", pr.id, "field", "title", "value", v)))));
            uiButtons.add(editTitle);
            UiKit.UiButton imp = new UiKit.UiButton(cx + 60, cy1 - 16, 64, 14, L10n.get("projectmemo.pd.importSchematics"), () ->
                    this.minecraft.setScreen(new MemoImportsScreen(pr.id)));
            uiButtons.add(imp);
            if ("planning".equals(pr.status)) {
                UiKit.UiButton toActive = new UiKit.UiButton(cx + 128, cy1 - 16, 64, 14, L10n.get("projectmemo.pd.toActive"), () ->
                        MemoClientState.sendAction("edit_project",
                                MemoClientState.argsOf("project", pr.id, "field", "status", "value", "active")));
                uiButtons.add(toActive);
            } else if ("active".equals(pr.status)) {
                UiKit.UiButton toPlanning = new UiKit.UiButton(cx + 128, cy1 - 16, 64, 14, L10n.get("projectmemo.pd.toPlanning"), () ->
                        MemoClientState.sendAction("edit_project",
                                MemoClientState.argsOf("project", pr.id, "field", "status", "value", "planning")));
                uiButtons.add(toPlanning);
            }
        }
    }

    private boolean isCreator(MemoData.Project pr) {
        return Minecraft.getInstance().player != null
                && pr.creatorUuid.equals(Minecraft.getInstance().player.getGameProfile().id().toString());
    }

    // ───────────────────────── 子任务页（点行进详情） ─────────────────────────

    private void drawTasksTab(GuiGraphics g, MemoData data, MemoData.Project pr, boolean mgr, boolean editable,
                              int cx, int cw, int cy0, int cy1, int mouseX, int mouseY) {
        List<MemoData.Task> all = data.tasksOf(pr.id);
        int tf = MemoClientState.getTaskFilter(pr.id);
        List<MemoData.Task> tasks = filteredTasks(all, tf);
        long done = all.stream().filter(t -> "done".equals(t.status)).count();
        String header = L10n.get("projectmemo.pd.tasksHeader", done, all.size())
                + (tf > 0 ? L10n.get("projectmemo.pd.filteredBy", taskFilters()[tf]) : L10n.get("projectmemo.pd.clickRowHint"));
        g.drawString(this.font, header, cx, cy0 + 2, UiKit.TEXT, false);
        // 材料收集任务：所有玩家可加（自动认领）；镜像服全只读不出
        if (editable && !MemoClientState.readOnly()) {
            UiKit.UiButton collectTask = new UiKit.UiButton(cx + cw - 64, cy0, 62, 14, L10n.get("projectmemo.pd.addCollect"), () ->
                    this.minecraft.setScreen(new MemoCollectPickScreen(pr.id)));
            collectTask.tooltip(L10n.get("projectmemo.pd.addCollectTip"));
            uiButtons.add(collectTask);
        }
        if (MemoClientState.canCreate() && editable) {
            UiKit.UiButton newTask = new UiKit.UiButton(cx + cw - 64 - 66, cy0, 62, 14, L10n.get("projectmemo.pd.addTask"), () ->
                    this.minecraft.setScreen(new MemoInputDialog(this, L10n.get("projectmemo.pd.newTaskTitle"), L10n.get("projectmemo.task.renamePrompt"), "", 40,
                            title -> MemoClientState.sendAction("create_task",
                                    MemoClientState.argsOf("project", pr.id, "title", title)))));
            uiButtons.add(newTask);
        }

        // ── 筛选下拉按钮（排在右侧添加按钮左边，格式同材料页排序下拉）──
        int fx = cx + cw;
        if (editable) fx = cx + cw - 66;                            // ➕材料收集 占位
        if (MemoClientState.canCreate() && editable) fx = cx + cw - 132; // ➕自定义任务 占位
        int tfw = 62;
        taskFilterDdX = fx - tfw;
        taskFilterDdY = cy0 - 1 + 15;
        UiKit.UiButton tfBtn = new UiKit.UiButton(taskFilterDdX, cy0 - 1, tfw, 14, "▾" + taskFilters()[tf],
                () -> taskFilterOpen = !taskFilterOpen);
        tfBtn.tooltip(L10n.get("projectmemo.pd.taskFilterTip"));
        uiButtons.add(tfBtn);

        taskListLeft = cx;
        taskListRight = cx + cw;
        taskListTop = cy0 + 18;
        taskListBottom = cy1;
        int contentH = tasks.size() * TASK_ROW_H;
        double maxScroll = Math.max(0, contentH - (taskListBottom - taskListTop));
        scroll = clampScroll(scroll, maxScroll);

        if (tasks.isEmpty()) {
            g.drawString(this.font, all.isEmpty() ? L10n.get("projectmemo.pd.noTasks") : L10n.get("projectmemo.pd.noMatchingTasks"),
                    cx + 4, taskListTop + 8, UiKit.FAINT, false);
        }

        g.enableScissor(cx, taskListTop, cx + cw, taskListBottom);
        for (int i = 0; i < tasks.size(); i++) {
            int rowY = taskListTop + i * TASK_ROW_H - (int) scroll;
            if (rowY + TASK_ROW_H < taskListTop || rowY > taskListBottom) continue;
            MemoData.Task t = tasks.get(i);
            boolean hover = UiKit.inRect(mouseX, mouseY, cx, rowY, cw, TASK_ROW_H);
            if (hover) g.fill(cx, rowY, cx + cw, rowY + TASK_ROW_H, 0x30FFFFFF);
            else if (i % 2 == 0) g.fill(cx, rowY, cx + cw, rowY + TASK_ROW_H, 0x10FFFFFF);

            int x = cx + 4;
            String titleShow = ("collect".equals(t.type) ? "🧺 " : "") + t.title;
            if ("done".equals(t.status)) {
                g.drawString(this.font, "✔", x, rowY + 6, UiKit.GREEN, false);
                x += 10;
                g.drawString(this.font, UiKit.truncPx(this.font, titleShow, cw - 160), x, rowY + 6, UiKit.FAINT, false);
                x = cx + cw - 140;
                g.drawString(this.font, UiKit.fmtDateShort(t.doneAt) + (t.assignee.isEmpty() ? "" : " · " + t.assignee),
                        x, rowY + 6, UiKit.DIM, false);
            } else if ("claimed".equals(t.status)) {
                g.drawString(this.font, "◔", x, rowY + 6, UiKit.YELLOW, false);
                x += 10;
                g.drawString(this.font, UiKit.truncPx(this.font, titleShow, cw - 160), x, rowY + 6, UiKit.YELLOW, false);
                x = cx + cw - 140;
                g.drawString(this.font, L10n.get("projectmemo.pd.claimedByRow", t.assignee), x, rowY + 6, UiKit.DIM, false);
            } else {
                g.drawString(this.font, "○", x, rowY + 6, UiKit.DIM, false);
                x += 10;
                g.drawString(this.font, UiKit.truncPx(this.font, titleShow, cw - 160), x, rowY + 6, UiKit.TEXT, false);
                x = cx + cw - 140;
                g.drawString(this.font, L10n.get("projectmemo.task.statusOpen"), x, rowY + 6, UiKit.DIM, false);
            }
            if (!t.note.isEmpty()) {
                g.drawString(this.font, "📝", cx + cw - 24, rowY + 6, UiKit.FAINT, false);
            }
        }
        g.disableScissor();
        UiKit.scrollbar(g, cx + cw + 2, taskListTop, taskListBottom - taskListTop, scroll, maxScroll);
    }

    /** 子任务筛选：0=全部 1=已认领 2=已完成 3=未认领（渲染与行点击共用，保证索引一致） */
    private static List<MemoData.Task> filteredTasks(List<MemoData.Task> all, int tf) {
        if (tf <= 0) return all;
        List<MemoData.Task> out = new java.util.ArrayList<>();
        for (MemoData.Task t : all) {
            boolean match = tf == 1 ? "claimed".equals(t.status)
                    : tf == 2 ? "done".equals(t.status)
                    : "open".equals(t.status);
            if (match) out.add(t);
        }
        return out;
    }

    // ───────────────────────── 投影页 ─────────────────────────

    private void drawSchematicsTab(GuiGraphics g, MemoData.Project pr, boolean mgr, boolean editable,
                                   int cx, int cw, int cy0, int cy1) {
        g.drawString(this.font, L10n.get("projectmemo.pd.importedSchematics", pr.schematics.size()), cx, cy0 + 2, UiKit.TEXT, false);
        if (mgr && editable) {
            UiKit.UiButton imp = new UiKit.UiButton(cx + cw - 64, cy0, 62, 14, L10n.get("projectmemo.pd.importSchematics"), () ->
                    this.minecraft.setScreen(new MemoImportsScreen(pr.id)));
            uiButtons.add(imp);
        }

        int listTop = cy0 + 18;
        int listBottom = cy1;
        int rowH = 24;
        double maxScroll = Math.max(0, pr.schematics.size() * rowH - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);

        if (pr.schematics.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.noSchematics"), cx + 4, listTop + 8, UiKit.FAINT, false);
            g.drawString(this.font, L10n.get("projectmemo.pd.schematicsHint"), cx + 4, listTop + 22, UiKit.FAINT, false);
        }

        g.enableScissor(cx, listTop, cx + cw, listBottom);
        for (int i = 0; i < pr.schematics.size(); i++) {
            int rowY = listTop + i * rowH - (int) scroll;
            if (rowY + rowH < listTop || rowY > listBottom) continue;
            MemoData.SchemEntry se = pr.schematics.get(i);
            final int idx = i;
            if (i % 2 == 0) g.fill(cx, rowY, cx + cw, rowY + rowH, 0x10FFFFFF);
            String src = "shared".equals(se.source) ? L10n.get("projectmemo.pd.srcShared") : L10n.get("projectmemo.pd.srcLocal");
            g.drawString(this.font, UiKit.truncPx(this.font, se.name, cw - 200), cx + 4, rowY + 3, UiKit.TEXT, false);
            g.drawString(this.font, L10n.get("projectmemo.pd.schematicMeta", src, se.blocks, se.kinds)
                    + (se.by.isEmpty() ? "" : " by " + se.by) + " " + UiKit.fmtDate(se.at),
                    cx + 4, rowY + 13, UiKit.FAINT, false);
            if (mgr) {
                int bx = cx + cw - 4;
                bx -= 34;
                UiKit.UiButton del = new UiKit.UiButton(bx, rowY + 4, 30, 14, L10n.get("projectmemo.common.delete"), () ->
                        this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.deleteSchematicTitle"),
                                L10n.get("projectmemo.pd.deleteSchematicMsg", se.name), true,
                                () -> MemoClientState.sendAction("schematic_delete",
                                        MemoClientState.argsOf("project", pr.id, "idx", idx)))));
                del.danger();
                del.tooltip(L10n.get("projectmemo.pd.deleteSchematicTip"));
                uiButtons.add(del);
            }
        }
        g.disableScissor();
        UiKit.scrollbar(g, cx + cw + 2, listTop, listBottom - listTop, scroll, maxScroll);
    }

    // ───────────────────────── 材料页（纯容器核验） ─────────────────────────

    /** 材料页渲染行：主行（view）或展开后的来源明细行（sub） */
    private static final class MatLine {
        MatView view;
        MemoData.MaterialRow sub;  // null=主行
        int height;
    }

    /** 材料页合并显示用：同物品跨来源聚合 */
    private static final class MatView {
        final String item;
        String name = "";
        int stack = 64;
        long need, delivered;
        final List<MemoData.MaterialRow> rows = new java.util.ArrayList<>();
        String collectBy = "";   // 进行中的收集任务认领人
        MatView(String item) { this.item = item; }
        long remaining() { return Math.max(0, need - delivered); }
    }

    private void drawMaterialsTab(GuiGraphics g, MemoData data, MemoData.Project pr, boolean mgr, boolean editable,
                                  int cx, int cw, int cy0, int cy1, int mouseX, int mouseY) {
        int sortMode = MemoClientState.getMatSort(pr.id);
        boolean unitBoxes = MemoClientState.getMatUnitBoxes(pr.id);

        // 收集任务认领映射：materialId -> 认领人（进行中的 collect 任务）
        java.util.Map<Integer, String> collectBy = new java.util.HashMap<>();
        for (MemoData.Task t : data.tasksOf(pr.id)) {
            if ("collect".equals(t.type) && !"done".equals(t.status)) {
                for (int mid : t.materialIds) collectBy.putIfAbsent(mid, t.assignee);
            }
        }

        // 原始行 → 来源筛选 + 认领筛选
        List<MemoData.MaterialRow> rows = new java.util.ArrayList<>(data.materialsOf(pr.id));
        java.util.Set<String> srcFilter = MemoClientState.getMatFilter(pr.id);
        java.util.Set<String> claimFilter = MemoClientState.getMatClaimFilter(pr.id);
        rows.removeIf(m -> {
            String src = m.source == null || m.source.isEmpty() ? "custom" : m.source;
            if (srcFilter != null && !srcFilter.contains(src)) return true;
            if (claimFilter != null) {
                String claimer = collectBy.getOrDefault(m.id, "");
                if (claimer.isEmpty()) return !claimFilter.contains("§unclaimed");
                return !claimFilter.contains(claimer);
            }
            return false;
        });

        // 同物品合并（筛选集内按物品聚合显示）
        java.util.Map<String, MatView> byItem = new java.util.LinkedHashMap<>();
        for (MemoData.MaterialRow m : rows) {
            MatView v = byItem.get(m.item);
            if (v == null) {
                v = new MatView(m.item);
                v.name = MemoItemPickerScreen.zhName(m.item, m.itemName);
                v.stack = MemoItemPickerScreen.maxStackOf(m.item);
                byItem.put(m.item, v);
            }
            v.need += m.need;
            v.delivered += m.delivered;
            v.rows.add(m);
            String cb = collectBy.get(m.id);
            if (cb != null && !cb.isEmpty() && v.collectBy.isEmpty()) v.collectBy = cb;
        }
        List<MatView> views = new java.util.ArrayList<>(byItem.values());

        // 排序
        if (sortMode == 1) views.sort(java.util.Comparator.comparingLong(v -> v.need));
        else if (sortMode == 2) views.sort((a, b) -> Long.compare(b.need, a.need));
        else if (sortMode == 3) views.sort(java.util.Comparator.comparingLong(MatView::remaining));
        else if (sortMode == 4) views.sort((a, b) -> Long.compare(b.remaining(), a.remaining()));

        // 百分比（按当前显示集）
        long fNeed = 0, fDelivered = 0;
        for (MatView v : views) { fNeed += v.need; fDelivered += Math.min(v.delivered, v.need); }
        int pct = fNeed <= 0 ? 0 : (int) (fDelivered * 100 / fNeed);
        boolean filtered = srcFilter != null || claimFilter != null;
        g.drawString(this.font, L10n.get("projectmemo.pd.materialsHeader", pct) + (filtered ? L10n.get("projectmemo.pd.filteredMark") : ""), cx, cy0 + 2, UiKit.TEXT, false);

        // ── 头部按钮（从右往左，紧凑布局不遮挡百分比文字）──
        {
            int bx = cx + cw;
            if (mgr && editable) {
                bx = addHeaderButton(bx, cy0, 36, L10n.get("projectmemo.pd.matImport"), () -> this.minecraft.setScreen(new MemoImportsScreen(pr.id)));
                bx = addHeaderButton(bx, cy0, 36, L10n.get("projectmemo.pd.matAdd"), () -> this.minecraft.setScreen(new MemoItemPickerScreen(pr.id)));
            }
            bx = addHeaderButton(bx, cy0, 46, unitBoxes ? L10n.get("projectmemo.pd.unitBoxes") : L10n.get("projectmemo.pd.unitCount"), () ->
                    MemoClientState.setMatUnitBoxes(pr.id, !MemoClientState.getMatUnitBoxes(pr.id)));
            // 排序下拉按钮
            int sw = 54;
            sortDdX = bx - sw;
            sortDdY = cy0 - 1 + 15;
            UiKit.UiButton sortBtn = new UiKit.UiButton(sortDdX, cy0 - 1, sw, 14, "▾" + sortLabels()[sortMode],
                    () -> sortOpen = !sortOpen);
            uiButtons.add(sortBtn);
            bx -= sw + 4;
            addHeaderButton(bx, cy0, 36, L10n.get("projectmemo.pd.matFilter"), () -> this.minecraft.setScreen(new MemoSourceFilterScreen(pr.id)));
        }

        // ── 收集区域（两行布局：坐标一行，按钮一行；选址隐藏时坐标一并隐藏） ──
        int dy = cy0 + 17;
        boolean canSeeDep = !(pr.locHidden && !mgr);
        if (pr.depWorld.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.depUnset"),
                    cx, dy, UiKit.DIM, false);
        } else if (!canSeeDep) {
            g.drawString(this.font, L10n.get("projectmemo.pd.depHidden"), cx, dy, UiKit.DIM, false);
        } else {
            String depText = L10n.get("projectmemo.pd.depArea", UiKit.worldCn(pr.depWorld),
                    pr.depX1, pr.depY1, pr.depZ1, pr.depX2, pr.depY2, pr.depZ2);
            g.drawString(this.font, depText, cx, dy, UiKit.DIM, false);
            int depMarkX = cx + this.font.width(depText) + 4;
            if (pr.depLocked && mgr) {
                g.drawString(this.font, "🔒", depMarkX, dy, UiKit.GOLD, false);
                depMarkX += 12;
            }
            if (pr.locHidden && mgr) {
                g.drawString(this.font, L10n.get("projectmemo.pd.hiddenMark"), depMarkX, dy, UiKit.GOLD, false);
            }
        }
        if (mgr && editable) {
            boolean locked = pr.depLocked;
            int bx = cx + cw;
            UiKit.UiButton check = new UiKit.UiButton(bx - 40, dy + 13, 40, 14, L10n.get("projectmemo.pd.verify"), () ->
                    MemoClientState.sendAction("check_deposit", MemoClientState.argsOf("project", pr.id)));
            check.tooltip(L10n.get("projectmemo.pd.verifyTip"));
            uiButtons.add(check);
            bx -= 44;
            UiKit.UiButton cb = new UiKit.UiButton(bx - 40, dy + 13, 40, 14, L10n.get("projectmemo.pd.cornerB"), locked ? null : () ->
                    MemoClientState.sendAction("set_deposit_b", MemoClientState.argsOf("project", pr.id)));
            cb.tooltip(locked ? L10n.get("projectmemo.pd.cornerLockedTip") : L10n.get("projectmemo.pd.cornerBTip"));
            uiButtons.add(cb);
            bx -= 44;
            UiKit.UiButton ca = new UiKit.UiButton(bx - 40, dy + 13, 40, 14, L10n.get("projectmemo.pd.cornerA"), locked ? null : () ->
                    MemoClientState.sendAction("set_deposit_a", MemoClientState.argsOf("project", pr.id)));
            ca.tooltip(locked ? L10n.get("projectmemo.pd.cornerLockedTip") : L10n.get("projectmemo.pd.cornerATip"));
            uiButtons.add(ca);
            bx -= 44;
            if (!pr.depWorld.isEmpty()) {
                UiKit.UiButton lockBtn = new UiKit.UiButton(bx - 50, dy + 13, 50, 14, locked ? L10n.get("projectmemo.common.unlock") : L10n.get("projectmemo.common.lock"), () -> {
                    com.google.gson.JsonObject args = MemoClientState.argsOf("project", pr.id);
                    args.addProperty("locked", !locked);
                    MemoClientState.sendAction("set_dep_lock", args);
                });
                lockBtn.tooltip(locked ? L10n.get("projectmemo.pd.unlockDepTip") : L10n.get("projectmemo.pd.lockDepTip"));
                uiButtons.add(lockBtn);
            }
        }

        // ── 构建渲染行（主行 + 展开后的来源明细行；明细跟随来源筛选）──
        matLines.clear();
        java.util.Set<String> expandedSet = MemoClientState.getMatExpanded(pr.id);
        for (MatView v : views) {
            MatLine ml = new MatLine(); ml.view = v; ml.height = MAT_ROW_H; matLines.add(ml);
            if (expandedSet.contains(v.item)) {
                for (MemoData.MaterialRow r : v.rows) {
                    MatLine sl = new MatLine(); sl.view = v; sl.sub = r; sl.height = 16; matLines.add(sl);
                }
            }
        }

        int listTop = dy + 32;
        int listBottom = cy1;
        matListX = cx; matListW = cw; matListTop = listTop; matListBottom = listBottom;
        int contentH = 0;
        for (MatLine l : matLines) contentH += l.height;
        double maxScroll = Math.max(0, contentH - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);

        if (views.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.noMaterials"), cx + 4, listTop + 8, UiKit.FAINT, false);
        }

        g.enableScissor(cx, listTop, cx + cw, listBottom);
        int lineY = listTop - (int) scroll;
        int mainIdx = 0;
        for (MatLine line : matLines) {
            int rowY = lineY;
            lineY += line.height;
            if (rowY + line.height <= listTop || rowY >= listBottom) continue;

            // ── 来源明细子行 ──
            if (line.sub != null) {
                MemoData.MaterialRow m = line.sub;
                String srcLabel = (m.source == null || m.source.isEmpty() || "custom".equals(m.source))
                        ? L10n.get("projectmemo.filter.customSource") : m.source;
                String subAmt = unitBoxes
                        ? UiKit.fmtAmount(m.delivered, line.view.stack) + " / " + UiKit.fmtAmount(m.need, line.view.stack)
                        : L10n.get("projectmemo.task.progressCount", m.delivered, m.need);
                g.drawString(this.font, "├ " + UiKit.truncPx(this.font, srcLabel, cw - 190),
                        cx + 20, rowY + 4, UiKit.FAINT, false);
                g.drawString(this.font, subAmt, cx + cw - 150, rowY + 4,
                        m.delivered >= m.need ? UiKit.GREEN : UiKit.DIM, false);
                if (mgr && editable) {
                    UiKit.UiButton subDel = new UiKit.UiButton(cx + cw - 30, rowY + 1, 26, 13, L10n.get("projectmemo.pd.delShort"), () ->
                            this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.delSourceTitle"),
                                    L10n.get("projectmemo.pd.delSourceMsg", line.view.name, srcLabel), true,
                                    () -> MemoClientState.sendAction("material_remove",
                                            MemoClientState.argsOf("material", m.id)))));
                    subDel.danger();
                    uiButtons.add(subDel);
                }
                continue;
            }

            // ── 主行 ──
            MatView v = line.view;
            if (mainIdx % 2 == 0) g.fill(cx, rowY, cx + cw, rowY + MAT_ROW_H, 0x10FFFFFF);
            mainIdx++;

            boolean single = v.rows.size() == 1;
            boolean canDel = mgr && editable;
            int actionW = canDel ? 34 : 0;

            int x = cx + 4;
            boolean exp = expandedSet.contains(v.item);
            g.drawString(this.font, exp ? "▾" : "▸", x, rowY + 4, UiKit.FAINT, false);
            x += 10;
            g.drawString(this.font, UiKit.truncPx(this.font, v.name, 84), x, rowY + 4, UiKit.TEXT, false);
            x += 88;
            String amt = unitBoxes
                    ? UiKit.fmtAmount(v.delivered, v.stack) + " / " + UiKit.fmtAmount(v.need, v.stack)
                    : L10n.get("projectmemo.task.progressCount", v.delivered, v.need);
            g.drawString(this.font, amt, x, rowY + 4, v.delivered >= v.need ? UiKit.GREEN : UiKit.DIM, false);
            x += this.font.width(amt) + 8;
            int barMax = cx + cw - actionW - x - (v.collectBy.isEmpty() ? 8 : 78);
            int barW = Math.max(26, Math.min(80, barMax));
            UiKit.progressBar(g, x, rowY + 4, barW, 8, v.need <= 0 ? 0 : Math.min(1, (double) v.delivered / v.need));
            x += barW + 6;

            // 收集任务认领指示（限宽防重合）
            if (!v.collectBy.isEmpty()) {
                int tagMax = cx + cw - actionW - x - 6;
                if (tagMax > 16) {
                    g.drawString(this.font, UiKit.truncPx(this.font, "🧺 " + v.collectBy, tagMax),
                            x, rowY + 4, UiKit.GOLD, false);
                }
            }

            if (canDel) {
                if (single) {
                    MemoData.MaterialRow m = v.rows.get(0);
                    addMatRowButton(cx + cw - 4, rowY, 30, L10n.get("projectmemo.pd.delRow"), true, () ->
                            this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.delRowTitle"), L10n.get("projectmemo.pd.delRowMsg", v.name), true,
                                    () -> MemoClientState.sendAction("material_remove", MemoClientState.argsOf("material", m.id)))));
                } else {
                    int n = v.rows.size();
                    List<Integer> ids = new java.util.ArrayList<>();
                    for (MemoData.MaterialRow r : v.rows) ids.add(r.id);
                    addMatRowButton(cx + cw - 4, rowY, 30, L10n.get("projectmemo.pd.delRow"), true, () ->
                            this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.pd.delMergedTitle"),
                                    L10n.get("projectmemo.pd.delMergedMsg", v.name, n), true,
                                    () -> {
                                        for (int id : ids) {
                                            MemoClientState.sendAction("material_remove",
                                                    MemoClientState.argsOf("material", id));
                                        }
                                    })));
                }
            }
        }
        g.disableScissor();

        UiKit.scrollbar(g, cx + cw + 2, listTop, listBottom - listTop, scroll, maxScroll);
    }

    /** 排序/筛选下拉画在 overlay 层（所有按钮之上），避免被角点锁等按钮遮挡 */
    @Override
    protected void drawOverlay(GuiGraphics g, int mouseX, int mouseY) {
        super.drawOverlay(g, mouseX, mouseY);
        if (tab == 1 && taskFilterOpen) {
            int tfSel = MemoClientState.getTaskFilter(projectId);
            int ddW = 66;
            int totalH = taskFilters().length * TASK_DD_ROW + 4;
            UiKit.panel(g, taskFilterDdX, taskFilterDdY, ddW, totalH);
            for (int i = 0; i < taskFilters().length; i++) {
                int oy = taskFilterDdY + 2 + i * TASK_DD_ROW;
                boolean sel = i == tfSel;
                boolean hov = UiKit.inRect(mouseX, mouseY, taskFilterDdX, oy, ddW, TASK_DD_ROW);
                if (sel) g.fill(taskFilterDdX + 1, oy, taskFilterDdX + ddW - 1, oy + TASK_DD_ROW, 0x50FFFFFF);
                else if (hov) g.fill(taskFilterDdX + 1, oy, taskFilterDdX + ddW - 1, oy + TASK_DD_ROW, 0x30FFFFFF);
                g.drawString(this.font, taskFilters()[i], taskFilterDdX + 6, oy + 3, sel ? UiKit.GOLD : UiKit.TEXT, false);
            }
        }
        if (tab == 3 && sortOpen) {
            int sortMode = MemoClientState.getMatSort(projectId);
            int ddW = 80;
            int totalH = sortLabels().length * SORT_DD_ROW + 4;
            UiKit.panel(g, sortDdX, sortDdY, ddW, totalH);
            for (int i = 0; i < sortLabels().length; i++) {
                int oy = sortDdY + 2 + i * SORT_DD_ROW;
                boolean sel = i == sortMode;
                boolean hov = UiKit.inRect(mouseX, mouseY, sortDdX, oy, ddW, SORT_DD_ROW);
                if (sel) g.fill(sortDdX + 1, oy, sortDdX + ddW - 1, oy + SORT_DD_ROW, 0x50FFFFFF);
                else if (hov) g.fill(sortDdX + 1, oy, sortDdX + ddW - 1, oy + SORT_DD_ROW, 0x30FFFFFF);
                g.drawString(this.font, sortLabels()[i], sortDdX + 6, oy + 4, sel ? UiKit.GOLD : UiKit.TEXT, false);
            }
        }
    }

    private int addHeaderButton(int rightX, int y, int w, String label, Runnable onClick) {
        UiKit.UiButton b = new UiKit.UiButton(rightX - w, y - 1, w, 14, label, onClick);
        uiButtons.add(b);
        return rightX - w - 4;
    }

    private int addMatRowButton(int rightX, int rowY, int w, String label, boolean danger, Runnable onClick) {
        UiKit.UiButton b = new UiKit.UiButton(rightX - w, rowY + 4, w, 13, label, onClick);
        if (danger) b.danger();
        uiButtons.add(b);
        return rightX - w - 3;
    }

    // ───────────────────────── 搭建说明页 ─────────────────────────

    private void drawNoteTab(GuiGraphics g, MemoData.Project pr, boolean mgr, boolean editable,
                             int cx, int cw, int cy0, int cy1) {
        g.drawString(this.font, L10n.get("projectmemo.pd.notesLabel"), cx, cy0 + 2, UiKit.TEXT, false);
        if (mgr && editable) {
            UiKit.UiButton edit = new UiKit.UiButton(cx + cw - 66, cy0, 64, 14, L10n.get("projectmemo.task.editNote"), () ->
                    this.minecraft.setScreen(new MemoTextScreen(L10n.get("projectmemo.pd.editNotesTitle"), pr.buildNote, this,
                            v -> MemoClientState.sendAction("edit_project",
                                    MemoClientState.argsOf("project", pr.id, "field", "note", "value", v)))));
            uiButtons.add(edit);
        }
        int y = cy0 + 16;
        if (pr.buildNote.isEmpty()) {
            g.drawString(this.font, L10n.get("projectmemo.pd.noNotes"), cx + 4, y, UiKit.FAINT, false);
        } else {
            for (FormattedCharSequence line : this.font.split(net.minecraft.network.chat.Component.literal(pr.buildNote), cw - 8)) {
                if (y > cy1 - 10) break;
                g.drawString(this.font, line, cx + 4, y, UiKit.TEXT, false);
                y += 11;
            }
        }
    }

    // ───────────────────────── 交互 ─────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // 子任务筛选下拉优先处理
        if (tab == 1 && taskFilterOpen && event.button() == 0) {
            int ddW = 66;
            int totalH = taskFilters().length * TASK_DD_ROW + 4;
            if (UiKit.inRect(event.x(), event.y(), taskFilterDdX, taskFilterDdY, ddW, totalH)) {
                int idx = (int) ((event.y() - taskFilterDdY - 2) / TASK_DD_ROW);
                if (idx >= 0 && idx < taskFilters().length) {
                    MemoClientState.setTaskFilter(projectId, idx);
                    scroll = 0;
                }
                taskFilterOpen = false;
                return true;
            }
            taskFilterOpen = false; // 点下拉外部：收起
            if (UiKit.inRect(event.x(), event.y(), taskFilterDdX, taskFilterDdY - 15, 62, 14)) return true; // 点在筛选按钮上=只收起
        }
        // 排序下拉优先处理
        if (tab == 3 && sortOpen && event.button() == 0) {
            int ddW = 80;
            int totalH = sortLabels().length * SORT_DD_ROW + 4;
            if (UiKit.inRect(event.x(), event.y(), sortDdX, sortDdY, ddW, totalH)) {
                int idx = (int) ((event.y() - sortDdY - 2) / SORT_DD_ROW);
                if (idx >= 0 && idx < sortLabels().length) {
                    MemoClientState.setMatSort(projectId, idx);
                    scroll = 0;
                }
                sortOpen = false;
                return true;
            }
            sortOpen = false; // 点下拉外部：收起
            if (UiKit.inRect(event.x(), event.y(), sortDdX, sortDdY - 15, 62, 14)) return true; // 点在排序按钮上=只收起
        }
        if (super.mouseClicked(event, doubled)) return true;
        // 材料行点击 → 展开/收起来源明细
        if (tab == 3 && event.button() == 0
                && UiKit.inRect(event.x(), event.y(), matListX, matListTop, matListW, matListBottom - matListTop)) {
            int yAcc = matListTop - (int) scroll;
            for (MatLine line : matLines) {
                if (event.y() >= yAcc && event.y() < yAcc + line.height) {
                    if (line.sub == null) {
                        java.util.Set<String> exp = MemoClientState.getMatExpanded(projectId);
                        if (exp.contains(line.view.item)) exp.remove(line.view.item);
                        else exp.add(line.view.item);
                    }
                    return true;
                }
                yAcc += line.height;
            }
            return true;
        }
        // 子任务行点击 → 子任务详情页
        if (tab == 1 && event.button() == 0
                && UiKit.inRect(event.x(), event.y(), taskListLeft, taskListTop,
                        taskListRight - taskListLeft, taskListBottom - taskListTop)) {
            List<MemoData.Task> tasks = filteredTasks(MemoClientState.data().tasksOf(projectId),
                    MemoClientState.getTaskFilter(projectId));
            int idx = (int) ((event.y() - taskListTop + scroll) / TASK_ROW_H);
            if (idx >= 0 && idx < tasks.size()) {
                this.minecraft.setScreen(new TaskDetailScreen(tasks.get(idx).id));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = clampScroll(scroll - scrollY * 20, Double.MAX_VALUE / 2);
        scroll = Math.max(0, scroll);
        return true;
    }
}