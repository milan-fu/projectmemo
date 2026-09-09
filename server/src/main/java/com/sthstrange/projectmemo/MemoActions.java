package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.MaterialRow;
import com.sthstrange.projectmemo.MemoData.Project;
import com.sthstrange.projectmemo.MemoData.Task;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 共享 Action 层：聊天 UI 按钮与（M1 的）客户端模组 payload 全部走这里。
 * 所有写操作=校验（权限+managers+状态机+限额）→ 改数据 → 原子存盘 → 审计。
 * 已拍板规则：材料纯数字登记；managers 仅创建者+OP 可改；限额按服务器时区；
 * 竣工冻结，解档=管理者+OP；创造服镜像（M5）只读不经过本类。
 */
public final class MemoActions {

    public static final class Result {
        public final boolean ok;
        public final String error;
        public String message;   // 成功时的附加信息（如导入摘要）
        public Result(boolean ok, String error) { this.ok = ok; this.error = error; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String msg) { return new Result(false, msg); }
        public Result withMessage(String m) { this.message = m; return this; }
    }

    private final ProjectMemoPlugin plugin;

    public MemoActions(ProjectMemoPlugin plugin) { this.plugin = plugin; }

    private MemoData d() { return plugin.getStore().data(); }

    private void save() {
        plugin.getStore().save();
        plugin.onDataChanged(); // wiki 导出防抖（未启用时为空操作）
    }

    private long now() { return Instant.now().getEpochSecond(); }

    private String today() { return LocalDate.now(ZoneId.systemDefault()).toString(); }

    private boolean isAdmin(Player p) {
        return p.hasPermission("memo.admin") || p.isOp();
    }

    private boolean isManagerOf(Project pr, Player p) {
        return pr.isManagerUuid(p.getUniqueId().toString()) || isAdmin(p);
    }

    /**
     * 名字 -> OfflinePlayer 安全解析（offline-mode + FastLogin 环境，v1.0.1 修复）。
     * 四段链：①在线精确匹配 ②usercache（Paper API，正版/FastLogin 玩家真实 UUID）
     * ③白名单（usercache 30 天过期后仍留 name->UUID 记录；本服 enforce-whitelist）
     * ④传统名字推导 v3（纯离线玩家兜底 = 1.0.0 原行为）。
     * 返回 null = 四条路都无法确认进过服。
     */
    private org.bukkit.OfflinePlayer resolveKnownPlayer(String name) {
        if (name == null || name.isEmpty()) return null;
        // ① 在线玩家：直接命中真实身份
        org.bukkit.OfflinePlayer t = Bukkit.getPlayerExact(name);
        if (t != null) return t;
        // ② usercache（Paper API）：正版/FastLogin 玩家真实 UUID
        try { t = Bukkit.getOfflinePlayerIfCached(name); } catch (Throwable ignored) { t = null; }
        if (t != null && (t.hasPlayedBefore() || t.isOnline())) return t;
        // ③ 白名单：usercache 过期后仍保留 name->UUID 记录（本服白名单按正版 UUID 维护）
        for (org.bukkit.OfflinePlayer w : Bukkit.getWhitelistedPlayers()) {
            if (w.getName() != null && w.getName().equalsIgnoreCase(name) && w.hasPlayedBefore()) return w;
        }
        // ④ 传统名字推导（离线模式 = v3 影子 UUID）：纯离线玩家兜底
        t = Bukkit.getOfflinePlayer(name);
        return (t.hasPlayedBefore() || t.isOnline()) ? t : null;
    }

    private boolean isCreatorOf(Project pr, Player p) {
        return pr.creatorUuid.equals(p.getUniqueId().toString());
    }

    public int quotaLeft(Player p) {
        int limit = plugin.getDailyLimit();
        JSONObject c = d().createCounters.get(p.getUniqueId().toString());
        if (c == null || !today().equals(c.optString("date"))) return limit;
        return Math.max(0, limit - c.optInt("count"));
    }

    private void bumpCounter(Player p) {
        String uuid = p.getUniqueId().toString();
        JSONObject c = d().createCounters.get(uuid);
        if (c == null || !today().equals(c.optString("date"))) {
            c = new JSONObject();
            c.put("date", today());
            c.put("count", 0);
            d().createCounters.put(uuid, c);
        }
        c.put("count", c.optInt("count") + 1);
    }

    private void audit(Player p, String op, String detail) {
        d().addAudit(now(), p.getName(), op, detail);
    }

    /** QQ bot 事件行（M3）：[MEMO-EVENT] {json}，仅立项/竣工/删除三类；bot tail latest.log 播报。config qq-events 控制；mirror 无写操作天然无事件。 */
    private void qqEvent(JSONObject o) {
        if (plugin.isQqEvents()) plugin.getLogger().info("[MEMO-EVENT] " + o);
    }

    private boolean editable(Project pr) {
        return "planning".equals(pr.status) || "active".equals(pr.status);
    }

    /** 编号紧凑化：max(existing)+1（删除后不留空洞；级联删除保证无悬挂引用） */
    private int nextProjectId() {
        int max = 0;
        for (Project x : d().projects) if (x.id > max) max = x.id;
        return max + 1;
    }

    private int nextTaskId() {
        int max = 0;
        for (Task x : d().tasks) if (x.id > max) max = x.id;
        return max + 1;
    }

