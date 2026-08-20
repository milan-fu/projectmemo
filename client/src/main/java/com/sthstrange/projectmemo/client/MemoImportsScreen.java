package com.sthstrange.projectmemo.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 投影导入双源（v0.7：排版修复 + 本地目录递归扫描）：
 *  ① 共享投影库（服务器 syncmatics/，带 placements.json 名称/所有者）→ 服务端解析
 *  ② 本地投影（客户端 schematics/ 目录，含子文件夹）→ 客户端解析 → 上传
 */
public final class MemoImportsScreen extends MemoScreenBase {

    private static final int ROW_H = 22;

    private final int projectId;
    private int source; // 0 共享投影库 1 本地投影
    private double scroll;

    private static final class ServerEntry {
        String file = "", name = "", owner = "";
    }
    private List<ServerEntry> serverFiles; // null=加载中

    private static final class LocalEntry {
        final File file;
        final String label; // 相对路径
        LocalEntry(File file, String label) { this.file = file; this.label = label; }
    }
    private List<LocalEntry> localFiles; // null=未扫描

    public MemoImportsScreen(int projectId) {
        super("投影导入");
        this.projectId = projectId;
    }

    @Override
    protected void init() {
        super.init();
        requestServerList();
    }

    private void requestServerList() {
        serverFiles = null;
        MemoClientState.sendAction("list_imports", null, ack -> {
            List<ServerEntry> out = new ArrayList<>();
            String message = MemoData.optStr(ack, "message", null);
            if (message != null && !message.isEmpty()) {
                try {
                    for (JsonElement e : JsonParser.parseString(message).getAsJsonArray()) {
                        if (!e.isJsonObject()) continue;
                        JsonObject o = e.getAsJsonObject();
                        ServerEntry se = new ServerEntry();
                        se.file = MemoData.optStr(o, "file");
                        se.name = MemoData.optStr(o, "name");
                        se.owner = MemoData.optStr(o, "owner");
                        if (!se.file.isEmpty()) out.add(se);
                    }
                } catch (Exception e) {
                    MemoToast.push("共享投影列表解析失败", MemoToast.RED);
                }
            }
            serverFiles = out;
        });
    }

    private File localDir() {
        return new File(Minecraft.getInstance().gameDirectory, "schematics");
    }

