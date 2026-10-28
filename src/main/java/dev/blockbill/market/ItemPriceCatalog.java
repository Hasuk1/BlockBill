package dev.blockbill.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ContainerComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Packaged server price policy. Stack names, enchantments, and client quotes never increase value. */
public final class ItemPriceCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger("blockbill.market");
    private static final long MODDED_FALLBACK_CENTS = 1;
    private static final Set<String> WARNED_UNKNOWN_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> PRICES = load();

    private ItemPriceCatalog() { }

    public static Map<String, Long> prices() { return PRICES; }

    /** Run after registries are available, before accepting sales. A version mismatch must be visible. */
    public static void validateRegistry() {
        Set<String> registered = new TreeSet<>();
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals("minecraft") && item != Items.AIR) registered.add(id.toString());
        }
        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(PRICES.keySet());
        Set<String> unexpected = new TreeSet<>(PRICES.keySet());
        unexpected.removeAll(registered);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalStateException("BlockBill price catalog differs from the vanilla item registry. Missing="
                    + missing + "; unexpected=" + unexpected);
        }
        LOGGER.info("Validated explicit prices for {} vanilla items (100 cents = 1 credit)", registered.size());
    }

    /** Per-item value, not stack value. Damaged equipment is discounted without integer overflow. */
    public static long unitPriceCents(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var identifier = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = identifier == null ? "unregistered" : identifier.toString();
        Long configured = PRICES.get(id);
        long base;
        if (configured == null) {
            if (WARNED_UNKNOWN_ITEMS.add(id)) {
                LOGGER.warn("No explicit BlockBill price for {}; using conservative {} cent per-item fallback", id, MODDED_FALLBACK_CENTS);
            }
            base = MODDED_FALLBACK_CENTS;
        } else {
            base = configured;
        }
        if (!stack.isDamageableItem()) return base;
        int maximum = stack.getMaxDamage();
        if (maximum <= 0) return base;
        long remaining = Math.max(0L, (long) maximum - Math.max(0, stack.getDamageValue()));
        // Both factors of the remainder product are <= Integer.MAX_VALUE, so it cannot overflow long.
        long discounted = base / maximum * remaining + (base % maximum) * remaining / maximum;
        return Math.max(1, discounted);
    }

    /** A blocked sale must leave the stack and every contained item untouched. Values are localization keys. */
    public static Optional<String> saleBlockedReason(ItemStack stack) {
        if (stack.isEmpty()) return Optional.of("blockbill.market.blocked.empty");
        for (var component : stack.getComponents()) {
            if (component.value() instanceof ContainerComponent<?> contents
                    && contents.itemCopies().anyMatch(item -> !item.isEmpty())) {
                return Optional.of("blockbill.market.blocked.container");
            }
        }
        var bees = stack.get(DataComponents.BEES);
        if (bees != null && !bees.bees().isEmpty()) return Optional.of("blockbill.market.blocked.occupied");
        if (stack.has(DataComponents.CONTAINER_LOOT)) return Optional.of("blockbill.market.blocked.unopened");
        var blockData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockData != null && (blockData.contains("Items") || blockData.contains("items")
                || blockData.contains("LootTable") || blockData.contains("loot_table"))) {
            return Optional.of("blockbill.market.blocked.container");
        }
        return Optional.empty();
    }

    private static Map<String, Long> load() {
        var stream = ItemPriceCatalog.class.getResourceAsStream("/assets/blockbill/item_prices.json");
        if (stream == null) throw new IllegalStateException("Missing packaged BlockBill item price catalog");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var document = JsonParser.parseReader(reader).getAsJsonObject();
            if (document.get("currency_scale").getAsInt() != 100) throw new IllegalStateException("Wrong market currency scale");
            Map<String, Long> prices = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : document.getAsJsonObject("prices").entrySet()) {
                String id = entry.getKey();
                long price = entry.getValue().getAsBigDecimal().longValueExact();
                if (!id.startsWith("minecraft:") || id.equals("minecraft:air") || price <= 0) {
                    throw new IllegalStateException("Invalid explicit price: " + id + "=" + price);
                }
                prices.put(id, price);
            }
            if (prices.isEmpty()) throw new IllegalStateException("Empty BlockBill item price catalog");
            return Collections.unmodifiableMap(prices);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read packaged BlockBill item price catalog", exception);
        }
    }
}
