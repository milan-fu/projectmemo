package com.sthstrange.projectmemo.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.2.1 服务器地标页（只读）：数据 = 服务端 landmarks_list（本机 LocationMarker locations.json）。
 * 维度分组可折叠（默认展开）；整行点击跳工程详情（复制/[详情] 按钮优先，不误触）；
 * 备注带「工程#<id>」标记且工程存在 → 可跳转；标记但工程已删 → 提示；其余显示备注或「暂无详情页」。
 */
public final class MemoLandmarksScreen extends MemoScreenBase {

    private static final Pattern PROJECT_MARK = Pattern.compile("工程#(\\d+)");
    private static final int ROW_H = 20;
    private static final int HEADER_H = 14;

    private static JsonArray cache;                      // 会话级缓存（跨屏幕实例）
    private static boolean loading;
    private static final Set<Integer> collapsed = new HashSet<>();   // 折叠的维度（默认全展开）

    private String searchText = "";
    private double scroll;
    private double maxScroll;
    private EditBox searchBox;
    private int listLeft, listRight, listTop, listBottom;
    /** 帧内命中盒：{y, dim}（分组头）与 {y, projectId}（条目行），点击用 */
    private final List<int[]> headerBoxes = new ArrayList<>();
    private final List<int[]> entryBoxes = new ArrayList<>();

    public MemoLandmarksScreen() { super(L10n.get("projectmemo.lm.title")); }

