package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric API 兼容垫片（NeoForge 侧）。
 *
 * <p>共享源码（{@code ../client/src/main/java} 里的 MemoNetworking / MemoClientState）
 * 直接调用 {@code ClientPlayNetworking.canSend/send}。本类提供同名同包的这两个成员并转发到
 * NeoForge 官方网络 API，使共享源码 <b>一行都不用改</b> 就能编到 NeoForge 上。
 *
 * <p>本文件是奇怪服自写实现，<b>未复制 Fabric API 源码</b>。
 * 同一客户端不可能同时存在两个 ClientPlayNetworking（Fabric 版 / NeoForge 版是两次独立构建的两个 jar）。
 *
 * <p>若上游新增了本类未覆盖的 Fabric API 用法，NeoForge 侧会立刻编译报错（不会静默漂移），
 * 在此补一个方法即可。守门脚本 client-neoforge/source_check.py 也会提前把这类漂移报出来。
 */
public final class ClientPlayNetworking {

    private static final Logger LOG = LoggerFactory.getLogger("ProjectMemo");

    /** 调试日志开关：默认关闭（发布版静默）；测试时加 JVM 参数 -Dprojectmemo.debug=true。 */
    private static final boolean DEBUG = Boolean.getBoolean("projectmemo.debug");

    private ClientPlayNetworking() { }

    /** 语义：处于服务端会话中即可发送（NeoForge 的发送路径本身不设通道门禁）。 */
    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        net.minecraft.client.multiplayer.ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return false;
        }
        // 对端是否认这个通道 —— 必须门禁，否则会出大问题：
        // NeoForge 服务端收到"未协商/未注册"的 modded payload 会直接 disconnect
        // （NetworkRegistry.handleModdedPayload: "unknown or unaccepted channel; disconnecting"）。
        // 用 hasChannel 判断同时覆盖两种情况：
        //   · 对端是 NeoForge 服：只有协商通过（或对方 c:register 声明）才发；
        //   · 对端是 Paper/vanilla：服务端插件用 registerOutgoingPluginChannel 声明过通道就走 ad-hoc 通道 → true。
        // 实测（2026-09-12 测试服）：Paper + memo-server 下 hasChannel=true（connectionType=OTHER），
        // 因此 Paper 路径不受影响，而"连别的 NeoForge 服"不会再乱发 payload 导致被踢。
        return net.neoforged.neoforge.network.registration.NetworkRegistry
                .hasChannel(listener, type.id());
    }

    public static void send(CustomPacketPayload payload) {
        if (DEBUG) LOG.info("[memo] C2S {}", tag(String.valueOf(payload)));
        ClientPacketDistributor.sendToServer(payload);
    }

    private static String tag(String s) {
        int i = s.indexOf("\"t\":\"");
        if (i < 0) {
            return "?";
        }
        int j = s.indexOf('"', i + 5);
        return j < 0 ? "?" : s.substring(i + 5, j);
    }
}
