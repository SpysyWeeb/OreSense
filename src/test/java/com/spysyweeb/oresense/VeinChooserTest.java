package com.spysyweeb.oresense;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hand-built level: a set of matching positions, scanned like OreScanner does (x, z, y order, box around the centre). */
class VeinChooserTest {
    private static final int CAP = 128;
    /** OreScanner.LARGEST_HIT_LIMIT; the game class cannot be loaded here, so the value is passed in. */
    private static final int LIMIT = 4096;

    /** 11 bits per axis, offset 1024: plenty for a test level, and stepping is plain arithmetic. */
    static long pack(int x, int y, int z) {
        return ((long) (x + 1024) << 22) | ((long) (y + 1024) << 11) | (z + 1024);
    }

    static int x(long p) { return (int) (p >>> 22) - 1024; }
    static int y(long p) { return (int) ((p >>> 11) & 2047) - 1024; }
    static int z(long p) { return (int) (p & 2047) - 1024; }

    static final class Level implements VeinChooser.Grid {
        final Set<Long> ore = new HashSet<>();
        final Map<Long, Integer> looks = new HashMap<>();

        Level add(int x, int y, int z) { ore.add(pack(x, y, z)); return this; }

        /** A solid w x h x d box of ore with its low corner at (x, y, z). */
        Level box(int x, int y, int z, int w, int h, int d) {
            for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) for (int k = 0; k < d; k++) add(x + i, y + j, z + k);
            return this;
        }

        @Override public boolean matches(long pos) {
            looks.merge(pos, 1, Integer::sum);
            return ore.contains(pos);
        }

        @Override public long offset(long pos, int dx, int dy, int dz) {
            return pack(x(pos) + dx, y(pos) + dy, z(pos) + dz);
        }