    private int nextMaterialId() {
        int max = 0;
        for (MaterialRow x : d().materials) if (x.id > max) max = x.id;
        return max + 1;
    }

    // ───────────────────────── 创建 ─────────────────────────

    public Result createProject(Player p, String title) {
        if (!p.hasPermission("memo.create")) return Result.fail("你没有立项权限（creator 组）");
        title = title == null ? "" : title.trim();
        if (title.isEmpty()) return Result.fail("工程标题不能为空");
        if (title.length() > 30) return Result.fail("标题最长 30 字");
        if (quotaLeft(p) <= 0)
            return Result.fail("今日立项额度已用完（每天最多 " + plugin.getDailyLimit() + " 个大条目）");
        Project pr = new Project();
        pr.id = nextProjectId();
        pr.title = title;
        pr.creator = p.getName();
        pr.creatorUuid = p.getUniqueId().toString();
        pr.managers.add(p.getName());
        pr.managerUuids.add(pr.creatorUuid);
        pr.createdAt = now();
        pr.status = "planning";
        d().projects.add(pr);
        bumpCounter(p);
        audit(p, "create_project", "#" + pr.id + " " + title);
        qqEvent(new JSONObject().put("type", "create").put("id", pr.id)
                .put("title", pr.title).put("by", p.getName()).put("at", pr.createdAt));
        save();
        return Result.ok();
    }

    public Result createTask(Player p, Project pr, String title) {
        if (!p.hasPermission("memo.create")) return Result.fail("你没有创建子任务权限（creator 组）");
        if (!editable(pr)) return Result.fail("工程《" + pr.title + "》已" + ("completed".equals(pr.status) ? "竣工" : "归档") + "，不能再添加");
        title = title == null ? "" : title.trim();
        if (title.isEmpty()) return Result.fail("子任务标题不能为空");
        if (title.length() > 40) return Result.fail("标题最长 40 字");
        Task t = new Task();
        t.id = nextTaskId();
        t.projectId = pr.id;
        t.title = title;
        List<Task> siblings = d().tasksOf(pr.id);
        t.sort = siblings.isEmpty() ? 0 : siblings.get(siblings.size() - 1).sort + 1;
        d().tasks.add(t);
        audit(p, "create_task", "#" + t.id + " @" + pr.title + " " + title);
        save();
        return Result.ok();
    }

