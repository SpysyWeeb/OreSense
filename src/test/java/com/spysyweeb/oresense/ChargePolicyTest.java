package com.spysyweeb.oresense;

import com.spysyweeb.oresense.ChargePolicy.BreakCharge;
import com.spysyweeb.oresense.ChargePolicy.LockAtBreak;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChargePolicyTest {
    private static final int MAX_CHARGES = 64;   // OreSensorItem.MAX_CHARGES

    @Test
    void perVeinCostsShardsPerVeinWhateverTheSize() {
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_VEIN, 1, 16, 1));
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_VEIN, 1, 16, 128));
        assertEquals(3, ChargePolicy.price(ChargeMode.PER_VEIN, 3, 16, 5));
        assertEquals(0, ChargePolicy.price(ChargeMode.PER_VEIN, 0, 16, 40), "0 makes the sensor free");
    }

    @Test
    void perSizeIsOneShardPerBlocksPerShardRoundedUpAtLeastOne() {
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 1));
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 16));
        assertEquals(2, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 17));
        assertEquals(8, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 128));
        assertEquals(16, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 256));
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 128, 128));
        assertEquals(256, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 1, 256));
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_SIZE, 1, 16, 0), "never below one shard");
        assertEquals(3, ChargePolicy.price(ChargeMode.PER_SIZE, 0, 16, 40), "shardsPerVein does not enter PER_SIZE");
    }

    @Test
    void perBlockPriceIsOne() {
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_BLOCK, 5, 16, 100));
        assertEquals(1, ChargePolicy.price(ChargeMode.PER_BLOCK, 0, 1, 1));
    }

    @Test
    void lockIsRefusedBelowItsPrice() {
        assertFalse(ChargePolicy.affordable(1, 0));
        assertTrue(ChargePolicy.affordable(1, 1));
        assertFalse(ChargePolicy.affordable(3, 2));
        assertTrue(ChargePolicy.affordable(3, 3));
        assertTrue(ChargePolicy.affordable(0, 0), "a free lock needs no shard");
    }

    @Test
    void priceAboveMaxChargesCanNeverBePaid() {
        int price = ChargePolicy.price(ChargeMode.PER_SIZE, 1, 1, 200);
        assertFalse(ChargePolicy.affordable(price, MAX_CHARGES));
    }

    @Test
    void checkKeepsOnlyLocksThatArePaidOrStillAffordable() {
        assertTrue(ChargePolicy.keepsLock(true, false, 5, 0), "a paid lock stays");
        assertTrue(ChargePolicy.keepsLock(false, true, 5, 0), "creative never pays");
        assertTrue(ChargePolicy.keepsLock(false, false, 2, 2));
        assertFalse(ChargePolicy.keepsLock(false, false, 2, 1), "shards taken out below the price");
        assertFalse(ChargePolicy.keepsLock(false, false, 1, 0), "1.0.3: no shard, no lock");
        assertTrue(ChargePolicy.keepsLock(false, false, 0, 0), "free mode");
    }

    @Test
    void priceIsAnnouncedOnlyWhenItDiffersFromOneShardPerVein() {
        assertFalse(ChargePolicy.showsPrice(ChargeMode.PER_VEIN, 1, false, false), "1.0.3's price: nothing new to say");
        assertFalse(ChargePolicy.showsPrice(ChargeMode.PER_VEIN, 0, false, false), "free: nothing to pay");
        assertTrue(ChargePolicy.showsPrice(ChargeMode.PER_VEIN, 2, false, false));
        assertTrue(ChargePolicy.showsPrice(ChargeMode.PER_SIZE, 1, false, false));
        assertTrue(ChargePolicy.showsPrice(ChargeMode.PER_BLOCK, 1, false, false));
        assertFalse(ChargePolicy.showsPrice(ChargeMode.PER_SIZE, 4, true, false), "paid: nothing to announce");
    }

    @Test
    void creativeSeesNoPriceAnywhere() {
        for (ChargeMode mode : ChargeMode.values()) {
            assertFalse(ChargePolicy.showsPrice(mode, 5, false, true), mode + ": creative pays nothing");
        }
    }

    @Test
    void onceModesPayTheirPriceOnceAndBecomePaid() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(
                new LockAtBreak(ChargeMode.PER_VEIN, false, 1, 10),
                new LockAtBreak(ChargeMode.PER_SIZE, false, 3, 10),
                new LockAtBreak(ChargeMode.PER_SIZE, true, 3, 10)), false);
        assertEquals(new BreakCharge(1, true, false), c[0]);
        assertEquals(new BreakCharge(3, true, false), c[1]);
        assertEquals(BreakCharge.NONE, c[2], "already paid");
    }

    @Test
    void onceModeThatCannotPayIsLetGo() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(new LockAtBreak(ChargeMode.PER_SIZE, false, 4, 3)), false);
        assertEquals(new BreakCharge(0, false, true), c[0]);
    }

    @Test
    void freeVeinIsPaidForNothing() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(new LockAtBreak(ChargeMode.PER_VEIN, false, 0, 0)), false);
        assertEquals(new BreakCharge(0, true, false), c[0]);
    }

    @Test
    void perBlockChargesEveryBlockAndNeverMarksPaid() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 5)), false);
        assertEquals(new BreakCharge(1, false, false), c[0]);
    }

    @Test
    void perBlockLockDropsWhenItsLastShardIsSpent() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 1)), false);
        assertEquals(new BreakCharge(1, false, true), c[0]);
        c = ChargePolicy.chargeForBreak(List.of(new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 0)), false);
        assertEquals(new BreakCharge(0, false, true), c[0]);
    }

    @Test
    void perBlockDedupeAcrossTwoCarriedSensors() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 5),
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 5)), false);
        assertEquals(new BreakCharge(1, false, false), c[0], "the first sensor found pays");
        assertEquals(BreakCharge.NONE, c[1], "the block is charged once");
    }

    @Test
    void perBlockDedupeSkipsASensorThatCannotPay() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 0),
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 5)), false);
        assertEquals(new BreakCharge(0, false, true), c[0], "cannot pay: let go");
        assertEquals(new BreakCharge(1, false, false), c[1], "the next one pays the block");
    }

    @Test
    void perBlockDedupeDoesNotTouchOnceModeLocks() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 5),
                new LockAtBreak(ChargeMode.PER_VEIN, false, 1, 5)), false);
        assertEquals(new BreakCharge(1, false, false), c[0]);
        assertEquals(new BreakCharge(1, true, false), c[1]);
    }

    @Test
    void creativePaysNothingInEveryMode() {
        BreakCharge[] c = ChargePolicy.chargeForBreak(List.of(
                new LockAtBreak(ChargeMode.PER_VEIN, false, 5, 0),
                new LockAtBreak(ChargeMode.PER_SIZE, false, 5, 0),
                new LockAtBreak(ChargeMode.PER_BLOCK, false, 1, 0)), true);
        assertEquals(new BreakCharge(0, true, false), c[0]);
        assertEquals(new BreakCharge(0, true, false), c[1]);
        assertEquals(BreakCharge.NONE, c[2]);
    }

    @Test
    void onlyPerBlockPaysMoreThanOnce() {
        assertTrue(ChargePolicy.paysOnce(ChargeMode.PER_VEIN));
        assertTrue(ChargePolicy.paysOnce(ChargeMode.PER_SIZE));
        assertFalse(ChargePolicy.paysOnce(ChargeMode.PER_BLOCK));
    }
}
