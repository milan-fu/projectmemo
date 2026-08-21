package com.sthstrange.projectmemo.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;

/**
 * 选址编辑屏：手动输入坐标 / 取脚下 / 隐藏切换 / 锁（防误改）。
 * 锁定实时生效（输入框立刻变灰/变白）；保存后停留本页；
 * 未保存修改（坐标/备注）退出时二次确认。
 */
public final class MemoLocationScreen extends MemoScreenBase {

    private final int projectId;
    private EditBox xBox, yBox, zBox, noteBox;
    private String snapX = "", snapY = "", snapZ = "", snapNote = "";

    public MemoLocationScreen(int projectId) {
        super(L10n.get("projectmemo.loc.title"));
        this.projectId = projectId;
    }

    private MemoData.Project pr() {
        return MemoClientState.data().projectById(projectId);
    }

    private boolean dirty() {
        if (xBox == null) return false; // 工程不存在（控件未建），无未保存修改可言
        return !snapX.equals(xBox.getValue().trim()) || !snapY.equals(yBox.getValue().trim())
                || !snapZ.equals(zBox.getValue().trim()) || !snapNote.equals(noteBox.getValue().trim());
    }

    private void takeSnapshot() {
        snapX = xBox.getValue().trim();
        snapY = yBox.getValue().trim();
        snapZ = zBox.getValue().trim();
        snapNote = noteBox.getValue().trim();
    }

    /** 锁状态变化时实时刷新输入框可用性（无需退出重进） */
    private void applyLockToBoxes(boolean locked) {
        if (xBox == null) return;
        xBox.setEditable(!locked);
        yBox.setEditable(!locked);
        zBox.setEditable(!locked);
        noteBox.setEditable(!locked);
    }

    @Override
    protected void buildWidgets() {
        MemoData.Project pr = pr();
        if (pr == null) return;
        int panelW = Math.min(320, this.width - 30);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - 170) / 2;

        xBox = addEditBox(new EditBox(this.font, x0 + 50, y0 + 46, 70, 16, net.minecraft.network.chat.Component.literal("X")));
        yBox = addEditBox(new EditBox(this.font, x0 + 140, y0 + 46, 70, 16, net.minecraft.network.chat.Component.literal("Y")));
        zBox = addEditBox(new EditBox(this.font, x0 + 230, y0 + 46, 70, 16, net.minecraft.network.chat.Component.literal("Z")));
        xBox.setValue(String.valueOf(pr.locX));
        yBox.setValue(String.valueOf(pr.locY));
        zBox.setValue(String.valueOf(pr.locZ));

        noteBox = addEditBox(new EditBox(this.font, x0 + 50, y0 + 70, panelW - 60, 16, net.minecraft.network.chat.Component.literal(L10n.get("projectmemo.loc.noteField"))));
        noteBox.setMaxLength(60);
        noteBox.setValue(pr.locNote);

        takeSnapshot();
        applyLockToBoxes(pr.locLocked);
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        MemoData.Project pr = pr();
        if (pr == null) {
            // 工程已不存在（开着本页时被删）：给提示+返回按钮，避免黑屏与 ESC/dirty 的 NPE
            int panelW0 = Math.min(320, this.width - 30);
            int x00 = (this.width - panelW0) / 2, y00 = (this.height - 170) / 2;
            UiKit.panel(g, x00, y00, panelW0, 170);
            g.drawString(this.font, L10n.get("projectmemo.pd.projectGone"), x00 + 12, y00 + 20, UiKit.RED, false);
            UiKit.UiButton back = new UiKit.UiButton(x00 + panelW0 - 62, y00 + 170 - 24, 52, 16, L10n.get("projectmemo.common.back"),
                    () -> this.minecraft.setScreen(new MemoMainScreen()));
            uiButtons.add(back);
            return;
        }
        int panelW = Math.min(320, this.width - 30);
        int panelH = 170;
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        g.drawString(this.font, L10n.get("projectmemo.loc.header", UiKit.trunc(pr.title, 16)), x0 + 10, y0 + 8, UiKit.TEXT, false);
        // 世界：显示玩家当前所在世界（保存时选址就记这个世界）
        String curWorld = UiKit.worldCn(null);
        if (Minecraft.getInstance().player != null) {
            curWorld = UiKit.worldCn(Minecraft.getInstance().player.level().dimension().identifier().toString());
        }
        String worldInfo = pr.locWorld.isEmpty()
                ? L10n.get("projectmemo.loc.worldCurrent", curWorld)
                : L10n.get("projectmemo.loc.worldSaved", UiKit.worldCn(pr.locWorld), curWorld);
        g.drawString(this.font, worldInfo, x0 + 10, y0 + 22, UiKit.DIM, false);
        if (pr.locLocked) {
            g.drawString(this.font, "🔒", x0 + 10 + this.font.width(worldInfo) + 6, y0 + 22, UiKit.GOLD, false);
        }

