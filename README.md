# ProjectMemo — 工程备忘录

为 Minecraft 生电/技术社区设计的**大工程协作备忘录**：立项、子任务、材料收集进度（真实容器核验）、投影导入、选址管理。

- **服务端插件**（Paper 系）是数据的唯一写入口；**客户端模组**（Fabric，masa 风格面板）是主编辑入口（快捷键 `J`）
- 不装模组的玩家通过聊天框 `/memo` **只读**查看
- **单人游戏也能用**：内置本地后端，数据随世界存档保存
- 可选 Redis 镜像模式：创造服/其他服务器**只读**同步全部数据

## 功能一览

- 工程管理：立项（可配每日限额）/ 规划↔进行中 / 竣工冻结 / 归档与解档 / 删除
- 子任务：自定义任务（认领/完成/代完/重开）+ 🧺 材料收集任务（任何玩家批量勾选材料认领）
- 材料收集：两角点划定收集区域，**[核验] 扫描区域内容器**（箱子/桶/潜影盒/漏斗，递归展开潜影盒与收纳袋）按实际数量更新进度，收齐自动完成收集任务
- 投影导入：解析 `.litematic` 自动生成材料行（支持服务器共享投影库 Syncmatica 与本地投影两种来源）
- 选址：坐标记录 / 隐藏（对非管理者隐藏，镜像服对所有人隐藏）/ 锁定防误改
- 权限：LuckPerms 节点（`memo.create` 等）+ 工程级管理者名单（数据驱动，无需权限节点）
- 导出与集成：`wiki-export/` Markdown 自动导出（供 wiki 同步链）、`[MEMO-EVENT]` 控制台事件行（供 QQ/Discord bot 播报立项/竣工/删除）、Redis writer/mirror 跨服只读镜像
- **服务器地标**：客户端「地标」页 / 聊天 `/memo landmarks` 只读浏览路标库（LocationMarker `!!loc`）；工程选址确认后自动收录为路标，隐藏选址 / 清空选址 / 删除工程时自动移除，条目此后由 `!!loc` 管理
- **机器使用说明**：工程信息页一键「加入使用说明」，已收录工程集中列在「使用说明」页 / `/memo manual`，点条目直达工程详情（正文 = 工程描述）；竣工后仍可收录/移出，归档后仍显示
- **无模组玩家**：进服提示与总览页提供 [工程总览] [服务器地标] [机器使用手册] 可点击入口，无需记命令
- 聊天框只读 UI（hover/点击/翻页）、审计日志（滚动 500 条）

## 安装

### 服务端插件（Paper / Leaves 1.21.x）

> 也可从 Hangar 直接下载：https://hangar.papermc.io/fudoghh/ProjectMemo

1. `ProjectMemo-1.2.0.jar` 放入 `plugins/`
2. 重启后编辑 `plugins/ProjectMemo/config.yml`（全部配置项见文件内注释）：
   - `role: single` 单服使用；`role: writer` + `redis` 配置 = 对外发布镜像的写端；`role: mirror` = 只读镜像端（如创造服）
   - `wiki-export: true` 开启 Markdown 导出；`qq-events: true` 开启事件日志行
   - `loc-sync.mode: socket` 开启「选址 → LocationMarker 路标库」自动同步（经 MCDR `remote_console`，默认 `127.0.0.1:25999`；无 MCDR 的环境可用 `dry-run` 只记日志或 `off` 关闭）；`locations-file` 指路标库路径（与 LocBridge 同约定）
3. LuckPerms 给需要的组授权（见下方权限表）

### 客户端模组（Fabric 1.21.11）

