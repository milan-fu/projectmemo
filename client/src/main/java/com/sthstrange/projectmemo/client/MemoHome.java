package com.sthstrange.projectmemo.client;

import net.minecraft.client.Minecraft;

/**
 * 「设为首页」：会话级首页。
 * 设计（服主定稿）：连接期间设置的首页一直有效；**重进游戏（重新进服）自动清空回到默认首页**，
 * 不做跨会话持久化（不写文件）。因此首页保存在内存，进服（onJoin）时清空。
 * 格式："main" 或 "project:<工程id>:<标签页>"
 */
public final class MemoHome {

    /** 会话级首页（内存，重进清空） */
    private static String home = "main";

    private MemoHome() { }

    public static String load() {
        return home;
    }

    public static void save(String value) {
        home = value == null || value.isEmpty() ? "main" : value;
    }

    /** 进服（onJoin）时调用：清空首页，重进回到默认 */
    public static void reset() {
        home = "main";
    }

    /** J 键调用：打开当前首页 */
    public static void openHome() {
        Minecraft mc = Minecraft.getInstance();
        String h = load();
        try {
            String[] parts = h.split(":");
            // v1.2.1：地标页/使用说明页也可设为首页（能力位缺失时回退主界面）
            if ("landmarks".equals(parts[0])) {
                if (MemoClientState.capsLandmarks()) mc.setScreen(new MemoLandmarksScreen());
                else mc.setScreen(new MemoMainScreen());
                return;
            }
            if ("manual".equals(parts[0])) {
                if (MemoClientState.capsManual()) mc.setScreen(new MemoManualScreen());
                else mc.setScreen(new MemoMainScreen());
                return;
            }
            if ("project".equals(parts[0]) && parts.length >= 2) {
                int id = Integer.parseInt(parts[1]);
                int tab = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                // 刚进服数据可能尚未同步，直接打开详情页，它每帧重绘，数据一到即显示
                mc.setScreen(new ProjectDetailScreen(id, tab));
                return;
            }
        } catch (Exception ignored) { }
        mc.setScreen(new MemoMainScreen());
    }
}
