package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.MaterialRow;
import com.sthstrange.projectmemo.MemoData.Project;
import com.sthstrange.projectmemo.MemoData.Task;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径 B（v0.5 定稿）：聊天框 = **只读简版备忘录**（服主拍板：修改必须走客户端模组）。
 * 列表/详情可看可翻页；一切修改操作在 memo-client 模组内完成，命令仅管理员兜底。
 */
public final class ChatUI {

    private static final NamedTextColor PREFIX_C = NamedTextColor.AQUA;
    private static final NamedTextColor DIM = NamedTextColor.GRAY;
    private static final NamedTextColor BTN = NamedTextColor.GOLD;
    private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_SHORT = DateTimeFormatter.ofPattern("MM-dd");

    private final ProjectMemoPlugin plugin;

    public ChatUI(ProjectMemoPlugin plugin) { this.plugin = plugin; }

    private MemoData d() { return plugin.getStore().data(); }

    public static Component prefix() {
        return Component.text("[备忘录] ", PREFIX_C);
    }

    private static Component line() {
        return Component.text("──────────────────────────────", NamedTextColor.DARK_GRAY);
    }

    private static void pageBreak(Player p) {
        p.sendMessage(Component.empty());
        p.sendMessage(Component.empty());
    }

    /** 按物品实际堆叠上限换算：盒=27组，组=该物品堆叠上限（剪刀=1、雪球=16、多数=64） */
    public static String fmtAmount(long n, int stackSize) {
        if (n < 0) n = 0;
        if (stackSize <= 1) return n + " 个";
        long boxSize = 27L * stackSize;
        long box = n / boxSize, rem = n % boxSize;
        long stack = rem / stackSize, piece = rem % stackSize;
        StringBuilder sb = new StringBuilder();
        if (box > 0) sb.append(box).append(" 盒 ");
        if (stack > 0 || box > 0) sb.append(stack).append(" 组 ");
        sb.append(piece).append(" 个");
        return sb.toString().trim();
    }

    public static String fmtAmount(long n) { return fmtAmount(n, 64); }

    private static int stackOf(String itemId) {
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(itemId);
        int s = mat == null ? 64 : mat.getMaxStackSize();
        return s <= 0 ? 64 : s;
    }

