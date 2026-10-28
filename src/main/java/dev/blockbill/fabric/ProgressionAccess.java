package dev.blockbill.fabric;

import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server authority for direct mining, sprint requests and entry through progression portals. */
public final class ProgressionAccess {
    private static final Map<UUID, Long> LAST_DENIAL = new HashMap<>();
    private static volatile boolean clientSprintAllowed = true;

    private ProgressionAccess() { }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                allowMining(player, pos, level.getBlockState(pos)) ? InteractionResult.PASS : InteractionResult.FAIL);
        // Recheck at completion: access may expire while the block is being mined.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> allowMining(player, pos, state));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_DENIAL.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_DENIAL.clear());
    }

    public static boolean allowMining(Player player, BlockPos pos, BlockState state) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.gameMode() != GameType.SURVIVAL) return true;
        boolean restricted = pos.getY() < 40 || state.is(BlockTags.ORES) || state.is(Blocks.ANCIENT_DEBRIS);
        return !restricted || allow(serverPlayer, ServiceType.MINE);
    }

    /** Called before LivingEntity updates both its sprint flag and movement-speed modifier. */
    public static boolean allowSprint(Entity entity) {
        if (entity instanceof Player player && entity.level().isClientSide()) {
            return !player.isLocalPlayer() || player.gameMode() != GameType.SURVIVAL || clientSprintAllowed;
        }
        return !(entity instanceof ServerPlayer player) || player.gameMode() != GameType.SURVIVAL
                || allow(player, ServiceType.SPRINT);
    }

    public static void setClientSprintAllowed(boolean allowed) {
        clientSprintAllowed = allowed;
    }

    /** Portal-only entry point. Normal teleport commands and free returns to Overworld never reach a denial. */
    public static boolean allowPortal(Entity root, ServerLevel destination) {
        if (root.level().dimension().equals(destination.dimension())
                || !(destination.dimension().equals(Level.NETHER) || destination.dimension().equals(Level.END))) return true;
        boolean allowed = allowTraveller(root);
        // A boat or other mount is teleported as the root entity. Its passengers must not bypass payment.
        for (Entity passenger : root.getIndirectPassengers()) allowed &= allowTraveller(passenger);
        return allowed;
    }

    private static boolean allowTraveller(Entity entity) {
        return !(entity instanceof ServerPlayer player) || player.gameMode() != GameType.SURVIVAL
                || allow(player, ServiceType.TRAVEL);
    }

    private static boolean allow(ServerPlayer player, ServiceType service) {
        var account = Accounts.get(player.level().getServer()).account(player.getUUID());
        if (SubscriptionEngine.hasAccess(account, service)) return true;
        long now = account.activePlayTicks();
        Long previous = LAST_DENIAL.get(player.getUUID());
        if (previous == null || now < previous || now - previous >= 20) {
            player.sendSystemMessage(Component.translatable("blockbill.access.denied",
                    Component.translatable("blockbill.service." + service.id()), service.price(), Component.translatable("blockbill.open_hub")));
            LAST_DENIAL.put(player.getUUID(), now);
        }
        return false;
    }
}
