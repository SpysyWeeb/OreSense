package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.OreSense;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Required login handshake, then the server's accepted-sample list during play. */
public final class OreSenseNetwork {
    static final int PROTOCOL = 1;
    static final ResourceLocation HANDSHAKE = ResourceLocation.fromNamespaceAndPath(OreSense.MODID, "handshake");

    private OreSenseNetwork() {}

    public static void register() {
        PayloadTypeRegistry.playS2C().register(KnownSamplesPacket.TYPE, KnownSamplesPacket.STREAM_CODEC);
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) ->
                sender.sendPacket(HANDSHAKE, protocolPacket()));
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE,
                (server, handler, understood, buf, synchronizer, sender) -> {
                    if (!understood || !matchesProtocol(buf)) {
                        handler.disconnect(Component.literal(
                                "This server requires a compatible OreSense installation for Minecraft 1.21.7 (Fabric)."));
                    }
                });
    }

    static FriendlyByteBuf protocolPacket() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(PROTOCOL);
        return buf;
    }

    static boolean matchesProtocol(FriendlyByteBuf buf) {
        try {
            return buf.readVarInt() == PROTOCOL && !buf.isReadable();
        } catch (RuntimeException malformedPacket) {
            return false;
        }
    }

    public static void send(ServerPlayer player, KnownSamplesPacket packet) {
        ServerPlayNetworking.send(player, packet);
    }
}
