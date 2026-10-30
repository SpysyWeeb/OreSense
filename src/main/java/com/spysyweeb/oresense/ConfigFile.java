package com.spysyweeb.oresense;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes a settings file: a JSON object of sections, each an object of settings, with a
 * {@code //} comment above every setting. Gson's lenient reader (the one the 1.0.x loader already
 * used) skips the comments, so files with and without them read the same.
 * <p>
 * The file is only rewritten when it lacks a setting (a file from an older version gains the new
 * keys with their defaults) or on {@link #save}. Rewriting keeps every value as written, including
 * a rejected one (so it stays there to be corrected) and keys this version does not know.
 */
public final class ConfigFile {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ConfigFile() {}

    /**
     * Reads {@code path} into {@code values}. No file: every setting takes its default and the file
     * is written. A file that cannot be parsed is left alone for correction; the settings fall
     * back to their defaults when {@code initial}, and keep their current values otherwise (a
     * re-read must not reset a running server). A missing or rejected setting takes its default.
     */
    public static synchronized void load(Path path, List<Config.Value<?>> values, String what, boolean initial) {
        if (!Files.exists(path)) {
            values.forEach(Config.Value::reset);
            try {
                write(path, new JsonObject(), values);
            } catch (IOException e) {
                LOGGER.warn("Cannot write {} {}; using defaults", what, path, e);
            }
            return;
        }
        JsonObject root = read(path);
        if (root == null) {
            if (initial) {
                values.forEach(Config.Value::reset);
                LOGGER.warn("Cannot read {} {}; using defaults. The file has been kept for correction.", what, path);
            } else {
                LOGGER.warn("Cannot read {} {}; keeping the settings in use. The file has been kept for correction.", what, path);
            }
            return;
        }

        warnAboutNonObjectSections(path, root, values);
        List<String> added = new ArrayList<>();
        for (Config.Value<?> value : values) {
            JsonElement element = setting(root, value);
            if (element == null) {
                value.reset();
                added.add(value.section() + "." + value.key());
            } else {
                apply(value, element);
            }
        }
        if (!initial) LOGGER.info("Reloaded {} from {}", what, path);
        if (added.isEmpty()) return;
        try {
            write(path, root, values);
            LOGGER.info("Added {} to {} with their defaults", String.join(", ", added), path);
        } catch (IOException e) {
            LOGGER.warn("Cannot add new settings to {} {}; using their defaults", what, path, e);
        }
    }

    /**
     * Writes the values in use to {@code path}, keeping any keys of the file this version does not
     * know. A file that cannot be parsed is replaced: saving means the screen's values are wanted.
     */
    public static synchronized void save(Path path, List<Config.Value<?>> values, String what) {
        JsonObject root = Files.exists(path) ? read(path) : null;
        if (root == null) root = new JsonObject();
        warnAboutNonObjectSections(path, root, values);
        for (Config.Value<?> value : values) put(root, value);
        try {
            write(path, root, values);
        } catch (IOException e) {
            LOGGER.error("Cannot save {} to {}", what, path, e);
        }
    }

    @Nullable
    private static JsonObject read(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            @SuppressWarnings("deprecation")   // the instance form exists on every Gson Minecraft ships
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            LOGGER.warn("{} does not hold a JSON object", path);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot parse {}: {}", path, e.toString());
        }
        return null;
    }

    /**
     * A known section that is not a JSON object cannot hold its settings: they take their defaults
     * and the section is written back as an object. Said in the log, not done silently.
     */
    private static void warnAboutNonObjectSections(Path path, JsonObject root, List<Config.Value<?>> values) {
        Set<String> sections = new LinkedHashSet<>();
        for (Config.Value<?> value : values) sections.add(value.section());
        for (String section : sections) {
            JsonElement element = root.get(section);
            if (element != null && !element.isJsonObject()) {
                LOGGER.warn("OreSense setting section {} in {} is {}, not an object; its settings use their defaults and "
                        + "the section is written back as an object", section, path, element);
            }
        }
    }

    @Nullable
    private static JsonElement setting(JsonObject root, Config.Value<?> value) {
        JsonElement section = root.get(value.section());
        if (section == null || !section.isJsonObject()) return null;
        return section.getAsJsonObject().get(value.key());
    }

    private static <T> void apply(Config.Value<T> value, JsonElement element) {
        T parsed = value.fromJson(element);
        if (parsed != null) {
            value.set(parsed);
            return;
        }
        LOGGER.warn("Invalid OreSense setting {}: {}; expected {}, using {}", value.key(), element, value.expected(),
                value.toJson(value.defaultValue()));
        value.reset();
    }

    private static <T> void put(JsonObject root, Config.Value<T> value) {
        JsonElement section = root.get(value.section());
        if (section == null || !section.isJsonObject()) {
            section = new JsonObject();
            root.add(value.section(), section);
        }
        section.getAsJsonObject().add(value.key(), value.toJson(value.get()));
    }

    /**
     * Writes {@code root} with the known settings in their order and with their comments. A known
     * setting the root lacks is written with its default. Unknown sections and keys follow the known
     * ones, unchanged. The text goes to a temporary file that then replaces the old one, so a crash
     * mid-write cannot leave half a file.
     */
    private static void write(Path path, JsonObject root, List<Config.Value<?>> values) throws IOException {
        Set<String> sections = new LinkedHashSet<>();
        for (Config.Value<?> value : values) sections.add(value.section());

        StringBuilder out = new StringBuilder("{\n");
        List<String> blocks = new ArrayList<>();
        for (String section : sections) {
            JsonElement existing = root.get(section);
            JsonObject old = existing != null && existing.isJsonObject() ? existing.getAsJsonObject() : new JsonObject();
            List<String> lines = new ArrayList<>();
            Set<String> known = new LinkedHashSet<>();
            for (Config.Value<?> value : values) {
                if (!value.section().equals(section)) continue;
                known.add(value.key());
                StringBuilder entry = new StringBuilder();
                for (String line : value.fileComment().split("\n")) entry.append("    // ").append(line).append('\n');
                JsonElement element = old.has(value.key()) ? old.get(value.key()) : defaultJson(value);
                entry.append("    ").append(GSON.toJson(value.key())).append(": ").append(GSON.toJson(element));
                lines.add(entry.toString());
            }
            for (Map.Entry<String, JsonElement> other : old.entrySet()) {
                if (known.contains(other.getKey())) continue;
                lines.add("    " + GSON.toJson(other.getKey()) + ": " + GSON.toJson(other.getValue()));
            }
            blocks.add("  " + GSON.toJson(section) + ": {\n" + String.join(",\n", lines) + "\n  }");
        }
        for (Map.Entry<String, JsonElement> other : root.entrySet()) {
            if (sections.contains(other.getKey())) continue;
            blocks.add("  " + GSON.toJson(other.getKey()) + ": " + GSON.toJson(other.getValue()));
        }
        out.append(String.join(",\n", blocks)).append("\n}\n");

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, out, StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> JsonElement defaultJson(Config.Value<T> value) {
        return value.toJson(value.defaultValue());
    }
}
