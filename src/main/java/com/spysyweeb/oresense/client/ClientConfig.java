package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.Config;
import com.spysyweeb.oresense.ConfigFile;
import com.spysyweeb.oresense.LiveConfig;
import com.spysyweeb.oresense.client.skin.Skins;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * Display settings in config/oresense-client.json, read on the client only and always editable,
 * including on a server. The renderer reads them every frame, so a change shows on the next one.
 * The settings screen edits them only through {@link #LIVE}.
 */
public final class ClientConfig {
    public static final String FILE_NAME = "oresense-client.json";
    public static final String DISPLAY = "display";
    public static final ClientConfig INSTANCE = new ClientConfig();
    private static volatile boolean loaded;
    /** The settings screen's way in: live changes in memory, one write on Done. */
    public static final LiveConfig LIVE = new LiveConfig(() -> loaded, ClientConfig::save);

    public final Config.Value<String> skin = Config.Value.text(DISPLAY, "skin", Skins.DEFAULT_ID,
            "Which dial art to draw. Resource packs can still retexture any skin.\n"
            + "Built-in skins: " + String.join(", ", Skins.ids()) + ". An unknown id draws " + Skins.DEFAULT_ID + ".");
    public final Config.Value<Boolean> pulsingLights = Config.Value.bool(DISPLAY, "pulsingLights", true,
            "The needle glow, sonar sweep and charge gauge pulse. Off holds them steady; the needle still turns.");

    private final List<Config.Value<?>> values = List.of(skin, pulsingLights);

    private ClientConfig() {}

    public List<Config.Value<?>> values() {
        return values;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        ConfigFile.load(path(), INSTANCE.values, "OreSense client configuration", true);
        loaded = true;
    }

    /** Writes the values in use (the settings screen's Done). */
    public static void save() {
        ConfigFile.save(path(), INSTANCE.values, "OreSense client configuration");
    }
}
