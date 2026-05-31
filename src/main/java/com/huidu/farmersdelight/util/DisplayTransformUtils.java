package com.huidu.farmersdelight.util;

import org.bukkit.block.BlockFace;

public final class DisplayTransformUtils {

    private DisplayTransformUtils() {
    }

    public static float cuttingBoardYaw(BlockFace blockFacing) {
        return flatItemYaw(oppositeHorizontal(blockFacing));
    }

    public static float skilletYaw(BlockFace blockFacing) {
        return flatItemYaw(blockFacing);
    }

    public static float stoveYaw(BlockFace blockFacing) {
        return flatItemYaw(oppositeHorizontal(blockFacing));
    }

    public static double[] stoveSlotOffset(double slotX, double slotY, double slotZ, BlockFace blockFacing) {
        return rotatePlanarOffset(slotX, slotY, slotZ, stoveYaw(blockFacing));
    }

    public static float[] stoveSlotOffset(float slotX, float slotY, float slotZ, BlockFace blockFacing) {
        double[] offset = stoveSlotOffset((double) slotX, (double) slotY, (double) slotZ, blockFacing);
        return new float[]{(float) offset[0], (float) offset[1], (float) offset[2]};
    }

    private static float flatItemYaw(BlockFace direction) {
        return CustomBlockUtils.getYRotation(horizontal(direction));
    }

    private static BlockFace oppositeHorizontal(BlockFace facing) {
        return horizontal(facing).getOppositeFace();
    }

    private static BlockFace horizontal(BlockFace facing) {
        if (facing == null) {
            return BlockFace.NORTH;
        }
        return switch (facing) {
            case SOUTH, EAST, WEST -> facing;
            default -> BlockFace.NORTH;
        };
    }

    private static double[] rotatePlanarOffset(double slotX, double slotY, double slotZ, float yawDegrees) {
        int yaw = Math.round(normalizeDegrees(yawDegrees));
        return switch (yaw) {
            case 0 -> new double[]{slotX, slotY, slotZ};
            case 90 -> new double[]{slotZ, slotY, -slotX};
            case -90 -> new double[]{-slotZ, slotY, slotX};
            case 180, -180 -> new double[]{-slotX, slotY, -slotZ};
            default -> {
                double radians = Math.toRadians(yawDegrees);
                double sin = Math.sin(radians);
                double cos = Math.cos(radians);
                yield new double[]{
                        (cos * slotX) + (sin * slotZ),
                        slotY,
                        (-sin * slotX) + (cos * slotZ)
                };
            }
        };
    }

    private static float normalizeDegrees(float degrees) {
        float normalized = degrees % 360.0F;
        if (normalized > 180.0F) {
            normalized -= 360.0F;
        } else if (normalized < -180.0F) {
            normalized += 360.0F;
        }
        return normalized;
    }
}
