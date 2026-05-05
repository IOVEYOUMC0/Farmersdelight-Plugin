package com.huidu.farmersdelight.util;

import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InteractionDebouncer {

    private static final long DEFAULT_COOLDOWN_MILLIS = 150L;
    private static final ConcurrentHashMap<Key, Long> RECENT_INTERACTIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger interactionCount = new AtomicInteger(0);
    private static final int CLEANUP_INTERVAL = 200;

    private InteractionDebouncer() {
    }

    public static boolean tryAcquire(UUID playerId, Location location) {
        return tryAcquire(playerId, location, DEFAULT_COOLDOWN_MILLIS);
    }

    public static boolean tryAcquire(UUID playerId, Location location, long cooldownMillis) {
        if (playerId == null || location == null || location.getWorld() == null) {
            return true;
        }

        long now = System.currentTimeMillis();
        Key key = new Key(
                playerId,
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );

        boolean[] acquired = {false};
        RECENT_INTERACTIONS.compute(key, (k, oldValue) -> {
            if (oldValue == null || now - oldValue >= cooldownMillis) {
                acquired[0] = true;
                return now;
            }
            acquired[0] = false;
            return oldValue;
        });

        if (acquired[0] && interactionCount.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            RECENT_INTERACTIONS.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownMillis);
        }
        return acquired[0];
    }

    private record Key(UUID playerId, UUID worldId, int x, int y, int z) {
        private Key {
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(worldId);
        }
    }
}
