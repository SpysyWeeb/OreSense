package com.spysyweeb.oresense;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** config/oresense.json: a 1.0.3 file keeps its values and gains the 1.1.0 keys; Done's file reads back the same. */
class ConfigTest {
    private static final Config C = Config.INSTANCE;

    @TempDir
    Path dir;

    @BeforeEach
    void defaults() {
        C.values().forEach(Config.Value::reset);
    }

    private Path legacyCopy() throws IOException {
        Path path = dir.resolve("oresense.json");
        try (InputStream in = ConfigTest.class.getResourceAsStream("/oresense-1.0.3-tuned.json")) {
            Files.copy(in, path);
        }
        return path;
    }

    @Test
    void legacyFileKeepsItsValuesAndGainsTheNewKeys() throws IOException {
        Path path = legacyCopy();
        Config.load(path);

        assertEquals(48, C.horizontalRange.get());
        assertEquals(24, C.verticalRange.get());
        assertEquals(10, C.scanIntervalTicks.get());
        assertFalse(C.showDirection.get());
        assertTrue(C.showActionBar.get());
        assertFalse(C.oresOnly.get());
        assertEquals(ChargeMode.PER_VEIN, C.chargeMode.get());
        assertEquals(1, C.shardsPerVein.get());
        assertEquals(16, C.blocksPerShard.get());
        assertEquals(128, C.veinCap.get());
        assertEquals(200, C.lockGraceTicks.get());
        assertEquals(VeinPick.CLOSEST, C.targetPick.get());

        String after = Files.readString(path);
        for (String key : List.of("\"chargeMode\": \"PER_VEIN\"", "\"shardsPerVein\": 1", "\"blocksPerShard\": 16",
                "\"veinCap\": 128", "\"lockGraceTicks\": 200", "\"targetPick\": \"CLOSEST\"",
                "\"horizontalRange\": 48", "\"oresOnly\": false")) {
            assertTrue(after.contains(key), key);
        }
        assertTrue(after.contains("// PER_BLOCK: one shard for every block"), "chargeMode comment");
        assertTrue(after.contains("// PER_BLOCK loophole: a player can put the sensor away"), "loophole stated");

        // a complete file is not rewritten on the next load
        Config.load(path);
        assertEquals(after, Files.readString(path));
    }

    @Test
    void savedFileReadsBackIdentically() throws IOException {
        Path path = legacyCopy();
        Config.load(path);
        C.horizontalRange.set(96);
        C.verticalRange.set(4);
        C.scanIntervalTicks.set(200);
        C.showDirection.set(true);
        C.showActionBar.set(false);
        C.oresOnly.set(true);
        C.chargeMode.set(ChargeMode.PER_BLOCK);
        C.shardsPerVein.set(0);
        C.blocksPerShard.set(128);
        C.veinCap.set(256);
        C.lockGraceTicks.set(0);
        C.targetPick.set(VeinPick.LARGEST);
        List<Object> saved = snapshot();
        Config.save(path);
        String written = Files.readString(path);

        C.values().forEach(Config.Value::reset);
        Config.load(path);
        assertEquals(saved, snapshot());
        assertEquals(written, Files.readString(path), "loading a saved file leaves it as written");

        Config.save(path);
        assertEquals(written, Files.readString(path), "saving the same values writes the same file");
    }

    @Test
    void missingFileIsWrittenWithDefaults() throws IOException {
        Path path = dir.resolve("sub").resolve("oresense.json");
        C.horizontalRange.set(40);
        Config.load(path);
        assertEquals(32, C.horizontalRange.get());
        assertTrue(Files.exists(path));
        C.horizontalRange.set(40);
        Config.load(path);
        assertEquals(32, C.horizontalRange.get(), "the written file holds the defaults");
    }

    @Test
    void rejectedValueFallsBackButStaysInTheFileForCorrection() throws IOException {
        Path path = dir.resolve("oresense.json");
        Files.writeString(path, "{\"scanning\": {\"horizontalRange\": 500, \"oresOnly\": \"yes\", \"retired\": true},"
                + " \"charging\": {\"chargeMode\": \"per_size\", \"veinCap\": 8},"
                + " \"targeting\": {\"targetPick\": \"PLAYER\"}, \"extra\": {\"keep\": [1, 2]}}");
        Config.load(path);
        assertEquals(32, C.horizontalRange.get());
        assertTrue(C.oresOnly.get());
        assertEquals(ChargeMode.PER_SIZE, C.chargeMode.get(), "enum names are read case-insensitively");
        assertEquals(128, C.veinCap.get());
        assertEquals(VeinPick.CLOSEST, C.targetPick.get(), "a 1.1.0 play-test file's PLAYER reads as the default");
        String after = Files.readString(path);
        assertTrue(after.contains("\"horizontalRange\": 500"), after);
        assertTrue(after.contains("\"oresOnly\": \"yes\""), after);
        assertTrue(after.contains("\"veinCap\": 8"), after);
        assertTrue(after.contains("\"targetPick\": \"PLAYER\""), after);
        assertTrue(after.contains("\"extra\": {\"keep\":[1,2]}"), after);
        // a key of a known section that this version does not know (a retired setting, say) is
        // ignored on read and kept as written, like an unknown section
        assertTrue(after.contains("\"retired\": true"), after);
        assertTrue(after.contains("\"verticalRange\": 16"), "missing keys added with defaults");
    }

    @Test
    void reloadOfABrokenFileKeepsTheSettingsInUse() throws IOException {
        Path path = dir.resolve("oresense.json");
        Files.writeString(path, "{\"scanning\": {\"horizontalRange\": 40}}");
        Config.load(path);
        assertEquals(40, C.horizontalRange.get());
        Files.writeString(path, "{\"scanning\": {\"horizontalRange\": ");
        ConfigFile.load(path, C.values(), "test", false);
        assertEquals(40, C.horizontalRange.get(), "a re-read keeps the values");
        ConfigFile.load(path, C.values(), "test", true);
        assertEquals(32, C.horizontalRange.get(), "a first read falls back to the defaults");
        assertEquals("{\"scanning\": {\"horizontalRange\": ", Files.readString(path, StandardCharsets.UTF_8),
                "the broken file is kept for correction");
    }

    @Test
    void setRejectsValuesOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> C.horizontalRange.set(3));
        assertThrows(IllegalArgumentException.class, () -> C.veinCap.set(257));
        C.blocksPerShard.set(1);
        assertEquals(1, C.blocksPerShard.get());
        assertEquals(List.of(ChargeMode.PER_VEIN, ChargeMode.PER_SIZE, ChargeMode.PER_BLOCK), C.chargeMode.choices());
    }

    private static List<Object> snapshot() {
        List<Object> values = new ArrayList<>();
        for (Config.Value<?> value : C.values()) values.add(value.get());
        return values;
    }
}
