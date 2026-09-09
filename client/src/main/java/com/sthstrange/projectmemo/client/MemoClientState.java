package com.sthstrange.projectmemo.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 客户端运行时状态：注册/权限位/数据镜像/nonce/ack 回调。
 * 仅在客户端主线程访问（网络收包统一 client.execute 回主线程）。
 */
public final class MemoClientState {

    public static final int PROTOCOL_VERSION = 1;
    private static final Gson GSON = new Gson();

    private static MemoData data = new MemoData();
    private static boolean ready;          // 已收到 init/sync（= 服务器装了 memo-server）
    private static boolean op;
    private static boolean canCreate;
    private static int quotaLeft;
    private static boolean mirror;     // 连着镜像服（创造服）
    private static boolean readOnly;   // 服务端要求全只读（镜像：认领/收集等也禁）
    private static String serverVersion = "";          // v1.2.0：perms.sv（旧服务端缺省 ""）
    private static boolean capsManual, capsLandmarks;  // v1.2.0：能力位（旧服务端全 false → UI 降级隐藏）
    private static final Set<Integer> managed = new HashSet<>();
    private static long dataVersion;       // init/sync/断线时递增，屏幕据此刷新
    /** 材料页投影来源筛选（会话内）：null=全部，set=勾选的来源集合 */
    private static final Map<Integer, Set<String>> matFilters = new java.util.HashMap<>();
    public static Set<String> getMatFilter(int projectId) { return matFilters.get(projectId); }
    public static void setMatFilter(int projectId, Set<String> f) {
        if (f == null) matFilters.remove(projectId); else matFilters.put(projectId, f);
    }

    /** 材料页认领玩家筛选（会话内）：null=全部，set=勾选的认领人（"§unclaimed"=未认领） */
    private static final Map<Integer, Set<String>> matClaimFilters = new java.util.HashMap<>();
    public static Set<String> getMatClaimFilter(int projectId) { return matClaimFilters.get(projectId); }
    public static void setMatClaimFilter(int projectId, Set<String> f) {
        if (f == null) matClaimFilters.remove(projectId); else matClaimFilters.put(projectId, f);
    }

    /** 材料页展开状态（会话内按工程保存）：展开显示来源明细的物品 id 集合 */
    private static final Map<Integer, Set<String>> matExpanded = new java.util.HashMap<>();
    public static Set<String> getMatExpanded(int projectId) {
        return matExpanded.computeIfAbsent(projectId, k -> new java.util.HashSet<>());
    }

    /** 子任务页筛选（会话内按工程保存）：0=全部 1=已认领 2=已完成 3=未认领 */
    private static final Map<Integer, Integer> taskFilters = new java.util.HashMap<>();
    public static int getTaskFilter(int projectId) { Integer v = taskFilters.get(projectId); return v == null ? 0 : v; }
    public static void setTaskFilter(int projectId, int v) { taskFilters.put(projectId, v); }

    /** 材料页排序/单位（会话内按工程保存，跨页面不丢） */
    private static final Map<Integer, Integer> matSorts = new java.util.HashMap<>();
    private static final Map<Integer, Boolean> matUnits = new java.util.HashMap<>();
    public static int getMatSort(int projectId) { Integer v = matSorts.get(projectId); return v == null ? 0 : v; }
    public static void setMatSort(int projectId, int v) { matSorts.put(projectId, v); }
    public static boolean getMatUnitBoxes(int projectId) { Boolean v = matUnits.get(projectId); return v == null || v; }
    public static void setMatUnitBoxes(int projectId, boolean v) { matUnits.put(projectId, v); }

    private static final AtomicInteger nonceGen = new AtomicInteger(1);
    private static final Map<Integer, Consumer<JsonObject>> ackHandlers = new ConcurrentHashMap<>();

    private MemoClientState() { }

    public static MemoData data() { return data; }

    public static boolean isReady() { return ready; }

    public static boolean isOp() { return op; }

    public static boolean canCreate() { return canCreate; }

    public static int quotaLeft() { return quotaLeft; }

    public static boolean mirror() { return mirror; }

    public static boolean readOnly() { return readOnly; }

    public static String serverVersion() { return serverVersion; }

    /** 服务端支持「机器使用说明」收录（1.2.0+） */
    public static boolean capsManual() { return capsManual; }

    /** 服务端支持地标列表（1.2.0+，读本机 LocationMarker） */
    public static boolean capsLandmarks() { return capsLandmarks; }

    /** 服务端 isManagerOf 语义镜像：本工程管理者或 OP */
    public static boolean manages(int projectId) { return op || managed.contains(projectId); }

    public static long dataVersion() { return dataVersion; }

    public static void reset() {
        data = new MemoData();
        ready = false;
        op = false;
        canCreate = false;
        quotaLeft = 0;
        mirror = false;
        readOnly = false;
        serverVersion = "";
        capsManual = false;
        capsLandmarks = false;
        managed.clear();
        ackHandlers.clear();
        dataVersion++;
    }

