package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.OreSense;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/** The mod's one network channel. It carries the sample list from server to client. */
public final class OreSenseNetwork {
    private static final int PROTOCOL = 1;

    /**
     * Both sides must carry this channel at the same protocol. A client with the mod refuses a
     * server without it (Alex, 2026-09-23: the player should not be able to join a server that
     * lacks a mod the client brings), and a server with the mod refuses a client without it.
     */
    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(OreSense.MODID, "main"))
            .networkProtocolVersion(PROTOCOL)
            .simpleChannel();

    private OreSenseNetwork() {}

    /** Called once from the mod constructor, while channels can still be registered. */
    public static void register() {
        CHANNEL.messageBuilder(KnownSamplesPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(KnownSamplesPacket::encode)
                .decoder(KnownSamplesPacket::decode)
                .consumerMainThread(KnownSamplesPacket::handle)
                .add();
    }

    public static void send(ServerPlayer player, KnownSamplesPacket packet) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }
}
