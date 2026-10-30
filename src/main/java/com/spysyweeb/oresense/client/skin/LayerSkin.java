package com.spysyweeb.oresense.client.skin;

import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.OreSensorItem;
import com.spysyweeb.oresense.client.SensorClient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A skin made of the 75 item/generated layers tools/gen_textures.py writes, stacked on one plane:
 * base -> sonar ring -> charge gauge -> needle tail -> needle tip -> lit lamp. The layers are
 * {@code ore_sensor_base}, {@code _empty}, {@code _lamp_up}, {@code _lamp_down},
 * {@code _charge_00..03}, {@code _needle_00..31}, {@code _tail_00..31} and {@code _sonar_00..02},
 * under {@code oresense:<prefix>}. Any square texture size works: nothing here assumes 16 px.
 */
public final class LayerSkin implements Skin {
    // locked needle glow: one pulse per PULSE_NEAR_MS on the target, PULSE_NEAR_MS + PULSE_FAR_MS
    // at the edge of the scan range, linear in between (the phase comes in Frame.lockedPhase)
    public static final double PULSE_NEAR_MS = 350.0;
    public static final double PULSE_FAR_MS = 1050.0;
    private static final long SONAR_MS = 1600L;   // one sweep of all the rings
    private static final long EMPTY_MS = 1400L;   // NO_CHARGE: the red gauge's slow pulse
    private static final long CHARGE_PULSE_MS = 2800L;
    private static final float CHARGE_BRIGHTNESS_LOW = 0.75f;
    private static final float CHARGE_BRIGHTNESS_SWING = 0.25f;
    // NO_CHARGE alpha of the red gauge row swings from EMPTY_ALPHA_LOW to EMPTY_ALPHA_LOW + EMPTY_ALPHA_SWING
    private static final float EMPTY_ALPHA_LOW = 0.35f;
    private static final float EMPTY_ALPHA_SWING = 0.65f;
    // pulsingLights off holds the sweep where ring 1 has just started (the preview sheet's ring-1 frame)
    private static final float SONAR_HELD_PHASE = 1f / SensorClient.SONAR_RINGS;
    // Needle tints. The needle and tail frames are baked white, so one tint colours the whole
    // stroke: LOCKED red times the pulse brightness, with a light tail; a locked vein out of
    // range holds a steady dim red instead of pulsing; every other state rests grey, tip and tail
    private static final float[] LOCK_TIP = {0.93f, 0.14f, 0.10f};
    private static final float[] LOCK_TAIL = {0.85f, 0.85f, 0.85f};
    private static final float[] LOST_TIP = {0.5f, 0.08f, 0.06f};
    private static final float[] REST = {0.62f, 0.62f, 0.66f};

    private final String id;
    private final String prefix;
    private final int previewLockedFrame;
    private final ResourceLocation base, lampUp, lampDown, empty;
    private final ResourceLocation[] needle, tail, sonar, charge;