    /** 递归扫描 schematics/（含子文件夹），深度 5，上限 300 个，按修改时间倒序 */
    private void scanLocal() {
        List<LocalEntry> out = new ArrayList<>();
        Path root = localDir().toPath();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root, 5)) {
                List<Path> found = new ArrayList<>();
                walk.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.toLowerCase().endsWith(".litematic") && !n.startsWith("._");
                        })
                        .limit(300)
                        .forEach(found::add);
                found.sort(Comparator.comparingLong((Path p) -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0L; }
                }).reversed());
                for (Path p : found) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.add(new LocalEntry(p.toFile(), rel));
                }
            } catch (Exception e) {
                MemoToast.push("扫描本地投影失败: " + e.getMessage(), MemoToast.RED);
            }
        }
        localFiles = out;
    }

    @Override
    protected void layoutAndDraw(GuiGraphics g, int mouseX, int mouseY) {
        int panelW = Math.min(400, this.width - 20);
        int panelH = Math.min(300, this.height - 20);
        int x0 = (this.width - panelW) / 2;
        int y0 = (this.height - panelH) / 2;
        UiKit.panel(g, x0, y0, panelW, panelH);

        g.drawString(this.font, "📥 投影导入 · 工程 #" + projectId, x0 + 10, y0 + 8, UiKit.TEXT, false);

        // 来源切换
        UiKit.UiButton tabShared = new UiKit.UiButton(x0 + 10, y0 + 22, 90, 16, "共享投影库", () -> { source = 0; scroll = 0; });
        uiButtons.add(tabShared);
        if (source == 0) g.fill(x0 + 10, y0 + 22, x0 + 100, y0 + 38, 0x50FFFFFF);
        UiKit.UiButton tabLocal = new UiKit.UiButton(x0 + 104, y0 + 22, 90, 16, "本地投影", () -> {
            source = 1; scroll = 0;
            if (localFiles == null) scanLocal();
        });
        uiButtons.add(tabLocal);
        if (source == 1) g.fill(x0 + 104, y0 + 22, x0 + 194, y0 + 38, 0x50FFFFFF);
        UiKit.UiButton refresh = new UiKit.UiButton(x0 + panelW - 50, y0 + 22, 40, 16, "刷新", () -> {
            if (source == 0) requestServerList();
            else scanLocal();
        });
        uiButtons.add(refresh);

        // 说明行（位于切换按钮下方，不再重叠）
        int hintY = y0 + 44;
        int listTop = y0 + 58;
        int listBottom = y0 + panelH - 28;

        if (source == 0) {
            g.drawString(this.font, UiKit.truncPx(this.font,
                    "来自服务器共享原理图（游戏内 Syncmatica 共享的投影）", panelW - 20), x0 + 10, hintY, UiKit.FAINT, false);
            if (serverFiles == null) {
                g.drawString(this.font, "正在获取共享投影列表…", x0 + 12, listTop + 12, UiKit.DIM, false);
            } else if (serverFiles.isEmpty()) {
                g.drawString(this.font, "共享投影库是空的", x0 + 12, listTop + 12, UiKit.DIM, false);
                g.drawString(this.font, "（在游戏内把投影放置后，用 Syncmatica 的「分享」功能共享，会出现在这里）",
                        x0 + 12, listTop + 26, UiKit.FAINT, false);
            } else {
                drawList(g, mouseX, mouseY, listTop, listBottom, serverFiles.size(), i -> {
                    ServerEntry se = serverFiles.get(i);
                    return new Row(se.name.isEmpty() ? se.file : se.name,
                            (se.owner.isEmpty() ? "" : "by " + se.owner + "  ") + se.file,
                            () -> this.minecraft.setScreen(new MemoConfirmDialog(this, "导入共享投影",
                                    "把「" + (se.name.isEmpty() ? se.file : se.name) + "」的方块统计导入为材料行？", false,
                                    () -> {
                                        com.google.gson.JsonObject args =
                                                MemoClientState.argsOf("project", projectId, "file", se.file);
                                        args.addProperty("name", se.name.isEmpty() ? se.file : se.name);
                                        MemoClientState.sendAction("import_schematic", args);
                                        this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3));
                                    })));
                });
            }
        } else {
            g.drawString(this.font, UiKit.truncPx(this.font,
                    "本机 Litematica 目录（含子文件夹）: " + localDir().getAbsolutePath(), panelW - 20),
                    x0 + 10, hintY, UiKit.FAINT, false);
            if (localFiles == null) scanLocal();
            if (localFiles.isEmpty()) {
                g.drawString(this.font, "schematics 目录里没有 .litematic 文件（含子文件夹）", x0 + 12, listTop + 12, UiKit.DIM, false);
            } else {
                drawList(g, mouseX, mouseY, listTop, listBottom, localFiles.size(), i -> {
                    LocalEntry le = localFiles.get(i);
                    return new Row(le.label, (le.file.length() / 1024) + " KB · 客户端本地解析",
                            () -> importLocal(le.file));
                });
            }
        }

        UiKit.UiButton back = new UiKit.UiButton(x0 + panelW - 52, y0 + panelH - 22, 44, 16, "返回", () ->
                this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3)));
        uiButtons.add(back);
    }

    private static final class Row {
        final String title, sub;
        final Runnable action;
        Row(String title, String sub, Runnable action) { this.title = title; this.sub = sub; this.action = action; }
    }

    private interface RowProvider { Row get(int i); }

    private void drawList(GuiGraphics g, int mouseX, int mouseY, int listTop, int listBottom, int count, RowProvider rp) {
        int panelW = Math.min(400, this.width - 20);
        int x0 = (this.width - panelW) / 2;
        int listX = x0 + 8;
        int listW = panelW - 16;
        double maxScroll = Math.max(0, count * ROW_H - (listBottom - listTop));
        scroll = clampScroll(scroll, maxScroll);
        g.enableScissor(listX, listTop, listX + listW, listBottom);
        for (int i = 0; i < count; i++) {
            int rowY = listTop + i * ROW_H - (int) scroll;
            if (rowY + ROW_H < listTop || rowY > listBottom) continue;
            Row row = rp.get(i);
            if (i % 2 == 0) g.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x10FFFFFF);
            g.drawString(this.font, UiKit.truncPx(this.font, row.title, listW - 150), listX + 4, rowY + 2, UiKit.TEXT, false);
            g.drawString(this.font, UiKit.truncPx(this.font, row.sub, listW - 150), listX + 4, rowY + 12, UiKit.FAINT, false);
            UiKit.UiButton imp = new UiKit.UiButton(listX + listW - 48, rowY + 3, 44, 15, "导入", row.action);
            uiButtons.add(imp);
        }
        g.disableScissor();
        UiKit.scrollbar(g, listX + listW + 2, listTop, listBottom - listTop, scroll, maxScroll);
    }

    private void importLocal(File f) {
        ClientLitematic.Parsed parsed;
        try {
            parsed = ClientLitematic.parse(f);
        } catch (Exception e) {
            MemoToast.push("解析失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), MemoToast.RED);
            return;
        }
        if (parsed.error != null) {
            MemoToast.push(parsed.error, MemoToast.RED);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("解析完成：").append(parsed.counts.size()).append(" 种物品，共 ")
          .append(parsed.totalBlocks).append(" 方块");
        if (parsed.skippedNonItem > 0) sb.append("（跳过 ").append(parsed.skippedNonItem).append(" 个非物品方块）");
        String summary = sb.toString();
        this.minecraft.setScreen(new MemoConfirmDialog(this, "导入本地投影",
                f.getName() + "\n" + summary + "\n导入为材料行？", false, () -> {
            JsonArray items = new JsonArray();
            for (var e : parsed.counts.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("item", e.getKey());
                o.addProperty("need", e.getValue());
                items.add(o);
            }
            JsonObject args = new JsonObject();
            args.addProperty("project", projectId);
            args.add("items", items);
            args.addProperty("name", f.getName());
            if (parsed.hasOrigin) {
                JsonObject origin = new JsonObject();
                origin.addProperty("x", parsed.originX);
                origin.addProperty("y", parsed.originY);
                origin.addProperty("z", parsed.originZ);
                args.add("origin", origin);
            }
            MemoClientState.sendAction("import_materials", args);
            this.minecraft.setScreen(new ProjectDetailScreen(projectId, 3));
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - scrollY * ROW_H);
        return true;
    }
}