    public static String fmtDate(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT_DAY);
    }

    private static String fmtDateShort(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT_SHORT);
    }

    /** 可点击按钮（[标签] 金色，runCommand + hover）；进服提示等外部入口复用 */
    public static Component btn(String label, String cmd, String hover) {
        return Component.text(" [" + label + "]", BTN)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(hover, DIM)));
    }

    private static Component copyBtn(String label, String value, String hover) {
        return Component.text(" [" + label + "]", BTN)
                .clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(HoverEvent.showText(Component.text(hover + "（复制到剪贴板）", DIM)));
    }

    private static String sym(String status) {
        switch (status) {
            case "active": return "◉";
            case "planning": return "◐";
            case "completed": return "○";
            case "archived": return "▣";
            default: return "?";
        }
    }

    private static NamedTextColor symColor(String status) {
        switch (status) {
            case "active": return NamedTextColor.GREEN;
            case "planning": return NamedTextColor.YELLOW;
            case "completed": return NamedTextColor.GRAY;
            default: return NamedTextColor.DARK_GRAY;
        }
    }

    private static String statusCn(String status) {
        switch (status) {
            case "active": return "进行中";
            case "planning": return "规划中";
            case "completed": return "已完成";
            case "archived": return "已归档";
            default: return status;
        }
    }

    private static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private boolean isMgr(Project pr, Player p) {
        return pr.isManagerUuid(p.getUniqueId().toString()) || p.hasPermission("memo.admin") || p.isOp();
    }

    // ───────────────────────── 总览（只读） ─────────────────────────

    public void sendOverview(Player p, int page) {
        List<Project> all = new ArrayList<>();
        for (Project pr : d().projects) if (!"archived".equals(pr.status)) all.add(pr);
        all.sort((a, b) -> {
            int wa = weight(a.status), wb = weight(b.status);
            if (wa != wb) return Integer.compare(wa, wb);
            return Long.compare(b.createdAt, a.createdAt);
        });
        int perPage = 8;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, pages));

        long active = all.stream().filter(x -> "active".equals(x.status)).count();
        long planning = all.stream().filter(x -> "planning".equals(x.status)).count();
        long completed = all.stream().filter(x -> "completed".equals(x.status)).count();

        pageBreak(p);
        p.sendMessage(prefix().append(Component.text("工程总览 · 进行中 " + active
                + " · 规划 " + planning + " · 已完成 " + completed, NamedTextColor.WHITE)));
        p.sendMessage(line());
        if (all.isEmpty()) p.sendMessage(Component.text("  还没有工程。", DIM));
        for (int i = (page - 1) * perPage; i < Math.min(page * perPage, all.size()); i++) {
            Project pr = all.get(i);
            List<Task> ts = d().tasksOf(pr.id);
            long done = ts.stream().filter(t -> "done".equals(t.status)).count();
            Component row = Component.text((i + 1) + ". ", DIM)
                    .append(Component.text(sym(pr.status) + " ", symColor(pr.status)))
                    .append(Component.text(trunc(pr.title, 14), NamedTextColor.WHITE))
                    .append(Component.text("  " + pr.creator + " · " + fmtDateShort(pr.createdAt) + " 开始", DIM))
                    .append(Component.text("  任务 " + done + "/" + ts.size(), DIM))
                    .append(Component.text("  材料 " + materialPct(pr.id) + "%", DIM));
            Component hover = Component.text("《" + pr.title + "》 " + statusCn(pr.status), NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("创建: " + pr.creator + " · " + fmtDate(pr.createdAt), DIM))
                    .append(Component.newline())
                    .append(Component.text("管理者: " + String.join("、", pr.managers), DIM))
                    .append(Component.newline())
                    .append(Component.text(trunc(pr.desc.isEmpty() ? "（无描述）" : pr.desc, 60), DIM));
            row = row.append(btn("详情", "/memo show " + pr.id, "点击查看工程详情"));
            p.sendMessage(row.hoverEvent(HoverEvent.showText(hover)));
        }
        p.sendMessage(line());
        Component footer = Component.empty();
        if (pages > 1) {
            if (page > 1) footer = footer.append(btn("上一页", "/memo list " + (page - 1), "上一页"));
            footer = footer.append(Component.text("  " + page + "/" + pages + "  ", DIM));
            if (page < pages) footer = footer.append(btn("下一页", "/memo list " + (page + 1), "下一页"));
        }
        if (footer != Component.empty()) p.sendMessage(footer);
        p.sendMessage(Component.text("📌 立项、认领、修改等操作请安装 ProjectMemo 客户端模组（masa 风格界面）。", NamedTextColor.DARK_GRAY));
        // 无 mod 玩家入口：点击直达各页（v1.2.0 rev4 聊天 UI 改版）
        p.sendMessage(btn("服务器地标", "/memo landmarks", "点击查看服务器地标")
                .append(Component.text("   "))
                .append(btn("机器使用手册", "/memo manual", "点击查看机器使用手册")));
    }

    private int weight(String status) {
        switch (status) {
            case "active": return 0;
            case "planning": return 1;
            case "completed": return 2;
            default: return 3;
        }
    }

    private int materialPct(int projectId) {
        long need = 0, delivered = 0;
        for (MaterialRow m : d().materialsOf(projectId)) { need += m.need; delivered += Math.min(m.delivered, m.need); }
        if (need == 0) return 0;
        return (int) (delivered * 100 / need);
    }

    // ───────────────────────── 详情（只读） ─────────────────────────

    public void sendDetail(Player p, Project pr) {
        boolean mgr = isMgr(pr, p);

        pageBreak(p);
        p.sendMessage(prefix()
                .append(Component.text("《" + pr.title + "》 ", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(sym(pr.status) + " " + statusCn(pr.status), symColor(pr.status)))
                .append(btn("返回总览", "/memo list", "返回工程总览")));
        p.sendMessage(Component.text("创建 " + pr.creator + " · " + fmtDate(pr.createdAt) + " 开始"
                + ("completed".equals(pr.status) ? " · " + fmtDate(pr.completedAt) + " 竣工" : ""), DIM));
        p.sendMessage(Component.text("管理者: " + String.join("、", pr.managers), DIM));
        if (!pr.desc.isEmpty()) p.sendMessage(Component.text("说明: " + pr.desc, DIM));
        if (pr.inManual) p.sendMessage(Component.text("✓ 已收录于机器使用手册（/memo manual）", NamedTextColor.GREEN)); // v1.2.0

        boolean canSeeLoc = !pr.locHidden || (mgr && !plugin.isMirror()); // 镜像服隐藏选址对所有人隐藏
        if (!pr.locWorld.isEmpty() && canSeeLoc) {
            p.sendMessage(Component.text("选址: " + pr.locWorld + " " + pr.locX + ", " + pr.locY + ", " + pr.locZ
                    + (pr.locNote.isEmpty() ? "" : " (" + pr.locNote + ")"), DIM)
                    .append(copyBtn("复制坐标", pr.locX + " " + pr.locY + " " + pr.locZ, "点击复制坐标")));
        }
        if (!pr.soWorld.isEmpty() && canSeeLoc) {
            p.sendMessage(Component.text("投影原点: " + pr.soX + ", " + pr.soY + ", " + pr.soZ, DIM)
                    .append(copyBtn("复制", pr.soX + " " + pr.soY + " " + pr.soZ, "点击复制投影原点坐标")));
        }
        if (!pr.locWorld.isEmpty() && pr.locHidden && (!mgr || plugin.isMirror())) {
            p.sendMessage(Component.text("选址: （已隐藏）", DIM));
        }

        if ("completed".equals(pr.status) && !pr.participants.isEmpty())
            p.sendMessage(Component.text("参与玩家: " + String.join("、", pr.participants), NamedTextColor.YELLOW));

        // ── 子任务（只读） ──
        List<Task> ts = d().tasksOf(pr.id);
        long doneCount = ts.stream().filter(t -> "done".equals(t.status)).count();
        p.sendMessage(line());
        p.sendMessage(Component.text("子任务 " + doneCount + "/" + ts.size(), NamedTextColor.WHITE));
        if (ts.isEmpty()) p.sendMessage(Component.text("  （暂无子任务）", DIM));
        for (Task t : ts) {
            Component row;
            if ("done".equals(t.status)) {
                row = Component.text("▪ ", DIM).append(Component.text(trunc(t.title, 20), DIM, TextDecoration.STRIKETHROUGH))
                        .append(Component.text("  ✔ " + fmtDateShort(t.doneAt) + (t.assignee.isEmpty() ? "" : " · " + t.assignee), NamedTextColor.GREEN));
            } else if ("claimed".equals(t.status)) {
                row = Component.text("▪ ", DIM).append(Component.text(trunc(t.title, 20), NamedTextColor.YELLOW))
                        .append(Component.text("  已认领: " + t.assignee + " (" + fmtDateShort(t.claimedAt) + ")", DIM));
            } else {
                row = Component.text("▪ ", DIM).append(Component.text(trunc(t.title, 20), NamedTextColor.WHITE))
                        .append(Component.text("  待认领", DIM));
            }
            p.sendMessage(row);
        }

        // ── 材料（只读） ──
        List<MaterialRow> ms = d().materialsOf(pr.id);
        p.sendMessage(line());
        p.sendMessage(Component.text("材料收集 " + materialPct(pr.id) + "%", NamedTextColor.WHITE));
        if (ms.isEmpty()) p.sendMessage(Component.text("  （暂无材料行）", DIM));
        for (MaterialRow m : ms) {
            Component row = Component.text("▪ ", DIM)
                    .append(Component.text(trunc(m.itemName, 14), NamedTextColor.WHITE))
                    .append(Component.text("  " + fmtAmount(m.delivered, stackOf(m.item)) + " / " + fmtAmount(m.need, stackOf(m.item)), DIM))
                    .append(Component.text("  " + bar(m), NamedTextColor.DARK_GREEN));
            if (!m.claimedBy.isEmpty())
                row = row.append(Component.text("  认领: " + String.join("、", m.claimedBy), NamedTextColor.YELLOW));
            p.sendMessage(row);
        }

        if (!pr.buildNote.isEmpty())
            p.sendMessage(Component.text("搭建情况: " + pr.buildNote, NamedTextColor.DARK_AQUA));

        if (mgr) p.sendMessage(Component.text("（你是本工程管理者/OP：修改请用 ProjectMemo 客户端模组或 /memo 命令）", NamedTextColor.DARK_GRAY));
    }

    private String bar(MaterialRow m) {
        if (m.need <= 0) return "░░░░░░░░░░";
        int filled = (int) Math.min(10, m.delivered * 10 / m.need);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(i < filled ? '█' : '░');
        return sb.toString();
    }

    // ───────────────────────── 地标 / 使用手册（v1.2.0，只读） ─────────────────────────

    private static String dimCn(int dim) {
        switch (dim) {
            case 0: return "主世界";
            case -1: return "下界";
            case 1: return "末地";
            default: return "未知(" + dim + ")";
        }
    }

    private static int dimOrder(int dim) {
        switch (dim) {
            case 0: return 0;
            case -1: return 1;
            case 1: return 2;
            default: return 3;
        }
    }

    public void sendLandmarks(Player p, int page) {
        List<LocationsReader.Landmark> all = new ArrayList<>(plugin.getLocations().read());
        all.sort((a, b) -> {
            int da = dimOrder(a.dim), db = dimOrder(b.dim);
            if (da != db) return Integer.compare(da, db);
            return a.name.compareToIgnoreCase(b.name);
        });
        int perPage = 10;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(1, Math.min(page, pages));

        pageBreak(p);
        p.sendMessage(prefix().append(Component.text("服务器地标 · 共 " + all.size() + " 个（路标增删用 !!loc）", NamedTextColor.WHITE)));
        p.sendMessage(btn("工程总览", "/memo list", "返回工程总览")
                .append(Component.text("   "))
                .append(btn("机器使用手册", "/memo manual", "点击查看机器使用手册")));
        p.sendMessage(line());
        if (all.isEmpty())
            p.sendMessage(Component.text("  暂无地标：游戏内 !!loc add 添加；工程选址（非隐藏）会自动收录。", DIM));
        int lastDim = Integer.MIN_VALUE;
        for (int i = (page - 1) * perPage; i < Math.min(page * perPage, all.size()); i++) {
            LocationsReader.Landmark lm = all.get(i);
            if (lm.dim != lastDim) {
                lastDim = lm.dim;
                p.sendMessage(Component.text("▸ " + dimCn(lm.dim), NamedTextColor.AQUA));
            }
            Component name = Component.text(trunc(lm.name, 16), NamedTextColor.WHITE);
            if (lm.projectId() < 0 && !lm.desc.isEmpty())
                name = name.hoverEvent(HoverEvent.showText(Component.text(lm.desc, DIM)));
            Component row = Component.text("  ").append(name)
                    .append(Component.text("  " + lm.ix() + ", " + lm.iy() + ", " + lm.iz(), DIM))
                    .append(copyBtn("复制", lm.ix() + " " + lm.iy() + " " + lm.iz(), "复制坐标"));
            Project pr = lm.projectId() > 0 ? d().projectById(lm.projectId()) : null;
            if (pr != null) row = row.append(btn("详情", "/memo show " + pr.id, "查看工程详情（该地标由工程选址收录）"));
            else row = row.append(Component.text("  （暂无详情页）", NamedTextColor.DARK_GRAY));
            p.sendMessage(row);
        }
        p.sendMessage(line());
        if (pages > 1) {
            Component footer = Component.empty();
            if (page > 1) footer = footer.append(btn("上一页", "/memo landmarks " + (page - 1), "上一页"));
            footer = footer.append(Component.text("  " + page + "/" + pages + "  ", DIM));
            if (page < pages) footer = footer.append(btn("下一页", "/memo landmarks " + (page + 1), "下一页"));
            p.sendMessage(footer);
        }
    }

    public void sendManual(Player p) {
        List<Project> ms = new ArrayList<>();
        for (Project pr : d().projects) if (pr.inManual) ms.add(pr);   // v1.2.1：归档工程仍显示（服主定）
        ms.sort((a, b) -> Long.compare(
                b.completedAt > 0 ? b.completedAt : b.createdAt,
                a.completedAt > 0 ? a.completedAt : a.createdAt));
        pageBreak(p);
        p.sendMessage(prefix().append(Component.text("机器使用手册 · 共 " + ms.size() + " 篇（正文=工程说明）", NamedTextColor.WHITE)));
        p.sendMessage(btn("工程总览", "/memo list", "返回工程总览")
                .append(Component.text("   "))
                .append(btn("服务器地标", "/memo landmarks", "点击查看服务器地标")));
        p.sendMessage(line());
        if (ms.isEmpty())
            p.sendMessage(Component.text("  暂无收录：在工程信息页点「加入使用说明」（客户端模组）。", DIM));
        for (Project pr : ms) {
            p.sendMessage(Component.text(sym(pr.status) + " ", symColor(pr.status))
                    .append(Component.text(trunc(pr.title, 18), NamedTextColor.WHITE))
                    .append(Component.text("  " + pr.creator
                            + (pr.completedAt > 0 ? " · " + fmtDate(pr.completedAt) + " 竣工" : " · " + fmtDate(pr.createdAt) + " 开始"), DIM))
                    .append(btn("详情", "/memo show " + pr.id, "查看使用说明（工程说明）")));
        }
        p.sendMessage(line());
    }

    // ───────────────────────── 控制台纯文本 ─────────────────────────

    public void sendPlainOverview(CommandSender s) {
        s.sendMessage("== 工程备忘录（控制台视图） ==");
        for (Project pr : d().projects) {
            List<Task> ts = d().tasksOf(pr.id);
            long done = ts.stream().filter(t -> "done".equals(t.status)).count();
            s.sendMessage(String.format("#%d [%s] %s | 创建:%s %s | 任务 %d/%d | 材料 %d%%",
                    pr.id, statusCn(pr.status), pr.title, pr.creator, fmtDate(pr.createdAt),
                    done, ts.size(), materialPct(pr.id)));
        }
        if (d().projects.isEmpty()) s.sendMessage("（空）");
    }
}
