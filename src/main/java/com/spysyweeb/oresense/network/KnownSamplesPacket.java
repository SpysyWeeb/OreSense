package com.spysyweeb.oresense.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.Registry;

import java.util.HashSet;
import java.util.Set;

/** Server to client: the items the sample slot accepts, and whether any block goes too. */
public record KnownSamplesPacket(Set<Item> items, boolean anyBlock) {

    public static void encode(KnownSamplesPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.anyBlock);
        buf.writeCollection(packet.items, (b, item) -> b.writeResourceLocation(Registry.ITEM.getKey(item)));
    }

    /**
     * An id this client does not know comes back as the registry default (air), not null
     * (Registry.get); both are dropped. Registry sync makes that unlikely, but an
     * air entry would be a sample nothing could ever be.
     */
    public static KnownSamplesPacket decode(FriendlyByteBuf buf) {
        boolean anyBlock = buf.readBoolean();
        Set<Item> items = buf.readCollection(HashSet::new, b -> Registry.ITEM.get(b.readResourceLocation()));
        items.remove(null);
        items.remove(Items.AIR);
        return new KnownSamplesPacket(items, anyBlock);
    }

}
