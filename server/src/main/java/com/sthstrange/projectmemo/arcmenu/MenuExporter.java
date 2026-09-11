package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.MemoData;
import com.sthstrange.projectmemo.ProjectMemoPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * ArcMenu 菜单导出器（memo-server 1.2.0-arcmenu · B 段）。
 *
 * 生成「结构静态、内容走占位符」的菜单到 plugins/ArcMenu/menus/memo/：
 *   memo-main                 总入口（open-commands: memomenu）
 *   memo-proj-&lt;state&gt;-&lt;n&gt;      工程列表分页（planning/active/completed × 容量页数）
 *   memo-loc-&lt;dim&gt;-&lt;n&gt;         地标分页（overworld/nether/end × 容量页数）
 *   memo-manual-&lt;n&gt;            使用说明分页
 *   memo-project-&lt;id&gt;          工程详情（生成式：工程增删改后重建 → reload 生效）
 *   memo-empty                空槽提示页
 *
 * 列表页内容全部来自 %projectmemo_*% 占位符（打开即求值），数据变化无需 reload。
 */
public final class MenuExporter {

    // ── 配色：取自 dsh-mc-theme 的 mc **亮色**主题（light: layer1/layer3/accentSoft/accent）──
    private static final String C_PANEL = "#fbf8ee";     // layer1 面板底（羊皮纸米白）
    private static final String C_ROW = "#ebe7d8";       // layer3 行底（比面板略深，做行区分）
    private static final String C_BTN = "#e3efd3";       // accentSoft 浅草绿按钮
    private static final String C_BTN_KEY = "#cfe2b4";   // accent200 关键按钮
    private static final String C_BORDER = "#d6d0bb";    // sidebarAccent：淡米色描边（草绿只做点缀，不做整体边框）
    private static final String C_DESC_BG = "#f6f2e7";   // layer2 详情底
    // 文字（亮底，legacy 近似）：正文 &0 近黑、次要 &8 深灰、强调 &2 深绿

    /** 整体缩放系数。注意：画布尺寸决定近眼屏幕的实际角尺寸，放大过头会让边缘超出视野，
     *  所以保持 1.0（320x180 是设计基准）；确实需要更大时应先实测视野边界。 */
    // z 是“世界格”深度：近眼屏距玩家约 0.65 格，z 差 1 格会带来 2.5 倍透视缩放，
    // 因此层级只能用毫米级步进（0.002 格 ≈ 0.3% 缩放差，肉眼无感）。
    private static final double Z_EDGE = -0.006;
    private static final double Z_PANEL = -0.004;
    private static final double Z_ROW = -0.002;
    private static final double Z_TEXT = 0.0;
    /** 文本自动换行阈值：给足宽度，避免长行折行后与下一行叠字。 */
    private static final int LINE_WIDTH = 2000;

    /**
     * 文本垂直补偿。标定菜单实测：文字块中心比设定的 offset.y 高 ≈ size × 5/9（半个行高），
     * 说明 MC 的 TextDisplay 是把文本块「底边」对齐到实体位置。这里按 size 下移半个行高校正。
     */
    private static double textDy(int size) {
        return textDy(size, 1);
    }

    /** n 行文本块的补偿：MC 把整块底边对齐 offset.y，块高 = n × size × 10/9，中心要下移一半。 */
    private static double textDy(int size, int lines) {
        return Math.max(1, lines) * size * 5.0 / 9.0;
    }

    private static final double S = 1.0;
    private static final int CANVAS_W = 320;
    private static final int CANVAS_H = 180;

    private static final String[] STATES = {"planning", "active", "completed"};
    private static final String[] STATE_CN = {"规划中", "进行中", "已竣工"};
    private static final String[][] DIMS = {{"overworld", "主世界"}, {"nether", "地狱"}, {"end", "末地"}};

    private final ProjectMemoPlugin plugin;
    private final File dir;
    private int rows = 8;
    private int pages = 6;
    private int locPages = 3;
    private String entry = "memomenu";

    public MenuExporter(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
        String path = plugin.getConfig().getString("arcmenu.menu-dir", "../ArcMenu/menus/memo");
        File cwd = new File(path);
        File rel = new File(plugin.getDataFolder(), path);
        this.dir = (cwd.isDirectory() ? cwd : rel).getAbsoluteFile();
    }

    public File dir() {
        return dir;
    }

