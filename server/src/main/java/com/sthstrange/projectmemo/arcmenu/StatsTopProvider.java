package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.ProjectMemoPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 排行榜数据源（memo-server 1.2.0-arcmenu · C 段）。
 *
 * 复用服务器既有 `!!stats <type>` 查询（经 MCDR remote_console 请求-响应，返回 JSON），
 * 与 QQ `#stats` 完全同口径；异步定时刷新 + 内存缓存，PAPI 求值只读缓存。
 * 分类：online(在线时长) / mine / place / kill / achievement / death。
 */
public final class StatsTopProvider {

    public static final String[] CATS = {"online", "mine", "place", "kill", "achievement", "death"};
    public static final String[] LABELS = {"在线时长", "挖掘数", "放置数", "击杀数", "成就数", "死亡数"};
    public static final int TOP_N = 10;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static final class Row {
        public final int rank;
        public final String name;
        public final String value;

        Row(int rank, String name, String value) {
            this.rank = rank;
            this.name = name;
            this.value = value;
        }
    }

    private final ProjectMemoPlugin plugin;
    private final Map<String, List<Row>> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> updatedAt = new ConcurrentHashMap<>();
    private BukkitTask task;

    public StatsTopProvider(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int minutes = Math.max(1, plugin.getConfig().getInt("arcmenu.stats-refresh-min", 1));
        long period = minutes * 60L * 20L;
        task = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::refreshAll, 100L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public List<Row> rows(String cat) {
        return cache.getOrDefault(cat, Collections.emptyList());
    }

    /** 某分类最近一次成功刷新的时间（HH:mm:ss），未刷新返回空串。 */
    public String updatedText(String cat) {
        Long t = updatedAt.get(cat);
        if (t == null) return "";
        return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime().format(HM);
    }

    public static String label(String cat) {
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i].equals(cat)) return LABELS[i];
        }
        return cat;
    }


    /** writer：把当前缓存打包成 JSON，供 Redis 发布给创造服。 */
    private String buildStatsJson() {
        JSONObject root = new JSONObject();
        root.put("updated", System.currentTimeMillis());
        JSONObject cats = new JSONObject();
        for (String cat : CATS) {
            List<Row> rows = cache.get(cat);
            if (rows == null) continue;
            JSONArray arr = new JSONArray();
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("rank", r.rank);
                o.put("name", r.name);
                o.put("value", r.value);
                arr.put(o);
            }
            cats.put(cat, arr);
        }
        root.put("cats", cats);
        try {
            // 玩家统计（「我的数据」页）也一起镜像给创造服
            root.put("players", com.sthstrange.projectmemo.arcmenu.Slots.collectPlayers(
                    new java.io.File(org.bukkit.Bukkit.getWorlds().get(0).getWorldFolder(), "stats")));
        } catch (Throwable ignored) {
            // 无世界/无 stats 时忽略
        }
        return root.toString();
    }

    /** mirror：主服同步过来的玩家统计（uuid → 各项），供「我的数据」显示主服口径。 */
    private volatile org.json.JSONObject remotePlayers;

    /** mirror：读该玩家的某项统计；无数据返回 null（由调用方回落到本地）。 */
    public String remotePlayerStat(String uuid, String field) {
        org.json.JSONObject p = remotePlayers;
        if (p == null) return null;
        org.json.JSONObject o = p.optJSONObject(uuid);
        if (o == null) return null;
        String v = o.optString(field, "");
        return v.isEmpty() ? null : v;
    }

    /** mirror：用 Redis 推来的主服榜单覆盖本地缓存（创造服显示主服数据）。 */
    public void applyRemote(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject pl = root.optJSONObject("players");
            if (pl != null) remotePlayers = pl;
            JSONObject cats = root.optJSONObject("cats");
            if (cats == null) return;
            for (String cat : CATS) {
                JSONArray arr = cats.optJSONArray(cat);
                if (arr == null) continue;
                List<Row> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r != null) list.add(new Row(r.optInt("rank", i + 1), r.optString("name"), r.optString("value")));
                }
                cache.put(cat, list);
                updatedAt.put(cat, System.currentTimeMillis());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("arcmenu: Redis 排行榜解析失败: " + e);
        }
    }

    private void refreshAll() {
        // mirror（创造服）：榜单完全来自 Redis，不能本地查询否则会覆盖主服数据
        if (plugin.isMirror()) return;
        String host = plugin.getConfig().getString("arcmenu.stats.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("arcmenu.stats.remote-console-port", 25999);
        for (String cat : CATS) {
            String json = exec(host, port, "!!stats " + cat);
            if (json == null || json.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(json.trim());
                if (!"top".equals(o.optString("cmd"))) continue;
                JSONArray rows = o.optJSONArray("rows");
                List<Row> list = new ArrayList<>();
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject r = rows.optJSONObject(i);
                        if (r == null) continue;
                        list.add(new Row(r.optInt("rank", i + 1), r.optString("name"), r.optString("text")));
                    }
                }
                cache.put(cat, list);
                updatedAt.put(cat, System.currentTimeMillis());
            } catch (Exception e) {
                plugin.getLogger().warning("arcmenu: 排行榜解析失败(" + cat + "): " + e);
            }
        }
        // writer（主服）：把榜单发布到 Redis，供创造服镜像读取
        if (plugin.isRedisEnabled() && "writer".equals(plugin.getRole())) {
            plugin.publishStats(buildStatsJson());
        }
    }

    /** 向 remote_console 发送 !! 命令并读回响应（与 bot 的 mcdr_exec 同协议）。 */
    private String exec(String host, int port, String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(6000);
            OutputStream out = s.getOutputStream();
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                s.shutdownOutput();
            } catch (Exception ignored) {
                // 某些实现不支持半关闭
            }
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return null;   // 无 stats 插件的服务器（如部分测试环境）静默跳过
        }
    }
}
