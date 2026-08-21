package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** masa 风格绘制工具：深色面板、自绘按钮、进度条、格式化（与服务端 ChatUI 对齐）。 */
public final class UiKit {

    public static final int BORDER = 0xFF000000;
    public static final int PANEL_EDGE = 0xFF2A2A35;
    public static final int PANEL_BG = 0xD8101018;
    public static final int TEXT = 0xFFE0E0E0;
    public static final int DIM = 0xFF989898;
    public static final int FAINT = 0xFF606068;
    public static final int GOLD = 0xFFFFC020;
    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int YELLOW = 0xFFFFFF55;

    private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_SHORT = DateTimeFormatter.ofPattern("MM-dd");

    private UiKit() { }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, BORDER);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PANEL_EDGE);
        g.fill(x, y, x + w, y + h, PANEL_BG);
    }

    public static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void button(GuiGraphics g, Font font, UiButton b, int mouseX, int mouseY) {
        boolean hover = b.enabled && inRect(mouseX, mouseY, b.x, b.y, b.w, b.h);
        int bg;
        if (!b.enabled) bg = 0xFF18181E;
        else if (b.danger) bg = hover ? 0xFF702020 : 0xFF481818;
        else bg = hover ? 0xFF4A4A5A : 0xFF2C2C38;
        g.fill(b.x, b.y, b.x + b.w, b.y + b.h, bg);
        g.fill(b.x, b.y, b.x + b.w, b.y + 1, BORDER);
        g.fill(b.x, b.y + b.h - 1, b.x + b.w, b.y + b.h, BORDER);
        g.fill(b.x, b.y, b.x + 1, b.y + b.h, BORDER);
        g.fill(b.x + b.w - 1, b.y, b.x + b.w, b.y + b.h, BORDER);
        int color;
        if (!b.enabled) color = 0xFF55555C;
        else if (b.danger) color = hover ? 0xFFFFB0B0 : 0xFFFF8080;
        else color = hover ? 0xFFFFFFFF : 0xFFD0D0D8;
        int maxW = b.w - 4;
        int tw = font.width(b.label);
        if (tw > maxW && tw > 0) {
            // 文案超宽（英文界面常见）：等比缩小字号画进按钮，最低 50%
            float scale = Math.max(0.5f, (float) maxW / tw);
            g.pose().pushMatrix();
            g.pose().translate((float) (b.x + b.w / 2.0), (float) (b.y + (b.h - 8) / 2.0 + 1));
            g.pose().scale(scale, scale);
            g.drawCenteredString(font, b.label, 0, 0, color);
            g.pose().popMatrix();
        } else {
            g.drawCenteredString(font, b.label, b.x + b.w / 2, b.y + (b.h - 8) / 2 + 1, color);
        }
    }

    public static void progressBar(GuiGraphics g, int x, int y, int w, int h, double pct) {
        g.fill(x, y, x + w, y + h, BORDER);
        double clamped = Math.max(0, Math.min(1, pct));
        int filled = (int) ((w - 2) * clamped);
        if (filled > 0) {
            int color = pct >= 1.0 ? 0xFF40C040 : 0xFF2E8E2E;
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, color);
        }
    }

    public static void scrollbar(GuiGraphics g, int x, int y, int h, double scroll, double maxScroll) {
        g.fill(x, y, x + 4, y + h, 0xFF18181E);
        if (maxScroll > 0) {
            int thumb = Math.max(16, h * h / (int) (h + maxScroll));
            int ty = y + (int) ((h - thumb) * (scroll / maxScroll));
            g.fill(x, ty, x + 4, ty + thumb, 0xFF606070);
        }
    }

    // ───────────────────────── 格式化（与服务端一致） ─────────────────────────

    public static String statusSym(String status) {
        switch (status) {
            case "active": return "◉";
            case "planning": return "◐";
            case "completed": return "○";
            case "archived": return "▣";
            default: return "?";
        }
    }

    public static int statusColor(String status) {
        switch (status) {
            case "active": return GREEN;
            case "planning": return YELLOW;
            case "completed": return 0xFFAAAAAA;
            default: return FAINT;
        }
    }

    public static String statusCn(String status) {
        switch (status) {
            case "active": return L10n.get("projectmemo.status.active");
            case "planning": return L10n.get("projectmemo.status.planning");
            case "completed": return L10n.get("projectmemo.status.completed");
            case "archived": return L10n.get("projectmemo.status.archived");
            default: return status;
        }
    }

    /** 按物品实际堆叠上限换算：盒=27组，组=堆叠上限（剪刀=1、雪球=16、多数=64） */
    public static String fmtAmount(long n, int stackSize) {
        if (n < 0) n = 0;
        if (stackSize <= 1) return n + L10n.get("projectmemo.unit.piece");
        long boxSize = 27L * stackSize;
        long box = n / boxSize, rem = n % boxSize;
        long stack = rem / stackSize, piece = rem % stackSize;
        StringBuilder sb = new StringBuilder();
        if (box > 0) sb.append(box).append(L10n.get("projectmemo.unit.box"));
        if (stack > 0 || box > 0) sb.append(stack).append(L10n.get("projectmemo.unit.stack"));
        sb.append(piece).append(L10n.get("projectmemo.unit.piece"));
        return sb.toString();
    }

    public static String fmtAmount(long n) { return fmtAmount(n, 64); }

    public static String fmtDate(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT_DAY);
    }

    public static String fmtDateShort(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT_SHORT);
    }

    public static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 按像素宽截断 */
    public static String truncPx(Font font, String s, int maxPx) {
        if (s == null) return "";
        if (font.width(s) <= maxPx) return s;
        int end = s.length();
        while (end > 1 && font.width(s.substring(0, end) + "…") > maxPx) end--;
        return s.substring(0, end) + "…";
    }

    /** 世界名本地化：含 nether→下界，含 end→末地，其余→主世界 */
    public static String worldCn(String w) {
        if (w == null || w.isEmpty()) return L10n.get("projectmemo.world.overworld");
        String lw = w.toLowerCase();
        if (lw.contains("nether")) return L10n.get("projectmemo.world.nether");
        if (lw.contains("end")) return L10n.get("projectmemo.world.end");
        return L10n.get("projectmemo.world.overworld");
    }

    /** 自绘按钮（每帧重建，mouseClicked 命中检测） */
    public static final class UiButton {
        public int x, y, w, h;
        public String label;
        public boolean enabled = true;
        public boolean danger;
        public Runnable onClick;
        public String tooltip;

        public UiButton(int x, int y, int w, int h, String label, Runnable onClick) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.onClick = onClick;
        }

        public UiButton tooltip(String t) { this.tooltip = t; return this; }

        public UiButton danger() { this.danger = true; return this; }

        public UiButton disabled() { this.enabled = false; return this; }
    }
}
