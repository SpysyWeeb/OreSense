package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.Config;
import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.OreSensorItem;
import com.spysyweeb.oresense.scan.Signal;
import net.fabricmc.fabric.api.client.model.ModelLoadingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

public class SensorClient {
    public static final int SONAR_RINGS = 3;
    /** Charge gauge cells under the window, 16 shards each: cell 0 is the left end, the gauge fills to the right. */
    public static final int CHARGE_CELLS = 4;
    /** Needle frames: frame i points 11.25 degrees * i clockwise from straight up, like the vanilla compass. */
    public static final int NEEDLE_FRAMES = 32;

    public static final ModelResourceLocation BASE = layer("ore_sensor_base");
    /** The lit lamps in the rim: 12 o'clock = the target is above, 6 o'clock = below. */
    public static final ModelResourceLocation LAMP_UP = layer("ore_sensor_lamp_up");
    public static final ModelResourceLocation LAMP_DOWN = layer("ore_sensor_lamp_down");
    /** NO_CHARGE: the four gauge cells in red. */
    public static final ModelResourceLocation EMPTY = layer("ore_sensor_empty");
    private static final ModelResourceLocation[] NEEDLE = numbered("ore_sensor_needle_", NEEDLE_FRAMES);
    private static final ModelResourceLocation[] TAIL = numbered("ore_sensor_tail_", NEEDLE_FRAMES);
    private static final ModelResourceLocation[] SONAR = numbered("ore_sensor_sonar_", SONAR_RINGS);
    private static final ModelResourceLocation[] CHARGE = numbered("ore_sensor_charge_", CHARGE_CELLS);

    /**
     * DORMANT: no sample, nobody is scanning with it, or it is not in the local player's view
     * (dropped, in an item frame, on a head). SEARCHING: being scanned, nothing found.
     * NO_CHARGE: being scanned, ore found, but the server would not lock on because the sensor
     * holds no amethyst (its NoCharge flag; the client never decides this from Charges).
     * LOCKED: being scanned and pointing at a block of the locked vein in this dimension.
     */
    public enum State { DORMANT, SEARCHING, NO_CHARGE, LOCKED }

    /** Where the hit is relative to the player's feet, with the same tolerance as the text reading. */
    public enum Vertical { LEVEL, ABOVE, BELOW }

    /**
     * What one rendered sensor shows: {@code angle} is where the needle tip points, in turns
     * (0..1) clockwise from straight up; the tail sits opposite it.
     * {@code lost}: the locked vein is out of range (the lock is in its grace time).
     * {@code paid}: a charge was spent on the locked vein. {@code closeness}: 0 at the edge of
     * the scan range .. 1 on the target block, 0 unless LOCKED. {@code charges}: shards stored.
     */
    public record Reading(State state, float angle, Vertical vertical, ItemStack sample,
                          boolean lost, boolean paid, float closeness, int charges) {}

    // Needle spring, in turns clockwise from up, shared by every rendered sensor like vanilla's
    // compass wobble; it only ever follows the local player's own view of a sensor, and steps
    // once per game tick. prevRotation is where the last step started, so a frame between two
    // ticks can draw the needle part of the way along that step.
    private static double rotation = 0.0, prevRotation = 0.0, delta = 0.0;
    private static long lastTick = 0;

    public static ModelResourceLocation needle(int frame) {
        return NEEDLE[frame];
    }

    public static ModelResourceLocation tail(int frame) {
        return TAIL[frame];
    }

    public static ModelResourceLocation sonar(int ring) {
        return SONAR[ring];
    }

    public static ModelResourceLocation charge(int cell) {
        return CHARGE[cell];
    }

    private static ModelResourceLocation layer(String name) {
        return new ModelResourceLocation(OreSense.MODID, name, "inventory");
    }

    private static ModelResourceLocation[] numbered(String prefix, int count) {
        ModelResourceLocation[] models = new ModelResourceLocation[count];
        for (int i = 0; i < count; i++) models[i] = layer(String.format(Locale.ROOT, "%s%02d", prefix, i));
        return models;
    }

    public static void registerModels() {
        ModelLoadingRegistry.INSTANCE.registerModelProvider((manager, out) -> {
            out.accept(BASE);
            out.accept(LAMP_UP);
            out.accept(LAMP_DOWN);
            out.accept(EMPTY);
            for (ModelResourceLocation frame : NEEDLE) out.accept(frame);
            for (ModelResourceLocation frame : TAIL) out.accept(frame);
            for (ModelResourceLocation ring : SONAR) out.accept(ring);
            for (ModelResourceLocation cell : CHARGE) out.accept(cell);
        });
    }

