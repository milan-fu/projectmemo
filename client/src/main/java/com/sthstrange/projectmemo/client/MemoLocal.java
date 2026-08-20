package com.sthstrange.projectmemo.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 单人档本地后端：无 memo-server 时（单人游戏内置服务器），
 * 全部操作在客户端本地执行，数据保存在 <世界目录>/projectmemo/data.json。
 * 单人玩家视为全权限（OP+creator+一切工程的管理者）。
 */
public final class MemoLocal {

    private static final Gson GSON = new Gson();
    private static boolean active;
    private static JsonObject store;
    private static File file;

    private MemoLocal() { }

    public static boolean isLocal() { return active; }

    /** 进入单人档：加载数据并把客户端状态置为全权限就绪 */
    public static void enterLocal() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        String levelName = "singleplayer";
        try {
            if (server != null) levelName = server.getWorldData().getLevelName();
        } catch (Exception ignored) { }
        file = new File(mc.gameDirectory, "saves/" + levelName + "/projectmemo/data.json");
        store = load();
        active = true;
        JsonObject perms = new JsonObject();
        perms.addProperty("op", true);
        perms.addProperty("canCreate", true);
        perms.addProperty("quotaLeft", 999);
        perms.add("managed", new JsonArray());
        JsonObject snap = new JsonObject();
        snap.add("perms", perms);
        snap.add("data", store);
        MemoClientState.applySnapshot(snap);
        MemoToast.push("单人本地备忘录模式（数据随世界保存）", MemoToast.GREEN);
    }

    public static void exitLocal() { active = false; }

    private static JsonObject load() {
        try {
            if (file != null && file.exists()) {
                String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                ensureArrays(o);
                return o;
            }
        } catch (Exception ignored) { }
        JsonObject o = new JsonObject();
        ensureArrays(o);
        return o;
    }

    private static void ensureArrays(JsonObject o) {
        if (!o.has("projects") || !o.get("projects").isJsonArray()) o.add("projects", new JsonArray());
        if (!o.has("tasks") || !o.get("tasks").isJsonArray()) o.add("tasks", new JsonArray());
        if (!o.has("materials") || !o.get("materials").isJsonArray()) o.add("materials", new JsonArray());
    }

    private static void save() {
        try {
            if (file == null) return;
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Files.write(file.toPath(), GSON.toJson(store).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** 应用并保存：刷新客户端数据镜像（屏幕自动重绘） */
    private static void refresh() {
        JsonObject snap = new JsonObject();
        snap.add("data", store);
        MemoClientState.applySnapshot(snap);
        save();
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static JsonArray projects() { return store.getAsJsonArray("projects"); }
    private static JsonArray tasks() { return store.getAsJsonArray("tasks"); }
    private static JsonArray materials() { return store.getAsJsonArray("materials"); }

    private static int nextId(JsonArray arr) {
        int max = 0;
        for (JsonElement e : arr) {
            if (e.isJsonObject()) max = Math.max(max, optInt(e.getAsJsonObject(), "id"));
        }
        return max + 1;
    }

    private static JsonObject projectById(int id) {
        for (JsonElement e : projects()) {
            if (e.isJsonObject() && optInt(e.getAsJsonObject(), "id") == id) return e.getAsJsonObject();
        }
        return null;
    }

    private static JsonObject taskById(int id) {
        for (JsonElement e : tasks()) {
            if (e.isJsonObject() && optInt(e.getAsJsonObject(), "id") == id) return e.getAsJsonObject();
        }
        return null;
    }

    private static JsonObject materialById(int id) {
        for (JsonElement e : materials()) {
            if (e.isJsonObject() && optInt(e.getAsJsonObject(), "id") == id) return e.getAsJsonObject();
        }
        return null;
    }

    private static JsonObject projectOf(JsonObject a) { return projectById(optInt(a, "project")); }
    private static JsonObject taskOf(JsonObject a) { return taskById(optInt(a, "task")); }
    private static JsonObject materialOf(JsonObject a) { return materialById(optInt(a, "material")); }

    private static int optInt(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
    }

    private static String optStr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long now() { return System.currentTimeMillis() / 1000L; }

    private static boolean editable(JsonObject pr) {
        String st = optStr(pr, "status");
        return "planning".equals(st) || "active".equals(st);
    }

    private static String playerName() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().name() : "player";
    }

    private static String playerUuid() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getGameProfile().id().toString() : "";
    }

    private static String playerDim() {
        return Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.level().dimension().identifier().toString() : "minecraft:overworld";
    }

    /** 计数单个物品堆：潜影盒（CONTAINER 组件）/收纳袋（BUNDLE_CONTENTS 组件）递归展开 */
    private static void countStack(ItemStack s, Map<String, Long> out, int depth) {
        if (s == null || s.isEmpty() || depth > 5) return;
        out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), (long) s.getCount(), Long::sum);
        try {
            ItemContainerContents cont = s.get(DataComponents.CONTAINER);
            if (cont != null) {
                for (ItemStack in : cont.nonEmptyItems()) countStack(in, out, depth + 1);
            }
        } catch (Exception ignored) { }
        try {
            BundleContents bundle = s.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack in : bundle.items()) countStack(in, out, depth + 1);
            }
        } catch (Exception ignored) { }
    }

    /** 老版 Gson JsonArray 无 removeIf：迭代器删除 */
    private static void removeFrom(JsonArray arr, java.util.function.Predicate<JsonElement> pred) {
        java.util.Iterator<JsonElement> it = arr.iterator();
        while (it.hasNext()) {
            if (pred.test(it.next())) it.remove();
        }
    }

    private static JsonObject ok() { return ack(true, null, null); }
    private static JsonObject ok(String message) { return ack(true, null, message); }
    private static JsonObject fail(String error) { return ack(false, error, null); }
    private static JsonObject ack(boolean okFlag, String error, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", okFlag);
        if (error != null) o.addProperty("error", error);
        if (message != null) o.addProperty("message", message);
        return o;
    }

    // ───────────────────────── 操作分发 ─────────────────────────

    public static JsonObject handle(String op, JsonObject a) {
        try {
            switch (op) {
                case "list_imports": return ok("[]");
                case "request_sync": return ok();
                case "import_schematic": return fail("单人档没有共享投影库，请用「本地投影」导入");
                case "create_project": return createProject(a);
                case "edit_project": return editProject(a);
                case "delete_project": return deleteProject(a);
                case "project_done": return setStatus(a, "completed", true);
                case "project_reopen": return setStatus(a, "active", false);
                case "archive": return setStatus(a, "archived", false);
                case "unarchive": return setStatus(a, "active", false);
                case "set_managers": return setManagers(a);
                case "participants_add": return participants(a, true);
                case "participants_remove": return participants(a, false);
                case "set_location_manual": return setLocation(a);
                case "set_loc_hidden": return toggleLocHidden(a);
                case "set_loc_lock": return setLocLock(a);
                case "clear_location": return clearLocation(a);
                case "set_deposit_a": return setDepositCorner(a, 1);
                case "set_deposit_b": return setDepositCorner(a, 2);
                case "clear_deposit": return clearDeposit(a);
                case "set_dep_lock": return setDepLock(a);
                case "check_deposit": return checkDeposit(a);
                case "create_task": return createTask(a);
                case "create_collect_task": return createCollectTask(a);
                case "claim": return claim(a);
                case "unclaim": return unclaim(a);
                case "task_done": return taskDone(a);
                case "task_reopen": return taskReopen(a);
                case "delete_task": return deleteTask(a);
                case "task_set_note": return taskSetNote(a);
                case "task_rename": return taskRename(a);
                case "material_add": return materialAdd(a);
                case "material_remove": return materialRemove(a);
                case "material_set_need": return materialSetNeed(a);
                case "schematic_delete": return schematicDelete(a);
                case "import_materials": return importMaterials(a);
                default: return fail("单人模式暂不支持操作 " + op);
            }
        } catch (Exception e) {
            return fail("本地处理异常: " + e.getClass().getSimpleName());
        }
    }

    // ───────────────────────── 工程 ─────────────────────────

    private static JsonObject createProject(JsonObject a) {
        String title = optStr(a, "title").trim();
        if (title.isEmpty()) return fail("工程标题不能为空");
        if (title.length() > 30) return fail("标题最长 30 字");
        JsonObject pr = new JsonObject();
        pr.addProperty("id", nextId(projects()));
        pr.addProperty("title", title);
        pr.addProperty("desc", "");
        pr.addProperty("status", "planning");
        pr.addProperty("creator", playerName());
        pr.addProperty("creatorUuid", playerUuid());
        JsonArray mgrs = new JsonArray(); mgrs.add(playerName());
        pr.add("managers", mgrs);
        JsonArray mgrUuids = new JsonArray(); mgrUuids.add(playerUuid());
        pr.add("managerUuids", mgrUuids);
        pr.addProperty("createdAt", now());
        pr.addProperty("completedAt", 0);
        pr.add("participants", new JsonArray());
        pr.add("tags", new JsonArray());
        pr.addProperty("buildNote", "");
        pr.add("schematics", new JsonArray());
        projects().add(pr);
        refresh();
        return ok();
    }

    private static JsonObject editProject(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        String field = optStr(a, "field");
        String value = optStr(a, "value");
        switch (field) {
            case "title":
                if (value.trim().isEmpty()) return fail("标题不能为空");
                pr.addProperty("title", value.trim());
                break;
            case "desc": pr.addProperty("desc", value); break;
            case "note": pr.addProperty("buildNote", value); break;
            case "status":
                if ("planning".equals(value) || "active".equals(value)) pr.addProperty("status", value);
                else return fail("无效状态");
                break;
            default: return fail("未知字段 " + field);
        }
        refresh();
        return ok();
    }

    private static JsonObject deleteProject(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        int id = optInt(pr, "id");
        projects().remove(pr);
        removeFrom(tasks(), e -> e.isJsonObject() && optInt(e.getAsJsonObject(), "projectId") == id);
        removeFrom(materials(), e -> e.isJsonObject() && optInt(e.getAsJsonObject(), "projectId") == id);
        refresh();
        return ok();
    }

    private static JsonObject setStatus(JsonObject a, String status, boolean stampDone) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        pr.addProperty("status", status);
        pr.addProperty("completedAt", stampDone ? now() : 0);
        refresh();
        return ok();
    }

    private static JsonObject setManagers(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        String name = optStr(a, "name").trim();
        if (name.isEmpty()) return fail("玩家名不能为空");
        boolean add = !a.has("add") || a.get("add").getAsBoolean();
        JsonArray mgrs = pr.has("managers") ? pr.getAsJsonArray("managers") : new JsonArray();
        if (add) {
            for (JsonElement e : mgrs) if (e.getAsString().equalsIgnoreCase(name)) return fail(name + " 已是管理者");
            mgrs.add(name);
        } else {
            if (name.equalsIgnoreCase(optStr(pr, "creator"))) return fail("创建者默认是管理者，不可移除");
            removeFrom(mgrs, e -> e.getAsString().equalsIgnoreCase(name));
        }
        pr.add("managers", mgrs);
        refresh();
        return ok();
    }

    private static JsonObject participants(JsonObject a, boolean add) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        String name = optStr(a, "name").trim();
        if (name.isEmpty()) return fail("玩家名不能为空");
        JsonArray parts = pr.has("participants") ? pr.getAsJsonArray("participants") : new JsonArray();
        if (add) {
            for (JsonElement e : parts) if (e.getAsString().equalsIgnoreCase(name)) return fail(name + " 已在参与玩家中");
            parts.add(name);
        } else {
            boolean[] removed = {false};
            removeFrom(parts, e -> {
                if (e.getAsString().equalsIgnoreCase(name)) { removed[0] = true; return true; }
                return false;
            });
            if (!removed[0]) return fail("找不到参与玩家 " + name);
        }
        pr.add("participants", parts);
        refresh();
        return ok();
    }

    // ───────────────────────── 选址 / 收集区域 ─────────────────────────

    private static JsonObject setLocation(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        JsonObject loc = new JsonObject();
        loc.addProperty("world", playerDim());
        loc.addProperty("x", optInt(a, "x"));
        loc.addProperty("y", optInt(a, "y"));
        loc.addProperty("z", optInt(a, "z"));
        loc.addProperty("note", optStr(a, "note"));
        loc.addProperty("hidden", pr.has("location") && optBool(pr.getAsJsonObject("location"), "hidden"));
        loc.addProperty("locked", pr.has("location") && optBool(pr.getAsJsonObject("location"), "locked"));
        pr.add("location", loc);
        refresh();
        return ok();
    }

    private static boolean optBool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static JsonObject toggleLocHidden(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        JsonObject loc = pr.has("location") ? pr.getAsJsonObject("location") : new JsonObject();
        loc.addProperty("hidden", !optBool(loc, "hidden"));
        pr.add("location", loc);
        refresh();
        return ok();
    }

    private static JsonObject setLocLock(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        JsonObject loc = pr.has("location") ? pr.getAsJsonObject("location") : new JsonObject();
        loc.addProperty("locked", !a.has("locked") || a.get("locked").getAsBoolean());
        pr.add("location", loc);
        refresh();
        return ok();
    }

    private static JsonObject clearLocation(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        pr.remove("location");
        refresh();
        return ok();
    }

    private static JsonObject setDepositCorner(JsonObject a, int corner) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        JsonObject dep = pr.has("depositArea") ? pr.getAsJsonObject("depositArea") : new JsonObject();
        if (dep.has("locked") && optBool(dep, "locked")) return fail("收集区域已锁定，请先解锁再改角点");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return fail("找不到玩家位置");
        int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
        String dim = playerDim();
        boolean worldChanged = dep.has("world") && !optStr(dep, "world").isEmpty() && !optStr(dep, "world").equals(dim);
        if (worldChanged && corner == 2)
            return fail("收集区域必须在同一世界（角点 A 在 " + optStr(dep, "world") + "）");
        dep.addProperty("world", dim);
        if (corner == 1) {
            dep.addProperty("x1", x); dep.addProperty("y1", y); dep.addProperty("z1", z);
            if (worldChanged) { dep.addProperty("x2", x); dep.addProperty("y2", y); dep.addProperty("z2", z); }
        }
        else { dep.addProperty("x2", x); dep.addProperty("y2", y); dep.addProperty("z2", z); }
        dep.addProperty("locked", optBool(dep, "locked"));
        pr.add("depositArea", dep);
        refresh();
        if (corner == 1 && worldChanged) return ok("区域已切换到当前世界，角点 B 需要重新设置");
        return ok(corner == 1 ? "角点 A 已设为脚下" : "角点 B 已设为脚下");
    }

    private static JsonObject clearDeposit(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        pr.remove("depositArea");
        refresh();
        return ok();
    }

    private static JsonObject setDepLock(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        JsonObject dep = pr.has("depositArea") ? pr.getAsJsonObject("depositArea") : new JsonObject();
        dep.addProperty("locked", !a.has("locked") || a.get("locked").getAsBoolean());
        pr.add("depositArea", dep);
        refresh();
        return ok();
    }

    /** 核验：在内置服务器线程扫描区域容器，回主线程更新进度并自动判定收集任务 */
    private static JsonObject checkDeposit(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        if (!pr.has("depositArea")) return fail("还没有设置收集区域（先设两个角点）");
        JsonObject dep = pr.getAsJsonObject("depositArea");
        String dim = optStr(dep, "world");
        if (dim.isEmpty()) return fail("还没有设置收集区域（先设两个角点）");
        int minX = Math.min(optInt(dep, "x1"), optInt(dep, "x2")), maxX = Math.max(optInt(dep, "x1"), optInt(dep, "x2"));
        int minY = Math.min(optInt(dep, "y1"), optInt(dep, "y2")), maxY = Math.max(optInt(dep, "y1"), optInt(dep, "y2"));
        int minZ = Math.min(optInt(dep, "z1"), optInt(dep, "z2")), maxZ = Math.max(optInt(dep, "z1"), optInt(dep, "z2"));
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0) return fail("区域无效（两个角点不能相同面）");
        if (volume > 4096) return fail("区域太大（" + volume + " 方块，上限 4096）");
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return fail("单人服务器不可用");
        ServerLevel level;
        try {
            level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
        } catch (Exception e) { return fail("区域世界解析失败"); }
        if (level == null) return fail("区域所在世界不存在: " + dim);
        final int pid = optInt(pr, "id");
        final int fx1 = minX, fx2 = maxX, fy1 = minY, fy2 = maxY, fz1 = minZ, fz2 = maxZ;
        server.execute(() -> {
            Map<String, Long> foundByItem = new HashMap<>();
            try {
                for (int x = fx1; x <= fx2; x++)
                    for (int y = fy1; y <= fy2; y++)
                        for (int z = fz1; z <= fz2; z++) {
                            BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                            if (be instanceof Container cont) {
                                for (int i = 0; i < cont.getContainerSize(); i++) {
                                    countStack(cont.getItem(i), foundByItem, 0);
                                }
                            }
                        }
            } catch (Exception ignored) { }
            Minecraft.getInstance().execute(() -> {
                if (!active) return;
                JsonObject prj = projectById(pid);
                if (prj == null) return;
                // 按行分配：同物品多行按顺序填充，末行承接全部剩余（含超量）——不翻倍且保留 64/30 式超量可见
                Map<String, java.util.List<JsonObject>> rowsByItem = new java.util.LinkedHashMap<>();
                for (JsonElement e : materials()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject m = e.getAsJsonObject();
                    if (optInt(m, "projectId") != pid) continue;
                    rowsByItem.computeIfAbsent(optStr(m, "item"), k -> new java.util.ArrayList<>()).add(m);
                }
                int updated = 0;
                long total = foundByItem.values().stream().mapToLong(Long::longValue).sum();
                for (Map.Entry<String, java.util.List<JsonObject>> e : rowsByItem.entrySet()) {
                    long avail = foundByItem.getOrDefault(e.getKey(), 0L);
                    java.util.List<JsonObject> list = e.getValue();
                    for (int i = 0; i < list.size(); i++) {
                        JsonObject m = list.get(i);
                        long give;
                        if (i == list.size() - 1) give = avail; // 末行承接剩余全部（含超量）
                        else {
                            long need = m.get("need").getAsLong();
                            give = Math.min(avail, Math.max(need, 0L));
                            avail -= give;
                        }
                        if (give != m.get("delivered").getAsLong()) { m.addProperty("delivered", give); updated++; }
                    }
                }
                // 收集任务自动判定
                java.util.List<String> autoDone = new java.util.ArrayList<>();
                for (JsonElement e : tasks()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject t = e.getAsJsonObject();
                    if (optInt(t, "projectId") != pid) continue;
                    if (!"collect".equals(optStr(t, "type")) || "done".equals(optStr(t, "status"))) continue;
                    if (!t.has("materialIds") || !t.get("materialIds").isJsonArray() || t.getAsJsonArray("materialIds").isEmpty()) continue;
                    boolean any = false, allOk = true;
                    for (JsonElement midE : t.getAsJsonArray("materialIds")) {
                        JsonObject m = materialById(midE.getAsInt());
                        if (m == null || optInt(m, "projectId") != pid) continue;
                        any = true;
                        if (m.get("delivered").getAsLong() < m.get("need").getAsLong()) { allOk = false; break; }
                    }
                    if (any && allOk) {
                        t.addProperty("status", "done");
                        t.addProperty("doneAt", now());
                        autoDone.add(optStr(t, "title"));
                    }
                }
                StringBuilder msg = new StringBuilder("核验完成：区域物品 " + total + " 件，更新 " + updated + " 行进度");
                if (!autoDone.isEmpty()) msg.append("；收集任务自动完成: ").append(String.join("、", autoDone));
                refresh();
                MemoToast.push(msg.toString(), MemoToast.GREEN);
            });
        });
        return ok("正在核验区域容器…");
    }

    // ───────────────────────── 子任务 ─────────────────────────

    private static JsonObject createTask(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        if (!editable(pr)) return fail("工程已竣工/归档，不能再添加");
        String title = optStr(a, "title").trim();
        if (title.isEmpty()) return fail("子任务标题不能为空");
        if (title.length() > 40) return fail("标题最长 40 字");
        JsonObject t = baseTask(optInt(pr, "id"));
        t.addProperty("title", title);
        tasks().add(t);
        refresh();
        return ok();
    }

    private static JsonObject baseTask(int projectId) {
        JsonObject t = new JsonObject();
        t.addProperty("id", nextId(tasks()));
        t.addProperty("projectId", projectId);
        t.addProperty("status", "open");
        t.addProperty("assignee", "");
        t.addProperty("assigneeUuid", "");
        t.addProperty("claimedAt", 0);
        t.addProperty("doneAt", 0);
        t.addProperty("note", "");
        int maxSort = 0;
        for (JsonElement e : tasks()) {
            if (e.isJsonObject() && optInt(e.getAsJsonObject(), "projectId") == projectId)
                maxSort = Math.max(maxSort, optInt(e.getAsJsonObject(), "sort"));
        }
        t.addProperty("sort", maxSort + 1);
        t.addProperty("type", "custom");
        t.add("materialIds", new JsonArray());
        return t;
    }

    private static JsonObject createCollectTask(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        if (!editable(pr)) return fail("工程已竣工/归档，不能再添加");
        String title = optStr(a, "title").trim();
        if (title.isEmpty()) return fail("请给收集任务取个名字");
        if (title.length() > 40) return fail("任务名最长 40 字");
        if (!a.has("materials") || !a.get("materials").isJsonArray() || a.getAsJsonArray("materials").isEmpty())
            return fail("请至少勾选一项材料");
        JsonArray mats = a.getAsJsonArray("materials");
        if (mats.size() > 50) return fail("单个收集任务最多 50 项材料");
        int pid = optInt(pr, "id");
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (JsonElement e : mats) {
            int mid = e.getAsInt();
            if (!seen.add(mid)) continue;
            JsonObject m = materialById(mid);
            if (m == null || optInt(m, "projectId") != pid) return fail("材料行 #" + mid + " 不存在");
            if (m.get("need").getAsLong() > 0 && m.get("delivered").getAsLong() >= m.get("need").getAsLong())
                return fail("「" + itemNameOf(m) + "」已收集完成，无需再认领");
            JsonObject covering = coveringCollectTask(pid, mid);
            if (covering != null) return fail("「" + itemNameOf(m) + "」已有收集任务，认领人: " + optStr(covering, "assignee"));
        }
        JsonObject t = baseTask(pid);
        t.addProperty("title", title);
        t.addProperty("type", "collect");
        JsonArray ids = new JsonArray();
        seen.forEach(ids::add);
        t.add("materialIds", ids);
        t.addProperty("status", "claimed");
        t.addProperty("assignee", playerName());
        t.addProperty("assigneeUuid", playerUuid());
        t.addProperty("claimedAt", now());
        tasks().add(t);
        refresh();
        return ok("已创建并认领收集任务「" + title + "」（" + seen.size() + " 项材料）");
    }

    private static JsonObject coveringCollectTask(int projectId, int materialId) {
        for (JsonElement e : tasks()) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            if (optInt(t, "projectId") != projectId) continue;
            if (!"collect".equals(optStr(t, "type")) || "done".equals(optStr(t, "status"))) continue;
            if (t.has("materialIds") && t.get("materialIds").isJsonArray()) {
                for (JsonElement midE : t.getAsJsonArray("materialIds")) {
                    if (midE.getAsInt() == materialId) return t;
                }
            }
        }
        return null;
    }

    private static String itemNameOf(JsonObject m) {
        String n = optStr(m, "itemName");
        return n.isEmpty() ? optStr(m, "item") : n;
    }

    private static JsonObject claim(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        if (!"open".equals(optStr(t, "status"))) return fail("该任务已被认领");
        t.addProperty("status", "claimed");
        t.addProperty("assignee", playerName());
        t.addProperty("assigneeUuid", playerUuid());
        t.addProperty("claimedAt", now());
        refresh();
        return ok();
    }

    private static JsonObject unclaim(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        if (!"claimed".equals(optStr(t, "status"))) return fail("该任务未被认领");
        t.addProperty("status", "open");
        t.addProperty("assignee", "");
        t.addProperty("assigneeUuid", "");
        t.addProperty("claimedAt", 0);
        refresh();
        return ok();
    }

    private static JsonObject taskDone(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        if ("done".equals(optStr(t, "status"))) return fail("该任务已完成");
        t.addProperty("status", "done");
        t.addProperty("doneAt", now());
        refresh();
        return ok();
    }

    private static JsonObject taskReopen(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        if (!"done".equals(optStr(t, "status"))) return fail("该任务未完成");
        t.addProperty("status", optStr(t, "assignee").isEmpty() ? "open" : "claimed");
        t.addProperty("doneAt", 0);
        refresh();
        return ok();
    }

    private static JsonObject deleteTask(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        tasks().remove(t);
        refresh();
        return ok();
    }

    private static JsonObject taskSetNote(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        String note = optStr(a, "note").trim();
        if (note.length() > 200) return fail("说明最长 200 字");
        t.addProperty("note", note);
        refresh();
        return ok();
    }

    private static JsonObject taskRename(JsonObject a) {
        JsonObject t = taskOf(a);
        if (t == null) return fail("找不到子任务");
        String title = optStr(a, "title").trim();
        if (title.isEmpty()) return fail("标题不能为空");
        if (title.length() > 40) return fail("标题最长 40 字");
        t.addProperty("title", title);
        refresh();
        return ok();
    }

    // ───────────────────────── 材料 ─────────────────────────

    private static JsonObject materialAdd(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        if (!editable(pr)) return fail("工程已竣工/归档");
        String item = optStr(a, "item");
        long need = a.has("need") ? a.get("need").getAsLong() : 0;
        if (item.isEmpty()) return fail("无效物品");
        if (need <= 0 || need > 10_000_000) return fail("需求数量需在 1~1000 万");
        int pid = optInt(pr, "id");
        for (JsonElement e : materials()) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            if (optInt(m, "projectId") == pid && item.equals(optStr(m, "item")) && "custom".equals(optStr(m, "source"))) {
                m.addProperty("need", m.get("need").getAsLong() + need);
                refresh();
                return ok("已合并到已有的同名材料行");
            }
        }
        JsonObject m = new JsonObject();
        m.addProperty("id", nextId(materials()));
        m.addProperty("projectId", pid);
        m.addProperty("item", item);
        m.addProperty("itemName", item.replaceFirst("^minecraft:", "").replace('_', ' '));
        m.addProperty("source", "custom");
        m.addProperty("need", need);
        m.addProperty("delivered", 0);
        m.add("claimedBy", new JsonArray());
        m.add("contributions", new JsonArray());
        m.addProperty("locked", false);
        materials().add(m);
        refresh();
        return ok();
    }

    private static JsonObject materialRemove(JsonObject a) {
        JsonObject m = materialOf(a);
        if (m == null) return fail("找不到材料行");
        materials().remove(m);
        refresh();
        return ok();
    }

    private static JsonObject materialSetNeed(JsonObject a) {
        JsonObject m = materialOf(a);
        if (m == null) return fail("找不到材料行");
        long need = a.has("need") ? a.get("need").getAsLong() : 0;
        if (need <= 0 || need > 10_000_000) return fail("需求数量需在 1~1000 万");
        m.addProperty("need", need);
        refresh();
        return ok();
    }

    // ───────────────────────── 投影记录 / 导入 ─────────────────────────

    private static JsonObject schematicDelete(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        int idx = optInt(a, "idx");
        if (!pr.has("schematics") || !pr.get("schematics").isJsonArray()) return fail("投影记录不存在");
        JsonArray sch = pr.getAsJsonArray("schematics");
        if (idx < 0 || idx >= sch.size()) return fail("投影记录不存在");
        JsonObject rec = sch.get(idx).getAsJsonObject();
        String name = optStr(rec, "name");
        sch.remove(idx);
        int pid = optInt(pr, "id");
        int removed = 0;
        java.util.Iterator<JsonElement> it = materials().iterator();
        while (it.hasNext()) {
            JsonElement e = it.next();
            if (e.isJsonObject() && optInt(e.getAsJsonObject(), "projectId") == pid
                    && name.equals(optStr(e.getAsJsonObject(), "source"))) {
                it.remove();
                removed++;
            }
        }
        refresh();
        return ok("已删除投影「" + name + "」及其 " + removed + " 行材料");
    }

    /** 本地投影导入（客户端已解析好 items）：同物品+同来源合并，记录投影条目 */
    private static JsonObject importMaterials(JsonObject a) {
        JsonObject pr = projectOf(a);
        if (pr == null) return fail("找不到工程");
        if (!editable(pr)) return fail("工程已竣工/归档，需先解档");
        if (!a.has("items") || !a.get("items").isJsonArray() || a.getAsJsonArray("items").isEmpty())
            return fail("没有可导入的材料");
        String name = optStr(a, "name");
        String src = name.isEmpty() ? "custom" : name;
        int pid = optInt(pr, "id");
        int added = 0, merged = 0;
        long totalBlocks = 0;
        for (JsonElement e : a.getAsJsonArray("items")) {
            if (!e.isJsonObject()) continue;
            JsonObject it = e.getAsJsonObject();
            String item = optStr(it, "item");
            long need = it.has("need") ? it.get("need").getAsLong() : 0;
            if (item.isEmpty() || need <= 0) continue;
            totalBlocks += need;
            JsonObject row = null;
            for (JsonElement re : materials()) {
                if (re.isJsonObject() && optInt(re.getAsJsonObject(), "projectId") == pid
                        && item.equals(optStr(re.getAsJsonObject(), "item")) && src.equals(optStr(re.getAsJsonObject(), "source"))) {
                    row = re.getAsJsonObject();
                    break;
                }
            }
            if (row != null) {
                row.addProperty("need", row.get("need").getAsLong() + need);
                merged++;
            } else {
                JsonObject m = new JsonObject();
                m.addProperty("id", nextId(materials()));
                m.addProperty("projectId", pid);
                m.addProperty("item", item);
                m.addProperty("itemName", item.replaceFirst("^minecraft:", "").replace('_', ' '));
                m.addProperty("source", src);
                m.addProperty("need", need);
                m.addProperty("delivered", 0);
                m.add("claimedBy", new JsonArray());
                m.add("contributions", new JsonArray());
                m.addProperty("locked", false);
                materials().add(m);
                added++;
            }
        }
        JsonObject rec = new JsonObject();
        rec.addProperty("name", name.isEmpty() ? "本地投影" : name);
        rec.addProperty("source", "local");
        rec.addProperty("file", name);
        rec.addProperty("by", playerName());
        rec.addProperty("at", now());
        rec.addProperty("blocks", totalBlocks);
        rec.addProperty("kinds", a.getAsJsonArray("items").size());
        boolean hasOrigin = a.has("hasOrigin") && a.get("hasOrigin").getAsBoolean();
        rec.addProperty("hasOrigin", hasOrigin);
        if (hasOrigin) {
            rec.addProperty("ox", optInt(a, "ox"));
            rec.addProperty("oy", optInt(a, "oy"));
            rec.addProperty("oz", optInt(a, "oz"));
        }
        if (!pr.has("schematics") || !pr.get("schematics").isJsonArray()) pr.add("schematics", new JsonArray());
        pr.getAsJsonArray("schematics").add(rec);
        refresh();
        StringBuilder sb = new StringBuilder("导入完成：新增 " + added + " 行");
        if (merged > 0) sb.append("，合并 ").append(merged).append(" 行");
        return ok(sb.toString());
    }
}
