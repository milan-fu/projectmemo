package com.sthstrange.projectmemo;

import com.sthstrange.projectmemo.MemoData.Project;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 选址 → LocationMarker 同步（v1.2.0，服主拍板：写进真 loc list，条目此后归 !!loc 管理）。
 * 通道 = 主机 remote_console(MCDR 插件) TCP 127.0.0.1:25999，一行一条 UTF-8 命令，console 身份执行。
 * 仅 writer/single 触发，mirror 永不触发。mode: socket（生产）| dry-run（测试服只记日志）| off。
 * 生命周期：选址设定/修改/重命名（非隐藏）→ del旧+add新；隐藏/清空/删工程 → del（防坐标泄露）；竣工不动。
 * 备注标记「工程#<id> · ProjectMemo 自动收录」供地标视图跳转识别；locSyncedName 记账防误删。
 */
public final class LocSync {

    /** 备注全文 = 工程#<id> + 此后缀 */
    public static final String DESC_SUFFIX = " · ProjectMemo 自动收录";

    private final ProjectMemoPlugin plugin;

    public LocSync(ProjectMemoPlugin plugin) { this.plugin = plugin; }

    private String mode() {
        String m = plugin.getConfig().getString("loc-sync.mode", "socket");
        return m == null ? "socket" : m.trim().toLowerCase();
    }

    public boolean active() { return !plugin.isMirror() && !"off".equals(mode()); }

    public String descMark(int projectId) { return "工程#" + projectId + DESC_SUFFIX; }

    /** 选址设定/修改/标题改名后调用（主线程）：非隐藏 → del旧+add新；隐藏或无选址 → 仅 del 旧条目 */
    public void onLocationChanged(Project pr) {
        if (!active()) return;
        final boolean want = !pr.locHidden && !pr.locWorld.isEmpty();
        final String oldName = pr.locSyncedName == null ? "" : pr.locSyncedName;
        final int id = pr.id;
        final String title = pr.title;
        final int dim = LocationsReader.worldToDim(pr.locWorld);   // 主线程取（Bukkit.getWorld）
        final int x = pr.locX, y = pr.locY, z = pr.locZ;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String name = sanitize(title);
            try {
                if (!oldName.isEmpty()) send("!!loc del " + quote(oldName));
                if (!want) { setSynced(id, ""); return; }
                send("!!loc add " + quote(name) + " " + x + " " + y + " " + z + " " + dim + " " + descMark(id));
                setSynced(id, name);
                // 3 秒回验：重名时 LocationMarker 拒绝添加（「已存在」只回 console）→ 改名 <标题>#<id> 重试一次
                Thread.sleep(3000L);
                if (!verified(id)) {
                    String alt = name + "#" + id;
                    plugin.getLogger().info("[LocSync] 路标「" + name + "」未落库（可能重名），改用「" + alt + "」重试");
                    send("!!loc add " + quote(alt) + " " + x + " " + y + " " + z + " " + dim + " " + descMark(id));
                    setSynced(id, alt);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("[LocSync] 同步失败 #" + id + " 《" + title + "》: " + e
                        + "（可 !!loc list 核对后手动补）");
            }
        });
    }

    /** 选址隐藏/清空、工程删除（主线程调用） */
    public void onRemove(Project pr) {
        if (!active()) return;
        final String oldName = pr.locSyncedName == null ? "" : pr.locSyncedName;
        if (oldName.isEmpty()) return;
        final int id = pr.id;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                send("!!loc del " + quote(oldName));
                setSynced(id, "");
            } catch (Exception e) {
                plugin.getLogger().warning("[LocSync] 删除路标失败 #" + id + " " + oldName + ": " + e
                        + "（可 !!loc del 手动清理）");
            }
        });
    }

    /** 回验：locations.json 里是否已有带本工程标记的条目 */
    private boolean verified(int projectId) {
        if ("dry-run".equals(mode())) return true;   // dry-run 不真发命令，跳过回验避免误重试
        for (LocationsReader.Landmark lm : plugin.getLocations().readFresh())
            if (lm.projectId() == projectId) return true;
        return false;
    }

    /** 主线程回写 locSyncedName 并存盘（只落盘，不触发 onDataChanged：与 wiki/redis 无关） */
    private void setSynced(int projectId, String name) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Project pr = plugin.getStore().data().projectById(projectId);
            if (pr == null) return;   // 工程已被删除：无需记账
            pr.locSyncedName = name == null ? "" : name;
            plugin.getStore().save();
        });
    }

    private void send(String cmd) throws Exception {
        if ("dry-run".equals(mode())) {
            plugin.getLogger().info("[LocSync][dry-run] " + cmd);
            return;
        }
        String host = plugin.getConfig().getString("loc-sync.remote-console-host", "127.0.0.1");
        int port = plugin.getConfig().getInt("loc-sync.remote-console-port", 25999);
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 2000);
                s.setSoTimeout(2000);
                OutputStream out = s.getOutputStream();
                out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("send failed");
    }

    /** 路标名清理：双引号→单引号（QuotableText 安全）、去换行、截断 30 字 */
    static String sanitize(String title) {
        String s = (title == null ? "" : title).replace('"', '\'').replace("\n", " ").trim();
        if (s.length() > 30) s = s.substring(0, 30);
        return s.isEmpty() ? "未命名工程" : s;
    }

    private static String quote(String name) { return "\"" + name + "\""; }
}
