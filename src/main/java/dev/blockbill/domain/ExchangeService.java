package dev.blockbill.domain;

import java.util.Objects;

public final class ExchangeService {
    private ExchangeService() { }

    /**
     * Performs the entire transaction on the server thread. Inventory must remain stable for the call.
     * Every argument, quantity and balance check completes before the first mutation.
     */
    public static TransactionResult exchange(PlayerAccount account, ExchangeOffer offer, int bundles,
                                             Inventory inventory, boolean survival) {
        Objects.requireNonNull(account);
        Objects.requireNonNull(offer);
        Objects.requireNonNull(inventory);
        if (!survival) {
            return TransactionResult.NOT_SURVIVAL;
        }
        if (bundles <= 0 || bundles > ExchangeOffer.MAX_BUNDLES) {
            return TransactionResult.INVALID_AMOUNT;
        }

        final long required;
        final long balance;
        try {
            required = Math.multiplyExact((long) offer.itemsPerBundle(), bundles);
            balance = Math.addExact(account.credits(), Math.multiplyExact(offer.creditsPerBundle(), bundles));
        } catch (ArithmeticException overflow) {
            return TransactionResult.OVERFLOW;
        }

        int[] removals = new int[inventory.size()];
        long remaining = required;
        for (int slot = 0; slot < removals.length && remaining > 0; slot++) {
            if (inventory.matches(slot, offer)) {
                int count = inventory.count(slot);
                if (count < 0) {
                    throw new IllegalArgumentException("Inventory stack counts must not be negative");
                }
                removals[slot] = (int) Math.min(remaining, count);
                remaining -= removals[slot];
            }
        }
        if (remaining != 0) {
            return TransactionResult.INSUFFICIENT_ITEMS;
        }

        for (int slot = 0; slot < removals.length; slot++) {
            if (removals[slot] != 0) {
                inventory.remove(slot, removals[slot]);
            }
        }
        account.setCredits(balance);
        return TransactionResult.SUCCESS;
    }

    /** Adapter over the player's slots, never an external container or the cursor stack. */
    public interface Inventory {
        int size();
        int count(int slot);
        boolean matches(int slot, ExchangeOffer offer);

        /**
         * Remove exactly count items from the prevalidated slot. Implement as a direct stack
         * mutation: this must not throw, invoke callbacks, or change any other slot.
         */
        void remove(int slot, int count);
    }
}
