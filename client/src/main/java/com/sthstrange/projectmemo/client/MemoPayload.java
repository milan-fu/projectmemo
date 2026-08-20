package com.sthstrange.projectmemo.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * projectmemo:main 自定义载荷：UTF-8 JSON 字符串包一层（C2S/S2C 同通道同编码）。
 * 协议 v1（服务端 MemoChannel 定稿）：C2S hello/action；S2C init/ack/sync/open_gui。
 * 1.21.11 命名：CustomPacketPayload + StreamCodec（旧名 CustomPayload/PacketCodec）。
 */
public record MemoPayload(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MemoPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("projectmemo", "main"));

    public static final StreamCodec<FriendlyByteBuf, MemoPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeCharSequence(payload.json(), StandardCharsets.UTF_8),
            buf -> new MemoPayload(buf.readCharSequence(buf.readableBytes(), StandardCharsets.UTF_8).toString()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
