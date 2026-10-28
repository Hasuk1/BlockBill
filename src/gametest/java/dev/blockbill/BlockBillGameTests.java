package dev.blockbill;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.CommonListenerCookie;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.minecraft.world.level.Level;

/** Tests execute the registered commands and Fabric callbacks in a running Minecraft server. */
public final class BlockBillGameTests {
    private static final Map<UUID, Connection> CONNECTIONS = new HashMap<>();
    @GameTest
    public void mixedLogsOffhandAndAtomicRefusal(GameTestHelper helper) {
        ServerPlayer first = player(helper, GameType.SURVIVAL);
        ServerPlayer second = player(helper, GameType.SURVIVAL);
        try {
            first.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 7));
            first.getInventory().setItem(1, new ItemStack(Items.BIRCH_LOG, 5));
            first.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SPRUCE_LOG, 4));
            helper.assertTrue(command(first, "blockbill exchange logs 1") == 1, "Mixed inventory/offhand logs exchange failed");
            helper.assertTrue(account(first).credits() == 12 && account(first).creditRemainder() == 0,
                    "16 mixed logs at 0.75 each must earn exactly 12.00 credits");
            helper.assertTrue(first.getInventory().getItem(0).isEmpty(), "Oak logs were not removed");
            helper.assertTrue(first.getInventory().getItem(1).isEmpty(), "Birch logs were not removed");
            helper.assertTrue(first.getItemInHand(InteractionHand.OFF_HAND).isEmpty(), "Offhand logs were not removed");
            helper.assertTrue(account(second).credits() == 0, "Another UUID shared the wallet");

            first.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 15));
            helper.assertTrue(command(first, "blockbill exchange logs 1") == 0, "Insufficient resources accepted");
            helper.assertTrue(first.getInventory().getItem(0).getCount() == 15, "Failed exchange partially removed logs");
            helper.assertTrue(account(first).credits() == 12 && account(first).creditRemainder() == 0,
                    "Failed exchange changed whole or fractional credits");

            first.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 16));
            account(first).setCredits(Long.MAX_VALUE);
            helper.assertTrue(command(first, "blockbill exchange logs 1") == 0, "Credit overflow accepted");
            helper.assertTrue(first.getInventory().getItem(0).getCount() == 16, "Overflow removed inventory");
            helper.assertTrue(account(first).credits() == Long.MAX_VALUE, "Overflow changed balance");
            helper.succeed();
        } finally {
            disconnect(first, second);
        }
    }

    @GameTest
    public void allExchangeOffersAndBundleBoundaries(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(1, new ItemStack(Items.ROTTEN_FLESH, 8));
            player.getInventory().setItem(2, new ItemStack(Items.IRON_INGOT, 4));
            helper.assertTrue(command(player, "blockbill exchange cobblestone 2") == 1, "Cobblestone exchange failed");
            helper.assertTrue(command(player, "blockbill exchange rotten_flesh 1") == 1, "Flesh exchange failed");
            helper.assertTrue(command(player, "blockbill exchange iron_ingot 1") == 1, "Iron exchange failed");
            helper.assertTrue(account(player).credits() == 12 && account(player).creditRemainder() == 52,
                    "Market exchange must pay 64*0.05 + 8*0.04 + 4*2.25 = 12.52 credits");
            helper.assertTrue(account(player).formattedCredits().equals("12.52"), "Fractional exchange balance formatted incorrectly");
            for (String count : List.of("0", "-1", "1000001", "2147483648")) {
                helper.assertTrue(rejectedSyntax(player, "blockbill exchange logs " + count), "Invalid bundle count accepted: " + count);
            }
            helper.assertTrue(account(player).credits() == 12 && account(player).creditRemainder() == 52,
                    "Invalid arguments changed whole or fractional balance");
            helper.succeed();
        } finally {
            disconnect(player);
        }
    }

    @GameTest
    public void purchasesAreAtomicAndPreserveTrial(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        try {
            PlayerAccount account = account(player);
            account.setCredits(39);
            account.creditCents(99);
            PlayerAccount.Snapshot before = account.snapshot();
            helper.assertTrue(command(player, "blockbill buy basic") == 0, "Unaffordable bundle accepted");
            helper.assertTrue(before.equals(account.snapshot()), "Unaffordable bundle mutated account");
            account.setCredits(60);
            helper.assertTrue(command(player, "blockbill buy mine") == 1, "Mine purchase failed");
            helper.assertTrue(account.paidUntil(ServiceType.MINE) == 18000, "Purchase lost trial time");
            helper.assertTrue(command(player, "blockbill buy basic") == 1, "All Access purchase failed");
            helper.assertTrue(account.credits() == 0, "Purchase did not charge 20 + 40");
            helper.assertTrue(account.creditRemainder() == 99, "Purchases discarded the existing fractional balance");
            helper.assertTrue(account.paidUntil(ServiceType.MINE) == 30000, "Mine renewal lost remaining paid time");
            helper.assertTrue(account.paidUntil(ServiceType.SLEEP) == 18000, "Sleep did not receive full period after trial");
            helper.assertTrue(account.paidUntil(ServiceType.SPRINT) == 18000, "Sprint did not receive full period after trial");
            helper.succeed();
        } finally {
            disconnect(player);
        }
    }

    @GameTest(maxTicks = 70)
    public void onlyOnlineSurvivalConsumesTimeAndCanTrade(GameTestHelper helper) {
        List<ServerPlayer> players = new ArrayList<>();
        for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE)) {
            ServerPlayer player = player(helper, mode);
            players.add(player);
            account(player);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 16));
            if (mode != GameType.SURVIVAL) {
                helper.assertTrue(command(player, "blockbill exchange logs 1") == 0, mode + " traded resources");
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 16, mode + " lost resources");
            }
        }
        PlayerAccount offline = Accounts.get(helper.getLevel().getServer()).account(UUID.randomUUID());
        long baseline = account(players.getFirst()).activePlayTicks();
        helper.runAfterDelay(40, () -> {
            try {
                helper.assertTrue(account(players.getFirst()).activePlayTicks() - baseline == 40,
                        "Survival counter must advance exactly once per server tick");
                for (ServerPlayer player : players.subList(1, players.size())) {
                    helper.assertTrue(account(player).activePlayTicks() == 0, player.gameMode() + " consumed trial");
                }
                helper.assertTrue(offline.activePlayTicks() == 0, "Offline account consumed trial");
                helper.succeed();
            } finally {
                disconnect(players.toArray(ServerPlayer[]::new));
            }
        });
    }

    @GameTest
    public void debugRequiresOperatorPermission(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        try {
            helper.assertTrue(rejectedSyntax(player, "blockbill debug credits 100"), "Unprivileged debug command was available");
            helper.assertTrue(account(player).credits() == 0, "Unprivileged debug changed credits");
            int result = execute(player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS),
                    "blockbill debug credits 100");
            helper.assertTrue(result == 1 && account(player).credits() == 100, "Authorized debug command failed");
            helper.succeed();
        } finally {
            disconnect(player);
        }
    }

    @GameTest
    public void normalAndStrawBedsRespectSleepAccessAndSpawn(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        execute(helper.getLevel().getServer().createCommandSourceStack(), "time set midnight");
        // Bed environment attributes are cached within a tick. Let the new time reach their
        // next evaluation before testing the actual vanilla rules, rather than replacing rules.
        helper.runAfterDelay(2, () -> {
            try {
                expireTrial(account(player));
                AbstractBedBlock normal = (AbstractBedBlock) Blocks.BED.red();
                BlockPos first = bed(helper, normal, new BlockPos(1, 2, 1));
                player.setPos(first.getX() + 0.5, first.getY(), first.getZ() + 0.5);
                var denied = player.startSleepInBed(normal, helper.getLevel().getBlockState(first),
                        normal.getBedRule(helper.getLevel(), first), first);
                helper.assertTrue(denied.left().isPresent(), "Normal bed allowed unpaid sleep");
                helper.assertTrue(denied.left().orElseThrow().message().equals(Component.translatable("blockbill.sleep.denied")),
                        "Normal bed failed for a vanilla reason instead of Sleep+: " + denied);
                helper.assertTrue(!player.isSleeping(), "Unpaid player entered sleep");
                helper.assertTrue(player.getRespawnConfig() != null
                        && player.getRespawnConfig().respawnData().pos().equals(first), "Denied sleep prevented setting respawn");

                AbstractBedBlock straw = (AbstractBedBlock) Blocks.STRAW_BED;
                BlockPos second = bed(helper, straw, new BlockPos(4, 2, 1));
                player.setPos(second.getX() + 0.5, second.getY(), second.getZ() + 0.5);
                var strawDenied = player.startSleepInBed(straw, helper.getLevel().getBlockState(second),
                        straw.getBedRule(helper.getLevel(), second), second);
                helper.assertTrue(strawDenied.left().isPresent() && !player.isSleeping(), "Straw bed bypassed unpaid sleep");
                helper.assertTrue(strawDenied.left().orElseThrow().message().equals(Component.translatable("blockbill.sleep.denied")),
                        "Straw bed failed for a vanilla reason instead of Sleep+: " + strawDenied);
                helper.assertTrue(player.getRespawnConfig().respawnData().pos().equals(first), "Straw bed changed vanilla respawn policy");

                account(player).setCredits(15);
                helper.assertTrue(command(player, "blockbill buy sleep") == 1, "Sleep purchase failed");
                var allowed = player.startSleepInBed(straw, helper.getLevel().getBlockState(second),
                        straw.getBedRule(helper.getLevel(), second), second);
                helper.assertTrue(allowed.right().isPresent() && player.isSleeping(), "Paid player could not use straw bed");
                player.stopSleepInBed(true, true);
                player.setPos(first.getX() + 0.5, first.getY(), first.getZ() + 0.5);
                var normalAllowed = player.startSleepInBed(normal, helper.getLevel().getBlockState(first),
                        normal.getBedRule(helper.getLevel(), first), first);
                helper.assertTrue(normalAllowed.right().isPresent(), "Paid player could not use normal bed");
                helper.succeed();
            } finally {
                disconnect(player);
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void hardMiningPreservesForeignEffectsAndLetsLegacyPenaltyExpire(GameTestHelper helper) {
        ServerPlayer unpaid = player(helper, GameType.SURVIVAL);
        ServerPlayer protectedPlayer = player(helper, GameType.SURVIVAL);
        ServerPlayer trial = player(helper, GameType.SURVIVAL);
        expireTrial(account(unpaid));
        expireTrial(account(protectedPlayer));
        for (ServerPlayer player : List.of(unpaid, protectedPlayer, trial)) {
            player.setPos(player.getX(), 20, player.getZ());
        }
        protectedPlayer.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 600, 2));
        // Simulate a short effect persisted from v0.3: v0.4 must let it expire without renewing it.
        unpaid.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 60, 0));
        BlockPos local = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos deep = new BlockPos(local.getX(), 20, local.getZ());
        helper.runAfterDelay(45, () -> {
            helper.getLevel().setBlockAndUpdate(deep, Blocks.STONE.defaultBlockState());
            helper.assertTrue(!unpaid.gameMode.destroyBlock(deep), "Unpaid deep mining was not hard-blocked");
            helper.assertTrue(helper.getLevel().getBlockState(deep).is(Blocks.STONE), "Denied mining removed block");
            helper.assertTrue(trial.gameMode.destroyBlock(deep), "Trial player could not actually break deep block");
            helper.assertTrue(unpaid.hasEffect(MobEffects.MINING_FATIGUE)
                    && unpaid.getEffect(MobEffects.MINING_FATIGUE).getDuration() <= 15,
                    "Hard gate refreshed or prematurely removed a legacy effect");
            helper.assertTrue(!trial.hasEffect(MobEffects.MINING_FATIGUE), "Trial player received fatigue");
            helper.assertTrue(protectedPlayer.getEffect(MobEffects.MINING_FATIGUE).getAmplifier() == 2,
                    "BlockBill replaced a stronger foreign effect");
            account(unpaid).setCredits(20);
            command(unpaid, "blockbill buy mine");
            account(protectedPlayer).setCredits(20);
            command(protectedPlayer, "blockbill buy mine");
            helper.getLevel().setBlockAndUpdate(deep, Blocks.STONE.defaultBlockState());
            helper.assertTrue(unpaid.gameMode.destroyBlock(deep), "Payment did not immediately unlock actual deep mining");
        });
        helper.runAfterDelay(115, () -> {
            try {
                helper.assertTrue(!unpaid.hasEffect(MobEffects.MINING_FATIGUE), "Legacy short fatigue did not expire after purchase");
                helper.assertTrue(protectedPlayer.hasEffect(MobEffects.MINING_FATIGUE)
                        && protectedPlayer.getEffect(MobEffects.MINING_FATIGUE).getAmplifier() == 2,
                        "Purchase removed another source's fatigue");
                helper.succeed();
            } finally {
                disconnect(unpaid, protectedPlayer, trial);
            }
        });
    }

    @GameTest(maxTicks = 330)
    public void repeatedSprintRequestsStayBlockedUntilPaymentAndStopAgainAtExpiry(GameTestHelper helper) {
        ServerPlayer unpaid = player(helper, GameType.SURVIVAL);
        ServerPlayer trial = player(helper, GameType.SURVIVAL);
        expireTrial(account(unpaid));
        double walkingSpeed = unpaid.getAttributeValue(Attributes.MOVEMENT_SPEED);
        // A server mock has no keyboard packets. Keep its sprint intent true on each actual game tick.
        helper.onEachTick(() -> {
            unpaid.setSprinting(true);
            trial.setSprinting(true);
            unpaid.getFoodData().setFoodLevel(20);
            trial.getFoodData().setFoodLevel(20);
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(!unpaid.isSprinting(), "Repeated unpaid requests bypassed hard sprint gate");
            helper.assertTrue(unpaid.getAttributeValue(Attributes.MOVEMENT_SPEED) == walkingSpeed,
                    "Denied sprint retained a speed modifier");
            helper.assertTrue(!unpaid.hasEffect(MobEffects.SLOWNESS), "Hard sprint gate added obsolete Slowness");
        });
        helper.runAfterDelay(125, () -> {
            helper.assertTrue(!unpaid.isSprinting(), "Unpaid runner started sprinting after old 120-tick threshold");
            helper.assertTrue(!trial.hasEffect(MobEffects.SLOWNESS), "Trial sprint was penalized");
            account(unpaid).setCredits(15);
            helper.assertTrue(command(unpaid, "blockbill buy sprint") == 1, "Sprint purchase failed");
            unpaid.setSprinting(true);
            helper.assertTrue(unpaid.isSprinting() && unpaid.getAttributeValue(Attributes.MOVEMENT_SPEED) > walkingSpeed,
                    "Payment did not restore vanilla sprint and speed");
        });
        helper.runAfterDelay(220, () -> {
            helper.assertTrue(unpaid.isSprinting(), "Paid continuous sprint was interrupted");
            while (SubscriptionEngine.hasAccess(account(unpaid), ServiceType.SPRINT)) account(unpaid).tick(true);
        });
        helper.runAfterDelay(285, () -> {
            try {
                helper.assertTrue(!unpaid.isSprinting(), "Expired sprint restarted despite repeated requests");
                helper.assertTrue(unpaid.getAttributeValue(Attributes.MOVEMENT_SPEED) == walkingSpeed,
                        "Expiry retained sprint speed modifier");
                helper.assertTrue(!unpaid.hasEffect(MobEffects.SLOWNESS), "Expiry applied obsolete Slowness");
                helper.assertTrue(!trial.hasEffect(MobEffects.SLOWNESS), "Trial sprint received an effect");
                helper.succeed();
            } finally {
                disconnect(unpaid, trial);
            }
        });
    }

    @GameTest
    public void deathAndRespawnKeepWalletAndSubscriptions(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ServerPlayer replacement = null;
        try {
            account(player).setCredits(200);
            command(player, "blockbill buy all");
            PlayerAccount.Snapshot expected = account(player).snapshot();
            player.kill(helper.getLevel());
            replacement = helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(replacement.getUUID().equals(player.getUUID()), "Respawn changed player UUID");
            helper.assertTrue(expected.equals(account(replacement).snapshot()), "Death/respawn reset account state");
            helper.succeed();
        } finally {
            disconnect(replacement == null ? player : replacement);
        }
    }

    @GameTest
    public void dimensionChangeAndReconnectKeepAccount(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ServerPlayer reconnected = null;
        boolean disconnected = false;
        try {
            expireTrial(account(player));
            account(player).setCredits(200);
            command(player, "blockbill buy all");
            PlayerAccount.Snapshot expected = account(player).snapshot();
            UUID uuid = player.getUUID();
            var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "Nether unavailable in test server");
            helper.assertTrue(player.teleportTo(nether, 0.5, 80, 0.5, Set.of(), 0, 0, false), "Dimension teleport failed");
            helper.assertTrue(player.level().dimension().equals(Level.NETHER), "Player did not enter Nether");
            helper.assertTrue(expected.equals(account(player).snapshot()), "Changing dimension reset account");
            disconnect(player);
            disconnected = true;
            reconnected = player(helper, GameType.SURVIVAL, uuid);
            helper.assertTrue(expected.equals(account(reconnected).snapshot()), "Reconnect reset wallet, trial or subscriptions");
            helper.succeed();
        } finally {
            if (reconnected != null) disconnect(reconnected);
            else if (!disconnected) disconnect(player);
        }
    }

    @GameTest
    public void accountStateSurvivesRealDiskReload(GameTestHelper helper) {
        try {
            Path directory = Files.createTempDirectory("blockbill-gametest-save-");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            PlayerAccount.Snapshot expected;
            try (SavedDataStorage storage = new SavedDataStorage(directory, DataFixers.getDataFixer(),
                    helper.getLevel().getServer().registryAccess())) {
                Accounts accounts = storage.computeIfAbsent(Accounts.TYPE);
                PlayerAccount account = accounts.account(first);
                expireTrial(account);
                account.setCredits(123);
                SubscriptionEngine.purchaseAll(account);
                account.setAutoRenew(ServiceType.FISH, true);
                accounts.account(second).setCredits(7);
                accounts.setDirty();
                expected = account.snapshot();
                storage.saveAndJoin();
            }
            try (var paths = Files.walk(directory)) {
                helper.assertTrue(paths.anyMatch(path -> path.toString().endsWith(".dat")), "No actual NBT file written");
            }
            try (SavedDataStorage reloaded = new SavedDataStorage(directory, DataFixers.getDataFixer(),
                    helper.getLevel().getServer().registryAccess())) {
                Accounts accounts = reloaded.computeIfAbsent(Accounts.TYPE);
                helper.assertTrue(expected.equals(accounts.account(first).snapshot()), "NBT disk reload lost wallet/time/subscriptions");
                helper.assertTrue(accounts.account(second).credits() == 7, "NBT disk reload mixed UUIDs");
            }
            // Keep evidence under the test runner's temporary directory; never touch user worlds.
            helper.succeed();
        } catch (IOException exception) {
            throw new AssertionError("SavedData roundtrip failed", exception);
        }
    }

    static ServerPlayer player(GameTestHelper helper, GameType mode) {
        return player(helper, mode, UUID.randomUUID());
    }

    static ServerPlayer player(GameTestHelper helper, GameType mode, UUID uuid) {
        GameProfile profile = new GameProfile(uuid, "bb-" + uuid.toString().substring(0, 8));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // The vanilla InLevel helper permanently overrides gameMode() to CREATIVE. Use a normal
        // player with the same embedded network connection so survival events are actually exercised.
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        // PlayerList does not register synthetic connections with the network tick loop. Without
        // this, ServerPlayer.doTick() never runs and vanilla effect durations never decrease.
        helper.getLevel().getServer().getConnection().getConnections().add(connection);
        CONNECTIONS.put(uuid, connection);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setGameMode(mode);
        player.setNoGravity(true);
        player.getAbilities().invulnerable = true;
        BlockPos position = helper.absolutePos(new BlockPos(2, 3, 2));
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return player;
    }

    static PlayerAccount account(ServerPlayer player) {
        return Accounts.get(player.level().getServer()).account(player.getUUID());
    }

    private static void expireTrial(PlayerAccount account) {
        while (SubscriptionEngine.trialRemainingTicks(account) > 0) account.tick(true);
    }

    static int command(ServerPlayer player, String command) {
        return execute(player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS), command);
    }

    private static int execute(CommandSourceStack source, String command) {
        try {
            return source.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException exception) {
            throw new AssertionError("Unexpected command failure: " + command, exception);
        }
    }

    private static boolean rejectedSyntax(ServerPlayer player, String command) {
        try {
            player.level().getServer().getCommands().getDispatcher().execute(command,
                    player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS));
            return false;
        } catch (CommandSyntaxException expected) {
            return true;
        }
    }

    private static BlockPos bed(GameTestHelper helper, AbstractBedBlock block, BlockPos foot) {
        helper.setBlock(foot.below(), Blocks.STONE);
        helper.setBlock(foot.north().below(), Blocks.STONE);
        helper.getLevel().setBlock(helper.absolutePos(foot), block.defaultBlockState().setValue(AbstractBedBlock.FACING, Direction.NORTH)
                .setValue(AbstractBedBlock.PART, BedPart.FOOT), 2);
        helper.getLevel().setBlock(helper.absolutePos(foot.north()), block.defaultBlockState().setValue(AbstractBedBlock.FACING, Direction.NORTH)
                .setValue(AbstractBedBlock.PART, BedPart.HEAD), 2);
        return helper.absolutePos(foot.north());
    }

    static void disconnect(ServerPlayer... players) {
        for (ServerPlayer player : players) {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            player.level().getServer().getPlayerList().remove(player);
            Connection connection = CONNECTIONS.remove(player.getUUID());
            if (connection != null) {
                player.level().getServer().getConnection().getConnections().remove(connection);
                connection.disconnect(Component.literal("Game test complete"));
            }
        }
    }
}
