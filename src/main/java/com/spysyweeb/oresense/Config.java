package com.spysyweeb.oresense;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Common/server settings, read once at startup from config/oresense.json. */
public final class Config {
    public static final long TARGET_LIFETIME = 300;
    public static final Config INSTANCE = new Config();
    private static final Logger LOGGER = LogManager.getLogger();

    public final Value<Integer> horizontalRange = new Value<>(32);
    public final Value<Integer> verticalRange = new Value<>(16);
    public final Value<Integer> scanIntervalTicks = new Value<>(20);
    public final Value<Boolean> showDirection = new Value<>(true);
    public final Value<Boolean> oresOnly = new Value<>(true);
    public final Value<Boolean> showActionBar = new Value<>(false);

    private Config() {}

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("oresense.json");
        if (!Files.exists(path)) {
            JsonObject scanning = new JsonObject();
            scanning.addProperty("horizontalRange", 32);
            scanning.addProperty("verticalRange", 16);
            scanning.addProperty("scanIntervalTicks", 20);
            scanning.addProperty("showDirection", true);
            scanning.addProperty("showActionBar", false);
            scanning.addProperty("oresOnly", true);
            JsonObject root = new JsonObject();
            root.add("scanning", scanning);
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Cannot write OreSense configuration {}; using defaults", path, e);
            }
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject scanning = root.has("scanning") ? root.getAsJsonObject("scanning") : new JsonObject();
            INSTANCE.horizontalRange.value = integer(scanning, "horizontalRange", 32, 4, 96);
            INSTANCE.verticalRange.value = integer(scanning, "verticalRange", 16, 4, 64);
            INSTANCE.scanIntervalTicks.value = integer(scanning, "scanIntervalTicks", 20, 5, 200);
            INSTANCE.showDirection.value = bool(scanning, "showDirection", true);
            INSTANCE.showActionBar.value = bool(scanning, "showActionBar", false);
            INSTANCE.oresOnly.value = bool(scanning, "oresOnly", true);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot read OreSense configuration {}; using defaults. The file has been kept for correction.", path, e);
        }
    }

    private static int integer(JsonObject object, String name, int fallback, int min, int max) {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int number = value.getAsBigDecimal().intValueExact();
                if (number >= min && number <= max) return number;
            }
        } catch (ArithmeticException | NumberFormatException ignored) {}
        LOGGER.warn("Invalid OreSense setting {}: {}; expected an integer from {} to {}, using {}", name, value, min, max, fallback);
        return fallback;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        LOGGER.warn("Invalid OreSense setting {}: {}; expected true or false, using {}", name, value, fallback);
        return fallback;
    }

    public static final class Value<T> {
        private volatile T value;
        private Value(T value) { this.value = value; }
        public T get() { return value; }
    }
}
