package com.spysyweeb.oresense.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.ReloadableServerRegistries;
import com.spysyweeb.oresense.OreSense;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Works out which ore blocks a sample refers to.
 * A sample can be the ore block itself, what the ore drops, or anything refined purely from
 * that: a plain diamond tunes the sensor to both diamond ore and deepslate diamond ore, and an
 * iron ingot, nugget or block finds iron ore just like raw iron does.
 * The map is built from what each ore block actually drops plus the server's own recipes,
 * so modded ores and their products work without anything being hardcoded. What no drop or
 * recipe leads back to comes from data-pack aliases ({@link SampleAliases}): an amethyst shard
 * finds geodes.
 */
public final class SampleResolver {
    private static Map<Item, Set<Block>> itemToOres = null;
    private static final TagKey<Block> ORE_TAG = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(OreSense.MODID, "ores"));
    /**
     * /reload replaces both managers when the newly loaded resources become live. Checking
     * their identities keeps a sample click during an in-flight reload from caching old
     * recipes and loot after the new resources have been installed.
     */
    private static RecipeManager builtRecipes;
    private static ReloadableServerRegistries.Holder builtLoot;

    private SampleResolver() {}

    /**
     * The blocks a sample finds: the ores it resolves to through loot tables and recipes, plus
     * any blocks a data pack aliases it to ({@link SampleAliases}; an amethyst shard finds geodes).
     */
    public static synchronized Set<Block> resolve(ServerLevel level, ItemStack sample) {
        if (sample.isEmpty()) return Set.of();
        Set<Block> ores = resolveOres(level, sample);
        Set<Block> aliased = SampleAliases.blocksFor(sample.getItem());
        if (aliased.isEmpty()) return ores;
        if (ores.isEmpty()) return aliased;
        Set<Block> both = new HashSet<>(ores);
        both.addAll(aliased);
        return both;
    }

    private static Set<Block> resolveOres(ServerLevel level, ItemStack sample) {
        // a block used directly as the sample: an ore stands for itself and every ore that drops
        // what it drops; any other block stands for itself only when the oresOnly config is off
        if (sample.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block.defaultBlockState().is(ORE_TAG)) {
                return byProduct(level).getOrDefault(sample.getItem(), Set.of(block));
            }
            if (!com.spysyweeb.oresense.Config.INSTANCE.oresOnly.get()) {
                return Set.of(block);
            }
        }

        return byProduct(level).getOrDefault(sample.getItem(), Set.of());
    }

    /**
     * Every item {@link #resolve} turns into at least one ore regardless of the oresOnly
     * config: the items with ores in the map, and every block item whose block is an ore
     * (resolve accepts those by their tag, even one that is not its block's asItem()), and
     * every item with a data-pack alias (the loader keeps only aliases with blocks, never air).
     * The "any block" rule of oresOnly=false is not listed here; the client gets it as a flag.
     * This is the list the client checks the sample slot against, so the two sides agree.
     */
    public static synchronized Set<Item> knownSamples(ServerLevel level) {
        Set<Item> items = new HashSet<>();
        byProduct(level).forEach((item, ores) -> {
            if (!ores.isEmpty() && item != Items.AIR) items.add(item);   // air: an ore with no item
        });
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof BlockItem blockItem
                    && blockItem.getBlock().defaultBlockState().is(ORE_TAG)) items.add(item);
        }
        items.addAll(SampleAliases.items());
        return items;
    }

    private static Map<Item, Set<Block>> byProduct(ServerLevel level) {
        RecipeManager recipes = level.getServer().getRecipeManager();
        ReloadableServerRegistries.Holder loot = level.getServer().reloadableRegistries();
        if (itemToOres != null && builtRecipes == recipes && builtLoot == loot) return itemToOres;

        // seed: each ore block's own item, and everything the ore drops, stand for that ore
        Map<Item, Set<Block>> map = new HashMap<>();
        Map<Block, Set<Item>> oreDrops = new HashMap<>();
        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.NETHERITE_PICKAXE));

        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.is(ORE_TAG)) continue;
            map.computeIfAbsent(block.asItem(), k -> new HashSet<>()).add(block);
            List<ItemStack> drops;
            try {
                drops = state.getDrops(params);
            } catch (Exception e) {
                continue;   // a mod's loot table may need context we cannot supply; skip it
            }
            Set<Item> dropped = new HashSet<>();
            for (ItemStack drop : drops) {
                if (drop.isEmpty()) continue;
                map.computeIfAbsent(drop.getItem(), k -> new HashSet<>()).add(block);
                dropped.add(drop.getItem());
            }
            oreDrops.put(block, dropped);
        }

        // material links from the server's recipes: item -> the items made purely of it
        Map<Item, Set<Item>> links = new HashMap<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() == RecipeType.SMELTING || recipe.getType() == RecipeType.BLASTING) {
                linkCooking(recipe, level, links);
            } else if (recipe.getType() == RecipeType.CRAFTING) {
                linkCrafting(recipe, level, links);
            }
        }

        // carry the ores along the links until nothing changes; a pass that changes something
        // adds at least one (item, ore) pair and there are finitely many, so this ends
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Item, Set<Item>> link : links.entrySet()) {
                Set<Block> from = map.get(link.getKey());
                if (from == null) continue;
                for (Item to : link.getValue()) {
                    if (map.computeIfAbsent(to, k -> new HashSet<>()).addAll(from)) changed = true;
                }
            }
        }

        // sibling ores: an ore block also stands for every ore its drops stand for, so an iron
        // ore sample finds deepslate iron ore too (both drop raw iron). A drop only gathers the
        // ores linked INTO it, so this is one-way where an ore drops a refined form: nether gold
        // ore drops nuggets, which gold ore's ingots also make, so it finds gold ore; gold ore
        // drops raw gold, and smelting never leads back from ingots to raw gold, so it does not
        // find nether gold ore (vanilla puts one in stone, the other in netherrack). All reads
        // happen before any write, so the result does not depend on the order the ores come in.
        Map<Item, Set<Block>> siblings = new HashMap<>();
        for (Map.Entry<Block, Set<Item>> ore : oreDrops.entrySet()) {
            Set<Block> found = siblings.computeIfAbsent(ore.getKey().asItem(), k -> new HashSet<>());
            for (Item drop : ore.getValue()) found.addAll(map.get(drop));
        }
        siblings.forEach((item, ores) -> map.computeIfAbsent(item, k -> new HashSet<>()).addAll(ores));

        itemToOres = map;
        builtRecipes = recipes;
        builtLoot = loot;
        return map;
    }

    /**
     * Smelting and blasting: the ingredient becomes the result. One way only, so smelting an
     * iron pickaxe into nuggets never makes the pickaxe a sample.
     */
    private static void linkCooking(Recipe<?> recipe, ServerLevel level, Map<Item, Set<Item>> links) {
        try {
            List<Ingredient> ingredients = recipe.placementInfo().ingredients();
            if (ingredients.size() != 1) return;
            Set<Item> results = results(recipe, level);
            for (Item from : accepted(ingredients.getFirst())) {
                for (Item result : results) link(links, from, result);
            }
        } catch (Exception e) {
            // A mod's recipe may not expose its ingredients or display outside its machine.
        }
    }

    /**
     * Crafting recipes whose filled slots all accept the same items: the material becomes the
     * result (nuggets become an ingot, an ingot becomes nuggets, ingots become a block, copper
     * blocks become cut copper). One way only, like smelting: a product can be made purely of
     * the material, but the material is never "made of" the product. That keeps a hub product
     * with two sources from joining them; blue dye comes from lapis and from cornflowers, so a
     * cornflower must not find lapis ore. Recipes that mix materials link nothing.
     */
    private static void linkCrafting(Recipe<?> recipe, ServerLevel level, Map<Item, Set<Item>> links) {
        try {
            if (recipe.isSpecial()) return;
            Set<Item> material = null;
            // PlacementInfo contains occupied ingredients only, without blank shaped cells.
            for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
                Set<Item> accepted = accepted(ingredient);
                if (accepted.isEmpty()) return;
                if (material == null) material = accepted;
                else if (!material.equals(accepted)) return;
            }
            if (material == null) return;
            for (Item result : results(recipe, level)) {
                for (Item item : material) link(links, item, result);
            }
        } catch (Exception e) {
            // A mod's recipe may not expose its ingredients or display outside a crafting grid.
        }
    }

    /** Server recipe displays replace the removed getResultItem API, without guessing inputs. */
    private static Set<Item> results(Recipe<?> recipe, ServerLevel level) {
        Set<Item> items = new HashSet<>();
        for (RecipeDisplay display : recipe.display()) {
            for (ItemStack stack : display.result().resolveForStacks(SlotDisplayContext.fromLevel(level))) {
                if (!stack.isEmpty() && stack.getItem() != Items.BARRIER) items.add(stack.getItem());
            }
        }
        return items;
    }

    /**
     * The items an ingredient accepts. Empty stacks and barrier placeholders are not
     * usable materials and must not link a recipe to an ore.
     */
    private static Set<Item> accepted(Ingredient ingredient) {
        Set<Item> items = new HashSet<>();
        for (var holder : ingredient.items().toList()) {
            Item item = holder.value();
            if (item != Items.AIR && item != Items.BARRIER) items.add(item);
        }
        return items;
    }

    private static void link(Map<Item, Set<Item>> links, Item from, Item to) {
        if (from != to) links.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    /** Drops the map and the data it holds on to; the next call rebuilds it. */
    public static synchronized void invalidate() {
        itemToOres = null;
        builtRecipes = null;
        builtLoot = null;
    }
}
