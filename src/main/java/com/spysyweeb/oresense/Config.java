package com.spysyweeb.oresense;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The gameplay settings, config/oresense-common.toml. Every key is read on the server only; a
 * client's copy does nothing on a dedicated server. Existing keys keep the names, sections,
 * ranges and defaults they had in 1.0.3, so every tuned file stays valid; NeoForge adds the new keys
 * with their defaults the first time a 1.0.3 file loads.
 */
public class Config {
    /** How long a reading keeps the needle locked on, in ticks. */
    public static final long TARGET_LIFETIME = 300;

    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;
    /** The loaded file behind {@link #SPEC}: live edits from the settings screen, and its Done. */
    public static final LiveConfig LIVE;

    public final ModConfigSpec.IntValue horizontalRange;
    public final ModConfigSpec.IntValue verticalRange;
    public final ModConfigSpec.IntValue scanIntervalTicks;
    public final ModConfigSpec.BooleanValue showDirection;
    public final ModConfigSpec.BooleanValue oresOnly;
    public final ModConfigSpec.BooleanValue showActionBar;

    public final ModConfigSpec.EnumValue<ChargeMode> chargeMode;
    public final ModConfigSpec.IntValue shardsPerVein;
    public final ModConfigSpec.IntValue blocksPerShard;
    public final ModConfigSpec.IntValue veinCap;
    public final ModConfigSpec.IntValue lockGraceTicks;

    public final ModConfigSpec.EnumValue<VeinPick> targetPick;

    Config(ModConfigSpec.Builder b) {
        b.comment("OreSense - prospecting sensor").push("scanning");
        horizontalRange = b.comment("How far out the sensor looks, in blocks. Larger boxes cost the server more per scan.")
                .defineInRange("horizontalRange", 32, 4, 96);
        verticalRange = b.comment("How far up and down it looks, in blocks.")
                .defineInRange("verticalRange", 16, 4, 64);
        scanIntervalTicks = b.comment("Ticks between scans of a held sensor. 20 is once a second.")
                .defineInRange("scanIntervalTicks", 20, 5, 200);
        showDirection = b.comment("Adds a rough compass direction, and above or below, to the text reading (showActionBar).")
                .define("showDirection", true);
        showActionBar = b.comment("Also print the reading above the hotbar. The needle shows it either way.")
                .define("showActionBar", false);
        oresOnly = b.comment("Samples must be ores, what they drop, or what is made from that. Off: any block.")
                .define("oresOnly", true);
        b.pop();

        b.comment("What a shard of amethyst buys. A lock's price is fixed when it is made.").push("charging");
        chargeMode = b.comment(
                        "What a shard buys.",
                        "PER_VEIN: shardsPerVein shards for the whole vein, paid when its first block is mined.",
                        "PER_SIZE: one shard per blocksPerShard blocks of the vein (rounded up, at least one), paid when its first block is mined.",
                        "PER_BLOCK: one shard for every block of the vein that is mined.",
                        "PER_BLOCK loophole: a player can put the sensor away after the first block and mine the rest of the vein for free. Choose it knowing that.")
                .defineEnum("chargeMode", ChargeMode.PER_VEIN);
        shardsPerVein = b.comment("PER_VEIN price. 0 makes the sensor free.")
                .defineInRange("shardsPerVein", 1, 0, 16);
        blocksPerShard = b.comment("PER_SIZE price: one shard per this many blocks of the vein, rounded up.")
                .defineInRange("blocksPerShard", 16, 1, 128);
        veinCap = b.comment("Most blocks one lock covers. A geode shell is over 64.")
                .defineInRange("veinCap", 128, 16, 256);
        lockGraceTicks = b.comment("How long a lock survives out of range before it is let go.")
                .defineInRange("lockGraceTicks", 200, 0, 1200);
        b.pop();

        b.comment("Which vein a sensor locks onto.").push("targeting");
        targetPick = b.comment(
                        "CLOSEST: the nearest vein.",
                        "LARGEST: the biggest vein in the scan box, the nearer one on a tie; falls back to CLOSEST for a scan whose box holds more than 4096 matching blocks.")
                .defineEnum("targetPick", VeinPick.CLOSEST);
        b.pop();
    }

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
        LIVE = new LiveConfig(SPEC);
    }
}
