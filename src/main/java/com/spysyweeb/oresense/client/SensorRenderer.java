package com.spysyweeb.oresense.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.spysyweeb.oresense.OreSensorItem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.model.IQuadTransformer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the 16x16 dial as stacked layers (base, sonar ring, charge gauge, needle tail and tip, lit
 * lamp), then the sample sitting in the window at the centre so you can see what it is hunting for.
 */
public class SensorRenderer extends BlockEntityWithoutLevelRenderer {
    // locked needle glow: one pulse per PULSE_NEAR_MS on the target, PULSE_NEAR_MS + PULSE_FAR_MS
    // at the edge of the scan range, linear in between
    private static final double PULSE_NEAR_MS = 350.0;
    private static final double PULSE_FAR_MS = 1050.0;
    private static final long SONAR_MS = 1600L;   // one sweep of all the rings
    private static final long EMPTY_MS = 1400L;   // NO_CHARGE: the red gauge's slow pulse
    private static final long CHARGE_PULSE_MS = 2800L;
    private static final float CHARGE_BRIGHTNESS_LOW = 0.75f;
    private static final float CHARGE_BRIGHTNESS_SWING = 0.25f;

    // NO_CHARGE alpha of the red gauge row swings from EMPTY_ALPHA_LOW to EMPTY_ALPHA_LOW + EMPTY_ALPHA_SWING
    private static final float EMPTY_ALPHA_LOW = 0.35f;
    private static final float EMPTY_ALPHA_SWING = 0.65f;
    // Needle tints. The needle and tail frames are baked white, so one tint colours the whole
    // stroke: LOCKED red times the pulse brightness, with a light tail; a locked vein out of
    // range holds a steady dim red instead of pulsing; every other state rests grey, tip and tail
    private static final float[] LOCK_TIP = {0.93f, 0.14f, 0.10f};
    private static final float[] LOCK_TAIL = {0.85f, 0.85f, 0.85f};
    private static final float[] LOST_TIP = {0.5f, 0.08f, 0.06f};
    private static final float[] REST = {0.62f, 0.62f, 0.66f};

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
    private static final float SAMPLE_SCALE = 0.25f;
    // where the flat icon sits: the dial's front face is the generated model's z 8.5/16 = 0.53125,
    // and the icon keeps at most +-0.007 of depth (0.25 x 0.539 x DECAL_DEPTH), so 0.542 puts all
    // of it just in front of the face. Any further out and perspective slides it off the lens
    // centre when the dial is seen at an angle in hand.
    private static final float DECAL_Z = 0.542f;

    public static SensorRenderer instance;

