package com.spysyweeb.oresense.client;

import com.spysyweeb.oresense.Config;
import com.spysyweeb.oresense.OreSense;
import com.spysyweeb.oresense.OreSensorItem;
import com.spysyweeb.oresense.client.skin.Skin;
import com.spysyweeb.oresense.client.skin.Skins;
import com.spysyweeb.oresense.scan.Signal;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.model.ModelLoadingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

public class SensorClient {
    public static final int SONAR_RINGS = 3;
    /** Charge gauge cells under the window, 16 shards each: cell 0 is the left end, the gauge fills to the right. */
    public static final int CHARGE_CELLS = 4;
    /** Needle frames: frame i points 11.25 degrees * i clockwise from straight up, like the vanilla compass. */
    public static final int NEEDLE_FRAMES = 32;

    // the layer models of each skin are in client.skin.LayerSkin

    /**
     * DORMANT: no sample, nobody is scanning with it, or it is not in the local player's view
     * (dropped, in an item frame, on a head). SEARCHING: being scanned, nothing found.
     * NO_CHARGE: being scanned, ore found, but the server would not lock on because the sensor
     * cannot pay the lock price (its NoCharge flag; the client never decides this from Charges).
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

    /** Registers the layer models of every built-in skin, so switching skins needs no reload. */
    public static void registerModels() {
        ModelLoadingRegistry.INSTANCE.registerModelProvider((manager, out) -> {
            for (Skin skin : Skins.all()) {
                for (ResourceLocation layer : skin.layers()) out.accept(layer);
            }
        });
    }

    public static void onClientSetup() {
        ScreenRegistry.register(OreSense.ORE_SENSOR_MENU, OreSensorScreen::new);
        BuiltinItemRendererRegistry.DynamicItemRenderer renderer = SensorRenderer.get()::renderByItem;
        BuiltinItemRendererRegistry.INSTANCE.register(OreSense.ORE_SENSOR, renderer);
        ItemTooltipCallback.EVENT.register((stack, flag, lines) -> addLockPrice(stack, lines));
    }

    /**
     * The first line after the price's place in the sensor's tooltip: the "Hold Shift" line, or
     * with Shift held the details that replace it (the empty-sensor note first, else the usage).
     */
    private static final Set<String> TOOLTIP_AFTER_PRICE = Set.of(
            "oresense.tooltip.more", "oresense.tooltip.empty.more.1", "oresense.tooltip.usage.scan");

    /**
     * While the sensor holds an unpaid lock whose price differs from 1.0.3's one shard, the hover
     * tooltip says what it costs, above the "Hold Shift" line (or the details it stands for).
     * Added here rather than in OreSensorItem.appendHoverText because only the client knows the
     * player looking is in creative, where nothing is paid and no price is shown.
     */
    public static void addLockPrice(ItemStack stack, List<Component> lines) {
        if (!(stack.getItem() instanceof OreSensorItem)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        Component price = OreSensorItem.lockPriceText(stack, player != null && player.getAbilities().instabuild);
        if (price == null) return;
        int at = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getContents() instanceof TranslatableContents t && TOOLTIP_AFTER_PRICE.contains(t.getKey())) {
                at = i;
                break;
            }
        }
        lines.add(at, Component.translatable("oresense.tooltip.lock_price", price).withStyle(ChatFormatting.LIGHT_PURPLE));
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
            // vanilla's compass spring, CompassItemPropertyFunction$CompassWobble.update in 1.20:
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
