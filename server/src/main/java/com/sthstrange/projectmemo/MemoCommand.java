package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.MaterialRow;
import com.sthstrange.projectmemo.MemoData.Project;
import com.sthstrange.projectmemo.MemoData.Task;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /memo 命令路由 v0.5：聊天 UI 只读（查看用）；一切修改=客户端模组（主入口）+ 命令（管理员兜底）。
 * 所有命令都要求一次性带全参数（不再有交互式输入）。
 */
public final class MemoCommand implements CommandExecutor, TabCompleter {

    private final ProjectMemoPlugin plugin;

    public MemoCommand(ProjectMemoPlugin plugin) { this.plugin = plugin; }

    private MemoData d() { return plugin.getStore().data(); }

    private MemoActions act() { return plugin.getActions(); }

    private void err(CommandSender s, String msg) {
        s.sendMessage(ChatUI.prefix().append(Component.text("✘ " + msg, NamedTextColor.RED)));
    }

    private void okMsg(Player p, String msg) {
        p.sendMessage(ChatUI.prefix().append(Component.text("✔ " + msg, NamedTextColor.GREEN)));
    }

    private void needMod(Player p, String usage) {
        p.sendMessage(ChatUI.prefix().append(Component.text(
                "修改操作请安装 ProjectMemo 客户端模组；或用完整命令: " + usage, NamedTextColor.GRAY)));
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        if (!(s instanceof Player)) {
            if (args.length == 0 || "list".equals(args[0])) {
                plugin.getUi().sendPlainOverview(s);
            } else {
                s.sendMessage("[ProjectMemo] 控制台仅支持 /memo list");
            }
            return true;
        }
        Player p = (Player) s;
        if (!p.hasPermission("memo.use")) { err(p, "无权限"); return true; }
        if (args.length == 0) args = new String[]{"open"};
        String sub = args[0].toLowerCase();
        if (plugin.isMirror() && !"open".equals(sub) && !"list".equals(sub) && !"show".equals(sub)) {
            err(p, "只读镜像服：备忘录编辑请回主服");
            return true;
        }
        try {
            route(p, sub, args);
        } catch (Exception e) {
            plugin.getLogger().warning("memo 命令异常: " + e);
            err(p, "内部错误，已记录日志");
        }
        return true;
    }

    /** 编辑类命令：聊天侧对普通玩家只读，这些仅限 OP/管理员（编辑入口统一在客户端模组；控制台不受影响） */
    private static final java.util.Set<String> EDIT_CMDS = new java.util.HashSet<>(Arrays.asList(
            "create", "sub", "claim", "unclaim", "done", "taskreopen",
            "matadd", "mataddhand", "matneed", "matdel", "import", "doimport",
            "set", "loc", "lochide", "locclear", "desc", "note",
            "managers", "participants", "finish", "reopen"));