    /**
     * 材料收集任务：任何玩家（不限管理者）可批量勾选材料行创建收集任务并自动认领。
     * 任务名自建；说明自动写入材料清单快照。
     * 规则：材料未完成才可建；一行材料同一时间只能被一个进行中的收集任务覆盖（别人不能再认领）。
     */
    public Result createCollectTask(Player p, Project pr, String title, List<Integer> materialIds) {
        if (!editable(pr)) return Result.fail("工程《" + pr.title + "》已竣工/归档，不能再添加");
        title = title == null ? "" : title.trim();
        if (title.isEmpty()) return Result.fail("请给收集任务取个名字");
        if (title.length() > 40) return Result.fail("任务名最长 40 字");
        if (materialIds == null || materialIds.isEmpty()) return Result.fail("请至少勾选一项材料");
        if (materialIds.size() > 50) return Result.fail("单个收集任务最多 50 项材料");
        // 逐项校验（说明区不再自动写清单——详情页直接显示实时收集进度）
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int mid : materialIds) {
            if (!seen.add(mid)) continue;
            MaterialRow m = d().materialById(mid);
            if (m == null || m.projectId != pr.id) return Result.fail("材料行 #" + mid + " 不存在");
            if (m.need > 0 && m.delivered >= m.need) return Result.fail("「" + displayName(m) + "」已收集完成，无需再认领");
            Task existing = coveringCollectTask(pr.id, mid);
            if (existing != null) return Result.fail("「" + displayName(m) + "」已有收集任务，认领人: " + existing.assignee);
        }
        Task t = new Task();
        t.id = nextTaskId();
        t.projectId = pr.id;
        t.type = "collect";
        t.materialIds.addAll(seen);
        t.title = title;
        t.status = "claimed";
        t.assignee = p.getName();
        t.assigneeUuid = p.getUniqueId().toString();
        t.claimedAt = now();
        List<Task> siblings = d().tasksOf(pr.id);
        t.sort = siblings.isEmpty() ? 0 : siblings.get(siblings.size() - 1).sort + 1;
        d().tasks.add(t);
        d().addParticipant(pr, p.getName());
        audit(p, "create_collect_task", "#" + t.id + " @" + pr.title + " mats=" + seen.size());
        save();
        return Result.ok().withMessage("已创建并认领收集任务「" + title + "」（" + seen.size() + " 项材料）");
    }

    /** 覆盖该材料行的进行中收集任务 */
    private Task coveringCollectTask(int projectId, int materialId) {
        for (Task t : d().tasksOf(projectId)) {
            if ("collect".equals(t.type) && !"done".equals(t.status) && t.materialIds.contains(materialId)) return t;
        }
        return null;
    }

    private String displayName(MaterialRow m) {
        return m.itemName == null || m.itemName.isEmpty() ? m.item : m.itemName;
    }

    // ───────────────────────── 工程编辑（管理者/OP） ─────────────────────────

    public Result editProject(Player p, Project pr, String field, String value) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能编辑");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        value = value == null ? "" : value.trim();
        switch (field) {
            case "title":
                if (value.isEmpty() || value.length() > 30) return Result.fail("标题 1~30 字");
                pr.title = value;
                break;
            case "desc":
                if (value.length() > 300) return Result.fail("描述最长 300 字");
                pr.desc = value;
                break;
            case "note":
                if (value.length() > 300) return Result.fail("搭建说明最长 300 字");
                pr.buildNote = value;
                break;
            case "locnote":
                pr.locNote = value;
                break;
            case "status":
                if (!"planning".equals(value) && !"active".equals(value))
                    return Result.fail("状态只能切 planning/active（竣工用 /memo finish）");
                pr.status = value;
                break;
            default:
                return Result.fail("未知字段 " + field);
        }
        audit(p, "edit_project", "#" + pr.id + " " + field);
        save();
        if ("title".equals(field)) plugin.getLocSync().onLocationChanged(pr); // 改名 → 路标同步重建（v1.2.0）
        return Result.ok();
    }

    /** v1.2.0：收录/移出「机器使用说明」。管理者+OP；planning/active/completed 开放（竣工后机器说明仍可维护），archived 全锁。 */
    public Result manualSet(Player p, Project pr, boolean on) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者/OP 能操作使用说明收录");
        if ("archived".equals(pr.status)) return Result.fail("工程已归档，不能修改收录");
        if (pr.inManual == on) return Result.fail(on ? "已在机器使用说明中" : "本就不在机器使用说明中");
        pr.inManual = on;
        audit(p, "manual_set", "#" + pr.id + " " + (on ? "+" : "-"));
        save();
        return Result.ok().withMessage(on ? "已收录进机器使用说明（/memo manual）" : "已移出机器使用说明");
    }

    public Result setLocationHidden(Player p, Project pr, boolean hidden) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能改选址可见性");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        pr.locHidden = hidden;
        audit(p, hidden ? "loc_hide" : "loc_show", "#" + pr.id);
        save();
        // v1.2.0：隐藏 → 从路标库删除（防坐标泄露）；重新公开 → 同步回去
        if (hidden) plugin.getLocSync().onRemove(pr);
        else plugin.getLocSync().onLocationChanged(pr);
        return Result.ok();
    }

    public Result clearLocation(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能清除选址");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        pr.locWorld = "";
        pr.locX = pr.locY = pr.locZ = 0;
        pr.locNote = "";
        pr.soWorld = "";
        audit(p, "loc_clear", "#" + pr.id);
        save();
        plugin.getLocSync().onRemove(pr); // v1.2.0：清除选址 → 移除路标
        return Result.ok();
    }

    /** 取玩家脚下坐标作选址 */
    public Result setLocation(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能改选址");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        if (pr.locLocked) return Result.fail("选址已锁定，请先解锁");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        pr.locWorld = p.getWorld().getName();
        pr.locX = p.getLocation().getBlockX();
        pr.locY = p.getLocation().getBlockY();
        pr.locZ = p.getLocation().getBlockZ();
        audit(p, "set_location", "#" + pr.id + " " + pr.locWorld + " " + pr.locX + "," + pr.locY + "," + pr.locZ);
        save();
        plugin.getLocSync().onLocationChanged(pr); // v1.2.0：非隐藏选址自动收录进路标库（!!loc）
        return Result.ok().withMessage(plugin.getLocSync().active() && !pr.locHidden
                ? "选址已保存，路标后台同步中（!!loc list 可查）" : null);
    }

    /** 手动填写选址坐标 */
    public Result setLocationManual(Player p, Project pr, int x, int y, int z, String note) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能改选址");
        if (pr.locLocked) return Result.fail("选址已锁定，请先解锁");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        pr.locWorld = p.getWorld().getName(); // 世界名始终跟随保存时所在世界（地狱存的就是地狱）
        pr.locX = x;
        pr.locY = y;
        pr.locZ = z;
        if (note != null && note.length() <= 60) pr.locNote = note.trim();
        audit(p, "set_location_manual", "#" + pr.id + " " + pr.locWorld + " " + x + "," + y + "," + z);
        save();
        plugin.getLocSync().onLocationChanged(pr); // v1.2.0：非隐藏选址自动收录进路标库（!!loc）
        return Result.ok().withMessage(plugin.getLocSync().active() && !pr.locHidden
                ? "选址已保存，路标后台同步中（!!loc list 可查）" : null);
    }

    /** 选址锁：防止坐标被误改 */
    public Result setLocationLocked(Player p, Project pr, boolean locked) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能操作选址锁");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        pr.locLocked = locked;
        audit(p, locked ? "loc_lock" : "loc_unlock", "#" + pr.id);
        save();
        return Result.ok();
    }

    // ───────────────────────── 材料收集核验区域 ─────────────────────────

    /** 角点锁：防止角点被误改（与选址锁同思路） */
    public Result setDepositLocked(Player p, Project pr, boolean locked) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能操作角点锁");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        pr.depLocked = locked;
        audit(p, locked ? "dep_lock" : "dep_unlock", "#" + pr.id);
        save();
        return Result.ok();
    }

    /** corner=1 或 2：取玩家脚下设为区域角点 */
    public Result setDepositCorner(Player p, Project pr, int corner) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能设置收集区域");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        if (pr.depLocked) return Result.fail("收集区域已锁定，请先解锁再改角点");
        String w = p.getWorld().getName();
        boolean worldChanged = !pr.depWorld.isEmpty() && !pr.depWorld.equals(w);
        if (worldChanged && corner == 2)
            return Result.fail("收集区域必须在同一世界（角点 A 在 " + pr.depWorld + "）");
        int x = p.getLocation().getBlockX(), y = p.getLocation().getBlockY(), z = p.getLocation().getBlockZ();
        if (corner == 1) {
            pr.depX1 = x; pr.depY1 = y; pr.depZ1 = z;
            if (worldChanged) { pr.depX2 = x; pr.depY2 = y; pr.depZ2 = z; } // 跨世界重设A：区域跟随新世界，角点B重置待设
        }
        else { pr.depX2 = x; pr.depY2 = y; pr.depZ2 = z; }
        pr.depWorld = w;
        audit(p, "set_deposit_" + corner, "#" + pr.id + " " + w + " " + x + "," + y + "," + z
                + (worldChanged ? " (world changed)" : ""));
        save();
        if (corner == 1 && worldChanged) return Result.ok().withMessage("区域已切换到当前世界，角点 B 需要重新设置");
        return Result.ok().withMessage(corner == 1 ? "角点 A 已设为脚下" : "角点 B 已设为脚下");
    }

    public Result clearDeposit(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能清除收集区域");
        if (pr.depLocked) return Result.fail("收集区域已锁定，请先解锁再清除");
        pr.depWorld = "";
        pr.depX1 = pr.depY1 = pr.depZ1 = pr.depX2 = pr.depY2 = pr.depZ2 = 0;
        audit(p, "clear_deposit", "#" + pr.id);
        save();
        return Result.ok();
    }

    /** 扫描区域内容器，核验材料收集进度（found>delivered 时更新，绝不回退已登记进度） */
    public Result checkDeposit(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能核验收集");
        if (pr.depWorld.isEmpty()) return Result.fail("还没有设置收集区域（先设两个角点）");
        org.bukkit.World world = Bukkit.getWorld(pr.depWorld);
        if (world == null) return Result.fail("区域所在世界不存在: " + pr.depWorld);
        int minX = Math.min(pr.depX1, pr.depX2), maxX = Math.max(pr.depX1, pr.depX2);
        int minY = Math.min(pr.depY1, pr.depY2), maxY = Math.max(pr.depY1, pr.depY2);
        int minZ = Math.min(pr.depZ1, pr.depZ2), maxZ = Math.max(pr.depZ1, pr.depZ2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0) return Result.fail("区域无效（两个角点不能相同面）");
        if (volume > 4096) return Result.fail("区域太大（" + volume + " 方块，上限 4096）");
        List<MaterialRow> rows = d().materialsOf(pr.id);
        if (rows.isEmpty()) return Result.fail("该工程还没有材料行");
        // 物品级计数（含潜影盒/收纳袋内嵌套物品）
        java.util.Map<String, Long> foundItems = new java.util.HashMap<>();
        int containers = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    org.bukkit.block.BlockState st;
                    try { st = world.getBlockAt(x, y, z).getState(); } catch (Exception e) { continue; }
                    if (!(st instanceof org.bukkit.block.Container)) continue;
                    containers++;
                    org.bukkit.inventory.ItemStack[] items;
                    try { items = ((org.bukkit.block.Container) st).getSnapshotInventory().getStorageContents(); }
                    catch (Exception e) {
                        try { items = ((org.bukkit.block.Container) st).getInventory().getStorageContents(); }
                        catch (Exception e2) { continue; }
                    }
                    for (org.bukkit.inventory.ItemStack it : items) countStack(it, foundItems, 0);
                }
            }
        }
        // 按行分配：同物品多行按顺序填充，最后一行承接全部剩余（含超出需求的部分）
        // —— 既避免合并显示翻倍，又保留「64/30」式超量可见
        java.util.Map<String, java.util.List<MaterialRow>> rowsByItem = new java.util.LinkedHashMap<>();
        for (MaterialRow row : rows) rowsByItem.computeIfAbsent(row.item, k -> new java.util.ArrayList<>()).add(row);
        java.util.Map<Integer, Long> found = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.List<MaterialRow>> e : rowsByItem.entrySet()) {
            long avail = foundItems.getOrDefault(e.getKey(), 0L);
            java.util.List<MaterialRow> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                MaterialRow row = list.get(i);
                long give;
                if (i == list.size() - 1) give = avail; // 末行承接剩余全部（含超量）
                else { give = Math.min(avail, Math.max(row.need, 0L)); avail -= give; }
                found.put(row.id, give);
            }
        }
        // 纯容器核验：delivered 与区域内容器实际数量同步（可增可减）
        int updated = 0;
        for (MaterialRow row : rows) {
            long f = found.getOrDefault(row.id, 0L);
            if (f != row.delivered) { row.delivered = f; updated++; }
        }
        // 收集任务自动判定：任务覆盖的材料全部收齐 → 自动完成（手动完成按钮仍保留）
        List<String> autoDone = new java.util.ArrayList<>();
        for (Task t : d().tasksOf(pr.id)) {
            if (!"collect".equals(t.type) || "done".equals(t.status) || t.materialIds.isEmpty()) continue;
            boolean any = false, allOk = true;
            for (int mid : t.materialIds) {
                MaterialRow row = d().materialById(mid);
                if (row == null || row.projectId != pr.id) continue;
                any = true;
                if (row.delivered < row.need) { allOk = false; break; }
            }
            if (any && allOk) {
                t.status = "done";
                t.doneAt = now();
                autoDone.add(t.title);
                audit(p, "collect_task_auto_done", "task#" + t.id);
            }
        }
        long totalFound = foundItems.values().stream().mapToLong(Long::longValue).sum();
        audit(p, "check_deposit", "#" + pr.id + " containers=" + containers + " updated=" + updated);
        save();
        StringBuilder msg = new StringBuilder("核验完成：扫描容器 " + containers + " 个，区域物品 "
                + totalFound + " 件，更新 " + updated + " 行进度");
        if (!autoDone.isEmpty()) msg.append("；收集任务自动完成: ").append(String.join("、", autoDone));
        return Result.ok().withMessage(msg.toString());
    }

    /** 计数单个物品堆（含潜影盒/收纳袋内嵌套物品，递归深度限制 5） */
    private void countStack(org.bukkit.inventory.ItemStack it, java.util.Map<String, Long> out, int depth) {
        if (it == null || it.getType() == org.bukkit.Material.AIR || depth > 5) return;
        out.merge(it.getType().getKey().toString(), (long) it.getAmount(), Long::sum);
        org.bukkit.inventory.meta.ItemMeta meta;
        try { meta = it.getItemMeta(); } catch (Exception e) { return; }
        if (meta == null) return;
        // 潜影盒（含各色）：BlockStateMeta 里的盒子内容
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta) {
            try {
                org.bukkit.block.BlockState bs = ((org.bukkit.inventory.meta.BlockStateMeta) meta).getBlockState();
                if (bs instanceof org.bukkit.block.ShulkerBox) {
                    org.bukkit.inventory.ItemStack[] in;
                    try { in = ((org.bukkit.block.ShulkerBox) bs).getSnapshotInventory().getStorageContents(); }
                    catch (Exception e) { in = ((org.bukkit.block.ShulkerBox) bs).getInventory().getStorageContents(); }
                    for (org.bukkit.inventory.ItemStack s : in) countStack(s, out, depth + 1);
                }
            } catch (Exception ignored) { }
        }
        // 收纳袋（各色）：BundleMeta 内容（Paper API，反射保险）
        try {
            if (meta instanceof org.bukkit.inventory.meta.BundleMeta) {
                for (org.bukkit.inventory.ItemStack s : ((org.bukkit.inventory.meta.BundleMeta) meta).getItems()) {
                    countStack(s, out, depth + 1);
                }
            }
        } catch (Throwable ignored) { }
    }

    public Result setManagers(Player p, Project pr, boolean add, String targetName) {
        if (!(isCreatorOf(pr, p) || isAdmin(p)))
            return Result.fail("只有工程创建者或 OP 能改管理者列表");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        if (add) {
            org.bukkit.OfflinePlayer target = resolveKnownPlayer(targetName);
            if (target == null)
                return Result.fail("玩家 " + targetName + " 从未进过服");
            String uuid = target.getUniqueId().toString();
            String name = target.getName() == null ? targetName : target.getName();
            if (pr.managerUuids.contains(uuid)) return Result.fail(name + " 已经是管理者");
            pr.managers.add(name);
            pr.managerUuids.add(uuid);
            audit(p, "add_manager", "#" + pr.id + " +" + name);
        } else {
            int idx = -1;
            for (int i = 0; i < pr.managers.size(); i++)
                if (pr.managers.get(i).equalsIgnoreCase(targetName)) { idx = i; break; }
            if (idx < 0) return Result.fail("找不到管理者 " + targetName);
            if (pr.managerUuids.get(idx).equals(pr.creatorUuid))
                return Result.fail("创建者默认是管理者，不能移除");
            pr.managers.remove(idx);
            pr.managerUuids.remove(idx);
            audit(p, "remove_manager", "#" + pr.id + " -" + targetName);
        }
        save();
        return Result.ok();
    }

    // ───────────────────────── 子任务（认领/完成） ─────────────────────────

    public Result claim(Player p, Task t) {
        if (!p.hasPermission("memo.claim")) return Result.fail("无认领权限");
        Project pr = d().projectById(t.projectId);
        if (pr == null || !editable(pr)) return Result.fail("该工程当前不可认领");
        if (!"open".equals(t.status)) return Result.fail("这条任务已被认领或已完成");
        t.status = "claimed";
        t.assignee = p.getName();
        t.assigneeUuid = p.getUniqueId().toString();
        t.claimedAt = now();
        d().addParticipant(pr, p.getName());
        audit(p, "claim", "task#" + t.id + " " + t.title);
        save();
        return Result.ok();
    }

    public Result unclaim(Player p, Task t) {
        boolean self = t.assigneeUuid.equals(p.getUniqueId().toString());
        Project pr = d().projectById(t.projectId);
        if (!self && (pr == null || !isManagerOf(pr, p)))
            return Result.fail("只能取消自己的认领（管理者/OP 可撤销他人）");
        if (!"claimed".equals(t.status)) return Result.fail("该任务当前未被认领");
        t.status = "open";
        t.assignee = "";
        t.assigneeUuid = "";
        t.claimedAt = 0;
        audit(p, "unclaim", "task#" + t.id);
        save();
        return Result.ok();
    }

    public Result taskDone(Player p, Task t) {
        Project pr = d().projectById(t.projectId);
        if (pr == null || !editable(pr)) return Result.fail("该工程当前不可操作");
        boolean self = t.assigneeUuid.equals(p.getUniqueId().toString());
        if (!self && !isManagerOf(pr, p))
            return Result.fail("只能完成自己认领的任务（管理者/OP 可代完）");
        if ("done".equals(t.status)) return Result.fail("该任务已完成");
        t.status = "done";
        t.doneAt = now();
        if (t.assignee.isEmpty()) { t.assignee = p.getName(); t.assigneeUuid = p.getUniqueId().toString(); }
        d().addParticipant(pr, t.assignee);
        audit(p, "task_done", "task#" + t.id + " " + t.title);
        save();
        return Result.ok();
    }

    public Result taskReopen(Player p, Task t) {
        Project pr = d().projectById(t.projectId);
        if (pr == null || !isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能重开任务");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        if (!"done".equals(t.status)) return Result.fail("该任务未完成");
        t.status = t.assignee.isEmpty() ? "open" : "claimed";
        t.doneAt = 0;
        audit(p, "task_reopen", "task#" + t.id);
        save();
        return Result.ok();
    }

    /** 子任务细节说明（创建者权限或管理者可编辑；竣工/归档后锁定） */
    public Result taskSetNote(Player p, Task t, String note) {
        Project pr = d().projectById(t.projectId);
        if (pr == null) return Result.fail("找不到工程");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        if (!p.hasPermission("memo.create") && !isManagerOf(pr, p))
            return Result.fail("只有 creator 组或管理者能编辑子任务说明");
        note = note == null ? "" : note.trim();
        if (note.length() > 200) return Result.fail("说明最长 200 字");
        t.note = note;
        audit(p, "task_set_note", "task#" + t.id + " len=" + note.length());
        save();
        return Result.ok();
    }

    /** 改子任务标题（creator 组或管理者） */
    public Result taskRename(Player p, Task t, String title) {
        Project pr = d().projectById(t.projectId);
        if (pr == null) return Result.fail("找不到工程");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        if (!p.hasPermission("memo.create") && !isManagerOf(pr, p))
            return Result.fail("只有 creator 组或管理者能改子任务标题");
        title = title == null ? "" : title.trim();
        if (title.isEmpty()) return Result.fail("标题不能为空");
        if (title.length() > 40) return Result.fail("标题最长 40 字");
        t.title = title;
        audit(p, "task_rename", "task#" + t.id + " " + title);
        save();
        return Result.ok();
    }

    // ───────────────────────── 材料 ─────────────────────────

    /** 纯数字登记（已拍板）；amount 由调用方解析好 */
    public Result deliver(Player p, MaterialRow m, long amount) {
        if (!p.hasPermission("memo.claim")) return Result.fail("无登记权限");
        Project pr = d().projectById(m.projectId);
        if (pr == null || !editable(pr)) return Result.fail("该工程当前不可登记材料");
        if (amount <= 0) return Result.fail("数量必须是正数");
        if (amount > 1_000_000) return Result.fail("单次登记不能超过 100 万");
        m.delivered += amount;
        JSONObject c = new JSONObject();
        c.put("by", p.getName());
        c.put("amount", amount);
        c.put("at", now());
        m.contributions.add(c);
        d().addParticipant(pr, p.getName());
        audit(p, "deliver", "mat#" + m.id + " " + m.itemName + " +" + amount);
        save();
        return Result.ok();
    }

    public Result materialAdd(Player p, Project pr, String itemId, long need) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能添加材料行");
        if (!editable(pr)) return Result.fail("工程已竣工/归档");
        if (need <= 0 || need > 10_000_000) return Result.fail("需求数量需在 1~1000 万");
        boolean added = addOrMergeRow(pr, itemId, need, "custom");
        if (!added) {
            audit(p, "material_add_merge", "#" + pr.id + " " + itemId + " +" + need);
            save();
            return Result.ok().withMessage("已合并到已有的同名材料行");
        }
        audit(p, "material_add", "#" + pr.id + " " + itemId + " x" + need);
        save();
        return Result.ok();
    }

    /** 手持物品快捷添加材料行（PCHSystem 手持读取同款思路） */
    public Result materialAddFromHand(Player p, Project pr, long need) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能添加材料行");
        if (!editable(pr)) return Result.fail("工程已竣工/归档");
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR || !hand.getType().isItem())
            return Result.fail("主手没有物品（先拿着要登记的材料再点）");
        return materialAdd(p, pr, hand.getType().getKey().toString(), need);
    }

    /** 从服务器共享投影库导入（PCHSystem 式建表）；name=人类可读名（placements.json） */
    public Result importMaterials(Player p, Project pr, java.io.File file, String name) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能导入投影");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        LitematicImporter.ImportResult ir = LitematicImporter.parse(file);
        if (ir.error != null) return Result.fail(ir.error);
        int added = 0, merged = 0;
        for (java.util.Map.Entry<Material, Long> e : ir.counts.entrySet()) {
            if (addOrMergeRow(pr, e.getKey().getKey().toString(), e.getValue(),
                    (name == null || name.isEmpty()) ? file.getName() : name)) added++; else merged++;
        }
        recordSchematic(pr, (name == null || name.isEmpty()) ? file.getName() : name,
                "shared", file.getName(), p.getName(), ir);
        audit(p, "import_schematic", "#" + pr.id + " " + file.getName() + " rows=" + added);
        save();
        return Result.ok().withMessage(importReport(ir, added, merged));
    }

    /** 客户端解析的本地投影批量导入（items=[{item,need}]，同款合并） */
    public Result importMaterialsBulk(Player p, Project pr, org.json.JSONArray items, String name,
                                      boolean hasOrigin, int ox, int oy, int oz) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能导入投影");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，需先解档");
        if (items == null || items.isEmpty()) return Result.fail("没有可导入的材料");
        if (items.length() > 400) return Result.fail("单次导入材料行过多（上限 400）");
        int added = 0, merged = 0, bad = 0;
        long totalBlocks = 0;
        for (Object o : items) {
            if (!(o instanceof org.json.JSONObject)) { bad++; continue; }
            org.json.JSONObject it = (org.json.JSONObject) o;
            long need = it.optLong("need", 0);
            totalBlocks += need;
            if (addOrMergeRow(pr, it.optString("item"), need,
                    (name == null || name.isEmpty()) ? "custom" : name)) added++; else merged++;
        }
        org.json.JSONObject rec = new org.json.JSONObject();
        rec.put("name", name == null || name.isEmpty() ? "本地投影" : name);
        rec.put("source", "local");
        rec.put("file", name == null ? "" : name);
        rec.put("by", p.getName());
        rec.put("at", now());
        rec.put("blocks", totalBlocks);
        rec.put("kinds", items.length());
        rec.put("hasOrigin", hasOrigin);
        if (hasOrigin) { rec.put("ox", ox); rec.put("oy", oy); rec.put("oz", oz); }
        pr.schematics.add(rec);
        audit(p, "import_materials_bulk", "#" + pr.id + " added=" + added + " merged=" + merged);
        save();
        StringBuilder sb = new StringBuilder("导入完成：新增 ").append(added).append(" 行");
        if (merged > 0) sb.append("，合并 ").append(merged).append(" 行");
        if (bad > 0) sb.append("，忽略 ").append(bad).append(" 条无效");
        if (hasOrigin) sb.append("；投影原点 ").append(ox).append(", ").append(oy).append(", ").append(oz);
        return Result.ok().withMessage(sb.toString());
    }

    private void recordSchematic(Project pr, String name, String source, String file,
                                 String by, LitematicImporter.ImportResult ir) {
        org.json.JSONObject rec = new org.json.JSONObject();
        rec.put("name", name);
        rec.put("source", source);
        rec.put("file", file);
        rec.put("by", by);
        rec.put("at", now());
        rec.put("blocks", ir.totalBlocks);
        rec.put("kinds", ir.counts.size());
        rec.put("hasOrigin", ir.hasOrigin);
        if (ir.hasOrigin) { rec.put("ox", ir.originX); rec.put("oy", ir.originY); rec.put("oz", ir.originZ); }
        pr.schematics.add(rec);
    }

    private String importReport(LitematicImporter.ImportResult ir, int added, int merged) {
        StringBuilder sb = new StringBuilder("投影导入完成：新增 ")
                .append(added).append(" 行材料");
        if (merged > 0) sb.append("，合并 ").append(merged).append(" 行");
        sb.append("（共 ").append(ir.totalBlocks).append(" 方块");
        if (ir.skippedNonItem > 0) sb.append("，跳过 ").append(ir.skippedNonItem).append(" 个非物品方块");
        sb.append("）");
        return sb.toString();
    }

    /** 删除投影记录（不删除已合并的材料行） */
    /** 删除投影记录，并连带删除该投影来源的材料行 */
    public Result schematicDelete(Player p, Project pr, int idx) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能管理投影");
        if (idx < 0 || idx >= pr.schematics.size()) return Result.fail("投影记录不存在");
        org.json.JSONObject rec = pr.schematics.remove(idx);
        String name = rec.optString("name");
        int removed = 0;
        java.util.Iterator<MaterialRow> it = d().materials.iterator();
        while (it.hasNext()) {
            MaterialRow row = it.next();
            if (row.projectId == pr.id && name != null && name.equals(row.source)) {
                it.remove();
                removed++;
            }
        }
        audit(p, "schematic_delete", "#" + pr.id + " " + name + " mats-" + removed);
        save();
        return Result.ok().withMessage("已删除投影「" + name + "」及其 " + removed + " 行材料");
    }

    /** 管理者手动添加参与玩家（参与但没领任务的玩家） */
    public Result participantsAdd(Player p, Project pr, String name) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能编辑参与玩家");
        if ("archived".equals(pr.status)) return Result.fail("工程已归档，不能再修改");
        org.bukkit.OfflinePlayer target = resolveKnownPlayer(name);
        if (target == null)
            return Result.fail("玩家 " + name + " 从未进过服");
        String real = target.getName() == null ? name : target.getName();
        if (pr.participants.contains(real)) return Result.fail(real + " 已在参与玩家中");
        pr.participants.add(real);
        audit(p, "participants_add", "#" + pr.id + " +" + real);
        save();
        return Result.ok();
    }

    /** 管理者手动移除参与玩家 */
    public Result participantsRemove(Player p, Project pr, String name) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能编辑参与玩家");
        if ("archived".equals(pr.status)) return Result.fail("工程已归档，不能再修改");
        boolean removed = pr.participants.removeIf(n -> n.equalsIgnoreCase(name));
        if (!removed) return Result.fail("找不到参与玩家 " + name);
        audit(p, "participants_remove", "#" + pr.id + " -" + name);
        save();
        return Result.ok();
    }



    /** 同物品+同来源合并需求数量；true=新增了行，false=合并进已有行 */
    private boolean addOrMergeRow(Project pr, String itemKey, long amount, String source) {
        Material mat = Material.matchMaterial(itemKey);
        if (mat == null || !mat.isItem() || amount <= 0) return false;
        String key = mat.getKey().toString();
        String src = (source == null || source.isEmpty()) ? "custom" : source;
        for (MaterialRow row : d().materialsOf(pr.id)) {
            if (key.equals(row.item) && src.equals(row.source)) { row.need += amount; return false; }
        }
        MaterialRow m = new MaterialRow();
        m.id = nextMaterialId();
        m.projectId = pr.id;
        m.item = key;
        m.itemName = mat.name().toLowerCase().replace('_', ' ');
        m.source = src;
        m.need = amount;
        d().materials.add(m);
        return true;
    }

    public Result materialSetNeed(Player p, MaterialRow m, long need) {
        Project pr = d().projectById(m.projectId);
        if (pr == null || !isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能改需求");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        if (need <= 0 || need > 10_000_000) return Result.fail("需求数量需在 1~1000 万");
        m.need = need;
        audit(p, "material_set_need", "mat#" + m.id + " ->" + need);
        save();
        return Result.ok();
    }

    public Result materialRemove(Player p, MaterialRow m) {
        Project pr = d().projectById(m.projectId);
        if (pr == null || !isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能删材料行");
        if (!editable(pr)) return Result.fail("工程已竣工/归档，不能再修改");
        d().materials.remove(m);
        audit(p, "material_remove", "mat#" + m.id + " " + m.itemName);
        save();
        return Result.ok();
    }

    // ───────────────────────── 状态流转 ─────────────────────────

    public Result projectDone(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能竣工");
        if (!editable(pr)) return Result.fail("工程不是进行中/规划中状态");
        pr.status = "completed";
        pr.completedAt = now();
        // participants 此刻起冻结（状态机阻止后续 claim/deliver/编辑）
        audit(p, "project_done", "#" + pr.id + " " + pr.title);
        qqEvent(new JSONObject().put("type", "done").put("id", pr.id)
                .put("title", pr.title).put("at", pr.completedAt)
                .put("days", Math.max(0, (pr.completedAt - pr.createdAt) / 86400))
                .put("participants", pr.participants));
        save();
        return Result.ok();
    }

    /** 解档：管理者+OP（已拍板） */
    public Result projectReopen(Player p, Project pr) {
        if (!isManagerOf(pr, p)) return Result.fail("只有本工程管理者或 OP 能解档");
        if (!"completed".equals(pr.status)) return Result.fail("工程未竣工");
        pr.status = "active";
        pr.completedAt = 0;
        audit(p, "project_reopen", "#" + pr.id);
        save();
        return Result.ok();
    }

    public Result archive(Player p, Project pr) {
        if (!p.hasPermission("memo.archive")) return Result.fail("归档需要 OP 权限");
        pr.status = "archived";
        audit(p, "archive", "#" + pr.id);
        save();
        return Result.ok();
    }

    public Result unarchive(Player p, Project pr) {
        if (!p.hasPermission("memo.archive")) return Result.fail("解档归档需要 OP 权限");
        if (!"archived".equals(pr.status)) return Result.fail("工程未归档");
        pr.status = "active";
        audit(p, "unarchive", "#" + pr.id);
        save();
        return Result.ok();
    }

    // ───────────────────────── 删除（仅 OP） ─────────────────────────

    public Result deleteProject(Player p, Project pr) {
        if (!p.hasPermission("memo.delete")) return Result.fail("删除需要 OP 权限");
        plugin.getLocSync().onRemove(pr); // v1.2.0：删工程 → 移除已同步路标（异步，不阻塞删除）
        d().tasks.removeIf(t -> t.projectId == pr.id);
        d().materials.removeIf(m -> m.projectId == pr.id);
        d().projects.remove(pr);
        audit(p, "delete_project", "#" + pr.id + " " + pr.title);
        qqEvent(new JSONObject().put("type", "delete").put("id", pr.id)
                .put("title", pr.title).put("by", p.getName()).put("at", now()));
        save();
        return Result.ok();
    }

    public Result deleteTask(Player p, Task t) {
        Project pr = d().projectById(t.projectId);
        if (pr == null || !isManagerOf(pr, p)) return Result.fail("只有管理者/OP 能删子任务");
        d().tasks.remove(t);
        audit(p, "delete_task", "task#" + t.id + " " + t.title);
        save();
        return Result.ok();
    }
}
