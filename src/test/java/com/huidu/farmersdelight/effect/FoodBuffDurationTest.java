package com.huidu.farmersdelight.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This plugin's own packs write effect durations in seconds, the PapersDelight packs write them in ticks. A
 * bowl of beef stew asking for 3600 means three minutes there and one hour here, so the conversion is part of
 * the compatibility surface rather than an implementation detail.
 */
class FoodBuffDurationTest {

    @Test
    void fullSecondsConvertExactly() {
        assertEquals(1, FoodBuffFunction.ticksToSeconds(20));
        assertEquals(60, FoodBuffFunction.ticksToSeconds(1200));
        assertEquals(180, FoodBuffFunction.ticksToSeconds(3600));
    }

    @Test
    void partialSecondsRoundUpSoTheEffectStillApplies() {
        assertEquals(1, FoodBuffFunction.ticksToSeconds(1));
        assertEquals(1, FoodBuffFunction.ticksToSeconds(19));
        assertEquals(2, FoodBuffFunction.ticksToSeconds(21));
    }

    @Test
    void aZeroOrNegativeDurationStillGrantsOneSecond() {
        assertEquals(1, FoodBuffFunction.ticksToSeconds(0));
        assertEquals(1, FoodBuffFunction.ticksToSeconds(-100));
    }
}
