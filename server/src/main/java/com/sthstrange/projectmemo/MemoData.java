package com.sthstrange.projectmemo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备忘录数据模型（data.json）。version=3，含 initiatives/dailyLog 预留字段（活动模块 M6）。
 * 完成态冻结；解档权=本工程管理者+OP（服主已拍板）。
 */
public final class MemoData {

    public int version = 3;
    public int nextProjectId = 1;
    public int nextTaskId = 1;
    public int nextMaterialId = 1;
    public int nextInitiativeId = 1;

    public final List<Project> projects = new ArrayList<>();
    public final List<Task> tasks = new ArrayList<>();
    public final List<MaterialRow> materials = new ArrayList<>();
    public final List<Initiative> initiatives = new ArrayList<>();

    /** uuid -> date(yyyy-MM-dd) -> [taskIds...]（活动模块用，M0 仅存取保留） */
    public final Map<String, Map<String, List<String>>> dailyLog = new LinkedHashMap<>();

    /** uuid -> {date, count} 每日大条目创建计数（服务器时区自然日） */
    public final Map<String, JSONObject> createCounters = new LinkedHashMap<>();

    /** 滚动审计日志，上限 500 */
    public final List<JSONObject> audit = new ArrayList<>();
    public static final int AUDIT_CAP = 500;

    // ───────────────────────── 实体 ─────────────────────────

    public static final class Project {
        public int id;
        public String title = "";
        public String desc = "";
        public String status = "planning";          // planning|active|completed|archived
        public String creator = "";
        public String creatorUuid = "";
        public final List<String> managers = new ArrayList<>();      // 名字
        public final List<String> managerUuids = new ArrayList<>();  // UUID（与 managers 平行）
        public long createdAt;
        public long completedAt;
        public final List<String> participants = new ArrayList<>();  // 名字（竣工冻结）
        public String locWorld = "";
        public int locX, locY, locZ;
        public String locNote = "";
        public boolean locHidden;        // 隐藏坐标（仅管理者/OP 可见）
        public boolean locLocked;        // 选址锁：防止误改
        public String soWorld = "";      // 投影原点（导入 litematic 时记录）
        public int soX, soY, soZ;
        public String depWorld = "";     // 材料收集核验区域（两角点）
        public int depX1, depY1, depZ1, depX2, depY2, depZ2;
        public boolean depLocked;        // 角点锁：防止角点被误改
        public final List<String> tags = new ArrayList<>();
        public String buildNote = "";
        public boolean inManual;             // v1.2.0：已收录进「机器使用说明」（竣工后仍可切换，归档锁）
        public String locSyncedName = "";    // v1.2.0：已同步到 LocationMarker 的路标名（空=未同步；防误删记账）
        /** 已导入的投影记录：{name, source(shared|local), file, by, at, blocks, kinds, ox,oy,oz,hasOrigin} */
        public final List<JSONObject> schematics = new ArrayList<>();

        public boolean isManagerUuid(String uuid) {
            return managerUuids.contains(uuid);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("desc", desc);
            o.put("status", status);
            o.put("creator", creator);
            o.put("creatorUuid", creatorUuid);
            o.put("managers", new JSONArray(managers));
            o.put("managerUuids", new JSONArray(managerUuids));
            o.put("createdAt", createdAt);
            o.put("completedAt", completedAt);
            o.put("participants", new JSONArray(participants));
            JSONObject loc = new JSONObject();
            loc.put("world", locWorld);
            loc.put("x", locX);
            loc.put("y", locY);
            loc.put("z", locZ);
            loc.put("note", locNote);
            loc.put("hidden", locHidden);
            loc.put("locked", locLocked);
            o.put("location", loc);
            if (!depWorld.isEmpty()) {
                JSONObject dep = new JSONObject();
                dep.put("world", depWorld);
                dep.put("x1", depX1); dep.put("y1", depY1); dep.put("z1", depZ1);
                dep.put("x2", depX2); dep.put("y2", depY2); dep.put("z2", depZ2);
                dep.put("locked", depLocked);
                o.put("depositArea", dep);
            }
            if (!soWorld.isEmpty()) {
                JSONObject so = new JSONObject();
                so.put("world", soWorld);
                so.put("x", soX);
                so.put("y", soY);
                so.put("z", soZ);
                o.put("schematicOrigin", so);
            }
            o.put("tags", new JSONArray(tags));
            o.put("buildNote", buildNote);
            o.put("inManual", inManual);
            if (!locSyncedName.isEmpty()) o.put("locSyncedName", locSyncedName);
            o.put("schematics", new JSONArray(schematics));
            return o;
        }

