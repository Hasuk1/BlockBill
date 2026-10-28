package dev.blockbill;

import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.fabric.ProgressionAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

import static dev.blockbill.BlockBillGameTests.account;
import static dev.blockbill.BlockBillGameTests.command;
import static dev.blockbill.BlockBillGameTests.disconnect;
import static dev.blockbill.BlockBillGameTests.player;

/** Real vanilla mutation entry points verify that hard gates run before their irreversible effects. */
public final class ProgressionGameTests {
    @GameTest
    public void freeMiningKeepsSurfaceWoodAndStoneButRejectsEveryOreAndDeepBlock(GameTestHelper helper) {
        ServerPlayer free = player(helper, GameType.SURVIVAL);
        ServerPlayer paid = player(helper, GameType.SURVIVAL);
        try {
            expireTrial(account(free));
            expireTrial(account(paid));
            free.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            paid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
            BlockPos surface = new BlockPos(base.getX(), 70, base.getZ());
            List<Block> ores = List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
                    Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                    Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                    Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
                    Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);
            for (Block ore : ores) {
                helper.getLevel().setBlockAndUpdate(surface, ore.defaultBlockState());
                helper.assertTrue(!free.gameMode.destroyBlock(surface), "Free player broke surface ore: " + ore);
                helper.assertTrue(helper.getLevel().getBlockState(surface).is(ore), "Denied mining removed ore: " + ore);
                helper.assertTrue(free.getMainHandItem().getDamageValue() == 0, "Denied mining damaged tool");
            }
            for (Block block : List.of(Blocks.STONE, Blocks.OAK_LOG, Blocks.DIRT)) {
                BlockPos deep = new BlockPos(base.getX(), 39, base.getZ());
                helper.getLevel().setBlockAndUpdate(deep, block.defaultBlockState());
                helper.assertTrue(!free.gameMode.destroyBlock(deep), "Free player broke block at Y39");
                BlockPos boundary = new BlockPos(base.getX(), 40, base.getZ());
                helper.getLevel().setBlockAndUpdate(boundary, block.defaultBlockState());
                helper.assertTrue(free.gameMode.destroyBlock(boundary), "Free basic surface resource blocked at Y40");
                helper.assertTrue(helper.getLevel().getBlockState(boundary).isAir(), "Allowed resource was not actually broken");
            }
            account(paid).setCredits(20);
            helper.assertTrue(command(paid, "blockbill buy mine") == 1, "Mine purchase failed");
            helper.getLevel().setBlockAndUpdate(surface, Blocks.DIAMOND_ORE.defaultBlockState());
            helper.assertTrue(paid.gameMode.destroyBlock(surface), "Paid mining could not break actual ore");
            helper.getLevel().setBlockAndUpdate(surface, Blocks.DIAMOND_ORE.defaultBlockState());
            helper.assertTrue(!free.gameMode.destroyBlock(surface), "Another player's payment unlocked Free mining");
            helper.assertTrue(paid.getMainHandItem().getDamageValue() > 0, "Successful mining did not execute vanilla tool use");
            helper.succeed();
        } finally {
            disconnect(free, paid);
        }
    }

    @GameTest
    public void miningStartIsCancelledAndCompletionRechecksExpiredAccess(GameTestHelper helper) {
        ServerPlayer miner = player(helper, GameType.SURVIVAL);
        try {
            expireTrial(account(miner));
            BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
            BlockPos pos = new BlockPos(base.getX(), 70, base.getZ());
            miner.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
            miner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            helper.getLevel().setBlockAndUpdate(pos, Blocks.DIAMOND_ORE.defaultBlockState());
            miner.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.SOUTH, 320, 0);
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_ORE), "Start packet broke restricted ore");
            helper.assertTrue(!isDestroyingBlock(miner), "Denied start entered vanilla mining-progress state");
            account(miner).setCredits(20);
            command(miner, "blockbill buy mine");
            miner.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.SOUTH, 320, 1);
            helper.assertTrue(isDestroyingBlock(miner), "Paid start did not enter real vanilla mining-progress state");
            while (SubscriptionEngine.hasAccess(account(miner), ServiceType.MINE)) account(miner).tick(true);
            helper.assertTrue(!miner.gameMode.destroyBlock(pos), "Completion ignored expiry after allowed mining start");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_ORE), "Expired completion removed ore");
            helper.assertTrue(miner.getMainHandItem().getDamageValue() == 0, "Rejected completion consumed durability");
            helper.succeed();
        } finally {
            disconnect(miner);
        }
    }

    @GameTest(maxTicks = 20)
    public void sprintRequestsAndExpiryCannotKeepSpeedModifierOrCreatePenaltyEffects(GameTestHelper helper) {
        ServerPlayer runner = player(helper, GameType.SURVIVAL);
        expireTrial(account(runner));
        double walking = runner.getAttributeValue(Attributes.MOVEMENT_SPEED);
        runner.setSprinting(true);
        helper.assertTrue(!runner.isSprinting(), "Unpaid sprint setter accepted start request");
        helper.assertTrue(runner.getAttributeValue(Attributes.MOVEMENT_SPEED) == walking, "Rejected sprint still applied speed modifier");
        account(runner).setCredits(15);
        command(runner, "blockbill buy sprint");
        runner.setSprinting(true);
        helper.assertTrue(runner.isSprinting() && runner.getAttributeValue(Attributes.MOVEMENT_SPEED) > walking,
                "Paid sprint failed to use vanilla speed modifier");
        runner.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2));
        while (SubscriptionEngine.hasAccess(account(runner), ServiceType.SPRINT)) account(runner).tick(true);
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(!runner.isSprinting(), "Expired sprint was not stopped by server tick");
                helper.assertTrue(runner.getAttributeValue(Attributes.MOVEMENT_SPEED) < walking,
                        "Expired sprint retained speed bonus over the foreign slowness");
                helper.assertTrue(runner.getEffect(MobEffects.SLOWNESS).getAmplifier() == 2,
                        "Hard gate removed/replaced another source's effect");
                helper.assertTrue(!runner.hasEffect(MobEffects.MINING_FATIGUE), "Hard gates applied obsolete fatigue penalty");
                helper.succeed();
            } finally {
                disconnect(runner);
            }
        });
    }

    @GameTest
    public void portalEntryRequiresTravelButReturnAndAdministrativeTeleportRemainFree(GameTestHelper helper) {
        ServerPlayer traveller = player(helper, GameType.SURVIVAL);
        try {
            expireTrial(account(traveller));
            ServerLevel origin = traveller.level();
            for (var dimension : List.of(Level.NETHER, Level.END)) {
                ServerLevel target = origin.getServer().getLevel(dimension);
                Vec3 before = traveller.position();
                traveller.teleportToPortalDestination(origin, transition(target));
                helper.assertTrue(traveller.level() == origin && traveller.position().equals(before),
                        "Unpaid portal entry moved player into " + dimension.identifier());
                helper.assertTrue(traveller.teleportTo(target, 0.5, 90, 0.5, Set.of(), 0, 0, false),
                        "Administrative teleport was incorrectly intercepted");
                helper.assertTrue(traveller.level() == target, "Administrative teleport did not move dimension");
                traveller.teleportToPortalDestination(target, transition(origin));
                helper.assertTrue(traveller.level() == origin, "Free return to Overworld was blocked");
            }
            account(traveller).setCredits(40);
            helper.assertTrue(command(traveller, "blockbill buy travel") == 1, "Travel purchase failed");
            for (var dimension : List.of(Level.NETHER, Level.END)) {
                ServerLevel target = origin.getServer().getLevel(dimension);
                traveller.teleportToPortalDestination(origin, transition(target));
                helper.assertTrue(traveller.level() == target, "Paid portal entry failed: " + dimension.identifier());
                traveller.teleportToPortalDestination(target, transition(origin));
            }
            helper.succeed();
        } finally {
            disconnect(traveller);
        }
    }

    @GameTest
    public void portalVehiclesCannotCarryUnpaidPassengersPastTravelGate(GameTestHelper helper) {
        ServerPlayer rider = player(helper, GameType.SURVIVAL);
        var boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(2, 2, 2));
        try {
            expireTrial(account(rider));
            helper.assertTrue(rider.startRiding(boat, true, true), "Passenger fixture did not mount boat");
            ServerLevel origin = helper.getLevel();
            ServerLevel target = origin.getServer().getLevel(Level.NETHER);
            boat.teleportToPortalDestination(origin, transition(target));
            helper.assertTrue(boat.level() == origin && rider.level() == origin && rider.getVehicle() == boat,
                    "Unpaid passenger bypassed gate via vehicle root");
            account(rider).setCredits(40);
            command(rider, "blockbill buy travel");
            boat.teleportToPortalDestination(origin, transition(target));
            helper.assertTrue(rider.level() == target, "Paid passenger did not travel with vanilla vehicle teleport");
            helper.succeed();
        } finally {
            if (rider.getVehicle() != null) {
                var vehicle = rider.getVehicle();
                rider.stopRiding();
                vehicle.discard();
            }
            if (!boat.isRemoved()) boat.discard();
            disconnect(rider);
        }
    }

    @GameTest
    public void trialAndNonSurvivalModesRemainExemptFromHardGates(GameTestHelper helper) {
        for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE)) {
            ServerPlayer player = player(helper, mode);
            try {
                if (mode != GameType.SURVIVAL) expireTrial(account(player));
                helper.assertTrue(ProgressionAccess.allowMining(player, new BlockPos(0, 10, 0), Blocks.DIAMOND_ORE.defaultBlockState()),
                        "Trial/exempt mode received mining restriction: " + mode);
                player.setSprinting(true);
                helper.assertTrue(player.isSprinting(), "Trial/exempt mode received sprint restriction: " + mode);
                ServerLevel target = helper.getLevel().getServer().getLevel(Level.NETHER);
                player.teleportToPortalDestination(player.level(), transition(target));
                helper.assertTrue(player.level() == target, "Trial/exempt mode received portal restriction: " + mode);
            } finally {
                disconnect(player);
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 640)
    public void advertisementUnlocksRealActionsForExactlySixHundredServerTicks(GameTestHelper helper) {
        ServerPlayer viewer = player(helper, GameType.SURVIVAL);
        expireTrial(account(viewer));
        helper.assertTrue(account(viewer).grantAdvertisement(), "Advertisement fixture grant failed");
        helper.assertTrue(!account(viewer).grantAdvertisement(), "Duplicate reward extended advertisement access");
        viewer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos ore = new BlockPos(base.getX(), 70, base.getZ());
        helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
        helper.assertTrue(viewer.gameMode.destroyBlock(ore), "Advertisement did not unlock real mining");
        viewer.setSprinting(true);
        helper.assertTrue(viewer.isSprinting(), "Advertisement did not unlock real sprint");
        ServerLevel origin = viewer.level();
        ServerLevel nether = origin.getServer().getLevel(Level.NETHER);
        viewer.teleportToPortalDestination(origin, transition(nether));
        helper.assertTrue(viewer.level() == nether, "Advertisement did not unlock portal entry");
        viewer.teleportToPortalDestination(nether, transition(origin));
        helper.runAfterDelay(599, () -> {
            helper.assertTrue(account(viewer).adRemainingTicks() == 1, "Advertisement ended before 600 actual server ticks");
            viewer.setSprinting(true);
            helper.assertTrue(viewer.isSprinting(), "Final advertisement tick was denied");
        });
        helper.runAfterDelay(600, () -> {
            try {
                helper.assertTrue(account(viewer).adRemainingTicks() == 0, "Advertisement outlived 600 actual server ticks");
                viewer.setSprinting(true);
                helper.assertTrue(!viewer.isSprinting(), "Expired advertisement retained sprint access");
                helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
                helper.assertTrue(!viewer.gameMode.destroyBlock(ore), "Expired advertisement retained mining access");
                viewer.teleportToPortalDestination(origin, transition(nether));
                helper.assertTrue(viewer.level() == origin, "Expired advertisement retained portal entry");
                helper.assertTrue(account(viewer).credits() == 0, "Advertisement changed wallet balance");
                for (ServiceType service : ServiceType.values()) {
                    helper.assertTrue(account(viewer).paidUntil(service) == 0, "Advertisement created permanent paid expiry");
                }
                helper.assertTrue(!account(viewer).grantAdvertisement(), "Advertisement cooldown was bypassed at expiry");
                helper.succeed();
            } finally {
                disconnect(viewer);
            }
        });
    }

    private static TeleportTransition transition(ServerLevel target) {
        return new TeleportTransition(target, new Vec3(0.5, 90, 0.5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
    }

    /** Observe the actual vanilla progress state, not an invocation counter of our callback. */
    private static boolean isDestroyingBlock(ServerPlayer player) {
        try {
            var field = ServerPlayerGameMode.class.getDeclaredField("isDestroyingBlock");
            field.setAccessible(true);
            return field.getBoolean(player.gameMode);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect pinned 26.3 mining progress", exception);
        }
    }

    private static void expireTrial(PlayerAccount account) {
        while (SubscriptionEngine.trialRemainingTicks(account) > 0) account.tick(true);
    }
}
