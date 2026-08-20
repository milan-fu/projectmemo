package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** 顶部中央轻量 toast：ack 成功绿 / 失败红 / 服务端 message。HudRenderCallback 每帧绘制。 */
public final class MemoToast {

    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int GOLD = 0xFFFFC020;

    private static final long TTL_MS = 3500;
    private static final List<Toast> toasts = new ArrayList<>();

    private record Toast(String text, int color, long expireAt) { }

    private MemoToast() { }

    public static void push(String text, int color) {
        if (text == null || text.isEmpty()) return;
        synchronized (toasts) {
            toasts.add(new Toast(text, color, System.currentTimeMillis() + TTL_MS));
            while (toasts.size() > 4) toasts.remove(0);
        }
    }

    public static void render(GuiGraphics g) {
        Font font = Minecraft.getInstance().font;
        if (font == null) return;
        long now = System.currentTimeMillis();
        synchronized (toasts) {
            toasts.removeIf(t -> t.expireAt() <= now);
            int y = 6;
            for (Toast t : toasts) {
                long left = t.expireAt() - now;
                int alpha = left < 700 ? (int) (255 * left / 700) : 255;
                if (alpha <= 10) continue;
                int w = font.width(t.text()) + 14;
                int x = (g.guiWidth() - w) / 2;
                g.fill(x, y, x + w, y + 14, ((alpha * 3 / 4) << 24) | 0x101018);
                g.fill(x, y, x + w, y + 1, (alpha << 24) | (t.color() & 0xFFFFFF));
                g.drawString(font, t.text(), x + 7, y + 3, (alpha << 24) | (t.color() & 0xFFFFFF), false);
                y += 17;
            }
        }
    }
}
