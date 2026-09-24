package com.spysyweeb.oresense.scan;

import net.minecraft.core.BlockPos;

/** Turns a raw distance into the vague, human-readable wording the sensor reports. */
public final class Signal {
    private Signal() {}

    /**
     * The player fills two blocks: the feet block (dy 0) and the head block (dy 1). Ore at either
     * height is on your level. The roof (dy 2) and anything higher is above; the block you stand
     * on (dy -1) and anything lower is below. dy is target Y minus the feet block Y.
     */
    public static final int LEVEL_FEET = 0;
    public static final int LEVEL_HEAD = 1;

    /** +1 above, -1 below, 0 on your level. */
    public static int verticalSign(int dy) {
        if (dy > LEVEL_HEAD) return 1;
        if (dy < LEVEL_FEET) return -1;
        return 0;
    }

    public static String strength(double distance, int range) {
        double frac = distance / Math.max(1, range);
        if (frac <= 0.15) return "oresense.strength.very_strong";
        if (frac <= 0.35) return "oresense.strength.strong";
        if (frac <= 0.60) return "oresense.strength.faint";
        return "oresense.strength.trace";
    }

    public static String compass(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < 2 && Math.abs(dz) < 2) return "oresense.dir.here";
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360;
        String[] keys = {"east","south_east","south","south_west","west","north_west","north","north_east"};
        int idx = (int) Math.round(angle / 45.0) % 8;
        return "oresense.dir." + keys[idx];
    }

    public static String vertical(BlockPos from, BlockPos to) {
        int sign = verticalSign(to.getY() - from.getY());
        if (sign > 0) return "oresense.vert.above";
        if (sign < 0) return "oresense.vert.below";
        return "oresense.vert.level";
    }
}
