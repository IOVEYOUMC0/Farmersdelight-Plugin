package com.huidu.farmersdelight.block.behavior;

/**
 * The crop growth model Minecraft's CropBlock uses, kept as plain arithmetic so the
 * behaviour can run it without touching the world more than once per neighbour and so the
 * numbers stay verifiable by a unit test.
 *
 *
 * Growth speed starts at 1, adds the 3x3 block area below the crop (the centre counts fully,
 * the eight surrounding positions at a quarter each; a moist farmland contributes 3, any other
 * crop-supporting block 1) and is halved when the crop stands in a row or diagonal of the same
 * crop. The per-random-tick chance is 1 / (floor(25 / speed) + 1).
 */
public final class VanillaCropGrowth {

    /** Soil factors for the 3x3 area below the crop, indexed (dx + 1) * 3 + (dz + 1). */
    public static final int SOIL_FACTOR_COUNT = 9;

    private VanillaCropGrowth() {
    }

    public static float growthSpeed(float[] soilFactors, boolean sameCropHorizontal,
                                    boolean sameCropVertical, boolean sameCropDiagonal) {
        float speed = 1.0F;
        for (int index = 0; index < SOIL_FACTOR_COUNT; index++) {
            float factor = soilFactors.length > index ? soilFactors[index] : 0.0F;
            if (index != 4) {
                factor /= 4.0F;
            }
            speed += factor;
        }
        if (sameCropHorizontal && sameCropVertical) {
            speed /= 2.0F;
        } else if (sameCropDiagonal) {
            speed /= 2.0F;
        }
        return speed;
    }

    public static float chance(float growthSpeed) {
        if (growthSpeed <= 0.0F) {
            return 0.0F;
        }
        return 1.0F / (float) (Math.floor(25.0D / growthSpeed) + 1.0D);
    }

    public static boolean passes(float growthSpeed, float roll) {
        return roll < chance(growthSpeed);
    }
}
