package dev.blockbill.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Intent only: the server never accepts a price, UUID, balance or expiry from the client. */
public record HubRequest(String action, String value, int slot, int amount, long revision) implements CustomPacketPayload {
    public static final Type<HubRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath("blockbill", "hub_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HubRequest> CODEC = new StreamCodec<>() {
        @Override public HubRequest decode(RegistryFriendlyByteBuf buffer) {
            return new HubRequest(buffer.readUtf(24), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(), buffer.readLong());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, HubRequest request) {
            buffer.writeUtf(request.action(), 24); buffer.writeUtf(request.value(), 128);
            buffer.writeVarInt(request.slot()); buffer.writeVarInt(request.amount()); buffer.writeLong(request.revision());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
