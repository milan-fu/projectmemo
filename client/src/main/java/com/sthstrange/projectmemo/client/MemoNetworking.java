package com.sthstrange.projectmemo.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** 收包分发与 hello 生命周期（协议 v1，与服务端 MemoChannel 对齐）。 */
public final class MemoNetworking {

    private MemoNetworking() { }

    /** 进入 play 阶段：单人档走本地后端；多人服重置状态并 hello（服务端回 init） */
    public static void onJoin() {
        MemoClientState.reset();
        MemoHome.reset();
        if (Minecraft.getInstance().isLocalServer()) {
            MemoLocal.enterLocal();
            return;
        }
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("v", MemoClientState.PROTOCOL_VERSION);
        if (ClientPlayNetworking.canSend(MemoPayload.TYPE)) {
            ClientPlayNetworking.send(new MemoPayload(hello.toString()));
        }
    }

    public static void onDisconnect() {
        MemoLocal.exitLocal();
        MemoClientState.reset();
    }

    /** S2C 分发（已在客户端主线程） */
    public static void onPacket(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String t = MemoData.optStr(o, "t");
            switch (t) {
                case "init":
                case "sync":
                    MemoClientState.applySnapshot(o);
                    break;
                case "ack":
                    MemoClientState.onAck(o);
                    break;
                case "open_gui":
                    Minecraft.getInstance().setScreen(new MemoMainScreen());
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            MemoToast.push(L10n.get("projectmemo.toast.dataParseError", e.getMessage()), MemoToast.RED);
        }
    }
}
