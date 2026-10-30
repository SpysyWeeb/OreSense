package com.spysyweeb.oresense;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Common/server settings in config/oresense.json: read at startup, read again on /reload, and
 * written by the settings screen's Done ({@link #save()}). The holders are live: every check reads
 * them, so a change applies at the next scan, reading or lock without a restart. The settings
 * screen edits them only through {@link #LIVE}: in memory at once, the file written on Done.
 */
public final class Config {
    public static final long TARGET_LIFETIME = 300;
    public static final String FILE_NAME = "oresense.json";
    public static final String SCANNING = "scanning";
    public static final String CHARGING = "charging";
    public static final String TARGETING = "targeting";
    public static final Config INSTANCE = new Config();
    private static volatile boolean loaded;
    /** The settings screen's way in: live changes in memory, one write on Done. */
    public static final LiveConfig LIVE = new LiveConfig(() -> loaded, Config::save);

    public final Value<Integer> horizontalRange = Value.integer(SCANNING, "horizontalRange", 32, 4, 96,
            "How far out the sensor looks, in blocks. Larger boxes cost the server more per scan.");
    public final Value<Integer> verticalRange = Value.integer(SCANNING, "verticalRange", 16, 4, 64,
            "How far up and down it looks, in blocks.");
    public final Value<Integer> scanIntervalTicks = Value.integer(SCANNING, "scanIntervalTicks", 20, 5, 200,
            "Ticks between scans of a held sensor. 20 is once a second.");
    public final Value<Boolean> showDirection = Value.bool(SCANNING, "showDirection", true,
            "Adds a rough compass direction, and above or below, to the text reading (showActionBar).");
    public final Value<Boolean> showActionBar = Value.bool(SCANNING, "showActionBar", false,
            "Also print the reading above the hotbar. The needle shows it either way.");
    public final Value<Boolean> oresOnly = Value.bool(SCANNING, "oresOnly", true,
            "Samples must be ores, what they drop, or what is made from that. Off: any block.");

    public final Value<ChargeMode> chargeMode = Value.choice(CHARGING, "chargeMode", ChargeMode.class, ChargeMode.PER_VEIN,
            "What a shard buys.\n"
            + "PER_VEIN: shardsPerVein shards for the whole vein, paid when its first block is mined.\n"
            + "PER_SIZE: one shard per blocksPerShard blocks of the vein (rounded up, at least one), paid when its first block is mined.\n"
            + "PER_BLOCK: one shard for every block of the vein that is mined.\n"
            + "PER_BLOCK loophole: a player can put the sensor away after the first block and mine the rest of the vein for free. Choose it knowing that.");
    public final Value<Integer> shardsPerVein = Value.integer(CHARGING, "shardsPerVein", 1, 0, 16,
            "PER_VEIN price. 0 makes the sensor free.");
    public final Value<Integer> blocksPerShard = Value.integer(CHARGING, "blocksPerShard", 16, 1, 128,
            "PER_SIZE price: one shard per this many blocks of the vein, rounded up.");
    public final Value<Integer> veinCap = Value.integer(CHARGING, "veinCap", 128, 16, 256,
            "Most blocks one lock covers. A geode shell is over 64.");
    public final Value<Integer> lockGraceTicks = Value.integer(CHARGING, "lockGraceTicks", 200, 0, 1200,
            "How long a lock survives out of range before it is let go.");

    public final Value<VeinPick> targetPick = Value.choice(TARGETING, "targetPick", VeinPick.class, VeinPick.CLOSEST,
            "CLOSEST: the nearest vein.\n"
            + "LARGEST: the biggest vein in the scan box, the nearer one on a tie; falls back to CLOSEST for a scan whose box holds more than 4096 matching blocks.");

    /** Every setting, in file order. */
    private final List<Value<?>> values = List.of(horizontalRange, verticalRange, scanIntervalTicks, showDirection,
            showActionBar, oresOnly, chargeMode, shardsPerVein, blocksPerShard, veinCap, lockGraceTicks, targetPick);

    private Config() {}

    public List<Value<?>> values() {
        return values;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Startup: reads the file, or writes one with the defaults when there is none. */
    public static void load() {
        load(path());
    }

    public static void load(Path path) {
        ConfigFile.load(path, INSTANCE.values, "OreSense configuration", true);
        loaded = true;
    }

    /**
     * /reload: reads the file again. A file that cannot be read keeps the settings in use
     * instead of falling back to the defaults on a running server.
     */
    public static void reload() {
        ConfigFile.load(path(), INSTANCE.values, "OreSense configuration", false);
    }

    /** Writes the settings in use to the file (the settings screen's Done). */
    public static void save() {
        save(path());
    }

    public static void save(Path path) {
        ConfigFile.save(path, INSTANCE.values, "OreSense configuration");
    }

    /**
     * One setting: where it lives in the file, its default, what values it accepts, and the
     * value in use. {@link #set} takes only accepted values, so the holders are always valid.
     */
    public static final class Value<T> {
        public enum Kind { INTEGER, BOOLEAN, CHOICE, TEXT }

        private final Kind kind;
        private final String section;
        private final String key;
        private final String comment;
        private final T defaultValue;
        private final int min;
        private final int max;
        @Nullable private final T[] choices;
        private volatile T value;

        private Value(Kind kind, String section, String key, T defaultValue, int min, int max,
                      @Nullable T[] choices, String comment) {
            this.kind = kind;
            this.section = section;
            this.key = key;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.choices = choices;
            this.comment = comment;
            this.value = defaultValue;
        }

        public static Value<Integer> integer(String section, String key, int defaultValue, int min, int max, String comment) {
            return new Value<>(Kind.INTEGER, section, key, defaultValue, min, max, null, comment);
        }

        public static Value<Boolean> bool(String section, String key, boolean defaultValue, String comment) {
            return new Value<>(Kind.BOOLEAN, section, key, defaultValue, 0, 0, null, comment);
        }

        public static <E extends Enum<E>> Value<E> choice(String section, String key, Class<E> type, E defaultValue,
                                                          String comment) {
            return new Value<>(Kind.CHOICE, section, key, defaultValue, 0, 0, type.getEnumConstants(), comment);
        }

        /** Free text, never blank (a skin id: an unknown one is resolved where it is used). */
        public static Value<String> text(String section, String key, String defaultValue, String comment) {
            return new Value<>(Kind.TEXT, section, key, defaultValue, 0, 0, null, comment);
        }

        public T get() { return value; }

        public void set(T newValue) {
            if (!accepts(newValue)) throw new IllegalArgumentException("Invalid value for " + section + "." + key + ": " + newValue);
            value = newValue;
        }

        public void reset() { value = defaultValue; }

        public T defaultValue() { return defaultValue; }

        public Kind kind() { return kind; }

        public String section() { return section; }

        public String key() { return key; }

        /** The integer range, inclusive; only meaningful for {@link Kind#INTEGER}. */
        public int min() { return min; }

        public int max() { return max; }

        /** The accepted values of a {@link Kind#CHOICE}, in order; empty for other kinds. */
        public List<T> choices() {
            return choices == null ? List.of() : List.of(choices);
        }

        public boolean accepts(@Nullable Object candidate) {
            if (candidate == null) return false;
            return switch (kind) {
                case INTEGER -> candidate instanceof Integer i && i >= min && i <= max;
                case BOOLEAN -> candidate instanceof Boolean;
                case CHOICE -> choices().contains(candidate);
                case TEXT -> candidate instanceof String s && !s.isBlank();
            };
        }

        /** The value a JSON element stands for, or null when this setting does not accept it. */
        @Nullable
        @SuppressWarnings("unchecked")
        T fromJson(JsonElement element) {
            if (!element.isJsonPrimitive()) return null;
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            Object parsed = null;
            switch (kind) {
                case INTEGER -> {
                    if (primitive.isNumber()) {
                        try {
                            parsed = primitive.getAsBigDecimal().intValueExact();
                        } catch (ArithmeticException | NumberFormatException ignored) {
                            // not a whole number in int range
                        }
                    }
                }
                case BOOLEAN -> parsed = primitive.isBoolean() ? primitive.getAsBoolean() : null;
                case CHOICE -> {
                    if (primitive.isString()) {
                        String name = primitive.getAsString().trim();
                        for (T choice : choices()) {
                            if (((Enum<?>) choice).name().equalsIgnoreCase(name)) parsed = choice;
                        }
                    }
                }
                case TEXT -> parsed = primitive.isString() ? primitive.getAsString().trim() : null;
            }
            return accepts(parsed) ? (T) parsed : null;
        }

        JsonElement toJson(T v) {
            return switch (kind) {
                case INTEGER -> new JsonPrimitive((Integer) v);
                case BOOLEAN -> new JsonPrimitive((Boolean) v);
                case CHOICE -> new JsonPrimitive(((Enum<?>) v).name());
                case TEXT -> new JsonPrimitive((String) v);
            };
        }

        /** What the file says about this setting: the description, then what it accepts and its default. */
        String fileComment() {
            String accepted = switch (kind) {
                case INTEGER -> String.format(Locale.ROOT, "Range: %d to %d. ", min, max);
                case BOOLEAN -> "true or false. ";
                case CHOICE -> "One of: " + String.join(", ", choices().stream().map(c -> ((Enum<?>) c).name()).toList()) + ". ";
                case TEXT -> "";
            };
            return comment + "\n" + accepted + "Default: " + toJson(defaultValue);
        }

        /** What the warning for a rejected file value says this setting expects. */
        String expected() {
            return switch (kind) {
                case INTEGER -> String.format(Locale.ROOT, "an integer from %d to %d", min, max);
                case BOOLEAN -> "true or false";
                case CHOICE -> "one of " + String.join(", ", choices().stream().map(c -> ((Enum<?>) c).name()).toList());
                case TEXT -> "a non-empty string";
            };
        }
    }
}
