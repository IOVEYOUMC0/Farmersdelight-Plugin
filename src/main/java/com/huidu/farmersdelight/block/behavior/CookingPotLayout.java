package com.huidu.farmersdelight.block.behavior;

import java.util.Arrays;
import java.util.stream.IntStream;

public record CookingPotLayout(
        int[] inputSlots,
        int[] pendingOutputSlots,
        int[] outputSlots,
        int[] containerSlots
) {
    public static final CookingPotLayout DEFAULT = new CookingPotLayout(
            range(0, 6),
            new int[]{6},
            new int[]{8},
            new int[]{7}
    );

    public static CookingPotLayout custom(int inputSlots, int pendingOutputSlots, int outputSlots, int containerSlots) {
        int inputCount = Math.max(1, inputSlots);
        int pendingCount = Math.max(1, pendingOutputSlots);
        int outputCount = Math.max(1, outputSlots);
        int containerCount = Math.max(0, containerSlots);

        int index = 0;
        int[] inputs = range(index, inputCount);
        index += inputCount;
        int[] pending = range(index, pendingCount);
        index += pendingCount;
        int[] outputs = range(index, outputCount);
        index += outputCount;
        int[] containers = range(index, containerCount);
        return new CookingPotLayout(inputs, pending, outputs, containers);
    }

    public int size() {
        return inputSlots.length + pendingOutputSlots.length + outputSlots.length + containerSlots.length;
    }

    public boolean isInputSlot(int slot) {
        return contains(inputSlots, slot);
    }

    public boolean isPendingOutputSlot(int slot) {
        return contains(pendingOutputSlots, slot);
    }

    public boolean isOutputSlot(int slot) {
        return contains(outputSlots, slot);
    }

    public boolean isContainerSlot(int slot) {
        return contains(containerSlots, slot);
    }

    public int firstPendingOutputSlot() {
        return pendingOutputSlots.length == 0 ? -1 : pendingOutputSlots[0];
    }

    public int firstOutputSlot() {
        return outputSlots.length == 0 ? -1 : outputSlots[0];
    }

    public int firstContainerSlot() {
        return containerSlots.length == 0 ? -1 : containerSlots[0];
    }

    public boolean isDefault() {
        return Arrays.equals(inputSlots, DEFAULT.inputSlots)
                && Arrays.equals(pendingOutputSlots, DEFAULT.pendingOutputSlots)
                && Arrays.equals(outputSlots, DEFAULT.outputSlots)
                && Arrays.equals(containerSlots, DEFAULT.containerSlots);
    }

    private static int[] range(int start, int count) {
        return IntStream.range(start, start + Math.max(0, count)).toArray();
    }

    private static boolean contains(int[] slots, int slot) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }
}
