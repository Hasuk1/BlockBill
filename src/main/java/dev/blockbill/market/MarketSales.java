package dev.blockbill.market;

import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ExchangeOffer;
import dev.blockbill.domain.TransactionResult;
import dev.blockbill.fabric.PlayerInventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Server-thread transactions over carried slots. All validation precedes all mutations. */
public final class MarketSales {
    private MarketSales() { }

    public static String sell(ServerPlayer player, PlayerAccount account, int slot, int amount, ItemStack expected) {
        if (!player.getUUID().equals(account.uuid())) return "hub.error.invalid";
        if (player.gameMode() != GameType.SURVIVAL) return "hub.error.survival";
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return "hub.error.container";
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize() || amount < 1) return "hub.error.invalid";
        ItemStack current = inventory.getItem(slot);
        if (current.isEmpty() || !ItemStack.matches(expected, current)) return "hub.error.stale";
        if (amount > current.getCount()) return "hub.error.invalid";
        var blocked = ItemPriceCatalog.saleBlockedReason(current);
        if (blocked.isPresent()) return blocked.get();
        long total;
        try { total = Math.multiplyExact(ItemPriceCatalog.unitPriceCents(current), amount); }
        catch (ArithmeticException exception) { return "hub.error.overflow"; }
        if (total <= 0 || !account.canCreditCents(total)) return "hub.error.overflow";
        current.shrink(amount);
        account.creditCents(total);
        synchronize(player);
        return "hub.sale.success";
    }

    /** Legacy exchange commands use exactly the same per-item valuation as the inventory shop. */
    public static TransactionResult exchange(ServerPlayer player, PlayerAccount account, ExchangeOffer offer, int bundles) {
        if (!player.getUUID().equals(account.uuid())) return TransactionResult.INVALID_AMOUNT;
        if (player.gameMode() != GameType.SURVIVAL) return TransactionResult.NOT_SURVIVAL;
        if (bundles < 1 || bundles > ExchangeOffer.MAX_BUNDLES) return TransactionResult.INVALID_AMOUNT;
        var view = new PlayerInventory(player);
        int[] removals = new int[view.size()];
        long needed = (long) offer.itemsPerBundle() * bundles;
        long total = 0;
        try {
            for (int i = 0; i < removals.length && needed > 0; i++) {
                if (!view.matches(i, offer)) continue;
                ItemStack stack = player.getInventory().getItem(i);
                if (ItemPriceCatalog.saleBlockedReason(stack).isPresent()) continue;
                removals[i] = (int) Math.min(needed, stack.getCount());
                total = Math.addExact(total, Math.multiplyExact(ItemPriceCatalog.unitPriceCents(stack), removals[i]));
                needed -= removals[i];
            }
        } catch (ArithmeticException exception) { return TransactionResult.OVERFLOW; }
        if (needed != 0) return TransactionResult.INSUFFICIENT_ITEMS;
        if (!account.canCreditCents(total)) return TransactionResult.OVERFLOW;
        for (int i = 0; i < removals.length; i++) if (removals[i] != 0) view.remove(i, removals[i]);
        account.creditCents(total);
        synchronize(player);
        return TransactionResult.SUCCESS;
    }

    private static void synchronize(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }
}
