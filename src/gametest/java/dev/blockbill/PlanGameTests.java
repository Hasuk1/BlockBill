package dev.blockbill;

import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.blockbill.BlockBillGameTests.account;
import static dev.blockbill.BlockBillGameTests.command;
import static dev.blockbill.BlockBillGameTests.disconnect;
import static dev.blockbill.BlockBillGameTests.player;
import static dev.blockbill.domain.ServiceType.*;

/** Contract values intentionally do not read prices/durations/contents from the implementation enum. */
public final class PlanGameTests {
    private static final Set<ServiceType> PLUS_SERVICES = EnumSet.of(MINE, SLEEP, SPRINT, FISH, STORAGE);
    private static final Set<ServiceType> ALL_SERVICES = Set.of(MINE, SLEEP, SPRINT, ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE, TRAVEL);
    private static final List<Plan> PLANS = List.of(
            new Plan("plus", 90, 18_000, PLUS_SERVICES),
            new Plan("pro", 300, 36_000, ALL_SERVICES),
            new Plan("business", 540, 72_000, ALL_SERVICES));

    @GameTest
    public void eachPlanCommandChargesExactPriceAndAddsExactIncludedPeriods(GameTestHelper helper) {
        for (Plan plan : PLANS) {
            ServerPlayer buyer = player(helper, GameType.SURVIVAL);
            ServerPlayer other = player(helper, GameType.SURVIVAL);
            try {
                account(buyer).setCredits(1000);
                account(buyer).creditCents(37);
                helper.assertTrue(command(buyer, "blockbill buy " + plan.id()) == 1, "Plan command failed: " + plan.id());
                helper.assertTrue(account(buyer).credits() == 1000 - plan.price(), "Plan charged wrong price: " + plan.id());
                helper.assertTrue(account(buyer).creditRemainder() == 37, "Plan purchase changed fractional credits");
                for (ServiceType service : ServiceType.values()) {
                    long expected = plan.services().contains(service) ? 6000 + plan.ticks() : 0;
                    helper.assertTrue(account(buyer).paidUntil(service) == expected, "Plan duration/content mismatch: " + plan.id() + "/" + service);
                    helper.assertTrue(account(other).paidUntil(service) == 0, "Plan granted another player's license");
                    helper.assertTrue(!account(buyer).autoRenew(service), "Plan silently enabled auto-renew");
                }
                helper.assertTrue(account(other).credits() == 0, "Plan changed another player's balance");
                while (account(buyer).activePlayTicks() < 6000) account(buyer).tick(true);
                for (ServiceType service : ServiceType.values()) {
                    helper.assertTrue(SubscriptionEngine.hasAccess(account(buyer), service) == plan.services().contains(service),
                            "Access after trial differs from plan contents: " + plan.id() + "/" + service);
                }
            } finally {
                disconnect(buyer, other);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void businessThenPlusExtendsFiveServicesWithoutDowngradingOthers(GameTestHelper helper) {
        ServerPlayer buyer = player(helper, GameType.SURVIVAL);
        try {
            PlayerAccount account = account(buyer);
            account.setCredits(1000);
            account.setAutoRenew(FISH, true);
            helper.assertTrue(command(buyer, "blockbill buy business") == 1, "Business command failed");
            for (int tick = 0; tick < 300; tick++) account.tick(true);
            helper.assertTrue(command(buyer, "blockbill buy plus") == 1, "Plus command after Business failed");
            helper.assertTrue(account.credits() == 370, "Business + Plus must cost 630 total");
            for (ServiceType service : ServiceType.values()) {
                helper.assertTrue(account.paidUntil(service) == (PLUS_SERVICES.contains(service) ? 96000 : 78000),
                        "Plus downgraded or incorrectly extended Business service: " + service);
                helper.assertTrue(account.autoRenew(service) == (service == FISH), "Plan changed existing auto-renew flags");
            }
            helper.succeed();
        } finally {
            disconnect(buyer);
        }
    }

    @GameTest
    public void insufficientFundsAndNonSurvivalRejectEveryPlanWithoutMutation(GameTestHelper helper) {
        for (Plan plan : PLANS) {
            ServerPlayer buyer = player(helper, GameType.SURVIVAL);
            try {
                account(buyer).setCredits(plan.price() - 1);
                account(buyer).creditCents(99);
                account(buyer).setAutoRenew(SLEEP, true);
                PlayerAccount.Snapshot before = account(buyer).snapshot();
                helper.assertTrue(command(buyer, "blockbill buy " + plan.id()) == 0, "Unaffordable plan accepted");
                helper.assertTrue(before.equals(account(buyer).snapshot()), "Unaffordable plan partially mutated account");
            } finally {
                disconnect(buyer);
            }
            for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE)) {
                ServerPlayer exempt = player(helper, mode);
                try {
                    account(exempt).setCredits(1000);
                    PlayerAccount.Snapshot before = account(exempt).snapshot();
                    helper.assertTrue(command(exempt, "blockbill buy " + plan.id()) == 0, mode + " bought plan");
                    helper.assertTrue(before.equals(account(exempt).snapshot()), mode + " was charged for rejected plan");
                } finally {
                    disconnect(exempt);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void overflowingLastServiceRollsBackEveryPlanCommand(GameTestHelper helper) {
        for (Plan plan : PLANS) {
            ServerPlayer buyer = player(helper, GameType.SURVIVAL);
            try {
                PlayerAccount.Snapshot base = account(buyer).snapshot();
                Map<String, Long> extras = new HashMap<>(base.extraPaidUntil());
                extras.put(plan.id().equals("plus") ? "storage" : "travel", Long.MAX_VALUE);
                PlayerAccount restored = PlayerAccount.restore(new PlayerAccount.Snapshot(base.uuid(), 1000, 0, 6000,
                        0, 0, 0, extras, Set.of("fish")));
                seedSingleAccount(helper, restored);
                PlayerAccount.Snapshot before = account(buyer).snapshot();
                helper.assertTrue(command(buyer, "blockbill buy " + plan.id()) == 0, "Overflowing plan accepted: " + plan.id());
                helper.assertTrue(before.equals(account(buyer).snapshot()), "Plan modified earlier services/balance before last-service overflow");
            } finally {
                disconnect(buyer);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void existingAllBundleStillAddsTenMinutesAfterPro(GameTestHelper helper) {
        ServerPlayer buyer = player(helper, GameType.SURVIVAL);
        try {
            account(buyer).setCredits(420);
            helper.assertTrue(command(buyer, "blockbill buy pro") == 1, "Pro command failed");
            helper.assertTrue(command(buyer, "blockbill buy all") == 1, "Legacy All command failed");
            helper.assertTrue(account(buyer).credits() == 0, "Pro + All must charge 300 + 120");
            for (ServiceType service : ServiceType.values()) {
                helper.assertTrue(account(buyer).paidUntil(service) == 54000, "Legacy All no longer adds exactly 12000 ticks");
            }
            helper.succeed();
        } finally {
            disconnect(buyer);
        }
    }

    /** Test-only fixture injection: replace this UUID alone; do not reset the shared live SavedData. */
    @SuppressWarnings("unchecked")
    private static void seedSingleAccount(GameTestHelper helper, PlayerAccount account) {
        try {
            Field players = Accounts.class.getDeclaredField("players");
            players.setAccessible(true);
            ((Map<UUID, PlayerAccount>) players.get(Accounts.get(helper.getLevel().getServer()))).put(account.uuid(), account);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not seed overflow fixture", exception);
        }
    }

    private record Plan(String id, long price, long ticks, Set<ServiceType> services) { }
}