    /** init/sync 快照应用：perms + data 全量替换 */
    public static void applySnapshot(JsonObject root) {
        if (root.has("perms") && root.get("perms").isJsonObject()) {
            JsonObject perms = root.getAsJsonObject("perms");
            op = MemoData.optBool(perms, "op");
            canCreate = MemoData.optBool(perms, "canCreate");
            quotaLeft = MemoData.optInt(perms, "quotaLeft");
            mirror = MemoData.optBool(perms, "mirror");
            readOnly = MemoData.optBool(perms, "readOnly");
            serverVersion = MemoData.optStr(perms, "sv", "");
            if (perms.has("caps") && perms.get("caps").isJsonObject()) {
                JsonObject caps = perms.getAsJsonObject("caps");
                capsManual = MemoData.optBool(caps, "manual");
                capsLandmarks = MemoData.optBool(caps, "landmarks");
            } else {
                capsManual = false;
                capsLandmarks = false;
            }
            managed.clear();
            for (String s : MemoData.optArr(perms, "managed")) {
                try { managed.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) { }
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            data = MemoData.parse(root.getAsJsonObject("data"));
        }
        ready = true;
        dataVersion++;
    }

    /** 发送 action；onAck 非空时回调（客户端主线程），否则走默认 toast。单人档走本地后端。 */
    public static void sendAction(String opName, JsonObject args, Consumer<JsonObject> onAck) {
        if (MemoLocal.isLocal()) {
            JsonObject ack = MemoLocal.handle(opName, args == null ? new JsonObject() : args);
            deliverAck(ack, onAck);
            return;
        }
        if (!ClientPlayNetworking.canSend(MemoPayload.TYPE)) {
            MemoToast.push(L10n.get("projectmemo.toast.noServer"), MemoToast.RED);
            return;
        }
        int nonce = nonceGen.getAndIncrement();
        if (onAck != null) ackHandlers.put(nonce, onAck);
        JsonObject o = new JsonObject();
        o.addProperty("t", "action");
        o.addProperty("nonce", nonce);
        o.addProperty("op", opName);
        o.add("args", args == null ? new JsonObject() : args);
        ClientPlayNetworking.send(new MemoPayload(GSON.toJson(o)));
    }

    public static void sendAction(String opName, JsonObject args) {
        sendAction(opName, args, null);
    }

    /** ack 处理：回调优先；无回调时 ok→绿色 toast（message 优先），失败→红色 error */
    public static void onAck(JsonObject ack) {
        int nonce = MemoData.optInt(ack, "nonce");
        deliverAck(ack, ackHandlers.remove(nonce));
    }

    private static void deliverAck(JsonObject ack, Consumer<JsonObject> handler) {
        boolean ok = MemoData.optBool(ack, "ok");
        String error = MemoData.optStr(ack, "error", null);
        String message = MemoData.optStr(ack, "message", null);
        if (handler != null) {
            try {
                handler.accept(ack);
                return;
            } catch (Exception e) {
                MemoToast.push(L10n.get("projectmemo.toast.internalError", e.getMessage()), MemoToast.RED);
                return;
            }
        }
        if (ok) {
            String shown = message == null || message.isEmpty() ? L10n.get("projectmemo.toast.done")
                    : (MemoLocal.isLocal() ? message : ServerErrors.translate(message));
            MemoToast.push(shown, MemoToast.GREEN);
        } else {
            // "找不到"多半是本地数据过期：静默请求一次全量同步自愈（同步到达后重试即可）——用原文判断
            if (error != null && error.contains("找不到") && !MemoLocal.isLocal()) {
                sendAction("request_sync", new JsonObject(), ack2 -> { });
            }
            String shown = error == null || error.isEmpty() ? L10n.get("projectmemo.toast.failed")
                    : (MemoLocal.isLocal() ? error : ServerErrors.translate(error));
            MemoToast.push(shown, MemoToast.RED);
        }
    }

    // ───────────────────────── args 便捷构造 ─────────────────────────

    public static JsonObject argsOf(String k1, Object v1) {
        JsonObject o = new JsonObject();
        put(o, k1, v1);
        return o;
    }

    public static JsonObject argsOf(String k1, Object v1, String k2, Object v2) {
        JsonObject o = argsOf(k1, v1);
        put(o, k2, v2);
        return o;
    }

    public static JsonObject argsOf(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        JsonObject o = argsOf(k1, v1, k2, v2);
        put(o, k3, v3);
        return o;
    }

    private static void put(JsonObject o, String key, Object v) {
        if (v instanceof Number n) o.addProperty(key, n);
        else if (v instanceof Boolean b) o.addProperty(key, b);
        else o.addProperty(key, v == null ? "" : String.valueOf(v));
    }
}