        /** The scan's hits and squared distances around the origin, in OreScanner's x, z, y order. */
        Hits scan(int range) {
            List<Long> hits = new ArrayList<>();
            List<Double> dist = new ArrayList<>();
            for (int x = -range; x <= range; x++)
                for (int z = -range; z <= range; z++)
                    for (int y = -range; y <= range; y++) {
                        long p = pack(x, y, z);
                        if (!ore.contains(p)) continue;
                        hits.add(p);
                        // OreScanner: block centre to the centre of the player's feet block
                        dist.add((double) x * x + (double) y * y + (double) z * z);
                    }
            Hits h = new Hits();
            h.hits = hits.stream().mapToLong(Long::longValue).toArray();
            h.distSq = dist.stream().mapToDouble(Double::doubleValue).toArray();
            return h;
        }
    }

    static final class Hits { long[] hits; double[] distSq; }

    // ---- flood ----

    @Test
    void floodIsBreadthFirstInAFixedNeighbourOrder() {
        Level level = new Level().add(0, 0, 0).add(1, 0, 0).add(2, 0, 0).add(1, 1, 0);
        long[] vein = VeinChooser.flood(level, pack(0, 0, 0), CAP);
        assertArrayEquals(new long[]{pack(0, 0, 0), pack(1, 0, 0), pack(1, 1, 0), pack(2, 0, 0)}, vein);
    }

    @Test
    void floodFollowsEdgesAndCorners() {
        Level level = new Level().add(0, 0, 0).add(1, 1, 1).add(2, 2, 2);
        assertEquals(3, VeinChooser.flood(level, pack(0, 0, 0), CAP).length);
    }

    @Test
    void floodStopsAtTheCap() {
        Level level = new Level().box(0, 0, 0, 10, 1, 1);
        assertEquals(4, VeinChooser.flood(level, pack(0, 0, 0), 4).length);
    }

    @Test
    void floodOfANonMatchingStartIsEmpty() {
        assertEquals(0, VeinChooser.flood(new Level(), pack(0, 0, 0), CAP).length);
    }

    // ---- closest (1.0.3's rule) ----

    @Test
    void closestTakesTheNearestVeinWhateverItsSize() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.closest(level, h.hits, h.distSq, new long[0], CAP, 8);
        assertNotNull(c);
        assertEquals(pack(3, 0, 0), c.start());
        assertEquals(2, c.vein().length);
    }

    @Test
    void closestSkipsAnIgnoredVeinAndGivesUpAfterMaxRejected() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.closest(level, h.hits, h.distSq, new long[]{pack(4, 0, 0)}, CAP, 8);
        assertNotNull(c);
        assertEquals(pack(-10, 0, 0), c.start(), "the small vein is ignored, the next closest is the big one");
        assertNull(VeinChooser.closest(level, h.hits, h.distSq, new long[]{pack(4, 0, 0)}, CAP, 1),
                "one rejection allowed, and it is used up");
    }

    @Test
    void closestTieGoesToTheEarlierHit() {
        Level level = new Level().add(-3, 0, 0).add(3, 0, 0);
        Hits h = level.scan(8);
        VeinChooser.Choice c = VeinChooser.closest(level, h.hits, h.distSq, new long[0], CAP, 8);
        assertNotNull(c);
        assertEquals(pack(-3, 0, 0), c.start(), "x, z, y scan order: x = -3 comes first");
    }

    // ---- largest ----

    /** A 2-block vein 3 blocks east, a 27-block vein 10 blocks west. */
    static Level twoVeins() {
        return new Level().add(3, 0, 0).add(4, 0, 0).box(-12, -1, -1, 3, 3, 3);
    }

    @Test
    void largestTakesTheBiggerVeinEvenWhenItIsFurther() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.largest(level, h.hits, h.distSq, new long[0], CAP);
        assertNotNull(c);
        assertEquals(27, c.vein().length);
        assertEquals(pack(-10, 0, 0), c.start(), "the flood starts at the vein's nearest hit");
    }

    @Test
    void largestTieGoesToTheNearerVein() {
        Level level = new Level().box(5, 0, 0, 2, 1, 1).box(-9, 0, 0, 2, 1, 1);
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.largest(level, h.hits, h.distSq, new long[0], CAP);
        assertNotNull(c);
        assertEquals(pack(5, 0, 0), c.start());
    }

    @Test
    void twoVeinsAtTheCapTieSoTheNearerWins() {
        Level level = new Level().box(4, 0, 0, 3, 3, 3).box(-14, 0, 0, 4, 4, 4);
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.largest(level, h.hits, h.distSq, new long[0], 20);
        assertNotNull(c);
        assertEquals(20, c.vein().length);
        assertEquals(4, x(c.start()), "27 and 64 blocks both stop at the cap of 20");
    }

    @Test
    void largestSkipsIgnoredVeins() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Choice c = VeinChooser.largest(level, h.hits, h.distSq, new long[]{pack(-11, 0, 0)}, CAP);
        assertNotNull(c);
        assertEquals(2, c.vein().length, "the big vein is ignored, the small one is left");
        assertNull(VeinChooser.largest(level, h.hits, h.distSq, new long[]{pack(-11, 0, 0), pack(3, 0, 0)}, CAP));
    }

    @Test
    void theRestOfACappedPaidVeinCannotComeBackAsANewVein() {
        // one 12-block row cut by a cap of 4 into floods x 1..4, 5..8, 9..12; the anchor is in the
        // first, so the whole row is a vein already paid for, and its rest must not be locked (and
        // charged) again as a "new" 4-block vein: the 2-block vein far away is what is left
        Level level = new Level().box(1, 0, 0, 12, 1, 1).box(20, 5, 20, 2, 1, 1);
        Hits h = level.scan(32);
        VeinChooser.Choice c = VeinChooser.largest(level, h.hits, h.distSq, new long[]{pack(2, 0, 0)}, 4);
        assertNotNull(c);
        assertEquals(pack(20, 5, 20), c.start());
        assertEquals(2, c.vein().length);
    }

    @Test
    void largestFloodsEachBlockAtMostOnce() {
        Level level = twoVeins().box(0, 5, 0, 5, 2, 5);
        Hits h = level.scan(16);
        level.looks.clear();
        VeinChooser.largest(level, h.hits, h.distSq, new long[0], CAP);
        int worst = level.looks.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertEquals(1, worst, "every position is looked at once across all the floods");
    }

    @Test
    void noHitsNoChoice() {
        assertNull(VeinChooser.largest(new Level(), new long[0], new double[0], new long[0], CAP));
        assertNull(VeinChooser.closest(new Level(), new long[0], new double[0], new long[0], CAP, 8));
    }

    // ---- choose: the hit limit ----

    @Test
    void largestAtTheHitLimitStillComparesVeins() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Result r = VeinChooser.choose(VeinPick.LARGEST, level, h.hits, h.distSq,
                LIMIT, LIMIT, new long[0], CAP, 8);
        assertFalse(r.fallback());
        assertEquals(27, r.choice().vein().length);
    }

    @Test
    void largestOverTheHitLimitFallsBackToClosest() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        VeinChooser.Result r = VeinChooser.choose(VeinPick.LARGEST, level, h.hits, h.distSq,
                LIMIT + 1, LIMIT, new long[0], CAP, 8);
        assertTrue(r.fallback());
        assertEquals(pack(3, 0, 0), r.choice().start());
    }

    @Test
    void largestOverTheHitLimitWithOnlyTheNearestHitKept() {
        // OreScanner stops keeping every hit past the limit when there are no anchors
        Level level = twoVeins();
        VeinChooser.Result r = VeinChooser.choose(VeinPick.LARGEST, level, new long[]{pack(3, 0, 0)}, new double[]{9},
                5000, LIMIT, new long[0], CAP, 8);
        assertTrue(r.fallback());
        assertEquals(2, r.choice().vein().length);
    }

    @Test
    void closestNeverReportsAFallback() {
        Level level = twoVeins();
        Hits h = level.scan(16);
        assertFalse(VeinChooser.choose(VeinPick.CLOSEST, level, h.hits, h.distSq, 99999, LIMIT, new long[0], CAP, 8).fallback());
    }

    @Test
    void distanceOrderKeepsScanOrderOnTies() {
        assertArrayEquals(new int[]{1, 3, 0, 2}, VeinChooser.byDistance(new double[]{4, 1, 4, 1}));
    }
}
