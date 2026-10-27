package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.blockbill.domain.ServiceType.*;
import static dev.blockbill.domain.TransactionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionEngineTest {
    @Test
    void firstAccountStartsOneTrialForAllServices() {
        PlayerAccount account = fresh();
        assertEquals(0, account.credits());
        assertEquals(6_000, account.trialEndTick());
        for (ServiceType service : ServiceType.values()) {
            assertTrue(SubscriptionEngine.hasAccess(account, service));
            assertEquals(6_000, SubscriptionEngine.remainingTicks(account, service));
            assertEquals(0, account.paidUntil(service));
        }
    }

    @Test
    void trialExpiresAtExactTickAndRestoreDoesNotRestartIt() {
        PlayerAccount account = state(0, 5_999, 0, 0, 0);
        assertTrue(SubscriptionEngine.hasAccess(account, MINE));
        assertTrue(account.tick(true));
        account = PlayerAccount.restore(account.snapshot());
        for (ServiceType service : ServiceType.values()) {
            assertFalse(SubscriptionEngine.hasAccess(account, service));
            assertEquals(0, SubscriptionEngine.remainingTicks(account, service));
        }
        assertEquals(0, SubscriptionEngine.trialRemainingTicks(account));
    }

    @Test
    void onlySurvivalTicksAdvanceClock() {
        PlayerAccount account = fresh();
        for (int tick = 0; tick < 1_000; tick++) {
            assertFalse(account.tick(false));
        }
        assertEquals(0, account.activePlayTicks());
        account.tick(true);
        assertEquals(1, account.activePlayTicks());
    }

    @Test
    void minePurchaseDuringTrialPreservesEveryTrialTick() {
        PlayerAccount account = state(20, 1_200, 0, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(0, account.credits());
        assertEquals(18_000, account.paidUntil(MINE));
        assertEquals(16_800, SubscriptionEngine.remainingTicks(account, MINE));
        assertEquals(4_800, SubscriptionEngine.remainingTicks(account, SLEEP));
    }

    @Test
    void earlyRenewalAddsWholePeriodToExistingLicense() {
        PlayerAccount account = state(40, 8_000, 18_000, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(30_000, account.paidUntil(MINE));
        assertEquals(20, account.credits());
    }

    @Test
    void purchaseAfterExpiryStartsFromCurrentActiveTick() {
        PlayerAccount account = state(20, 25_000, 18_000, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(37_000, account.paidUntil(MINE));
    }

    @Test
    void paidAccessExpiresAtExactBoundary() {
        PlayerAccount account = state(0, 17_999, 18_000, 0, 0);
        assertTrue(SubscriptionEngine.hasAccess(account, MINE));
        assertEquals(1, SubscriptionEngine.remainingTicks(account, MINE));
        account.tick(true);
        assertFalse(SubscriptionEngine.hasAccess(account, MINE));
    }

    @Test
    void insufficientCreditsNeverChangesAnyState() {
        PlayerAccount account = state(19, 7_000, 0, 9_000, 10_000);
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(INSUFFICIENT_CREDITS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(before, account.snapshot());
    }

    @Test
    void individualServicePricesAreIndependent() {
        for (ServiceType service : new ServiceType[]{SLEEP, SPRINT}) {
            PlayerAccount account = state(15, 7_000, 0, 0, 0);
            assertEquals(SUCCESS, SubscriptionEngine.purchase(account, service));
            assertEquals(0, account.credits());
            assertEquals(19_000, account.paidUntil(service));
            assertFalse(SubscriptionEngine.hasAccess(account, MINE));
        }
    }

    @Test
    void basicRenewsEachServiceFromItsOwnExpiryForFortyCredits() {
        PlayerAccount account = state(43, 8_000, 18_000, 20_000, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.BASIC));
        assertEquals(3, account.credits());
        assertEquals(30_000, account.paidUntil(MINE));
        assertEquals(32_000, account.paidUntil(SLEEP));
        assertEquals(20_000, account.paidUntil(SPRINT));
    }

    @Test
    void allAccessPreservesTrialForAllServices() {
        PlayerAccount account = state(120, 0, 0, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchaseAll(account));
        for (ServiceType service : ServiceType.values()) {
            assertEquals(18_000, account.paidUntil(service));
        }
    }

    @Test
    void basicWithInsufficientCreditsIsFullyRejected() {
        PlayerAccount account = state(39, 8_000, 18_000, 20_000, 0);
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(INSUFFICIENT_CREDITS, SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.BASIC));
        assertEquals(before, account.snapshot());
    }

    @Test
    void repeatedPurchasesSpendExactlyOncePerSuccessfulCall() {
        PlayerAccount account = state(40, 7_000, 0, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(INSUFFICIENT_CREDITS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(before, account.snapshot());
        assertEquals(31_000, account.paidUntil(MINE));
    }

    @Test
    void renewalOverflowDoesNotSpendCredits() {
        PlayerAccount account = state(20, 7_000, Long.MAX_VALUE - 11_999, 0, 0);
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(OVERFLOW, SubscriptionEngine.purchase(account, MINE));
        assertEquals(before, account.snapshot());
    }

    @Test
    void overflowInLastBasicServiceDoesNotPartiallyRenewEarlierServices() {
        PlayerAccount account = state(40, 7_000, 18_000, 20_000, Long.MAX_VALUE);
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(OVERFLOW, SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.BASIC));
        assertEquals(before, account.snapshot());
    }

    @Test
    void renewalCanReachMaximumLongExactly() {
        PlayerAccount account = state(20, 7_000, Long.MAX_VALUE - 12_000, 0, 0);
        assertEquals(SUCCESS, SubscriptionEngine.purchase(account, MINE));
        assertEquals(Long.MAX_VALUE, account.paidUntil(MINE));
        assertEquals(Long.MAX_VALUE - 7_000, SubscriptionEngine.remainingTicks(account, MINE));
    }

    @Test
    void saturatedClockNeverWrapsOrRestartsTrial() {
        PlayerAccount account = state(40, Long.MAX_VALUE - 1, Long.MAX_VALUE, 0, 0);
        assertTrue(account.tick(true));
        assertFalse(account.tick(true));
        assertEquals(Long.MAX_VALUE, account.activePlayTicks());
        assertFalse(SubscriptionEngine.hasAccess(account, MINE));
        assertEquals(OVERFLOW, SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.BASIC));
        assertEquals(40, account.credits());
    }

    @Test
    void playerAccountsCannotMutateEachOther() {
        PlayerAccount first = state(40, 0, 0, 0, 0);
        PlayerAccount second = fresh();
        PlayerAccount.Snapshot untouched = second.snapshot();
        first.tick(true);
        SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.BASIC);
        assertEquals(untouched, second.snapshot());
        assertNotEquals(first.uuid(), second.uuid());
    }

    @Test
    void snapshotRoundTripRetainsEveryPersistentValueAndIsDetached() {
        PlayerAccount original = state(123, 7_654, 22_222, 33_333, 44_444);
        PlayerAccount.Snapshot saved = original.snapshot();
        PlayerAccount loaded = PlayerAccount.restore(saved);
        assertEquals(saved, loaded.snapshot());
        loaded.tick(true);
        loaded.credit(1);
        assertEquals(saved, original.snapshot());
        assertEquals(6_000, loaded.trialEndTick());
    }

    @Test
    void invalidPersistentValuesAreRejectedRatherThanCreatingFreeCredit() {
        UUID uuid = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, -1, 0, 6_000, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, 0, -1, 6_000, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, 0, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, 0, 0, 6_000, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, 0, 0, 6_000, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerAccount.Snapshot(uuid, 0, 0, 6_000, 0, 0, -1));
        assertThrows(NullPointerException.class, () -> new PlayerAccount(null));
    }

    @Test
    void invalidOrOverflowingCreditLeavesBalanceUnchanged() {
        PlayerAccount account = fresh();
        account.setCredits(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> account.credit(1));
        assertThrows(IllegalArgumentException.class, () -> account.credit(-1));
        assertThrows(IllegalArgumentException.class, () -> account.setCredits(-1));
        assertEquals(Long.MAX_VALUE, account.credits());
    }

    private static PlayerAccount fresh() {
        return new PlayerAccount(UUID.randomUUID());
    }

    private static PlayerAccount state(long credits, long ticks, long mine, long sleep, long sprint) {
        return PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), credits, ticks, 6_000, mine, sleep, sprint));
    }
}
