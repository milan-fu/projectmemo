# 第三方组件声明 / Third-Party Notices

ProjectMemo 本体以 **MIT** 许可发布（见 [LICENSE](LICENSE)）。本仓库与发布的插件 jar 包含以下第三方组件，分两类：

## 1. 内嵌进发布的插件 jar（shade）

| 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|
| org.json (json) | 20231013 | JSON License（含 "The Software shall be used for Good, not Evil" 条款，**非 OSI 认证的自由许可**） | <https://github.com/stleary/JSON-java> |
| Adventure text-serializer-plain | 4.26.1 | MIT | <https://github.com/KyoriPowered/adventure> |

## 2. 随仓库分发、仅编译期引用（`lib/`，不内嵌进发布 jar）

| 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|
| Leaves API | — | GPLv3 | <https://github.com/LeavesMC/Leaves> |
| AuthMe | 6.0.0 | GPLv3 | <https://github.com/AuthMe/AuthMeReloaded> |
| PlaceholderAPI | 2.12.3 | GPL-3.0 | <https://github.com/PlaceholderAPI/PlaceholderAPI> |
| Adventure api / key | 4.26.1 | MIT | <https://github.com/KyoriPowered/adventure> |
| Gson | 2.10.1 | Apache-2.0 | <https://github.com/google/gson> |
| BungeeCord chat | 1.21-R0.2 | BSD-3-Clause | <https://github.com/SpigotMC/BungeeCord> |
| Kyori examination-api | 1.3.0 | MIT | <https://github.com/KyoriPowered/examination> |

这些 jar 位于仓库根目录 `lib/`，随仓库提供以便离线构建；每个文件的 SHA-256、大小与来源见 [`lib/README.md`](lib/README.md)。GPLv3 组件仅作编译期 classpath 引用，**不会被内嵌、也不会被再许可为 MIT**；再分发或构建时请遵守各自许可。

## 客户端模组

客户端仅依赖 **Fabric API**（MIT，FabricMC）；未引用 malilib 或任何 GPL 代码，UI 为手写实现。
