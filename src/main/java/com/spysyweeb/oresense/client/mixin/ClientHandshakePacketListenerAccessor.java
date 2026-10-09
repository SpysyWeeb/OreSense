package com.spysyweeb.oresense.client.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Login no longer exposes its connection; bind handshake approval to this exact connection. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public interface ClientHandshakePacketListenerAccessor {
    @Accessor("connection")
    Connection oresense$getConnection();
}
