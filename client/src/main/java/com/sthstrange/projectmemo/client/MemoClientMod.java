package com.sthstrange.projectmemo.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * ProjectMemo 客户端模组入口（纯客户端，无服务端组件）。
 * 1.20.5+ 自定义 payload 必须先注册类型（C2S/S2C 各自注册）。
 */
public final class MemoClientMod implements ClientModInitializer {

    public static final String MOD_ID = "projectmemo";

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(MemoPayload.TYPE, MemoPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MemoPayload.TYPE, MemoPayload.CODEC);

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "keys"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.projectmemo.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                category));

        ClientPlayNetworking.registerGlobalReceiver(MemoPayload.TYPE, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> MemoNetworking.onPacket(json));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(MemoNetworking::onJoin));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(MemoNetworking::onDisconnect));

        // 快捷键（默认 J——M 是投影的；可在 选项→按键 里改）：打开「设为首页」记住的页面
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (MemoClientState.isReady() && client.player != null) {
                    MemoHome.openHome();
                } else {
                    MemoToast.push(L10n.get("projectmemo.toast.notConnected"), MemoToast.GOLD);
                }
            }
        });

        HudRenderCallback.EVENT.register((graphics, delta) -> MemoToast.render(graphics));
    }

    public static KeyMapping openKey() { return openKey; }
}
