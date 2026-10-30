package com.spysyweeb.oresense.client.skin;

import com.spysyweeb.oresense.client.ClientConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in skins, a fixed list (discovering skins from resource packs is later work). Each
 * further skin lives at textures/item/skins/&lt;id&gt;/ and models/item/skins/&lt;id&gt;/, written by
 * {@code tools/gen_textures.py --skin <id>}; {@code compass} is the 1.0 art at its original
 * paths, so resource packs that retexture the sensor keep working.
 */
public final class Skins {
    private Skins() {}

    public static final String DEFAULT_ID = "compass";
    // the last argument is the LOCKED needle frame of the skin's approved preview sheet, which the
    // settings tile shows (gen_textures.py: compass LOCKED_FRAME 5; PREVIEW_LOCKED_FRAME concept 6,
    // compass_b 8)
    public static final LayerSkin COMPASS = new LayerSkin(DEFAULT_ID, "item/", 5);
    public static final LayerSkin CONCEPT = new LayerSkin("concept", "item/skins/concept/", 6);
    public static final LayerSkin COMPASS_B = new LayerSkin("compass_b", "item/skins/compass_b/", 8);

    // the settings screen's tiles, left to right; compass stays the default and the fallback
    private static final List<Skin> BUILT_IN = List.of(CONCEPT, COMPASS, COMPASS_B);

    public static List<Skin> all() {
        return BUILT_IN;
    }

    /** The ids, for the config comment. */
    public static List<String> ids() {
        return BUILT_IN.stream().map(Skin::id).toList();
    }

    /** The skin with this id; an unknown id (a skin from another version, a typo) falls back to compass. */
    public static Skin byId(String id) {
        for (Skin skin : BUILT_IN) {
            if (skin.id().equals(id)) return skin;
        }
        return COMPASS;
    }

    /** The skin the client config chooses, read every frame so a change shows on the next one. */
    public static Skin current() {
        return byId(ClientConfig.INSTANCE.skin.get());
    }

    /** All layer model ids used by the built-in skins. */
    public static List<ResourceLocation> allLayers() {
        List<ResourceLocation> layers = new ArrayList<>();
        for (Skin skin : BUILT_IN) {
            for (ResourceLocation layer : skin.layers()) {
                if (!layers.contains(layer)) layers.add(layer);
            }
        }
        return layers;
    }
}
