package dev.blockbill.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FreeAndAdvertisingTest {
    @Test void smallSalesAccumulateWithoutRoundingAwayMoney() {
        PlayerAccount account = new PlayerAccount(UUID.randomUUID());
        for (int i = 0; i < 99; i++) account.creditCents(1);
        assertEquals("0.99", account.formattedCredits());
        account.creditCents(2);
        assertEquals("1.01", account.formattedCredits());
        assertEquals(account.snapshot(), PlayerAccount.restore(account.snapshot()).snapshot());
    }

    @Test void wholeCreditPurchasesPreserveFractionalMoney() {
        PlayerAccount account = new PlayerAccount(UUID.randomUUID());
        account.creditCents(9_037);
        assertEquals(TransactionResult.SUCCESS, SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.PLUS));
        assertEquals("0.37", account.formattedCredits());
        var before = account.snapshot();
        assertEquals(TransactionResult.INSUFFICIENT_CREDITS, SubscriptionEngine.purchase(account, ServiceType.FISH));
        assertEquals(before, account.snapshot());
    }

    @Test void fractionalCarryOverflowFailsWithoutChangingEitherPart() {
        PlayerAccount account = new PlayerAccount(UUID.randomUUID());
        account.setCredits(Long.MAX_VALUE);
        account.creditCents(99);
        var before = account.snapshot();
        assertFalse(account.canCreditCents(1));
        assertThrows(ArithmeticException.class, () -> account.creditCents(1));
        assertEquals(before, account.snapshot());
        assertThrows(IllegalArgumentException.class, () -> account.creditCents(-1));
        assertEquals(before, account.snapshot());
    }

    @Test void advertisementLastsExactlySixHundredActiveTicksAndCannotStack() {
        PlayerAccount account = expired();
        assertTrue(account.grantAdvertisement());
        for (int i = 0; i < 599; i++) account.tick(true);
        for (ServiceType service : ServiceType.values()) assertTrue(SubscriptionEngine.hasAccess(account, service));
        assertEquals(1, account.adRemainingTicks());
        var before = account.snapshot();
        assertFalse(account.grantAdvertisement());
        assertEquals(before, account.snapshot());
        account.tick(true);
        for (ServiceType service : ServiceType.values()) assertFalse(SubscriptionEngine.hasAccess(account, service));
    }

    @Test void cooldownAndRewardPersistAndPauseOutsideSurvival() {
        PlayerAccount account = expired();
        assertTrue(account.grantAdvertisement());
        account = PlayerAccount.restore(account.snapshot());
        for (int i = 0; i < 500; i++) account.tick(false);
        assertEquals(600, account.adRemainingTicks());
        assertEquals(1800, account.adCooldownTicks());
        for (int i = 0; i < 1799; i++) account.tick(true);
        assertFalse(account.grantAdvertisement());
        account.tick(true);
        assertTrue(account.grantAdvertisement());
    }

    @Test void adDoesNotChangePaidDeadlinesOrEnableAutomaticSpending() {
        PlayerAccount account = expired();
        account.setCredits(540);
        SubscriptionEngine.purchaseBundle(account, SubscriptionBundle.BUSINESS);
        var before = account.snapshot();
        assertTrue(account.grantAdvertisement());
        for (ServiceType service : ServiceType.values()) {
            assertEquals(PlayerAccount.restore(before).paidUntil(service), account.paidUntil(service));
            assertFalse(account.autoRenew(service));
        }
        assertEquals(before.credits(), account.credits());
    }

    @Test void hugeClockCannotWrapAdvertisingTime() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 0, Long.MAX_VALUE - 500,
                6000, 0, 0, 0));
        var before = account.snapshot();
        assertFalse(account.grantAdvertisement());
        assertEquals(before, account.snapshot());
    }

    @Test void legacySnapshotsDoNotMintFractionalCreditsAdvertisingOrTravel() {
        PlayerAccount account = PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 75, 7000, 6000, 18000, 0, 0));
        assertEquals("75.00", account.formattedCredits());
        assertEquals(0, account.adRemainingTicks());
        assertEquals(0, account.adCooldownTicks());
        assertFalse(SubscriptionEngine.hasAccess(account, ServiceType.TRAVEL));
    }

    private static PlayerAccount expired() {
        return PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), 0, 6000, 6000, 0, 0, 0));
    }
}
