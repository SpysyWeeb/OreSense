package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.scan.KnownSamples;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fmllegacy.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Server to client: the items the sample slot accepts, and whether any block goes too. */
public record KnownSamplesPacket(Set<Item> items, boolean anyBlock) {

    public static void encode(KnownSamplesPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.anyBlock);
        buf.writeCollection(packet.items, (b, item) -> b.writeResourceLocation(ForgeRegistries.ITEMS.getKey(item)));
    }

    /**
     * An id this client does not know comes back as the registry default (air), not null
     * (ForgeRegistry.getValue); both are dropped. Registry sync makes that unlikely, but an
     * air entry would be a sample nothing could ever be.
     */
    public static KnownSamplesPacket decode(FriendlyByteBuf buf) {
        boolean anyBlock = buf.readBoolean();
        Set<Item> items = buf.readCollection(HashSet::new, b -> ForgeRegistries.ITEMS.getValue(b.readResourceLocation()));
        items.remove(null);
        items.remove(Items.AIR);
        return new KnownSamplesPacket(items, anyBlock);
    }

    /** Queues the sample update on the client's main thread before marking the packet handled. */
    public static void handle(KnownSamplesPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> KnownSamples.set(packet.items, packet.anyBlock));
        context.setPacketHandled(true);
    }
}
