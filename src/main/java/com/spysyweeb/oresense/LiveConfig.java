package com.spysyweeb.oresense;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The settings screen's access to one config file ({@link Config#LIVE}, {@code
 * ClientConfig.LIVE}). Edits change memory; Done calls {@link #save()} for each changed file,
 * while Cancel restores the opening values without writing.
 * <p>
 * On Forge 58.1.0, FML attaches autosave to the top-level TOML config. Calling ConfigValue.set
 * would write during an edit, so this section-writing adapter updates the in-memory section and
 * clears the spec's value caches. This adapter writes the file only in save(). This keeps
 * slider changes and Cancel from writing intermediate values.
 * <p>
 * The render thread clears caches while the integrated server may read values; this adapter adds
 * no cross-thread visibility guarantee.
 */
public final class LiveConfig {
    private final ForgeConfigSpec spec;
    @Nullable
    private volatile ModConfig config;

    public LiveConfig(ForgeConfigSpec spec) {
        this.spec = spec;
    }

    /**
     * Mod-bus listener for ModConfigEvent.Loading, Reloading and Unloading: keeps the ModConfig
     * that holds this spec's file. Reloading runs on FML's file-watcher thread, hence volatile.
     */
    public void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() != spec) return;
        config = event instanceof ModConfigEvent.Unloading ? null : event.getConfig();
    }

    /** True while the file is loaded, so values can be changed and saved. */
    public boolean isLoaded() {
        ModConfig c = config;
        return c != null && c.getConfigData() != null;
    }

    /** The value as it is in memory now. */
    public <T> T get(ForgeConfigSpec.ConfigValue<T> value) {
        return value.get();
    }

    /** Changes one value in memory, at once, without writing the file. Enums are stored by name. */
    public <T> void set(ForgeConfigSpec.ConfigValue<T> value, T newValue) {
        ModConfig c = config;
        CommentedConfig data = c == null ? null : c.getConfigData();
        if (data == null) throw new IllegalStateException("OreSense config is not loaded");
        List<String> path = value.getPath();
        if (path.size() < 2) throw new IllegalArgumentException("live values must sit in a section: " + path);
        com.electronwill.nightconfig.core.Config section = data.get(path.subList(0, path.size() - 1));
        Object stored = newValue instanceof Enum<?> e ? e.name() : newValue;
        section.set(List.of(path.get(path.size() - 1)), stored);
        spec.afterReload();
    }

    /** Writes the file once, with every value as it is in memory now. */
    public void save() {
        if (isLoaded()) spec.save();
    }

    /** The smallest value a ranged int accepts, from the spec. */
    public int min(ForgeConfigSpec.IntValue value) {
        return range(value).getMin();
    }

    /** The largest value a ranged int accepts, from the spec. */
    public int max(ForgeConfigSpec.IntValue value) {
        return range(value).getMax();
    }

    private ForgeConfigSpec.Range<Integer> range(ForgeConfigSpec.IntValue value) {
        ForgeConfigSpec.ValueSpec valueSpec = spec.getSpec().get(value.getPath());
        return valueSpec.getRange();
    }
}