    /** 全量生成，返回写入的文件数。 */
    public synchronized int export() {
        rows = Math.max(1, plugin.getConfig().getInt("arcmenu.rows-per-page", 8));
        pages = Math.max(1, plugin.getConfig().getInt("arcmenu.list-capacity-pages", 6));
        locPages = Math.max(1, plugin.getConfig().getInt("arcmenu.loc-pages-per-dim", 3));
        entry = plugin.getConfig().getString("arcmenu.entry-command", "memomenu");
        try {
            if (dir.isDirectory()) {
                java.io.File[] olds = dir.listFiles((d, nm) -> nm.startsWith("memo-") && nm.endsWith(".yml"));
                if (olds != null) for (java.io.File f : olds) f.delete();
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                plugin.getLogger().warning("arcmenu: 无法创建菜单目录 " + dir.getAbsolutePath());
                return 0;
            }
            int count = 0;
            count += write("memo-main.yml", mainMenu());
            count += write("memo-empty.yml", emptyMenu());
            count += write("memo-loc.yml", landmarkIndex());
            count += write("memo-top.yml", topIndex());
            count += write("memo-top-me.yml", topMeMenu());
            for (int page = 1; page <= pages; page++) {
                count += write("memo-proj-" + page + ".yml", projectListMenu(page));
            }
            for (String[] dim : DIMS) {
                for (int page = 1; page <= locPages; page++) {
                    count += write("memo-loc-" + dim[0] + "-" + page + ".yml",
                            landmarkMenu(dim[0], dim[1], page));
                }
            }
            for (int page = 1; page <= pages; page++) {
                count += write("memo-manual-" + page + ".yml", manualMenu(page));
            }
            // 详情页固定按槽位序号预生成（内容为占位符，增删改无需重导出）
            for (int n = 1; n <= Slots.PROJ_TOTAL; n++) {
                count += write("memo-project-" + n + ".yml", detailMenu(n));
            }
            for (int i = 0; i < StatsTopProvider.CATS.length; i++) {
                count += write("memo-top-" + StatsTopProvider.CATS[i] + ".yml",
                        topMenu(StatsTopProvider.CATS[i], StatsTopProvider.LABELS[i]));
            }
            count += write("memo-info.yml", infoMenu());
            count += writeCmdsPages();
            count += writeGuidePages();
            return count;
        } catch (Exception e) {
            plugin.getLogger().warning("arcmenu: 菜单导出失败 " + e);
            return 0;
        }
    }

    // ───────────────────────── 页面 ─────────────────────────

    private String mainMenu() {
        Page page = new Page("memo-main", "open-commands: [" + entry + "]");
        page.bg();
        page.text("title", 0, 58, 12, "&0服务器备忘录");
        page.text("sub", 0, 44, 8, "&8右键按钮进行交互 · 输入 /m 召唤本菜单");
        page.button("b1", -76, 24, 132, 20, "&01. 新人导览");
        page.button("b2", 76, 24, 132, 20, "&02. 工程备忘录");
        page.button("b3", -76, 0, 132, 20, "&03. 机器使用说明");
        page.button("b4", 76, 0, 132, 20, "&04. 常用指令");
        page.button("b5", -76, -24, 132, 20, "&05. 服务器地标");
        page.button("b6", 76, -24, 132, 20, "&06. 玩家排行榜");
        page.text("foot", 0, -44, 7,
                "&8工程 &0%projectmemo_projects_total% &8· 地标 &0%projectmemo_landmarks_total%"
                        + " &8· 使用说明 &0%projectmemo_manual_total% &8· 在线 &0%projectmemo_online%");
        page.button("closeb", 0, -66, 110, 18, "&0关闭");
        page.open("b1-area", -76, 24, 132, 20, "memo-guide-1");
        page.open("b2-area", 76, 24, 132, 20, "memo-proj-1");
        page.open("b3-area", -76, 0, 132, 20, "memo-manual-1");
        page.open("b4-area", 76, 0, 132, 20, "memo-cmds");
        page.open("b5-area", -76, -24, 132, 20, "memo-loc");
        page.open("b6-area", 76, -24, 132, 20, "memo-top");
        page.action("close-area", 0, -66, 110, 18, "close");
        return page.build();
    }

    private String emptyMenu() {
        Page page = new Page("memo-empty", null);
        page.bg();
        page.text("title", 0, 26, 11, "&0这里暂时是空的");
        page.text("sub", 0, 6, 9, "&8该位置没有条目，或条目已被删除");
        page.button("backb", 0, -30, 110, 24, "&0返回");
        page.action("back-area", 0, -30, 110, 24, "back");
        return page.build();
    }

    /** 工程列表：全部工程按默认顺序，按页排列（不再按状态分类）。 */
    private String projectListMenu(int pageNo) {
        Page page = new Page("memo-proj-" + pageNo, null);
        page.bg();
        page.text("title", 0, 58, 11, "&0工程备忘录");
        page.text("info", 0, 46, 7, "&8第 " + pageNo + "/" + pages + " 页 · 共 &0%projectmemo_proj_count% &8个 · 右键点击行查看详情");
        for (int i = 1; i <= rows; i++) {
            int slot = (pageNo - 1) * rows + i;
            double y = 36 - (i - 1) * 11;
            String p = "proj_" + slot;
            page.rect("rb" + i, 0, y, 292, 10, C_ROW, 235);
            page.text("b" + i, 0, y, 8, "&0%projectmemo_" + p + "_line%");
            page.open("r" + i + "-area", 0, y, 292, 11, "%projectmemo_" + p + "_menu%");
        }
        page.nav(pageNo, pages, "memo-proj-", "memo-main");
        return page.build();
    }

    private String landmarkMenu(String dim, String dimCn, int pageNo) {
        Page page = new Page("memo-loc-" + dim + "-" + pageNo, null);
        page.bg();
        page.text("title", 0, 58, 11, "&0服务器地标 · " + dimCn);
        page.text("info", 0, 46, 7, "&8第 " + pageNo + "/" + locPages + " 页 · 共 &0%projectmemo_loc_"
                + dim + "_count% &8个 · 点行复制坐标，[详情] 跳关联工程");
        for (int i = 1; i <= rows; i++) {
            int slot = (pageNo - 1) * rows + i;
            double y = 36 - (i - 1) * 11;
            String p = "loc_" + dim + "_" + slot;
            page.rect("rb" + i, 0, y, 292, 10, C_ROW, 235);
            page.text("r" + i, -12, y, 8, "&0%projectmemo_" + p + "_line%");
            page.copyCoords("r" + i + "-area", 0, y, 292, 11,
                    "%projectmemo_" + p + "_name%", "%projectmemo_" + p + "_coord%");
            page.textRight("rg" + i, 141, y, 7, "&2[详情]");
            page.openPrio("rg" + i + "-area", 128, y, 30, 11, "%projectmemo_" + p + "_menu%", 10);
        }
        page.nav(pageNo, locPages, "memo-loc-" + dim + "-", "memo-main");
        return page.build();
    }

