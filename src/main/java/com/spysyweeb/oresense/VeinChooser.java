package com.spysyweeb.oresense;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Picks the vein a scan locks onto. It sees the world only through {@link Grid}: whether a packed
 * position holds a matching block, and how to step from one position to a neighbour. In the game
 * the positions are BlockPos.asLong values (so the anchors the item stores compare as they are);
 * the unit tests use their own packing. Only the JDK (not even the game's fastutil, which the
 * test classpath does not carry), so it is unit-tested and ports unchanged.
 */
public final class VeinChooser {
    private VeinChooser() {}

    /** The blocks a vein choice can see. */
    public interface Grid {
        /** True when {@code pos} holds a block of the sample (and can be read without loading anything). */
        boolean matches(long pos);

        /** {@code pos} moved by (dx, dy, dz). */
        long offset(long pos, int dx, int dy, int dz);
    }

    /**
     * A chosen vein: the hit the flood started from, that hit's squared distance from the scan
     * centre, and every flooded block in breadth-first order from it (the start first).
     */
    public record Choice(long start, double startDistSq, long[] vein) {}

    /** {@code choice} is null when nothing was chosen; {@code fallback}: LARGEST was asked for, CLOSEST was used. */
    public record Result(Choice choice, boolean fallback) {}

    /**
     * The vein choice for one scan. {@code count} is every matching block in the box; LARGEST
     * falls back to CLOSEST when it is over {@code hitLimit} (OreScanner.LARGEST_HIT_LIMIT in the
     * game), and then {@code hits} only has to hold what CLOSEST needs (every hit when there are
     * anchors, else the nearest one).
     */
    public static Result choose(VeinPick pick, Grid grid, long[] hits, double[] distSq, int count, int hitLimit,
                                long[] anchors, int cap, int maxRejected) {
        if (pick == VeinPick.LARGEST && count <= hitLimit) {
            return new Result(largest(grid, hits, distSq, anchors, cap), false);
        }
        return new Result(closest(grid, hits, distSq, anchors, cap, maxRejected), pick == VeinPick.LARGEST);
    }

    /**
     * Every block reachable from {@code start} through the 26 neighbours (faces, edges and corners)
     * that matches, in breadth-first order, at most {@code cap} blocks. Empty when {@code start}
     * does not match. Neighbours go in a fixed order (dx, then dy, then dz, each -1..1), so the
     * same world gives the same list.
     */
    public static long[] flood(Grid grid, long start, int cap) {
        return flood(grid, start, cap, new HashSet<>(), null).blocks();
    }

    /**
     * CLOSEST, 1.0.3's rule unchanged: the nearest hit whose vein holds none of {@code anchors}.
     * A rejected hit is set aside with its whole (capped) vein and the next nearest is tried;
     * after {@code maxRejected} rejections the choice gives up. Ties go to the earlier hit in
     * {@code hits}, which the scanner fills in x, z, y order. Each candidate is flooded on its own.
     *
     * @return the choice, or null when there is none
     */
    public static Choice closest(Grid grid, long[] hits, double[] distSq, long[] anchors, int cap, int maxRejected) {
        Set<Long> anchorSet = setOf(anchors);
        Set<Long> excluded = new HashSet<>();
        for (int rejected = 0; rejected < maxRejected; rejected++) {
            int pick = -1;
            double pickSq = Double.MAX_VALUE;
            for (int i = 0; i < hits.length; i++) {
                if (distSq[i] < pickSq && !excluded.contains(hits[i])) {
                    pickSq = distSq[i];
                    pick = i;
                }
            }
            if (pick < 0) return null;
            long[] vein = flood(grid, hits[pick], cap);
            if (!containsAny(vein, anchorSet)) return new Choice(hits[pick], pickSq, vein);
            excluded.add(hits[pick]);
            for (long p : vein) excluded.add(p);
        }
        return null;
    }

