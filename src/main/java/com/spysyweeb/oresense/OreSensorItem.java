package com.spysyweeb.oresense;

import com.spysyweeb.oresense.menu.OreSensorMenu;
import com.spysyweeb.oresense.scan.KnownSamples;
import com.spysyweeb.oresense.scan.OreScanner;
import com.spysyweeb.oresense.scan.SampleResolver;
import com.spysyweeb.oresense.scan.ScanResult;
import com.spysyweeb.oresense.scan.Signal;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class OreSensorItem extends Item {
    // 1.18.1 has no amethyst resonance asset. Use its chime and compensate for the
    // 0.2 volume in vanilla's sounds.json so lock/release keep their intended loudness.
    private static final float AMETHYST_CHIME_VOLUME_SCALE = 5.0f;

    public static final String SAMPLE_TAG = "Sample";
    private static final String TARGET_TAG = "Target";      // [x,y,z]: the block the needle points at
    private static final String DIM_TAG = "TargetDim";
    private static final String PING_TAG = "PingTime";
    private static final String DIST_TAG = "PingDistance";
    private static final String RANGE_TAG = "PingRange";
    private static final String CHARGES_TAG = "Charges";
    private static final String NO_CHARGE_TAG = "NoCharge";
    private static final String IGNORE_TAG = "Ignore";
    private static final String LOCK_TAG = "Lock";
    private static final String LOCK_BLOCKS = "Blocks";     // BlockPos.asLong of every vein block at lock time
    private static final String LOCK_SINCE = "Since";
    private static final String LOCK_LOST = "LostSince";    // -1 while in range, else when it left range
    private static final String LOCK_PAID = "Paid";
    // 1.1.0, written when the lock is made and read from the lock while it exists (a config change
    // waits for the next lock); a 1.0.3 lock has none and reads as price 1, PER_VEIN, grace 200,
    // cap 128, closest
    private static final String LOCK_PRICE = "Price";       // shards
    private static final String LOCK_MODE = "Mode";         // ChargeMode name
    private static final String LOCK_GRACE = "Grace";       // ticks out of range before the lock lets go
    private static final String LOCK_CAP = "Cap";           // the vein cap its flood used
    private static final String LOCK_LARGEST = "Largest";   // chosen as the largest vein (not through the fallback)
    private static final String PICK_FALLBACK_TAG = "PickFallback"; // the fallback notice has been shown
    private static final String NEEDS_TAG = "Needs";        // with NoCharge: shards the found vein's lock needs

    /** Amethyst shards the sensor holds. Each lock's price is paid out of them (config charging). */
    public static final int MAX_CHARGES = 64;
    /** The vein cap 1.0.3 used for every lock; a 1.0.3 lock with this many blocks was cut short. */
    private static final int LEGACY_VEIN_CAP = 128;
    /** The lock grace 1.0.3 used for every lock: 10 s. */
    private static final int LEGACY_LOCK_GRACE = 200;
    /**
     * How long the price line stays above the hotbar after a lock, in ticks (vanilla shows an
     * action-bar message about this long); the continuous reading waits that long so it does not
     * write over it.
     */
    private static final int PRICE_LINE_TICKS = 60;
    /** A sneak-use release this close to the locked vein (blocks, centre to centre) counts as found. */
    public static final double FOUND_DISTANCE = 4.0;
    /** Paid veins remembered so they are not locked, and charged, again; the oldest goes first. */
    public static final int IGNORE_VEINS = 8;
    /** Positions remembered per paid vein. */
    public static final int IGNORE_ANCHORS_PER_VEIN = 4;

    /**
     * Whether the player looking at the tooltip holds Shift, which swaps the "Hold Shift" line for
     * the details. Common code cannot name a client class, so the client initializer sets it
     * (OreSenseClient.onInitializeClient); the dedicated server never builds tooltips.
     */
    public static BooleanSupplier moreInfoHeld = () -> false;

    public OreSensorItem(Properties properties) { super(properties); }

    // ---- reading (written by the server, read by the dial) ----

    /** Where the needle points, or null if the sensor has nothing to point at. */
    @Nullable
    public static BlockPos getTarget(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        if (tag == null || !tag.contains(TARGET_TAG)) return null;
        int[] xyz = tag.getIntArray(TARGET_TAG);
        return xyz.length == 3 ? new BlockPos(xyz[0], xyz[1], xyz[2]) : null;
    }

    public static String getTargetDimension(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null ? "" : tag.getString(DIM_TAG);
    }

    public static long getPingTime(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null ? Long.MIN_VALUE : tag.getLong(PING_TAG);
    }

    /** Distance to the target, or -1 when the last check found nothing. */
    public static float getDistance(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null || !tag.contains(DIST_TAG) ? -1f : tag.getFloat(DIST_TAG);
    }

    public static float getRange(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null || !tag.contains(RANGE_TAG) ? 32f : tag.getFloat(RANGE_TAG);
    }

    /** Amethyst shards stored, 0..{@link #MAX_CHARGES}. */
    public static int getCharges(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null ? 0 : Mth.clamp(tag.getInt(CHARGES_TAG), 0, MAX_CHARGES);
    }

    /**
     * Stores the shard count; the screen's charge slot writes through here. Adding enough charges
     * also clears NoCharge: the flag says the server could not pay the lock it found (Needs
     * shards, 1 when a 1.0.3 server set it), and that just stopped being true, so the dial should
     * not keep showing it until the next check.
     */
    public static void setCharges(ItemStack sensor, int charges) {
        int count = Mth.clamp(charges, 0, MAX_CHARGES);
        CompoundTag tag = sensor.getOrCreateTag();
        tag.putInt(CHARGES_TAG, count);
        if (tag.getBoolean(NO_CHARGE_TAG) && count >= neededShards(tag)) {
            tag.putBoolean(NO_CHARGE_TAG, false);
        }
    }

    /** The server found a vein but the sensor could not pay its lock price (never for creative). */
    public static boolean isNoCharge(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag != null && tag.getBoolean(NO_CHARGE_TAG);
    }

    /** With NoCharge: how many shards the vein the server found needs (1 from a 1.0.3 server). */
    public static int getNeededShards(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag == null ? 1 : neededShards(tag);
    }

    private static int neededShards(CompoundTag tag) {
        return tag.contains(NEEDS_TAG, Tag.TAG_INT) ? tag.getInt(NEEDS_TAG) : 1;
    }

    public static boolean isLocked(ItemStack sensor) {
        return lockOf(sensor) != null;
    }

    /** Locked, but the vein has been out of scan range since {@code LostSince}. */
    public static boolean isLockLost(ItemStack sensor) {
        CompoundTag lock = lockOf(sensor);
        return lock != null && lock.contains(LOCK_LOST) && lock.getLong(LOCK_LOST) >= 0;
    }

    /** Locked, and the vein's price has been paid (never for PER_BLOCK, which pays per block). */
    public static boolean isLockPaid(ItemStack sensor) {
        CompoundTag lock = lockOf(sensor);
        return lock != null && lock.getBoolean(LOCK_PAID);
    }

    @Nullable
    private static CompoundTag lockOf(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        return tag != null && tag.contains(LOCK_TAG, Tag.TAG_COMPOUND) ? tag.getCompound(LOCK_TAG) : null;
    }

    /**
     * Writes the result of one check; {@code pos == null} is a miss. PingTime is refreshed on
     * every check, hit or miss, because the client reads a fresh PingTime as "the server is
     * scanning this stack right now". NoCharge is written every time too, so it never outlives
     * the check that set it.
     */
    private static void record(ItemStack sensor, Level level, @Nullable BlockPos pos, double distance, int range,
                               boolean noCharge) {
        CompoundTag tag = sensor.getOrCreateTag();
        tag.putString(DIM_TAG, dimensionId(level));
        tag.putLong(PING_TAG, level.getGameTime());
        tag.putFloat(RANGE_TAG, range);
        tag.putBoolean(NO_CHARGE_TAG, noCharge);
        if (!noCharge) tag.remove(NEEDS_TAG);
        if (pos != null) {
            tag.putIntArray(TARGET_TAG, new int[]{pos.getX(), pos.getY(), pos.getZ()});
            tag.putFloat(DIST_TAG, (float) distance);
        } else {
            tag.remove(TARGET_TAG);
            tag.putFloat(DIST_TAG, -1f);
        }
    }

    private static void hit(ItemStack sensor, Level level, BlockPos pos, double distance, int range) {
        record(sensor, level, pos, distance, range, false);
    }

    private static void miss(ItemStack sensor, Level level, int range) {
        record(sensor, level, null, -1, range, false);
    }

    /** Ore found, but the sensor holds fewer shards than the lock would cost: no lock, red gauge. */
    private static void noCharge(ItemStack sensor, Level level, int range, int needs) {
        record(sensor, level, null, -1, range, true);
        sensor.getOrCreateTag().putInt(NEEDS_TAG, needs);
    }

    /**
     * The server stops checking this sensor: it lost its lock and is not in a hand. No Target
     * and no PingTime, so the dial goes dormant now instead of showing a reading that nobody
     * refreshes (a lock on a mined-out block, or a search) until the ping goes stale.
     */
    private static void endReading(ItemStack sensor) {
        CompoundTag tag = sensor.getOrCreateTag();
        tag.remove(TARGET_TAG);
        tag.remove(PING_TAG);
        tag.putFloat(DIST_TAG, -1f);
        tag.putBoolean(NO_CHARGE_TAG, false);
        tag.remove(NEEDS_TAG);
    }

    private static String dimensionId(Level level) {
        return level.dimension().location().toString();
    }

    // ---- sample ----

    /** The sample block this sensor is tuned to, or null if empty. */
    @Nullable
    public static Block getSampleBlock(ItemStack sensor) {
        ItemStack sample = getSample(sensor);
        if (sample.isEmpty() || !(sample.getItem() instanceof BlockItem blockItem)) return null;
        return blockItem.getBlock();
    }

    public static ItemStack getSample(ItemStack sensor) {
        CompoundTag tag = sensor.getTag();
        if (tag == null || !tag.contains(SAMPLE_TAG)) return ItemStack.EMPTY;
        return ItemStack.of(tag.getCompound(SAMPLE_TAG));
    }

    /**
     * Stores the sample (one of it). A lock belongs to the sample it was made with, so a
     * different sample lets it go, free and not remembered in Ignore; writing the same sample
     * back (the screen does on every change and on close) keeps it. Without this the next
     * check would read the lock's blocks against the new sample, find none, and could not
     * tell a swapped sample from a mined-out vein.
     */
    public static void setSample(ItemStack sensor, ItemStack sample) {
        if (!ItemStack.isSameItemSameTags(getSample(sensor), sample) && sensor.getTag() != null) {
            sensor.getTag().remove(LOCK_TAG);
        }
        if (sample.isEmpty()) {
            if (sensor.getTag() != null) sensor.getTag().remove(SAMPLE_TAG);
            return;
        }
        ItemStack one = sample.copy();
        one.setCount(1);
        sensor.getOrCreateTag().put(SAMPLE_TAG, one.save(new CompoundTag()));
    }

    /**
     * The slot takes only what the sensor can find: an ore, what it drops, or something made
     * purely from that (a diamond asks for diamond ore); with oresOnly off, any block too.
     * The server asks the resolver; the client checks the list the server sent it
     * ({@link KnownSamples}), so both sides refuse the same stacks and nothing snaps back.
     * A sensor is refused because the held one could otherwise be swapped into its own slot:
     * the hand goes empty, the screen closes, and the sensor is lost with the slot.
     */
    public static boolean isValidSample(Level level, ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof OreSensorItem) return false;
        if (level instanceof ServerLevel serverLevel) return !SampleResolver.resolve(serverLevel, stack).isEmpty();
        return KnownSamples.accepts(stack);
    }

    // ---- use ----

    /**
     * Right-click opens the sample and charge slots. Sneak-right-click on a locked sensor lets
     * the lock go instead: standing within {@link #FOUND_DISTANCE} of a vein not yet paid for
     * counts as having found it and pays its price; otherwise nothing is spent. A PER_BLOCK lock
     * is paid per block mined, so letting it go costs nothing.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack sensor = player.getItemInHand(hand);
        if (player.isSecondaryUseActive() && isLocked(sensor)) {
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                releaseByHand(serverLevel, serverPlayer, sensor);
            }
            return InteractionResultHolder.sidedSuccess(sensor, level.isClientSide());
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new ExtendedScreenHandlerFactory() {
                @Override
                public void writeScreenOpeningData(ServerPlayer openingPlayer, FriendlyByteBuf buf) {
                    buf.writeEnum(hand);
                }

                @Override
                public Component getDisplayName() {
                    return new TranslatableComponent("container.oresense.ore_sensor");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inventory, Player openingPlayer) {
                    return new OreSensorMenu(id, inventory, hand);
                }
            });
        }
        return InteractionResultHolder.sidedSuccess(sensor, level.isClientSide());
    }

    private static void releaseByHand(ServerLevel level, ServerPlayer player, ItemStack sensor) {
        forgetOtherLevel(level, sensor.getOrCreateTag());
        CompoundTag lock = lockOf(sensor);
        if (lock != null) {
            ItemStack sample = getSample(sensor);
            Set<Block> targets = sample.isEmpty() ? Set.of() : SampleResolver.resolve(level, sample);
            boolean paid = lock.getBoolean(LOCK_PAID);
            long[] blocks = lock.getLongArray(LOCK_BLOCKS);
            List<BlockPos> remaining = targets.isEmpty() ? List.of() : remaining(level, blocks, targets);
            // a PER_BLOCK lock charges only for blocks actually mined, so finding it costs nothing
            if (!paid && ChargePolicy.paysOnce(lockMode(lock)) && !remaining.isEmpty()) {
                BlockPos center = player.blockPosition();
                if (distance(center, nearest(remaining, center)) <= FOUND_DISTANCE && pay(player, sensor, lock)) {
                    // the shard chime only when shards are spent (or creative pays), not for a free lock
                    if (lockPrice(lock) > 0 || player.getAbilities().instabuild) {
                        player.playNotifySound(SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);
                    }
                }
            }
            // A hand release means "not this one": skip the vein whether or not it was paid,
            // otherwise the next check re-locks the same vein a second later (Alex heard exactly
            // that: the release chime followed by a lock chime).
            release(level, sensor, lock, true, blocks, remaining, targets);
        }
        miss(sensor, level, Config.INSTANCE.horizontalRange.get());
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                0.8f * AMETHYST_CHIME_VOLUME_SCALE, 0.6f);
    }

    // ---- server check ----

    /**
     * Every scanIntervalTicks the server checks a sensor that is in a hand, or one that is
     * locked anywhere in the inventory (the lock has to keep following its vein while the
     * holder mines with a pickaxe). A sensor elsewhere, unlocked, is left alone.
     */
    @Override
    public void inventoryTick(ItemStack sensor, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof ServerPlayer player)) return;
        // Compare the actual hand stacks by identity; only the main hand or off hand is held.
        boolean inHand = sensor == player.getMainHandItem() || sensor == player.getOffhandItem();
        if (!inHand && !isLocked(sensor)) return;

        int interval = Math.max(1, Config.INSTANCE.scanIntervalTicks.get());
        if ((level.getGameTime() + player.getId()) % interval != 0) return;
        check(serverLevel, player, sensor, inHand);
    }

    private static void check(ServerLevel level, ServerPlayer player, ItemStack sensor, boolean inHand) {
        Config config = Config.INSTANCE;
        int hRange = config.horizontalRange.get();
        int vRange = config.verticalRange.get();
        CompoundTag tag = sensor.getOrCreateTag();
        forgetOtherLevel(level, tag);

        // 1. nothing to look for: a lock means nothing without its sample and goes free
        ItemStack sample = getSample(sensor);
        Set<Block> targets = sample.isEmpty() ? Set.of() : SampleResolver.resolve(level, sample);
        if (targets.isEmpty()) {
            tag.remove(LOCK_TAG);
            if (inHand) miss(sensor, level, hRange); else endReading(sensor);
            return;
        }
        pruneIgnore(level, tag, targets);

        // 2. locked: follow the vein
        CompoundTag lock = lockOf(sensor);
        if (lock != null && followLock(level, player, sensor, lock, targets, inHand, hRange, vRange)) return;

        // 3. not locked (any more) and not held: nobody is looking at it
        if (!inHand) {
            endReading(sensor);
            return;
        }

        // 4. held and free: look for the closest (or largest) vein not already paid for
        BlockPos center = player.blockPosition();
        VeinPick pick = config.targetPick.get();
        int cap = config.veinCap.get();
        ScanResult result = OreScanner.scan(level, center, targets, hRange, vRange,
                tag.getLongArray(IGNORE_TAG), cap, IGNORE_VEINS, pick);
        noteFallback(player, tag, result.fallback());
        if (!result.found()) {
            miss(sensor, level, hRange);
            return;
        }
        // the price is fixed now and stored in the lock, so a config change does not move it
        List<BlockPos> vein = result.veinBlocks();
        ChargeMode mode = config.chargeMode.get();
        int price = ChargePolicy.price(mode, config.shardsPerVein.get(), config.blocksPerShard.get(), vein.size());
        boolean creative = player.getAbilities().instabuild;
        if (!creative && !ChargePolicy.affordable(price, getCharges(sensor))) {
            noCharge(sensor, level, hRange, price);
            return;
        }
        boolean largest = pick == VeinPick.LARGEST && !result.fallback();
        CompoundTag newLock = new CompoundTag();
        newLock.putLongArray(LOCK_BLOCKS, pack(vein));
        newLock.putLong(LOCK_SINCE, level.getGameTime());
        newLock.putLong(LOCK_LOST, -1L);
        newLock.putBoolean(LOCK_PAID, false);
        newLock.putInt(LOCK_PRICE, price);
        newLock.putString(LOCK_MODE, mode.name());
        newLock.putInt(LOCK_GRACE, config.lockGraceTicks.get());
        newLock.putInt(LOCK_CAP, cap);
        newLock.putBoolean(LOCK_LARGEST, largest);
        tag.put(LOCK_TAG, newLock);
        hit(sensor, level, result.nearest(), result.nearestDistance(), hRange);
        // Use the same chime as manual release, at a higher pitch for a new lock.
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                AMETHYST_CHIME_VOLUME_SCALE, 1.2f);
        Component priceLine = priceText(newLock, creative);
        if (priceLine != null) {
            // the price, once, above the hotbar, whether or not the continuous reading is on; the
            // reading waits PRICE_LINE_TICKS so it does not write over it (followLock)
            player.displayClientMessage(new TranslatableComponent("oresense.msg.locked_price", priceLine)
                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        } else {
            showReading(player, center, result.nearest(), result.nearestDistance(), hRange, vein.size(), largest);
        }
    }

    /**
     * A LARGEST scan that had to fall back to the closest vein (too many matching blocks to
     * compare) says so once, in chat so the reading cannot write over it; the next scan that
     * does not fall back ends the episode.
     */
    private static void noteFallback(ServerPlayer player, CompoundTag tag, boolean fallback) {
        if (!fallback) {
            tag.remove(PICK_FALLBACK_TAG);
        } else if (!tag.getBoolean(PICK_FALLBACK_TAG)) {
            tag.putBoolean(PICK_FALLBACK_TAG, true);
            player.sendMessage(new TranslatableComponent("oresense.msg.pick.fallback").withStyle(ChatFormatting.YELLOW), net.minecraft.Util.NIL_UUID);
        }
    }

    /**
     * Step 2 of a check. True when this check's reading is written (the lock holds, or it just
     * ran out of grace); false when the lock was let go and the check goes on as unlocked.
     */
    private static boolean followLock(ServerLevel level, ServerPlayer player, ItemStack sensor, CompoundTag lock,
                                      Set<Block> targets, boolean inHand, int hRange, int vRange) {
        CompoundTag tag = sensor.getOrCreateTag();
        boolean paid = lock.getBoolean(LOCK_PAID);
        boolean creative = player.getAbilities().instabuild;
        if (!ChargePolicy.keepsLock(paid, creative, lockPrice(lock), getCharges(sensor))) {
            // the shards this lock was taken on are gone (taken back out, spent by PER_BLOCK, or
            // the lock was made in creative); it goes free rather than let its vein be mined for nothing
            tag.remove(LOCK_TAG);
            return false;
        }
        if (!paid && pickChanged(tag, lock)) {
            // the world's targetPick now asks for the other kind of vein: an unpaid lock goes
            // free and this check scans again with the new choice
            tag.remove(LOCK_TAG);
            return false;
        }

        long[] blocks = lock.getLongArray(LOCK_BLOCKS);
        List<BlockPos> remaining = remaining(level, blocks, targets);
        if (remaining.isEmpty()) {
            release(level, sensor, lock, paid, blocks, remaining, targets);
            return false;
        }

        BlockPos center = player.blockPosition();
        BlockPos nearest = nearest(remaining, center);
        double distance = distance(center, nearest);
        if (inScanBox(center, nearest, hRange, vRange)) {
            lock.putLong(LOCK_LOST, -1L);
        } else {
            long now = level.getGameTime();
            long lostSince = lock.contains(LOCK_LOST) ? lock.getLong(LOCK_LOST) : -1L;
            if (lostSince < 0) {
                lock.putLong(LOCK_LOST, now);
            } else if (now - lostSince > lockGrace(lock)) {
                release(level, sensor, lock, paid, blocks, remaining, targets);
                if (inHand) miss(sensor, level, hRange); else endReading(sensor);
                return true;
            }
        }
        hit(sensor, level, nearest, distance, hRange);
        boolean priceLineShowing = priceText(lock, creative) != null
                && level.getGameTime() - lock.getLong(LOCK_SINCE) < PRICE_LINE_TICKS;
        if (inHand && !priceLineShowing) {
            showReading(player, center, nearest, distance, hRange, remaining.size(), lock.getBoolean(LOCK_LARGEST));
        }
        return true;
    }

    /**
     * Lets the lock go. With {@code ignore} (a vein already paid for, or a hand release) the vein
     * is remembered in Ignore so the next scan does not lock it, and charge for it, again; without,
     * it goes free and may be locked again. The paid vein's rest is flooded with the lock's own cap.
     */
    private static void release(Level level, ItemStack sensor, CompoundTag lock, boolean ignore, long[] blocks,
                                List<BlockPos> remaining, Set<Block> targets) {
        CompoundTag tag = sensor.getOrCreateTag();
        tag.remove(LOCK_TAG);
        if (ignore) addIgnore(tag, paidVeinAnchors(level, blocks, remaining, targets, lockCap(lock)));
    }

    /**
     * Lock and Ignore hold positions in the level of the last check (TargetDim, which every
     * check rewrites). In another level they would name that level's blocks, so both are
     * dropped; the lock goes free.
     */
    private static void forgetOtherLevel(Level level, CompoundTag tag) {
        if (dimensionId(level).equals(tag.getString(DIM_TAG))) return;
        tag.remove(LOCK_TAG);
        tag.remove(IGNORE_TAG);
    }

    // ---- charging ----

    /**
     * The player successfully broke a block (Fabric's PlayerBlockBreakEvents.AFTER, server
     * side). The player's sensors in the 36 main slots or the off hand whose lock contains that
     * block and still owes for it are charged by {@link ChargePolicy#chargeForBreak}: an unpaid
     * PER_VEIN or PER_SIZE lock pays its price once and stays, paid, on the rest of the vein; a
     * PER_BLOCK lock pays one shard for the block, and only one PER_BLOCK sensor pays per
     * block. Creative players pay nothing. A lock that cannot pay, or a PER_BLOCK lock that
     * just spent its last shard, is let go.
     */
    public static void onBlockBroken(ServerPlayer player, Level level, BlockPos pos) {
        long broken = pos.asLong();
        String dimension = dimensionId(level);
        Inventory inventory = player.getInventory();
        boolean creative = player.getAbilities().instabuild;
        List<ItemStack> sensors = new ArrayList<>();
        List<ChargePolicy.LockAtBreak> locks = new ArrayList<>();
        for (NonNullList<ItemStack> part : List.of(inventory.items, inventory.offhand)) {
            for (ItemStack stack : part) {
                if (!(stack.getItem() instanceof OreSensorItem)) continue;
                CompoundTag lock = lockOf(stack);
                if (lock == null) continue;
                ChargeMode mode = lockMode(lock);
                boolean paid = lock.getBoolean(LOCK_PAID);
                if (paid && ChargePolicy.paysOnce(mode)) continue;
                if (!dimension.equals(getTargetDimension(stack))) continue;
                if (!contains(lock.getLongArray(LOCK_BLOCKS), broken)) continue;
                sensors.add(stack);
                locks.add(new ChargePolicy.LockAtBreak(mode, paid, lockPrice(lock), getCharges(stack)));
            }
        }
        ChargePolicy.BreakCharge[] charges = ChargePolicy.chargeForBreak(locks, creative);
        boolean chime = false;
        for (int i = 0; i < charges.length; i++) {
            ChargePolicy.BreakCharge charge = charges[i];
            if (charge.equals(ChargePolicy.BreakCharge.NONE)) continue;
            ItemStack stack = sensors.get(i);
            CompoundTag tag = stack.getOrCreateTag();
            CompoundTag lock = lockOf(stack);
            if (charge.spend() > 0) {
                int left = Mth.clamp(tag.getInt(CHARGES_TAG), 0, MAX_CHARGES) - charge.spend();
                tag.putInt(CHARGES_TAG, left);
                if (left > 0) tag.putBoolean(NO_CHARGE_TAG, false);
                chime = true;
            }
            if (charge.markPaid()) {
                lock.putBoolean(LOCK_PAID, true);
                if (creative) chime = true;
            }
            if (charge.drop()) {
                tag.remove(LOCK_TAG);
                if (stack == player.getMainHandItem() || stack == player.getOffhandItem()) {
                    noCharge(stack, level, Config.INSTANCE.horizontalRange.get(), locks.get(i).price());
                } else {
                    endReading(stack);
                }
            }
        }
        // the shard chime only when shards are spent (or creative pays), never for a free lock
        if (chime) player.playNotifySound(SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    /**
     * Pays a PER_VEIN or PER_SIZE lock's price and marks it paid (the sneak-use release next to
     * its vein). False when the sensor holds fewer shards than the price; creative pays nothing.
     */
    private static boolean pay(ServerPlayer player, ItemStack sensor, CompoundTag lock) {
        boolean creative = player.getAbilities().instabuild;
        int charges = getCharges(sensor);
        int price = lockPrice(lock);
        if (!creative && !ChargePolicy.affordable(price, charges)) return false;
        lock.putBoolean(LOCK_PAID, true);
        if (!creative && price > 0) {
            CompoundTag tag = sensor.getOrCreateTag();
            tag.putInt(CHARGES_TAG, charges - price);
            if (charges > price) tag.putBoolean(NO_CHARGE_TAG, false);
        }
        return true;
    }

    /** The lock's price; a 1.0.3 lock (no Price) cost one shard. */
    private static int lockPrice(CompoundTag lock) {
        return lock.contains(LOCK_PRICE, Tag.TAG_INT) ? Math.max(0, lock.getInt(LOCK_PRICE)) : 1;
    }

    /** The lock's charge mode; a 1.0.3 lock (no Mode) was per vein. */
    private static ChargeMode lockMode(CompoundTag lock) {
        return ChargeMode.fromName(lock.getString(LOCK_MODE));
    }

    /** The vein cap the lock's flood used; a 1.0.3 lock was flooded with 128. */
    private static int lockCap(CompoundTag lock) {
        return lock.contains(LOCK_CAP, Tag.TAG_INT) ? Math.max(1, lock.getInt(LOCK_CAP)) : LEGACY_VEIN_CAP;
    }

    /** Ticks the lock may stay out of range; a 1.0.3 lock had 200. */
    private static int lockGrace(CompoundTag lock) {
        return lock.contains(LOCK_GRACE, Tag.TAG_INT) ? Math.max(0, lock.getInt(LOCK_GRACE)) : LEGACY_LOCK_GRACE;
    }

    // ---- vein choice ----

    /**
     * True when the world's targetPick now asks for the other kind of vein than the lock was
     * chosen as (the lock's Largest flag). A lock taken through the hit-limit fallback is a
     * closest-vein lock made while LARGEST was asked for; while PickFallback says the last scan
     * fell back, that is not a change (otherwise the lock would be let go and taken again every
     * check).
     */
    private static boolean pickChanged(CompoundTag tag, CompoundTag lock) {
        boolean largestNow = Config.INSTANCE.targetPick.get() == VeinPick.LARGEST;
        boolean lockLargest = lock.getBoolean(LOCK_LARGEST);
        if (largestNow && !lockLargest && tag.getBoolean(PICK_FALLBACK_TAG)) return false;
        return largestNow != lockLargest;
    }

    // ---- veins and the Ignore list ----

    /**
     * The lock's blocks that are still of the sample, in the lock's (breadth-first) order. A
     * block in an unloaded chunk counts as still there: it cannot be read without loading it.
     */
    private static List<BlockPos> remaining(Level level, long[] blocks, Set<Block> targets) {
        List<BlockPos> left = new ArrayList<>();
        for (long packed : blocks) {
            BlockPos pos = BlockPos.of(packed);
            if (stillThere(level, pos, targets)) left.add(pos);
        }
        return left;
    }

    private static boolean stillThere(Level level, BlockPos pos, Set<Block> targets) {
        if (!level.isLoaded(pos)) return !level.isOutsideBuildHeight(pos);
        return targets.contains(level.getBlockState(pos).getBlock());
    }

    /** The first of the closest blocks to the player's feet block, centre to centre. */
    private static BlockPos nearest(List<BlockPos> blocks, BlockPos center) {
        BlockPos best = blocks.get(0);
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            double dSq = pos.distSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, true);
            if (dSq < bestSq) {
                bestSq = dSq;
                best = pos;
            }
        }
        return best;
    }

    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(pos.distSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, true));
    }

    /** Inside the box a scan covers around the player's feet block. */
    private static boolean inScanBox(BlockPos center, BlockPos pos, int hRange, int vRange) {
        return Math.abs(pos.getX() - center.getX()) <= hRange
                && Math.abs(pos.getZ() - center.getZ()) <= hRange
                && Math.abs(pos.getY() - center.getY()) <= vRange;
    }

    /**
     * Anchors for a paid vein, from the part of it that is still there: the lock's remaining
     * blocks, or, once those are all mined, the rest of a vein too big for the lock (a flood
     * stops at the vein cap, and a geode can be bigger than that). A lock that holds fewer
     * than its {@code cap} blocks held the whole vein, so a mined-out one leaves nothing to
     * remember.
     */
    private static long[] paidVeinAnchors(Level level, long[] blocks, List<BlockPos> remaining, Set<Block> targets,
                                          int cap) {
        if (!remaining.isEmpty()) return spread(remaining);
        if (blocks.length < cap || targets.isEmpty()) return new long[0];
        for (long packed : blocks) {
            BlockPos mined = BlockPos.of(packed);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = mined.offset(dx, dy, dz);
                        if (OreScanner.matches(level, next, targets)) {
                            return spread(OreScanner.vein(level, next, targets, cap));
                        }
                    }
                }
            }
        }
        return new long[0];
    }

    /**
     * {@link #IGNORE_ANCHORS_PER_VEIN} positions spread over a vein in its breadth-first order:
     * the first, the last, and evenly between. A vein with fewer blocks repeats some, so every
     * group in Ignore has the same length and groups need no separator.
     */
    private static long[] spread(List<BlockPos> vein) {
        if (vein.isEmpty()) return new long[0];
        long[] group = new long[IGNORE_ANCHORS_PER_VEIN];
        int last = vein.size() - 1;
        for (int k = 0; k < group.length; k++) {
            group[k] = vein.get(k * last / (group.length - 1)).asLong();
        }
        return group;
    }

    /** Appends one vein's anchors to Ignore, dropping the oldest vein past {@link #IGNORE_VEINS}. */
    private static void addIgnore(CompoundTag tag, long[] group) {
        if (group.length != IGNORE_ANCHORS_PER_VEIN) return;
        int per = IGNORE_ANCHORS_PER_VEIN;
        long[] old = tag.getLongArray(IGNORE_TAG);
        int whole = old.length / per;
        int kept = Math.min(whole, IGNORE_VEINS - 1);
        long[] next = new long[(kept + 1) * per];
        System.arraycopy(old, (whole - kept) * per, next, 0, kept * per);
        System.arraycopy(group, 0, next, kept * per, per);
        tag.putLongArray(IGNORE_TAG, next);
    }

    /**
     * Drops the anchors whose block is no longer of the sample (an anchor in an unloaded chunk
     * stays: it cannot be read), refills each group from its surviving anchors, and drops a
     * vein whose anchors are all gone.
     */
    private static void pruneIgnore(Level level, CompoundTag tag, Set<Block> targets) {
        long[] old = tag.getLongArray(IGNORE_TAG);
        if (old.length == 0) return;
        int per = IGNORE_ANCHORS_PER_VEIN;
        LongArrayList kept = new LongArrayList(old.length);
        long[] alive = new long[per];
        for (int group = 0; group + per <= old.length; group += per) {
            int n = 0;
            for (int i = group; i < group + per; i++) {
                if (stillThere(level, BlockPos.of(old[i]), targets)) alive[n++] = old[i];
            }
            for (int i = 0; n > 0 && i < per; i++) kept.add(alive[i % n]);
        }
        if (kept.isEmpty()) {
            tag.remove(IGNORE_TAG);
        } else {
            long[] next = kept.toLongArray();
            if (!Arrays.equals(old, next)) tag.putLongArray(IGNORE_TAG, next);
        }
    }

    private static long[] pack(List<BlockPos> blocks) {
        long[] packed = new long[blocks.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = blocks.get(i).asLong();
        return packed;
    }

    private static boolean contains(long[] packed, long value) {
        for (long p : packed) {
            if (p == value) return true;
        }
        return false;
    }

    // ---- text ----

    /**
     * The optional action-bar reading (config showActionBar), in its 1.0.3 form; {@code count} is
     * blocks left in the vein. A lock chosen as the largest vein says so up front, one word. The
     * price and the fallback notice never go in here (they have their own lines, which do not
     * depend on showActionBar).
     */
    private static void showReading(ServerPlayer player, BlockPos center, BlockPos target, double distance,
                                    int range, int count, boolean largest) {
        if (!Config.INSTANCE.showActionBar.get()) return;
        Component strength = new TranslatableComponent(Signal.strength(distance, range));
        Component line;
        if (Config.INSTANCE.showDirection.get()) {
            line = new TranslatableComponent("oresense.msg.reading_dir",
                    strength,
                    new TranslatableComponent(Signal.compass(center, target)),
                    new TranslatableComponent(Signal.vertical(center, target)),
                    count);
        } else {
            line = new TranslatableComponent("oresense.msg.reading", strength, count);
        }
        if (largest) line = new TranslatableComponent("oresense.msg.reading_parts", new TranslatableComponent("oresense.msg.pick.largest"), line);
        player.displayClientMessage(line.copy().withStyle(ChatFormatting.AQUA), true);
    }

    /**
     * What the lock costs, before its first block is mined (ChargePolicy.showsPrice): "N blocks,
     * M shard(s)", or "1 shard per block"; null when there is nothing to announce (1.0.3's one
     * shard per vein, free, paid, or a creative player).
     */
    @Nullable
    private static Component priceText(CompoundTag lock, boolean creative) {
        ChargeMode mode = lockMode(lock);
        int price = lockPrice(lock);
        if (!ChargePolicy.showsPrice(mode, price, lock.getBoolean(LOCK_PAID), creative)) return null;
        if (mode == ChargeMode.PER_BLOCK) return new TranslatableComponent("oresense.msg.price_per_block");
        return new TranslatableComponent("oresense.msg.price", lock.getLongArray(LOCK_BLOCKS).length, price);
    }

    /**
     * The held lock's price for the hover tooltip (the client adds it, because only the client
     * knows whether the player looking is in creative); null when there is nothing to announce.
     */
    @Nullable
    public static Component lockPriceText(ItemStack sensor, boolean creative) {
        CompoundTag lock = lockOf(sensor);
        return lock == null ? null : priceText(lock, creative);
    }

    /**
     * Short by default, details behind Shift: the sample, shards and any warning, followed by
     * "Hold Shift" or the short usage details. Vanilla 1.18.1's item-tooltip path does not wrap
     * lines. When ChargePolicy.showsPrice permits it, SensorClient.addLockPrice inserts the
     * unpaid lock's price above the usage details. The sample is stored in NBT on 1.18.1;
     * vanilla adds no contents line for it.
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ItemStack sample = getSample(stack);
        tooltip.add(sample.isEmpty()
                ? new TranslatableComponent("oresense.tooltip.empty").withStyle(ChatFormatting.DARK_GRAY)
                : new TranslatableComponent("oresense.tooltip.tuned", sample.getHoverName()).withStyle(ChatFormatting.AQUA));
        int charges = getCharges(stack);
        if (charges > 0) {
            tooltip.add(new TranslatableComponent("oresense.tooltip.charges", charges, MAX_CHARGES).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (isNoCharge(stack)) {
            // the server found a vein this sensor cannot pay for: say what it costs
            tooltip.add(new TranslatableComponent("oresense.tooltip.needs_shards", getNeededShards(stack)).withStyle(ChatFormatting.RED));
        } else if (charges == 0) {
            // neutral, not a warning: the client cannot see the server's price, and a free sensor
            // (shardsPerVein 0) needs no shards; the red Needs line comes when a lock is refused
            tooltip.add(new TranslatableComponent("oresense.tooltip.no_charge").withStyle(ChatFormatting.GRAY));
        }
        if (!moreInfoHeld.getAsBoolean()) {
            tooltip.add(new TranslatableComponent("oresense.tooltip.more",
                    new TranslatableComponent("oresense.tooltip.more.key").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (sample.isEmpty()) {
            tooltip.add(new TranslatableComponent("oresense.tooltip.empty.more.1").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(new TranslatableComponent("oresense.tooltip.empty.more.2").withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(new TranslatableComponent("oresense.tooltip.usage.scan").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(new TranslatableComponent("oresense.tooltip.usage.lock").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(new TranslatableComponent("oresense.tooltip.usage.release").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) { return false; }

}