    /**
     * {@code prefix} is the model path the layers sit under, e.g. "item/" or "item/skins/concept/";
     * {@code previewLockedFrame} is the LOCKED needle frame of the skin's preview sheet
     * ({@link Skin#previewLockedFrame}).
     */
    public LayerSkin(String id, String prefix, int previewLockedFrame) {
        this.id = id;
        this.prefix = prefix;
        this.previewLockedFrame = Objects.checkIndex(previewLockedFrame, SensorClient.NEEDLE_FRAMES);
        this.base = layer("ore_sensor_base");
        this.lampUp = layer("ore_sensor_lamp_up");
        this.lampDown = layer("ore_sensor_lamp_down");
        this.empty = layer("ore_sensor_empty");
        this.needle = numbered("ore_sensor_needle_", SensorClient.NEEDLE_FRAMES);
        this.tail = numbered("ore_sensor_tail_", SensorClient.NEEDLE_FRAMES);
        this.sonar = numbered("ore_sensor_sonar_", SensorClient.SONAR_RINGS);
        this.charge = numbered("ore_sensor_charge_", SensorClient.CHARGE_CELLS);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Component name() {
        return Component.translatable("oresense.skin." + id);
    }

    @Override
    public int previewLockedFrame() {
        return previewLockedFrame;
    }

    /** The model of one layer, by its file name ({@code ore_sensor_base} etc.). */
    public ResourceLocation layer(String name) {
        return new ResourceLocation(OreSense.MODID, prefix + name);
    }

    private ResourceLocation[] numbered(String name, int count) {
        ResourceLocation[] models = new ResourceLocation[count];
        for (int i = 0; i < count; i++) models[i] = layer(String.format(Locale.ROOT, "%s%02d", name, i));
        return models;
    }

    @Override
    public List<ResourceLocation> layers() {
        List<ResourceLocation> layers = new ArrayList<>(List.of(base, lampUp, lampDown, empty));
        layers.addAll(List.of(needle));
        layers.addAll(List.of(tail));
        layers.addAll(List.of(sonar));
        layers.addAll(List.of(charge));
        return layers;
    }

    /**
     * The texture of one of this skin's layers: {@code textures/<prefix><name>.png}, which is what
     * the layer's item/generated model draws (layer0 = {@code oresense:<prefix><name>}). Built from
     * the prefix and the layer's file name, so it does not depend on how a version names the
     * model itself.
     */
    @Override
    public ResourceLocation texture(ResourceLocation layer) {
        String path = layer.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new ResourceLocation(OreSense.MODID, "textures/" + prefix + name + ".png");
    }

    /**
     * The dial, bottom to top: base, sonar ring (SEARCHING), charge gauge, needle tail, needle tip,
     * lit lamp (LOCKED, above or below). The sonar, the gauge, a locked tip and the lamps glow.
     */
    @Override
    public void compose(SensorClient.Reading reading, Frame frame, LayerSink sink) {
        sink.layer(base, 1f, 1f, 1f, 1f, false);

        if (reading.state() == SensorClient.State.SEARCHING) {
            float p = frame.pulsingLights() ? Math.floorMod(frame.now(), SONAR_MS) / (float) SONAR_MS : SONAR_HELD_PHASE;
            int ring = Math.min((int) (p * SensorClient.SONAR_RINGS), SensorClient.SONAR_RINGS - 1);
            if (ring > 0) {
                sink.layer(sonar[ring - 1], 1f, 1f, 1f, 0.30f * (1f - p), true);
            }
            sink.layer(sonar[ring], 1f, 1f, 1f, 0.85f * (1f - p), true);
        }

        // the gauge before the needle: a needle pointing down crosses the gauge row under the
        // window and must stay one unbroken stroke over it
        composeGauge(reading, frame, sink);

        // the frame nearest the spring's angle: frame i points 11.25 degrees * i clockwise from up,
        // the same sense as the angle (positive = the target is to the player's right)
        int frameIndex = Math.floorMod(Math.round(reading.angle() * SensorClient.NEEDLE_FRAMES), SensorClient.NEEDLE_FRAMES);
        ResourceLocation tip = needle[frameIndex];
        ResourceLocation tl = tail[frameIndex];
        if (reading.state() == SensorClient.State.LOCKED) {
            sink.layer(tl, LOCK_TAIL[0], LOCK_TAIL[1], LOCK_TAIL[2], 1f, false);
            float lampB;                      // the lamps pulse in step with the needle tip
            if (reading.lost()) {
                sink.layer(tip, LOST_TIP[0], LOST_TIP[1], LOST_TIP[2], 1f, true);
                lampB = 0.55f;                // steady and dim, like the lost tip
            } else {
                // pulsingLights off holds the glow at full
                float b = frame.pulsingLights()
                        ? (float) (0.55 + 0.45 * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * frame.lockedPhase())))
                        : 1f;
                sink.layer(tip, LOCK_TIP[0] * b, LOCK_TIP[1] * b, LOCK_TIP[2] * b, 1f, true);
                lampB = b;
            }
            if (reading.vertical() != SensorClient.Vertical.LEVEL) {
                ResourceLocation lamp = reading.vertical() == SensorClient.Vertical.ABOVE ? lampUp : lampDown;
                sink.layer(lamp, lampB, lampB, lampB, 1f, true);
            }
        } else {
            sink.layer(tl, REST[0], REST[1], REST[2], 1f, false);
            sink.layer(tip, REST[0], REST[1], REST[2], 1f, false);
        }
    }

    /**
     * The charge gauge, the row of four cells under the window. The base holds all four unlit
     * (dark amethyst), so only lit cells are drawn. Stored charge is shown even when dormant
     * or without a sample: ceil(charges * 4 / 64) cells lit from the left end, 16 shards each,
     * with the filled pixels gently pulsing between 75% and full brightness (held full with
     * pulsingLights off). One shard already lights a cell, so an unlit gauge always means empty.
     * NO_CHARGE shows the red row in a slow pulse (held bright with pulsingLights off): the server
     * found ore but the sensor holds too few shards to lock it.
     */
    private void composeGauge(SensorClient.Reading reading, Frame frame, LayerSink sink) {
        switch (reading.state()) {
            case NO_CHARGE -> {
                float a = EMPTY_ALPHA_LOW + EMPTY_ALPHA_SWING;
                if (frame.pulsingLights()) {
                    double phase = Math.floorMod(frame.now(), EMPTY_MS) / (double) EMPTY_MS;
                    a = (float) (EMPTY_ALPHA_LOW + EMPTY_ALPHA_SWING * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)));
                }
                sink.layer(empty, 1f, 1f, 1f, a, true);
            }
            case DORMANT, SEARCHING, LOCKED -> {
                int max = OreSensorItem.MAX_CHARGES;
                int cells = SensorClient.CHARGE_CELLS;
                int lit = (Mth.clamp(reading.charges(), 0, max) * cells + max - 1) / max;
                float brightness = 1f;
                if (frame.pulsingLights()) {
                    double phase = Math.floorMod(frame.now(), CHARGE_PULSE_MS) / (double) CHARGE_PULSE_MS;
                    brightness = (float) (CHARGE_BRIGHTNESS_LOW
                            + CHARGE_BRIGHTNESS_SWING * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)));
                }
                for (int i = 0; i < lit; i++) {
                    sink.layer(charge[i], brightness, brightness, brightness, 1f, true);
                }
            }
        }
    }
}