    public SensorRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
              Minecraft.getInstance().getEntityModels());
    }

    public static SensorRenderer get() {
        if (instance == null) instance = new SensorRenderer();
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        Minecraft mc = Minecraft.getInstance();
        ModelManager models = mc.getModelManager();
        SensorClient.Reading reading = SensorClient.read(stack, context);
        long now = Util.getMillis();              // one clock for every sensor on screen

        // ItemRenderer has already applied the display transform and the -0.5 model shift, so
        // this is model space: x,y in [0,1], every layer's front face at z 0.53. All layers go
        // into one buffer in draw order. Every layer is an unturned item/generated model, so its
        // front quad shares its corners with the base's and comes out of the vertex transform
        // bit for bit alike: the same depth at every pixel (LEQUAL passes) and the same sort key
        // (BufferBuilder sorts translucent quads by the midpoint of vertices 0 and 2, stable for
        // ties), so each layer lands on top of the one before it.
        VertexConsumer vc = ItemRenderer.getFoilBufferDirect(buffer,
                ItemBlockRenderTypes.getRenderType(stack, true), true, false);

        drawLayer(pose, vc, models.getModel(SensorClient.BASE), 1f, 1f, 1f, 1f, light, overlay);

        if (reading.state() == SensorClient.State.SEARCHING) {
            float p = Math.floorMod(now, SONAR_MS) / (float) SONAR_MS;
            int ring = (int) (p * SensorClient.SONAR_RINGS);
            if (ring > 0) {
                drawLayer(pose, vc, models.getModel(SensorClient.sonar(ring - 1)),
                        1f, 1f, 1f, 0.30f * (1f - p), LightTexture.FULL_BRIGHT, overlay);
            }
            drawLayer(pose, vc, models.getModel(SensorClient.sonar(ring)),
                    1f, 1f, 1f, 0.85f * (1f - p), LightTexture.FULL_BRIGHT, overlay);
        }

        // the gauge before the needle: a needle pointing down crosses the gauge row under the
        // window and must stay one unbroken stroke over it
        drawGauge(pose, vc, models, reading, now, overlay);

        // the frame nearest the spring's angle: frame i points 11.25 degrees * i clockwise from up,
        // the same sense as the angle (positive = the target is to the player's right)
        int frame = Math.floorMod(Math.round(reading.angle() * SensorClient.NEEDLE_FRAMES), SensorClient.NEEDLE_FRAMES);
        BakedModel tip = models.getModel(SensorClient.needle(frame));
        BakedModel tail = models.getModel(SensorClient.tail(frame));
        if (reading.state() == SensorClient.State.LOCKED) {
            drawLayer(pose, vc, tail, LOCK_TAIL[0], LOCK_TAIL[1], LOCK_TAIL[2], 1f, light, overlay);
            float lampB;                      // the lamps pulse in step with the needle tip
            if (reading.lost()) {
                drawLayer(pose, vc, tip, LOST_TIP[0], LOST_TIP[1], LOST_TIP[2], 1f, LightTexture.FULL_BRIGHT, overlay);
                lampB = 0.55f;                // steady and dim, like the lost tip
            } else {
                double phase = advancePulse(now, PULSE_NEAR_MS + PULSE_FAR_MS * (1.0 - reading.closeness()));
                float b = (float) (0.55 + 0.45 * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)));
                drawLayer(pose, vc, tip, LOCK_TIP[0] * b, LOCK_TIP[1] * b, LOCK_TIP[2] * b, 1f, LightTexture.FULL_BRIGHT, overlay);
                lampB = b;
            }
            if (reading.vertical() != SensorClient.Vertical.LEVEL) {
                BakedModel lamp = models.getModel(reading.vertical() == SensorClient.Vertical.ABOVE
                        ? SensorClient.LAMP_UP : SensorClient.LAMP_DOWN);
                drawLayer(pose, vc, lamp, lampB, lampB, lampB, 1f, LightTexture.FULL_BRIGHT, overlay);
            }
        } else {
            drawLayer(pose, vc, tail, REST[0], REST[1], REST[2], 1f, light, overlay);
            drawLayer(pose, vc, tip, REST[0], REST[1], REST[2], 1f, light, overlay);
        }

        ItemStack sample = reading.sample();
        if (sample.isEmpty()) return;

        // Every sample with quads is drawn as its inventory icon pressed flat onto the lens: a
        // block would otherwise stand out of the dial as a cube, and even a flat item is a slab
        // 1/16 thick whose front face floats off the lens under perspective. Only models with no
        // quads keep the plain GUI render: builtin/entity models (chest, banner, skull, bed,
        // trident...) draw through a BEWLR, and ItemRenderer.render swaps the spyglass's in-hand
        // model for its flat inventory model in GUI (ItemRenderer.java:105-112).
        BakedModel model = mc.getItemRenderer().getModel(sample, mc.level, null, 0);
        boolean flat = !model.isCustomRenderer() && !sample.is(Items.SPYGLASS);
        pose.pushPose();
        pose.translate(0.5f, 0.5f, flat ? DECAL_Z : 0.56f);   // lens centre
        pose.scale(SAMPLE_SCALE, SAMPLE_SCALE, SAMPLE_SCALE);
        if (flat) {
            drawFlatIcon(pose, buffer, mc, sample, model, light, overlay);
        } else {
            mc.getItemRenderer().renderStatic(sample, ItemDisplayContext.GUI, light, overlay,
                    pose, buffer, mc.level, 0);
        }
        pose.popPose();
    }

    /**
     * Draws a 3D model the way its inventory icon looks, pressed onto one plane, so a block sample
     * sits in the glass like a picture instead of a cube poking out of the dial.
     * <p>
     * Each quad goes through the model's own GUI transform, built by the engine's own
     * {@code ItemTransform.apply} (translate, then {@code rotationXYZ}, then scale; Forge's
     * right_rotation after that, identity for vanilla), after the same -0.5 centring
     * ItemRenderer.render does. Then z is pressed to {@code DECAL_DEPTH} of itself, so every face
     * lies on the socket plane as far as the eye can tell, while the depth test still sees which
     * part of the model is nearer.
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
    private static void drawFlatIcon(PoseStack ps, MultiBufferSource buffer, Minecraft mc,
                                     ItemStack sample, BakedModel model, int light, int overlay) {
        PoseStack gui = new PoseStack();
        model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false, gui);
        Matrix4f place = gui.last().pose();
        Matrix3f turn = gui.last().normal();

        // the same quad lists, in the same order and with the same seed, as renderModelLists
        List<BakedQuad> quads = new ArrayList<>();
        RandomSource rand = RandomSource.create();
        for (Direction side : Direction.values()) {
            rand.setSeed(42L);
            quads.addAll(model.getQuads(null, side, rand));
        }
        rand.setSeed(42L);
        quads.addAll(model.getQuads(null, null, rand));

        // a block's own sheet (cutout, or translucent-cull for translucent blocks), on the block
        // atlas, which is where the quads' baked UVs point
        VertexConsumer vc = buffer.getBuffer(ItemBlockRenderTypes.getRenderType(sample, true));
        PoseStack.Pose dial = ps.last();
        Vector3f n = new Vector3f(), p = new Vector3f();
        for (BakedQuad quad : quads) {
            Direction face = quad.getDirection();
            turn.transform(face.getStepX(), face.getStepY(), face.getStepZ(), n).normalize();
            if (n.z() <= 0f) continue;                // faces away from the viewer
            // a face looking straight at the viewer (a flat item's front) is lit in full; the
            // three faces of a block's isometric icon get the inventory look: top, left, right
            float shade = n.z() >= 0.9f || n.y() > 0.5f ? 1.0f : n.x() < 0f ? 0.8f : 0.6f;
            int tint = quad.isTinted() ? mc.getItemColors().getColor(sample, quad.getTintIndex()) : -1;
            float r = (tint >> 16 & 255) / 255f * shade;
            float g = (tint >> 8 & 255) / 255f * shade;
            float b = (tint & 255) / 255f * shade;

            // vertex data is DefaultVertexFormat.BLOCK; IQuadTransformer holds its int offsets
            int[] v = quad.getVertices();
            for (int o = 0; o < v.length; o += IQuadTransformer.STRIDE) {
                place.transformPosition(
                        Float.intBitsToFloat(v[o + IQuadTransformer.POSITION]) - 0.5f,
                        Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 1]) - 0.5f,
                        Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 2]) - 0.5f, p);
                int c = v[o + IQuadTransformer.COLOR];  // R,G,B,A bytes, R in the low byte
                vc.vertex(dial.pose(), p.x(), p.y(), p.z() * DECAL_DEPTH)  // pressed onto the socket
                        .color(r * (c & 255) / 255f, g * (c >> 8 & 255) / 255f, b * (c >> 16 & 255) / 255f, 1f)
                        .uv(Float.intBitsToFloat(v[o + IQuadTransformer.UV0]),
                            Float.intBitsToFloat(v[o + IQuadTransformer.UV0 + 1]))
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(dial, 0f, 0f, 1f)
                        .endVertex();
            }
        }
    }

    /**
     * The charge gauge, the row of four cells under the window. The base holds all four unlit
     * (dark amethyst), so only lit cells are drawn. Stored charge is shown even when dormant
     * or without a sample: ceil(charges * 4 / 64) cells lit from the left end, 16 shards each,
     * with the filled pixels gently pulsing between 75% and full brightness.
     * One shard already lights a cell, so an unlit gauge always means empty. NO_CHARGE shows the
     * red row in a slow pulse (the server found ore but the sensor has no amethyst to lock with).
     */
    private static void drawGauge(PoseStack pose, VertexConsumer vc, ModelManager models,
                                  SensorClient.Reading reading, long now, int overlay) {
        switch (reading.state()) {
            case NO_CHARGE -> {
                double phase = Math.floorMod(now, EMPTY_MS) / (double) EMPTY_MS;
                float a = (float) (EMPTY_ALPHA_LOW
                        + EMPTY_ALPHA_SWING * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)));
                drawLayer(pose, vc, models.getModel(SensorClient.EMPTY), 1f, 1f, 1f, a, LightTexture.FULL_BRIGHT, overlay);
            }
            case DORMANT, SEARCHING, LOCKED -> {
                int max = OreSensorItem.MAX_CHARGES;
                int cells = SensorClient.CHARGE_CELLS;
                int lit = (Mth.clamp(reading.charges(), 0, max) * cells + max - 1) / max;
                double phase = Math.floorMod(now, CHARGE_PULSE_MS) / (double) CHARGE_PULSE_MS;
                float brightness = (float) (CHARGE_BRIGHTNESS_LOW
                        + CHARGE_BRIGHTNESS_SWING * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)));
                for (int i = 0; i < lit; i++) {
                    drawLayer(pose, vc, models.getModel(SensorClient.charge(i)),
                            brightness, brightness, brightness, 1f, LightTexture.FULL_BRIGHT, overlay);
                }
            }
        }
    }

    /** Advances the shared locked-needle pulse to {@code now} at the given period; returns its phase. */
    private static double advancePulse(long now, double periodMs) {
        if (pulseClock != Long.MIN_VALUE) pulsePhase = (pulsePhase + (now - pulseClock) / periodMs) % 1.0;
        pulseClock = now;
        return pulsePhase;
    }

    /**
     * Writes one item/generated layer straight into {@code vc} with our own tint and alpha
     * (renderModelLists would route the tint through ItemColors and has no alpha).
     */
    private static void drawLayer(PoseStack ps, VertexConsumer vc, BakedModel model,
                                  float r, float g, float b, float a, int light, int overlay) {
        PoseStack.Pose pose = ps.last();
        RandomSource rand = RandomSource.create(42L);
        float[] bright = {1f, 1f, 1f, 1f};
        int[] lights = {light, light, light, light};
        for (Direction d : Direction.values())
            for (BakedQuad q : model.getQuads(null, d, rand)) vc.putBulkData(pose, q, bright, r, g, b, a, lights, overlay, false);
        for (BakedQuad q : model.getQuads(null, null, rand)) vc.putBulkData(pose, q, bright, r, g, b, a, lights, overlay, false);
    }
}
