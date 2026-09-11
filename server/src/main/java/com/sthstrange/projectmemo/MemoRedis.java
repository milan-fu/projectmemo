package com.sthstrange.projectmemo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 最小 Redis 客户端（手写 RESP，零第三方依赖；熔断/重连语义照抄 PlusBridgeRedis：失败后最长 30s 退避，绝不阻塞主线程）。
 * writer（主服）：数据变更 → SET snapshotKey + PUBLISH channel（专用守护线程）。
 * mirror（创造服）：SUBSCRIBE channel + GET snapshotKey（订阅连接与读快照连接分离——Redis 订阅态连接只能跑订阅命令）；
 *                   收到消息/首连/10 分钟兜底 → 回调 onSnapshot。
 */
public final class MemoRedis {

    private final ProjectMemoPlugin plugin;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;
    private final String snapshotKey;

    private volatile boolean running;
    private Thread worker;

    /** writer：最新待发快照（只保留最新——全量快照语义，旧的无意义） */
    private volatile String pendingSnapshot;
    /** Redis 中排行榜快照的 key（writer 写 / mirror 读）。 */
    private final String statsKey;

    /** 待发送的排行榜快照。 */
    private volatile String pendingStats;

    /** writer：发布排行榜快照（异步、不阻塞主线程）。 */
    public void publishStatsAsync(String statsJson) {
        if (!enabled || !running) return;
        pendingStats = statsJson;
        synchronized (wakeLock) { wakeLock.notifyAll(); }
    }
    private final Object wakeLock = new Object();

    public MemoRedis(ProjectMemoPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("redis.enabled", false);
        this.host = plugin.getConfig().getString("redis.host", "127.0.0.1");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
        this.channel = plugin.getConfig().getString("redis.channel", "memo");
        this.snapshotKey = plugin.getConfig().getString("redis.snapshot-key", "memo:snapshot");
        this.statsKey = plugin.getConfig().getString("redis.stats-key", "memo:stats");
    }

    public boolean enabled() { return enabled; }

    public void stop() {
        running = false;
        synchronized (wakeLock) { wakeLock.notifyAll(); }
        if (worker != null) worker.interrupt();
    }

    // ───────────────────────── writer ─────────────────────────

    public void startWriter() {
        running = true;
        worker = new Thread(() -> writerLoop(), "ProjectMemo-Redis-Writer");
        worker.setDaemon(true);
        worker.start();
        plugin.getLogger().info("Redis writer 已启动: " + host + ":" + port + " channel=" + channel + " key=" + snapshotKey);
    }

    /** 主线程调用：数据变更后投递最新快照（异步发送，绝不阻塞） */
    public void publishAsync(String snapshotJson) {
        if (!enabled || !running) return;
        pendingSnapshot = snapshotJson;
        synchronized (wakeLock) { wakeLock.notifyAll(); }
    }

    private void writerLoop() {
        Conn conn = null;
        int failures = 0;
        while (running) {
            String json;
            String stats;
            synchronized (wakeLock) {
                while (running && pendingSnapshot == null && pendingStats == null) {
                    try { wakeLock.wait(5000); } catch (InterruptedException ignored) { }
                }
                json = pendingSnapshot;
                pendingSnapshot = null;
                stats = pendingStats;
                pendingStats = null;
            }
            if (!running || (json == null && stats == null)) continue;
            try {
                if (conn == null) conn = connect();
                if (json != null) {
                    conn.cmd("SET", snapshotKey, json);
                    Object r1 = conn.readReply();
                    if (r1 instanceof RespError) throw new IOException("SET 失败: " + ((RespError) r1).msg);
                }
                if (stats != null) {
                    conn.cmd("SET", statsKey, stats);
                    Object r3 = conn.readReply();
                    if (r3 instanceof RespError) throw new IOException("SET stats 失败: " + ((RespError) r3).msg);
                }
                conn.cmd("PUBLISH", channel, "update");
                Object r2 = conn.readReply();
                if (r2 instanceof RespError) throw new IOException("PUBLISH 失败: " + ((RespError) r2).msg);
                if (failures > 0) {
                    failures = 0;
                    plugin.getLogger().info("Redis writer 连接恢复");
                }
            } catch (Exception e) {
                closeQuiet(conn);
                conn = null;
                failures++;
                if (failures == 1 || failures % 10 == 0)
                    plugin.getLogger().warning("Redis writer 失败(" + failures + "): " + e.getMessage() + "，熔断退避后重试");
                if (pendingSnapshot == null && json != null) pendingSnapshot = json;
                if (pendingStats == null && stats != null) pendingStats = stats;
                try { Thread.sleep(Math.min(30000L, 2000L * failures)); } catch (InterruptedException ignored) { }
            }
        }
        closeQuiet(conn);
    }

    public void startMirror(Consumer<String> onSnapshot) {
        startMirror(onSnapshot, null);
    }

    /** mirror：onStats 非空时，同时把 Redis 里的排行榜快照回调出去（创造服读主服榜单）。 */
    public void startMirror(Consumer<String> onSnapshot, Consumer<String> onStats) {
        running = true;
        worker = new Thread(() -> mirrorLoop(onSnapshot, onStats), "ProjectMemo-Redis-Mirror");
        worker.setDaemon(true);
        worker.start();
        plugin.getLogger().info("Redis mirror 已启动: " + host + ":" + port + " channel=" + channel
                + " key=" + snapshotKey + (onStats == null ? "" : " + statsKey=" + statsKey));
    }

