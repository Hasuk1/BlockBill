package dev.blockbill.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AccessState(int mask) implements CustomPacketPayload {
    public static final Type<AccessState> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockbill", "access_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AccessState> CODEC = new StreamCodec<>() {
        @Override public AccessState decode(RegistryFriendlyByteBuf buffer) { return new AccessState(buffer.readVarInt()); }
        @Override public void encode(RegistryFriendlyByteBuf buffer, AccessState state) { buffer.writeVarInt(state.mask()); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
