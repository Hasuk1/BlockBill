package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static dev.blockbill.domain.ExchangeOffer.*;
import static dev.blockbill.domain.TransactionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeServiceTest {
    @Test
    void expansionOffersUsePublishedAmountsAndRejectPartialPayment() {
        ExchangeOffer[] offers = {COAL, WHEAT, COPPER_INGOT, REDSTONE, GOLD_INGOT, EMERALD};
        int[] quantities = {8, 16, 8, 16, 4, 1};
        long[] payments = {16, 12, 16, 20, 32, 28};
        for (int i = 0; i < offers.length; i++) {
            ExchangeOffer offer = offers[i];
            Slots shortInventory = new Slots(new Stack("minecraft:" + offer.id(), quantities[i] - 1, false));
            PlayerAccount account = account(7);
            assertEquals(INSUFFICIENT_ITEMS, ExchangeService.exchange(account, offer, 1, shortInventory, true));
            assertEquals(7, account.credits());
            assertArrayEquals(new int[]{quantities[i] - 1}, shortInventory.counts());
            Slots fullInventory = new Slots(new Stack("minecraft:" + offer.id(), quantities[i], false));
            assertEquals(SUCCESS, ExchangeService.exchange(account, offer, 1, fullInventory, true));
            assertEquals(7 + payments[i], account.credits());
            assertArrayEquals(new int[]{0}, fullInventory.counts());
        }
    }

    @Test
    void mixedVanillaLogSpeciesAndStacksPayTwoBundlesExactly() {
        PlayerAccount account = account(5);
        Slots slots = new Slots(
                new Stack("minecraft:oak_log", 7, true),
                new Stack("minecraft:birch_log", 19, true),
                new Stack("minecraft:spruce_log", 12, true),
                new Stack("minecraft:diamond", 10, false));
        assertEquals(SUCCESS, ExchangeService.exchange(account, LOGS, 2, slots, true));
        assertEquals(29, account.credits());
        assertArrayEquals(new int[]{0, 0, 6, 10}, slots.counts());
        assertEquals(3, slots.removals);
    }

    @Test
    void everyResourceUsesItsPublishedRate() {
        for (ExchangeOffer offer : ExchangeOffer.values()) {
            String item = offer == LOGS ? "oak_log" : offer.id();
            Slots slots = new Slots(new Stack("minecraft:" + item, offer.itemsPerBundle() * 3 + 1, offer == LOGS));
            PlayerAccount account = account(0);
            assertEquals(SUCCESS, ExchangeService.exchange(account, offer, 3, slots, true));
            assertEquals(offer.creditsPerBundle() * 3, account.credits());
            assertArrayEquals(new int[]{1}, slots.counts());
        }
    }

    @Test
    void fifteenLogsFailWithoutTakingAnyOfSeveralStacks() {
        Slots slots = new Slots(new Stack("minecraft:oak_log", 7, true), new Stack("minecraft:birch_log", 8, true));
        PlayerAccount account = account(10);
        PlayerAccount.Snapshot before = account.snapshot();
        assertEquals(INSUFFICIENT_ITEMS, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertEquals(before, account.snapshot());
        assertArrayEquals(new int[]{7, 8}, slots.counts());
        assertEquals(0, slots.removals);
    }

    @Test
    void moddedLogsAndUnrelatedVanillaResourcesDoNotCount() {
        Slots slots = new Slots(new Stack("anothermod:oak_log", 64, true), new Stack("minecraft:oak_planks", 64, false));
        PlayerAccount account = account(0);
        assertEquals(INSUFFICIENT_ITEMS, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertArrayEquals(new int[]{64, 64}, slots.counts());
        assertEquals(0, account.credits());
    }

    @Test
    void missingItemsAndEmptyInventoryDoNotGrantCredits() {
        PlayerAccount account = account(0);
        assertEquals(INSUFFICIENT_ITEMS, ExchangeService.exchange(account, IRON_INGOT, 1, new Slots(), true));
        assertEquals(0, account.credits());
    }

    @Test
    void zeroNegativeAndOversizedRequestsAreRejectedBeforeReadingInventory() {
        for (int bundles : new int[]{Integer.MIN_VALUE, -1, 0, MAX_BUNDLES + 1, Integer.MAX_VALUE}) {
            PlayerAccount account = account(0);
            assertEquals(INVALID_AMOUNT, ExchangeService.exchange(account, LOGS, bundles, unreadableInventory(), true));
            assertEquals(0, account.credits());
        }
    }

    @Test
    void maximumAllowedRequestDoesNotOverflowItemCount() {
        Slots slots = new Slots(new Stack("minecraft:cobblestone", 32_000_000, false));
        PlayerAccount account = account(0);
        assertEquals(SUCCESS, ExchangeService.exchange(account, COBBLESTONE, MAX_BUNDLES, slots, true));
        assertEquals(16_000_000, account.credits());
        assertArrayEquals(new int[]{0}, slots.counts());
    }

    @Test
    void nonSurvivalCannotEarnCreditsOrEvenAccessInventory() {
        PlayerAccount account = account(5);
        assertEquals(NOT_SURVIVAL, ExchangeService.exchange(account, LOGS, 1, unreadableInventory(), false));
        assertEquals(5, account.credits());
    }

    @Test
    void balanceOverflowRejectsBeforeAnyInventoryMutation() {
        PlayerAccount account = account(Long.MAX_VALUE - 11);
        Slots slots = new Slots(new Stack("minecraft:oak_log", 16, true));
        assertEquals(OVERFLOW, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertEquals(Long.MAX_VALUE - 11, account.credits());
        assertArrayEquals(new int[]{16}, slots.counts());
        assertEquals(0, slots.removals);
    }

    @Test
    void exactMaximumBalanceIsAllowed() {
        PlayerAccount account = account(Long.MAX_VALUE - 12);
        Slots slots = new Slots(new Stack("minecraft:oak_log", 16, true));
        assertEquals(SUCCESS, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertEquals(Long.MAX_VALUE, account.credits());
        assertArrayEquals(new int[]{0}, slots.counts());
    }

    @Test
    void repeatingRequestCannotSellTheSameItemsTwice() {
        PlayerAccount account = account(0);
        Slots slots = new Slots(new Stack("minecraft:oak_log", 16, true));
        assertEquals(SUCCESS, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertEquals(INSUFFICIENT_ITEMS, ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertEquals(12, account.credits());
        assertEquals(1, slots.removals);
    }

    @Test
    void invalidSlotCountFailsBeforeChangingEarlierMatchingStacks() {
        PlayerAccount account = account(0);
        Slots slots = new Slots(new Stack("minecraft:oak_log", 7, true), new Stack("minecraft:birch_log", -1, true));
        assertThrows(IllegalArgumentException.class, () -> ExchangeService.exchange(account, LOGS, 1, slots, true));
        assertArrayEquals(new int[]{7, -1}, slots.counts());
        assertEquals(0, slots.removals);
        assertEquals(0, account.credits());
    }

    private static PlayerAccount account(long credits) {
        PlayerAccount account = new PlayerAccount(UUID.randomUUID());
        account.setCredits(credits);
        return account;
    }

    private static ExchangeService.Inventory unreadableInventory() {
        return new ExchangeService.Inventory() {
            public int size() { throw new AssertionError("Inventory should not be inspected"); }
            public int count(int slot) { throw new AssertionError(); }
            public boolean matches(int slot, ExchangeOffer offer) { throw new AssertionError(); }
            public void remove(int slot, int count) { throw new AssertionError(); }
        };
    }

    private static final class Stack {
        final String id;
        int count;
        final boolean log;

        Stack(String id, int count, boolean log) {
            this.id = id;
            this.count = count;
            this.log = log;
        }
    }

    private static final class Slots implements ExchangeService.Inventory {
        final Stack[] stacks;
        int removals;

        Slots(Stack... stacks) { this.stacks = stacks; }

        public int size() { return stacks.length; }
        public int count(int slot) { return stacks[slot].count; }

        public boolean matches(int slot, ExchangeOffer offer) {
            Stack stack = stacks[slot];
            return offer == LOGS
                    ? stack.id.startsWith("minecraft:") && stack.log
                    : stack.id.equals("minecraft:" + offer.id());
        }

        public void remove(int slot, int count) {
            assertTrue(count > 0 && count <= stacks[slot].count);
            stacks[slot].count -= count;
            removals++;
        }

        int[] counts() { return Arrays.stream(stacks).mapToInt(stack -> stack.count).toArray(); }
    }
}
