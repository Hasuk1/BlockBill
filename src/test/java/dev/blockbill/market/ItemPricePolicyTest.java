package dev.blockbill.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ItemPricePolicyTest {
    private static final Map<String, Long> PRICES = readPrices();

    @Test
    void explicitVanillaCatalogContainsPositiveWholeCentsWithoutAir() {
        assertEquals(1_657, PRICES.size());
        assertFalse(PRICES.containsKey("minecraft:air"));
        PRICES.forEach((item, price) -> {
            assertTrue(item.matches("minecraft:[a-z0-9_]+"), item);
            assertTrue(price > 0, item);
        });
    }

    @Test
    void basicAcquisitionAnchorsAndFarmDiscountsMatchReviewedPolicy() {
        assertEquals(75L, price("oak_log"));
        assertEquals(75L, price("birch_log"));
        assertEquals(75L, price("spruce_log"));
        assertEquals(18L, price("oak_planks"));
        assertEquals(5L, price("stone"));
        assertEquals(5L, price("cobblestone"));
        assertEquals(4L, price("rotten_flesh"));
        assertEquals(4L, price("wheat"));
        assertEquals(2L, price("bamboo"));
        assertEquals(2L, price("sugar_cane"));
        assertEquals(225L, price("iron_ingot"));
        assertEquals(90L, price("copper_ingot"));
        assertEquals(450L, price("gold_ingot"));
        assertEquals(2_000L, price("diamond"));
    }

    @Test
    void reversibleCompressionPreservesValueAndWoodCraftingCannotMultiplyCredits() {
        for (String metal : Set.of("iron", "gold", "copper")) {
            assertEquals(9 * price(metal + "_nugget"), price(metal + "_ingot"));
            assertEquals(9 * price(metal + "_ingot"), price(metal + "_block"));
        }
        for (String resource : Set.of("diamond", "emerald", "coal", "redstone")) {
            assertEquals(9 * price(resource), price(resource + "_block"));
        }
        assertTrue(4 * price("oak_planks") <= price("oak_log"));
        assertTrue(4 * price("stick") <= 2 * price("oak_planks"));
        assertTrue(16 * price("glass_pane") <= 6 * price("glass"));
    }

    @Test
    void everyCapturedVanillaRecipeAndWorldTransformCannotIncreaseSaleValue() {
        JsonArray constraints = read("/market/recipe_constraints.json").getAsJsonArray();
        assertEquals(2_100, constraints.size(), "Regenerate fixtures alongside the complete exact-version catalog");
        for (JsonElement value : constraints) {
            JsonObject recipe = value.getAsJsonObject();
            boolean remainders = recipe.get("remainders").getAsBoolean();
            BigInteger input = BigInteger.ZERO;
            for (JsonElement ingredient : recipe.getAsJsonArray("ingredients")) {
                BigInteger cheapest = null;
                for (JsonElement alternative : ingredient.getAsJsonArray()) {
                    String id = alternative.getAsString();
                    BigInteger cost = BigInteger.valueOf(PRICES.get(id));
                    if (remainders && Set.of("minecraft:milk_bucket", "minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:powder_snow_bucket").contains(id)) {
                        cost = cost.subtract(BigInteger.valueOf(price("bucket")));
                    } else if (remainders && id.equals("minecraft:honey_bottle")) {
                        cost = cost.subtract(BigInteger.valueOf(price("glass_bottle")));
                    }
                    cheapest = cheapest == null ? cost : cheapest.min(cost);
                }
                assertNotNull(cheapest, recipe.toString());
                input = input.add(cheapest);
            }
            BigInteger output = BigInteger.valueOf(PRICES.get(recipe.get("output").getAsString()))
                    .multiply(recipe.get("count").getAsBigInteger());
            assertTrue(output.compareTo(input) <= 0, recipe.get("recipe").getAsString() + ": output=" + output + ", input=" + input);
        }
    }

    private static long price(String id) { return PRICES.get("minecraft:" + id); }

    private static Map<String, Long> readPrices() {
        JsonObject catalog = read("/assets/blockbill/item_prices.json").getAsJsonObject();
        assertEquals(100, catalog.get("currency_scale").getAsInt());
        assertEquals("26.3", catalog.get("minecraft_version").getAsString());
        Map<String, Long> prices = new TreeMap<>();
        catalog.getAsJsonObject("prices").entrySet().forEach(entry -> prices.put(entry.getKey(), entry.getValue().getAsBigDecimal().longValueExact()));
        return prices;
    }

    private static JsonElement read(String path) {
        var stream = ItemPricePolicyTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (java.io.IOException exception) {
            throw new AssertionError("Cannot read pricing fixture " + path, exception);
        }
    }
}
