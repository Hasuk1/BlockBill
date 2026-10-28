package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.blockbill.domain.ServiceType.*;
import static dev.blockbill.domain.SubscriptionBundle.*;
import static dev.blockbill.domain.TransactionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpansionTest {
    @Test
    void publishedIndividualPricesAndOrderAreStable() {
        assertArrayEquals(new ServiceType[]{MINE, SLEEP, SPRINT, ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE, TRAVEL}, ServiceType.values());
        assertArrayEquals(new long[]{20, 15, 15, 25, 20, 20, 20, 15, 15, 40},
                java.util.Arrays.stream(ServiceType.values()).mapToLong(ServiceType::price).toArray());
        for (ServiceType service : ServiceType.values()) {
            PlayerAccount account = account(service.price(), 7_000);
            assertEquals(SUCCESS, SubscriptionEngine.purchase(account, service));
            assertEquals(0, account.credits());
            assertEquals(19_000, account.paidUntil(service));
            for (ServiceType other : ServiceType.values()) {
                assertEquals(other == service, SubscriptionEngine.hasAccess(account, other));
            }
        }
    }

    @Test
    void bundleDefinitionsMatchPublishedCatalogAndCannotBeChanged() {
        assertEquals(Set.of(MINE, SLEEP, SPRINT), BASIC.services());
        assertEquals(Set.of(ENCHANT, ANVIL, SMITH), WORKSHOP.services());
        assertEquals(Set.of(MINE, SPRINT, FISH, STORAGE), EXPLORER.services());
        assertEquals(EnumSet.allOf(ServiceType.class), ALL.services());
        assertEquals(40, BASIC.price());
        assertEquals(50, WORKSHOP.price());
        assertEquals(50, EXPLORER.price());
        assertEquals(120, ALL.price());
        assertThrows(UnsupportedOperationException.class, () -> BASIC.services().add(TRADE));
    }

    @Test
    void originalBundlesStillGrantTenMinutesToTheirOwnServicesForTheOriginalPrice() {
        for (SubscriptionBundle bundle : List.of(BASIC, WORKSHOP, EXPLORER, ALL)) {
            assertEquals(12_000, bundle.durationTicks());
            assertEquals(10, bundle.durationMinutes());
            assertFalse(bundle.isPlan());
            PlayerAccount account = account(bundle.price() + 7, 7_000);
            assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, bundle));
            assertEquals(7, account.credits());
            for (ServiceType service : ServiceType.values()) {
                assertEquals(bundle.services().contains(service) ? 19_000 : 0, account.paidUntil(service));
            }
        }
    }

    @Test
    void allAliasBuysNineServicesForOneHundredTwenty() {
        PlayerAccount account = account(125, 7_000);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseAll(account));
        assertEquals(5, account.credits());
        for (ServiceType service : ServiceType.values()) assertEquals(19_000, account.paidUntil(service));
    }

    @Test
    void insufficientFundsNeverPartiallyPurchaseAnyBundle() {
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            PlayerAccount account = account(bundle.price() - 1, 7_000);
            PlayerAccount.Snapshot before = account.snapshot();
            assertEquals(INSUFFICIENT_CREDITS, SubscriptionEngine.purchaseBundle(account, bundle));
            assertEquals(before, account.snapshot());
        }
    }

    @Test
    void lastServiceOverflowRejectsEntireNineServiceBundle() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 120, 7_000, 6_000,
                18_000, 20_000, 22_000, Map.of("enchant", 24_000L, "storage", Long.MAX_VALUE), Set.of()));
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(OVERFLOW, SubscriptionEngine.purchaseAll(account));
        assertEquals(before, account.snapshot());
    }

    @Test
    void lateNewServiceStartsAtCurrentTimeAndEarlyRenewalPreservesItsRemainingTime() {
        PlayerAccount account = account(50, 100_000);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, ENCHANT));
        assertEquals(112_000, account.paidUntil(ENCHANT));
        account.tick(true);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, ENCHANT));
        assertEquals(124_000, account.paidUntil(ENCHANT));
        assertEquals(0, account.credits());
    }

    @Test
    void autorenewIsOptInAndDoesNotChargeDuringTrial() {
        PlayerAccount account = account(100, 5_999);
        for (ServiceType service : ServiceType.values()) assertFalse(account.autoRenew(service));
        account.setAutoRenew(ENCHANT, true);
        PlayerAccount.Snapshot before = account.snapshot();
        assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
        assertEquals(before, account.snapshot());
        account.tick(true);
        assertEquals(Map.of(ENCHANT, SUCCESS), SubscriptionEngine.renewDue(account));
        assertEquals(75, account.credits());
        assertEquals(18_000, account.paidUntil(ENCHANT));
    }

    @Test
    void autorenewChargesOnePeriodAtExactExpiryAndCannotDoubleCharge() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 60, 17_999, 6_000,
                0, 0, 0, Map.of("trade", 18_000L), Set.of("trade")));
        assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
        account.tick(true);
        assertEquals(Map.of(TRADE, SUCCESS), SubscriptionEngine.renewDue(account));
        assertEquals(30_000, account.paidUntil(TRADE));
        assertEquals(40, account.credits());
        assertTrue(account.autoRenew(TRADE));
        for (int repetition = 0; repetition < 100; repetition++) assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
        assertEquals(40, account.credits());
    }

    @Test
    void insufficientAutorenewDisablesOnlyThatServiceAndNeverRetriesWithoutOptIn() {
        PlayerAccount account = account(14, 6_000);
        account.setAutoRenew(STORAGE, true);
        assertEquals(Map.of(STORAGE, INSUFFICIENT_CREDITS), SubscriptionEngine.renewDue(account));
        assertEquals(14, account.credits());
        assertEquals(0, account.paidUntil(STORAGE));
        assertFalse(account.autoRenew(STORAGE));
        account.credit(1);
        assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
        assertEquals(15, account.credits());
        account.setAutoRenew(STORAGE, true);
        assertEquals(Map.of(STORAGE, SUCCESS), SubscriptionEngine.renewDue(account));
        assertEquals(0, account.credits());
    }

    @Test
    void overflowAutorenewDisablesFlagWithoutSpendingOrWrappingClock() {
        PlayerAccount account = account(20, Long.MAX_VALUE);
        account.setAutoRenew(ANVIL, true);
        assertEquals(Map.of(ANVIL, OVERFLOW), SubscriptionEngine.renewDue(account));
        assertEquals(20, account.credits());
        assertEquals(0, account.paidUntil(ANVIL));
        assertFalse(account.autoRenew(ANVIL));
        assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
    }

    @Test
    void simultaneousAutorenewUsesCatalogOrderAndIndividualPrices() {
        PlayerAccount account = account(40, 6_000);
        account.setAutoRenew(STORAGE, true);
        account.setAutoRenew(SLEEP, true);
        account.setAutoRenew(MINE, true);
        Map<ServiceType, TransactionResult> result = SubscriptionEngine.renewDue(account);
        assertEquals(List.of(MINE, SLEEP, STORAGE), new ArrayList<>(result.keySet()));
        assertEquals(Map.of(MINE, SUCCESS, SLEEP, SUCCESS, STORAGE, INSUFFICIENT_CREDITS), result);
        assertEquals(5, account.credits());
        assertTrue(account.autoRenew(MINE));
        assertTrue(account.autoRenew(SLEEP));
        assertFalse(account.autoRenew(STORAGE));
        assertFalse(SubscriptionEngine.hasAccess(account, SPRINT));
        assertThrows(UnsupportedOperationException.class, () -> result.put(SPRINT, SUCCESS));
    }

    @Test
    void cheaperLaterServiceCanRenewAfterEarlierFailure() {
        PlayerAccount account = account(20, 6_000);
        account.setAutoRenew(ENCHANT, true);
        account.setAutoRenew(ANVIL, true);
        assertEquals(Map.of(ENCHANT, INSUFFICIENT_CREDITS, ANVIL, SUCCESS), SubscriptionEngine.renewDue(account));
        assertEquals(0, account.credits());
        assertFalse(account.autoRenew(ENCHANT));
        assertTrue(account.autoRenew(ANVIL));
    }

    @Test
    void changingAutorenewFlagNeverPurchasesOrLosesCurrentLicense() {
        PlayerAccount account = account(30, 7_000);
        SubscriptionEngine.purchase(account, FISH);
        long expiry = account.paidUntil(FISH);
        account.setAutoRenew(FISH, true);
        account.setAutoRenew(FISH, false);
        assertTrue(SubscriptionEngine.renewDue(account).isEmpty());
        assertEquals(15, account.credits());
        assertEquals(expiry, account.paidUntil(FISH));
    }

    @Test
    void passiveRestoreAndNonSurvivalTicksPreserveBalanceTimeAndAutorenewFlags() {
        PlayerAccount account = account(25, 7_000);
        account.setAutoRenew(ENCHANT, true);
        PlayerAccount.Snapshot saved = account.snapshot();
        PlayerAccount restored = PlayerAccount.restore(saved);
        for (int tick = 0; tick < 1_000; tick++) restored.tick(false);
        // The adapter must not call renewDue while offline or outside survival.
        assertEquals(saved, restored.snapshot());
        assertEquals(Map.of(ENCHANT, SUCCESS), SubscriptionEngine.renewDue(restored));
    }

    @Test
    void legacySnapshotDoesNotGrantNewTrialNewLicensesOrAutoRenew() {
        PlayerAccount.Snapshot legacy = new PlayerAccount.Snapshot(UUID.randomUUID(), 100, 10_000, 6_000, 18_000, 20_000, 22_000);
        PlayerAccount account = PlayerAccount.restore(legacy);
        assertEquals(legacy, account.snapshot());
        assertEquals(0, SubscriptionEngine.trialRemainingTicks(account));
        for (ServiceType service : List.of(ENCHANT, ANVIL, SMITH, TRADE, FISH, STORAGE)) {
            assertEquals(0, account.paidUntil(service));
            assertFalse(SubscriptionEngine.hasAccess(account, service));
        }
        for (ServiceType service : ServiceType.values()) assertFalse(account.autoRenew(service));
    }

    @Test
    void snapshotsCopyBothCollectionsAndRemainDetachedFromFutureAccountChanges() {
        Map<String, Long> extras = new HashMap<>(Map.of("enchant", 20_000L));
        Set<String> renew = new HashSet<>(Set.of("enchant"));
        PlayerAccount.Snapshot snapshot = new PlayerAccount.Snapshot(UUID.randomUUID(), 100, 7_000, 6_000, 0, 0, 0, extras, renew);
        extras.put("enchant", 999L);
        renew.clear();
        assertEquals(Map.of("enchant", 20_000L), snapshot.extraPaidUntil());
        assertEquals(Set.of("enchant"), snapshot.autoRenew());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.extraPaidUntil().put("fish", 1L));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.autoRenew().clear());
        PlayerAccount restored = PlayerAccount.restore(snapshot);
        assertEquals(snapshot, restored.snapshot());
        SubscriptionEngine.purchase(restored, ENCHANT);
        restored.setAutoRenew(ENCHANT, false);
        assertEquals(20_000, snapshot.extraPaidUntil().get("enchant"));
        assertEquals(Set.of("enchant"), snapshot.autoRenew());
    }

    @Test
    void unknownFutureServiceAndFlagRoundTripWithoutExecutionOrCharge() {
        PlayerAccount.Snapshot future = new PlayerAccount.Snapshot(UUID.randomUUID(), 100, 30_000, 6_000, 0, 0, 0,
                Map.of("teleport", 10_000L), Set.of("teleport"));
        PlayerAccount restored = PlayerAccount.restore(future);
        assertEquals(future, restored.snapshot());
        assertTrue(SubscriptionEngine.renewDue(restored).isEmpty());
        assertEquals(future, restored.snapshot());
    }

    @Test
    void extrasCannotOverrideAuthoritativeLegacyExpiryFields() {
        PlayerAccount.Snapshot mixed = new PlayerAccount.Snapshot(UUID.randomUUID(), 100, 7_000, 6_000, 0, 20_000, 0,
                Map.of("mine", Long.MAX_VALUE, "sleep", 0L), Set.of());
        PlayerAccount restored = PlayerAccount.restore(mixed);
        assertFalse(SubscriptionEngine.hasAccess(restored, MINE));
        assertEquals(20_000, restored.paidUntil(SLEEP));
        assertEquals(mixed, restored.snapshot());
    }

    @Test
    void negativeExtraExpiryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(UUID.randomUUID(), 0, 0, 6_000,
                0, 0, 0, Map.of("storage", -1L), Set.of()));
    }

    private static PlayerAccount account(long credits, long ticks) {
        return PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), credits, ticks, 6_000, 0, 0, 0));
    }
}