        g.drawString(this.font, "X", x0 + 40, y0 + 50, UiKit.DIM, false);
        g.drawString(this.font, "Y", x0 + 130, y0 + 50, UiKit.DIM, false);
        g.drawString(this.font, "Z", x0 + 220, y0 + 50, UiKit.DIM, false);
        g.drawString(this.font, L10n.get("projectmemo.loc.noteLabel"), x0 + 10, y0 + 74, UiKit.DIM, false);

        boolean locked = pr.locLocked;
        int by = y0 + 96;
        UiKit.UiButton here = new UiKit.UiButton(x0 + 10, by, 62, 16, L10n.get("projectmemo.loc.here"), locked ? null : () -> {
            if (Minecraft.getInstance().player == null) return;
            var pos = Minecraft.getInstance().player.blockPosition();
            xBox.setValue(String.valueOf(pos.getX()));
            yBox.setValue(String.valueOf(pos.getY()));
            zBox.setValue(String.valueOf(pos.getZ()));
        });
        if (locked) here.disabled();
        here.tooltip(L10n.get("projectmemo.loc.hereTip"));
        uiButtons.add(here);

        UiKit.UiButton lockBtn = new UiKit.UiButton(x0 + 158, by, 66, 16, locked ? L10n.get("projectmemo.common.unlock") : L10n.get("projectmemo.common.lock"), () -> {
            JsonObject args = MemoClientState.argsOf("project", pr.id);
            args.addProperty("locked", !locked);
            MemoClientState.sendAction("set_loc_lock", args);
            applyLockToBoxes(!locked); // 输入框立刻变灰/变白，不用退出重进
        });
        lockBtn.tooltip(locked ? L10n.get("projectmemo.loc.unlockTip") : L10n.get("projectmemo.loc.lockTip"));
        uiButtons.add(lockBtn);

        UiKit.UiButton hideBtn = new UiKit.UiButton(x0 + 230, by, 80, 16, pr.locHidden ? L10n.get("projectmemo.loc.unhide") : L10n.get("projectmemo.loc.hide"), locked ? null : () ->
                MemoClientState.sendAction("set_loc_hidden", MemoClientState.argsOf("project", pr.id)));
        if (locked) hideBtn.disabled();
        hideBtn.tooltip(pr.locHidden ? L10n.get("projectmemo.loc.unhideTip") : L10n.get("projectmemo.loc.hideTip"));
        uiButtons.add(hideBtn);

        int by2 = y0 + panelH - 24;
        UiKit.UiButton save = new UiKit.UiButton(x0 + 10, by2, 52, 16, L10n.get("projectmemo.common.save"), locked ? null : () -> {
            try {
                int x = Integer.parseInt(xBox.getValue().trim());
                int y = Integer.parseInt(yBox.getValue().trim());
                int z = Integer.parseInt(zBox.getValue().trim());
                JsonObject args = MemoClientState.argsOf("project", pr.id, "x", x);
                args.addProperty("y", y);
                args.addProperty("z", z);
                args.addProperty("note", noteBox.getValue().trim());
                MemoClientState.sendAction("set_location_manual", args);
                takeSnapshot(); // 保存后不再算"未保存修改"
                MemoToast.push(L10n.get("projectmemo.loc.saved"), MemoToast.GREEN);
            } catch (NumberFormatException e) {
                MemoToast.push(L10n.get("projectmemo.loc.intOnly"), MemoToast.RED);
            }
        });
        if (locked) save.disabled();
        uiButtons.add(save);

        UiKit.UiButton clear = new UiKit.UiButton(x0 + 70, by2, 52, 16, L10n.get("projectmemo.common.clear"), locked ? null : () ->
                this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.loc.clearTitle"), L10n.get("projectmemo.loc.clearMsg"), true, () -> {
                    MemoClientState.sendAction("clear_location", MemoClientState.argsOf("project", pr.id));
                    this.minecraft.setScreen(new ProjectDetailScreen(projectId));
                })));
        if (locked) clear.disabled();
        uiButtons.add(clear);

        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 62, by2, 52, 16, L10n.get("projectmemo.common.back"), this::tryLeave);
        uiButtons.add(back);

        if (dirty()) {
            String dirtyMark = L10n.get("projectmemo.loc.dirty");
            g.drawString(this.font, dirtyMark, x0 + panelW - 10 - this.font.width(dirtyMark), y0 + 8, UiKit.GOLD, false);
        }
    }

    /** 退出：有未保存修改时二次确认 */
    private void tryLeave() {
        if (dirty()) {
            this.minecraft.setScreen(new MemoConfirmDialog(this, L10n.get("projectmemo.loc.unsavedTitle"),
                    L10n.get("projectmemo.loc.unsavedMsg"), true,
                    () -> this.minecraft.setScreen(new ProjectDetailScreen(projectId))));
        } else {
            this.minecraft.setScreen(new ProjectDetailScreen(projectId));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC：有未保存修改先确认
            tryLeave();
            return true;
        }
        return super.keyPressed(event);
    }
}
