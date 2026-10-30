package com.spysyweeb.oresense.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu's Config button for OreSense: entrypoint "modmenu" in fabric.mod.json. Mod Menu is
 * optional. Fabric Loader stores an entrypoint as its class name and loads the class only when
 * someone asks for that entrypoint (EntrypointStorage creates it in getOrCreate), and only Mod
 * Menu asks for "modmenu"; nothing else refers to this class. So without Mod Menu it is never
 * loaded, and the missing ModMenuApi type is never looked up.
 */
public final class OreSenseModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return OreSenseSettingsScreen::new;
    }
}
