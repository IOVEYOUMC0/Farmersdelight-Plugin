package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class SkilletEffectManagerTest {
    @Test
    void disabledOrZeroChanceEffectsNeverQueryPlayersOrChunks() throws Exception {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected world query: " + method.getName()); });
        var effects = new SkilletEffectManager(null);
        var location = new Location(world, 0, 64, 0);
        set(effects, "smokeEnabled", false);
        set(effects, "sizzleEnabled", false);
        effects.dispatchTickEffects(world, location, null);
        set(effects, "smokeEnabled", true);
        set(effects, "sizzleEnabled", true);
        set(effects, "smokeChance", 0.0D);
        set(effects, "sizzleChance", 0.0D);
        effects.dispatchTickEffects(world, location, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unloadingRemovesOnlyTheRelevantEffectBudgets() throws Exception {
        var effects = new SkilletEffectManager(null);
        var field = SkilletEffectManager.class.getDeclaredField("chunkEffectBudget");
        field.setAccessible(true);
        var budgets = (Map<UUID, Map<Long, Object>>) field.get(effects);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        budgets.put(first, new ConcurrentHashMap<>(Map.of(1L, new Object(), 2L, new Object())));
        budgets.put(second, new ConcurrentHashMap<>(Map.of(1L, new Object())));
        effects.cleanupChunk(first, 1L);
        assertEquals(1, budgets.get(first).size());
        assertTrue(budgets.get(first).containsKey(2L));
        assertTrue(budgets.get(second).containsKey(1L));
        effects.cleanupWorld(first);
        assertFalse(budgets.containsKey(first));
        assertTrue(budgets.containsKey(second));
        effects.cleanup();
        assertTrue(budgets.isEmpty());
    }

    private static void set(SkilletEffectManager effects, String name, Object value) throws Exception {
        var field = SkilletEffectManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(effects, value);
    }
}
