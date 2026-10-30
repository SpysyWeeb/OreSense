package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.OreSensorItem;
import com.spysyweeb.oresense.network.ClientOreSenseNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/** Keeps rendering and client networking out of dedicated-server initialization. */
public final class OreSenseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        // the sensor tooltip's details show while Shift is held (OreSensorItem.appendHoverText)
        OreSensorItem.moreInfoHeld = () -> Minecraft.getInstance().hasShiftDown();
        SensorClient.registerItemModelType();
        SensorClient.onClientSetup();
        ClientOreSenseNetwork.register();
    }
}
