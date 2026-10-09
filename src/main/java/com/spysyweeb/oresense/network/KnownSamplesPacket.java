package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.scan.KnownSamples;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/** Server to client: the items the sample slot accepts, and whether any block goes too. */
public record KnownSamplesPacket(Set<Item> items, boolean anyBlock) {

    public static void encode(KnownSamplesPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.anyBlock);
        buf.writeVarInt(packet.items.size());
        for (Item item : packet.items) buf.writeIdentifier(ForgeRegistries.ITEMS.getKey(item));
    }

    /**
     * An id this client does not know comes back as the registry default (air), not null
     * (ForgeRegistry.getValue); both are dropped. Registry sync makes that unlikely, but an
     * air entry would be a sample nothing could ever be.
     */
    public static KnownSamplesPacket decode(FriendlyByteBuf buf) {
        boolean anyBlock = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > buf.readableBytes()) {
            throw new io.netty.handler.codec.DecoderException("Invalid sample item count: " + count);
        }
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < count; i++) items.add(ForgeRegistries.ITEMS.getValue(buf.readIdentifier()));
        items.remove(null);
        items.remove(Items.AIR);
        return new KnownSamplesPacket(items, anyBlock);
    }

    /**
     * Runs on the client's main thread: consumerMainThread queues it there and marks the
     * packet handled itself (SimpleChannel.MessageBuilder), so this only stores the list.
     */
    public static void handle(KnownSamplesPacket packet, CustomPayloadEvent.Context ctx) {
        KnownSamples.set(packet.items, packet.anyBlock);
    }
}
