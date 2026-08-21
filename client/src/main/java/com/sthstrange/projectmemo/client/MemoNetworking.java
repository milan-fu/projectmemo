package com.sthstrange.projectmemo.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** 收包分发与 hello 生命周期（协议 v1，与服务端 MemoChannel 对齐）。 */
public final class MemoNetworking {

    private static final int HELLO_MAX_ATTEMPTS = 10;
    private static final long HELLO_INTERVAL_MS = 3000;
    private static long nextHelloAt;
    private static int helloAttempts;

    private MemoNetworking() { }

    /** 进入 play 阶段：单人档走本地后端；多人服重置状态并 hello（服务端回 init） */
    public static void onJoin() {
        MemoClientState.reset();
        MemoHome.reset();
        helloAttempts = 0;
        nextHelloAt = System.currentTimeMillis() + HELLO_INTERVAL_MS;
        if (Minecraft.getInstance().isLocalServer()) {
            MemoLocal.enterLocal();
            return;
        }
        if (ClientPlayNetworking.canSend(MemoPayload.TYPE)) {
            sendHello();
        }
    }

    /** 客户端 tick：init 未到达时每 3 秒重发 hello（最多 10 次）。
     *  容错 AuthMe 登录延迟/进服瞬间丢包等握手时序问题；服务端 hello 幂等（重发=重发 init）。 */
    public static void tick() {
        if (MemoLocal.isLocal() || MemoClientState.isReady()) return;
        if (Minecraft.getInstance().player == null) return;
        if (!ClientPlayNetworking.canSend(MemoPayload.TYPE)) return;
        long now = System.currentTimeMillis();
        if (now < nextHelloAt || helloAttempts >= HELLO_MAX_ATTEMPTS) return;
        helloAttempts++;
        nextHelloAt = now + HELLO_INTERVAL_MS;
        sendHello();
    }

    private static void sendHello() {
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        hello.addProperty("v", MemoClientState.PROTOCOL_VERSION);
        ClientPlayNetworking.send(new MemoPayload(hello.toString()));
    }

    public static void onDisconnect() {
        MemoLocal.exitLocal();
        MemoClientState.reset();
        helloAttempts = 0;
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
