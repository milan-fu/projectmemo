package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.arcmenu.ArcMenuBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * ProjectMemo (memo-server) —— 奇怪服工程备忘录，生存服唯一写入口。
 * role: single（测试/生产前置）| writer（预留 Redis 镜像发布）| mirror（创造服只读，M5）。
 * M0 = 数据模型 + Action 层 + 聊天点击 UI + 命令 + 进服按钮 + 输入截获 + 审计。
 */
public final class ProjectMemoPlugin extends JavaPlugin {

    private MemoStore store;
    private MemoActions actions;
    private ChatUI ui;
    private MemoChannel channel;
    private WikiExporter wiki;
    private MemoRedis redis;
    private LocationsReader locations;   // v1.2.0：LocationMarker 路标库只读
    private LocSync locSync;             // v1.2.0：选址 → !!loc 同步
    private ArcMenuBridge arcMenu;       // v1.2.0-arcmenu：ArcMenu 只读展示接入层
    private File importsDir;
    private int dailyLimit = 2;
    private String role = "single";
    private boolean qqEvents = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        File dir = getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            getLogger().severe("无法创建数据目录 " + dir.getAbsolutePath());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        store = new MemoStore(this, dir);
        if (isMirror()) store.initEmpty();   // mirror 数据全来自 Redis 快照，不读本地 data.json
        else store.load();
        actions = new MemoActions(this);
        ui = new ChatUI(this);

        channel = new MemoChannel(this);
        channel.register();

        wiki = new WikiExporter(this, dir);
        if (wiki.enabled()) wiki.export();

        redis = new MemoRedis(this);
        if (redis.enabled()) {
            if (isMirror()) redis.startMirror(this::applyMirrorSnapshot, this::applyRemoteStats);
            else if ("writer".equals(role)) redis.startWriter();
        }


        locations = new LocationsReader(this);
        locSync = new LocSync(this);
        getLogger().info("地标库: " + locations.file().getPath()
                + " | loc-sync=" + getConfig().getString("loc-sync.mode", "socket")
                + (isMirror() ? "（mirror 不同步选址）" : ""));

        arcMenu = new ArcMenuBridge(this);
        arcMenu.start();

        importsDir = new File(dir, "imports");
        if (!importsDir.exists()) {
            if (importsDir.mkdirs()) getLogger().info("投影导入目录: " + importsDir.getAbsolutePath());
        }

        PluginCommand cmd = getCommand("memo");
        if (cmd != null) {
            MemoCommand mc = new MemoCommand(this);
            cmd.setExecutor(mc);
            cmd.setTabCompleter(mc);
        }

