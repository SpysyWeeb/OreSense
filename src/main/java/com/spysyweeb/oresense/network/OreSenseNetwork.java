package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.OreSense;
import net.fabricmc.fabric.api.networking.v1.FriendlyByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Required login handshake, then the server's accepted-sample list during play. */
public final class OreSenseNetwork {
    static final int PROTOCOL = 1;
    static final Identifier HANDSHAKE = Identifier.fromNamespaceAndPath(OreSense.MODID, "handshake");

    private OreSenseNetwork() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(KnownSamplesPacket.TYPE, KnownSamplesPacket.STREAM_CODEC);
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) ->
                sender.sendPacket(HANDSHAKE, protocolPacket()));
        ServerLoginNetworking.registerGlobalReceiver(HANDSHAKE,
                (server, handler, understood, buf, synchronizer, sender) -> {
                    if (!understood || !matchesProtocol(buf)) {
                        handler.disconnect(Component.literal(
                                "This server requires OreSense (Fabric) and Fabric API."));
                    }
                });
    }

    static FriendlyByteBuf protocolPacket() {
        FriendlyByteBuf buf = FriendlyByteBufs.create();
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
