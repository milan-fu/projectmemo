package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.MaterialRow;
import com.sthstrange.projectmemo.MemoData.Project;
import com.sthstrange.projectmemo.MemoData.Task;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M1 payload 通道 projectmemo:main（协议已定稿，见交接文档 T1）。
 * C2S: hello / action；S2C: init / ack / sync / open_gui。
 * 所有写操作经 MemoActions（与聊天/命令同一条路径，服务端二次校验，客户端不可信）；
 * dispatch 强制主线程；异常全部捕获回 ack.ok=false，不抛给客户端。
 */
public final class MemoChannel implements PluginMessageListener, Listener {

    public static final String CHANNEL = "projectmemo:main";
    public static final int PROTOCOL_VERSION = 1;
    private static final int MAX_PAYLOAD = 1024 * 1024;

    private final ProjectMemoPlugin plugin;
    private final Set<UUID> memoClients = ConcurrentHashMap.newKeySet();

    public MemoChannel(ProjectMemoPlugin plugin) { this.plugin = plugin; }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** 玩家是否已注册为模组客户端（hello 过且未退出） */
    public boolean isClient(Player p) { return memoClients.contains(p.getUniqueId()); }

    public int clientCount() { return memoClients.size(); }

    // ───────────────────────── 入站（C2S） ─────────────────────────

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        if (message == null || message.length == 0 || message.length > MAX_PAYLOAD) return;
        final JSONObject json;
        try {
            json = new JSONObject(new String(message, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("payload JSON 解析失败（来自 " + player.getName() + "）: " + e.getMessage());
            return;
        }
        String t = json.optString("t");
        if ("hello".equals(t)) {
            Bukkit.getScheduler().runTask(plugin, () -> handleHello(player, json));
        } else if ("action".equals(t)) {
            Bukkit.getScheduler().runTask(plugin, () -> handleAction(player, json));
        }
        // 其余类型忽略
    }

    private void handleHello(Player player, JSONObject json) {
        if (!player.isOnline()) return;
        memoClients.add(player.getUniqueId());
        plugin.getLogger().info("模组客户端注册: " + player.getName()
                + " (v" + json.optInt("v", -1) + ", 在线客户端 " + memoClients.size() + ")");
        sendInit(player);
    }

    private void handleAction(Player player, JSONObject json) {
        if (!player.isOnline()) return;
        int nonce = json.optInt("nonce", -1);
        String op = json.optString("op");
        JSONObject args = json.optJSONObject("args");
        if (args == null) args = new JSONObject();
        if (plugin.isMirror() && !"request_sync".equals(op)) {
            sendAck(player, nonce, MemoActions.Result.fail("只读镜像服：备忘录编辑请回主服"));
            return;
        }
        MemoActions.Result r;
        try {
            r = dispatch(player, op, args);
        } catch (Exception e) {
            plugin.getLogger().warning("action " + op + " 异常（" + player.getName() + "）: " + e);
            r = MemoActions.Result.fail("内部错误: " + e.getClass().getSimpleName());
        }
        if (r == null) r = MemoActions.Result.fail("未知操作 " + op);
        if (!r.ok) {
            plugin.getLogger().info("[action失败] " + player.getName() + " " + op + " " + args + " -> " + r.error);
        }
        sendAck(player, nonce, r);
        if (r.ok && !"list_imports".equals(op) && !"request_sync".equals(op)) {
            broadcastSync();
            plugin.onDataChanged(); // wiki 导出防抖（T4）
        }
    }

    /** op → MemoActions 映射；返回 null = 未知 op */
    private MemoActions.Result dispatch(Player p, String op, JSONObject a) {
        MemoActions act = plugin.getActions();
        MemoData d = plugin.getStore().data();
        switch (op) {
            case "create_project":
                return act.createProject(p, a.optString("title"));
            case "create_task": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.createTask(p, pr, a.optString("title"));
            }
            case "task_rename": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到子任务")
                        : act.taskRename(p, t, a.optString("title"));
            }
            case "request_sync": {
                // 客户端数据可能过期（提示"找不到"时自动请求）：重发全量快照
                sendInit(p);
                return MemoActions.Result.ok();
            }
            case "create_collect_task": {
                Project pr = project(d, a);
                if (pr == null) return MemoActions.Result.fail("找不到工程");
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                org.json.JSONArray arr = a.optJSONArray("materials");
                if (arr != null) for (Object x : arr) ids.add(((Number) x).intValue());
                return act.createCollectTask(p, pr, a.optString("title"), ids);
            }
            case "edit_project": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.editProject(p, pr, a.optString("field"), a.optString("value"));
            }
            case "set_location": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.setLocation(p, pr);
            }
            case "set_loc_hidden": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.setLocationHidden(p, pr, !pr.locHidden); // toggle
            }
            case "set_loc_lock": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.setLocationLocked(p, pr, a.optBoolean("locked", !pr.locLocked));
            }
            case "set_location_manual": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.setLocationManual(p, pr, a.optInt("x"), a.optInt("y"), a.optInt("z"), a.optString("note"));
            }
            case "set_dep_lock": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.setDepositLocked(p, pr, a.optBoolean("locked", !pr.depLocked));
            }
            case "set_deposit_a": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.setDepositCorner(p, pr, 1);
            }
            case "set_deposit_b": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.setDepositCorner(p, pr, 2);
            }
            case "clear_deposit": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.clearDeposit(p, pr);
            }
            case "check_deposit": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.checkDeposit(p, pr);
            }
            case "clear_location": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.clearLocation(p, pr);
            }
            case "set_managers": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.setManagers(p, pr, a.optBoolean("add", true), a.optString("name"));
            }
            case "claim": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.claim(p, t);
            }
            case "unclaim": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.unclaim(p, t);
            }
            case "task_done": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.taskDone(p, t);
            }
            case "task_reopen": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.taskReopen(p, t);
            }
            case "delete_task": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.deleteTask(p, t);
            }
            case "task_set_note": {
                Task t = task(d, a);
                return t == null ? MemoActions.Result.fail("找不到任务") : act.taskSetNote(p, t, a.optString("note"));
            }
            case "deliver": {
                MaterialRow m = material(d, a);
                if (m == null) return MemoActions.Result.fail("找不到材料行");
                Long amount = amount(a, "amount");
                if (amount == null) return MemoActions.Result.fail("数量格式错误（支持 64、3*64、2*1728+64）");
                return act.deliver(p, m, amount);
            }
            case "material_add": {
                Project pr = project(d, a);
                if (pr == null) return MemoActions.Result.fail("找不到工程");
                Long need = amount(a, "need");
                if (need == null) return MemoActions.Result.fail("数量格式错误（支持 64、3*64、2*1728+64）");
                return act.materialAdd(p, pr, a.optString("item"), need);
            }
            case "material_add_hand": {
                Project pr = project(d, a);
                if (pr == null) return MemoActions.Result.fail("找不到工程");
                Long need = amount(a, "need");
                if (need == null) return MemoActions.Result.fail("数量格式错误（支持 64、3*64、2*1728+64）");
                return act.materialAddFromHand(p, pr, need);
            }
            case "material_set_need": {
                MaterialRow m = material(d, a);
                if (m == null) return MemoActions.Result.fail("找不到材料行");
                Long need = amount(a, "need");
                if (need == null) return MemoActions.Result.fail("数量格式错误（支持 64、3*64、2*1728+64）");
                return act.materialSetNeed(p, m, need);
            }
            case "material_remove": {
                MaterialRow m = material(d, a);
                return m == null ? MemoActions.Result.fail("找不到材料行") : act.materialRemove(p, m);
            }
            case "import_schematic": {
                Project pr = project(d, a);
                if (pr == null) return MemoActions.Result.fail("找不到工程");
                String name = new File(a.optString("file")).getName(); // 白名单化，防路径穿越
                if (name.isEmpty() || !name.toLowerCase().endsWith(".litematic") || name.startsWith("._"))
                    return MemoActions.Result.fail("只能导入共享投影库内的 .litematic 文件");
                File f = new File(plugin.getSyncmaticsDir(), name);
                if (!f.exists()) return MemoActions.Result.fail("共享投影库里没有 " + name);
                return act.importMaterials(p, pr, f, a.optString("name"));
            }
            case "schematic_delete": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.schematicDelete(p, pr, a.optInt("idx", -1));
            }
            case "participants_add": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.participantsAdd(p, pr, a.optString("name"));
            }
            case "participants_remove": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程")
                        : act.participantsRemove(p, pr, a.optString("name"));
            }

            case "import_materials": {
                Project pr = project(d, a);
                if (pr == null) return MemoActions.Result.fail("找不到工程");
                org.json.JSONArray items = a.optJSONArray("items");
                JSONObject origin = a.optJSONObject("origin");
                boolean hasOrigin = origin != null;
                int ox = hasOrigin ? origin.optInt("x") : 0;
                int oy = hasOrigin ? origin.optInt("y") : 0;
                int oz = hasOrigin ? origin.optInt("z") : 0;
                return act.importMaterialsBulk(p, pr, items, a.optString("name"), hasOrigin, ox, oy, oz);
            }
            case "list_imports": {
                // 只读：ack.message 带共享投影库（syncmatics/）列表 JSON [{file,name,owner}]
                // 人类可读名来自 syncmatics/placements.json（file_name/owner 按 hash 合并）
                File dir = plugin.getSyncmaticsDir();
                java.util.Map<String, String> nameMap = new java.util.HashMap<>();
                java.util.Map<String, String> ownerMap = new java.util.HashMap<>();
                File pm = new File(dir, "placements.json");
                if (pm.exists()) {
                    try {
                        JSONObject root = new JSONObject(new String(
                                java.nio.file.Files.readAllBytes(pm.toPath()), StandardCharsets.UTF_8));
                        JSONArray pls = root.optJSONArray("placements");
                        if (pls != null) for (Object o : pls) {
                            if (!(o instanceof JSONObject)) continue;
                            JSONObject pl = (JSONObject) o;
                            String hash = pl.optString("hash");
                            String nm = pl.optString("file_name");
                            JSONObject owner = pl.optJSONObject("owner");
                            String on = owner == null ? "" : owner.optString("name");
                            if (hash.isEmpty()) continue;
                            String old = nameMap.get(hash);
                            if (old == null) nameMap.put(hash, nm);
                            else if (!nm.isEmpty() && !old.contains(nm)) nameMap.put(hash, old + "、" + nm);
                            if (!on.isEmpty()) ownerMap.putIfAbsent(hash, on);
                        }
                    } catch (Exception ignored) { }
                }
                JSONArray arr = new JSONArray();
                File[] files = dir.listFiles((d2, n) -> n.toLowerCase().endsWith(".litematic"));
                if (files != null) {
                    List<File> fl = new ArrayList<>(java.util.Arrays.asList(files));
                    fl.sort(java.util.Comparator.comparing(File::getName));
                    for (File f : fl) {
                        String hash = f.getName().substring(0, f.getName().length() - ".litematic".length());
                        JSONObject item = new JSONObject();
                        item.put("file", f.getName());
                        item.put("name", nameMap.getOrDefault(hash, ""));
                        item.put("owner", ownerMap.getOrDefault(hash, ""));
                        arr.put(item);
                    }
                }
                return MemoActions.Result.ok().withMessage(arr.toString());
            }
            case "project_done": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.projectDone(p, pr);
            }
            case "project_reopen": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.projectReopen(p, pr);
            }
            case "archive": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.archive(p, pr);
            }
            case "unarchive": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.unarchive(p, pr);
            }
            case "delete_project": {
                Project pr = project(d, a);
                return pr == null ? MemoActions.Result.fail("找不到工程") : act.deleteProject(p, pr);
            }
            default:
                return null;
        }
    }

    /** args.project 支持数字 id 或标题（MemoData.projectByRef 同款） */
    private static Project project(MemoData d, JSONObject a) {
        Object ref = a.opt("project");
        if (ref == null) return null;
        String s = String.valueOf(ref).trim();
        return s.isEmpty() ? null : d.projectByRef(s);
    }

    private static Task task(MemoData d, JSONObject a) {
        return d.taskById(a.optInt("task", -1));
    }

    private static MaterialRow material(MemoData d, JSONObject a) {
        return d.materialById(a.optInt("material", -1));
    }

    /** 数量：数字直取；字符串走 ChatInputSyntax（3*64 / 2*1728+64） */
    private static Long amount(JSONObject a, String key) {
        Object v = a.opt(key);
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n > 0 ? n : null;
        }
        if (v instanceof String) return ChatInputSyntax.parseAmount((String) v);
        return null;
    }

    // ───────────────────────── 出站（S2C） ─────────────────────────

    private void send(Player p, JSONObject json) {
        if (p == null || !p.isOnline()) return;
        try {
            p.sendPluginMessage(plugin, CHANNEL, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("payload 发送失败（" + p.getName() + "）: " + e.getMessage());
        }
    }

    /** 本人权限位：{op, canCreate, quotaLeft, managed:[projectId...]}；mirror 全零 + mirror/readOnly 位 */
    private JSONObject permsFor(Player p) {
        JSONObject perms = new JSONObject();
        if (plugin.isMirror()) {
            perms.put("op", false);
            perms.put("canCreate", false);
            perms.put("quotaLeft", 0);
            perms.put("managed", new JSONArray());
            perms.put("mirror", true);
            perms.put("readOnly", true);
            return perms;
        }
        perms.put("op", p.hasPermission("memo.admin") || p.isOp());
        perms.put("canCreate", p.hasPermission("memo.create"));
        perms.put("quotaLeft", plugin.getActions().quotaLeft(p));
        JSONArray managed = new JSONArray();
        String uuid = p.getUniqueId().toString();
        for (Project pr : plugin.getStore().data().projects)
            if (pr.isManagerUuid(uuid)) managed.put(pr.id);
        perms.put("managed", managed);
        return perms;
    }

    public void sendInit(Player p) {
        JSONObject o = new JSONObject();
        o.put("t", "init");
        o.put("perms", permsFor(p));
        o.put("data", plugin.getStore().data().toJson());
        send(p, o);
    }

    /** 每次成功 action 后向所有已注册客户端全量广播（数据量小，不做增量，已定） */
    public void broadcastSync() {
        if (memoClients.isEmpty()) return;
        JSONObject data = plugin.getStore().data().toJson();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!memoClients.contains(p.getUniqueId())) continue;
            JSONObject o = new JSONObject();
            o.put("t", "sync");
            o.put("perms", permsFor(p));
            o.put("data", data);
            send(p, o);
        }
    }

    public void sendOpenGui(Player p) {
        JSONObject o = new JSONObject();
        o.put("t", "open_gui");
        send(p, o);
    }

    private void sendAck(Player p, int nonce, MemoActions.Result r) {
        JSONObject o = new JSONObject();
        o.put("t", "ack");
        o.put("nonce", nonce);
        o.put("ok", r.ok);
        o.put("error", r.ok ? JSONObject.NULL : r.error);
        o.put("message", r.message == null ? JSONObject.NULL : r.message);
        send(p, o);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (memoClients.remove(e.getPlayer().getUniqueId()))
            plugin.getLogger().info("模组客户端注销: " + e.getPlayer().getName()
                    + "（在线客户端 " + memoClients.size() + "）");
    }
}
