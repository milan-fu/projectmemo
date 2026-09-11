package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.LocationsReader;
import com.sthstrange.projectmemo.MemoData;
import com.sthstrange.projectmemo.ProjectMemoPlugin;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 槽位索引（memo-server 1.2.0-arcmenu）。
 *
 * ArcMenu 的菜单是静态结构，内容只能靠占位符注入，因此把 memo 数据摊平成「固定槽位表」：
 *   proj_&lt;state&gt;_&lt;n&gt;_&lt;field&gt;   state = planning|active|completed，field = title|id|status|creator|date|menu
 *   loc_&lt;dim&gt;_&lt;n&gt;_&lt;field&gt;      dim = overworld|nether|end，field = name|coord|x|y|z|desc|project|menu
 *   manual_&lt;n&gt;_&lt;field&gt;         field = title|id|status|creator|date|menu
 *   实时值：online|projects_total|projects_active|projects_completed|landmarks_total|manual_total
 *   计数：proj_&lt;state&gt;_count|loc_&lt;dim&gt;_count|manual_count
 *
 * 表在数据变更钩子（ProjectMemoPlugin#onDataChanged）里整体重建，占位符求值只做 O(1) 查表。
 */
public final class Slots {

    /** 全量工程列表槽位上限（proj_&lt;n&gt;_*，不分状态）。 */
    public static final int PROJ_TOTAL = 108;
    public static final int PROJ_PER_STATE = 48;
    public static final int LOC_PER_DIM = 24;
    public static final int MANUAL_MAX = 48;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] STATES = {"planning", "active", "completed"};
    private static final String[][] DIMS = {{"overworld", "0"}, {"nether", "-1"}, {"end", "1"}};

    private final ProjectMemoPlugin plugin;
    private final StatsTopProvider stats;
    private volatile Map<String, String> table = Collections.emptyMap();
    private volatile String summary = "未构建";

    public Slots(ProjectMemoPlugin plugin, StatsTopProvider stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    /** 占位符查询（PAPI 主线程调用）：未命中返回空串，保证 ArcMenu 空槽显示为空。 */
    public String resolve(String key, org.bukkit.OfflinePlayer player) {
        if (key == null || key.isEmpty()) return "";
        String k = key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "online": return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "tps": return tpsText();
            case "memory": return memoryText();
            default: break;
        }
        if (k.startsWith("top_") && k.endsWith("_pad")) {
            String base2 = k.substring(0, k.length() - 4);
            if (base2.endsWith("_value")) return padLeftTo(topValue(base2), 52, 8);
            return topValue(base2);
        }
        if (k.startsWith("top_")) return topValue(k);
        // 选址：隐藏时仅工程管理者/admin/OP 可见；镜像服对所有人隐藏（与聊天 UI 一致）
        if (k.startsWith("proj_") && k.endsWith("_loc")) {
            String base = k.substring(0, k.length() - 4);
            String loc = table.getOrDefault(k, "");
            if (loc.isEmpty()) return "未设置";
            boolean hidden = "1".equals(table.get(base + "_locHidden"));
            if (!hidden) return loc;
            String mgrs = table.getOrDefault(base + "_managers", "");
            String uuid = player == null || player.getUniqueId() == null ? "" : player.getUniqueId().toString();
            boolean mgr = (!uuid.isEmpty() && ("," + mgrs + ",").contains("," + uuid + ","))
                    || (player instanceof org.bukkit.entity.Player pl
                        && (pl.isOp() || pl.hasPermission("memo.admin")));
            return (mgr && !plugin.isMirror()) ? loc : "（已隐藏）";
        }
        if (k.startsWith("me_") && k.endsWith("_pad")) {
            return padLeftTo(myStat(k.substring(3, k.length() - 4), player), 62, 9);
        }
        if (k.startsWith("me_")) return myStat(k.substring(3), player);
        String value = table.get(k);
        return value == null ? "" : value;
    }

    /** 我的数据（生存服 world/stats/<uuid>.json 直读；成就数需 advancements，暂不统计）。 */

    /** 放置数：只累加 minecraft:used 中在可放置清单里的物品（与 MCDR !!stats place 同口径）。 */
    private static long sumPlaceable(org.json.JSONObject stats) {
        org.json.JSONObject used = stats.optJSONObject("minecraft:used");
        if (used == null) return 0L;
        java.util.Set<String> pl = placeableItems();
        if (pl.isEmpty()) return -1L;   // 清单不可用：宁可显示 — 也不给错数
        long sum = 0L;
        java.util.Iterator<String> it = used.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (pl.contains(k)) sum += used.optLong(k);
        }
        return sum;
    }

    /**
     * 定位 MCDR 侧的可放置清单（stats_query 的 placeable_items.json）。
     * 服务器工作目录是 &lt;mcdr&gt;/server，而清单在 &lt;mcdr&gt;/plugins/，不同启动方式 cwd 可能不同，
     * 所以按候选路径依次探测，避免像之前那样静默退回全量求和。
     */
    private static java.io.File findPlaceableFile() {
        String[] candidates = {
                "../plugins/placeable_items.json",
                "plugins/placeable_items.json",
                "../../plugins/placeable_items.json",
        };
        java.util.List<java.io.File> list = new java.util.ArrayList<>();
        String cwd = System.getProperty("user.dir", ".");
        for (String c : candidates) list.add(new java.io.File(cwd, c));
        try {
            list.add(new java.io.File(org.bukkit.Bukkit.getWorldContainer().getParentFile(), "plugins/placeable_items.json"));
            list.add(new java.io.File(new java.io.File(org.bukkit.Bukkit.getWorldContainer(), ".."), "plugins/placeable_items.json"));
        } catch (Throwable ignored) {
            // 无世界时忽略
        }
        for (java.io.File f : list) {
            try { if (f.isFile()) return f.getCanonicalFile(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 可放置物品清单（MCDR stats_query 的 placeable_items.json），与 !!stats place 同口径。 */
    private static volatile java.util.Set<String> placeable;

    private static java.util.Set<String> placeableItems() {
        java.util.Set<String> p = placeable;
        if (p != null) return p;
        try {
            // 服务器工作目录是 <mcdr>/server，清单在 <mcdr>/plugins/placeable_items.json
            java.io.File f = findPlaceableFile();
            if (f == null) {
                System.out.println("[ProjectMemo] 找不到可放置清单（已尝试多个候选路径），放置数将显示 —");
                placeable = java.util.Collections.emptySet();
                return placeable;
            }
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONArray arr = new org.json.JSONArray(text);
            java.util.Set<String> s = new java.util.HashSet<>();
            for (int i = 0; i < arr.length(); i++) s.add(arr.getString(i));
            placeable = s;
        } catch (Throwable t) {
            System.out.println("[ProjectMemo] 读取可放置清单失败: " + t);
            placeable = java.util.Collections.emptySet();
        }
        return placeable;
    }

    private String myStat(String field, org.bukkit.OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) return "—";
        // 创造服（mirror）：用主服同步过来的统计，否则会显示创造服自己的数据
        if (plugin.isMirror() && stats != null) {
            String v = stats.remotePlayerStat(player.getUniqueId().toString(), field);
            if (v != null) return v;
        }
        try {
            if (Bukkit.getWorlds().isEmpty()) return "—";
            java.io.File statsDir = new java.io.File(Bukkit.getWorlds().get(0).getWorldFolder(), "stats");
            java.io.File f = new java.io.File(statsDir, player.getUniqueId() + ".json");
            if (!f.isFile()) return "—";
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(text);
            org.json.JSONObject stats = root.optJSONObject("stats");
            if (stats == null) return "—";
            org.json.JSONObject o = computeStats(stats, player.getUniqueId().toString());
            String v = o.optString(field, "");
            return v.isEmpty() ? "—" : v;
        } catch (Throwable t) {
            return "—";
        }
    }

    /** 统一的统计口径：主服本地读取、导出给创造服、创造服显示都走这里（放置数用可放置清单过滤）。 */
    static org.json.JSONObject computeStats(org.json.JSONObject stats, String uuid) {
        org.json.JSONObject custom = stats.optJSONObject("minecraft:custom");
        long ticks = custom == null ? 0L : custom.optLong("minecraft:play_time");
        long sec = ticks / 20L;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("online", String.format(Locale.ROOT, "%dh%02dm", sec / 3600, (sec % 3600) / 60));
        o.put("mine", String.format(Locale.ROOT, "%,d", sumCategory(stats, "minecraft:mined")));
        long placeSum = sumPlaceable(stats);
        o.put("place", placeSum < 0 ? "—" : String.format(Locale.ROOT, "%,d", placeSum));
        o.put("kill", String.format(Locale.ROOT, "%,d", custom == null ? 0L : custom.optLong("minecraft:mob_kills")));
        o.put("death", String.format(Locale.ROOT, "%,d", custom == null ? 0L : custom.optLong("minecraft:deaths")));
        o.put("achievement", achievementText(uuid));
        return o;
    }

    /** MC 1.21 全部成就数（不含 recipes），与 MCDR stats_query 一致。 */
    private static final int ACHIEVEMENT_TOTAL = 122;

    /** 成就数：advancements/<uuid>.json 中 done=true 且非 recipes 的条目，显示 N/122。 */
    private static String achievementText(String uuid) {
        try {
            if (uuid == null || uuid.isEmpty() || Bukkit.getWorlds().isEmpty()) return "—";
            java.io.File dir = new java.io.File(Bukkit.getWorlds().get(0).getWorldFolder(), "advancements");
            java.io.File f = new java.io.File(dir, uuid + ".json");
            if (!f.isFile()) return "0/" + ACHIEVEMENT_TOTAL;
            org.json.JSONObject root = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            int n = 0;
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("minecraft:recipes/")) continue;
                org.json.JSONObject v = root.optJSONObject(k);
                if (v != null && v.optBoolean("done")) n++;
            }
            return n + "/" + ACHIEVEMENT_TOTAL;
        } catch (Throwable t) {
            return "—";
        }
    }

    /** 主服：把本机 world/stats 汇总成 uuid → 统计（随 Redis 快照发给创造服）。 */
    public static org.json.JSONObject collectPlayers(java.io.File statsDir) {
        org.json.JSONObject out = new org.json.JSONObject();
        java.io.File[] files = statsDir == null ? null : statsDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (java.io.File f : files) {
            String name = f.getName();
            try {
                org.json.JSONObject root = new org.json.JSONObject(
                        new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                org.json.JSONObject st = root.optJSONObject("stats");
                if (st == null) continue;
                out.put(name.substring(0, name.length() - 5), computeStats(st, name.substring(0, name.length() - 5)));
            } catch (Throwable ignored) {
                // 单个玩家统计损坏不影响整体
            }
        }
        return out;
    }

    /** 把值用前置空格补到指定逻辑宽度，实现列内右对齐（游戏端替换占位符后右边界一致）。 */
    private static String padLeftTo(String value, double targetUnits, int size) {
        if (value == null || value.isEmpty()) return value;
        double w = textWidthLogic(value, size);
        double spaceUnit = 4.0 * size / 9.0;
        int spaces = (int) Math.max(0, Math.floor((targetUnits - w) / spaceUnit));
        return " ".repeat(spaces) + value;
    }

    /** 与 MenuExporter 相同的 MC 默认字体 advance 表（见 ArcMenu TooltipMeasurer）。 */
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

    private static double glyphPx(char c) {
        int o = c;
        if (o == 0x20) return 4;
        if (o == 0x09) return 16;
        if (o >= 0x2E80) return 9;
        if (o == 0x27 || "i!.,:;|`".indexOf(c) >= 0) return 2;
        if ("ltI[](){}".indexOf(c) >= 0) return 4;
        if ("fjkr".indexOf(c) >= 0) return 5;
        return 6;
    }

    /** 工程选址文本（如 "world_test 8 -4 6"）；未选址返回空串。 */
    private static String locText(MemoData.Project p) {
        if (p.locWorld == null || p.locWorld.isEmpty()) return "";
        return p.locWorld + " " + p.locX + " " + p.locY + " " + p.locZ
                + (p.locNote == null || p.locNote.isEmpty() ? "" : "（" + p.locNote + "）");
    }

    /**
     * 把文本按显示宽度折成**固定行数**（不足补空行、超出截断）。
     * 描述走占位符时，生成阶段看不到真实换行，必须靠数据侧固定行数，
     * 生成器才能用正确的行数做垂直补偿（否则多行文本会整体上移、压到上面的元素）。
     */
    private static String fixedLines(String s, int maxLines, double perLine, int size) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String raw : s.replace("\r", "").split("\n")) {
            StringBuilder line = new StringBuilder(raw.trim());
            while (out.size() < maxLines && line.length() > 0) {
                if (textWidthLogic(line.toString(), size) <= perLine) {
                    out.add(line.toString());
                    break;
                }
                int cut = line.length();
                while (cut > 1 && textWidthLogic(line.substring(0, cut), size) > perLine) cut--;
                out.add(line.substring(0, cut));
                line.delete(0, cut);
            }
            if (out.size() >= maxLines) break;
        }
        while (out.size() < maxLines) out.add("");
        return String.join("\n", out);
    }

    private static long sumCategory(org.json.JSONObject stats, String category) {
        org.json.JSONObject obj = stats.optJSONObject(category);
        if (obj == null) return 0L;
        long sum = 0L;
        for (String k : obj.keySet()) sum += obj.optLong(k);
        return sum;
    }

    /** 排行榜槽位：top_&lt;cat&gt;_&lt;n&gt;_{rank|name|value} 与 top_&lt;cat&gt;_updated。 */
    private String topValue(String key) {
        if (stats == null) return "";
        String[] p = key.split("_");
        if (p.length == 3 && "updated".equals(p[2])) return stats.updatedText(p[1]);
        if (p.length != 4) return "";
        int n;
        try {
            n = Integer.parseInt(p[2]);
        } catch (Exception e) {
            return "";
        }
        List<StatsTopProvider.Row> rows = stats.rows(p[1]);
        if (n < 1 || n > rows.size()) return "";
        StatsTopProvider.Row row = rows.get(n - 1);
        switch (p[3]) {
            case "rank": return String.valueOf(row.rank);
            case "name": return esc(row.name);
            case "value": return row.value;
            default: return "";
        }
    }

    private static String tpsText() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps.length > 0 ? String.format(Locale.ROOT, "%.1f", tps[0]) : "—";
        } catch (Throwable t) {
            return "—";
        }
    }

    private static String memoryText() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        return used + "/" + max + "M";
    }

    public String summary() {
        return summary;
    }

    /** 重建槽位表。只在数据变更后调用（主线程），O(工程数 + 地标数)。 */
    public synchronized void rebuild() {
        Map<String, String> t = new HashMap<>(1024);
        MemoData data = plugin.getStore().data();

        // ── 工程：不分状态，按 data.projects 的默认顺序编号（proj_<n>_*）──
        // 旧的状态分类槽位（proj_<state>_<n>_*）保留兼容，但菜单已改用全量列表。
        // 与聊天 UI（ChatUI 第 137 行）保持一致：按 data.projects 存储顺序，排除已归档。
        int total = 0;
        java.util.Map<Integer, Integer> slotOf = new java.util.HashMap<>();
        for (MemoData.Project p : data.projects) {
            if ("archived".equals(p.status)) continue;
            if (++total > PROJ_TOTAL) break;
            int n = total;
            String pre = "proj_" + n;
            boolean done = "completed".equals(p.status) || "archived".equals(p.status);
            t.put(pre + "_title", esc(cut(p.title, 20)));
            t.put(pre + "_id", String.valueOf(p.id));
            t.put(pre + "_status", statusName(p.status));
            t.put(pre + "_creator", esc(p.creator));
            t.put(pre + "_date", fmtDate(done ? p.completedAt : p.createdAt));
            slotOf.put(p.id, n);
            t.put(pre + "_menu", "memo-project-" + n);   // 详情页固定按槽位序号，数据变更无需重导出
            t.put(pre + "_line", esc(cut(p.title, 20)) + " · " + statusName(p.status));
            t.put(pre + "_desc", esc(fixedLines(p.desc == null || p.desc.isBlank() ? "（暂无描述）" : p.desc, 5, 280, 8)));
            t.put(pre + "_show", "/memo show " + p.id);   // 完整说明走聊天 UI（描述超出 5 行时用）
            t.put(pre + "_locHidden", p.locHidden ? "1" : "");
            t.put(pre + "_managers", String.join(",", p.managerUuids));
            t.put(pre + "_loc", esc(locText(p)));
        }
        for (int k = total + 1; k <= PROJ_TOTAL; k++) {
            t.put("proj_" + k + "_menu", "memo-empty");
            t.put("proj_" + k + "_line", "");
            t.put("proj_" + k + "_desc", "");
            t.put("proj_" + k + "_loc", "");
            t.put("proj_" + k + "_show", "");
            t.put("proj_" + k + "_locHidden", "");
            t.put("proj_" + k + "_managers", "");
        }
        t.put("proj_count", String.valueOf(total));
        for (String state : STATES) {
            int c = 0;
            for (MemoData.Project p : data.projects) {
                if ("completed".equals(state)) {
                    if ("completed".equals(p.status) || "archived".equals(p.status)) c++;
                } else if (state.equals(p.status)) c++;
            }
            t.put("proj_" + state + "_count", String.valueOf(c));
        }

        // ── 地标（按维度分组）──
        List<LocationsReader.Landmark> landmarks = plugin.getLocations().read();
        int locTotal = 0;
        for (String[] dim : DIMS) {
            int dimId = Integer.parseInt(dim[1]);
            int n = 0, count = 0;
            for (LocationsReader.Landmark lm : landmarks) {
                if (lm.dim != dimId) continue;
                count++;
                if (++n > LOC_PER_DIM) continue;
                String pre = "loc_" + dim[0] + "_" + n;
                int pid = lm.projectId();
                t.put(pre + "_name", esc(cut(lm.name, 14)));
                t.put(pre + "_coord", lm.ix() + " " + lm.iy() + " " + lm.iz());
                t.put(pre + "_x", String.valueOf(lm.ix()));
                t.put(pre + "_y", String.valueOf(lm.iy()));
                t.put(pre + "_z", String.valueOf(lm.iz()));
                t.put(pre + "_desc", esc(truncate(lm.desc, 40)));
                t.put(pre + "_project", pid > 0 ? String.valueOf(pid) : "");
                t.put(pre + "_menu", pid > 0 && slotOf.containsKey(pid) ? "memo-project-" + slotOf.get(pid) : "memo-empty");
                t.put(pre + "_line", esc(cut(lm.name, 14)) + " @ " + lm.ix() + " " + lm.iy() + " " + lm.iz());
            }
            for (int k = n + 1; k <= LOC_PER_DIM; k++) {
                t.put("loc_" + dim[0] + "_" + k + "_menu", "memo-empty");
                t.put("loc_" + dim[0] + "_" + k + "_line", "");
            }
            t.put("loc_" + dim[0] + "_count", String.valueOf(count));
            locTotal += count;
        }

        // ── 使用说明（inManual）──
        int manualCount = 0, mn = 0;
        for (MemoData.Project p : data.projects) {
            if (!p.inManual) continue;
            manualCount++;
            if (++mn > MANUAL_MAX) continue;
            String pre = "manual_" + mn;
            t.put(pre + "_title", esc(cut(p.title, 20)));
            t.put(pre + "_id", String.valueOf(p.id));
            t.put(pre + "_status", statusName(p.status));
            t.put(pre + "_creator", esc(p.creator));
            t.put(pre + "_date", fmtDate(p.createdAt));
            t.put(pre + "_menu", slotOf.containsKey(p.id) ? "memo-project-" + slotOf.get(p.id) : "memo-empty");
            t.put(pre + "_line", esc(cut(p.title, 20)) + " · " + statusName(p.status));
        }
        for (int k = mn + 1; k <= MANUAL_MAX; k++) {
            t.put("manual_" + k + "_menu", "memo-empty");
            t.put("manual_" + k + "_line", "");
        }
        t.put("manual_count", String.valueOf(manualCount));

        // ── 实时值与汇总 ──
        int active = 0, completed = 0;
        for (MemoData.Project p : data.projects) {
            if ("active".equals(p.status)) active++;
            else if ("completed".equals(p.status) || "archived".equals(p.status)) completed++;
        }
        t.put("projects_total", String.valueOf(data.projects.size()));
        t.put("projects_active", String.valueOf(active));
        t.put("projects_completed", String.valueOf(completed));
        t.put("landmarks_total", String.valueOf(locTotal));
        t.put("manual_total", String.valueOf(manualCount));

        table = t;
        summary = "工程 " + data.projects.size() + "（进行中 " + active + "）· 地标 " + locTotal
                + " · 使用说明 " + manualCount + " · 槽位 " + t.size();
    }

    private static String statusName(String status) {
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
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT);
    }

    /** ArcMenu 用 & 作颜色码前缀，玩家文本里的 & 需转义为 &&。 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&&");
    }

    /** 行内标题的硬截断（ArcMenu 文本不自动收缩，超长会溢出画布）。 */
    private static String cut(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
