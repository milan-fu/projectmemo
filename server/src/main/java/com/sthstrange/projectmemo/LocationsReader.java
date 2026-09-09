package com.sthstrange.projectmemo;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LocationMarker(!!loc) 路标库 locations.json 的只读读取器（v1.2.0）。
 * mtime+长度缓存；mod 地标页 payload、聊天 /memo landmarks、LocSync 回验共用。
 * 路标条目管理归 !!loc（LocationMarker），本插件只读查看 + 经 remote_console 走官方命令写入。
 */
public final class LocationsReader {

    /** ProjectMemo 自动收录条目的备注标记：工程#<id> · ProjectMemo 自动收录 */
    public static final Pattern PROJECT_MARK = Pattern.compile("工程#(\\d+)");

    public static final class Landmark {
        public String name = "";
        public String desc = "";
        public int dim;
        public double x, y, z;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("desc", desc);
            o.put("dim", dim);
            o.put("x", (int) Math.round(x));
            o.put("y", (int) Math.round(y));
            o.put("z", (int) Math.round(z));
            return o;
        }

        /** ProjectMemo 自动收录的地标 → 工程 id；-1 = 普通路标 */
        public int projectId() {
            Matcher m = PROJECT_MARK.matcher(desc);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        public int ix() { return (int) Math.round(x); }
        public int iy() { return (int) Math.round(y); }
        public int iz() { return (int) Math.round(z); }
    }

    private final ProjectMemoPlugin plugin;
    private final File file;
    private long lastMtime = -1, lastLen = -1;
    private List<Landmark> cache = new ArrayList<>();
    private boolean warnedMissing = false;

    public LocationsReader(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
        String path = plugin.getConfig().getString("locations-file", "../config/location_marker/locations.json");
        File cwdRel = new File(path);
        File dataRel = new File(plugin.getDataFolder(), path);
        File pick = cwdRel.exists() ? cwdRel : (dataRel.exists() ? dataRel : cwdRel);
        this.file = pick.getAbsoluteFile();
    }

    public File file() { return file; }

    /** 强制重读（LocSync 回验用；日常 read() 走 mtime 缓存） */
    public synchronized List<Landmark> readFresh() {
        lastMtime = -1;
        return read();
    }

    public synchronized List<Landmark> read() {
        if (!file.exists()) {
            if (!warnedMissing) {
                plugin.getLogger().warning("路标库不存在（未装 LocationMarker 属正常）: " + file.getPath());
                warnedMissing = true;
            }
            cache = new ArrayList<>();
            return cache;
        }
        warnedMissing = false;
        long m = file.lastModified(), len = file.length();
        if (m == lastMtime && len == lastLen) return cache;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(text);
            List<Landmark> out = new ArrayList<>();
            for (Object o : arr) {
                if (!(o instanceof JSONObject)) continue;
                JSONObject j = (JSONObject) o;
                Landmark lm = new Landmark();
                lm.name = j.optString("name");
                Object dv = j.opt("desc");
                lm.desc = (dv == null || dv == JSONObject.NULL) ? "" : String.valueOf(dv);
                lm.dim = j.optInt("dim");
                JSONObject pos = j.optJSONObject("pos");
                if (pos != null) {
                    lm.x = pos.optDouble("x");
                    lm.y = pos.optDouble("y");
                    lm.z = pos.optDouble("z");
                }
                if (!lm.name.isEmpty()) out.add(lm);
            }
            cache = out;
            lastMtime = m;
            lastLen = len;
        } catch (Exception e) {
            plugin.getLogger().warning("路标库解析失败（保留上次缓存）: " + e);
        }
        return cache;
    }

    /** Bukkit 世界名 → LocationMarker 维度 id（0主世界/-1下界/1末地）。仅主线程调用（Bukkit.getWorld）。 */
    public static int worldToDim(String worldName) {
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w != null) {
            switch (w.getEnvironment()) {
                case NETHER: return -1;
                case THE_END: return 1;
                default: return 0;
            }
        }
        String n = worldName == null ? "" : worldName.toLowerCase();
        if (n.contains("nether")) return -1;
        if (n.contains("end")) return 1;
        return 0;
    }
}