    private String manualMenu(int pageNo) {
        Page page = new Page("memo-manual-" + pageNo, null);
        page.bg();
        page.text("title", 0, 58, 11, "&0机器使用说明");
        page.text("info", 0, 46, 7, "&8第 " + pageNo + "/" + pages + " 页 · 共 &0%projectmemo_manual_count% &8台");
        for (int i = 1; i <= rows; i++) {
            int slot = (pageNo - 1) * rows + i;
            double y = 36 - (i - 1) * 11;
            String p = "manual_" + slot;
            page.rect("rb" + i, 0, y, 292, 10, C_ROW, 235);
            page.text("r" + i, 0, y, 8, "&0%projectmemo_" + p + "_line%");
            page.open("r" + i + "-area", 0, y, 292, 11, "%projectmemo_" + p + "_menu%");
        }
        page.nav(pageNo, pages, "memo-manual-", "memo-main");
        return page.build();
    }

    /**
     * 工程详情页：按“槽位序号”固定生成（memo-project-1..PROJ_TOTAL），内容全部走占位符，
     * 因此数据变更后**无需重新导出/重载** —— 重新打开就是最新数据。
     */
    private String detailMenu(int n) {
        Page page = new Page("memo-project-" + n, null);
        page.bg();
        String p = "proj_" + n;
        page.text("title", 0, 58, 11, "&0%projectmemo_" + p + "_title%");
        page.text("meta", 0, 46, 8, "&8#%projectmemo_" + p + "_id% &8· &2%projectmemo_" + p
                + "_status% &8· &0%projectmemo_" + p + "_date%");
        page.text("meta2", 0, 34, 8, "&8创建者 &0%projectmemo_" + p + "_creator%");
        page.text("loc", 0, 22, 8, "&8选址：&0%projectmemo_" + p + "_loc%");
        page.rect("descbg", 0, -12, 292, 56, C_DESC_BG, 220);
        page.textFixed("desc", 0, -12, 8, "&0%projectmemo_" + p + "_desc%", 5);   // 数据侧固定 5 行
        page.chat("desc-area", 0, -12, 292, 56, "%projectmemo_" + p + "_show%");   // 点描述框看完整说明
        page.text("foot", 0, -48, 7, "&8刷新 = 重读数据 · &7点此行看完整说明");
        page.chat("foot-area", 0, -52, 292, 12, "%projectmemo_" + p + "_show%");
        page.button("refreshb", -62, -64, 108, 22, "&0刷新");
        page.button("backb", 62, -64, 108, 22, "&0返回");
        page.action("refresh-area", -62, -64, 108, 22, "open: memo-project-" + n);
        page.action("back-area", 62, -64, 108, 22, "back");   // 用 ArcMenu 的历史栈：从哪进就回哪
        return page.build();
    }

    /** 排行榜页（行内 update 自动刷新）。 */
    private String topMenu(String cat, String label) {
        Page page = new Page("memo-top-" + cat, null);
        page.bg();
        page.text("title", 0, 58, 11, "&0排行榜 · " + label);
        page.textUpdate("info", 0, 44, 7, "&8更新于 &0%projectmemo_top_" + cat
                + "_updated% &8· 与 QQ #stats 同口径", 100);
        for (int i = 1; i <= StatsTopProvider.TOP_N; i++) {
            // 榜单有 10 行，行距 9.5 才能让最后一行（-49.5）避开底部按钮（顶边 -54）
            double y = 36 - (i - 1) * 9.5;
            String p = "top_" + cat + "_" + i;
            page.rect("rb" + i, 0, y, 290, 9, C_ROW, 235);
            page.textUpdate("r" + i, -46, y, 8, "&8%projectmemo_" + p + "_rank%. &0%projectmemo_" + p + "_name%", 100);
            page.textUpdateLeftFixed("rv" + i, 84, y, 8, "&2%projectmemo_" + p + "_value_pad%", 100, 52);
        }
        page.button("backb", 0, -64, 110, 20, "&0返回");
        page.action("back-area", 0, -64, 110, 20, "open: memo-top");
        return page.build();
    }

    /** 服务器信息页：config 文案 + 实时槽。 */
    private String infoMenu() {
        Page page = new Page("memo-info", null);
        page.bg();
        page.text("title", 0, 58, 11, "&0服务器信息");
        double y = 50;
        int idx = 0;
        for (String line : plugin.getConfig().getStringList("arcmenu.info-lines")) {
            if (y < -6) break;
            page.text("l" + (idx++), 0, y, 9, line);
            y -= 15;
        }
        page.textUpdate("rt", 0, -34, 9, "&8在线 &0%projectmemo_online% &8· TPS &0%projectmemo_tps% &8· 内存 &0%projectmemo_memory%", 100);
        page.textUpdate("rc", 0, -46, 8, "&8工程 &0%projectmemo_projects_total% &8· 地标 &0%projectmemo_landmarks_total% &8· 说明 &0%projectmemo_manual_total%", 100);
        page.button("cmdsb", -62, -68, 108, 20, "&0常用指令");
        page.button("backb", 62, -68, 108, 20, "&0返回");
        page.action("cmds-area", -62, -68, 108, 20, "open: memo-cmds");
        page.action("back-area", 62, -68, 108, 20, "open: memo-main");
        return page.build();
    }

