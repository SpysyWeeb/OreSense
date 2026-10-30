package com.spysyweeb.oresense;

/**
 * What a shard buys (config charging.chargeMode). Pure: no game classes, so it is unit-tested
 * and ports to every branch as it is.
 */
public enum ChargeMode {
    /** charging.shardsPerVein shards for a whole vein, paid when its first block is mined (1.0.3's rule). */
    PER_VEIN,
    /** One shard per charging.blocksPerShard blocks of the vein, rounded up, paid when its first block is mined. */
    PER_SIZE,
    /** One shard for every block of the vein that is mined; the lock is never paid off. */
    PER_BLOCK;

    /** The name a lock stores; anything else (or nothing, a 1.0.3 lock) reads as PER_VEIN. */
    public static ChargeMode fromName(String name) {
        for (ChargeMode mode : values()) {
            if (mode.name().equals(name)) return mode;
        }
        return PER_VEIN;
    }
}