        PluginCommand uiCmd = getCommand("m");
        if (uiCmd != null) {
            // /m 由本插件声明（这样才会出现在客户端 tab 补全里）；实际交给 ArcMenu 打开新版菜单。
            uiCmd.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("[ProjectMemo] /m 只能由玩家使用");
                    return true;
                }
                if (!player.hasPermission("memo.use")) {
                    player.sendMessage("[ProjectMemo] 你没有使用备忘录的权限");
                    return true;
                }
                // ArcMenu 用 legacy commandMap 注册入口命令，命名空间可能是 "arcmenu:" 也可能没有，
                // 两种写法都试一次（performCommand 找不到命令会返回 false）。
                if (!player.performCommand("memoui") && !player.performCommand("arcmenu:memoui")) {
                    player.sendMessage("[ProjectMemo] 新版菜单不可用（ArcMenu 未启用或未加载菜单）");
                }
                return true;
            });
        }

        if (getConfig().getBoolean("join-button", true)) {
            boolean authMe = false;
            try {
                Class.forName("fr.xephi.authme.events.LoginEvent");
                authMe = getServer().getPluginManager().getPlugin("AuthMe") != null;
            } catch (Throwable ignored) { }
            if (authMe) {
                getServer().getPluginManager().registerEvents(new JoinButtonListener(), this);
                getLogger().info("进服按钮: AuthMe LoginEvent 模式");
            } else {
                getServer().getPluginManager().registerEvents(new JoinButtonFallbackListener(), this);
                getLogger().info("进服按钮: PlayerJoinEvent 兜底模式（未检测到 AuthMe）");
            }
        }

        int minutes = Math.max(1, getConfig().getInt("save-interval-min", 5));
        getServer().getScheduler().runTaskTimer(this, () -> { if (!isMirror()) store.save(); },
                minutes * 60L * 20L, minutes * 60L * 20L);

        getLogger().info("ProjectMemo 已启用 role=" + role + " 每日限额=" + dailyLimit
                + " payload通道=" + MemoChannel.CHANNEL
                + "（数据: " + store.data().projects.size() + " 工程）");
    }

    @Override
    public void onDisable() {
        if (arcMenu != null) arcMenu.stop();
        if (redis != null) redis.stop();
        if (wiki != null) wiki.export(); // 停服前保证 wiki-export 最新（未启用时为空操作）
        if (store != null && !isMirror()) store.save();
        getLogger().info("ProjectMemo 已停用（data.json 已保存）");
    }

    public void loadConfigValues() {
        dailyLimit = Math.max(1, getConfig().getInt("daily-project-limit", 2));
        role = getConfig().getString("role", "single");
        qqEvents = getConfig().getBoolean("qq-events", true);
    }

    public MemoStore getStore() { return store; }

    public MemoActions getActions() { return actions; }

    public ChatUI getUi() { return ui; }

    public MemoChannel getChannel() { return channel; }

    public WikiExporter getWiki() { return wiki; }

    public LocationsReader getLocations() { return locations; }

    public LocSync getLocSync() { return locSync; }

    public ArcMenuBridge getArcMenu() { return arcMenu; }

    /** 数据变更钩子（MemoActions 每次写盘后调用）：驱动 wiki 导出防抖 + writer 发布快照 */
    public void onDataChanged() {
        if (arcMenu != null) arcMenu.refresh();      // v1.2.0-arcmenu：重建占位符槽位表
        if (wiki != null) wiki.scheduleDebounced();
        if (redis != null && redis.enabled() && "writer".equals(role))
            redis.publishAsync(getStore().data().toJson().toString());
    }

    public boolean isMirror() { return "mirror".equals(role); }

    /** Redis 是否启用（StatsTopProvider 用它判断要不要发布榜单）。 */
    public boolean isRedisEnabled() { return redis != null && redis.enabled(); }

    /** writer：把排行榜快照发布到 Redis（创造服镜像读取）。 */
    public void publishStats(String statsJson) {
        if (redis != null) redis.publishStatsAsync(statsJson);
    }

    /** mirror：收到 Redis 推送的排行榜速照 → 交给 StatsTopProvider（创造服显示主服榜单）。 */
    private void applyRemoteStats(String json) {
        if (arcMenu != null && arcMenu.stats() != null) {
            org.bukkit.Bukkit.getScheduler().runTask(this, () -> arcMenu.stats().applyRemote(json));
        }
    }

    /** mirror 收到 Redis 快照（redis 线程回调）→ 主线程应用：隐藏选址对所有人脱敏 + 全量同步在线客户端 */
    public void applyMirrorSnapshot(String json) {
        if (getServer() == null) return;
        getServer().getScheduler().runTask(this, () -> {
            try {
                MemoData d = MemoData.fromJson(new org.json.JSONObject(json));
                for (MemoData.Project pr : d.projects) {
                    if (!pr.locHidden) continue;
                    // 隐藏选址在镜像服对所有人隐藏：坐标/备注/投影原点/收集区域全部清零
                    pr.locX = pr.locY = pr.locZ = 0;
                    pr.locNote = "";
                    pr.soWorld = ""; pr.soX = pr.soY = pr.soZ = 0;
                    // depWorld 保留非空：客户端靠它区分「未设置」与「已设置但随选址隐藏」；只清零坐标值
                    pr.depX1 = pr.depY1 = pr.depZ1 = pr.depX2 = pr.depY2 = pr.depZ2 = 0;
                }
                boolean first = !store.hasSnapshot();
                store.replace(d);
                if (channel != null) channel.broadcastSync();
                if (first) getLogger().info("镜像快照已应用: " + d.projects.size() + " 工程");
            } catch (Exception e) {
                getLogger().warning("镜像快照应用失败: " + e);
            }
        });
    }

    public File getImportsDir() { return importsDir; }

    /** Leaves 内置共享原理图库（syncmatica）：server 根目录/syncmatics */
    public File getSyncmaticsDir() {
        File pluginsDir = getDataFolder().getParentFile();          // plugins/
        File serverDir = pluginsDir == null ? null : pluginsDir.getParentFile(); // server 根
        return new File(serverDir == null ? new File(".") : serverDir, "syncmatics");
    }

    public int getDailyLimit() { return dailyLimit; }

    public boolean isQqEvents() { return qqEvents; }

    public String getRole() { return role; }
}