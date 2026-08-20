package com.sthstrange.projectmemo;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * data.json 存储层：UTF-8 无 BOM，原子写（临时文件 + move），供 QQ/wiki/镜像多方并发读。
 */
public final class MemoStore {

    private final File file;
    private final ProjectMemoPlugin plugin;
    private MemoData data;
    private boolean snapshotApplied;   // mirror：是否已应用过快照（用于首次日志）

    public MemoStore(ProjectMemoPlugin plugin, File dir) {
        this.plugin = plugin;
        this.file = new File(dir, "data.json");
    }

    public synchronized MemoData data() {
        return data;
    }

    /** mirror：空数据起步，等待 Redis 快照（不读不写本地 data.json） */
    public synchronized void initEmpty() {
        data = new MemoData();
        plugin.getLogger().info("mirror 模式：数据等待 Redis 快照");
    }

    /** mirror：整体替换内存数据（不落盘——镜像服无本地数据所有权） */
    public synchronized void replace(MemoData d) {
        data = d;
        snapshotApplied = true;
    }

    public synchronized boolean hasSnapshot() { return snapshotApplied; }

    public synchronized void load() {
        if (!file.exists()) {
            data = new MemoData();
            save();
            plugin.getLogger().info("data.json 不存在，已初始化: " + file.getAbsolutePath());
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            JSONObject root = new JSONObject(new JSONTokener(in));
            data = MemoData.fromJson(root);
            plugin.getLogger().info("已加载 data.json: " + data.projects.size() + " 工程 / "
                    + data.tasks.size() + " 子任务 / " + data.materials.size() + " 材料行");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "data.json 解析失败，启用空数据并保留原文件", e);
            File bak = new File(file.getParentFile(), "data.json.bad-" + System.currentTimeMillis());
            try { Files.copy(file.toPath(), bak.toPath()); } catch (IOException ignored) { }
            data = new MemoData();
        }
    }

    /** 原子存盘：写临时文件 → move 覆盖（QQ bot/wiki 导出依赖此原子性） */
    public synchronized void save() {
        if (data == null) return;
        try {
            File tmp = new File(file.getParentFile(), "data.json.tmp");
            byte[] bytes = data.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
                try { out.getFD().sync(); } catch (IOException ignored) { }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "data.json 存盘失败", e);
        }
    }
}
