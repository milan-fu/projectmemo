package com.sthstrange.projectmemo.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 多行文本编辑框：自动换行 + 光标 + 上下滚动（用于描述/说明等长文本）。
 * 支持：字符输入、退格、删除、回车换行、方向键、Home/End、滚轮。
 */
public final class MemoMultilineEdit {

    private static final int LINE_H = 11;

    private final Font font;
    public int x, y, w, h;
    private String text = "";
    private int cursor;
    private double scroll;
    public boolean focused = true;

    private final List<int[]> lines = new ArrayList<>(); // 每行 [start, end) 字符区间

    public MemoMultilineEdit(Font font, int x, int y, int w, int h) {
        this.font = font;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setText(String t) {
        text = t == null ? "" : t;
        cursor = Math.min(cursor, text.length());
        rebuild();
    }

    public String getText() { return text; }

    private void rebuild() {
        lines.clear();
        int maxW = w - 10;
        int paraStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                wrapPara(paraStart, i, maxW);
                paraStart = i + 1;
            }
        }
        if (lines.isEmpty()) lines.add(new int[]{0, 0});
        double maxScroll = Math.max(0, lines.size() * LINE_H - (h - 8));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private void wrapPara(int start, int end, int maxW) {
        if (start >= end) {
            lines.add(new int[]{start, start});
            return;
        }
        int lineStart = start;
        while (lineStart < end) {
            int lineEnd = lineStart;
            while (lineEnd < end && font.width(text.substring(lineStart, lineEnd + 1)) <= maxW) lineEnd++;
            if (lineEnd == lineStart) lineEnd = lineStart + 1; // 单字符超宽也强制一行
            lines.add(new int[]{lineStart, lineEnd});
            lineStart = lineEnd;
        }
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF14141C);
        if (focused) {
            g.fill(x, y, x + w, y + 1, 0xFFFFFFFF);
            g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
            g.fill(x, y, x + 1, y + h, 0xFFFFFFFF);
            g.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
        }
        g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
        int first = Math.max(0, (int) (scroll / LINE_H));
        for (int i = first; i < lines.size(); i++) {
            int ly = y + 4 + i * LINE_H - (int) scroll;
            if (ly > y + h - 4) break;
            int[] ln = lines.get(i);
            String s = text.substring(ln[0], Math.min(ln[1], text.length()));
            g.drawString(font, s, x + 5, ly, 0xFFE0E0E0, false);
            if (focused && cursor >= ln[0] && cursor <= ln[1]) {
                int cx = x + 5 + font.width(text.substring(ln[0], Math.min(cursor, text.length())));
                g.fill(cx, ly - 1, cx + 1, ly + 9, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
    }

    private void ensureCursorVisible() {
        int lineIdx = lineOf(cursor);
        int top = (int) (scroll / LINE_H);
        int visible = (h - 8) / LINE_H;
        if (lineIdx < top) scroll = lineIdx * LINE_H;
        else if (lineIdx >= top + visible) scroll = (lineIdx - visible + 1) * LINE_H;
        double maxScroll = Math.max(0, lines.size() * LINE_H - (h - 8));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private int lineOf(int pos) {
        for (int i = 0; i < lines.size(); i++) {
            int[] ln = lines.get(i);
            if (pos >= ln[0] && pos <= ln[1]) return i;
        }
        return lines.size() - 1;
    }

    public boolean keyPressed(KeyEvent e) {
        if (!focused) return false;
        int key = e.key();
        if (key == 259) { // backspace
            if (cursor > 0) {
                text = text.substring(0, cursor - 1) + text.substring(cursor);
                cursor--;
                rebuild();
                ensureCursorVisible();
            }
            return true;
        }
        if (key == 261) { // delete
            if (cursor < text.length()) {
                text = text.substring(0, cursor) + text.substring(cursor + 1);
                rebuild();
            }
            return true;
        }
        if (key == 257 || key == 335) { // enter / kp enter
            text = text.substring(0, cursor) + "\n" + text.substring(cursor);
            cursor++;
            rebuild();
            ensureCursorVisible();
            return true;
        }
        if (key == 263) { if (cursor > 0) { cursor--; ensureCursorVisible(); } return true; }   // left
        if (key == 262) { if (cursor < text.length()) { cursor++; ensureCursorVisible(); } return true; } // right
        if (key == 265 || key == 264) { // up / down
            int li = lineOf(cursor);
            int target = key == 265 ? li - 1 : li + 1;
            if (target >= 0 && target < lines.size()) {
                int col = cursor - lines.get(li)[0];
                int[] tl = lines.get(target);
                cursor = Math.min(tl[1], tl[0] + col);
                ensureCursorVisible();
            }
            return true;
        }
        if (key == 268) { cursor = 0; ensureCursorVisible(); return true; }      // home
        if (key == 269) { cursor = text.length(); ensureCursorVisible(); return true; } // end
        return false;
    }

    public boolean charTyped(int codepoint) {
        if (!focused || codepoint < 32) return false;
        if (text.length() >= 300) return false;
        String s = new String(Character.toChars(codepoint));
        text = text.substring(0, cursor) + s + text.substring(cursor);
        cursor += s.length();
        rebuild();
        ensureCursorVisible();
        return true;
    }

    public void mouseScrolled(double amt) {
        double maxScroll = Math.max(0, lines.size() * LINE_H - (h - 8));
        scroll = Math.max(0, Math.min(scroll - amt * LINE_H, maxScroll));
    }

    public boolean mouseClicked(double mx, double my) {
        if (mx < x || mx > x + w || my < y || my > y + h) {
            focused = false;
            return false;
        }
        focused = true;
        int rowIdx = Math.max(0, Math.min(lines.size() - 1, (int) ((my - y - 4 + scroll) / LINE_H)));
        int[] ln = lines.get(rowIdx);
        String s = text.substring(ln[0], Math.min(ln[1], text.length()));
        int best = ln[0];
        for (int i = 0; i <= s.length(); i++) {
            if (x + 5 + font.width(s.substring(0, i)) <= mx) best = ln[0] + i;
        }
        cursor = Math.min(best, ln[1]);
        return true;
    }
}
