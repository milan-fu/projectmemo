package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.ProjectMemoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * 菜单重载调度器（memo-server 1.2.0-arcmenu · B 段）。
 *
 * 详情页是生成式文件，工程增删改后必须重载菜单才生效；而 /arcmenu reload 会关闭所有在线会话，
 * 因此这里做「脏标记 + 窗口执行」：默认等到没有玩家在线时执行，超过上限则强制执行。
 * 列表/地标/手册/排行榜走占位符，永远不触发本调度。
 */
public final class ReloadScheduler {

    private final ProjectMemoPlugin plugin;
    private BukkitTask task;
    private volatile long dirtySince;

    public ReloadScheduler(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int interval = Math.max(5, plugin.getConfig().getInt("arcmenu.reload-check-sec", 30));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * interval, 20L * interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 菜单文件已重建 → 标脏，等待合适窗口重载。 */
    public void markDirty() {
        if (dirtySince == 0) dirtySince = System.currentTimeMillis();
    }

    public boolean isDirty() {
        return dirtySince != 0;
    }

    public long dirtySeconds() {
        return dirtySince == 0 ? 0 : (System.currentTimeMillis() - dirtySince) / 1000;
    }

    private void tick() {
        if (dirtySince == 0) return;
        String guard = plugin.getConfig().getString("arcmenu.reload-guard", "no-players");
        if ("off".equals(guard)) return;
        int maxWaitMin = Math.max(1, plugin.getConfig().getInt("arcmenu.reload-max-wait-min", 30));
        boolean idle = Bukkit.getOnlinePlayers().isEmpty();
        boolean timeout = dirtySeconds() > maxWaitMin * 60L;
        if ("immediate".equals(guard) || idle || timeout) {
            forceReload(idle ? "无玩家在线" : (timeout ? "等待超时" : "immediate"));
        }
    }

    /** 立即重载（跳过窗口保护），管理员命令与超时路径都走这里。 */
    public void forceReload(String reason) {
        dirtySince = 0;
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "arcmenu reload");
        } catch (Throwable t) {
            ok = false;
            plugin.getLogger().warning("arcmenu: 菜单重载异常 " + t);
        }
        plugin.getLogger().info("arcmenu: 菜单重载（" + reason + "，成功=" + ok + "）");
    }
}
