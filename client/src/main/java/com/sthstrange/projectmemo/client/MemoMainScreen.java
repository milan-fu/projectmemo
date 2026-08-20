package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：工程列表（masa 风格）。
 * 行 = 状态符号/标题/创建者/开始时间/子任务与材料进度；顶部搜索 + 状态筛选；
 * 底部 [新建工程]（canCreate 且有额度）；滚轮/拖动滚动。
 */
public final class MemoMainScreen extends MemoScreenBase {

    private static final int ROW_H = 22;
    private static final String[] FILTERS = {"全部", "进行中", "规划中", "已完成", "已归档"};

    private String searchText = "";
    private int filterIndex = 0;      // 0全部(不含归档) 1进行中 2规划中 3已完成 4已归档
    private double scroll;
    private boolean draggingScrollbar;
    private boolean filterOpen;
    private int ddX, ddY;
    private int fbX, fbY;
    private final int ddW = 64, ddRowH = 16, fbW = 52, fbH = 16;
    private EditBox searchBox;
    private List<MemoData.Project> viewRows = new ArrayList<>();
    private int listTop, listBottom, listLeft, listRight;

    public MemoMainScreen() {
        super("工程备忘录");
    }

    @Override
    protected void buildWidgets() {
        int panelW = panelW();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH()) / 2;
        searchBox = new EditBox(this.font, x0 + 8, y0 + 24, (int) (panelW * 0.56), 16, Component.literal("搜索"));
        searchBox.setMaxLength(40);
        searchBox.setValue(searchText);
        searchBox.setResponder(s -> searchText = s);
        addEditBox(searchBox);
    }

    private int panelW() { return Math.min(460, this.width - 16); }

    private int panelH() { return Math.min(330, this.height - 24); }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = panelW();
        int panelH = panelH();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        MemoData data = MemoClientState.data();

        // ── 标题行 ──
        String titleMain = "⚒ 工程备忘录";
        g.drawString(this.font, titleMain, x0 + 8, y0 + 8, UiKit.TEXT, false);
        if (MemoClientState.mirror()) {
            g.drawString(this.font, "（只读镜像 · 数据来自主服）", x0 + 8 + this.font.width(titleMain) + 4, y0 + 8, UiKit.GOLD, false);
        }
        if (MemoClientState.isReady()) {
            long active = data.projects.stream().filter(p -> "active".equals(p.status)).count();
            long planning = data.projects.stream().filter(p -> "planning".equals(p.status)).count();
            long completed = data.projects.stream().filter(p -> "completed".equals(p.status)).count();
            String counts = "进行中 " + active + " · 规划 " + planning + " · 已完成 " + completed;
            g.drawString(this.font, counts, x0 + panelW - 8 - this.font.width(counts), y0 + 8, UiKit.DIM, false);
        } else {
            String wait = "等待服务器响应…";
            g.drawString(this.font, wait, x0 + panelW - 8 - this.font.width(wait), y0 + 8, UiKit.YELLOW, false);
        }

        // ── 搜索框右侧：筛选按钮 + 刷新 ──
        int rx = x0 + panelW - 8;
        UiKit.UiButton refresh = new UiKit.UiButton(rx - 40, y0 + 24, 40, 16, "刷新",
                () -> MemoNetworking.onJoin());
        refresh.tooltip("重新 hello，拉取全量数据");
        uiButtons.add(refresh);
        fbX = rx - 40 - 56;
        fbY = y0 + 24;
        UiKit.UiButton filter = new UiKit.UiButton(fbX, fbY, fbW, fbH, FILTERS[filterIndex] + " ▾",
                () -> filterOpen = !filterOpen);
        filter.tooltip("状态筛选（下拉选择）");
        uiButtons.add(filter);

        // ── 列表区 ──
        listLeft = x0 + 6;
        listRight = x0 + panelW - 14;
        listTop = y0 + 48;
        listBottom = y0 + panelH - 30;
        viewRows = filteredRows(data);

        int contentH = viewRows.size() * ROW_H;
        double maxScroll = Math.max(0, contentH - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);

        if (viewRows.isEmpty()) {
            g.drawString(this.font, MemoClientState.isReady() ? "没有匹配的工程。" : "连接中…",
                    x0 + panelW / 2 - this.font.width(MemoClientState.isReady() ? "没有匹配的工程。" : "连接中…") / 2,
                    listTop + 20, UiKit.DIM, false);
        }

        g.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < viewRows.size(); i++) {
            int rowY = listTop + i * ROW_H - (int) scroll;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            drawRow(g, data, viewRows.get(i), i, rowY, mouseX, mouseY);
        }
        g.disableScissor();

        UiKit.scrollbar(g, x0 + panelW - 10, listTop, listBottom - listTop, scroll, maxScroll);

        // ── 底部按钮行 ──
        int by = y0 + panelH - 24;
        if (MemoClientState.canCreate()) {
            int quota = MemoClientState.quotaLeft();
            UiKit.UiButton create = new UiKit.UiButton(x0 + 8, by, 150, 16,
                    "➕ 新建工程(剩 " + quota + ")", quota > 0 ? this::openCreateDialog : null);
            if (quota <= 0) create.disabled();
            create.tooltip("creator 组每日限 " + quota + " 个（服务器时区自然日）");
            uiButtons.add(create);
        }
        boolean isHome = "main".equals(MemoHome.load());
        UiKit.UiButton home = new UiKit.UiButton(x0 + panelW - 8 - 44 - 64 - 4, by, 64, 16,
                isHome ? "★ 已是首页" : "设为首页", () -> {
                    MemoHome.save("main");
                    MemoToast.push("已设为首页：按 J 直接打开总览", MemoToast.GREEN);
                });
        home.tooltip("把备忘录总览设为首页，按 J 直达");
        if (isHome) home.disabled();
        uiButtons.add(home);
        UiKit.UiButton close = new UiKit.UiButton(x0 + panelW - 8 - 44, by, 44, 16, "关闭", this::onClose);
        uiButtons.add(close);

        // ── 筛选下拉（最后画，盖在最上层） ──
        if (filterOpen) {
            ddX = rx - 40 - 56;
            ddY = y0 + 42;
            int totalH = FILTERS.length * ddRowH + 4;
            UiKit.panel(g, ddX, ddY, ddW, totalH);
            for (int i = 0; i < FILTERS.length; i++) {
                int oy = ddY + 2 + i * ddRowH;
                boolean sel = i == filterIndex;
                boolean hov = UiKit.inRect(mouseX, mouseY, ddX, oy, ddW, ddRowH);
                if (sel) g.fill(ddX + 1, oy, ddX + ddW - 1, oy + ddRowH, 0x50FFFFFF);
                else if (hov) g.fill(ddX + 1, oy, ddX + ddW - 1, oy + ddRowH, 0x30FFFFFF);
                g.drawString(this.font, FILTERS[i], ddX + 6, oy + 4, sel ? UiKit.GOLD : UiKit.TEXT, false);
            }
        }
    }

    private void drawRow(GuiGraphics g, MemoData data, MemoData.Project pr, int index, int rowY, int mouseX, int mouseY) {
        boolean hover = UiKit.inRect(mouseX, mouseY, listLeft, rowY, listRight - listLeft, ROW_H);
        if (hover) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x40FFFFFF);
        if (index % 2 == 0) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x14FFFFFF);

        int cx = listLeft + 4;
        g.drawString(this.font, UiKit.statusSym(pr.status), cx, rowY + 7, UiKit.statusColor(pr.status), false);
        cx += 12;
        g.drawString(this.font, UiKit.truncPx(this.font, pr.title, 110), cx, rowY + 4, UiKit.TEXT, false);
        g.drawString(this.font, "#" + pr.id, cx, rowY + 13, UiKit.FAINT, false);
        cx += 118;

        List<MemoData.Task> tasks = data.tasksOf(pr.id);
        long done = tasks.stream().filter(t -> "done".equals(t.status)).count();
        String meta = pr.creator + " · " + UiKit.fmtDateShort(pr.createdAt) + " 开始";
        g.drawString(this.font, UiKit.truncPx(this.font, meta, 120), cx, rowY + 4, UiKit.DIM, false);
        String prog = "任务 " + done + "/" + tasks.size() + " · 材料 " + data.materialPct(pr.id) + "%";
        g.drawString(this.font, prog, cx, rowY + 13, UiKit.FAINT, false);
        cx += 128;

        if (cx + 60 < listRight - 4) {
            UiKit.UiButton detail = new UiKit.UiButton(listRight - 46, rowY + 3, 40, 16, "详情",
                    () -> openDetail(pr.id));
            uiButtons.add(detail);
        }
    }

    private List<MemoData.Project> filteredRows(MemoData data) {
        List<MemoData.Project> out = new ArrayList<>();
        String q = searchText.toLowerCase();
        for (MemoData.Project pr : data.projects) {
            if (filterIndex == 0) {
                if ("archived".equals(pr.status)) continue;
            } else {
                String want = FILTERS[filterIndex];
                if (!UiKit.statusCn(pr.status).equals(want)) continue;
            }
            if (!q.isEmpty()) {
                boolean hit = pr.title.toLowerCase().contains(q)
                        || pr.creator.toLowerCase().contains(q)
                        || String.valueOf(pr.id).equals(q.trim());
                if (!hit) for (String tag : pr.tags) if (tag.toLowerCase().contains(q)) { hit = true; break; }
                if (!hit) continue;
            }
            out.add(pr);
        }
        out.sort((a, b) -> {
            int wa = weight(a.status), wb = weight(b.status);
            if (wa != wb) return Integer.compare(wa, wb);
            return Long.compare(b.createdAt, a.createdAt);
        });
        return out;
    }

    private static int weight(String status) {
        switch (status) {
            case "active": return 0;
            case "planning": return 1;
            case "completed": return 2;
            default: return 3;
        }
    }

    private void openCreateDialog() {
        this.minecraft.setScreen(new MemoInputDialog(this, "新建工程", "工程标题（≤30 字）", "", 30,
                title -> MemoClientState.sendAction("create_project", MemoClientState.argsOf("title", title))));
    }

    private void openDetail(int projectId) {
        this.minecraft.setScreen(new ProjectDetailScreen(projectId));
    }

    // ───────────────────────── 滚动 ─────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (UiKit.inRect(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listBottom - listTop)) {
            int contentH = viewRows.size() * ROW_H;
            double maxScroll = Math.max(0, contentH - (listBottom - listTop));
            scroll = clampScroll(scroll - scrollY * ROW_H, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        // 筛选下拉优先处理
        if (filterOpen && event.button() == 0) {
            if (UiKit.inRect(mouseX, mouseY, ddX, ddY, ddW, FILTERS.length * ddRowH + 4)) {
                int idx = (int) ((mouseY - ddY - 2) / ddRowH);
                if (idx >= 0 && idx < FILTERS.length) {
                    filterIndex = idx;
                    scroll = 0;
                }
                filterOpen = false;
                return true;
            }
            filterOpen = false; // 点下拉外部：收起
            if (UiKit.inRect(mouseX, mouseY, fbX, fbY, fbW, fbH)) return true; // 点在筛选按钮上=只收起，不再展开
        }
        // 滚动条拖动
        if (event.button() == 0 && UiKit.inRect(mouseX, mouseY, listRight, listTop, 8, listBottom - listTop)) {
            draggingScrollbar = true;
            dragScrollbarTo(mouseY);
            return true;
        }
        if (event.button() == 0 && UiKit.inRect(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listBottom - listTop)) {
            int idx = (int) ((mouseY - listTop + scroll) / ROW_H);
            if (idx >= 0 && idx < viewRows.size()) {
                openDetail(viewRows.get(idx).id);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar && event.button() == 0) {
            dragScrollbarTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    private void dragScrollbarTo(double mouseY) {
        int trackH = listBottom - listTop;
        int contentH = viewRows.size() * ROW_H;
        double maxScroll = Math.max(0, contentH - trackH);
        if (trackH <= 0) return;
        double ratio = (mouseY - listTop) / trackH;
        scroll = clampScroll(ratio * maxScroll, maxScroll);
    }
}
