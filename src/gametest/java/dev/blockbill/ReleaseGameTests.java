package dev.blockbill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static dev.blockbill.BlockBillGameTests.account;
import static dev.blockbill.BlockBillGameTests.command;
import static dev.blockbill.BlockBillGameTests.disconnect;
import static dev.blockbill.BlockBillGameTests.player;
import static dev.blockbill.domain.ServiceType.ENCHANT;
import static dev.blockbill.domain.ServiceType.MINE;

/** Release regressions: untrusted commands/hands, tick ordering and fail-closed persistence. */
public final class ReleaseGameTests {
    @GameTest
    public void publicCatalogsAndInvalidTargetArgumentsCannotMutateAccounts(GameTestHelper helper) {
        ServerPlayer caller = player(helper, GameType.SURVIVAL);
        ServerPlayer other = player(helper, GameType.SURVIVAL);
        try {
            account(caller).setCredits(1000);
            caller.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.EMERALD, 7));
            PlayerAccount.Snapshot before = account(caller).snapshot();
            PlayerAccount.Snapshot untouched = account(other).snapshot();
            for (String info : List.of("help", "prices", "services", "plans", "status")) {
                helper.assertTrue(command(caller, "blockbill " + info) == 1, "Public command requires operator: " + info);
            }
            for (String invalid : List.of("buy pro " + other.getUUID(), "autorenew fish on @a",
                    "exchange emerald -1", "exchange emerald 2147483648", "buy business extra",
                    "exchange emerald 1 @a")) {
                boolean rejected = false;
                try {
                    helper.getLevel().getServer().getCommands().getDispatcher().execute("blockbill " + invalid,
                            caller.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS));
                } catch (CommandSyntaxException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected, "Invalid/untrusted command accepted: " + invalid);
                helper.assertTrue(before.equals(account(caller).snapshot()), "Invalid command partially changed caller");
                helper.assertTrue(untouched.equals(account(other).snapshot()), "Command changed another UUID");
                helper.assertTrue(caller.getOffhandItem().getCount() == 7, "Invalid command consumed inventory");
            }
            helper.succeed();
        } finally {
            disconnect(caller, other);
        }
    }

    @GameTest
    public void offhandStationRequestsUseTheRequestingPlayersServerAccount(GameTestHelper helper) {
        ServerPlayer paid = player(helper, GameType.SURVIVAL);
        ServerPlayer unpaid = player(helper, GameType.SURVIVAL);
        try {
            expireTrial(account(paid));
            expireTrial(account(unpaid));
            account(paid).setCredits(25);
            helper.assertTrue(command(paid, "blockbill buy enchant") == 1, "Non-operator purchase failed");
            BlockPos table = table(helper);
            for (ServerPlayer player : List.of(paid, unpaid)) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.LAPIS_LAZULI, 7));
            }
            helper.assertTrue(useTable(unpaid, table, InteractionHand.OFF_HAND) == InteractionResult.FAIL,
                    "Offhand bypassed unpaid enchanting");
            helper.assertTrue(unpaid.containerMenu == unpaid.inventoryMenu, "Denied offhand opened a server menu");
            // 26.3 ServerPlayerGameMode invokes the table's useWithoutItem only for MAIN_HAND.
            // A paid OFF_HAND packet must reach vanilla PASS; it cannot open this particular menu.
            helper.assertTrue(useTable(paid, table, InteractionHand.OFF_HAND) == InteractionResult.PASS,
                    "Paid offhand request did not preserve vanilla PASS");
            helper.assertTrue(paid.containerMenu == paid.inventoryMenu, "Offhand unexpectedly opened vanilla enchanting menu");
            helper.assertTrue(useTable(paid, table, InteractionHand.MAIN_HAND).consumesAction(),
                    "Paid main-hand station request was rejected");
            helper.assertTrue(paid.containerMenu instanceof EnchantmentMenu, "Paid player's real main-hand menu failed");
            helper.assertTrue(useTable(unpaid, table, InteractionHand.MAIN_HAND) == InteractionResult.FAIL,
                    "Another player's open paid menu granted access");
            for (ServerPlayer player : List.of(paid, unpaid)) {
                helper.assertTrue(player.getOffhandItem().getCount() == 7, "Opening/denying station consumed offhand items");
            }
            helper.assertTrue(account(unpaid).paidUntil(ENCHANT) == 0, "Access leaked between accounts");
            helper.succeed();
        } finally {
            paid.closeContainer();
            unpaid.closeContainer();
            disconnect(paid, unpaid);
        }
    }

    @GameTest
    public void offhandFishingCannotBypassPaymentOrReplaceMainHand(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        try {
            expireTrial(account(player));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 9));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.FISHING_ROD));
            InteractionResult denied = player.gameMode.useItem(player, player.level(), player.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(denied == InteractionResult.FAIL && player.fishing == null, "Offhand cast bypassed Fish+");
            helper.assertTrue(player.getOffhandItem().getDamageValue() == 0, "Denied offhand cast damaged rod");
            account(player).setCredits(15);
            helper.assertTrue(command(player, "blockbill buy fish") == 1, "Fish purchase failed");
            player.gameMode.useItem(player, player.level(), player.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(player.fishing != null, "Paid offhand cast failed to create real hook");
            helper.assertTrue(player.getMainHandItem().is(Items.STONE) && player.getMainHandItem().getCount() == 9,
                    "Offhand cast replaced main hand");
            helper.assertTrue(player.getOffhandItem().is(Items.FISHING_ROD), "Offhand cast lost rod");
            helper.succeed();
        } finally {
            if (player.fishing != null) player.fishing.discard();
            disconnect(player);
        }
    }

    @GameTest(maxTicks = 30)
    public void renewalRunsBeforeHardMiningAndServiceChecksAtTheSameBoundary(GameTestHelper helper) {
        ServerPlayer funded = player(helper, GameType.SURVIVAL);
        ServerPlayer limited = player(helper, GameType.SURVIVAL);
        ServerPlayer unpaid = player(helper, GameType.SURVIVAL);
        for (ServerPlayer player : List.of(funded, limited, unpaid)) {
            while (account(player).activePlayTicks() < 5999) account(player).tick(true);
            player.setPos(player.getX(), 20, player.getZ());
        }
        account(funded).setCredits(45);
        account(limited).setCredits(20);
        for (ServerPlayer player : List.of(funded, limited)) {
            command(player, "blockbill autorenew mine on");
            command(player, "blockbill autorenew enchant on");
        }
        BlockPos table = table(helper);
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(account(funded).activePlayTicks() == 6001, "Fixture did not cross exact trial boundary");
                helper.assertTrue(account(funded).credits() == 0 && account(limited).credits() == 0,
                        "Simultaneous renewals charged wrong amount");
                helper.assertTrue(account(funded).paidUntil(MINE) == 18000 && account(funded).paidUntil(ENCHANT) == 18000,
                        "Funded renewals did not start on boundary");
                helper.assertTrue(account(limited).paidUntil(MINE) == 18000 && account(limited).paidUntil(ENCHANT) == 0,
                        "Renewal order did not prioritize Mine before Enchant");
                helper.assertTrue(account(limited).autoRenew(MINE) && !account(limited).autoRenew(ENCHANT),
                        "Partial budget did not preserve successful flag and disable failed one");
                BlockPos ore = table.east();
                for (ServerPlayer miner : List.of(funded, limited, unpaid)) {
                    miner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
                    helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
                    helper.assertTrue(miner.gameMode.destroyBlock(ore) == (miner != unpaid),
                            "Hard mining did not immediately honor the exact renewal boundary");
                    helper.assertTrue(!miner.hasEffect(MobEffects.MINING_FATIGUE), "Hard mining applied obsolete boundary fatigue");
                }
                helper.assertTrue(helper.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE)
                        && unpaid.getMainHandItem().getDamageValue() == 0, "Unpaid control lost block or tool durability");
                funded.setPos(table.getX(), table.getY(), table.getZ() + 1);
                limited.setPos(table.getX(), table.getY(), table.getZ() + 1);
                useTable(funded, table, InteractionHand.MAIN_HAND);
                helper.assertTrue(funded.containerMenu instanceof EnchantmentMenu, "Renewed Enchant+ was not immediately usable");
                helper.assertTrue(useTable(limited, table, InteractionHand.MAIN_HAND) == InteractionResult.FAIL,
                        "Failed automatic Enchant payment granted access");
                helper.succeed();
            } finally {
                funded.closeContainer();
                disconnect(funded, limited, unpaid);
            }
        });
    }

    @GameTest
    public void corruptNbtNeverLoadsPartialAccountsOrOverwritesOriginalFile(GameTestHelper helper) {
        JsonObject valid = validRecord(UUID.randomUUID());
        List<JsonArray> invalid = new ArrayList<>();
        for (String field : List.of("credits", "activeTicks", "trialEnd", "mineUntil", "sleepUntil", "sprintUntil")) {
            JsonObject corrupt = otherRecord(valid);
            corrupt.addProperty(field, -1L);
            invalid.add(records(valid, corrupt));
        }
        JsonObject badUuid = otherRecord(valid);
        badUuid.addProperty("uuid", "not-a-uuid");
        invalid.add(records(valid, badUuid));
        JsonObject missing = otherRecord(valid);
        missing.remove("credits");
        invalid.add(records(valid, missing));
        JsonObject badExtras = otherRecord(valid);
        JsonObject extras = new JsonObject();
        extras.addProperty("fish", -1);
        badExtras.add("extraPaidUntil", extras);
        invalid.add(records(valid, badExtras));
        JsonObject badFlags = otherRecord(valid);
        badFlags.addProperty("autoRenew", 42);
        invalid.add(records(valid, badFlags));
        for (String field : List.of("creditRemainder", "adUntil", "adReadyAt")) {
            JsonObject corrupt = otherRecord(valid);
            corrupt.addProperty(field, -1);
            invalid.add(records(valid, corrupt));
        }
        JsonObject badRemainder = otherRecord(valid);
        badRemainder.addProperty("creditRemainder", 100);
        invalid.add(records(valid, badRemainder));
        invalid.add(records(valid, valid));
        try {
            for (JsonArray corrupt : invalid) {
                var nbt = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, corrupt);
                var decoded = Accounts.CODEC.parse(NbtOps.INSTANCE, nbt);
                helper.assertTrue(decoded.error().isPresent(), "Invalid NBT account list accepted");
                helper.assertTrue(decoded.resultOrPartial().isEmpty(), "Invalid NBT exposed partial accounts to vanilla loader");
                CompoundTag root = new CompoundTag();
                root.put("data", nbt);
                assertPreservedAfterRejectedLoad(helper, root, false);
            }
            assertPreservedAfterRejectedLoad(helper, new CompoundTag(), false);
            assertPreservedAfterRejectedLoad(helper, null, true);
            helper.succeed();
        } catch (IOException exception) {
            throw new AssertionError("Could not prepare isolated corrupt NBT fixture", exception);
        }
    }

    @GameTest
    public void strictLoaderCachesPerWorldAndStillSavesValidAccounts(GameTestHelper helper) {
        try {
            Path directory = Files.createTempDirectory("blockbill-release-valid-");
            UUID uuid = UUID.randomUUID();
            PlayerAccount.Snapshot expected;
            try (SavedDataStorage storage = storage(helper, directory)) {
                Accounts accounts = Accounts.load(storage, directory);
                accounts.account(uuid).setCredits(540);
                SubscriptionEngine.purchaseBundle(accounts.account(uuid), dev.blockbill.domain.SubscriptionBundle.BUSINESS);
                accounts.account(uuid).setAutoRenew(ServiceType.FISH, true);
                accounts.account(uuid).creditCents(37);
                accounts.account(uuid).grantAdvertisement();
                expected = accounts.account(uuid).snapshot();
                // A different path on subsequent calls must not trigger another disk read or reset this storage.
                helper.assertTrue(accounts == Accounts.load(storage, directory.resolve("unused")), "Loader did not cache world identity");
                accounts.setDirty();
            }
            try (SavedDataStorage storage = storage(helper, directory)) {
                Accounts reloaded = Accounts.load(storage, directory);
                helper.assertTrue(expected.equals(reloaded.account(uuid).snapshot()), "Strict loader lost valid saved state");
                helper.assertTrue(reloaded == storage.get(Accounts.TYPE), "Validated data missing from vanilla save cache");
            }
            helper.succeed();
        } catch (IOException exception) {
            throw new AssertionError("Strict loader roundtrip failed", exception);
        }
    }

    private static void assertPreservedAfterRejectedLoad(GameTestHelper helper, CompoundTag root, boolean truncated) throws IOException {
        Path directory = Files.createTempDirectory("blockbill-release-corrupt-");
        Path file = Accounts.TYPE.id().withSuffix(".dat").resolveAgainst(directory);
        Files.createDirectories(file.getParent());
        if (truncated) Files.write(file, new byte[] {0x1f, (byte) 0x8b, 8, 0});
        else NbtIo.writeCompressed(root, file);
        byte[] original = Files.readAllBytes(file);
        try (SavedDataStorage storage = storage(helper, directory)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean rejected = false;
                try {
                    Accounts.load(storage, directory);
                } catch (IllegalStateException expected) {
                    rejected = expected.getMessage().contains("Original file was preserved");
                }
                helper.assertTrue(rejected, "Corrupt data reset to a new or cached empty account table");
            }
            storage.saveAndJoin();
        }
        helper.assertTrue(Arrays.equals(original, Files.readAllBytes(file)), "Rejected corrupt account file was overwritten");
    }

    private static SavedDataStorage storage(GameTestHelper helper, Path directory) {
        return new SavedDataStorage(directory, DataFixers.getDataFixer(), helper.getLevel().getServer().registryAccess());
    }

    private static JsonObject validRecord(UUID uuid) {
        JsonObject record = new JsonObject();
        record.addProperty("uuid", uuid.toString());
        record.addProperty("credits", 100);
        record.addProperty("activeTicks", 6000);
        record.addProperty("trialEnd", 6000);
        record.addProperty("mineUntil", 18000);
        record.addProperty("sleepUntil", 0);
        record.addProperty("sprintUntil", 0);
        return record;
    }

    private static JsonArray records(JsonObject... records) {
        JsonArray array = new JsonArray();
        for (JsonObject record : records) array.add(record.deepCopy());
        return array;
    }

    private static JsonObject otherRecord(JsonObject valid) {
        JsonObject result = valid.deepCopy();
        result.addProperty("uuid", UUID.randomUUID().toString());
        return result;
    }

    private static void expireTrial(PlayerAccount account) {
        while (SubscriptionEngine.trialRemainingTicks(account) > 0) account.tick(true);
    }

    private static BlockPos table(GameTestHelper helper) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local, Blocks.ENCHANTING_TABLE);
        helper.setBlock(local.above(), Blocks.AIR);
        return helper.absolutePos(local);
    }

    private static InteractionResult useTable(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        return player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}
