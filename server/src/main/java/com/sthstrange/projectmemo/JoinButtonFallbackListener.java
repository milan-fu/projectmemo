package com.sthstrange.projectmemo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** AuthMe 不存在时的兜底（测试环境可能没有 AuthMe 的场景）。 */
public final class JoinButtonFallbackListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPermission("memo.use")) return;
        Component hint = Component.text("[备忘录] ", NamedTextColor.AQUA)
                .append(Component.text("打开工程备忘录 →  ", NamedTextColor.GRAY))
                .append(ChatUI.btn("工程总览", "/memo open", "打开工程备忘录（装了模组则弹出界面）"))
                .append(Component.text("   "))
                .append(ChatUI.btn("服务器地标", "/memo landmarks", "点击查看服务器地标"))
                .append(Component.text("   "))
                .append(ChatUI.btn("机器使用手册", "/memo manual", "点击查看机器使用手册"));
        event.getPlayer().sendMessage(hint);
    }
}
