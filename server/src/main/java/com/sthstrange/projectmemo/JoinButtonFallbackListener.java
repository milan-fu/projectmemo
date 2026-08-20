package com.sthstrange.projectmemo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
                .append(Component.text("点击这里", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/memo open"))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("工程备忘录 · 点击查看", NamedTextColor.GRAY))))
                .append(Component.text(" 打开工程备忘录 →", NamedTextColor.GRAY));
        event.getPlayer().sendMessage(hint);
    }
}
