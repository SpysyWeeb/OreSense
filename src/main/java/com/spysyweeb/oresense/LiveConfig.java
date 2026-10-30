package com.spysyweeb.oresense;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The settings screen's access to one config file ({@link Config#LIVE}, {@code
 * ClientConfig.LIVE}). Edits change memory; Done calls {@link #save()} for each changed file,
 * while Cancel restores the opening values without writing.
 * <p>
 * On FML 7.0.13 / NeoForge 21.5.98, this is a thin adapter over {@link
 * ModConfigSpec.ConfigValue#set}. ConfigTracker reads the TOML into a SynchronizedConfig with
 * TomlFormat. Setting a value changes memory without autosaving; LoadedConfig.save() writes
 * atomically and then posts the reload event.
 */
public final class LiveConfig {
    private final ModConfigSpec spec;

    public LiveConfig(ModConfigSpec spec) {
        this.spec = spec;
    }

    /** True while the file is loaded, so values can be read, changed and saved. */
    public boolean isLoaded() {
        return spec.isLoaded();
    }

    public <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return value.get();
    }

    /** Changes one value in memory, at once, without writing the file. */
    public <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        value.set(newValue);
    }

    /** Writes the file once, with every value as it is in memory now. */
    public void save() {
        if (isLoaded()) spec.save();
    }

    /** The smallest value a ranged int accepts, from the spec. */
    public int min(ModConfigSpec.IntValue value) {
        return range(value).getMin();
    }

    /** The largest value a ranged int accepts, from the spec. */
    public int max(ModConfigSpec.IntValue value) {
        return range(value).getMax();
    }

    private static ModConfigSpec.Range<Integer> range(ModConfigSpec.IntValue value) {
        return value.getSpec().getRange();
    }
}
