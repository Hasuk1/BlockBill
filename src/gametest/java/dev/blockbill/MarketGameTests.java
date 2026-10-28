package dev.blockbill;

import dev.blockbill.market.ItemPriceCatalog;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;

import java.util.List;
import java.util.TreeSet;

/** Uses the actual 26.3 registry, component implementations and equipment durability. */
public final class MarketGameTests {
    @GameTest
    public void everyVanillaRegistryItemHasAnExplicitPositiveServerPrice(GameTestHelper helper) {
        ItemPriceCatalog.validateRegistry();
        var expected = new TreeSet<String>();
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals("minecraft") || item == Items.AIR) continue;
            expected.add(id.toString());
            ItemStack stack = new ItemStack(item);
            long price = ItemPriceCatalog.unitPriceCents(stack);
            helper.assertTrue(price > 0, "Unpriced registered item: " + id);
            helper.assertTrue(price == ItemPriceCatalog.prices().get(id.toString()), "Default stack differs from explicit price: " + id);
        }
        helper.assertTrue(expected.equals(ItemPriceCatalog.prices().keySet()), "Catalog and authoritative registry do not match exactly");
        helper.assertTrue(expected.size() == 1_657, "Unexpected vanilla registry version");
        helper.assertTrue(ItemPriceCatalog.unitPriceCents(ItemStack.EMPTY) == 0, "AIR/empty stack became saleable value");
        helper.succeed();
    }

    @GameTest
    public void equipmentDamageIsLinearAndStackCountOrCustomNameCannotInflateUnitPrice(GameTestHelper helper) {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        long full = ItemPriceCatalog.unitPriceCents(tool);
        int maximum = tool.getMaxDamage();
        long previous = full;
        for (int damage : new int[]{0, 1, maximum / 2, maximum - 1, maximum}) {
            tool.setDamageValue(damage);
            long expected = Math.max(1, full * (maximum - damage) / maximum);
            long actual = ItemPriceCatalog.unitPriceCents(tool);
            helper.assertTrue(actual == expected && actual <= previous, "Nonlinear or increasing damage price at " + damage);
            previous = actual;
        }
        ItemStack log = new ItemStack(Items.OAK_LOG, 64);
        log.set(DataComponents.CUSTOM_NAME, Component.literal("Legendary diamond premium log"));
        helper.assertTrue(ItemPriceCatalog.unitPriceCents(log) == 75, "Stack size/name inflated a unit price");
        helper.assertTrue(ItemPriceCatalog.unitPriceCents(log.copyWithCount(1)) == 75, "Unit price depends on stack count");
        helper.succeed();
    }

    @GameTest
    public void filledContainersBundlesAndLoadedWeaponsAreBlockedWithoutChangingContents(GameTestHelper helper) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStackTemplate(Items.EMERALD, 2))));
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        for (ItemStack stack : List.of(shulker, bundle, crossbow)) {
            ItemStack before = stack.copy();
            helper.assertTrue(ItemPriceCatalog.saleBlockedReason(stack).isPresent(), "A filled item was saleable: " + stack);
            helper.assertTrue(ItemStack.matches(before, stack), "Checking a sale changed contained items");
        }
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        bundle.set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
        for (ItemStack empty : List.of(shulker, bundle, crossbow)) {
            helper.assertTrue(ItemPriceCatalog.saleBlockedReason(empty).isEmpty(), "An emptied item remained blocked");
            helper.assertTrue(ItemPriceCatalog.unitPriceCents(empty) > 0, "An emptied item lost its sale value");
        }
        helper.succeed();
    }

    @GameTest
    public void occupiedBeehivesAreProtectedButEmptyOnesCanBeSold(GameTestHelper helper) {
        ItemStack hive = new ItemStack(Items.BEEHIVE);
        hive.set(DataComponents.BEES, new Bees(List.of(BeehiveBlockEntity.Occupant.create(0))));
        ItemStack before = hive.copy();
        helper.assertTrue(ItemPriceCatalog.saleBlockedReason(hive).isPresent(), "Sale could destroy a contained bee");
        helper.assertTrue(ItemStack.matches(before, hive), "Checking a sale altered bees");
        hive.set(DataComponents.BEES, Bees.EMPTY);
        helper.assertTrue(ItemPriceCatalog.saleBlockedReason(hive).isEmpty(), "Empty beehive was blocked");
        helper.succeed();
    }
}
