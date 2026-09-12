package com.sthstrange.projectmemo.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.sthstrange.projectmemo.client.L10n;
import com.sthstrange.projectmemo.client.MemoClientState;
import com.sthstrange.projectmemo.client.MemoHome;
import com.sthstrange.projectmemo.client.MemoNetworking;
import com.sthstrange.projectmemo.client.MemoPayload;
import com.sthstrange.projectmemo.client.MemoToast;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 客户端接线：与 Fabric 版 MemoClientMod 一一对应。
 *
 * <p>这是 <b>唯一</b> 需要长期跟着 Fabric 入口走的地方（上游改快捷键/事件时同步这里）。
 * 上游改共享逻辑（MemoNetworking / MemoClientState 等）时本类无需改动。
 */
@EventBusSubscriber(modid = ProjectMemoNeoForge.MOD_ID, value = Dist.CLIENT)
public final class ProjectMemoNeoForgeClient {

    private static final Logger LOG = LoggerFactory.getLogger("ProjectMemo");

    /** 调试日志开关：默认关闭（发布版静默）；测试时加 JVM 参数 -Dprojectmemo.debug=true。 */
    private static final boolean DEBUG = Boolean.getBoolean("projectmemo.debug");

    private static final Identifier TOAST_LAYER =
            Identifier.fromNamespaceAndPath(ProjectMemoNeoForge.MOD_ID, "toast");
    private static final Identifier KEYS_CATEGORY_ID =
            Identifier.fromNamespaceAndPath(ProjectMemoNeoForge.MOD_ID, "keys");

    private static KeyMapping.Category keysCategory;
    private static KeyMapping openKey;

    private ProjectMemoNeoForgeClient() { }

    // ================= mod 事件总线 =================

    /** 对应 Fabric: PayloadTypeRegistry.playC2S/playS2C().register(...) + registerGlobalReceiver(...) */
    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // 注意：NeoForge 不允许同一 payload 分别 playToServer/playToClient 注册两次
        // （NetworkRegistry: "Cannot register payload ... as it is already registered"），
        // 必须一次性 playBidirectional —— 这是与 Fabric（playC2S/playS2C 各自注册）的关键差异。
        event.registrar("1").optional()
                .playBidirectional(MemoPayload.TYPE, MemoPayload.CODEC,
                        (payload, context) -> {
                            // 客户端从不处理上行载荷
                        },
                        (payload, context) -> context.enqueueWork(() -> {
                            if (DEBUG) LOG.info("[memo] S2C {}", tag(payload.json()));
                            MemoNetworking.onPacket(payload.json());
                        }));
    }

    /** 对应 Fabric: KeyBindingHelper.registerKeyBinding(...) */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        keysCategory = new KeyMapping.Category(KEYS_CATEGORY_ID);
        event.registerCategory(keysCategory);
        openKey = new KeyMapping("key.projectmemo.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J, keysCategory);
        event.register(openKey);
        if (DEBUG) LOG.info("[memo] keymapping registered: {}/{}", keysCategory, openKey.getName());
    }

    /** 对应 Fabric: HudRenderCallback.EVENT.register((graphics, delta) -> MemoToast.render(graphics)) */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(TOAST_LAYER, (graphics, delta) -> MemoToast.render(graphics));
        if (DEBUG) LOG.info("[memo] gui layer registered: {}", TOAST_LAYER);
    }

    // ================= 游戏事件总线 =================

    /** 对应 Fabric: ClientTickEvents.END_CLIENT_TICK（hello 重试 + 快捷键开面板） */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        MemoNetworking.tick();
        if (openKey == null) {
            return;
        }
        while (openKey.consumeClick()) {
            if (DEBUG) LOG.info("[memo] open key pressed (ready={}, player={})",
                    MemoClientState.isReady(), Minecraft.getInstance().player != null);
            if (MemoClientState.isReady() && Minecraft.getInstance().player != null) {
                MemoHome.openHome();
            } else {
                MemoToast.push(L10n.get("projectmemo.toast.notConnected"), MemoToast.GOLD);
            }
        }
    }

    /** 对应 Fabric: ClientPlayConnectionEvents.JOIN */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft.getInstance().execute(MemoNetworking::onJoin);
    }

    /** 对应 Fabric: ClientPlayConnectionEvents.DISCONNECT */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft.getInstance().execute(MemoNetworking::onDisconnect);
    }

    // ================= 调试辅助 =================

    /** 只打印协议类型（hello/init/ack/sync/open_gui），避免刷屏。 */
    private static String tag(String json) {
        int i = json.indexOf("\"t\":\"");
        if (i < 0) {
            return "?";
        }
        int j = json.indexOf('"', i + 5);
        return j < 0 ? "?" : json.substring(i + 5, j);
    }
}
