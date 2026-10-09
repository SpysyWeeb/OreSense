package com.spysyweeb.oresense.scan;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Counts matching blocks around a point and remembers the closest one; floods veins. */
public final class OreScanner {
    private OreScanner() {}

    /**
     * The closest matching block in the box around {@code center}, skipping veins the sensor
     * has already been paid for. A candidate whose vein (flooded from it, at most
     * {@code veinCap} blocks) contains one of {@code ignoredAnchors} is set aside with its whole
     * vein and the next closest is tried; after {@code maxRejected} such veins the scan gives up
     * and reports nothing. {@code count} is every matching block in the box, ignored or not.
     * Ties go to the first block in x, z, y order, with or without anchors.
     */
    public static ScanResult scan(Level level, BlockPos center, Set<Block> targets, int hRange, int vRange,
                                  long[] ignoredAnchors, int veinCap, int maxRejected) {
        boolean ignoring = ignoredAnchors.length > 0;
        int count = 0;
        double bestSq = Double.MAX_VALUE;
        BlockPos best = null;
        // with anchors to honour, every hit is kept so the next closest can be found without rescanning
        LongArrayList hits = ignoring ? new LongArrayList() : null;
        DoubleArrayList hitsSq = ignoring ? new DoubleArrayList() : null;

        int minY = Math.max(level.getMinBuildHeight(), center.getY() - vRange);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + vRange);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = center.getX() - hRange; x <= center.getX() + hRange; x++) {
            for (int z = center.getZ() - hRange; z <= center.getZ() + hRange; z++) {
                // never load chunks just to scan them
                if (!level.hasChunkAt(cursor.set(x, minY, z))) continue;
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!targets.contains(state.getBlock())) continue;
                    count++;
                    double dSq = cursor.distSqr(
                            center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5, true);
                    if (ignoring) {
                        hits.add(cursor.asLong());
                        hitsSq.add(dSq);
                    } else if (dSq < bestSq) {
                        bestSq = dSq;
                        best = cursor.immutable();
                    }
                }
            }
        }
        if (!ignoring) {
            if (best == null) return ScanResult.EMPTY;
            return new ScanResult(count, Math.sqrt(bestSq), best);
        }

        LongSet anchors = new LongOpenHashSet(ignoredAnchors);
        LongSet excluded = new LongOpenHashSet();
        for (int rejected = 0; rejected < maxRejected; rejected++) {
            int pick = -1;
            double pickSq = Double.MAX_VALUE;
            for (int i = 0; i < hits.size(); i++) {
                double dSq = hitsSq.getDouble(i);
                if (dSq < pickSq && !excluded.contains(hits.getLong(i))) {
                    pickSq = dSq;
                    pick = i;
                }
            }
            if (pick < 0) return ScanResult.EMPTY;

            BlockPos hit = BlockPos.of(hits.getLong(pick));
            List<BlockPos> vein = vein(level, hit, targets, veinCap);
            if (!containsAny(vein, anchors)) return new ScanResult(count, Math.sqrt(pickSq), hit);
            excluded.add(hit.asLong());
            for (BlockPos p : vein) excluded.add(p.asLong());
        }
        return ScanResult.EMPTY;
    }

    /**
     * The vein around {@code start}: every block reachable from it through the 26 neighbours
     * (faces, edges and corners) whose block is one of {@code targets}, in breadth-first order
     * from {@code start}, at most {@code cap} blocks. Empty when {@code start} itself does not
     * match. The neighbours are visited in a fixed order, so the same world gives the same
     * list. Blocks in chunks that are not loaded are left out; this never loads a chunk.
     */
    public static List<BlockPos> vein(Level level, BlockPos start, Set<Block> targets, int cap) {
        List<BlockPos> vein = new ArrayList<>();
        if (cap <= 0 || !matches(level, start, targets)) return vein;

        BlockPos first = start.immutable();
        LongSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(first.asLong());
        queue.add(first);
        vein.add(first);
        if (vein.size() >= cap) return vein;
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (!seen.add(n.asLong()) || !matches(level, n, targets)) continue;
                        vein.add(n);
                        if (vein.size() >= cap) return vein;
                        queue.add(n);
                    }
                }
            }
        }
        return vein;
    }

    /**
     * True when the block at {@code pos} is loaded, inside the build height, and one of
     * {@code targets}. Never loads a chunk.
     */
    public static boolean matches(Level level, BlockPos pos, Set<Block> targets) {
        return level.isLoaded(pos) && targets.contains(level.getBlockState(pos).getBlock());
    }

    private static boolean containsAny(List<BlockPos> vein, LongSet anchors) {
        for (BlockPos p : vein) {
            if (anchors.contains(p.asLong())) return true;
        }
        return false;
    }
}
