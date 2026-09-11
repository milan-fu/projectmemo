package com.sthstrange.projectmemo.arcmenu;

import com.sthstrange.projectmemo.ProjectMemoPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PAPI 扩展：注册 %projectmemo_*% 占位符（memo-server 1.2.0-arcmenu）。
 * 只读查表，不做 IO；表由 {@link Slots} 在数据变更时重建。
 */
public final class ArcMenuPlaceholders extends PlaceholderExpansion {

    private final ProjectMemoPlugin plugin;
    private final Slots slots;
    private final ArcMenuBridge bridge;

    public ArcMenuPlaceholders(ProjectMemoPlugin plugin, Slots slots, ArcMenuBridge bridge) {
        this.plugin = plugin;
        this.slots = slots;
        this.bridge = bridge;
    }

    @Override
    public String getIdentifier() {
        return "projectmemo";
    }

    @Override
    public String getAuthor() {
        return "sth-strange";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (slots == null) return "";
        // 打开/刷新页面时会批量求值占位符 —— 借此刷新数据（含 MCDR 地标库的变化）
        if (bridge != null) bridge.rebuildIfStale();
        return slots.resolve(params, player);
    }
}
