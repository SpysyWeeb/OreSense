package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.OreSense;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PlayNetworkDirection;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;

/** The mod's one network channel. It carries the sample list from server to client. */
public final class OreSenseNetwork {
    private static final String PROTOCOL = "1";

    /**
     * Both sides must carry this channel at the same protocol. A client with the mod refuses a
     * server without it (Alex, 2026-09-23: the player should not be able to join a server that
     * lacks a mod the client brings), and a server with the mod refuses a client without it.
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OreSense.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private OreSenseNetwork() {}

    /** Called once from the mod constructor, while channels can still be registered. */
    public static void register() {
        CHANNEL.messageBuilder(KnownSamplesPacket.class, 0, PlayNetworkDirection.PLAY_TO_CLIENT)
                .encoder(KnownSamplesPacket::encode)
                .decoder(KnownSamplesPacket::decode)
                .consumerMainThread(KnownSamplesPacket::handle)
                .add();
    }

    public static void send(ServerPlayer player, KnownSamplesPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