    private void mirrorLoop(Consumer<String> onSnapshot, Consumer<String> onStats) {
        int failures = 0;
        while (running) {
            Conn sub = null;
            try {
                sub = connect();
                sub.cmd("SUBSCRIBE", channel);
                Object confirm = sub.readReply(); // subscribe 确认
                if (confirm instanceof RespError) throw new IOException("SUBSCRIBE 失败: " + ((RespError) confirm).msg);
                failures = 0;
                // 首连立即拉一次快照（writer 未发布过时为空，等待后续消息）
                applyOnce(onSnapshot, onStats);
                long lastFetch = System.currentTimeMillis();
                sub.sock.setSoTimeout(60000);
                while (running) {
                    Object msg;
                    try {
                        msg = sub.readReply();
                    } catch (SocketTimeoutException te) {
                        // 10 分钟兜底重拉（防丢消息）
                        if (System.currentTimeMillis() - lastFetch > 600000L) {
                            if (applyOnce(onSnapshot, onStats)) lastFetch = System.currentTimeMillis();
                        }
                        continue;
                    }
                    if (msg instanceof List<?> l && l.size() >= 3 && "message".equals(l.get(0))) {
                        if (applyOnce(onSnapshot, onStats)) lastFetch = System.currentTimeMillis();
                    }
                }
            } catch (Exception e) {
                failures++;
                if (failures == 1 || failures % 10 == 0)
                    plugin.getLogger().warning("Redis mirror 连接失败(" + failures + "): " + e.getMessage() + "，熔断退避后重连");
                try { Thread.sleep(Math.min(30000L, 2000L * failures)); } catch (InterruptedException ignored) { }
            } finally {
                closeQuiet(sub);
            }
        }
    }

    /** 拉一次快照（+排行榜）并回调；返回是否成功 */
    private boolean applyOnce(Consumer<String> onSnapshot, Consumer<String> onStats) {
        boolean ok = true;
        String snap = fetchKey(snapshotKey);
        if (snap == null) {
            plugin.getLogger().warning("Redis mirror: 快照不存在或读取失败（writer 可能未发布）");
            ok = false;
        } else {
            try {
                onSnapshot.accept(snap);
            } catch (Exception e) {
                plugin.getLogger().warning("Redis mirror: 快照应用失败: " + e);
                ok = false;
            }
        }
        if (onStats != null) {
            String stats = fetchKey(statsKey);
            if (stats != null) {
                try {
                    onStats.accept(stats);
                } catch (Exception e) {
                    plugin.getLogger().warning("Redis mirror: 排行榜应用失败: " + e);
                }
            }
        }
        return ok;
    }

    /** 短连接 GET 指定 key（订阅态连接不能跑 GET） */
    private String fetchKey(String key) {
        Conn c = null;
        try {
            c = connect();
            c.cmd("GET", key);
            Object r = c.readReply();
            return r instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuiet(c);
        }
    }

    private Conn connect() throws IOException {
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        Conn c = new Conn(sock);
        if (password != null && !password.isEmpty()) {
            c.cmd("AUTH", password);
            Object r = c.readReply();
            if (r instanceof RespError) throw new IOException("AUTH 失败: " + ((RespError) r).msg);
        }
        return c;
    }

    private static void closeQuiet(Conn c) {
        if (c == null) return;
        try { c.sock.close(); } catch (Exception ignored) { }
    }

    private static final class RespError {
        final String msg;
        RespError(String msg) { this.msg = msg; }
    }

    private static final class Conn {
        final Socket sock;
        final OutputStream out;
        final InputStream in;

        Conn(Socket s) throws IOException {
            sock = s;
            out = s.getOutputStream();
            in = new BufferedInputStream(s.getInputStream());
        }

        void cmd(String... args) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                bos.write(('$' + Integer.toString(b.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                bos.write(b);
                bos.write('\r'); bos.write('\n');
            }
            out.write(bos.toByteArray());
            out.flush();
        }

        Object readReply() throws IOException {
            int t = in.read();
            if (t < 0) throw new IOException("连接已关闭");
            String line = readLine();
            switch ((char) t) {
                case '+': return line;
                case '-': return new RespError(line);
                case ':': return Long.parseLong(line);
                case '$': {
                    int len = Integer.parseInt(line);
                    if (len < 0) return null;
                    byte[] buf = new byte[len];
                    int off = 0;
                    while (off < len) {
                        int n = in.read(buf, off, len - off);
                        if (n < 0) throw new IOException("bulk 中途 EOF");
                        off += n;
                    }
                    in.read(); in.read(); // CRLF
                    return new String(buf, StandardCharsets.UTF_8);
                }
                case '*': {
                    int n = Integer.parseInt(line);
                    if (n < 0) return null;
                    List<Object> arr = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) arr.add(readReply());
                    return arr;
                }
                default: throw new IOException("未知 RESP 类型: " + (char) t);
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r') { in.read(); break; }
                bos.write(c);
            }
            if (c < 0) throw new IOException("EOF");
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
