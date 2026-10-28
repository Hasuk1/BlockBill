package dev.blockbill.fabric;

import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmithingTableBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Gates new interactions before vanilla opens a menu or consumes an item. */
public final class ServiceAccess {
    private static final Map<UUID, Long> LAST_DENIAL = new HashMap<>();
    private static final long DENIAL_INTERVAL_TICKS = 20;

    private ServiceAccess() { }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player.gameMode() != GameType.SURVIVAL) return InteractionResult.PASS;
            // Match vanilla's secondary-use rule: sneaking with either hand occupied skips
            // the block interaction and permits ordinary placement/use of the held item.
            if (player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) {
                return InteractionResult.PASS;
            }
            ServiceType service = blockService(level.getBlockState(hit.getBlockPos()).getBlock());
            return service == null ? InteractionResult.PASS : check(player, service);
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player.gameMode() != GameType.SURVIVAL || !isTradeInteraction(entity)
                    || player.getItemInHand(hand).is(Items.VILLAGER_SPAWN_EGG)) {
                return InteractionResult.PASS;
            }
            return check(player, ServiceType.TRADE);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player.gameMode() != GameType.SURVIVAL || !player.getItemInHand(hand).is(Items.FISHING_ROD)
                    || player.fishing != null) {
                return InteractionResult.PASS;
            }
            return check(player, ServiceType.FISH);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_DENIAL.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_DENIAL.clear());
    }

    private static ServiceType blockService(Block block) {
        if (block instanceof EnchantingTableBlock) return ServiceType.ENCHANT;
        if (block instanceof AnvilBlock) return ServiceType.ANVIL;
        if (block instanceof SmithingTableBlock) return ServiceType.SMITH;
        if (block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock) return ServiceType.STORAGE;
        return null;
    }

    private static boolean isTradeInteraction(Entity entity) {
        if (!(entity instanceof Villager || entity instanceof WanderingTrader)) return false;
        AbstractVillager villager = (AbstractVillager) entity;
        return villager.isAlive() && !villager.isBaby() && !villager.isTrading() && !villager.isSleeping();
    }

    private static InteractionResult check(Player player, ServiceType service) {
        if (player.level().isClientSide()) {
            // SUCCESS is the exact Fabric UseItem value that forwards a packet. It also consumes
            // this interaction on the client, preventing fallback to another hand/item. The
            // server independently checks access and performs the original action when paid.
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        var account = Accounts.get(serverPlayer.level().getServer()).account(player.getUUID());
        if (SubscriptionEngine.hasAccess(account, service)) return InteractionResult.PASS;
        long now = account.activePlayTicks();
        Long previous = LAST_DENIAL.get(player.getUUID());
        if (previous == null || now < previous || now - previous >= DENIAL_INTERVAL_TICKS) {
            serverPlayer.sendSystemMessage(Component.translatable("blockbill.access.denied",
                    Component.translatable("blockbill.service." + service.id()), service.price(), Component.translatable("blockbill.open_hub")));
            LAST_DENIAL.put(player.getUUID(), now);
        }
        return InteractionResult.FAIL;
    }
}
