package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.blockbill.domain.ServiceType.*;
import static dev.blockbill.domain.SubscriptionBundle.*;
import static dev.blockbill.domain.TransactionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanTest {
    private static final Set<ServiceType> PLUS_SERVICES = Set.of(MINE, SLEEP, SPRINT, FISH, STORAGE);
    private static final Set<ServiceType> ALL_SERVICES = Set.of(MINE, SLEEP, SPRINT, ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE);

    @Test
    void threePlansMatchThePublishedPriceDurationAndComposition() {
        assertEquals("plus", PLUS.id());
        assertEquals(90, PLUS.price());
        assertEquals(18_000, PLUS.durationTicks());
        assertEquals(15, PLUS.durationMinutes());
        assertEquals(PLUS_SERVICES, PLUS.services());
        assertEquals("pro", PRO.id());
        assertEquals(300, PRO.price());
        assertEquals(36_000, PRO.durationTicks());
        assertEquals(30, PRO.durationMinutes());
        assertEquals(ALL_SERVICES, PRO.services());
        assertEquals("business", BUSINESS.id());
        assertEquals(540, BUSINESS.price());
        assertEquals(72_000, BUSINESS.durationTicks());
        assertEquals(60, BUSINESS.durationMinutes());
        assertEquals(ALL_SERVICES, BUSINESS.services());
        for (SubscriptionBundle plan : List.of(PLUS, PRO, BUSINESS)) {
            assertTrue(plan.isPlan());
            assertThrows(UnsupportedOperationException.class, () -> plan.services().clear());
        }
    }

    @Test
    void plusChargesNinetyAndGrantsFifteenMinutesOnlyToItsFiveServices() {
        PlayerAccount account = account(97, 7_000);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, PLUS));
        assertEquals(7, account.credits());
        for (ServiceType service : ServiceType.values()) {
            assertEquals(PLUS_SERVICES.contains(service) ? 25_000 : 0, account.paidUntil(service));
        }
    }

    @Test
    void proChargesThreeHundredAndGrantsThirtyMinutesToAllNine() {
        PlayerAccount account = account(307, 7_000);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, PRO));
        assertEquals(7, account.credits());
        for (ServiceType service : ALL_SERVICES) assertEquals(43_000, account.paidUntil(service));
    }

    @Test
    void businessChargesFiveHundredFortyAndGrantsSixtyMinutesOnlyToBuyer() {
        PlayerAccount buyer = account(547, 7_000);
        PlayerAccount other = account(11, 8_000);
        PlayerAccount.Snapshot untouched = other.snapshot();
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(buyer, BUSINESS));
        assertEquals(7, buyer.credits());
        for (ServiceType service : ALL_SERVICES) assertEquals(79_000, buyer.paidUntil(service));
        assertEquals(untouched, other.snapshot());
    }

    @Test
    void eachPlanAddsItsFullDurationAfterRemainingTrialTime() {
        SubscriptionBundle[] plans = {PLUS, PRO, BUSINESS};
        long[] prices = {90, 300, 540};
        long[] expiries = {24_000, 42_000, 78_000};
        for (int index = 0; index < plans.length; index++) {
            PlayerAccount account = account(prices[index], 1_000);
            assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, plans[index]));
            assertEquals(0, account.credits());
            Set<ServiceType> expected = index == 0 ? PLUS_SERVICES : ALL_SERVICES;
            for (ServiceType service : expected) assertEquals(expiries[index], account.paidUntil(service));
            assertEquals(5_000, SubscriptionEngine.trialRemainingTicks(account));
        }
    }

    @Test
    void plusRenewsMixedExpiriesFromEachServicesPaidOrTrialBoundary() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 90, 5_000, 6_000,
                30_000, 0, 2_000, Map.of("fish", 15_000L, "storage", 4_000L, "enchant", 55_000L), Set.of()));
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, PLUS));
        assertEquals(48_000, account.paidUntil(MINE));
        assertEquals(24_000, account.paidUntil(SLEEP));
        assertEquals(24_000, account.paidUntil(SPRINT));
        assertEquals(33_000, account.paidUntil(FISH));
        assertEquals(24_000, account.paidUntil(STORAGE));
        assertEquals(55_000, account.paidUntil(ENCHANT));
        assertEquals(0, account.paidUntil(ANVIL));
        assertEquals(0, account.credits());
    }

    @Test
    void planPurchasedAfterAllOldLicensesExpireStartsAtCurrentActiveTime() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 90, 100_000, 6_000,
                18_000, 20_000, 22_000, Map.of("fish", 30_000L, "storage", 40_000L), Set.of()));
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, PLUS));
        for (ServiceType service : PLUS_SERVICES) assertEquals(118_000, account.paidUntil(service));
        assertEquals(0, account.paidUntil(TRADE));
    }

    @Test
    void plusAfterBusinessExtendsItsServicesWithoutShorteningWorkshopOrTrade() {
        PlayerAccount account = account(630, 7_000);
        account.setAutoRenew(FISH, true);
        account.setAutoRenew(ENCHANT, true);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, BUSINESS));
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, PLUS));
        for (ServiceType service : PLUS_SERVICES) assertEquals(97_000, account.paidUntil(service));
        for (ServiceType service : List.of(ENCHANT, ANVIL, SMITH, TRADE)) assertEquals(79_000, account.paidUntil(service));
        assertEquals(Set.of("fish", "enchant"), account.snapshot().autoRenew());
        assertEquals(0, account.credits());
    }

    @Test
    void everyPlanPreservesExistingFlagsAndDoesNotEnableOthers() {
        for (SubscriptionBundle plan : List.of(PLUS, PRO, BUSINESS)) {
            PlayerAccount account = account(1_000, 7_000);
            account.setAutoRenew(SLEEP, true);
            account.setAutoRenew(TRADE, true);
            assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, plan));
            assertEquals(Set.of("sleep", "trade"), account.snapshot().autoRenew());

            PlayerAccount neverOptedIn = account(1_000, 7_000);
            assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(neverOptedIn, plan));
            assertTrue(neverOptedIn.snapshot().autoRenew().isEmpty());
        }
    }

    @Test
    void insufficientFundsRejectEveryPlanWithoutChangingAnyState() {
        SubscriptionBundle[] plans = {PLUS, PRO, BUSINESS};
        long[] insufficientBalances = {89, 299, 539};
        for (int index = 0; index < plans.length; index++) {
            PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), insufficientBalances[index],
                    7_000, 6_000, 20_000, 25_000, 30_000, Map.of("enchant", 40_000L, "storage", 50_000L), Set.of("fish")));
            PlayerAccount.Snapshot before = account.snapshot();
            assertEquals(INSUFFICIENT_CREDITS, SubscriptionEngine.purchaseBundle(account, plans[index]));
            assertEquals(before, account.snapshot());
        }
    }

    @Test
    void overflowInLastIncludedServiceRejectsEachWholePlanBeforeSpending() {
        SubscriptionBundle[] plans = {PLUS, PRO, BUSINESS};
        long[] prices = {90, 300, 540};
        long[] durations = {18_000, 36_000, 72_000};
        for (int index = 0; index < plans.length; index++) {
            PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), prices[index], 7_000, 6_000,
                    18_000, 20_000, 22_000, Map.of("storage", Long.MAX_VALUE - durations[index] + 1), Set.of("mine", "storage")));
            PlayerAccount.Snapshot before = account.snapshot();
            assertEquals(OVERFLOW, SubscriptionEngine.purchaseBundle(account, plans[index]));
            assertEquals(before, account.snapshot());
        }
    }

    @Test
    void exactMaximumExpiryIsAllowedForEveryPlan() {
        SubscriptionBundle[] plans = {PLUS, PRO, BUSINESS};
        long[] prices = {90, 300, 540};
        long[] durations = {18_000, 36_000, 72_000};
        for (int index = 0; index < plans.length; index++) {
            PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), prices[index], 7_000, 6_000,
                    0, 0, 0, Map.of("storage", Long.MAX_VALUE - durations[index]), Set.of()));
            assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, plans[index]));
            assertEquals(Long.MAX_VALUE, account.paidUntil(STORAGE));
            assertEquals(0, account.credits());
        }
    }

    @Test
    void businessExpiryAutorenewsOnlyOptedInIndividualsForTenMinutesAtTheirOwnPrices() {
        PlayerAccount account = account(585, 0);
        account.setAutoRenew(MINE, true);
        account.setAutoRenew(ENCHANT, true);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, BUSINESS));
        assertEquals(45, account.credits());
        PlayerAccount.Snapshot paid = account.snapshot();
        PlayerAccount expired = PlayerAccount.restore(new PlayerAccount.Snapshot(paid.uuid(), paid.credits(), 78_000, paid.trialEndTick(),
                paid.paidUntilMine(), paid.paidUntilSleep(), paid.paidUntilSprint(), paid.extraPaidUntil(), paid.autoRenew()));
        assertEquals(Map.of(MINE, SUCCESS, ENCHANT, SUCCESS), SubscriptionEngine.renewDue(expired));
        assertEquals(90_000, expired.paidUntil(MINE));
        assertEquals(90_000, expired.paidUntil(ENCHANT));
        assertEquals(78_000, expired.paidUntil(SLEEP));
        assertEquals(78_000, expired.paidUntil(STORAGE));
        assertEquals(0, expired.credits());
        assertEquals(Set.of("mine", "enchant"), expired.snapshot().autoRenew());
        assertEquals(paid, account.snapshot());
    }

    @Test
    void planStateRoundTripsUsingExistingServiceExpiriesWithoutAPlanLevel() {
        PlayerAccount account = account(630, 7_000);
        SubscriptionEngine.purchaseBundle(account, BUSINESS);
        SubscriptionEngine.purchaseBundle(account, PLUS);
        account.setAutoRenew(TRADE, true);
        PlayerAccount.Snapshot saved = account.snapshot();
        PlayerAccount restored = PlayerAccount.restore(saved);
        assertEquals(saved, restored.snapshot());
        assertEquals(97_000, restored.paidUntil(MINE));
        assertEquals(79_000, restored.paidUntil(TRADE));
        assertTrue(restored.autoRenew(TRADE));
    }

    private static PlayerAccount account(long credits, long ticks) {
        return PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), credits, ticks, 6_000, 0, 0, 0));
    }
}
