package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 材料页筛选：两个分类——按投影来源、按认领玩家（含未认领）。
 * 勾选为暂存，点 [确认] 才应用；[全选] 一键恢复不过滤。会话内按工程保存。
 */
public final class MemoSourceFilterScreen extends MemoScreenBase {

    private static final int ROW_H = 18;
    private static final String UNCLAIMED = "§unclaimed";
    private final int projectId;
    private double scroll;
    private int listTop, listBottom, listLeft, listRight;

    /** 暂存的勾选（null=该分类不过滤） */
    private Set<String> selSources;
    private Set<String> selClaimers;
    private List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        boolean header;
        String section;  // "source" | "claimer"
        String value;
        String label;
    }

    public MemoSourceFilterScreen(int projectId) {
        super(L10n.get("projectmemo.filter.title"));
        this.projectId = projectId;
        Set<String> s = MemoClientState.getMatFilter(projectId);
        selSources = s == null ? null : new LinkedHashSet<>(s);
        Set<String> c = MemoClientState.getMatClaimFilter(projectId);
        selClaimers = c == null ? null : new LinkedHashSet<>(c);
    }

    private static String srcLabel(String src) {
        return "custom".equals(src) ? L10n.get("projectmemo.filter.customSource") : src;
    }

    private void buildEntries() {
        entries.clear();
        MemoData data = MemoClientState.data();
        // 来源分类
        Entry hs = new Entry(); hs.header = true; hs.section = "source"; hs.label = L10n.get("projectmemo.filter.bySource"); entries.add(hs);
        Set<String> sources = new LinkedHashSet<>();
        for (MemoData.MaterialRow m : data.materialsOf(projectId)) {
            sources.add(m.source == null || m.source.isEmpty() ? "custom" : m.source);
        }
        for (String src : sources) {
            Entry e = new Entry(); e.section = "source"; e.value = src; e.label = srcLabel(src); entries.add(e);
        }
        // 认领分类
        Entry hc = new Entry(); hc.header = true; hc.section = "claimer"; hc.label = L10n.get("projectmemo.filter.byClaimer"); entries.add(hc);
        Set<String> claimers = new LinkedHashSet<>();
        for (MemoData.Task t : data.tasksOf(projectId)) {
            if ("collect".equals(t.type) && !"done".equals(t.status) && !t.assignee.isEmpty()) claimers.add(t.assignee);
        }
        for (String c : claimers) {
            Entry e = new Entry(); e.section = "claimer"; e.value = c; e.label = c; entries.add(e);
        }
        Entry un = new Entry(); un.section = "claimer"; un.value = UNCLAIMED; un.label = L10n.get("projectmemo.filter.unclaimed"); entries.add(un);
    }

    private boolean isSelected(Entry e) {
        Set<String> sel = "source".equals(e.section) ? selSources : selClaimers;
        return sel == null || sel.contains(e.value);
    }

    private void toggle(Entry e) {
        Set<String> all = "source".equals(e.section) ? allValues("source") : allValues("claimer");
        Set<String> sel = "source".equals(e.section) ? selSources : selClaimers;
        Set<String> now = sel == null ? new LinkedHashSet<>(all) : new LinkedHashSet<>(sel);
        if (now.contains(e.value)) now.remove(e.value); else now.add(e.value);
        boolean isAll = now.size() == all.size() && now.containsAll(all);
        if ("source".equals(e.section)) selSources = isAll ? null : now;
        else selClaimers = isAll ? null : now;
    }

    private Set<String> allValues(String section) {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (!e.header && section.equals(e.section)) out.add(e.value);
        }
        return out;
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        buildEntries();
        int panelW = Math.min(320, this.width - 20);
        int panelH = Math.min(300, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        g.drawString(this.font, L10n.get("projectmemo.filter.hint"), x0 + 10, y0 + 8, UiKit.TEXT, false);

        listLeft = x0 + 8;
        listRight = x0 + panelW - 8;
        listTop = y0 + 22;
        listBottom = y0 + panelH - 30;
        double maxScroll = Math.max(0, entries.size() * ROW_H - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);

        g.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < entries.size(); i++) {
            int rowY = listTop + i * ROW_H - (int) scroll;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            Entry e = entries.get(i);
            if (e.header) {
                g.drawString(this.font, "— " + e.label + " —", listLeft + 6, rowY + 5, UiKit.GOLD, false);
                UiKit.UiButton allSec = new UiKit.UiButton(listRight - 78, rowY + 1, 34, 14, L10n.get("projectmemo.common.selectAll"), () -> {
                    if ("source".equals(e.section)) selSources = null; else selClaimers = null;
                });
                uiButtons.add(allSec);
                UiKit.UiButton noneSec = new UiKit.UiButton(listRight - 40, rowY + 1, 38, 14, L10n.get("projectmemo.common.selectNone"), () -> {
                    if ("source".equals(e.section)) selSources = new LinkedHashSet<>();
                    else selClaimers = new LinkedHashSet<>();
                });
                uiButtons.add(noneSec);
                continue;
            }
            boolean on = isSelected(e);
            if (i % 2 == 0) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x0DFFFFFF);
            boolean hover = UiKit.inRect(mouseX, mouseY, listLeft, rowY, listRight - listLeft, ROW_H);
            if (hover) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x20FFFFFF);
            g.drawString(this.font, (on ? "☑ " : "☐ ") + UiKit.truncPx(this.font, e.label, panelW - 60),
                    listLeft + 8, rowY + 5, on ? UiKit.TEXT : UiKit.FAINT, false);
        }
        g.disableScissor();
        UiKit.scrollbar(g, listRight + 1, listTop, listBottom - listTop, scroll, maxScroll);

        int by = y0 + panelH - 24;
        g.drawString(this.font, L10n.get("projectmemo.filter.stagedHint"), x0 + 10, by + 4, UiKit.FAINT, false);
        UiKit.UiButton confirm = new UiKit.UiButton(x0 + panelW - 104, by, 48, 16, L10n.get("projectmemo.common.confirm"), () -> {
            MemoClientState.setMatFilter(projectId, selSources);
            MemoClientState.setMatClaimFilter(projectId, selClaimers);
            this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3));
        });
        uiButtons.add(confirm);
        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 52, by, 44, 16, L10n.get("projectmemo.common.back"), () ->
                this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3)));
        uiButtons.add(back);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() == 0
                && UiKit.inRect(event.x(), event.y(), listLeft, listTop, listRight - listLeft, listBottom - listTop)) {
            int idx = (int) ((event.y() - listTop + scroll) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                Entry e = entries.get(idx);
                if (!e.header) toggle(e);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - scrollY * 18);
        return true;
    }
}
