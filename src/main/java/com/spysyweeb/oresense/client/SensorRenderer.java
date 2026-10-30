package com.spysyweeb.oresense.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.spysyweeb.oresense.client.skin.LayerSkin;
import com.spysyweeb.oresense.client.skin.Skin;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Util;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws the dial in the skin the snapshot carries (the client config's choice, read every frame),
 * then the sample sitting in the window at the centre so you can see what it is hunting for. The
 * skin draws the face; this class owns what is the same for every skin: the shared pulse clock
 * ({@link #lockedPhase}), the pulsingLights setting, and the sample decal in the window
 * ({@link #WINDOW_X}, {@link #WINDOW_Y}, {@link #WINDOW_SIZE}). The settings screen's preview
 * tiles use the same two.
 */
public class SensorRenderer implements SpecialModelRenderer<SensorItemModel.Snapshot> {
    /**
     * The dial's grid, 16 by 16 like any item; the window rect below is in these units, from the top
     * left, whatever a skin's texture size (a 32 px skin has 2 texels per unit).
     */
    public static final int DIAL_PIXELS = 16;
    /** Left edge of the window the sample sits in (pixels 6..9 of 16: exactly in the middle). */
    public static final int WINDOW_X = 6;
    /** Top edge of the window. */
    public static final int WINDOW_Y = 6;
    /** The window is WINDOW_SIZE by WINDOW_SIZE dial pixels. */
    public static final int WINDOW_SIZE = 4;
    // Phase (0..1) of the locked needle pulse. Its period follows the distance, which the server
    // updates once per scan, so the phase is integrated (phase += elapsed / period) instead of
    // taken as (clock mod period) / period: that would jump the brightness every time the
    // period changes. Shared like the needle easing: every sensor on screen pulses together.
    private static double pulsePhase = 0.0;
    private static long pulseClock = Long.MIN_VALUE;
    // how much of its depth a flat block sample keeps: too thin to see, but the depth test
    // still knows which part of a stairs or anvil model is in front
    private static final float DECAL_DEPTH = 0.05f;
    // the sample's size in the window. The window is 4x4 of the dial's 16 px, 0.25 of the item: a
    // flat item's 16 px sprite spans +-0.5 of its unit, so at 0.25 it fills the window exactly. A
    // block's inventory icon is the same size in a slot (its GUI transform's 0.625 scale is what
    // makes the isometric cube, |x| <= 0.442 and |y| <= 0.492 in drawFlatIcon's worked example,
    // as big as a flat item), so at 0.25 it spans 3.5 x 3.9 of the 4 px and stays off the needle
    // root and tail pixels next to the window and off the gauge row under it
    private static final float SAMPLE_SCALE = WINDOW_SIZE / (float) DIAL_PIXELS;
    // the window's centre on the item's plane (x right, y up, 0..1): 0.5, 0.5
    private static final float WINDOW_CENTRE_X = (WINDOW_X + WINDOW_SIZE / 2f) / DIAL_PIXELS;
    private static final float WINDOW_CENTRE_Y = 1f - (WINDOW_Y + WINDOW_SIZE / 2f) / DIAL_PIXELS;
    // where the flat icon sits: the dial's front face is the generated model's z 8.5/16 = 0.53125,
    // and the icon keeps at most +-0.007 of depth (0.25 x 0.539 x DECAL_DEPTH), so 0.542 puts all
    // of it just in front of the face. Any further out and perspective slides it off the lens
    // centre when the dial is seen at an angle in hand.
    private static final float DECAL_Z = 0.542f;

    private final Map<Identifier, List<BakedQuad>> models;

    public SensorRenderer(Map<Identifier, List<BakedQuad>> models) {
        this.models = Map.copyOf(models);
    }

    @Override
    public SensorItemModel.Snapshot extractArgument(ItemStack stack) {
        throw new IllegalStateException("Sensor readings require the original item render context");
    }

    @Override
    public void getExtents(java.util.function.Consumer<org.joml.Vector3fc> extents) {
        for (float x : new float[]{0f, 1f})
            for (float y : new float[]{0f, 1f})
                for (float z : new float[]{0.46875f, 0.56f})
                    extents.accept(new Vector3f(x, y, z));
    }

    @Override
    public void submit(SensorItemModel.Snapshot snapshot, PoseStack pose,
                       SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
        SensorClient.Reading reading = snapshot.reading();
        Skin skin = snapshot.skin();
        long now = Util.getMillis();
        boolean pulsingLights = !ClientConfig.SPEC.isLoaded() || ClientConfig.INSTANCE.pulsingLights.get();
        Skin.Frame frame = new Skin.Frame(now, lockedPhase(reading, now), pulsingLights, light, overlay);
        collector.order(0).submitCustomGeometry(pose, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_ITEMS),
                (savedPose, vc) -> skin.render(poseStack(savedPose), vc, this::model, reading, frame));

        ItemStackRenderState sample = snapshot.sample();
        if (sample.isEmpty()) return;
        boolean flat = true;
        for (int i = 0; i < sample.activeLayerCount; i++) {
            if (sample.layers[i].specialRenderer != null || sample.layers[i].quads.isEmpty()) flat = false;
        }
        pose.pushPose();
        pose.translate(WINDOW_CENTRE_X, WINDOW_CENTRE_Y, flat ? DECAL_Z : 0.56f);
        pose.scale(SAMPLE_SCALE, SAMPLE_SCALE, SAMPLE_SCALE);
        if (flat) {
            for (int i = 0; i < sample.activeLayerCount; i++) {
                ItemStackRenderState.LayerRenderState layer = sample.layers[i];
                // Quads in a layer may use different atlases or cutout/translucent materials.
                Map<RenderType, List<BakedQuad>> materials = new LinkedHashMap<>();
                for (BakedQuad quad : layer.quads.all()) {
                    materials.computeIfAbsent(quad.materialInfo().itemRenderType(), key -> new ArrayList<>()).add(quad);
                }
                materials.forEach((renderType, quads) -> collector.order(1).submitCustomGeometry(pose, renderType,
                        (savedPose, vc) -> drawFlatIcon(poseStack(savedPose), vc, layer, quads, light, overlay)));
            }
        } else {
            sample.submit(pose, collector, light, overlay, outlineColor);
        }
        pose.popPose();
    }

    private static PoseStack poseStack(PoseStack.Pose savedPose) {
        PoseStack pose = new PoseStack();
        pose.last().set(savedPose);
        return pose;
    }

    /** A baked layer; an id no skin registered draws nothing rather than failing the frame. */
    private List<BakedQuad> model(Identifier layer) {
        return models.getOrDefault(layer, List.of());
    }

    /**
     * Draws a 3D model the way its inventory icon looks, pressed onto one plane, so a block sample
     * sits in the glass like a picture instead of a cube poking out of the dial.
     * <p>
     * Each quad uses LayerRenderState.applyTransform for the sample layer's GUI transform,
     * including model-space centring and the layer's local transform. Its transformed z is then
     * scaled by {@code DECAL_DEPTH}, preserving depth ordering while pressing the icon onto the
     * socket plane.
     * A face whose turned normal has z <= 0 faces away from the viewer and is skipped, which leaves
     * exactly the faces the icon shows, and each keeps the icon's fixed shading baked into its
     * colour: top (normal y > 0.5) 1.0, left-facing side (x < 0) 0.8, the other side 0.6. The
     * decal's normal is the dial's own front normal, so it is lit exactly like the dial face.
     * <p>
     * Worked through for a vanilla cube (block/block GUI transform: rotation (30, 225, 0),
     * translation 0, scale 0.625). rotationXYZ(30, 225, 0) = Rx(30)*Ry(225): turn about y, then x.
     * <pre>
     *   Ry(225): x' = -0.7071(x + z)   y' = y                  z' = 0.7071(x - z)
     *   Rx(30):  x' = x                y' = 0.8660y - 0.5000z  z' = 0.5000y + 0.8660z
     *
     *   Top face (Direction.UP, vertices in FaceInfo.UP order), centred c = p - 0.5:
     *   v0 (0,1,0) c(-.5,.5,-.5) Ry(.7071,.5,0)  Rx(.7071,.4330,.2500)  x.625 ( .4419,.2706, .1563)
     *   v1 (0,1,1) c(-.5,.5,.5)  Ry(0,.5,-.7071) Rx(0,.7866,-.3624)     x.625 ( 0,    .4916,-.2265)
     *   v2 (1,1,1) c(.5,.5,.5)   Ry(-.7071,.5,0) Rx(-.7071,.4330,.2500) x.625 (-.4419,.2706, .1563)
     *   v3 (1,1,0) c(.5,.5,-.5)  Ry(0,.5,.7071)  Rx(0,.0795,.8624)      x.625 ( 0,    .0497, .5390)
     *   flattened: right (.442,.271) -> top (0,.492) -> left (-.442,.271) -> bottom (0,.050),
     *   signed area +0.195: counter-clockwise seen from +z, so the sheet's back-face cull keeps it.
     *   normal (0,1,0) -> Ry (0,1,0) -> Rx (0,.8660,.5000): z > 0, drawn; y > 0.5, shade 1.0.
     *
     *   Side face (Direction.EAST, FaceInfo.EAST order):
     *   v0 (1,1,1) c(.5,.5,.5)   Ry(-.7071,.5,0)  Rx(-.7071,.4330,.2500)  x.625 (-.4419, .2706, .1563)
     *   v1 (1,0,1) c(.5,-.5,.5)  Ry(-.7071,-.5,0) Rx(-.7071,-.4330,-.2500) x.625 (-.4419,-.2706,-.1563)
     *   v2 (1,0,0) c(.5,-.5,-.5) Ry(0,-.5,.7071)  Rx(0,-.7866,.3624)       x.625 ( 0,   -.4916, .2265)
     *   v3 (1,1,0) c(.5,.5,-.5)  Ry(0,.5,.7071)   Rx(0,.0795,.8624)        x.625 ( 0,    .0497, .5390)
     *   flattened: upper left -> lower left -> bottom -> centre, signed area +0.239: counter-clockwise.
     *   normal (1,0,0) -> Ry (-.7071,0,.7071) -> Rx (-.7071,-.3536,.6124): z > 0, drawn; y <= 0.5 and
     *   x < 0, left side, shade 0.8.
     *
     *   NORTH turns to (.7071,-.3536,.6124): drawn, right side, shade 0.6. DOWN, SOUTH and WEST turn
     *   to z = -.5000, -.6124, -.6124: skipped. The three kept faces tile the hexagon |x| <= .442,
     *   |y| <= .492 without overlapping (they share the corners at (0,.050) etc.). The socket pose
     *   puts it at 0.5 +- 0.110 on x and 0.5 +- 0.123 on y, z 0.542 +- 0.007 (0.25 x .539 x
     *   DECAL_DEPTH): in front of the dial face (z 0.53), inside the 4x4 window (0.5 +- 0.125).
     * </pre>
     * A model built from several boxes (stairs, anvil, hopper...) is not one convex solid: faces
     * of different boxes land on the same pixels, and the nearer one has to win, as it does in
     * the real icon. That is why z is pressed, not dropped: at one shared z the LEQUAL depth test
     * of the block sheets lets whichever face is drawn later win. For stairs that is the lower
     * step's top (no cullface, so in the unculled list, drawn last), painted over the upper step's
     * front.
     */
    private static void drawFlatIcon(PoseStack ps, VertexConsumer vc,
                                     ItemStackRenderState.LayerRenderState layer, List<BakedQuad> quads, int light, int overlay) {
        PoseStack gui = new PoseStack();
        layer.applyTransform(gui.last());
        Matrix4f place = gui.last().pose();
        Matrix3f turn = gui.last().normal();


        // a block's own sheet (cutout, or translucent-cull for translucent blocks), on the block
        // atlas, which is where the quads' baked UVs point
        PoseStack.Pose dial = ps.last();
        Vector3f n = new Vector3f(), p = new Vector3f();
        for (BakedQuad quad : quads) {
            Direction face = quad.direction();
            turn.transform(face.getStepX(), face.getStepY(), face.getStepZ(), n).normalize();
            if (n.z() <= 0f) continue;                // faces away from the viewer
            // a face looking straight at the viewer (a flat item's front) is lit in full; the
            // three faces of a block's isometric icon get the inventory look: top, left, right
            float shade = n.z() >= 0.9f || n.y() > 0.5f ? 1.0f : n.x() < 0f ? 0.8f : 0.6f;
            int tint = quad.materialInfo().isTinted() && quad.materialInfo().tintIndex() < layer.tintLayers().size()
                    ? layer.tintLayers().getInt(quad.materialInfo().tintIndex()) : -1;
            float r = (tint >> 16 & 255) / 255f * shade;
            float g = (tint >> 8 & 255) / 255f * shade;
            float b = (tint & 255) / 255f * shade;

            for (int vertex = 0; vertex < 4; vertex++) {
                place.transformPosition(quad.position(vertex), p);
                long uv = quad.packedUV(vertex);
                vc.addVertex(dial.pose(), p.x(), p.y(), p.z() * DECAL_DEPTH)
                        .setColor(r, g, b, 1f)
                        .setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(dial, 0f, 0f, 1f);
            }
        }
    }

    /**
     * The shared phase (0..1) of the locked needle's pulse for {@code reading} at {@code now}: a
     * LOCKED reading in range advances it at a period that follows its closeness (PULSE_NEAR_MS on
     * the target, PULSE_NEAR_MS + PULSE_FAR_MS at the edge of the range); any other reading leaves
     * it alone and gets 0.
     */
    public static double lockedPhase(SensorClient.Reading reading, long now) {
        return reading.state() == SensorClient.State.LOCKED && !reading.lost()
                ? advancePulse(now, LayerSkin.PULSE_NEAR_MS + LayerSkin.PULSE_FAR_MS * (1.0 - reading.closeness())) : 0;
    }

    /** Advances the shared locked-needle pulse to {@code now} at the given period; returns its phase. */
    private static double advancePulse(long now, double periodMs) {
        if (pulseClock != Long.MIN_VALUE) pulsePhase = (pulsePhase + (now - pulseClock) / periodMs) % 1.0;
        pulseClock = now;
        return pulsePhase;
    }
}