    public static void onClientSetup() {
        ScreenRegistry.register(OreSense.ORE_SENSOR_MENU, OreSensorScreen::new);
        BuiltinItemRendererRegistry.DynamicItemRenderer renderer = SensorRenderer.get()::renderByItem;
        BuiltinItemRendererRegistry.INSTANCE.register(OreSense.ORE_SENSOR, renderer);
    }

    /** Works out what this stack's dial shows right now, easing the needle toward the live reading. */
    public static Reading read(ItemStack stack, ItemDisplayContext context) {
        ItemStack sample = OreSensorItem.getSample(stack);
        int charges = OreSensorItem.getCharges(stack);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel world = mc.level;
        if (player == null || world == null || !isLocalView(stack, context, player)) {
            return new Reading(State.DORMANT, 0f, Vertical.LEVEL, sample, false, false, 0f, charges);
        }

        long now = world.getGameTime();
        BlockPos target = OreSensorItem.getTarget(stack);
        State state;
        if (sample.isEmpty() || now - OreSensorItem.getPingTime(stack) > Config.TARGET_LIFETIME) {
            state = State.DORMANT;
        } else if (target != null
                && !OreSensorItem.isNoCharge(stack)   // the server never writes both; keep NO_CHARGE authoritative if it ever did
                && world.dimension().location().toString().equals(OreSensorItem.getTargetDimension(stack))) {
            state = State.LOCKED;
        } else if (OreSensorItem.isNoCharge(stack)) {
            state = State.NO_CHARGE;
        } else {
            state = State.SEARCHING;
        }

        double want = 0.0;                        // rest: straight up
        Vertical vertical = Vertical.LEVEL;
        boolean lost = false, paid = false;
        float closeness = 0f;
        if (state == State.LOCKED) {
            lost = OreSensorItem.isLockLost(stack);
            paid = OreSensorItem.isLockPaid(stack);
            // the server's distance to the target over its scan range; -1 would mean a miss,
            // which a LOCKED reading never carries, but it must not read as "on top of it"
            float distance = OreSensorItem.getDistance(stack);
            closeness = distance < 0f ? 0f
                    : 1f - Mth.clamp(distance / Math.max(1f, OreSensorItem.getRange(stack)), 0f, 1f);

            double dx = (target.getX() + 0.5) - player.getX();
            double dz = (target.getZ() + 0.5) - player.getZ();
            double facing = Math.toRadians(player.getViewYRot(mc.getFrameTime()) + 90.0);
            want = wrap((Math.atan2(dz, dx) - facing) / (Math.PI * 2.0));

            int sign = Signal.verticalSign(target.getY() - player.blockPosition().getY());
            if (sign > 0) vertical = Vertical.ABOVE;
            else if (sign < 0) vertical = Vertical.BELOW;
        }

        if (now != lastTick) {
            lastTick = now;
            // vanilla's compass spring, CompassItemPropertyFunction$CompassWobble.update in 1.20.1:
            // pull 0.1 of the shortest way to the target into the speed, keep 0.8 of the speed
            double d = wrap(want - rotation + 0.5) - 0.5;
            delta += d * 0.1;
            delta *= 0.8;
            prevRotation = rotation;
            rotation = wrap(rotation + delta);
        }

        float angle = 0f;                         // DORMANT rests straight up
        if (state != State.DORMANT) {
            // the shortest way from the last step's start to its end (the step is |delta| < 0.5)
            double step = wrap(rotation - prevRotation + 0.5) - 0.5;
            angle = (float) wrap(prevRotation + mc.getFrameTime() * step);
        }
        return new Reading(state, angle, vertical, sample, lost, paid, closeness, charges);
    }

    /**
     * True when this render is the local player looking at their own sensor: an inventory or
     * hotbar icon, their first-person hands, or their own hands in third person. Another player's
     * held sensor also renders in a third-person context, but it is a different stack object than
     * the local player's hand items, so it stays dormant instead of steering the shared needle.
     */
    private static boolean isLocalView(ItemStack stack, ItemDisplayContext context, LocalPlayer player) {
        if (context == ItemDisplayContext.GUI || context.firstPerson()) return true;
        if (context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            return stack == player.getMainHandItem() || stack == player.getOffhandItem();
        }
        return false;
    }

    private static double wrap(double v) {
        v %= 1.0;
        return v < 0 ? v + 1.0 : v;
    }
}
