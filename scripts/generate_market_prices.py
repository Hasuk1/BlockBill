#!/usr/bin/env python3
"""Generate the explicit BlockBill price table from an exact vanilla registry and Mojang recipe resources.

No Minecraft execution or network access. The registry is dumped by the separately run test/bootstrap.
All natural items need a reviewed category; crafted items are bounded by their cheapest vanilla recipe.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import zipfile

NS = "minecraft:"
CRAFT_CEILING = 1_000_000


def names(words):
    return {NS + name for name in words.split()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/assets/blockbill/item_prices.json"))
    parser.add_argument("--report", type=Path, default=Path(".local/research/market-price-report.json"))
    parser.add_argument("--constraints", type=Path, default=Path("src/test/resources/market/recipe_constraints.json"))
    args = parser.parse_args()
    registry = set(json.loads(args.registry.read_text())) - {NS + "air"}
    with zipfile.ZipFile(args.minecraft_jar) as archive:
        recipes = {name.removeprefix("data/minecraft/recipe/").removesuffix(".json"): json.loads(archive.read(name))
                   for name in archive.namelist() if name.startswith("data/minecraft/recipe/") and name.endswith(".json")}
        raw_tags = {NS + name.removeprefix("data/minecraft/tags/item/").removesuffix(".json"): json.loads(archive.read(name))["values"]
                    for name in archive.namelist() if name.startswith("data/minecraft/tags/item/") and name.endswith(".json")}
    tags = {}

    def expand(value):
        if isinstance(value, list):
            return set().union(*(expand(entry) for entry in value))
        if isinstance(value, dict):
            return expand(value.get("item", value.get("id")))
        if value.startswith("#"):
            tag = value[1:]
            if tag not in tags:
                tags[tag] = set().union(*(expand(entry) for entry in raw_tags[tag]))
            return tags[tag] & registry
        return {value} & registry

    constraints = []
    skipped = []
    same_item_special = []
    for name, recipe in sorted(recipes.items()):
        kind = recipe["type"].removeprefix(NS)
        result = recipe.get("result", recipe.get("output", {}))
        output = result.get("id")
        count = result.get("count", 1)
        ingredients = None
        if kind == "crafting_shaped":
            symbols = collections.Counter("".join(recipe["pattern"]).replace(" ", ""))
            ingredients = [recipe["key"][symbol] for symbol, amount in symbols.items() for _ in range(amount)]
        elif kind == "crafting_shapeless":
            ingredients = recipe["ingredients"]
        elif kind in ("stonecutting", "smelting", "blasting", "smoking", "campfire_cooking"):
            ingredients = [recipe["ingredient"]]
        elif kind == "crafting_transmute":
            if not output and recipe.get("add_material_count_to_result"):
                for copied in sorted(expand(recipe["input"])):
                    constraints.append({"recipe": name + "/" + copied.removeprefix(NS), "output": copied, "count": 2,
                                        "ingredients": [[copied], sorted(expand(recipe["material"]))], "remainders": True})
                continue
            ingredients = [recipe["input"], recipe["material"]]
        elif kind == "smithing_transform":
            ingredients = [recipe["template"], recipe["base"], recipe["addition"]]
        elif kind == "crafting_decorated_pot":
            ingredients = [recipe[side] for side in ("back", "front", "left", "right")]
        elif kind == "crafting_special_firework_rocket":
            ingredients = [recipe["fuel"], recipe["shell"]]
        elif kind == "crafting_special_firework_star":
            ingredients = [recipe["fuel"], recipe["dye"]]
        elif kind == "crafting_special_bookcloning":
            ingredients = [recipe["material"]]  # The source book is returned, not consumed.
        elif kind == "crafting_imbue":
            ingredients = [recipe["material"]] * count + [recipe["source"]]
        elif kind == "brewing":
            # A batch can transform three bottles with one reagent.
            ingredients = [recipe["input"]] * 3 + [recipe["reagent"]]
            count = 3
        elif kind in ("smithing_trim", "crafting_dye", "crafting_special_bannerduplicate", "crafting_special_firework_star_fade",
                      "crafting_special_shielddecoration", "crafting_special_repairitem", "crafting_special_mapextending"):
            same_item_special.append(name)  # Components never increase a catalog price.
            continue
        if not output or ingredients is None:
            skipped.append(name)
            continue
        choices = [sorted(expand(ingredient)) for ingredient in ingredients]
        if output not in registry or any(not choice for choice in choices):
            raise ValueError(f"Unresolved recipe registry/tag reference: {name}")
        constraints.append({"recipe": name, "output": output, "count": count, "ingredients": choices,
                            "remainders": kind.startswith("crafting_")})

    produced = {entry["output"] for entry in constraints}
    groups = [
        (1, "creative-only", "air barrier bedrock budding_amethyst chain_command_block command_block command_block_minecart debug_stick end_portal_frame jigsaw knowledge_book light reinforced_deepslate repeating_command_block spawner structure_block structure_void test_block test_instance_block trial_spawner vault petrified_oak_slab"),
        (2, "bulk-earth", "dirt dirt_path grass_block podzol mycelium rooted_dirt coarse_dirt mud muddy_mangrove_roots farmland sand red_sand gravel suspicious_sand suspicious_gravel netherrack soul_sand soul_soil"),
        (3, "bulk-sand", "sand red_sand"),
        (5, "bulk-stone", "stone cobblestone deepslate cobbled_deepslate andesite diorite granite basalt smooth_basalt blackstone calcite tuff cinnabar sulfur end_stone dripstone_block pointed_dripstone"),
        (2, "automatable-plants", "bamboo cactus kelp dried_kelp sugar_cane wheat_seeds beetroot_seeds melon_seeds pumpkin_seeds sweet_berries glow_berries nether_wart cocoa_beans"),
        (4, "farm-produce", "wheat beetroot potato carrot apple melon_slice poisonous_potato brown_mushroom red_mushroom chorus_fruit"),
        (8, "farm-produce", "pumpkin melon carved_pumpkin"),
        (4, "common-mob-drop", "rotten_flesh string feather spider_eye ink_sac glow_ink_sac egg blue_egg brown_egg armadillo_scute"),
        (9, "common-mob-drop", "bone"),
        (3, "common-mob-drop", "bone_meal"),
        (10, "common-mob-drop", "gunpowder slime_ball magma_cream leather rabbit_hide"),
        (8, "farm-meat", "beef chicken porkchop rabbit mutton cod salmon tropical_fish pufferfish"),
        (25, "fuel-mineral", "coal charcoal"),
        (90, "metal", "copper_ingot raw_copper"),
        (10, "metal", "copper_nugget"),
        (225, "metal", "iron_ingot raw_iron"),
        (25, "metal", "iron_nugget"),
        (450, "metal", "gold_ingot raw_gold"),
        (50, "metal", "gold_nugget"),
        (20, "mineral", "redstone lapis_lazuli quartz amethyst_shard glowstone_dust flint clay_ball brick resin_clump resin_brick"),
        (2_000, "rare-mineral", "diamond"),
        (250, "trade-mineral", "emerald"),
        (4_000, "rare-mineral", "ancient_debris netherite_scrap"),
        (16_000, "rare-mineral", "netherite_ingot"),
        (75, "renewable-adventure-drop", "ender_pearl blaze_rod breeze_rod prismarine_shard prismarine_crystals honeycomb"),
        (100, "adventure-drop", "ghast_tear phantom_membrane rabbit_foot turtle_scute nautilus_shell shulker_shell"),
        (500, "adventure-loot", "echo_shard experience_bottle ominous_bottle goat_horn name_tag saddle sponge wet_sponge trial_key ominous_trial_key"),
        (1_000, "rare-loot", "dragon_breath heart_of_the_sea totem_of_undying disc_fragment_5"),
        (10_000, "boss-loot", "nether_star dragon_egg"),
        (20_000, "rare-equipment", "elytra"),
        (2_500, "rare-equipment", "trident"),
        (30_000, "rare-loot", "heavy_core"),
        (100, "hard-stone", "obsidian crying_obsidian"),
        (16, "natural-fiber", "cobweb"),
        (50, "mixed-mineral", "gilded_blackstone"),
        (225, "copper-statue", "copper_golem_statue"),
        (500, "worn-equipment", "chipped_anvil"),
        (250, "worn-equipment", "damaged_anvil"),
        (5, "renewable-block", "ice snowball moss_block pale_moss_block moss_carpet pale_moss_carpet pale_hanging_moss sculk sculk_vein"),
        (20, "renewable-block", "sculk_sensor sculk_catalyst sculk_shrieker shroomlight magma_block bee_nest beehive"),
        (100, "adventure-plant", "chorus_flower chorus_plant sniffer_egg turtle_egg pitcher_pod torchflower_seeds"),
        (5, "bottle", "glass_bottle potion splash_potion lingering_potion"),
        (20, "farm-honey", "honey_bottle"),
        (60, "farm-honey", "honey_block"),
        (675, "bucket", "bucket water_bucket lava_bucket milk_bucket powder_snow_bucket axolotl_bucket cod_bucket salmon_bucket pufferfish_bucket tropical_fish_bucket tadpole_bucket sulfur_cube_bucket"),
        (10, "paper-equivalent", "filled_map"),
        (25, "book-equivalent", "enchanted_book written_book"),
        (4, "plant", "dead_bush fern large_fern short_grass tall_grass short_dry_grass tall_dry_grass bush red_shrub leaf_litter wildflowers pink_petals vine weeping_vines twisting_vines seagrass lily_pad sea_pickle nether_sprouts crimson_roots warped_roots hanging_roots mangrove_roots big_dripleaf small_dripleaf azalea flowering_azalea firefly_bush spore_blossom glow_lichen frogspawn shelf_mushroom crimson_fungus warped_fungus"),
        (20, "natural-wood-derived", "red_mushroom_block brown_mushroom_block mushroom_stem crimson_nylium warped_nylium nether_wart_block warped_wart_block"),
        (100, "natural-crystal", "amethyst_cluster large_amethyst_bud medium_amethyst_bud small_amethyst_bud potent_sulfur sulfur_spike"),
        (500, "rare-loot", "bell dragon_head wither_skeleton_skull creeper_head piglin_head player_head skeleton_skull zombie_head"),
        (1_000, "rare-food", "enchanted_golden_apple"),
    ]
    explicit = {item: (value, category) for value, category, words in groups for item in names(words)}
    unclassified = []
    prices, categories = {}, {}
    flowers = expand("#minecraft:flowers")
    for item in sorted(registry):
        name = item.removeprefix(NS)
        if item in explicit:
            value, category = explicit[item]
        elif name.endswith("_spawn_egg") or name.startswith("infested_"):
            value, category = 1, "creative-only"
        elif name.endswith(("_log", "_wood", "_stem", "_hyphae")):
            value, category = 75, "wood"
        elif name.endswith("_planks"):
            value, category = 18, "wood"
        elif name.endswith(("_leaves", "_sapling", "_propagule")):
            value, category = 4, "renewable-plant"
        elif item in flowers:
            value, category = 8, "flower"
        elif name.endswith("_wool"):
            value, category = 16, "renewable-wool"
        elif name.endswith("_coral") or "_coral_" in name:
            value, category = 20, "natural-coral"
        elif name.endswith("_ore"):
            material = name.removeprefix("deepslate_").removeprefix("nether_").removesuffix("_ore")
            source = {"iron": "iron_ingot", "copper": "copper_ingot", "gold": "gold_ingot", "lapis": "lapis_lazuli"}.get(material, material)
            value, category = explicit[NS + source][0], "ore"
        elif name.endswith("_pottery_sherd") or name.endswith("_armor_trim_smithing_template"):
            value, category = 500, "adventure-loot"
        elif name.startswith("music_disc_"):
            value, category = 1_000, "adventure-loot"
        elif name.endswith("_banner_pattern"):
            value, category = 100, "pattern"
        elif name.endswith("_map"):
            value, category = 10, "map-equivalent"
        elif name.endswith("_horse_armor") or name.endswith("_nautilus_armor"):
            value, category = 200, "loot-equipment"
        elif name.startswith("chainmail_"):
            value, category = 100, "loot-equipment"
        elif name.endswith("_froglight"):
            value, category = 75, "renewable-adventure-block"
        elif name.endswith("_concrete") or name == "stripped_bamboo_block" or name.startswith(("exposed_", "weathered_", "oxidized_")):
            value, category = CRAFT_CEILING, "world-transform-derived"
        elif item in produced:
            value, category = CRAFT_CEILING, "recipe-derived"
        else:
            unclassified.append(item)
            continue
        prices[item], categories[item] = value, category
    if unclassified:
        raise ValueError("Unclassified natural/uncraftable items; assign a reviewed category: " + ", ".join(unclassified))

    # World transformations have no crafting JSON, but should not multiply resale value either.
    for item in sorted(registry):
        name = item.removeprefix(NS)
        if name.endswith("_concrete"):
            source = item + "_powder"
        elif name.startswith("stripped_"):
            source = NS + name.removeprefix("stripped_")
        elif name.startswith(("exposed_", "weathered_", "oxidized_")):
            base = re.sub(r"^(exposed|weathered|oxidized)_", "", name)
            source = NS + ("copper_block" if base == "copper" else base)
        else:
            continue
        if source in registry:
            constraints.append({"recipe": "world_transform/" + name, "output": item, "count": 1,
                                "ingredients": [[source]], "remainders": False})

    def ingredient_cost(item, with_remainders):
        cost = prices[item]
        if with_remainders:
            remainder = NS + "bucket" if item in names("milk_bucket water_bucket lava_bucket powder_snow_bucket") else (
                NS + "glass_bottle" if item == NS + "honey_bottle" else None)
            if remainder:
                cost -= prices[remainder]
        return cost

    updates = 0
    for iteration in range(1_000):
        changed = False
        for constraint in constraints:
            cap = sum(min(ingredient_cost(item, constraint["remainders"]) for item in choices)
                      for choices in constraint["ingredients"]) // constraint["count"]
            if cap < 1:
                raise ValueError(f"Positive cent precision cannot satisfy {constraint['recipe']}; review source category values")
            if prices[constraint["output"]] > cap:
                prices[constraint["output"]] = cap
                updates += 1
                changed = True
        if not changed:
            break
    else:
        raise ValueError("Price constraints did not converge")
    unresolved = [item for item, price in prices.items() if price == CRAFT_CEILING]
    if unresolved:
        raise ValueError("Recipe cycle has no reviewed source anchor: " + ", ".join(unresolved))
    document = {"minecraft_version": "26.3", "currency_scale": 100,
                "prices": dict(sorted(prices.items()))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({"registry_items": len(registry), "constraint_count": len(constraints), "iterations": iteration + 1,
                                      "price_updates": updates, "same_item_component_recipes": same_item_special,
                                      "unhandled_recipes": skipped, "categories": dict(collections.Counter(categories.values())),
                                      "item_categories": categories, "minecraft_jar_sha256": hashlib.sha256(args.minecraft_jar.read_bytes()).hexdigest()}, indent=2) + "\n")
    args.constraints.parent.mkdir(parents=True, exist_ok=True)
    args.constraints.write_text(json.dumps(constraints, indent=2) + "\n")
    print(f"Wrote {len(prices)} explicit prices; {len(constraints)} recipe constraints; {iteration + 1} iterations; {len(skipped)} unhandled recipes")
    for item in names("oak_log oak_planks stone diamond iron_ingot iron_nugget iron_block copper_ingot gold_ingot coal wheat emerald"):
        print(item, prices[item])


if __name__ == "__main__":
    main()
