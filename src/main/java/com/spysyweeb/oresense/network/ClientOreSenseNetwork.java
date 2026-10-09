package com.spysyweeb.oresense.network;

import com.spysyweeb.oresense.scan.KnownSamples;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Client APIs stay out of the common initializer so dedicated servers can load the mod. */
@Environment(EnvType.CLIENT)
public final class ClientOreSenseNetwork {
    // Key by connection, so a prior successful join cannot authorize the next server.
    private static final Set<Connection> verified = ConcurrentHashMap.newKeySet();

    private ClientOreSenseNetwork() {}

    public static void register() {
        ClientLoginConnectionEvents.INIT.register((handler, client) -> {
            verified.remove(handler.getConnection());
            KnownSamples.set(Set.of(), false);
        });
        ClientLoginNetworking.registerGlobalReceiver(OreSenseNetwork.HANDSHAKE,
                (client, handler, buf, listenerAdder) -> {
                    if (!OreSenseNetwork.matchesProtocol(buf)) {
                        handler.getConnection().disconnect(Component.literal(
                                "The server uses an incompatible OreSense network version."));
                        return CompletableFuture.completedFuture(null);
                    }
                    verified.add(handler.getConnection());
                    return CompletableFuture.completedFuture(OreSenseNetwork.protocolPacket());
                });
        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) ->
                verified.remove(handler.getConnection()));
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            if (!verified.remove(handler.getConnection())) {
                handler.getConnection().disconnect(Component.literal(
                        "OreSense must also be installed on the server to join with this mod."));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            verified.remove(handler.getConnection());
            KnownSamples.set(Set.of(), false);
        });
        ClientPlayNetworking.registerGlobalReceiver(OreSenseNetwork.SAMPLES, (client, handler, buf, sender) -> {
            KnownSamplesPacket packet = KnownSamplesPacket.decode(buf);
            client.execute(() -> {
                if (client.getConnection() == handler) KnownSamples.set(packet.items(), packet.anyBlock());
            });
        });
    }
}
