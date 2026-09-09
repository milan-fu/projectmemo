package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.2.1 机器使用说明页：已收录（inManual）工程列表，正文=工程描述本身。
 * 归档工程只要仍收录就继续显示（不消失）；整行点击进工程详情（[详情] 按钮同样可用）；
 * 带搜索框与刷新按钮（刷新=向服务端重取全量快照）；可设为首页。收录开关在工程信息页（管理者+OP）。
 */
public final class MemoManualScreen extends MemoScreenBase {

    private static final int ROW_H = 22;

    private String searchText = "";
    private double scroll;
    private double maxScroll;
    private int listLeft, listRight, listTop, listBottom;
    private EditBox searchBox;
    private final List<int[]> entryBoxes = new ArrayList<>();   // 帧内命中盒 {y, projectId}

    public MemoManualScreen() { super(L10n.get("projectmemo.manual.title")); }

    @Override
    protected void buildWidgets() {
        int panelW = panelW();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH()) / 2;
        searchBox = new EditBox(this.font, x0 + 8, y0 + 24, (int) (panelW * 0.5), 16,
                Component.literal(L10n.get("projectmemo.manual.search")));
        searchBox.setMaxLength(30);
        searchBox.setValue(searchText);
        searchBox.setResponder(s -> searchText = s);
        addEditBox(searchBox);
    }

    private int panelW() { return Math.min(460, this.width - 16); }

    private int panelH() { return Math.min(330, this.height - 24); }

    private List<MemoData.Project> rows() {
        List<MemoData.Project> out = new ArrayList<>();
        String q = searchText.toLowerCase();
        for (MemoData.Project pr : MemoClientState.data().projects) {
            if (!pr.inManual) continue;   // 归档工程只要仍收录也显示（服主定）
            if (!q.isEmpty()
                    && !pr.title.toLowerCase().contains(q)
                    && !pr.creator.toLowerCase().contains(q)
                    && !pr.desc.toLowerCase().contains(q)) continue;
            out.add(pr);
        }
        out.sort((a, b) -> Long.compare(
                b.completedAt > 0 ? b.completedAt : b.createdAt,
                a.completedAt > 0 ? a.completedAt : a.createdAt));
        return out;
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = panelW(), panelH = panelH();
        int x0 = (this.width - panelW) / 2, y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        MemoNav.draw(g, this.font, uiButtons, x0, y0, 2);

        List<MemoData.Project> rows = rows();
        int rx = x0 + panelW - 8;
        String counts = L10n.get("projectmemo.manual.count", rows.size());
        g.drawString(this.font, counts, rx - this.font.width(counts), y0 + 8, UiKit.DIM, false);
        UiKit.UiButton refresh = new UiKit.UiButton(rx - 40, y0 + 24, 40, 16,
                L10n.get("projectmemo.common.refresh"), () -> {
                    MemoClientState.sendAction("request_sync", null);
                    MemoToast.push(L10n.get("projectmemo.manual.refreshing"), MemoToast.GREEN);
                });
        refresh.tooltip(L10n.get("projectmemo.manual.refreshTip"));
        uiButtons.add(refresh);

        listLeft = x0 + 6;
        listRight = x0 + panelW - 14;
        listTop = y0 + 48;
        listBottom = y0 + panelH - 30;

        entryBoxes.clear();
        if (rows.isEmpty()) {
            String msg = (!searchText.isEmpty() && !MemoClientState.data().projects.isEmpty())
                    ? L10n.get("projectmemo.main.noMatch") : L10n.get("projectmemo.manual.empty");
            g.drawString(this.font, msg, x0 + panelW / 2 - this.font.width(msg) / 2, listTop + 20, UiKit.DIM, false);
        }
        maxScroll = Math.max(0, rows.size() * ROW_H - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);
        g.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            int rowY = listTop + i * ROW_H - (int) scroll;
            MemoData.Project pr = rows.get(i);
            entryBoxes.add(new int[]{rowY, pr.id});
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            boolean hover = UiKit.inRect(mouseX, mouseY, listLeft, rowY, listRight - listLeft, ROW_H);
            if (hover) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x40FFFFFF);
            if (i % 2 == 0) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x14FFFFFF);
            int cx = listLeft + 4;
            g.drawString(this.font, UiKit.statusSym(pr.status), cx, rowY + 7, UiKit.statusColor(pr.status), false);
            cx += 12;
            g.drawString(this.font, UiKit.truncPx(this.font, pr.title, 150), cx, rowY + 3, UiKit.TEXT, false);
            String meta = pr.creator + " · "
                    + (pr.completedAt > 0 ? UiKit.fmtDateShort(pr.completedAt) : UiKit.fmtDateShort(pr.createdAt));
            g.drawString(this.font, meta, cx, rowY + 12, UiKit.FAINT, false);
            UiKit.UiButton det = new UiKit.UiButton(listRight - 46, rowY + 3, 40, 16,
                    L10n.get("projectmemo.main.detail"),
                    () -> this.minecraft.setScreen(new ProjectDetailScreen(pr.id, 0, ProjectDetailScreen.FROM_MANUAL)));
            det.tooltip(L10n.get("projectmemo.manual.detailTip"));
            uiButtons.add(det);
        }
        g.disableScissor();
        UiKit.scrollbar(g, x0 + panelW - 10, listTop, listBottom - listTop, scroll, maxScroll);

        int by = y0 + panelH - 24;
        g.drawString(this.font, L10n.get("projectmemo.manual.hint"), x0 + 8, by + 4, UiKit.FAINT, false);
        boolean isHome = "manual".equals(MemoHome.load());
        UiKit.UiButton home = new UiKit.UiButton(x0 + panelW - 8 - 44 - 64 - 4, by, 64, 16,
                isHome ? L10n.get("projectmemo.main.isHome") : L10n.get("projectmemo.main.setHome"), () -> {
                    MemoHome.save("manual");
                    MemoToast.push(L10n.get("projectmemo.main.homeSet"), MemoToast.GREEN);
                });
        home.tooltip(L10n.get("projectmemo.main.homeTip"));
        if (isHome) home.disabled();
        uiButtons.add(home);
        UiKit.UiButton close = new UiKit.UiButton(x0 + panelW - 8 - 44, by, 44, 16,
                L10n.get("projectmemo.common.close"), this::onClose);
        uiButtons.add(close);
    }

    /** 整行点击进工程详情；[详情] 按钮/刷新/导航/首页/关闭 由 super 优先消费 */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() != 0) return false;
        double mx = event.x(), my = event.y();
        if (!UiKit.inRect(mx, my, listLeft, listTop, listRight - listLeft, listBottom - listTop)) return false;
        for (int[] eb : entryBoxes) {
            if (UiKit.inRect(mx, my, listLeft, eb[0], listRight - listLeft, ROW_H)) {
                this.minecraft.setScreen(new ProjectDetailScreen(eb[1], 0, ProjectDetailScreen.FROM_MANUAL));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (UiKit.inRect(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listBottom - listTop)) {
            scroll = clampScroll(scroll - scrollY * ROW_H, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
