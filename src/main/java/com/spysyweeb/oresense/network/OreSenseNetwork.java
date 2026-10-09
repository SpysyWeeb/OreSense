package com.spysyweeb.oresense.network;
import com.spysyweeb.oresense.OreSense;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
/** Required server-to-client sample-list payload, registered on the mod event bus. */
public final class OreSenseNetwork {
    private OreSenseNetwork() {}
    public static void register(RegisterPayloadHandlerEvent event) {
        event.registrar(OreSense.MODID).versioned("1").play(KnownSamplesPacket.ID, KnownSamplesPacket::decode, handlers -> handlers.client(KnownSamplesPacket::handle));
    }
    public static void send(ServerPlayer player, KnownSamplesPacket packet) {
        PacketDistributor.PLAYER.with(player).send(packet);
    }
}
