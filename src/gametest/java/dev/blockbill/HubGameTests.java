package dev.blockbill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.infrastructure.Accounts;
import dev.blockbill.market.ItemPriceCatalog;
import dev.blockbill.network.HubRequest;
import dev.blockbill.network.HubServer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import static dev.blockbill.BlockBillGameTests.account;
import static dev.blockbill.BlockBillGameTests.player;
import static dev.blockbill.BlockBillGameTests.disconnect;

public final class HubGameTests {
    @GameTest public void purchasesAndAutoRenewUseAuthenticatedAccountAndRejectReplays(GameTestHelper helper) {
        var buyer = player(helper, GameType.SURVIVAL); var other = player(helper, GameType.SURVIVAL);
        try {
            account(buyer).setCredits(1000); var untouched = account(other).snapshot();
            long revision = open(buyer).get("revision").getAsLong();
            var buy = new HubRequest("BUY", "business", 0, 0, revision);
            var result = request(buyer, buy);
            helper.assertTrue(result.get("message").getAsString().equals("hub.buy.success"), "UI purchase failed");
            helper.assertTrue(account(buyer).credits() == 460, "UI Business charged wrong price");
            var beforeReplay = account(buyer).snapshot();
            helper.assertTrue(request(buyer, buy).get("message").getAsString().equals("hub.error.stale"), "Replayed BUY accepted");
            helper.assertTrue(beforeReplay.equals(account(buyer).snapshot()), "Replay changed money or time");
            var toggle = new HubRequest("AUTO", "travel", 0, 1, result.get("revision").getAsLong());
            request(buyer, toggle);
            helper.assertTrue(account(buyer).autoRenew(ServiceType.TRAVEL), "UI toggle did not arm travel");
            helper.assertTrue(untouched.equals(account(other).snapshot()), "UI touched another UUID");
            helper.succeed();
        } finally { disconnect(buyer, other); }
    }

