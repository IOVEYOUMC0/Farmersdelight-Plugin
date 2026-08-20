package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.UUID;

// Per-stove in-memory state shared by the tick / interaction / save paths. A StoveData is both the
// data holder and the per-block monitor: concurrent interactions and removes synchronize on it so
// slot claims and clears can never race across Folia region threads.
public final class StoveData {

    public static final int SLOT_COUNT = 6;

    final Location location;
    final ItemStack[] items = new ItemStack[SLOT_COUNT];
    final int[] cookingTime = new int[SLOT_COUNT];
    final int[] maxTime = new int[SLOT_COUNT];
    final int[] displayEntities = new int[SLOT_COUNT];
    final UUID[] ownerIds = new UUID[SLOT_COUNT];
    final String[] ownerNames = new String[SLOT_COUNT];
    // Blocked-above flag with a tick-stamp TTL, replacing the old Location-keyed cache map: the
    // steady-state per-tick cost is two volatile reads instead of a CHM lookup + lambda. MIN_VALUE
    // marks "never checked / event-invalidated" and must be compared explicitly — a plain
    // subtraction against it overflows.
    volatile long blockedAboveCheckedTick = Long.MIN_VALUE;
    volatile boolean blockedAbove;

    StoveData(Location location, int defaultCookTime) {
        this.location = location;
        Arrays.fill(maxTime, defaultCookTime);
        Arrays.fill(displayEntities, -1);
    }
}