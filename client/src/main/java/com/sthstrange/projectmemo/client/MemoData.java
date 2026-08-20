package com.sthstrange.projectmemo.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * data.json 客户端镜像模型（Gson 解析，只读）。
 * 结构与服务端 MemoData 对齐（version=3）；渲染层不直接碰 JsonObject。
 */
public final class MemoData {

    public final List<Project> projects = new ArrayList<>();
    public final List<Task> tasks = new ArrayList<>();
    public final List<MaterialRow> materials = new ArrayList<>();

    // ───────────────────────── 实体 ─────────────────────────

    public static final class Project {
        public int id;
        public String title = "";
        public String desc = "";
        public String status = "planning";
        public String creator = "";
        public String creatorUuid = "";
        public final List<String> managers = new ArrayList<>();
        public long createdAt;
        public long completedAt;
        public final List<String> participants = new ArrayList<>();
        public String locWorld = "";
        public int locX, locY, locZ;
        public String locNote = "";
        public boolean locHidden;
        public boolean locLocked;
        public String depWorld = "";
        public int depX1, depY1, depZ1, depX2, depY2, depZ2;
        public boolean depLocked;        // 角点锁
        public String soWorld = "";
        public int soX, soY, soZ;
        public final List<String> tags = new ArrayList<>();
        public String buildNote = "";
        public final List<SchemEntry> schematics = new ArrayList<>();
    }

    public static final class SchemEntry {
        public String name = "";
        public String source = "";   // shared | local
        public String by = "";
        public long at;
        public long blocks;
        public int kinds;
        public boolean hasOrigin;
        public int ox, oy, oz;
    }

    public static final class Task {
        public int id;
        public int projectId;
        public String title = "";
        public String status = "open";   // open|claimed|done
        public String assignee = "";
        public long claimedAt;
        public long doneAt;
        public String note = "";
        public int sort;
        public String type = "custom";   // custom | collect
        public final List<Integer> materialIds = new ArrayList<>(); // collect 任务关联的材料行
    }

    public static final class MaterialRow {
        public int id;
        public int projectId;
        public String item = "";
        public String itemName = "";
        public String source = "custom"; // custom=手动，其他=投影名
        public long need;
        public long delivered;
        public final List<String> claimedBy = new ArrayList<>();
        public final List<Contribution> contributions = new ArrayList<>();

        public static final class Contribution {
            public String by = "";
            public long amount;
            public long at;
        }
    }

    // ───────────────────────── 解析 ─────────────────────────

    public static MemoData parse(JsonObject root) {
        MemoData d = new MemoData();
        if (root == null) return d;
        if (root.has("projects")) for (JsonElement e : root.getAsJsonArray("projects")) d.projects.add(parseProject(e.getAsJsonObject()));
        if (root.has("tasks")) for (JsonElement e : root.getAsJsonArray("tasks")) d.tasks.add(parseTask(e.getAsJsonObject()));
        if (root.has("materials")) for (JsonElement e : root.getAsJsonArray("materials")) d.materials.add(parseMaterial(e.getAsJsonObject()));
        return d;
    }

