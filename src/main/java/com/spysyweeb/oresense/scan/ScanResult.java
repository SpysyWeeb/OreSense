package com.spysyweeb.oresense.scan;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * One scan. {@code nearest} is where the needle points (the chosen vein's nearest block),
 * {@code veinBlocks} the chosen vein already flooded (breadth-first, at most the vein cap), so the
 * lock does not flood it again. {@code fallback}: LARGEST was asked for but the box held more
 * than OreScanner.LARGEST_HIT_LIMIT matching blocks, so this scan chose the closest vein.
 */
public record ScanResult(int count, double nearestDistance, BlockPos nearest, List<BlockPos> veinBlocks, boolean fallback) {
    public static final ScanResult EMPTY = new ScanResult(0, Double.MAX_VALUE, null, List.of(), false);
    public boolean found() { return count > 0 && nearest != null; }
}
