package com.sthstrange.projectmemo.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 添加材料行：中文/ID 搜索物品 → 列表点选 → 盒/组/个 数量输入（按该物品堆叠上限换算）。
 * v0.7 排版修复：列表与数量区之间留出固定间距，提示文字不再重叠。
 */
public final class MemoItemPickerScreen extends MemoScreenBase {

    private static final class ItemEntry {
        final String id;
        final String zh;
        final int maxStack;
        ItemEntry(String id, String zh, int maxStack) { this.id = id; this.zh = zh; this.maxStack = maxStack; }
    }

    private static List<ItemEntry> allItems; // 懒加载缓存
    private static Map<String, String> zhById;

    private final int projectId;
    private String query = "";
    private List<ItemEntry> results = new ArrayList<>();
    private double scroll;
    private int selected = -1;
    private EditBox searchBox, boxBox, stackBox, pieceBox;
    private int listX, listY, listW, listH;

    public MemoItemPickerScreen(int projectId) {
        super("添加材料");
        this.projectId = projectId;
    }

    private static List<ItemEntry> allItems() {
        if (allItems == null) {
            allItems = new ArrayList<>();
            zhById = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                String zh;
                try { zh = I18n.get(item.getDescriptionId()); } catch (Exception e) { zh = id; }
                if (zh.equals(item.getDescriptionId())) zh = "";
                int maxStack;
                try { maxStack = item.getDefaultInstance().getMaxStackSize(); } catch (Exception e2) { maxStack = 64; }
                allItems.add(new ItemEntry(id, zh, maxStack));
                if (!zh.isEmpty()) zhById.put(id, zh);
            }
        }
        return allItems;
    }

    /** 物品中文名查询（材料页共用）：找不到时回退 itemName，再回退 id */
    public static String zhName(String itemId, String fallback) {
        allItems();
        String zh = zhById.get(itemId);
        if (zh != null && !zh.isEmpty()) return zh;
        if (fallback != null && !fallback.isEmpty()) return fallback;
        return itemId;
    }

    /** 物品堆叠上限查询（材料页共用）：找不到返回 64 */
    public static int maxStackOf(String itemId) {
        for (ItemEntry e : allItems()) {
            if (e.id.equals(itemId)) return e.maxStack;
        }
        return 64;
    }

    private void refilter() {
        String q = query.trim().toLowerCase();
        results.clear();
        selected = -1;
        scroll = 0;
        for (ItemEntry e : allItems()) {
            if (q.isEmpty()
                    || (!e.zh.isEmpty() && e.zh.toLowerCase().contains(q))
                    || e.id.toLowerCase().contains(q)) {
                results.add(e);
                if (results.size() >= 300) break;
            }
        }
    }

    private int panelH() { return Math.min(330, this.height - 20); }

    @Override
    protected void buildWidgets() {
        int panelW = Math.min(420, this.width - 20);
        int panelH = panelH();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;

        searchBox = addEditBox(new EditBox(this.font, x0 + 10, y0 + 22, panelW - 20, 16, Component.literal("搜索")));
        searchBox.setMaxLength(40);
        searchBox.setValue(query);
        searchBox.setResponder(s -> { query = s; refilter(); });

        int qy = y0 + panelH - 80;
        boxBox = addEditBox(new EditBox(this.font, x0 + 66, qy, 44, 16, Component.literal("盒")));
        stackBox = addEditBox(new EditBox(this.font, x0 + 136, qy, 44, 16, Component.literal("组")));
        pieceBox = addEditBox(new EditBox(this.font, x0 + 206, qy, 44, 16, Component.literal("个")));
        boxBox.setValue("0");
        stackBox.setValue("0");
        pieceBox.setValue("0");

        refilter();
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = Math.min(420, this.width - 20);
        int panelH = panelH();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        g.drawString(this.font, "添加材料行 · 搜索支持中文名与物品 ID", x0 + 10, y0 + 8, UiKit.TEXT, false);

        // ── 搜索结果列表（固定区域，不与下方重叠） ──
        listX = x0 + 10;
        listW = panelW - 20;
        listY = y0 + 44;
        listH = panelH - 176;
        int rowH = 14;
        double maxScroll = Math.max(0, results.size() * rowH - listH);
        scroll = clampScroll(scroll, maxScroll);

        if (results.isEmpty()) {
            g.drawString(this.font, "（没有匹配的物品）", listX + 4, listY + 6, UiKit.FAINT, false);
        }
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < results.size(); i++) {
            int rowY = listY + i * rowH - (int) scroll;
            if (rowY + rowH < listY || rowY > listY + listH) continue;
            ItemEntry e = results.get(i);
            boolean sel = i == selected;
            boolean hov = UiKit.inRect(mouseX, mouseY, listX, rowY, listW, rowH);
            if (sel) g.fill(listX, rowY, listX + listW, rowY + rowH, 0x60FFFFA0);
            else if (hov) g.fill(listX, rowY, listX + listW, rowY + rowH, 0x30FFFFFF);
            else if (i % 2 == 0) g.fill(listX, rowY, listX + listW, rowY + rowH, 0x10FFFFFF);
            String label = e.zh.isEmpty() ? e.id : e.zh;
            g.drawString(this.font, UiKit.truncPx(this.font, label, listW - 150), listX + 4, rowY + 3,
                    sel ? UiKit.GOLD : UiKit.TEXT, false);
            g.drawString(this.font, UiKit.truncPx(this.font, e.id, 120), listX + listW - 126, rowY + 3, UiKit.FAINT, false);
        }
        g.disableScissor();
        UiKit.scrollbar(g, listX + listW + 2, listY, listH, scroll, maxScroll);

        // ── 列表下方固定信息区（依次排布，不重叠） ──
        int infoY = y0 + panelH - 126;
        g.drawString(this.font, results.size() >= 300 ? "结果过多（上限 300），请输入更精确的关键词"
                        : ("匹配 " + results.size() + " 项 · 点击上方列表选择物品"),
                listX, infoY, UiKit.FAINT, false);

        int selY = y0 + panelH - 110;
        ItemEntry selEntry = selected >= 0 && selected < results.size() ? results.get(selected) : null;
        if (selEntry != null) {
            String selText = "已选: " + (selEntry.zh.isEmpty() ? selEntry.id : selEntry.zh + " (" + selEntry.id + ")")
                    + "  每堆 " + selEntry.maxStack + " 个";
            g.drawString(this.font, UiKit.truncPx(this.font, selText, panelW - 20), x0 + 10, selY, UiKit.GOLD, false);
        } else {
            g.drawString(this.font, "尚未选择物品", x0 + 10, selY, UiKit.FAINT, false);
        }

        // ── 数量区 ──
        int qy = y0 + panelH - 80;
        g.drawString(this.font, "盒", x0 + 50, qy + 4, UiKit.DIM, false);
        g.drawString(this.font, "组", x0 + 120, qy + 4, UiKit.DIM, false);
        g.drawString(this.font, "个", x0 + 190, qy + 4, UiKit.DIM, false);
        int total = currentTotal(selEntry);
        g.drawString(this.font, "= 共 " + (selEntry == null ? UiKit.fmtAmount(total)
                : UiKit.fmtAmount(total, selEntry.maxStack)), x0 + 262, qy + 4,
                total > 0 ? UiKit.GREEN : UiKit.DIM, false);

        int by = y0 + panelH - 24;
        UiKit.UiButton ok = new UiKit.UiButton(x0 + 10, by, 72, 16, "确定添加", this::confirmAdd);
        if (selEntry == null || total <= 0) ok.disabled();
        uiButtons.add(ok);
        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 52, by, 44, 16, "返回", () ->
                this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3)));
        uiButtons.add(back);
    }

    private int parseIntOrZero(EditBox b) {
        try { return Math.max(0, Integer.parseInt(b.getValue().trim())); } catch (Exception e) { return 0; }
    }

    private int currentTotal(ItemEntry e) {
        if (e == null) return 0;
        long total = (long) parseIntOrZero(boxBox) * 27L * e.maxStack
                + (long) parseIntOrZero(stackBox) * e.maxStack
                + parseIntOrZero(pieceBox);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private void confirmAdd() {
        if (selected < 0 || selected >= results.size()) return;
        ItemEntry e = results.get(selected);
        int total = currentTotal(e);
        if (total <= 0) {
            MemoToast.push("数量必须大于 0", MemoToast.RED);
            return;
        }
        JsonObject args = MemoClientState.argsOf("project", projectId, "item", e.id);
        args.addProperty("need", total);
        MemoClientState.sendAction("material_add", args);
        this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3));
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mx = event.x(), my = event.y();
        if (event.button() == 0 && UiKit.inRect(mx, my, listX, listY, listW, listH)) {
            int rowH = 14;
            int idx = (int) ((my - listY + scroll) / rowH);
            if (idx >= 0 && idx < results.size()) {
                selected = idx;
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (UiKit.inRect(mouseX, mouseY, listX, listY, listW, listH)) {
            scroll = Math.max(0, scroll - scrollY * 28);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
