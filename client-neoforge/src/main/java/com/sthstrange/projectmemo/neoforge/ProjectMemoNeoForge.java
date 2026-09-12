package com.sthstrange.projectmemo.neoforge;

import net.neoforged.fml.common.Mod;

/**
 * ProjectMemo 客户端模组（NeoForge 版）入口。
 *
 * <p>本类刻意不引用任何客户端类：客户端侧的一切注册都在 {@link ProjectMemoNeoForgeClient}
 * （{@code Dist.CLIENT} 订阅类）里，因此本 jar 即使被误放进服务端也不会在类加载阶段崩溃。
 *
 * <p>共享代码（29 个类中的 28 个）来自 Fabric 树 {@code ../client/src/main/java}，
 * 本工程不复制，只通过 build.gradle 的 srcDir 引用 + 一个 2 方法的兼容垫片吸收差异。
 */
@Mod("projectmemo")
public final class ProjectMemoNeoForge {

    public static final String MOD_ID = "projectmemo";

    public ProjectMemoNeoForge() {
        // 所有注册走 @EventBusSubscriber（NeoForge 会自动投递到正确的总线）
    }
}
