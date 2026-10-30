package com.spysyweeb.oresense;

import java.util.function.BooleanSupplier;

/**
 * The settings screen's access to one config file ({@link Config#LIVE}, {@code
 * ClientConfig.LIVE}). Edits change memory; Done calls {@link #save()} for each changed file,
 * while Cancel restores the opening values without writing.
 * <p>
 * On Fabric and Quilt this is a thin adapter over {@link Config.Value#set}. Setting a value
 * changes memory without writing. This adapter saves through {@link Config#save()} or {@code
 * ClientConfig.save()}; loading can also create a file or add missing settings.
 */
public final class LiveConfig {
    private final BooleanSupplier loaded;
    private final Runnable writer;

    /**
     * @param loaded true once the file has been read (or written with the defaults)
     * @param writer writes the file with every value as it is in memory
     */
    public LiveConfig(BooleanSupplier loaded, Runnable writer) {
        this.loaded = loaded;
        this.writer = writer;
    }

    /** True while the file is loaded, so values can be read, changed and saved. */
    public boolean isLoaded() {
        return loaded.getAsBoolean();
    }

    public <T> T get(Config.Value<T> value) {
        return value.get();
    }

    /** Changes one value in memory, at once, without writing the file. */
    public <T> void set(Config.Value<T> value, T newValue) {
        value.set(newValue);
    }

    /** Writes the file once, with every value as it is in memory now. */
    public void save() {
        if (isLoaded()) writer.run();
    }

    /** The smallest value an int setting accepts. */
    public int min(Config.Value<Integer> value) {
        return value.min();
    }

    /** The largest value an int setting accepts. */
    public int max(Config.Value<Integer> value) {
        return value.max();
    }
}
