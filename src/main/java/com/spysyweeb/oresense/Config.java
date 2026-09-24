package com.spysyweeb.oresense;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
    /** How long a reading keeps the needle locked on, in ticks. */
    public static final long TARGET_LIFETIME = 300;

    public static final ForgeConfigSpec SPEC;
    public static final Config INSTANCE;

    public final ForgeConfigSpec.IntValue horizontalRange;
    public final ForgeConfigSpec.IntValue verticalRange;
    public final ForgeConfigSpec.IntValue scanIntervalTicks;
    public final ForgeConfigSpec.BooleanValue showDirection;
    public final ForgeConfigSpec.BooleanValue oresOnly;
    public final ForgeConfigSpec.BooleanValue showActionBar;

    Config(ForgeConfigSpec.Builder b) {
        b.comment("OreSense - prospecting sensor").push("scanning");
        horizontalRange = b.comment("How far out the sensor looks, in blocks.")
                .defineInRange("horizontalRange", 32, 4, 96);
        verticalRange = b.comment("How far up and down the sensor looks, in blocks.")
                .defineInRange("verticalRange", 16, 4, 64);
        scanIntervalTicks = b.comment("How often the held sensor rescans, in ticks (20 = once a second).")
                .defineInRange("scanIntervalTicks", 20, 5, 200);
        showDirection = b.comment("Report a rough compass direction, not just strength.")
                .define("showDirection", true);
        showActionBar = b.comment("Also print the reading as text. The needle shows it either way.")
                .define("showActionBar", false);
        oresOnly = b.comment("true: samples must be ores, what they drop, or what is made purely from that (ingots, nuggets, blocks). false: any block may be sampled directly.")
                .define("oresOnly", true);
        b.pop();
    }

    static {
        Pair<Config, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }
}
