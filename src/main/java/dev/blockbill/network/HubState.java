package dev.blockbill.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HubState(String json) implements CustomPacketPayload {
    public static final Type<HubState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockbill", "hub_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HubState> CODEC = new StreamCodec<>() {
        @Override public HubState decode(RegistryFriendlyByteBuf buffer) { return new HubState(buffer.readUtf(32767)); }
        @Override public void encode(RegistryFriendlyByteBuf buffer, HubState state) { buffer.writeUtf(state.json(), 32767); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
