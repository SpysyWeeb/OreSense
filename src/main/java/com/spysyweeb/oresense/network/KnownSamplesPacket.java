package com.spysyweeb.oresense.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import com.spysyweeb.oresense.OreSense;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashSet;
import java.util.Set;

/** Server to client: the items the sample slot accepts, and whether any block goes too. */
public record KnownSamplesPacket(Set<Item> items, boolean anyBlock) implements CustomPacketPayload {
    public static final Type<KnownSamplesPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(OreSense.MODID, "known_samples"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KnownSamplesPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> encode(packet, buf), KnownSamplesPacket::decode);

    @Override
    public Type<KnownSamplesPacket> type() { return TYPE; }


    public static void encode(KnownSamplesPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.anyBlock);
        buf.writeVarInt(packet.items.size());
        for (Item item : packet.items) buf.writeIdentifier(BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * An id this client does not know comes back as the registry default (air), not null
     * (Registry.get); both are dropped. Registry sync makes that unlikely, but an
     * air entry would be a sample nothing could ever be.
     */
    public static KnownSamplesPacket decode(FriendlyByteBuf buf) {
        boolean anyBlock = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > buf.readableBytes()) throw new io.netty.handler.codec.DecoderException("Invalid sample count: " + count);
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < count; i++) items.add(BuiltInRegistries.ITEM.getValue(buf.readIdentifier()));
        items.remove(null);
        items.remove(Items.AIR);
        return new KnownSamplesPacket(items, anyBlock);
    }

}