    private void route(Player p, String sub, String[] args) {
        if (EDIT_CMDS.contains(sub) && !(p.hasPermission("memo.admin") || p.isOp())) {
            err(p, "聊天侧为只读：认领/立项/修改等操作请安装 ProjectMemo 客户端模组");
            return;
        }
        switch (sub) {
            case "open": {
                if (plugin.getChannel().isClient(p)) {
                    plugin.getChannel().sendOpenGui(p); // 已注册模组客户端 → 弹 GUI
                } else {
                    plugin.getUi().sendOverview(p, 1);  // 无模组 → 聊天只读总览
                }
                return;
            }
            case "list": {
                int page = args.length > 1 ? parseInt(args[1], 1) : 1;
                plugin.getUi().sendOverview(p, page);
                return;
            }
            case "show": {
                if (args.length < 2) { err(p, "用法: /memo show <编号|名称>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "create": {
                if (args.length < 2) { needMod(p, "/memo create <标题>"); return; }
                MemoActions.Result r = act().createProject(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                if (!r.ok) { err(p, r.error); return; }
                Project pr = d().projects.get(d().projects.size() - 1);
                okMsg(p, "已立项《" + pr.title + "》（编号 #" + pr.id + "）");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "sub": {
                if (args.length < 3) { needMod(p, "/memo sub <工程> <标题>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = act().createTask(p, pr, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已添加子任务");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "claim": case "unclaim": case "done": case "taskreopen": case "taskdel": {
                if (args.length < 2) { err(p, "用法: /memo " + sub + " <任务编号>"); return; }
                Task t = d().taskById(parseInt(args[1], -1));
                if (t == null) { err(p, "找不到任务 #" + args[1]); return; }
                Project pr = d().projectById(t.projectId);
                MemoActions.Result r;
                switch (sub) {
                    case "claim": r = act().claim(p, t); break;
                    case "unclaim": r = act().unclaim(p, t); break;
                    case "done": r = act().taskDone(p, t); break;
                    case "taskreopen": r = act().taskReopen(p, t); break;
                    default: r = act().deleteTask(p, t); break;
                }
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "操作完成");
                if (pr != null) plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "deliver": {
                err(p, "登记上交已废弃：材料进度改用「材料收集页 → 设角点 → 核验」自动扫描容器");
                return;
            }

            case "mataddhand": {
                if (args.length < 3) { needMod(p, "/memo mataddhand <工程> <数量>（主手持材料）"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                Material hand = p.getInventory().getItemInMainHand().getType();
                if (hand == Material.AIR || !hand.isItem()) { err(p, "主手没有物品"); return; }
                Long need = ChatInputSyntax.parseAmount(args[2]);
                if (need == null) { err(p, "数量格式错误"); return; }
                MemoActions.Result r = act().materialAdd(p, pr, hand.getKey().toString(), need);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已添加材料行（" + hand.getKey() + "）");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "matadd": {
                if (args.length < 4) { needMod(p, "/memo matadd <工程> <物品ID> <数量>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = act().materialAdd(p, pr, args[2], parseLong(args[3], -1));
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已添加材料行");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "matneed": {
                if (args.length < 3) { needMod(p, "/memo matneed <材料编号> <新数量>"); return; }
                MaterialRow m = d().materialById(parseInt(args[1], -1));
                if (m == null) { err(p, "找不到材料行 #" + args[1]); return; }
                MemoActions.Result r = act().materialSetNeed(p, m, parseLong(args[2], -1));
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已修改需求数量");
                Project pr = d().projectById(m.projectId);
                if (pr != null) plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "matdel": {
                MaterialRow m = d().materialById(parseInt(args[1], -1));
                if (m == null) { err(p, "找不到材料行 #" + args[1]); return; }
                MemoActions.Result r = act().materialRemove(p, m);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已删除材料行");
                Project pr = d().projectById(m.projectId);
                if (pr != null) plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "import": {
                needMod(p, "/memo doimport <工程> <imports目录中的文件名>（建议用客户端模组 [投影导入] 界面）");
                return;
            }
            case "doimport": {
                if (args.length < 3) { err(p, "用法: /memo doimport <工程> <文件名>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                String name = new File(args[2]).getName();
                if (name.startsWith("._")) { err(p, "无效文件（macOS 元数据文件）"); return; }
                File f = new File(plugin.getSyncmaticsDir(), name);
                if (!f.exists()) { err(p, "共享投影库里没有 " + name); return; }
                MemoActions.Result r = act().importMaterials(p, pr, f, name);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, r.message == null ? "导入完成" : r.message);
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "set": {
                if (args.length < 3) { needMod(p, "/memo set <工程> desc|note <文本> 或 loc|lochide|locclear"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                String field = args[2].toLowerCase();
                switch (field) {
                    case "loc": {
                        MemoActions.Result r = act().setLocation(p, pr);
                        if (!r.ok) { err(p, r.error); return; }
                        okMsg(p, "选址已设为脚下: " + pr.locWorld + " " + pr.locX + ", " + pr.locY + ", " + pr.locZ);
                        plugin.getUi().sendDetail(p, pr);
                        return;
                    }
                    case "lochide": {
                        MemoActions.Result r = act().setLocationHidden(p, pr, !pr.locHidden);
                        if (!r.ok) { err(p, r.error); return; }
                        okMsg(p, pr.locHidden ? "坐标已隐藏（仅管理者/OP 可见）" : "坐标已公开");
                        plugin.getUi().sendDetail(p, pr);
                        return;
                    }
                    case "locclear": {
                        MemoActions.Result r = act().clearLocation(p, pr);
                        if (!r.ok) { err(p, r.error); return; }
                        okMsg(p, "选址已清除");
                        plugin.getUi().sendDetail(p, pr);
                        return;
                    }
                    case "desc": case "note": {
                        if (args.length < 4) { needMod(p, "/memo set " + pr.id + " " + field + " <文本>"); return; }
                        MemoActions.Result r = act().editProject(p, pr, field, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                        if (!r.ok) { err(p, r.error); return; }
                        okMsg(p, "已更新");
                        plugin.getUi().sendDetail(p, pr);
                        return;
                    }
                    default:
                        err(p, "字段只能是 desc/note/loc/lochide/locclear");
                }
                return;
            }
            case "managers": {
                if (args.length < 4) { needMod(p, "/memo managers <工程> add|remove <玩家名>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                boolean add = "add".equalsIgnoreCase(args[2]);
                if (!add && !"remove".equalsIgnoreCase(args[2])) { err(p, "add 或 remove"); return; }
                MemoActions.Result r = act().setManagers(p, pr, add, args[3]);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, (add ? "已添加管理者 " : "已移除管理者 ") + args[3]);
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "participants": {
                if (args.length < 4) { needMod(p, "/memo participants <工程> add|remove <玩家名>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                boolean add = "add".equalsIgnoreCase(args[2]);
                if (!add && !"remove".equalsIgnoreCase(args[2])) { err(p, "add 或 remove"); return; }
                MemoActions.Result r = add ? act().participantsAdd(p, pr, args[3])
                        : act().participantsRemove(p, pr, args[3]);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, (add ? "已添加参与玩家 " : "已移除参与玩家 ") + args[3]);
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "finish": {
                if (args.length < 2) { needMod(p, "/memo finish <工程>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = act().projectDone(p, pr);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "🎉 工程《" + pr.title + "》已竣工（参与: " + String.join("、", pr.participants) + "）");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "reopen": {
                if (args.length < 2) { needMod(p, "/memo reopen <工程>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = act().projectReopen(p, pr);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已解档，回到进行中");
                plugin.getUi().sendDetail(p, pr);
                return;
            }
            case "archive": case "unarchive": {
                if (args.length < 2) { needMod(p, "/memo archive|unarchive <工程>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = "archive".equals(sub) ? act().archive(p, pr) : act().unarchive(p, pr);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "archive".equals(sub) ? "已归档（从列表隐藏，数据保留）" : "已取消归档");
                return;
            }
            case "delete": {
                if (!p.hasPermission("memo.delete")) { err(p, "删除需要 OP 权限"); return; }
                if (args.length < 2) { needMod(p, "/memo delete <工程>"); return; }
                Project pr = d().projectByRef(args[1]);
                if (pr == null) { err(p, "找不到工程: " + args[1]); return; }
                MemoActions.Result r = act().deleteProject(p, pr);
                if (!r.ok) { err(p, r.error); return; }
                okMsg(p, "已删除工程");
                return;
            }
            case "daily":
                p.sendMessage(ChatUI.prefix().append(Component.text(
                        "活动模块（每日整活/聚落建设名单等长期活动）开发中，见里程碑 M6。", NamedTextColor.GRAY)));
                return;
            case "admin": {
                if (!p.hasPermission("memo.admin")) { err(p, "无权限"); return; }
                if (args.length > 1 && "reload".equals(args[1])) {
                    plugin.reloadConfig();
                    plugin.loadConfigValues();
                    okMsg(p, "配置已重载");
                } else if (args.length > 1 && "save".equals(args[1])) {
                    plugin.getStore().save();
                    okMsg(p, "已强制存盘");
                } else {
                    p.sendMessage(ChatUI.prefix().append(Component.text(
                            "工程 " + d().projects.size() + " · 任务 " + d().tasks.size()
                                    + " · 材料 " + d().materials.size() + " · 审计 " + d().audit.size(),
                            NamedTextColor.GRAY)));
                }
                return;
            }
            default:
                err(p, "未知子命令。试试 /memo（总览）、/memo show <编号>");
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    // ───────────────────────── tab 补全 ─────────────────────────

    private static final List<String> ROOT = Arrays.asList("list", "show", "create", "sub", "claim", "unclaim",
            "done", "set", "managers", "participants", "import", "finish", "reopen", "archive", "delete", "daily", "admin");

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        if (!(s instanceof Player)) return List.of();
        if (!s.hasPermission("memo.use")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String x : ROOT) if (x.startsWith(args[0].toLowerCase())) out.add(x);
            return out;
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            switch (sub) {
                case "show": case "sub": case "set": case "managers": case "participants": case "finish": case "reopen":
                case "archive": case "unarchive": case "delete": case "matadd": case "doimport":
                    for (Project pr : d().projects) out.add(String.valueOf(pr.id));
                    break;
                case "claim": case "unclaim": case "done": case "taskreopen": case "taskdel":
                    for (Task t : d().tasks) out.add(String.valueOf(t.id));
                    break;
                case "matneed": case "matdel":
                    for (MaterialRow m : d().materials) out.add(String.valueOf(m.id));
                    break;
                case "admin":
                    out.addAll(Arrays.asList("reload", "save"));
                    break;
                default: break;
            }
            return filter(out, args[1]);
        }
        if (args.length == 3) {
            if ("set".equals(sub)) out.addAll(Arrays.asList("desc", "note", "loc", "lochide", "locclear"));
            if ("managers".equals(sub)) out.addAll(Arrays.asList("add", "remove"));
            if ("matadd".equals(sub)) {
                String q = args[2].toLowerCase();
                int n = 0;
                for (Material m : Material.values()) {
                    if (m.isItem() && m.getKey().toString().contains(q)) {
                        out.add(m.getKey().toString());
                        if (++n >= 40) break;
                    }
                }
            }
            if ("doimport".equals(sub)) {
                File[] files = plugin.getImportsDir().listFiles((f, name) -> name.toLowerCase().endsWith(".litematic"));
                if (files != null) for (File f : files) out.add(f.getName());
            }
            return filter(out, args[2]);
        }
        return out;
    }

    private static List<String> filter(List<String> in, String prefix) {
        List<String> out = new ArrayList<>();
        for (String x : in) if (x.toLowerCase().startsWith(prefix.toLowerCase())) out.add(x);
        return out;
    }
}
