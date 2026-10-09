package com.spysyweeb.oresense.network;
import com.spysyweeb.oresense.OreSense;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
/** Required server-to-client sample-list payload, registered on the mod event bus. */
public final class OreSenseNetwork {
    private OreSenseNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(KnownSamplesPacket.TYPE, KnownSamplesPacket.STREAM_CODEC, KnownSamplesPacket::handle);
    }
    public static void send(ServerPlayer player, KnownSamplesPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