        public static Project fromJson(JSONObject o) {
            Project p = new Project();
            p.id = o.optInt("id");
            p.title = o.optString("title");
            p.desc = o.optString("desc");
            p.status = o.optString("status", "planning");
            p.creator = o.optString("creator");
            p.creatorUuid = o.optString("creatorUuid");
            for (Object x : o.optJSONArray("managers")) p.managers.add(String.valueOf(x));
            for (Object x : o.optJSONArray("managerUuids")) p.managerUuids.add(String.valueOf(x));
            p.createdAt = o.optLong("createdAt");
            p.completedAt = o.optLong("completedAt");
            for (Object x : o.optJSONArray("participants")) p.participants.add(String.valueOf(x));
            JSONObject loc = o.optJSONObject("location");
            if (loc != null) {
                p.locWorld = loc.optString("world");
                p.locX = loc.optInt("x");
                p.locY = loc.optInt("y");
                p.locZ = loc.optInt("z");
                p.locNote = loc.optString("note");
                p.locHidden = loc.optBoolean("hidden");
                p.locLocked = loc.optBoolean("locked");
            }
            JSONObject dep = o.optJSONObject("depositArea");
            if (dep != null) {
                p.depWorld = dep.optString("world");
                p.depX1 = dep.optInt("x1"); p.depY1 = dep.optInt("y1"); p.depZ1 = dep.optInt("z1");
                p.depX2 = dep.optInt("x2"); p.depY2 = dep.optInt("y2"); p.depZ2 = dep.optInt("z2");
                p.depLocked = dep.optBoolean("locked");
            }
            JSONObject so = o.optJSONObject("schematicOrigin");
            if (so != null) {
                p.soWorld = so.optString("world");
                p.soX = so.optInt("x");
                p.soY = so.optInt("y");
                p.soZ = so.optInt("z");
            }
            for (Object x : o.optJSONArray("tags")) p.tags.add(String.valueOf(x));
            p.buildNote = o.optString("buildNote");
            p.inManual = o.optBoolean("inManual");
            p.locSyncedName = o.optString("locSyncedName");
            JSONArray sch = o.optJSONArray("schematics");
            if (sch != null) for (Object x : sch) if (x instanceof JSONObject) p.schematics.add((JSONObject) x);
            return p;
        }
    }

    public static final class Task {
        public int id;
        public int projectId;
        public String title = "";
        public String status = "open";              // open|claimed|done
        public String assignee = "";
        public String assigneeUuid = "";
        public long claimedAt;
        public long doneAt;
        public String note = "";
        public int sort;
        public String type = "custom";              // custom=自定义任务 | collect=材料收集任务
        public final List<Integer> materialIds = new ArrayList<>(); // collect 任务关联的材料行（可多个）

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("projectId", projectId);
            o.put("title", title);
            o.put("status", status);
            o.put("assignee", assignee);
            o.put("assigneeUuid", assigneeUuid);
            o.put("claimedAt", claimedAt);
            o.put("doneAt", doneAt);
            o.put("note", note);
            o.put("sort", sort);
            o.put("type", type);
            o.put("materialIds", new JSONArray(materialIds));
            return o;
        }

