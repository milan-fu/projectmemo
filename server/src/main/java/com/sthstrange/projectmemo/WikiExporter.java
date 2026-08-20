package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.Project;
import com.sthstrange.projectmemo.MemoData.Task;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * wiki 导出器（M4 v2，2026-08-19 服主定稿页面结构）：把 data.json 渲染成 Markdown，
 * **只写** plugins/ProjectMemo/wiki-export/。
 * 页面：index.md 总览（规划中/进行中/已完成 三分类，归档并入已完成）
 *      + p<id>.md 每工程详情（信息/说明/选址/子任务/搭建说明/参与玩家）。
 * 材料相关内容一律不上 wiki；无活动页。
 * 时机：插件 enable 一次 + 数据变更防抖（默认 60s，可配）+ 停服兜底。不碰 WSL/mkdocs（P3 同步链接管）。
 */
public final class WikiExporter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ProjectMemoPlugin plugin;
    private final File outDir;
    private BukkitTask pending;

    public WikiExporter(ProjectMemoPlugin plugin, File dataDir) {
        this.plugin = plugin;
        this.outDir = new File(dataDir, "wiki-export");
    }

    public boolean enabled() {
        return !plugin.isMirror() && plugin.getConfig().getBoolean("wiki-export", false);
    }

    private long debounceTicks() {
        return Math.max(5, plugin.getConfig().getInt("wiki-export-debounce-sec", 60)) * 20L;
    }

    /** 变更后防抖调度（60s 内多次变更只导出一次） */
    public synchronized void scheduleDebounced() {
        if (!enabled()) return;
        if (pending != null) pending.cancel();
        pending = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            synchronized (WikiExporter.this) { pending = null; }
            export();
        }, debounceTicks());
    }

    /** 全量导出：index.md 总览 + p<id>.md 详情；清理一切残留旧页（含旧 slug 命名与已废弃的 activities.md） */
    public synchronized void export() {
        if (!enabled()) return;
        try {
            if (!outDir.exists() && !outDir.mkdirs()) {
                plugin.getLogger().warning("wiki-export: 无法创建目录 " + outDir.getAbsolutePath());
                return;
            }
            MemoData d = plugin.getStore().data();
            write("index.md", renderIndex(d));
            List<String> detailNames = new ArrayList<>();
            for (Project pr : d.projects) {
                String name = "p" + pr.id + ".md";
                write(name, renderDetail(d, pr));
                detailNames.add(name);
            }
            File[] existing = outDir.listFiles((dir, n) -> n.endsWith(".md") && !"index.md".equals(n));
            if (existing != null) for (File f : existing)
                if (!detailNames.contains(f.getName()) && !f.delete())
                    plugin.getLogger().warning("wiki-export: 清理失败 " + f.getName());
            plugin.getLogger().info("wiki-export: 已导出 " + d.projects.size() + " 个工程到 " + outDir.getName() + "/");
        } catch (Exception e) {
            plugin.getLogger().warning("wiki-export 导出失败: " + e);
        }
    }

    private void write(String name, String content) throws Exception {
        Files.write(new File(outDir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String fmtDate(long epoch) {
        if (epoch <= 0) return "—";
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate().format(FMT);
    }

    /** wiki 三分类口径：归档并入已完成（服主拍板） */
    private static String statusCn(String status) {
        switch (status) {
            case "active": return "进行中";
            case "planning": return "规划中";
            case "completed": case "archived": return "已完成";
            default: return status;
        }
    }

    private static String mdEscape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    // ───────────────────────── index.md ─────────────────────────

    private String renderIndex(MemoData d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 工程备忘录\n\n");
        sb.append("> 本页由 ProjectMemo 自动生成，请勿手动编辑。\n\n");
        long active = 0, planning = 0, completed = 0;
        for (Project pr : d.projects) {
            if ("active".equals(pr.status)) active++;
            else if ("planning".equals(pr.status)) planning++;
            else completed++; // completed + archived
        }
        sb.append("共 ").append(d.projects.size()).append(" 个工程 · 进行中 ").append(active)
                .append(" · 规划中 ").append(planning).append(" · 已完成 ").append(completed).append("\n");
        renderSection(sb, d, "active", "进行中");
        renderSection(sb, d, "planning", "规划中");
        renderDoneSection(sb, d);
        return sb.toString();
    }

    private void renderSection(StringBuilder sb, MemoData d, String status, String label) {
        List<Project> list = new ArrayList<>();
        for (Project pr : d.projects) if (status.equals(pr.status)) list.add(pr);
        if (list.isEmpty()) return;
        list.sort(Comparator.comparingInt(pr -> pr.id));
        sb.append("\n## ").append(label).append("\n\n");
        sb.append("| # | 工程 | 创建者 | 开始 | 子任务 |\n|---|------|--------|------|--------|\n");
        for (Project pr : list) {
            List<Task> ts = d.tasksOf(pr.id);
            long done = ts.stream().filter(t -> "done".equals(t.status)).count();
            sb.append("| ").append(pr.id)
                    .append(" | [").append(mdEscape(pr.title)).append("](p").append(pr.id).append(".md)")
                    .append(" | ").append(mdEscape(pr.creator))
                    .append(" | ").append(fmtDate(pr.createdAt))
                    .append(" | ").append(done).append("/").append(ts.size())
                    .append(" |\n");
        }
    }

    private void renderDoneSection(StringBuilder sb, MemoData d) {
        List<Project> list = new ArrayList<>();
        for (Project pr : d.projects)
            if ("completed".equals(pr.status) || "archived".equals(pr.status)) list.add(pr);
        if (list.isEmpty()) return;
        list.sort(Comparator.comparingInt(pr -> pr.id));
        sb.append("\n## 已完成\n\n");
        sb.append("| # | 工程 | 创建者 | 开始 | 竣工 |\n|---|------|--------|------|------|\n");
        for (Project pr : list) {
            sb.append("| ").append(pr.id)
                    .append(" | [").append(mdEscape(pr.title)).append("](p").append(pr.id).append(".md)")
                    .append(" | ").append(mdEscape(pr.creator))
                    .append(" | ").append(fmtDate(pr.createdAt))
                    .append(" | ").append(fmtDate(pr.completedAt))
                    .append(" |\n");
        }
    }

    // ───────────────────────── p<id>.md ─────────────────────────

    private String renderDetail(MemoData d, Project pr) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(pr.title).append("\n\n");
        sb.append("- 编号 #").append(pr.id).append(" · 状态：**").append(statusCn(pr.status)).append("**\n");
        sb.append("- 创建：").append(pr.creator).append(" · ").append(fmtDate(pr.createdAt)).append(" 开始");
        if (("completed".equals(pr.status) || "archived".equals(pr.status)) && pr.completedAt > 0)
            sb.append(" · ").append(fmtDate(pr.completedAt)).append(" 竣工");
        sb.append("\n");
        sb.append("- 管理者：").append(pr.managers.isEmpty() ? "—" : mdEscape(String.join("、", pr.managers)));
        sb.append("\n");

        if (!pr.desc.isEmpty()) sb.append("\n## 说明\n\n").append(pr.desc.trim()).append("\n");

        sb.append("\n## 选址\n\n");
        if (pr.locWorld.isEmpty()) {
            sb.append("（未选址）\n");
        } else if (pr.locHidden) {
            sb.append("（坐标已隐藏）\n");
        } else {
            sb.append(pr.locWorld).append(" ").append(pr.locX).append(", ").append(pr.locY).append(", ").append(pr.locZ);
            if (!pr.locNote.isEmpty()) sb.append("（").append(pr.locNote).append("）");
            sb.append("\n");
        }

        List<Task> ts = new ArrayList<>(d.tasksOf(pr.id));
        ts.sort(Comparator.comparingInt(t -> t.sort));
        long done = ts.stream().filter(t -> "done".equals(t.status)).count();
        boolean hasCollect = ts.stream().anyMatch(t -> "collect".equals(t.type));
        sb.append("\n## 子任务（").append(done).append("/").append(ts.size()).append("）\n\n");
        if (ts.isEmpty()) sb.append("（暂无）\n");
        else {
            if (hasCollect) sb.append("> 注：🧺 前缀 = 材料收集任务（材料收齐后核验自动完成）\n\n");
            for (Task t : ts) {
                sb.append("- ");
                String mark = "collect".equals(t.type) ? "🧺 " : "";
                if ("done".equals(t.status)) {
                    sb.append(mark).append(t.title)
                            .append(" —— 完成：").append(t.assignee.isEmpty() ? "—" : t.assignee)
                            .append("（").append(fmtDate(t.doneAt)).append("）");
                } else if ("claimed".equals(t.status)) {
                    sb.append(mark).append(t.title).append(" —— 认领：").append(t.assignee);
                } else {
                    sb.append(mark).append(t.title).append(" —— 待认领");
                }
                sb.append("\n");
            }
        }

        if (!pr.buildNote.isEmpty()) sb.append("\n## 搭建说明\n\n").append(pr.buildNote.trim()).append("\n");

        sb.append("\n---\n");
        if (!pr.participants.isEmpty()) sb.append("参与玩家：").append(String.join("、", pr.participants)).append(" ｜ ");
        sb.append("[← 返回总览](index.md)\n");
        return sb.toString();
    }
}
