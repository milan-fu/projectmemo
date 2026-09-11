package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.ProjectMemoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * ArcMenu 接入层总控（memo-server 1.2.0-arcmenu）。
 *
 * 职责：配置开关、依赖探测（ArcMenu / PlaceholderAPI）、PAPI 扩展注册、槽位索引与菜单文件生命周期、
 * 菜单重载调度。纯只读展示层：不写 data.json、不改变 memo 既有行为；依赖缺失时自动停用并打日志。
 */
public final class ArcMenuBridge {

    private final ProjectMemoPlugin plugin;
    private Slots slots;
    private StatsTopProvider stats;
    private MenuExporter exporter;
    private ReloadScheduler reloader;
    private ArcMenuPlaceholders expansion;
    private BukkitTask pendingExport;
    private boolean enabled;
    private String disabledReason = "";

    /** 最近一次重建槽位表的时间（用于“打开页面时刷新”的节流）。 */
    private long lastRebuildAt;

    /**
     * 占位符求值时调用：距上次重建超过 refreshSec 就重建。
     * 菜单在打开/刷新时会求值占位符，所以这等价于“每次打开页面刷新数据”，
     * 又不会为没人看的页面做无用功。
     */
    public void rebuildIfStale() {
        Slots s = this.slots;
        if (s == null) return;
        long now = System.currentTimeMillis();
        int sec = Math.max(1, plugin.getConfig().getInt("arcmenu.slots-refresh-sec", 5));
        if (now - lastRebuildAt < sec * 1000L) return;
        lastRebuildAt = now;
        s.rebuild();
    }

    public ArcMenuBridge(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
    }

    /** 插件启用时调用（主线程）。 */
    public void start() {
        if (!plugin.getConfig().getBoolean("arcmenu.enabled", true)) {
            disabledReason = "arcmenu.enabled=false";
            plugin.getLogger().info("ArcMenu 接入层未启用（配置关闭）");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("ArcMenu") == null) {
            disabledReason = "未安装 ArcMenu";
            plugin.getLogger().info("ArcMenu 接入层未启用（未安装 ArcMenu）");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            disabledReason = "未安装 PlaceholderAPI";
            plugin.getLogger().info("ArcMenu 接入层未启用（未安装 PlaceholderAPI）");
            return;
        }

        this.stats = new StatsTopProvider(plugin);
        stats.start();
        Slots built = new Slots(plugin, stats);
        built.rebuild();
        this.lastRebuildAt = System.currentTimeMillis();
        try {
            ArcMenuPlaceholders reg = new ArcMenuPlaceholders(plugin, built, this);
            if (!reg.register()) {
                disabledReason = "PlaceholderAPI 扩展注册被拒";
                plugin.getLogger().warning("ArcMenu 接入层未启用（PAPI 扩展注册被拒）");
                return;
            }
            this.expansion = reg;
        } catch (Throwable t) {
            disabledReason = "注册异常: " + t;
            plugin.getLogger().warning("ArcMenu 接入层启动失败: " + t);
            return;
        }
        this.slots = built;
        this.exporter = new MenuExporter(plugin);
        int written = exporter.export();
        this.reloader = new ReloadScheduler(plugin);
        reloader.start();
        this.enabled = true;
        plugin.getLogger().info("ArcMenu 接入层已启用 · " + built.summary()
                + " · 菜单 " + written + " 个 → " + exporter.dir().getName() + "/");
    }

    /** 插件停用时调用。 */
    public void stop() {
        if (pendingExport != null) {
            pendingExport.cancel();
            pendingExport = null;
        }
        if (reloader != null) reloader.stop();
        if (stats != null) stats.stop();
        if (expansion != null) {
            try {
                expansion.unregister();
            } catch (Throwable ignored) {
                // 关服阶段忽略
            }
            expansion = null;
        }
        enabled = false;
    }

    /** 数据变更后：重建槽位表 + 防抖重建菜单文件 + 标脏等待重载窗口。 */
    public void refresh() {
        Slots current = this.slots;
        if (current != null) current.rebuild();
        this.lastRebuildAt = System.currentTimeMillis();
        if (exporter == null) return;
        if (pendingExport != null) pendingExport.cancel();
        int debounce = Math.max(1, plugin.getConfig().getInt("arcmenu.export-debounce-sec", 5));
        pendingExport = Bukkit.getScheduler().runTaskLater(plugin, this::exportNow, 20L * debounce);
    }

    /** 立即重建菜单文件（返回文件数）；不触发 ArcMenu 重载。 */
    public int exportNow() {
        pendingExport = null;
        if (exporter == null) return 0;
        int written = exporter.export();
        if (reloader != null) reloader.markDirty();
        return written;
    }

    /** 管理员手动重载（跳过窗口保护）。 */
    public void reloadNow() {
        if (reloader != null) reloader.forceReload("管理员手动");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Slots slots() {
        return slots;
    }

    public StatsTopProvider stats() {
        return stats;
    }

    public MenuExporter exporter() {
        return exporter;
    }

    public ReloadScheduler reloader() {
        return reloader;
    }

    public String disabledReason() {
        return disabledReason;
    }

    /** /memo arcmenu status 用的一行状态。 */
    public String statusLine() {
        if (!enabled) {
            return "ArcMenu 接入层：未启用" + (disabledReason.isEmpty() ? "" : "（" + disabledReason + "）");
        }
        StringBuilder b = new StringBuilder();
        b.append("ArcMenu 接入层：已启用 · ").append(slots.summary());
        b.append(" · 菜单目录 ").append(exporter.dir().getName());
        b.append(reloader.isDirty()
                ? " · 待重载（已 " + reloader.dirtySeconds() + "s）"
                : " · 无待重载");
        return b.toString();
    }
}
