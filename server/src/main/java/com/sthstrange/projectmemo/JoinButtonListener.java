package com.sthstrange.projectmemo;

import fr.xephi.authme.events.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * 进服按钮：AuthMe 登录成功后发送，格式 1:1 复刻 PlusBridge CreativeLoginListener（不花哨）。
 * 与「进创造服」按钮的差异仅：对全体玩家显示（memo.use 默认 true）。
 */
public final class JoinButtonListener implements Listener {

    @EventHandler
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("memo.use")) return;
        Component hint = Component.text("[备忘录] ", NamedTextColor.AQUA)
                .append(Component.text("打开工程备忘录 →  ", NamedTextColor.GRAY))
                .append(ChatUI.btn("工程总览", "/memo open", "打开工程备忘录（装了模组则弹出界面）"))
                .append(Component.text("   "))
                .append(ChatUI.btn("服务器地标", "/memo landmarks", "点击查看服务器地标"))
                .append(Component.text("   "))
                .append(ChatUI.btn("机器使用手册", "/memo manual", "点击查看机器使用手册"));
        player.sendMessage(hint);
    }
}
