package com.spysyweeb.oresense;

/**
 * The charging rules of brief section 2.4 as plain arithmetic, so they are unit-tested without the
 * game. OreSensorItem decides when to ask (lock time, a block broken, a check); this decides what
 * a lock costs and whether the sensor can pay it. Creative players never pay; callers handle that.
 */
public final class ChargePolicy {
    private ChargePolicy() {}

    /**
     * What a lock costs, fixed when the lock is made. PER_VEIN: {@code shardsPerVein} (0 makes the
     * sensor free). PER_SIZE: one shard per {@code blocksPerShard} blocks of the vein, rounded up,
     * at least one. PER_BLOCK: one, charged again for every block mined.
     */
    public static int price(ChargeMode mode, int shardsPerVein, int blocksPerShard, int veinBlocks) {
        return switch (mode) {
            case PER_VEIN -> Math.max(0, shardsPerVein);
            case PER_SIZE -> Math.max(1, ceilDiv(Math.max(0, veinBlocks), Math.max(1, blocksPerShard)));
            case PER_BLOCK -> 1;
        };
    }

    /**
     * True when a sensor holding {@code charges} shards can pay {@code price}. A price above
     * OreSensorItem.MAX_CHARGES can never be met, so such a lock is refused rather than left
     * waiting for shards that cannot fit.
     */
    public static boolean affordable(int price, int charges) {
        return charges >= price;
    }

    /** PER_BLOCK locks pay for every block and are never marked paid; the others pay once. */
    public static boolean paysOnce(ChargeMode mode) {
        return mode != ChargeMode.PER_BLOCK;
    }

    /**
     * Whether a check keeps a lock: a paid lock always stays, an unpaid one only while the sensor
     * still holds its price (1.0.3's "no shard, no lock", generalised to the stored price).
     */
    public static boolean keepsLock(boolean paid, boolean creative, int price, int charges) {
        return paid || creative || affordable(price, charges);
    }

    /**
     * Whether a lock's price is announced (the line above the hotbar when it locks, and the hover
     * tooltip while it is held): only when it differs from 1.0.3's one shard per vein, so PER_VEIN
     * above one shard, PER_SIZE always, PER_BLOCK always (as "1 shard per block"). Free locks
     * (price 0), paid locks and creative players, who pay nothing, have nothing to announce.
     */
    public static boolean showsPrice(ChargeMode mode, int price, boolean paid, boolean creative) {
        if (paid || creative) return false;
        return switch (mode) {
            case PER_VEIN -> price > 1;
            case PER_SIZE, PER_BLOCK -> true;
        };
    }

    /** One carried sensor whose lock holds the block just broken, as the break finds it. */
    public record LockAtBreak(ChargeMode mode, boolean paid, int price, int charges) {}

    /**
     * What one sensor does about the break: spend {@code spend} shards, mark its lock paid, and/or
     * let the lock go (a lock that cannot pay, or a PER_BLOCK lock whose last shard this was).
     */
    public record BreakCharge(int spend, boolean markPaid, boolean drop) {
        public static final BreakCharge NONE = new BreakCharge(0, false, false);
    }

    /**
     * The charges for one broken block, for the carried sensors whose lock holds it, in inventory
     * order. A paid PER_VEIN or PER_SIZE lock owes nothing; an unpaid one pays its price and
     * becomes paid, or is let go when it cannot. PER_BLOCK locks pay one shard for the block, but
     * the block is charged once: the first PER_BLOCK sensor that can pay does, the rest owe
     * nothing for it. A PER_BLOCK sensor that cannot pay is let go (and the block stays
     * uncharged for the next one); one that spends its last shard is let go after paying.
     * Creative players pay nothing in every mode.
     */
    public static BreakCharge[] chargeForBreak(java.util.List<LockAtBreak> locks, boolean creative) {
        BreakCharge[] out = new BreakCharge[locks.size()];
        boolean blockCharged = false;
        for (int i = 0; i < out.length; i++) {
            LockAtBreak lock = locks.get(i);
            if (paysOnce(lock.mode())) {
                if (lock.paid()) out[i] = BreakCharge.NONE;
                else if (creative) out[i] = new BreakCharge(0, true, false);
                else if (affordable(lock.price(), lock.charges())) out[i] = new BreakCharge(lock.price(), true, false);
                else out[i] = new BreakCharge(0, false, true);
            } else if (blockCharged) {
                out[i] = BreakCharge.NONE;
            } else if (creative) {
                out[i] = BreakCharge.NONE;
                blockCharged = true;
            } else if (affordable(1, lock.charges())) {
                out[i] = new BreakCharge(1, false, lock.charges() == 1);
                blockCharged = true;
            } else {
                out[i] = new BreakCharge(0, false, true);
            }
        }
        return out;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