    private static Project parseProject(JsonObject o) {
        Project p = new Project();
        p.id = optInt(o, "id");
        p.title = optStr(o, "title");
        p.desc = optStr(o, "desc");
        p.status = optStr(o, "status", "planning");
        p.creator = optStr(o, "creator");
        p.creatorUuid = optStr(o, "creatorUuid");
        for (String s : optArr(o, "managers")) p.managers.add(s);
        p.createdAt = optLong(o, "createdAt");
        p.completedAt = optLong(o, "completedAt");
        for (String s : optArr(o, "participants")) p.participants.add(s);
        if (o.has("location") && o.get("location").isJsonObject()) {
            JsonObject loc = o.getAsJsonObject("location");
            p.locWorld = optStr(loc, "world");
            p.locX = optInt(loc, "x");
            p.locY = optInt(loc, "y");
            p.locZ = optInt(loc, "z");
            p.locNote = optStr(loc, "note");
            p.locHidden = optBool(loc, "hidden");
            p.locLocked = optBool(loc, "locked");
        }
        if (o.has("depositArea") && o.get("depositArea").isJsonObject()) {
            JsonObject dep = o.getAsJsonObject("depositArea");
            p.depWorld = optStr(dep, "world");
            p.depX1 = optInt(dep, "x1"); p.depY1 = optInt(dep, "y1"); p.depZ1 = optInt(dep, "z1");
            p.depX2 = optInt(dep, "x2"); p.depY2 = optInt(dep, "y2"); p.depZ2 = optInt(dep, "z2");
            p.depLocked = optBool(dep, "locked");
        }
        if (o.has("schematicOrigin") && o.get("schematicOrigin").isJsonObject()) {
            JsonObject so = o.getAsJsonObject("schematicOrigin");
            p.soWorld = optStr(so, "world");
            p.soX = optInt(so, "x");
            p.soY = optInt(so, "y");
            p.soZ = optInt(so, "z");
        }
        for (String s : optArr(o, "tags")) p.tags.add(s);
        p.buildNote = optStr(o, "buildNote");
        if (o.has("schematics") && o.get("schematics").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("schematics")) {
                if (!e.isJsonObject()) continue;
                JsonObject so = e.getAsJsonObject();
                SchemEntry se = new SchemEntry();
                se.name = optStr(so, "name");
                se.source = optStr(so, "source");
                se.by = optStr(so, "by");
                se.at = optLong(so, "at");
                se.blocks = optLong(so, "blocks");
                se.kinds = optInt(so, "kinds");
                se.hasOrigin = optBool(so, "hasOrigin");
                se.ox = optInt(so, "ox");
                se.oy = optInt(so, "oy");
                se.oz = optInt(so, "oz");
                p.schematics.add(se);
            }
        }
        return p;
    }

    private static Task parseTask(JsonObject o) {
        Task t = new Task();
        t.id = optInt(o, "id");
        t.projectId = optInt(o, "projectId");
        t.title = optStr(o, "title");
        t.status = optStr(o, "status", "open");
        t.assignee = optStr(o, "assignee");
        t.claimedAt = optLong(o, "claimedAt");
        t.doneAt = optLong(o, "doneAt");
        t.note = optStr(o, "note");
        t.sort = optInt(o, "sort");
        t.type = optStr(o, "type", "custom");
        if (o.has("materialIds") && o.get("materialIds").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("materialIds")) {
                try { t.materialIds.add(e.getAsInt()); } catch (Exception ignored) { }
            }
        } else {
            int legacy = optInt(o, "materialId", -1);
            if (legacy >= 0) t.materialIds.add(legacy);
        }
        return t;
    }

    private static MaterialRow parseMaterial(JsonObject o) {
        MaterialRow m = new MaterialRow();
        m.id = optInt(o, "id");
        m.projectId = optInt(o, "projectId");
        m.item = optStr(o, "item");
        m.itemName = optStr(o, "itemName");
        m.source = optStr(o, "source", "custom");
        m.need = optLong(o, "need");
        m.delivered = optLong(o, "delivered");
        for (String s : optArr(o, "claimedBy")) m.claimedBy.add(s);
        if (o.has("contributions")) for (JsonElement e : o.getAsJsonArray("contributions")) {
            if (!e.isJsonObject()) continue;
            JsonObject c = e.getAsJsonObject();
            MaterialRow.Contribution con = new MaterialRow.Contribution();
            con.by = optStr(c, "by");
            con.amount = optLong(c, "amount");
            con.at = optLong(c, "at");
            m.contributions.add(con);
        }
        return m;
    }

    // ───────────────────────── 便捷查询 ─────────────────────────

    public Project projectById(int id) {
        for (Project p : projects) if (p.id == id) return p;
        return null;
    }

    public Task taskById(int id) {
        for (Task t : tasks) if (t.id == id) return t;
        return null;
    }

    public MaterialRow materialById(int id) {
        for (MaterialRow m : materials) if (m.id == id) return m;
        return null;
    }

    public List<Task> tasksOf(int projectId) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks) if (t.projectId == projectId) out.add(t);
        out.sort((a, b) -> a.sort != b.sort ? Integer.compare(a.sort, b.sort) : Integer.compare(a.id, b.id));
        return out;
    }

    public List<MaterialRow> materialsOf(int projectId) {
        List<MaterialRow> out = new ArrayList<>();
        for (MaterialRow m : materials) if (m.projectId == projectId) out.add(m);
        return out;
    }

    public int materialPct(int projectId) {
        long need = 0, delivered = 0;
        for (MaterialRow m : materialsOf(projectId)) {
            need += m.need;
            delivered += Math.min(m.delivered, m.need);
        }
        return need == 0 ? 0 : (int) (delivered * 100 / need);
    }

    // ───────────────────────── JSON 工具 ─────────────────────────

    static String optStr(JsonObject o, String key) { return optStr(o, key, ""); }

    static String optStr(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : def;
    }

    static int optInt(JsonObject o, String key, int def) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def; }
        catch (Exception e) { return def; }
    }

    static int optInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return 0;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    static long optLong(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return 0;
        try { return o.get(key).getAsLong(); } catch (Exception e) { return 0; }
    }

    static boolean optBool(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return false;
        try { return o.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }

    static List<String> optArr(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return out;
        JsonArray arr = o.getAsJsonArray(key);
        for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