    /**
     * LARGEST: floods veins from the hits in distance order (ties: the earlier hit) with one shared
     * set of looked-at positions, so each block is flooded at most once; a hit an earlier flood
     * took is skipped. A flood that runs into a block an earlier flood took is the rest of a vein
     * the cap cut short: it belongs to that vein, which is nearer and never smaller, so it is not a
     * candidate of its own, and a vein already paid for (one holding an anchor) cannot come back as
     * a "new" vein through its rest. Veins holding one of {@code anchors} are skipped. The vein with
     * the most blocks wins; on a tie the nearer one (flooded first) keeps it, so two veins at the
     * cap go to the nearer.
     *
     * @return the choice, or null when every vein is skipped or there are no hits
     */
    public static Choice largest(Grid grid, long[] hits, double[] distSq, long[] anchors, int cap) {
        Set<Long> anchorSet = setOf(anchors);
        Set<Long> visited = new HashSet<>();
        Set<Long> claimed = new HashSet<>();
        Choice best = null;
        for (int i : byDistance(distSq)) {
            if (visited.contains(hits[i])) continue;
            Flood flood = flood(grid, hits[i], cap, visited, claimed);
            long[] vein = flood.blocks();
            if (flood.touched() || vein.length == 0 || containsAny(vein, anchorSet)) continue;
            if (best == null || vein.length > best.vein().length) best = new Choice(hits[i], distSq[i], vein);
        }
        return best;
    }

    /** A flood's blocks, and whether it ran into a block that an earlier flood had taken. */
    private record Flood(long[] blocks, boolean touched) {}

    /**
     * The flood behind {@link #flood(Grid, long, int)}. Positions in {@code visited} are not
     * looked at again and every position looked at is added to it, so floods sharing it call
     * {@link Grid#matches} at most once per position. With {@code claimed} (LARGEST), every block
     * taken is added to it, and meeting a block taken by an earlier flood marks this one touched.
     */
    private static Flood flood(Grid grid, long start, int cap, Set<Long> visited, Set<Long> claimed) {
        if (cap <= 0 || !visited.add(start) || !grid.matches(start)) return new Flood(new long[0], false);
        Set<Long> mine = claimed == null ? null : new HashSet<>();
        boolean touched = false;
        long[] vein = new long[Math.min(cap, 16)];
        int size = 0;
        vein[size++] = start;
        if (claimed != null) {
            claimed.add(start);
            mine.add(start);
        }
        if (size >= cap) return new Flood(Arrays.copyOf(vein, size), false);
        // the vein doubles as the queue: entries before `head` have been expanded
        for (int head = 0; head < size; head++) {
            long p = vein[head];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        long n = grid.offset(p, dx, dy, dz);
                        if (!visited.add(n)) {
                            // looked at before: a block some flood took, a non-match, or one of ours
                            if (claimed != null && claimed.contains(n) && !mine.contains(n)) touched = true;
                            continue;
                        }
                        if (!grid.matches(n)) continue;
                        if (size == vein.length) vein = Arrays.copyOf(vein, Math.min(cap, size * 2));
                        vein[size++] = n;
                        if (claimed != null) {
                            claimed.add(n);
                            mine.add(n);
                        }
                        if (size >= cap) return new Flood(Arrays.copyOf(vein, size), touched);
                    }
                }
            }
        }
        return new Flood(Arrays.copyOf(vein, size), touched);
    }

    /** Hit indices sorted by distance; equal distances keep their order in the array. */
    static int[] byDistance(double[] distSq) {
        Integer[] order = new Integer[distSq.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(distSq[a], distSq[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        int[] out = new int[order.length];
        for (int i = 0; i < out.length; i++) out[i] = order[i];
        return out;
    }

    private static Set<Long> setOf(long[] values) {
        Set<Long> set = new HashSet<>();
        for (long v : values) set.add(v);
        return set;
    }

    private static boolean containsAny(long[] vein, Set<Long> anchors) {
        if (anchors.isEmpty()) return false;
        for (long p : vein) {
            if (anchors.contains(p)) return true;
        }
        return false;
    }
}
