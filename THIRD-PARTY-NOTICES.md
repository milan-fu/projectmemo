# 第三方组件声明 / Third-Party Notices

服务端插件 jar 内 **shade（内嵌分发）** 的依赖：

- **org.json** (json-20231013) — JSON License：
  <https://github.com/stleary/JSON-java>
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software ... The Software shall be used for Good, not Evil.
- **Adventure text-serializer-plain / api / key** (4.26.1, KyoriPowered) — MIT License：
  <https://github.com/KyoriPowered/adventure>

**仅编译期引用、不随 jar 分发**（运行时由服务器/玩家客户端提供）：
Leaves/Paper API、Gson（服务器内置）、AuthMe API（可选进服按钮钩子）、examination-api、bungeecord-chat。

客户端模组仅依赖 **Fabric API**（MIT，FabricMC）；未引用 malilib 或任何 GPL 代码，UI 为手写实现。
