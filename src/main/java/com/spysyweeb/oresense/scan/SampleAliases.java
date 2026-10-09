package com.spysyweeb.oresense.scan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import com.spysyweeb.oresense.OreSense;
import org.apache.logging.log4j.Logger;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Samples that stand for blocks no loot table or recipe leads back to. An amethyst shard is
 * not the drop of an ore, but it is what a geode is made of, so it finds the geode: budding
 * amethyst, the buds and clusters, and the amethyst blocks. With all of those in one target
 * set, the sensor's vein flood treats a geode as one vein.
 *
 * Read from data packs, {@code data/<namespace>/sample_aliases/*.json}:
 * {@code {"item": "<item id>", "blocks": ["<block id>", ...]}}. Files naming the same item add
 * up; a data pack that overrides a file with an empty block list removes that alias. Unknown
 * ids are logged and skipped. /reload reads the folder again.
 */
public final class SampleAliases extends SimpleJsonResourceReloadListener<JsonElement> {
    public static final String DIRECTORY = "sample_aliases";
    private static final Logger LOGGER = LogManager.getLogger();

    /** Replaced whole by each reload, so a reader always sees one complete load. */
    private static volatile Map<Item, Set<Block>> aliases = Map.of();

    public SampleAliases() {
        super(com.mojang.serialization.Codec.PASSTHROUGH.xmap(
                dynamic -> dynamic.convert(com.mojang.serialization.JsonOps.INSTANCE).getValue(),
                json -> new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JsonOps.INSTANCE, json)), net.minecraft.resources.FileToIdConverter.json(DIRECTORY));
    }


    /** The blocks an item stands for through an alias; empty when it has none. */
    public static Set<Block> blocksFor(Item item) {
        return aliases.getOrDefault(item, Set.of());
    }

    /** Every item that has an alias. */
    public static Set<Item> items() {
        return aliases.keySet();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<Item, Set<Block>> loaded = new HashMap<>();
        files.forEach((file, json) -> {
            try {
                JsonObject object = GsonHelper.convertToJsonObject(json, "sample alias");
                String itemId = GsonHelper.getAsString(object, "item");
                Item item = item(itemId);
                if (item == null) {
                    LOGGER.warn("Sample alias {}: unknown item '{}', skipped", file, itemId);
                    return;
                }
                Set<Block> blocks = new HashSet<>();
                for (JsonElement element : GsonHelper.getAsJsonArray(object, "blocks")) {
                    String blockId = GsonHelper.convertToString(element, "block");
                    Block block = block(blockId);
                    if (block == null) {
                        LOGGER.warn("Sample alias {}: unknown block '{}', skipped", file, blockId);
                        continue;
                    }
                    blocks.add(block);
                }
                if (!blocks.isEmpty()) loaded.computeIfAbsent(item, k -> new HashSet<>()).addAll(blocks);
            } catch (RuntimeException e) {
                LOGGER.error("Sample alias {} could not be read, skipped: {}", file, e.getMessage());
            }
        });

        Map<Item, Set<Block>> frozen = new HashMap<>();
        loaded.forEach((item, blocks) -> frozen.put(item, Set.copyOf(blocks)));
        aliases = Map.copyOf(frozen);
        LOGGER.debug("Loaded {} sample aliases", frozen.size());
    }

    /**
     * The registered item for an id, or null when the id is malformed or not registered. The
     * registry hands back its default (air) for an unknown id instead of null, so presence is
     * checked first; air itself is never a usable alias either way.
     */
    @Nullable
    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(location);
        return item == Items.AIR ? null : item;
    }

    /** The registered block for an id, or null, the same way as {@link #item}. */
    @Nullable
    private static Block block(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(location);
        return block == Blocks.AIR ? null : block;
    }
}
