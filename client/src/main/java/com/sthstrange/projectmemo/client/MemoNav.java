package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * v1.2.0 顶级导航：【工程】【地标】【使用说明】三页签。
 * 地标/使用说明按服务端能力位（perms.caps）显隐——旧服务端/单人档自动降级。
 */
public final class MemoNav {

    private MemoNav() { }

    /** 画导航并登记按钮；返回末尾 x（供镜像徽标续排）。selected: 0=工程 1=地标 2=使用说明 */
    public static int draw(GuiGraphics g, Font font, List<UiKit.UiButton> uiButtons, int x0, int y0, int selected) {
        int x = x0 + 8;
        g.drawString(font, "⚒", x, y0 + 8, UiKit.GOLD, false);
        x += font.width("⚒") + 6;
        x = tab(g, font, uiButtons, x, y0, 0, selected, L10n.get("projectmemo.nav.projects"),
                () -> Minecraft.getInstance().setScreen(new MemoMainScreen()));
        if (MemoClientState.capsLandmarks())
            x = tab(g, font, uiButtons, x, y0, 1, selected, L10n.get("projectmemo.nav.landmarks"),
                    () -> Minecraft.getInstance().setScreen(new MemoLandmarksScreen()));
        if (MemoClientState.capsManual())
            x = tab(g, font, uiButtons, x, y0, 2, selected, L10n.get("projectmemo.nav.manual"),
                    () -> Minecraft.getInstance().setScreen(new MemoManualScreen()));
        return x;
    }

    private static int tab(GuiGraphics g, Font font, List<UiKit.UiButton> uiButtons,
                           int x, int y0, int idx, int selected, String label, Runnable onClick) {
        int w = font.width(label) + 12;
        if (idx == selected) {
            g.fill(x, y0 + 5, x + w, y0 + 20, 0x50FFFFFF);
            g.drawString(font, label, x + 6, y0 + 8, UiKit.GOLD, false);
        } else {
            uiButtons.add(new UiKit.UiButton(x, y0 + 5, w, 15, label, onClick));
        }
        return x + w + 4;
    }
}
