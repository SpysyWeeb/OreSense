package com.spysyweeb.oresense.scan;

import com.spysyweeb.oresense.VeinChooser;
import com.spysyweeb.oresense.VeinPick;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Counts matching blocks around a point and picks the vein to lock; floods veins. */
public final class OreScanner {
    private OreScanner() {}

    /**
     * A LARGEST scan floods every matching block in its box; past this many (oresOnly off and a
     * common block as the sample) it falls back to CLOSEST for that check instead.
     */
    public static final int LARGEST_HIT_LIMIT = 4096;

    /**
     * The vein to lock in the box around {@code center}, skipping veins the sensor has already been
     * paid for (a vein, flooded at most {@code veinCap} blocks, that holds one of
     * {@code ignoredAnchors}). CLOSEST is 1.0.3's rule: the closest matching block's vein; ties go
     * to the first block in x, z, y order; after {@code maxRejected} ignored veins it gives up.
     * LARGEST floods every vein in the box and takes the biggest, the nearer on a tie, unless the
     * box holds more than {@link #LARGEST_HIT_LIMIT} matching blocks, when this scan
     * falls back to CLOSEST. {@code count} is every matching block in the box, ignored or not.
     */
    public static ScanResult scan(Level level, BlockPos center, Set<Block> targets, int hRange, int vRange,
                                  long[] ignoredAnchors, int veinCap, int maxRejected, VeinPick pick) {
        boolean ignoring = ignoredAnchors.length > 0;
        boolean largest = pick == VeinPick.LARGEST;
        int count = 0;
        double bestSq = Double.MAX_VALUE;
        long best = 0;
        // CLOSEST with anchors to honour needs every hit, to try the next closest without rescanning;
        // LARGEST needs every hit up to the limit, past which it falls back and needs only the nearest
        LongArrayList hits = ignoring || largest ? new LongArrayList() : null;
        DoubleArrayList hitsSq = hits != null ? new DoubleArrayList() : null;

        int minY = Math.max(level.getMinY(), center.getY() - vRange);
        int maxY = Math.min(level.getMaxY(), center.getY() + vRange);
        double cx = center.getX() + 0.5, cy = center.getY() + 0.5, cz = center.getZ() + 0.5;
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
                    double dSq = cursor.distToCenterSqr(cx, cy, cz);
                    if (dSq < bestSq) {
                        bestSq = dSq;
                        best = cursor.asLong();
                    }
                    if (hits != null && (ignoring || hits.size() <= LARGEST_HIT_LIMIT)) {
                        hits.add(cursor.asLong());
                        hitsSq.add(dSq);
                    }
                }
            }
        }
        if (count == 0) return ScanResult.EMPTY;

        long[] hitArray;
        double[] sqArray;
        if (hits == null || (!ignoring && count > LARGEST_HIT_LIMIT)) {
            // plain CLOSEST, or a LARGEST that falls back with no anchors: the nearest block is all it needs
            hitArray = new long[]{best};
            sqArray = new double[]{bestSq};
        } else {
            hitArray = hits.toLongArray();
            sqArray = hitsSq.toDoubleArray();
        }
        VeinChooser.Result result = VeinChooser.choose(pick, grid(level, targets), hitArray, sqArray, count,
                LARGEST_HIT_LIMIT, ignoredAnchors, veinCap, maxRejected);
        VeinChooser.Choice choice = result.choice();
        if (choice == null || choice.vein().length == 0) {
            return new ScanResult(count, Double.MAX_VALUE, null, List.of(), result.fallback());
        }
        List<BlockPos> vein = positions(choice.vein());
        if (!largest || result.fallback()) {
            // CLOSEST: the needle points at the hit itself, as in 1.0.3
            return new ScanResult(count, Math.sqrt(choice.startDistSq()), BlockPos.of(choice.start()), vein, result.fallback());
        }
        // LARGEST: the needle points at the nearest block of the chosen vein
        BlockPos nearest = vein.get(0);
        double nearestSq = Double.MAX_VALUE;
        for (BlockPos p : vein) {
            double dSq = p.distToCenterSqr(cx, cy, cz);
            if (dSq < nearestSq) {
                nearestSq = dSq;
                nearest = p;
            }
        }
        return new ScanResult(count, Math.sqrt(nearestSq), nearest, vein, false);
    }

    /**
     * The vein around {@code start}: every block reachable from it through the 26 neighbours
     * (faces, edges and corners) whose block is one of {@code targets}, in breadth-first order
     * from {@code start}, at most {@code cap} blocks. Empty when {@code start} itself does not
     * match. The neighbours are visited in a fixed order, so the same world gives the same
     * list. Blocks in chunks that are not loaded are left out; this never loads a chunk.
     */
    public static List<BlockPos> vein(Level level, BlockPos start, Set<Block> targets, int cap) {
        return positions(VeinChooser.flood(grid(level, targets), start.asLong(), cap));
    }

    /**
     * True when the block at {@code pos} is loaded, inside the build height, and one of
     * {@code targets}. Never loads a chunk.
     */
    public static boolean matches(Level level, BlockPos pos, Set<Block> targets) {
        return level.isLoaded(pos) && targets.contains(level.getBlockState(pos).getBlock());
    }

    /** The level as VeinChooser sees it: BlockPos.asLong positions, matching = {@link #matches}. */
    private static VeinChooser.Grid grid(Level level, Set<Block> targets) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        return new VeinChooser.Grid() {
            @Override
            public boolean matches(long pos) {
                return OreScanner.matches(level, cursor.set(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos)), targets);
            }

            @Override
            public long offset(long pos, int dx, int dy, int dz) {
                return BlockPos.offset(pos, dx, dy, dz);
            }
        };
    }

    private static List<BlockPos> positions(long[] packed) {
        List<BlockPos> out = new ArrayList<>(packed.length);
        for (long p : packed) out.add(BlockPos.of(p));
        return out;
    }
}
