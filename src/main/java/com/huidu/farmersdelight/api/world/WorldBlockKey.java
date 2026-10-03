package com.huidu.farmersdelight.api.world;

import org.bukkit.World;

import java.util.UUID;

/**
 * The world;x;y;z string the FD-family furniture managers key their registries on. It lives here so
 * the format is written once: it used to be spelled out in each manager and copied verbatim into the addon
 * template, where changing one copy would silently split a registry in two.
 *
 *
 * The value is only ever an in-memory map key, never persisted, so the format is not a stored contract.
 */
public final class WorldBlockKey {

    private WorldBlockKey() {
    }

    /** The key for a block in a loaded world. */
    public static String of(World world, int x, int y, int z) {
        return of(world.getUID(), x, y, z);
    }

    /** The key from a world id, for callers that already hold the id instead of the world. */
    public static String of(UUID worldId, int x, int y, int z) {
        return worldId + ";" + x + ";" + y + ";" + z;
    }
}
