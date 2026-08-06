package com.huidu.farmersdelight.effect;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectManagerLevelPersistenceTest {

    @Test
    void comfortLevelSurvivesPdcRoundTrip() {
        PersistentDataContainer pdc = persistentDataContainer();
        Player player = player(UUID.randomUUID(), pdc);
        try {
            EffectManager.applyComfort(player, 30, 3);
            assertEquals(3, EffectManager.comfortLevel(player));

            EffectManager.saveComfortToPdc(player);
            EffectManager.removeComfort(player);
            assertEquals(0, EffectManager.comfortLevel(player));

            assertTrue(EffectManager.restoreComfortFromPdc(player));
            assertEquals(3, EffectManager.comfortLevel(player));
        } finally {
            EffectManager.removeComfort(player);
        }
    }

    private static Player player(UUID id, PersistentDataContainer pdc) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getPersistentDataContainer" -> pdc;
                    case "getName", "toString" -> "test-player";
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PersistentDataContainer persistentDataContainer() {
        Map<NamespacedKey, Object> values = new HashMap<>();
        return (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put((NamespacedKey) args[0], args[2]);
                        yield null;
                    }
                    case "get" -> values.get(args[0]);
                    case "getOrDefault" -> values.getOrDefault(args[0], args[2]);
                    case "has" -> values.containsKey(args[0]);
                    case "remove" -> {
                        values.remove(args[0]);
                        yield null;
                    }
                    case "isEmpty" -> values.isEmpty();
                    case "getKeys" -> values.keySet();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
