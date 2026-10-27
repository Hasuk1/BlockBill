package dev.blockbill.fabric;

import dev.blockbill.domain.ExchangeOffer;
import dev.blockbill.domain.ExchangeService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A server-thread view of carried inventory, including equipment and offhand; never external containers. */
public final class PlayerInventory implements ExchangeService.Inventory {
    private final Inventory inventory;

    public PlayerInventory(ServerPlayer player) {
        inventory = player.getInventory();
    }

    @Override public int size() { return inventory.getContainerSize(); }
    @Override public int count(int slot) { return inventory.getItem(slot).getCount(); }

    @Override
    public boolean matches(int slot, ExchangeOffer offer) {
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) return false;
        return switch (offer) {
            case LOGS -> stack.is(ItemTags.LOGS) && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft");
            case COBBLESTONE -> stack.is(Items.COBBLESTONE);
            case ROTTEN_FLESH -> stack.is(Items.ROTTEN_FLESH);
            case IRON_INGOT -> stack.is(Items.IRON_INGOT);
            case COAL -> stack.is(Items.COAL);
            case WHEAT -> stack.is(Items.WHEAT);
            case COPPER_INGOT -> stack.is(Items.COPPER_INGOT);
            case REDSTONE -> stack.is(Items.REDSTONE);
            case GOLD_INGOT -> stack.is(Items.GOLD_INGOT);
            case EMERALD -> stack.is(Items.EMERALD);
        };
    }

    @Override public void remove(int slot, int count) { inventory.getItem(slot).shrink(count); }
}
