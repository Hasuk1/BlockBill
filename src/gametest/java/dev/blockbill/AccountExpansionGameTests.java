package dev.blockbill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.blockbill.BlockBillGameTests.account;
import static dev.blockbill.BlockBillGameTests.command;
import static dev.blockbill.BlockBillGameTests.disconnect;
import static dev.blockbill.BlockBillGameTests.player;
import static dev.blockbill.domain.ServiceType.*;

/** Exercises the public commands and the registered server tick callbacks, using ordinary player permissions. */
public final class AccountExpansionGameTests {
    @GameTest(maxTicks = 80)
    public void autorenewStartsAtTrialBoundaryThroughServerTicks(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        PlayerAccount account = account(player);
        advanceTo(account, 5_998);
        account.setCredits(25);
        helper.assertTrue(command(player, "blockbill autorenew enchant on") == 1, "Ordinary player could not opt in");
        helper.assertTrue(account.credits() == 25 && account.paidUntil(ENCHANT) == 0, "Opt-in itself spent credits");
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(account.activePlayTicks() == 6_001, "Real survival ticks did not advance once each");
                helper.assertTrue(account.credits() == 0, "Trial expiry did not charge exactly 25 credits");
                helper.assertTrue(account.paidUntil(ENCHANT) == 18_000, "Renewal did not start on the exact trial boundary");
                helper.assertTrue(account.autoRenew(ENCHANT), "Successful renewal disabled the opt-in");
                helper.assertTrue(account.paidUntil(ANVIL) == 0, "Renewal activated an unrelated service");
                helper.succeed();
            } finally {
                disconnect(player);
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void failedAutomaticPaymentDisablesAndDoesNotRetryWhenFunded(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        PlayerAccount account = account(player);
        advanceTo(account, 5_998);
        account.setCredits(14);
        helper.assertTrue(command(player, "blockbill autorenew fish on") == 1, "Fish opt-in command failed");
        helper.runAfterDelay(3, () -> {
            try {
                helper.assertTrue(!account.autoRenew(FISH), "Unaffordable automatic renewal remained enabled");
                helper.assertTrue(account.credits() == 14 && account.paidUntil(FISH) == 0, "Failed renewal partially charged or granted time");
                account.credit(100);
            } catch (RuntimeException | Error failure) {
                disconnect(player);
                throw failure;
            }
        });
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(account.credits() == 114, "Disabled automatic renewal retried after funding");
                helper.assertTrue(account.paidUntil(FISH) == 0 && !account.autoRenew(FISH), "Disabled service silently reactivated");
                helper.assertTrue(command(player, "blockbill autorenew fish on") == 1, "Player could not opt in again");
            } catch (RuntimeException | Error failure) {
                disconnect(player);
                throw failure;
            }
        });
        helper.runAfterDelay(23, () -> {
            try {
                helper.assertTrue(account.credits() == 99, "Explicit re-enable did not purchase exactly once");
                helper.assertTrue(account.paidUntil(FISH) > account.activePlayTicks() && account.autoRenew(FISH),
                        "Re-enabled service did not become active");
                helper.succeed();
            } finally {
                disconnect(player);
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void armedAutorenewDoesNotRunOutsideSurvivalOrWhileOffline(GameTestHelper helper) {
        List<ServerPlayer> players = new ArrayList<>();
        List<PlayerAccount.Snapshot> expected = new ArrayList<>();
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE)) {
            ServerPlayer player = player(helper, GameType.SURVIVAL);
            players.add(player);
            PlayerAccount account = account(player);
            advanceTo(account, 6_000);
            account.setCredits(25);
            helper.assertTrue(command(player, "blockbill autorenew enchant on") == 1, "Could not arm renewal in survival");
            player.setGameMode(mode);
            expected.add(account.snapshot());
            helper.assertTrue(command(player, "blockbill autorenew enchant off") == 0, mode + " changed autorenew settings");
        }
        PlayerAccount offline = Accounts.get(helper.getLevel().getServer()).account(UUID.randomUUID());
        advanceTo(offline, 6_000);
        offline.setCredits(25);
        offline.setAutoRenew(ENCHANT, true);
        PlayerAccount.Snapshot offlineExpected = offline.snapshot();
        helper.runAfterDelay(20, () -> {
            try {
                for (int index = 0; index < players.size(); index++) {
                    helper.assertTrue(expected.get(index).equals(account(players.get(index)).snapshot()),
                            players.get(index).gameMode() + " spent time, money, or changed a flag");
                }
                helper.assertTrue(offlineExpected.equals(offline.snapshot()), "Offline account renewed or consumed time");
                helper.succeed();
            } finally {
                disconnect(players.toArray(ServerPlayer[]::new));
            }
        });
    }

    @GameTest
    public void bundleCommandsMatchCatalogAndRejectPartialPurchases(GameTestHelper helper) {
        String[] bundles = {"basic", "workshop", "explorer", "all"};
        long[] prices = {40, 50, 50, 120};
        List<Set<ServiceType>> members = List.of(
                Set.of(MINE, SLEEP, SPRINT), Set.of(ENCHANT, ANVIL, SMITH), Set.of(MINE, SPRINT, FISH, STORAGE),
                Set.of(MINE, SLEEP, SPRINT, ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE));
        for (int index = 0; index < bundles.length; index++) {
            ServerPlayer player = player(helper, GameType.SURVIVAL);
            try {
                PlayerAccount account = account(player);
                account.setCredits(prices[index] - 1);
                PlayerAccount.Snapshot before = account.snapshot();
                helper.assertTrue(command(player, "blockbill buy " + bundles[index]) == 0, "Unaffordable bundle succeeded");
                helper.assertTrue(before.equals(account.snapshot()), "Failed bundle changed a balance or expiry");
                account.setCredits(prices[index] + 9);
                helper.assertTrue(command(player, "blockbill buy " + bundles[index]) == 1, "Affordable bundle failed");
                helper.assertTrue(account.credits() == 9, "Wrong published bundle price: " + bundles[index]);
                for (ServiceType service : ServiceType.values()) {
                    long expiry = members.get(index).contains(service) ? 18_000 : 0;
                    helper.assertTrue(account.paidUntil(service) == expiry, "Wrong member or trial preservation: " + bundles[index] + "/" + service);
                    helper.assertTrue(!account.autoRenew(service), "Manual bundle opted player into automatic charges");
                }
                PlayerAccount.Snapshot paid = account.snapshot();
                helper.assertTrue(command(player, "blockbill buy " + bundles[index]) == 0, "Repeat unaffordable purchase succeeded");
                helper.assertTrue(paid.equals(account.snapshot()), "Repeat failed purchase changed an existing license");
            } finally {
                disconnect(player);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void expandedExchangeCommandsUseExactVanillaItemsAndPublishedRates(GameTestHelper helper) {
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        try {
            Item[] items = {Items.COAL, Items.WHEAT, Items.COPPER_INGOT, Items.REDSTONE, Items.GOLD_INGOT, Items.EMERALD};
            String[] names = {"coal", "wheat", "copper_ingot", "redstone", "gold_ingot", "emerald"};
            int[] quantities = {8, 16, 8, 16, 4, 1};
            long[] payments = {16, 12, 16, 20, 32, 28};
            player.getInventory().setItem(7, new ItemStack(Items.CHARCOAL, 64));
            helper.assertTrue(command(player, "blockbill exchange coal 1") == 0, "Charcoal incorrectly counted as coal");
            helper.assertTrue(player.getInventory().getItem(7).getCount() == 64 && account(player).credits() == 0,
                    "Rejected charcoal exchange mutated inventory or balance");
            long expectedCredits = 0;
            for (int index = 0; index < items.length; index++) {
                player.getInventory().setItem(index, new ItemStack(items[index], quantities[index] - 1));
                helper.assertTrue(command(player, "blockbill exchange " + names[index] + " 1") == 0,
                        "Incomplete bundle accepted for " + names[index]);
                helper.assertTrue(player.getInventory().getItem(index).getCount() == quantities[index] - 1,
                        "Rejected exchange partially removed " + names[index]);
                helper.assertTrue(account(player).credits() == expectedCredits, "Rejected exchange credited resources");
                player.getInventory().setItem(index, new ItemStack(items[index], quantities[index]));
                helper.assertTrue(command(player, "blockbill exchange " + names[index] + " 1") == 1,
                        "Exact vanilla bundle failed for " + names[index]);
                expectedCredits += payments[index];
                helper.assertTrue(account(player).credits() == expectedCredits, "Wrong published rate for " + names[index]);
                helper.assertTrue(player.getInventory().getItem(index).isEmpty(), "Exchange did not remove exact resources");
            }
            helper.assertTrue(account(player).credits() == 124, "Six new offers must earn 124 credits together");
            helper.assertTrue(player.getInventory().getItem(7).getCount() == 64, "Exchange touched unrelated charcoal");
            helper.succeed();
        } finally {
            disconnect(player);
        }
    }

    @GameTest
    public void oldAccountJsonLoadsWithoutNewPrivilegesAndNewStateRoundTrips(GameTestHelper helper) {
        UUID uuid = UUID.randomUUID();
        JsonObject old = new JsonObject();
        old.addProperty("uuid", uuid.toString());
        old.addProperty("credits", 200L);
        old.addProperty("activeTicks", 10_000L);
        old.addProperty("trialEnd", 6_000L);
        old.addProperty("mineUntil", 18_000L);
        old.addProperty("sleepUntil", 20_000L);
        old.addProperty("sprintUntil", 22_000L);
        JsonArray oldAccounts = new JsonArray();
        oldAccounts.add(old);
        Accounts decoded = Accounts.CODEC.parse(JsonOps.INSTANCE, oldAccounts).getOrThrow();
        PlayerAccount account = decoded.account(uuid);
        PlayerAccount.Snapshot legacy = new PlayerAccount.Snapshot(uuid, 200, 10_000, 6_000, 18_000, 20_000, 22_000);
        helper.assertTrue(legacy.equals(account.snapshot()), "Loading old seven fields changed account state");
        for (ServiceType service : List.of(ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE)) {
            helper.assertTrue(account.paidUntil(service) == 0 && !SubscriptionEngine.hasAccess(account, service),
                    "Old world received a new license or restarted trial");
        }
        for (ServiceType service : ServiceType.values()) helper.assertTrue(!account.autoRenew(service), "Migration enabled automatic charges");

        SubscriptionEngine.purchase(account, ENCHANT);
        SubscriptionEngine.purchase(account, STORAGE);
        account.setAutoRenew(ENCHANT, true);
        account.setAutoRenew(STORAGE, true);
        PlayerAccount.Snapshot expanded = account.snapshot();
        var json = Accounts.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();
        Accounts reloaded = Accounts.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        helper.assertTrue(expanded.equals(reloaded.account(uuid).snapshot()), "New optional fields were lost in codec roundtrip");
        helper.assertTrue(reloaded.account(uuid).paidUntil(ENCHANT) == 22_000 && reloaded.account(uuid).paidUntil(STORAGE) == 22_000,
                "New licenses have unexpected expiries after roundtrip");
        helper.assertTrue(reloaded.account(uuid).autoRenew(ENCHANT) && reloaded.account(uuid).autoRenew(STORAGE),
                "New autorenew flags did not survive codec roundtrip");
        helper.succeed();
    }

    private static void advanceTo(PlayerAccount account, long tick) {
        while (account.activePlayTicks() < tick) account.tick(true);
    }
}
