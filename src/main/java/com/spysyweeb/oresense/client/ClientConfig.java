package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.LiveConfig;
import com.spysyweeb.oresense.client.skin.Skins;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The display settings, config/oresense-client.toml: read on the client only and always editable,
 * on a server too. Registered as a CLIENT config (SensorClient.init), so a dedicated server never
 * loads it.
 */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ClientConfig INSTANCE;
    /** The loaded file behind {@link #SPEC}: live edits from the settings screen, and its Done. */
    public static final LiveConfig LIVE;

    public final ForgeConfigSpec.ConfigValue<String> skin;
    public final ForgeConfigSpec.BooleanValue pulsingLights;

    private ClientConfig(ForgeConfigSpec.Builder b) {
        b.comment("OreSense - how the sensor looks on this computer").push("display");
        skin = b.comment("Which dial art to draw. Resource packs can still retexture any skin.",
                        "Built-in skins: " + String.join(", ", Skins.ids()) + ". An unknown id draws " + Skins.DEFAULT_ID + ".")
                .define("skin", Skins.DEFAULT_ID);
        pulsingLights = b.comment("The needle glow, sonar sweep and charge gauge pulse. Off holds them steady; the needle still turns.")
                .define("pulsingLights", true);
        b.pop();
    }

    static {
        Pair<ClientConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
        LIVE = new LiveConfig(SPEC);
    }
}