        public static Task fromJson(JSONObject o) {
            Task t = new Task();
            t.id = o.optInt("id");
            t.projectId = o.optInt("projectId");
            t.title = o.optString("title");
            t.status = o.optString("status", "open");
            t.assignee = o.optString("assignee");
            t.assigneeUuid = o.optString("assigneeUuid");
            t.claimedAt = o.optLong("claimedAt");
            t.doneAt = o.optLong("doneAt");
            t.note = o.optString("note");
            t.sort = o.optInt("sort");
            t.type = o.optString("type", "custom");
            JSONArray mids = o.optJSONArray("materialIds");
            if (mids != null) {
                for (Object x : mids) t.materialIds.add(((Number) x).intValue());
            } else {
                int legacy = o.optInt("materialId", -1);   // 兼容旧数据
                if (legacy >= 0) t.materialIds.add(legacy);
            }
            return t;
        }
    }

    public static final class MaterialRow {
        public int id;
        public int projectId;
        public String item = "";        // registry id
        public String itemName = "";    // 显示名
        public String source = "custom"; // 来源：custom=手动添加，其他=投影名
        public long need;
        public long delivered;
        public final List<String> claimedBy = new ArrayList<>();
        public final List<JSONObject> contributions = new ArrayList<>(); // {by, amount, at}
        public boolean locked;          // M6 PCH 式交付确认预留

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("projectId", projectId);
            o.put("item", item);
            o.put("itemName", itemName);
            o.put("source", source);
            o.put("need", need);
            o.put("delivered", delivered);
            o.put("claimedBy", new JSONArray(claimedBy));
            o.put("contributions", new JSONArray(contributions));
            o.put("locked", locked);
            return o;
        }

        public static MaterialRow fromJson(JSONObject o) {
            MaterialRow m = new MaterialRow();
            m.id = o.optInt("id");
            m.projectId = o.optInt("projectId");
            m.item = o.optString("item");
            m.itemName = o.optString("itemName");
            m.source = o.optString("source", "custom");
            m.need = o.optLong("need");
            m.delivered = o.optLong("delivered");
            for (Object x : o.optJSONArray("claimedBy")) m.claimedBy.add(String.valueOf(x));
            JSONArray cs = o.optJSONArray("contributions");
            if (cs != null) for (Object x : cs) m.contributions.add((JSONObject) x);
            m.locked = o.optBoolean("locked");
            return m;
        }
    }

    /** 长期活动/名单制（活动模块 M6，数据结构预留） */
    public static final class Initiative {
        public int id;
        public String title = "";
        public String desc = "";
        public String creator = "";
        public long createdAt;
        public String status = "open";  // open|ended（不冻结，可重开）
        public final List<JSONObject> members = new ArrayList<>(); // {by, uuid, at, note}
        public final List<JSONObject> slots = new ArrayList<>();   // {id,title,status,assignee,claimedAt}

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("desc", desc);
            o.put("creator", creator);
            o.put("createdAt", createdAt);
            o.put("status", status);
            o.put("members", new JSONArray(members));
            o.put("slots", new JSONArray(slots));
            return o;
        }

        public static Initiative fromJson(JSONObject o) {
            Initiative i = new Initiative();
            i.id = o.optInt("id");
            i.title = o.optString("title");
            i.desc = o.optString("desc");
            i.creator = o.optString("creator");
            i.createdAt = o.optLong("createdAt");
            i.status = o.optString("status", "open");
            JSONArray ms = o.optJSONArray("members");
            if (ms != null) for (Object x : ms) i.members.add((JSONObject) x);
            JSONArray ss = o.optJSONArray("slots");
            if (ss != null) for (Object x : ss) i.slots.add((JSONObject) x);
            return i;
        }
    }

    // ───────────────────────── JSON ─────────────────────────

    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        root.put("version", version);
        root.put("nextProjectId", nextProjectId);
        root.put("nextTaskId", nextTaskId);
        root.put("nextMaterialId", nextMaterialId);
        root.put("nextInitiativeId", nextInitiativeId);
        JSONArray ps = new JSONArray();
        for (Project p : projects) ps.put(p.toJson());
        root.put("projects", ps);
        JSONArray ts = new JSONArray();
        for (Task t : tasks) ts.put(t.toJson());
        root.put("tasks", ts);
        JSONArray ms = new JSONArray();
        for (MaterialRow m : materials) ms.put(m.toJson());
        root.put("materials", ms);
        JSONArray is = new JSONArray();
        for ( Initiative i : initiatives) is.put(i.toJson());
        root.put("initiatives", is);
        JSONObject dl = new JSONObject();
        for (Map.Entry<String, Map<String, List<String>>> e : dailyLog.entrySet()) {
            JSONObject dates = new JSONObject();
            for (Map.Entry<String, List<String>> d : e.getValue().entrySet()) {
                dates.put(d.getKey(), new JSONArray(d.getValue()));
            }
            dl.put(e.getKey(), dates);
        }
        root.put("dailyLog", dl);
        JSONObject cc = new JSONObject();
        for (Map.Entry<String, JSONObject> e : createCounters.entrySet()) cc.put(e.getKey(), e.getValue());
        root.put("createCounters", cc);
        JSONArray au = new JSONArray();
        for (JSONObject a : audit) au.put(a);
        root.put("audit", au);
        return root;
    }

    public static MemoData fromJson(JSONObject root) {
        MemoData d = new MemoData();
        d.version = root.optInt("version", 3);
        d.nextProjectId = root.optInt("nextProjectId", 1);
        d.nextTaskId = root.optInt("nextTaskId", 1);
        d.nextMaterialId = root.optInt("nextMaterialId", 1);
        d.nextInitiativeId = root.optInt("nextInitiativeId", 1);
        JSONArray ps = root.optJSONArray("projects");
        if (ps != null) for (Object x : ps) d.projects.add(Project.fromJson((JSONObject) x));
        JSONArray ts = root.optJSONArray("tasks");
        if (ts != null) for (Object x : ts) d.tasks.add(Task.fromJson((JSONObject) x));
        JSONArray ms = root.optJSONArray("materials");
        if (ms != null) for (Object x : ms) d.materials.add(MaterialRow.fromJson((JSONObject) x));
        JSONArray is = root.optJSONArray("initiatives");
        if (is != null) for (Object x : is) d.initiatives.add(Initiative.fromJson((JSONObject) x));
        JSONObject dl = root.optJSONObject("dailyLog");
        if (dl != null) {
            for (String uuid : dl.keySet()) {
                JSONObject dates = dl.getJSONObject(uuid);
                Map<String, List<String>> m = new LinkedHashMap<>();
                for (String date : dates.keySet()) {
                    List<String> list = new ArrayList<>();
                    for (Object x : dates.getJSONArray(date)) list.add(String.valueOf(x));
                    m.put(date, list);
                }
                d.dailyLog.put(uuid, m);
            }
        }
        JSONObject cc = root.optJSONObject("createCounters");
        if (cc != null) for (String uuid : cc.keySet()) d.createCounters.put(uuid, cc.getJSONObject(uuid));
        JSONArray au = root.optJSONArray("audit");
        if (au != null) for (Object x : au) d.audit.add((JSONObject) x);
        return d;
    }

    // ───────────────────────── 便捷查询 ─────────────────────────

    public Project projectById(int id) {
        for (Project p : projects) if (p.id == id) return p;
        return null;
    }

    /** 按 id 或标题（忽略大小写/包含）找工程 */
    public Project projectByRef(String ref) {
        try {
            Project p = projectById(Integer.parseInt(ref));
            if (p != null) return p;
        } catch (NumberFormatException ignored) { }
        for (Project p : projects) if (p.title.equalsIgnoreCase(ref)) return p;
        for (Project p : projects) if (p.title.toLowerCase().contains(ref.toLowerCase())) return p;
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

    public void addParticipant(Project p, String name) {
        if (name != null && !name.isEmpty() && !p.participants.contains(name)) p.participants.add(name);
    }

    public void addAudit(long at, String by, String op, String detail) {
        JSONObject a = new JSONObject();
        a.put("at", at);
        a.put("by", by);
        a.put("op", op);
        a.put("detail", detail);
        audit.add(a);
        while (audit.size() > AUDIT_CAP) audit.remove(0);
    }
}
