package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.OreSensorItem;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * The mod's client-only entry point (FML constructs it on the physical client only):
 * the display config, the mod list's Config button, and the Shift test
 * for the sensor's tooltip.
 */
@Mod(value = OreSense.MODID, dist = Dist.CLIENT)
public final class OreSenseClient {
    public OreSenseClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "oresense-client.toml");
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parent) -> new OreSenseSettingsScreen(parent));
        OreSensorItem.moreInfoHeld = Screen::hasShiftDown;
    }
}