1. 安装 [Fabric Loader](https://fabricmc.net/) + [Fabric API](https://modrinth.com/mod/fabric-api)（**不需要 malilib**）
2. `projectmemo-client-1.2.0.jar` 放入 `.minecraft/versions/<版本>/mods/` 或 `.minecraft/mods/`（下载：[GitHub Releases](https://github.com/milan-fu/projectmemo/releases)，历史版本同页）
3. 进入安装了插件的服务器，按 **J** 打开面板
4. 单人游戏直接进入即可使用（本地模式，全功能）

## 权限节点

| 节点 | 默认 | 作用 |
|---|---|---|
| `memo.use` | 所有人 | 查看（无权限则命令不可见） |
| `memo.claim` | 所有人 | 认领子任务 |
| `memo.create` | 建议 creator 组 | 立项（受每日限额）/ 建自定义子任务 / 改任务标题说明 |
| `memo.delete` | OP | 删除工程 |
| `memo.archive` | OP | 归档 / 解档归档 |
| `memo.admin` | OP | 管理兜底 |

工程级**管理者**由数据名单驱动（创建者自动成为管理者，创建者/OP 可增减）：管理者可编辑该工程的一切（信息/选址/子任务/材料/核验/竣工/解档），无需任何权限节点。

## 构建

- **服务端**：JDK 21。`server/build.ps1`（javac + shade json/adventure-plain，依赖在 `server/lib/`；可用 `JAVA_HOME` 指定 JDK）
- **客户端**：Gradle 9.5+（fabric-loom），`cd client && gradle build`（需联网拉取 Minecraft/映射）

## 外部平台

- [MCMod 词条](https://www.mcmod.cn/class/30204.html)
- [MineBBS：客户端 mod](https://www.minebbs.com/resources/projectmemo-mod.17932/)
- [MineBBS：服务端插件（Leaves/Paper）](https://www.minebbs.com/resources/projectmemo-leves-x.17933/)

## 本地化

UI 已全量国际化：**简体中文 / 繁体中文 / 文言文**显示中文界面，其他语言自动使用英文——跟随 Minecraft 客户端语言设置，无需任何配置。服务端插件的消息由客户端内置映射表翻译（`ServerErrors`），服务端无需改动。

## 已知限制

- **英文界面下部分按钮文案较长，可能出现溢出/重叠**（按钮宽度为硬编码像素，按中文排版设计）。不影响任何功能与数据，仅观感问题；中文界面排版完整。
- 未安装模组的玩家看到的聊天只读 UI、服务端 wiki 导出为中文（服务端能力）。
- 地标同步依赖 MCDR 的 `remote_console` 与 LocationMarker 插件；未安装 LocationMarker 时地标页为空、选址同步自动跳过（`loc-sync` 可关）。
- wiki 导出的多行描述/搭建说明使用 Markdown 硬换行（行尾两空格），与游戏内换行一致。

## 许可

[MIT](LICENSE)。第三方组件声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

# ProjectMemo (English)

A **project-coordination memo system** for technical Minecraft communities: project proposals, subtasks, material-collection progress verified against **real containers**, schematic (Litematica) imports, and site locations.

- **Server plugin** (Paper/Leaves 1.21.x) is the single source of truth; the **Fabric client mod** (masa-style panels, hotkey `J`) is the main editing UI. Mod-less players get a read-only chat UI (`/memo`).
- **Works in singleplayer** too: built-in local backend, data stored inside the world save.
- Optional **Redis mirror mode**: other servers (e.g. a creative server) can run read-only mirrors with live sync.
- **Fully localized UI**: Simplified/Traditional Chinese and Classical Chinese show the Chinese interface; all other languages automatically use English (follows the Minecraft client language, zero config). Server plugin messages are translated client-side via a built-in mapping table — no server-side changes needed.

**Highlights in 1.2.0**: server landmarks view (client tab / `/memo landmarks`) reading the LocationMarker `!!loc` waypoint library, with project sites auto-registered as waypoints (and auto-removed when the site is hidden/cleared or the project is deleted); machine usage manual (one-click "add to manual" on a project's info page, listed on the Manual tab / `/memo manual`, entry jumps to the project whose description is the manual body); mod-less players get clickable [Overview] [Server Landmarks] [Usage Manual] entries in chat.

**Known limitations**: in the English UI some buttons may overflow or overlap (button widths are hard-coded pixels designed for Chinese text). Purely cosmetic — no functional impact; the Chinese UI is pixel-perfect. Landmark sync requires MCDR's `remote_console` plus LocationMarker (without LocationMarker the landmark page is simply empty); wiki exports use Markdown hard breaks for multi-line descriptions.
- Integrations: Markdown wiki export, `[MEMO-EVENT]` console lines (project created/completed/deleted) for chat bots, LuckPerms permission nodes plus per-project manager lists (data-driven, no permission nodes needed).

**Install**: drop the jar into `plugins/` (server) or `mods/` (client, requires Fabric API; **malilib is NOT required**). Download jars from [GitHub Releases](https://github.com/milan-fu/projectmemo/releases); the server plugin is also on [Hangar](https://hangar.papermc.io/fudoghh/ProjectMemo). See config comments in `plugins/ProjectMemo/config.yml` for `role`/`redis`/`wiki-export`/`qq-events`.

**External pages**: [MCMod](https://www.mcmod.cn/class/30204.html) · [MineBBS client mod](https://www.minebbs.com/resources/projectmemo-mod.17932/) · [MineBBS server plugin (Leaves/Paper)](https://www.minebbs.com/resources/projectmemo-leves-x.17933/).

**License**: [MIT](LICENSE) — see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for bundled dependencies.
