package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.network.ClientOreSenseNetwork;
import net.fabricmc.api.ClientModInitializer;

/** Keeps rendering and client networking out of dedicated-server initialization. */
public final class OreSenseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SensorClient.registerModels();
        SensorClient.onClientSetup();
        ClientOreSenseNetwork.register();
    }
}
