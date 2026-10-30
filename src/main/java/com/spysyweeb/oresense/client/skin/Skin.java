package com.spysyweeb.oresense.client.skin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.spysyweeb.oresense.client.SensorClient;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

import java.util.List;
import java.util.function.Function;

/**
 * One look of the dial: an id (stored in the client config), a display name, the models it needs
 * baked, and the dial face for a reading as an ordered list of tinted layers ({@link #compose}).
 * {@link #render} writes those layers' baked quads; the settings screen's preview tiles blit
 * each layer's texture ({@link #texture}), so a preview needs neither an
 * ItemStack nor the item renderer. SensorRenderer draws the sample in the window on top, the same
 * for every skin. {@link LayerSkin} is the only kind in 1.1.0; the interface leaves room for a
 * skin that draws more than stacked layers (the planned radar skin would override {@link #render}).
 */
public interface Skin {
    /** Stable id, as the client config stores it. */
    String id();

    /** The name the settings screen shows (lang oresense.skin.&lt;id&gt;). */
    Component name();

    /** Every model this skin draws; registered as dependencies of the sensor's item model and baked once. */
    List<Identifier> layers();

    /**
     * Takes the dial face one layer at a time, bottom first: the layer (one of {@link #layers()}),
     * its tint and alpha, and whether it glows (full brightness instead of the light it is drawn in).
     */
    interface LayerSink {
        void layer(Identifier layer, float r, float g, float b, float a, boolean fullBright);
    }

    /** The dial face for {@code reading} at {@code frame}: every layer to draw, in order, with its tint. */
    void compose(SensorClient.Reading reading, Frame frame, LayerSink sink);

    /** The texture file {@code layer}'s item/generated model draws, for drawing the layer without a baked model. */
    Identifier texture(Identifier layer);

    /**
     * The needle frame (0 to {@link SensorClient#NEEDLE_FRAMES} - 1) the settings screen's preview tile
     * shows LOCKED at, with the up lamp lit: the LOCKED frame of the skin's approved preview sheet
     * (tools/gen_textures.py: {@code PREVIEW_LOCKED_FRAME}, else {@code LOCKED_FRAME}), a frame whose
     * tip stays clear of the lamp, so the tile shows the dial as it was approved.
     */
    int previewLockedFrame();

    /**
     * Draws the dial face into {@code vc} (entity translucent on the item atlas), on the item's
     * plane: {@link #compose}, each layer's baked quads written with its tint, at full brightness
     * for a glowing layer and at {@code frame.light()} otherwise. {@code models} looks up a baked
     * layer from {@link #layers()}.
     */
    default void render(PoseStack pose, VertexConsumer vc, Function<Identifier, List<BakedQuad>> models,
                        SensorClient.Reading reading, Frame frame) {
        compose(reading, frame, (layer, r, g, b, a, fullBright) -> writeLayer(pose, vc, models.apply(layer),
                r, g, b, a, fullBright ? LightCoordsUtil.FULL_BRIGHT : frame.light(), frame.overlay()));
    }

    /**
     * Per-frame inputs that are not part of the reading: the clock (ms), the shared phase of the
     * locked needle's pulse, the pulsingLights setting (true = the lights pulse), and the light and
     * overlay to draw with.
     */
    record Frame(long now, double lockedPhase, boolean pulsingLights, int light, int overlay) {}

    /**
     * Writes one item/generated layer into {@code vc} through the per-vertex path, supplying the
     * layer's tint and alpha directly. This keeps layer colour and fading independent of
     * item-colour providers.
     */
    private static void writeLayer(PoseStack ps, VertexConsumer vc, List<BakedQuad> model,
                                   float r, float g, float b, float a, int light, int overlay) {
        PoseStack.Pose pose = ps.last();
        for (BakedQuad quad : model) {
            Direction face = quad.direction();
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                long uv = quad.packedUV(vertex);
                vc.addVertex(pose, quad.position(vertex))
                        .setColor(r, g, b, a)
                        .setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, face.getStepX(), face.getStepY(), face.getStepZ());
            }
        }
    }
}