    @Override
    protected void buildWidgets() {
        int panelW = panelW();
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH()) / 2;
        searchBox = new EditBox(this.font, x0 + 8, y0 + 24, (int) (panelW * 0.5), 16,
                Component.literal(L10n.get("projectmemo.lm.search")));
        searchBox.setMaxLength(30);
        searchBox.setValue(searchText);
        searchBox.setResponder(s -> searchText = s);
        addEditBox(searchBox);
        if (cache == null && !loading) request();
    }

    private static void request() {
        loading = true;
        MemoClientState.sendAction("landmarks_list", null, ack -> {
            loading = false;
            if (MemoData.optBool(ack, "ok")) {
                String msg = MemoData.optStr(ack, "message", "[]");
                try {
                    cache = JsonParser.parseString(msg == null || msg.isEmpty() ? "[]" : msg).getAsJsonArray();
                } catch (Exception e) {
                    cache = new JsonArray();
                }
            } else {
                cache = null;
                MemoToast.push(L10n.get("projectmemo.toast.failed"), MemoToast.RED);
            }
        });
    }

    private int panelW() { return Math.min(460, this.width - 16); }

    private int panelH() { return Math.min(330, this.height - 24); }

    private static final class Row {
        boolean header;
        String text;
        int dim;
        JsonObject lm;
        int projectId = -1;
        boolean projectGone;
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (cache == null) return out;
        String q = searchText.toLowerCase();
        for (int dim : new int[]{0, -1, 1}) {
            List<JsonObject> group = new ArrayList<>();
            for (JsonElement e : cache) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (MemoData.optInt(o, "dim") != dim) continue;
                String name = MemoData.optStr(o, "name");
                String desc = MemoData.optStr(o, "desc");
                if (!q.isEmpty() && !name.toLowerCase().contains(q) && !desc.toLowerCase().contains(q)) continue;
                group.add(o);
            }
            if (group.isEmpty()) continue;
            group.sort((a, b) -> MemoData.optStr(a, "name").compareToIgnoreCase(MemoData.optStr(b, "name")));
            Row h = new Row();
            h.header = true;
            h.dim = dim;
            h.text = dimLabel(dim) + " · " + group.size();
            out.add(h);
            if (collapsed.contains(dim)) continue;   // 折叠：不列条目
            for (JsonObject o : group) {
                Row r = new Row();
                r.lm = o;
                Matcher m = PROJECT_MARK.matcher(MemoData.optStr(o, "desc"));
                if (m.find()) {
                    int pid = Integer.parseInt(m.group(1));
                    if (MemoClientState.data().projectById(pid) != null) r.projectId = pid;
                    else r.projectGone = true;
                }
                out.add(r);
            }
        }
        return out;
    }

    private static String dimLabel(int dim) {
        switch (dim) {
            case 0: return L10n.get("projectmemo.world.overworld");
            case -1: return L10n.get("projectmemo.world.nether");
            case 1: return L10n.get("projectmemo.world.end");
            default: return L10n.get("projectmemo.lm.dimUnknown", dim);
        }
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = panelW(), panelH = panelH();
        int x0 = (this.width - panelW) / 2, y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);
        MemoNav.draw(g, this.font, uiButtons, x0, y0, 1);

        int rx = x0 + panelW - 8;
        if (cache != null) {
            String counts = L10n.get("projectmemo.lm.count", cache.size());
            g.drawString(this.font, counts, rx - this.font.width(counts), y0 + 8, UiKit.DIM, false);
        }
        UiKit.UiButton refresh = new UiKit.UiButton(rx - 40, y0 + 24, 40, 16, L10n.get("projectmemo.common.refresh"), () -> {
            cache = null;
            request();
        });
        refresh.tooltip(L10n.get("projectmemo.lm.refreshTip"));
        uiButtons.add(refresh);

        listLeft = x0 + 6;
        listRight = x0 + panelW - 14;
        listTop = y0 + 48;
        listBottom = y0 + panelH - 30;

        headerBoxes.clear();
        entryBoxes.clear();

        if (cache == null) {
            String msg = loading ? L10n.get("projectmemo.lm.loading") : L10n.get("projectmemo.lm.loadHint");
            g.drawString(this.font, msg, x0 + panelW / 2 - this.font.width(msg) / 2, listTop + 20, UiKit.DIM, false);
        } else {
            List<Row> rows = rows();
            if (rows.isEmpty()) {
                String msg = (cache.size() > 0 && !searchText.isEmpty())
                        ? L10n.get("projectmemo.main.noMatch") : L10n.get("projectmemo.lm.empty");
                g.drawString(this.font, msg, x0 + panelW / 2 - this.font.width(msg) / 2, listTop + 20, UiKit.DIM, false);
            }
            int contentH = 0;
            for (Row r : rows) contentH += r.header ? HEADER_H : ROW_H;
            maxScroll = Math.max(0, contentH - (listBottom - listTop));
            scroll = clampScroll(scroll, maxScroll);
            g.enableScissor(listLeft, listTop, listRight, listBottom);
            int y = listTop - (int) scroll;
            for (Row r : rows) {
                if (r.header) {
                    if (y + HEADER_H > listTop && y < listBottom) {
                        boolean open = !collapsed.contains(r.dim);
                        g.drawString(this.font, (open ? "▾ " : "▸ ") + r.text, listLeft + 4, y + 3, UiKit.YELLOW, false);
                    }
                    headerBoxes.add(new int[]{y, r.dim});
                    y += HEADER_H;
                } else {
                    if (y + ROW_H > listTop && y < listBottom) drawEntry(g, r, y, mouseX, mouseY);
                    entryBoxes.add(new int[]{y, r.projectId});
                    y += ROW_H;
                }
            }
            g.disableScissor();
            UiKit.scrollbar(g, x0 + panelW - 10, listTop, listBottom - listTop, scroll, maxScroll);
        }

        int by = y0 + panelH - 24;
        g.drawString(this.font, L10n.get("projectmemo.lm.manageHint"), x0 + 8, by + 4, UiKit.FAINT, false);
        boolean isHome = "landmarks".equals(MemoHome.load());
        UiKit.UiButton home = new UiKit.UiButton(x0 + panelW - 8 - 44 - 64 - 4, by, 64, 16,
                isHome ? L10n.get("projectmemo.main.isHome") : L10n.get("projectmemo.main.setHome"), () -> {
                    MemoHome.save("landmarks");
                    MemoToast.push(L10n.get("projectmemo.main.homeSet"), MemoToast.GREEN);
                });
        home.tooltip(L10n.get("projectmemo.main.homeTip"));
        if (isHome) home.disabled();
        uiButtons.add(home);
        UiKit.UiButton close = new UiKit.UiButton(x0 + panelW - 8 - 44, by, 44, 16,
                L10n.get("projectmemo.common.close"), this::onClose);
        uiButtons.add(close);
    }

    private void drawEntry(GuiGraphics g, Row r, int rowY, int mouseX, int mouseY) {
        boolean hover = UiKit.inRect(mouseX, mouseY, listLeft, rowY, listRight - listLeft, ROW_H);
        if (hover) g.fill(listLeft, rowY, listRight, rowY + ROW_H, 0x40FFFFFF);
        JsonObject o = r.lm;
        String name = MemoData.optStr(o, "name");
        String desc = MemoData.optStr(o, "desc");
        int x = MemoData.optInt(o, "x"), y = MemoData.optInt(o, "y"), z = MemoData.optInt(o, "z");
        int cx = listLeft + 10;
        g.drawString(this.font, UiKit.truncPx(this.font, name, 110), cx, rowY + 6, UiKit.TEXT, false);
        cx += 116;
        String coord = x + ", " + y + ", " + z;
        g.drawString(this.font, coord, cx, rowY + 6, UiKit.DIM, false);
        cx += this.font.width(coord) + 6;
        UiKit.UiButton copy = new UiKit.UiButton(cx, rowY + 2, 34, 15, L10n.get("projectmemo.lm.copy"), () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(x + " " + y + " " + z);
            MemoToast.push(L10n.get("projectmemo.lm.copied"), MemoToast.GREEN);
        });
        copy.tooltip(L10n.get("projectmemo.lm.copyTip"));
        uiButtons.add(copy);
        if (r.projectId > 0) {
            UiKit.UiButton det = new UiKit.UiButton(listRight - 46, rowY + 2, 40, 15,
                    L10n.get("projectmemo.main.detail"),
                    () -> this.minecraft.setScreen(new ProjectDetailScreen(r.projectId, 0, ProjectDetailScreen.FROM_LANDMARKS)));
            det.tooltip(L10n.get("projectmemo.lm.detailTip"));
            uiButtons.add(det);
        } else {
            String tail = r.projectGone ? L10n.get("projectmemo.lm.projectGone")
                    : (desc.isEmpty() ? L10n.get("projectmemo.lm.noDetail")
                    : UiKit.truncPx(this.font, desc, Math.max(40, listRight - (cx + 44) - 8)));
            g.drawString(this.font, tail, cx + 44, rowY + 6, UiKit.FAINT, false);
        }
    }

    /** 整行点击跳详情；按钮（复制/[详情]/刷新/导航/首页/关闭）由 super 优先消费，不会误触 */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() != 0) return false;
        double mx = event.x(), my = event.y();
        if (!UiKit.inRect(mx, my, listLeft, listTop, listRight - listLeft, listBottom - listTop)) return false;
        for (int[] hb : headerBoxes) {
            if (UiKit.inRect(mx, my, listLeft, hb[0], listRight - listLeft, HEADER_H)) {
                if (!collapsed.remove(hb[1])) collapsed.add(hb[1]);   // 折叠/展开切换
                return true;
            }
        }
        for (int[] eb : entryBoxes) {
            if (UiKit.inRect(mx, my, listLeft, eb[0], listRight - listLeft, ROW_H)) {
                if (eb[1] > 0) this.minecraft.setScreen(new ProjectDetailScreen(eb[1], 0, ProjectDetailScreen.FROM_LANDMARKS));
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