                /** 常用指令：config arcmenu.cmds-categories（入口页 + 每分类自动分页，支持可点击行）。 */
    private int writeCmdsPages() throws Exception {
        List<java.util.Map<?, ?>> cats = plugin.getConfig().getMapList("arcmenu.cmds-categories");
        if (cats.isEmpty()) return 0;
        int count = 0;
        Page index = new Page("memo-cmds", null);
        index.bg();
        index.text("title", 0, 58, 11, "&0常用指令");
        index.text("info", 0, 46, 7, "&8完整的指令说明请参考服务器 wiki");
        double[] xs = {-74, 74, -74, 74};
        double[] ys = {16, 16, -34, -34};
        int n = 0;
        for (java.util.Map<?, ?> cat : cats) {
            if (n >= 4) break;
            String cid = String.valueOf(cat.get("id"));
            index.button("c" + n, xs[n], ys[n], 140, 32, "&0" + String.valueOf(cat.get("title")));
            index.open("c" + n + "-area", xs[n], ys[n], 140, 32, "memo-cmds-" + cid + "-1");
            n++;
        }
        index.button("backb", 0, -68, 110, 20, "&0返回");
        index.action("back-area", 0, -68, 110, 20, "open: memo-main");
        count += write("memo-cmds.yml", index.build());

        for (java.util.Map<?, ?> cat : cats) {
            String cid = String.valueOf(cat.get("id"));
            String ctitle = String.valueOf(cat.get("title"));
            List<String> lines = stringList(cat.get("lines"));
            List<Integer> clicks = new ArrayList<>();
            List<String> clickCmds = new ArrayList<>();
            Object clickObj = cat.get("clickable");
            if (clickObj instanceof List) {
                for (Object c : (List<?>) clickObj) {
                    if (!(c instanceof java.util.Map)) continue;
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) c;
                    if (m.get("line") == null || m.get("chat") == null) continue;
                    clicks.add(((Number) m.get("line")).intValue());
                    clickCmds.add(String.valueOf(m.get("chat")));
                }
            }
            int perPage = 9;
            // 智能分页：每页最多 perPage 行；且分组标题（<...>）不留在页尾——已填 >=6 行时遇到分组标题就换页
            List<List<Integer>> pageIdx = new ArrayList<>();
            List<Integer> cur = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String plain = lines.get(i).replaceAll("(?i)&[0-9a-fk-or]", "").trim();
                boolean header = plain.startsWith("<");
                if (!cur.isEmpty() && cur.size() >= 6 && header) {
                    pageIdx.add(cur);
                    cur = new ArrayList<>();
                } else if (cur.size() >= perPage) {
                    pageIdx.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(i);
            }
            if (!cur.isEmpty()) pageIdx.add(cur);
            int pages = Math.max(1, pageIdx.size());
            for (int pg = 0; pg < pages; pg++) {
                Page page = new Page("memo-cmds-" + cid + "-" + (pg + 1), null);
                page.bg();
                page.text("title", 0, 58, 11, "&0" + ctitle);
                if (pages > 1) page.text("info", 0, 46, 7, "&8第 " + (pg + 1) + "/" + pages + " 页");
                double y = 36;
                int idx = 0;
                List<Integer> rows = pageIdx.get(pg);
                for (int ri = 0; ri < rows.size(); ri++) {
                    int li = rows.get(ri);
                    if (y < -56) break;
                    renderLine(page, "c" + idx, lines.get(li), y, 8);
                    int hit = clicks.indexOf(li);
                    if (hit >= 0) page.chat("click" + idx + "-area", 0, y, 292, 11, clickCmds.get(hit));
                    idx++;
                    y -= 11;
                }
                if (pages > 1) {
                    String prev = pg > 0 ? "memo-cmds-" + cid + "-" + pg : "memo-cmds-" + cid + "-" + pages;
                    String next = pg < pages - 1 ? "memo-cmds-" + cid + "-" + (pg + 2) : "memo-cmds-" + cid + "-1";
                    page.button("prevb", -97, -68, 86, 20, "&0上一页");
                    page.button("backb", 0, -68, 86, 20, "&0返回");
                    page.button("nextb", 97, -68, 86, 20, "&0下一页");
                    page.action("prev-area", -97, -68, 86, 20, "open: " + prev);
                    page.action("back-area", 0, -68, 86, 20, "open: memo-cmds");
                    page.action("next-area", 97, -68, 86, 20, "open: " + next);
                } else {
                    page.button("backb", 0, -68, 110, 20, "&0返回分类");
                    page.action("back-area", 0, -68, 110, 20, "open: memo-cmds");
                }
                count += write("memo-cmds-" + cid + "-" + (pg + 1) + ".yml", page.build());
            }
        }
        return count;
    }

    /** 地标入口：选择世界。 */
    private String landmarkIndex() {
        Page page = new Page("memo-loc", null);
        page.bg();
        page.text("title", 0, 58, 11, "&0服务器地标");
        page.text("info", 0, 46, 7, "&8选择世界查看 · 点地标行复制坐标");
        String[][] dims = {{"overworld", "主世界"}, {"nether", "地狱"}, {"end", "末地"}};
        for (int i = 0; i < dims.length; i++) {
            double y = 24 - i * 28;
            page.button("d" + i, 0, y, 210, 24, "&0" + dims[i][1] + "  &8（&0%projectmemo_loc_" + dims[i][0] + "_count% &8个）");
            page.open("d" + i + "-area", 0, y, 210, 24, "memo-loc-" + dims[i][0] + "-1");
        }
        page.button("backb", 0, -68, 110, 20, "&0返回");
        page.action("back-area", 0, -68, 110, 20, "open: memo-main");
        return page.build();
    }

    /** 排行榜入口：各分类 + 我的数据。 */
    private String topIndex() {
        Page page = new Page("memo-top", null);
        page.bg();
        page.text("title", 0, 58, 11, "&0玩家排行榜");
        page.text("info", 0, 46, 7, "&8与 QQ #stats 同口径 · 假人不进榜");
        String[][] items = {{"online", "在线总时长"}, {"mine", "生存挖掘"}, {"place", "放置数"}, {"kill", "击杀数"},
                {"achievement", "成就数"}, {"death", "死亡数"}, {"me", "我的数据"}};
        for (int i = 0; i < items.length; i++) {
            double x = (i % 2 == 0) ? -76 : 76;
            double y = 30 - (i / 2) * 24;
            page.button("t" + i, x, y, 140, 22, "&0" + items[i][1]);
            page.open("t" + i + "-area", x, y, 140, 22, "memo-top-" + items[i][0]);
        }
        page.button("backb", 0, -68, 110, 20, "&0返回");
        page.action("back-area", 0, -68, 110, 20, "open: memo-main");
        return page.build();
    }

    /** 我的数据（生存服口径；完整排名可点击发送 !!stats me）。 */
    private String topMeMenu() {
        Page page = new Page("memo-top-me", null);
        page.bg();
        page.text("title", 0, 58, 11, "&0我的数据");
        page.text("info", 0, 46, 7, plugin.isMirror()
                ? "&8数据来自主服（生存服统计口径）"
                : "&8与 ESC 统计同源（生存服口径）");
        String[][] rows = {{"总在线时长", "me_online"}, {"挖掘数", "me_mine"}, {"放置数", "me_place"},
                {"击杀数", "me_kill"}, {"成就数", "me_achievement"}, {"死亡数", "me_death"}};
        double y = 36;
        int idx = 0;
        for (String[] r : rows) {
            page.textLeft("ml" + idx, -140, y, 9, "&0" + r[0]);
            page.textLeftFixed("mr" + idx, 34, y, 9, "&2%projectmemo_" + r[1] + "_pad%", 62);
            idx++;
            y -= 14;
        }
        page.text("tip", 0, -52, 7, "&8完整排名：点这行发送 &0!!stats me");
        page.chat("chat-area", 0, -52, 292, 14, "!!stats me");
        page.button("backb", 0, -76, 110, 18, "&0返回");
        page.action("back-area", 0, -76, 110, 18, "open: memo-top");
        return page.build();
    }

    private static List<String> stringList(Object obj) {
        List<String> out = new ArrayList<>();
        if (obj instanceof List) for (Object o : (List<?>) obj) out.add(String.valueOf(o));
        return out;
    }

    /** 指令/列表页行：指令左对齐 x=-140、说明左对齐 x=30。 */
    private void renderLine(Page page, String id, String line, double y, int size) {
        String[] cols = line.split("\\|\\|", -1);
        renderCell(page, id + "l", cols[0], -140, 30, y, size, false, cols.length > 1);
        if (cols.length > 1 && !cols[1].trim().isEmpty()) {
            renderCell(page, id + "r", cols[1], 30, 30, y, size, false, true);
        }
    }

    /** 导览行：按页面 layout 选择排版。
     *  labeled = 键右对齐/值左对齐（设置页）；tag = 标签左对齐 + 说明紧跟；其他 = 左列左对齐 + 说明 x=30。 */
    private void renderGuideLine(Page page, String id, String line, double y, int size, String layout) {
        String[] cols = line.split("\\|\\|", -1);
        boolean hasEq = cols[0].indexOf('=') > 0 || (cols.length > 1 && cols[1].indexOf('=') > 0);
        if ("labeled".equals(layout) && hasEq) {
            renderCell(page, id + "l", cols[0], -70, -64, y, size, true, cols.length > 1);
            if (cols.length > 1 && !cols[1].trim().isEmpty()) {
                renderCell(page, id + "r", cols[1], 60, 66, y, size, true, true);
            }
        } else if ("tag".equals(layout)) {
            renderCell(page, id + "l", cols[0], -140, -140, y, size, false, cols.length > 1);
            if (cols.length > 1 && !cols[1].trim().isEmpty()) {
                renderCell(page, id + "r", cols[1], -85, -85, y, size, false, true);
            }
        } else {
            renderCell(page, id + "l", cols[0], -140, 30, y, size, false, cols.length > 1);
            if (cols.length > 1 && !cols[1].trim().isEmpty()) {
                renderCell(page, id + "r", cols[1], 30, 30, y, size, false, true);
            }
        }
    }

    private void renderCell(Page page, String id, String cell, double keyX, double valX, double y, int size,
                            boolean keyRight, boolean multiCol) {
        int eq = cell.indexOf('=');
        if (eq > 0) {
            if (keyRight) page.textRight(id + "k", keyX, y, size, cell.substring(0, eq));
            else page.textLeft(id + "k", keyX, y, size, cell.substring(0, eq));
            page.textLeft(id + "v", valX, y, size, cell.substring(eq + 1));
        } else if (multiCol) {
            if (keyRight) page.textRight(id, keyX, y, size, cell);
            else page.textLeft(id, keyX, y, size, cell);
        } else {
            page.textLeft(id, -140, y, size, cell);
        }
    }

    /** 新人导览：config arcmenu.guide-pages（每项一页，支持双列与可点击行）。 */
    private int writeGuidePages() throws Exception {
        List<java.util.Map<?, ?>> pages = plugin.getConfig().getMapList("arcmenu.guide-pages");
        int count = 0;
        for (int i = 0; i < pages.size(); i++) {
            java.util.Map<?, ?> def = pages.get(i);
            Object titleObj = def.get("title");
            Page page = new Page("memo-guide-" + (i + 1), null);
            page.bg();
            page.text("title", 0, 58, 11, "&0" + (titleObj == null ? "新人导览" : String.valueOf(titleObj)));
            if (pages.size() > 1) page.text("info", 0, 46, 7, "&8第 " + (i + 1) + "/" + pages.size() + " 页");
            String layout = def.get("layout") == null ? "" : String.valueOf(def.get("layout"));
            double y = 36;
            int idx = 0;
            for (String line : stringList(def.get("lines"))) {
                if (y < -56) break;
                renderGuideLine(page, "g" + idx, line, y, 8, layout);
                idx++;
                y -= 11;
            }
            Object clickObj = def.get("clickable");
            if (clickObj instanceof List) {
                for (Object c : (List<?>) clickObj) {
                    if (!(c instanceof java.util.Map)) continue;
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) c;
                    Object ln = m.get("line");
                    Object chat = m.get("chat");
                    if (ln == null || chat == null) continue;
                    double cy = 40 - ((Number) ln).intValue() * 11 - 8 * 5.0 / 9.0;   // 对齐文字实际位置（含 8 号字的补偿）
                    page.chat("click" + ln + "-area", 0, cy, 292, 12, String.valueOf(chat));
                }
            }
            String prev = i > 0 ? "memo-guide-" + i : "memo-main";
            String next = i < pages.size() - 1 ? "memo-guide-" + (i + 2) : "memo-main";
            page.button("prevb", -97, -68, 86, 20, "&0上一页");
            page.button("backb", 0, -68, 86, 20, "&0返回");
            page.button("nextb", 97, -68, 86, 20, "&0下一页");
            page.action("prev-area", -97, -68, 86, 20, "open: " + prev);
            page.action("back-area", 0, -68, 86, 20, "open: memo-main");
            page.action("next-area", 97, -68, 86, 20, "open: " + next);
            count += write("memo-guide-" + (i + 1) + ".yml", page.build());
        }
        return count;
    }

    // ───────────────────────── 页面构建器 ─────────────────────────