    @GameTest public void salesConsumeExactItemsCreditCentsAndRejectStaleOrReplayedQuotes(GameTestHelper helper) {
        var seller = player(helper, GameType.SURVIVAL);
        try {
            seller.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
            var initial = open(seller); long revision = initial.get("revision").getAsLong();
            var sell = new HubRequest("SELL", "999999999 client price ignored", 0, 1, revision);
            var result = request(seller, sell);
            helper.assertTrue(result.get("message").getAsString().equals("hub.sale.success"), "Sale failed");
            helper.assertTrue(account(seller).formattedCredits().equals("0.75"), "One oak log must pay 75 cents");
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 3, "Wrong inventory removal");
            var before = account(seller).snapshot(); request(seller, sell);
            helper.assertTrue(before.equals(account(seller).snapshot()) && seller.getInventory().getItem(0).getCount() == 3,
                    "Replayed sale removed items or minted money");
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            result = request(seller, new HubRequest("SELL", "", 0, 3, result.get("revision").getAsLong()));
            helper.assertTrue(result.get("message").getAsString().equals("hub.error.stale"), "Replacement item sold at stale quote");
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 3 && before.equals(account(seller).snapshot()), "Stale sale partially mutated");
            result = request(seller, new HubRequest("SELL", "", 0, 3, result.get("revision").getAsLong()));
            helper.assertTrue(result.get("message").getAsString().equals("hub.sale.success"), "Refreshed stack sale failed");
            helper.assertTrue(account(seller).formattedCredits().equals("60.75") && seller.getInventory().getItem(0).isEmpty(), "Stack proceeds are wrong");
            helper.succeed();
        } finally { disconnect(seller); }
    }

    @GameTest public void invalidAmountsSlotsAndWalletOverflowLeaveItemsUntouched(GameTestHelper helper) {
        var seller = player(helper, GameType.SURVIVAL);
        try {
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
            var state = open(seller);
            for (int[] attempt : new int[][]{{-1, 1}, {Integer.MAX_VALUE, 1}, {0, -1}, {0, 0}, {0, 3}}) {
                state = request(seller, new HubRequest("SELL", "", attempt[0], attempt[1], state.get("revision").getAsLong()));
                helper.assertTrue(account(seller).credits() == 0 && seller.getInventory().getItem(0).getCount() == 2, "Invalid sale changed inventory/balance");
            }
            account(seller).setCredits(Long.MAX_VALUE); account(seller).creditCents(99);
            state = request(seller, new HubRequest("SELL", "", 0, 1, state.get("revision").getAsLong()));
            helper.assertTrue(state.get("message").getAsString().equals("hub.error.overflow"), "Overflow sale was accepted");
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 2 && account(seller).creditRemainder() == 99,
                    "Overflow lost items or fractional balance");
            helper.succeed();
        } finally { disconnect(seller); }
    }

    @GameTest public void nonSurvivalMutationRequestsCannotSpendSellOrGrantAds(GameTestHelper helper) {
        for (GameType mode : new GameType[]{GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE}) {
            var player = player(helper, mode);
            try {
                account(player).setCredits(1000); player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
                var before = account(player).snapshot(); var state = open(player);
                for (String action : new String[]{"BUY", "AUTO", "SELL", "AD_START", "AD_CLAIM"}) {
                    state = request(player, new HubRequest(action, "pro", 0, 1, state.get("revision").getAsLong()));
                    helper.assertTrue(state.get("message").getAsString().equals("hub.error.survival"), "Mutation allowed in " + mode);
                    helper.assertTrue(before.equals(account(player).snapshot()) && player.getInventory().getItem(0).getCount() == 2,
                            "Exempt-mode request mutated money, settings or inventory");
                }
            } finally { disconnect(player); }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 160) public void adRequiresRealWatchGrantsOnceAndPersistsCooldown(GameTestHelper helper) {
        var viewer = player(helper, GameType.SURVIVAL);
        var state = open(viewer);
        state = request(viewer, new HubRequest("AD_START", "", 0, 0, state.get("revision").getAsLong()));
        state = request(viewer, new HubRequest("AD_CLAIM", "", 0, 0, state.get("revision").getAsLong()));
        helper.assertTrue(state.get("message").getAsString().equals("hub.ad.incomplete") && account(viewer).adRemainingTicks() == 0,
                "Client could instantly claim an ad");
        helper.runAfterDelay(55, () -> request(viewer, new HubRequest("REFRESH", "", 0, 0, 0)));
        helper.runAfterDelay(110, () -> {
            try {
                var current = request(viewer, new HubRequest("REFRESH", "", 0, 0, 0));
                var claim = new HubRequest("AD_CLAIM", "", 0, 0, current.get("revision").getAsLong());
                var awarded = request(viewer, claim);
                helper.assertTrue(awarded.get("message").getAsString().equals("hub.ad.granted"), "Full watched ad was rejected");
                helper.assertTrue(account(viewer).adRemainingTicks() == 600 && account(viewer).adCooldownTicks() == 1800,
                        "Ad has wrong exact duration/cooldown");
                var before = account(viewer).snapshot(); request(viewer, claim);
                helper.assertTrue(before.equals(account(viewer).snapshot()), "Claim replay changed reward");
                var encoded = Accounts.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, Accounts.get(viewer.level().getServer())).getOrThrow();
                var decoded = Accounts.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
                helper.assertTrue(before.equals(decoded.account(viewer.getUUID()).snapshot()), "Advertising state did not persist through codec");
                helper.succeed();
            } finally { disconnect(viewer); }
        });
    }

    @GameTest public void closingCancelsAdAndFullCatalogIsServerPricedAndPaginated(GameTestHelper helper) {
        var viewer = player(helper, GameType.SURVIVAL);
        try {
            var state = open(viewer);
            state = request(viewer, new HubRequest("AD_START", "", 0, 0, state.get("revision").getAsLong()));
            HubServer.handle(viewer, new HubRequest("CLOSE", "", 0, 0, 0));
            state = open(viewer);
            state = request(viewer, new HubRequest("AD_CLAIM", "", 0, 0, state.get("revision").getAsLong()));
            helper.assertTrue(state.get("message").getAsString().equals("hub.ad.incomplete"), "Closing preserved unfinished watch");
            state = request(viewer, new HubRequest("CATALOG", "minecraft:diamond", 0, 0, 0));
            for (var value : state.getAsJsonArray("catalog")) {
                var row = value.getAsJsonObject(); String id = row.get("item").getAsString();
                helper.assertTrue(id.contains("minecraft:diamond") && row.get("unitPriceCents").getAsLong() == ItemPriceCatalog.prices().get(id),
                        "Catalog ignored search or server pricing");
            }
            state = request(viewer, new HubRequest("CATALOG", "", Integer.MAX_VALUE, 0, 0));
            helper.assertTrue(state.get("catalogPage").getAsInt() == state.get("catalogPages").getAsInt() - 1, "Invalid catalog page was not clamped");
            helper.assertTrue(state.getAsJsonArray("catalog").size() <= 45, "Unbounded catalog payload");
            helper.succeed();
        } finally { disconnect(viewer); }
    }

    private static JsonObject open(ServerPlayer player) { return request(player, new HubRequest("OPEN", "", 0, 0, 0)); }
    private static JsonObject request(ServerPlayer player, HubRequest request) {
        var state = HubServer.handle(player, request);
        if (state == null) throw new AssertionError("Missing server UI response for " + request.action());
        return JsonParser.parseString(state.json()).getAsJsonObject();
    }
}
