package com.spysyweeb.oresense.scan;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * The client's copy of which items the server's {@link SampleResolver} accepts as a sample.
 * The client cannot run the resolver (loot tables exist only on the server), so the server
 * sends this list on login and after every /reload. Only the client reads it; the server
 * always asks the resolver itself. Common code on purpose: the network handler that fills it
 * is loaded on a dedicated server too.
 */
public final class KnownSamples {
    private static volatile Set<Item> items = Set.of();
    /** The server's oresOnly config is off, so any block may be sampled directly. */
    private static volatile boolean anyBlock = false;

    private KnownSamples() {}

    public static boolean accepts(ItemStack stack) {
        return items.contains(stack.getItem()) || (anyBlock && stack.getItem() instanceof BlockItem);
    }

    public static void set(Set<Item> known, boolean anyBlockAllowed) {
        items = Set.copyOf(known);
        anyBlock = anyBlockAllowed;
    }
}