// ───────────────────────── 页面构建器 ─────────────────────────

    /** 单页构建：frontend 元素与 backend 区域分开累积，避免互相污染。 */
    private final class Page {
        private final String id;
        private final String extra;
        private final StringBuilder front = new StringBuilder();
        private final StringBuilder back = new StringBuilder();

        Page(String id, String extra) {
            this.id = id;
            this.extra = extra;
            // 背景由各页面显式调用 rect("bg", ...) 声明，避免重复键
        }

        /** 页面背景：z=-1，位于所有行背景之下，避免同深度重叠导致的闪烁。 */
        void bg() {
            // 外层描边 + 内层面板（用两个矩形代替 frame，避免边框元素在不同版本的坐标缩放差异）
            front.append("  edge:\n    type: rectangle\n    width: 310\n    height: 176\n    color: '")
                    .append(C_BORDER).append("'\n    opacity: 255\n    offset: {x: 0, y: 0, z: " + Z_EDGE + "}\n");
            front.append("  bg:\n    type: rectangle\n    width: 306\n    height: 172\n    color: '")
                    .append(C_PANEL).append("'\n    opacity: 252\n    offset: {x: 0, y: 0, z: " + Z_PANEL + "}\n");
        }

        void rect(String eid, double x, double y, double w, double h, String color, int opacity) {
            front.append("  ").append(eid).append(":\n    type: rectangle\n    width: ").append(n(w * S))
                    .append("\n    height: ").append(n(h * S)).append("\n    color: '").append(color)
                    .append("'\n    opacity: ").append(opacity)
                    .append("\n    offset: {x: ").append(n(x * S)).append(", y: ").append(n(y * S)).append(", z: " + Z_ROW + "}\n");
        }

        void text(String eid, double x, double y, int size, String content) {
            textAlign(eid, x, y, size, content, "center");
        }

        void textLeft(String eid, double x, double y, int size, String content) {
            textAlign(eid, x, y, size, content, "left");
        }

        void textRight(String eid, double x, double y, int size, String content) {
            textAlign(eid, x, y, size, content, "right");
        }

        /**
         * ArcMenu 把 text 的 alignment 直接交给 MC 的 TextDisplay；TextDisplay 的实体位置是
         * 文本块中心（alignment 只决定块内多行的对齐）。所以这里把“期望的对齐边界”换算成中心 x。
         */
        private void textAlign(String eid, double x, double y, int size, String content, String align) {
            double w = 0;
            for (String line : content.split("\n")) {
                w = Math.max(w, textWidthLogic(line, size));
            }
            double cx = x;
            if ("left".equals(align)) cx = x + w / 2.0;
            else if ("right".equals(align)) cx = x - w / 2.0;
            front.append("  ").append(eid).append(":\n    type: text\n    content: ").append(quote(content))
                    .append("\n    size: ").append(Math.max(1, (int) Math.round(size * S))).append("\n    alignment: ").append(align).append("\n    line-width: ").append(LINE_WIDTH)
                    .append("\n    offset: {x: ").append(n(cx * S)).append(", y: ").append(n((y - textDy(size, content.split("\n").length)) * S)).append(", z: " + Z_TEXT + "}\n");
        }

        /**
         * 左对齐文本，块宽固定。值列用“数据侧补白”保证实际宽度一致，
         * 所以这里不必也不能按内容算宽度（占位符在渲染时才替换）。
         */
        /** 固定行数的多行文本（行数已知：内容由数据侧铺成固定行数）。 */
        void textFixed(String eid, double x, double y, int size, String content, int lines) {
            front.append("  ").append(eid).append(":\n    type: text\n    content: ").append(quote(content))
                    .append("\n    size: ").append(Math.max(1, (int) Math.round(size * S))).append("\n    alignment: center")
                    .append("\n    line-width: ").append(LINE_WIDTH)
                    .append("\n    offset: {x: ").append(n(x * S))
                    .append(", y: ").append(n((y - textDy(size, lines)) * S)).append(", z: " + Z_TEXT + "}\n");
        }

        void textLeftFixed(String eid, double x, double y, int size, String content, double blockWidth) {
            front.append("  ").append(eid).append(":\n    type: text\n    content: ").append(quote(content))
                    .append("\n    size: ").append(Math.max(1, (int) Math.round(size * S))).append("\n    alignment: left")
                    .append("\n    line-width: ").append(LINE_WIDTH)
                    .append("\n    offset: {x: ").append(n((x + blockWidth / 2) * S))
                    .append(", y: ").append(n((y - textDy(size)) * S)).append(", z: " + Z_TEXT + "}\n");
        }

        /** 同上，另带 update 周期。 */
        void textUpdateLeftFixed(String eid, double x, double y, int size, String content, int updateTicks, double blockWidth) {
            front.append("  ").append(eid).append(":\n    type: text\n    content: ").append(quote(content))
                    .append("\n    size: ").append(Math.max(1, (int) Math.round(size * S))).append("\n    alignment: left")
                    .append("\n    line-width: ").append(LINE_WIDTH)
                    .append("\n    update: ").append(updateTicks)
                    .append("\n    offset: {x: ").append(n((x + blockWidth / 2) * S))
                    .append(", y: ").append(n((y - textDy(size)) * S)).append(", z: " + Z_TEXT + "}\n");
        }

        /** 带 update 周期的文本（每 N tick 重新求值占位符）。 */
        void textUpdate(String eid, double x, double y, int size, String content, int updateTicks) {
            front.append("  ").append(eid).append(":\n    type: text\n    content: ").append(quote(content))
                    .append("\n    size: ").append(Math.max(1, (int) Math.round(size * S))).append("\n    alignment: center").append("\n    line-width: ").append(LINE_WIDTH)
                    .append("\n    update: ").append(updateTicks)
                    .append("\n    offset: {x: ").append(n(x * S)).append(", y: ").append(n((y - textDy(size)) * S)).append(", z: " + Z_TEXT + "}\n");
        }

        void button(String eid, double x, double y, double w, double h, String label) {
            rect(eid + "bg", x, y, w, h, C_BTN, 235);
            text(eid + "tx", x, y, 9, label);
        }

        void open(String areaId, double x, double y, double w, double h, String menu) {
            openPrio(areaId, x, y, w, h, menu, 0);
        }

        /** priority > 0：在同点重叠的热区中优先命中（如地标行右侧的 [详情] 覆盖整行复制区）。 */
        void openPrio(String areaId, double x, double y, double w, double h, String menu, int priority) {
            back.append("  ").append(areaId).append(":\n    x: ").append(n(x * S)).append("\n    y: ").append(n(y * S))
                    .append("\n    width: ").append(n(w * S)).append("\n    height: ").append(n(h * S));
            if (priority > 0) back.append("\n    priority: ").append(priority);
            back.append("\n    actions:\n      right:\n        - 'sound: UI_BUTTON_CLICK-1-1'\n"
                            + "        - 'open: ").append(menu).append("'\n");
        }

        void action(String areaId, double x, double y, double w, double h, String action) {
            back.append("  ").append(areaId).append(":\n    x: ").append(n(x * S)).append("\n    y: ").append(n(y * S))
                    .append("\n    width: ").append(n(w * S)).append("\n    height: ").append(n(h * S))
                    .append("\n    actions:\n      right: '").append(action).append("'\n");
        }

        /** 点击后由玩家发送一段聊天内容（`!!` 开头的会由 MCDR 拦截执行）。 */
        void chat(String areaId, double x, double y, double w, double h, String text) {
            back.append("  ").append(areaId).append(":\n    x: ").append(n(x * S)).append("\n    y: ").append(n(y * S))
                    .append("\n    width: ").append(n(w * S)).append("\n    height: ").append(n(h * S))
                    .append("\n    actions:\n      right: 'chat: ").append(text.replace("'", "''")).append("'\n");
        }

        /** 地标行：点击复制坐标（tellraw + copy_to_clipboard；服务器无 tp）。 */
        void copyCoords(String areaId, double x, double y, double w, double h, String name, String coord) {
            String json = "{\"text\":\"[地标] \",\"color\":\"gray\",\"extra\":["
                    + "{\"text\":\"" + name + "\",\"color\":\"white\"},"
                    + "{\"text\":\" — " + coord + "\",\"color\":\"yellow\",\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\""
                    + coord + "\"},\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"点击复制坐标\"}}]}";
            back.append("  ").append(areaId).append(":\n    x: ").append(n(x * S)).append("\n    y: ").append(n(y * S))
                    .append("\n    width: ").append(n(w * S)).append("\n    height: ").append(n(h * S))
                    .append("\n    actions:\n      right:\n        - 'sound: UI_BUTTON_CLICK-1-1'\n"
                            + "        - 'tellraw: ").append(json.replace("'", "''")).append("'\n");
        }

        void nav(int pageNo, int total, String prefix, String backMenu) {
            String prev = pageNo > 1 ? prefix + (pageNo - 1) : backMenu;
            String next = pageNo < total ? prefix + (pageNo + 1) : backMenu;
            button("prevb", -97, -68, 86, 20, "&0上一页");
            button("backb", 0, -68, 86, 20, "&0返回");
            button("nextb", 97, -68, 86, 20, "&0下一页");
            action("prev-area", -97, -68, 86, 20, "open: " + prev);
            action("back-area", 0, -68, 86, 20, "open: " + backMenu);
            action("next-area", 97, -68, 86, 20, "open: " + next);
        }

        String build() {
            StringBuilder b = new StringBuilder();
            b.append("schema-version: 1\n");
            b.append("id: ").append(id).append("\n");
            if (extra != null) b.append(extra).append("\n");
            b.append("canvas:\n  width: ").append(CANVAS_W).append("\n  height: ").append(CANVAS_H)
                    .append("\n  pixels-per-block: 42.7\n  distance: 3\n");
            b.append("frontend:\n").append(front);
            b.append("backend:\n").append(back);
            return b.toString();
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private int write(String name, String content) throws Exception {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
        return 1;
    }

    /** 一行文本宽度（逻辑单位）：MC 默认字体各字符 advance(px) 之和 × size/9（9px = 一行 = size 逻辑单位）。 */
    private static double textWidthLogic(String s, int size) {
        double px = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            px += glyphPx(c);
        }
        return px * size / 9.0;
    }

    /**
     * 单字符 advance（px），与 ArcMenu 自己的 TooltipMeasurer.glyphWidth 完全一致——
     * 它专为 MC 的 TextDisplay 校准，照搬可保证我们算出的块中心与客户端一致。
     */
    private static double glyphPx(char c) {
        int o = c;
        if (o == 0x20) return 4;
        if (o == 0x09) return 16;
        if (o >= 0x2E80) return 9;
        if (o == 0x27 || "i!.,:;|\u0060".indexOf(c) >= 0) return 2;
        if ("ltI[](){}".indexOf(c) >= 0) return 4;
        if ("fjkr".indexOf(c) >= 0) return 5;
        return 6;
    }

    private static String n(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** YAML 双引号字符串：转义 " 与 \\，把真换行写成 \\n。 */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') b.append("\\\"");
                else if (c == '\\') b.append("\\\\");
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("");
                else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** 内嵌文本净化：去掉 & 颜色码，压平换行。 */
    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("&", "").replace("\n", " ").replace("\r", " ").trim();
    }

    /** 长文断行（粗略按字符数），限制行数，空则给占位文案。 */
    /**
     * 按“显示宽度”折行（perLine 是逻辑单位宽度而非字符数），
     * 避免把 "/bot action … use_on" 这类中英混排行按字符数硬切断。
     */
    private static String multiline(String s, int maxLines, double perLine) {
        if (s == null || s.trim().isEmpty()) return "（暂无描述）";
        s = s.replaceAll("(?i)&[0-9a-fk-or]", "");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String raw : s.replace("\r", "").split("\n")) {
            StringBuilder line = new StringBuilder(raw.trim());
            while (lines.size() < maxLines && line.length() > 0) {
                if (textWidthLogic(line.toString(), 8) <= perLine) {
                    lines.add(line.toString());
                    break;
                }
                int cut = line.length();
                while (cut > 1 && textWidthLogic(line.substring(0, cut), 8) > perLine) cut--;
                lines.add(line.substring(0, cut));
                line.delete(0, cut);
            }
            if (lines.size() >= maxLines) break;
        }
        if (lines.isEmpty()) return "（暂无描述）";
        return String.join("\n", lines);
    }

    private static String statusCn(String status) {
        switch (status) {
            case "planning": return "规划中";
            case "active": return "进行中";
            case "completed": return "已竣工";
            case "archived": return "已归档";
            default: return status;
        }
    }

    private static String fmtDate(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }
}
