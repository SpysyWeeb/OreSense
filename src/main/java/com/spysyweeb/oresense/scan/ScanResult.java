package com.spysyweeb.oresense.scan;

import net.minecraft.core.BlockPos;

public record ScanResult(int count, double nearestDistance, BlockPos nearest) {
    public static final ScanResult EMPTY = new ScanResult(0, Double.MAX_VALUE, null);
    public boolean found() { return count > 0 && nearest != null; }
}
