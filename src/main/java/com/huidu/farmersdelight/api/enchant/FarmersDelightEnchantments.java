package com.huidu.farmersdelight.api.enchant;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Addon entry point for registering custom enchantments into FarmersDelight's knife/skillet enchant system.
 * FarmersDelight owns the two mechanical layers — the datapack registry entry and the enchanting-table/anvil
 * candidate pool — while the addon owns the runtime effect through its own Bukkit listener (read the enchant
 * level off the held item.
 *
 * Call in the addon's onEnable (it hard-depends on FarmersDelight, so FarmersDelight is already up). Because an
 * enchantment is a datapack registry object, a register() enchant only becomes usable after a server restart —
 * FarmersDelight prints a restart banner when it writes the datapack.
 *
 * Register ids under the farmersdelight: namespace to stay clear of the conflict detector; other namespaces are
 * still fine because API-registered ids are whitelisted there.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightEnchantments {

    private static final Map<String, EnchantmentDefinition> MANAGED = new ConcurrentHashMap<>();
    private static final Map<String, Set<EnchantGroup>> POOL_ONLY = new ConcurrentHashMap<>();

    private FarmersDelightEnchantments() {
    }

    /**
     * Full registration: FarmersDelight writes the datapack enchantment JSON (registry presence) AND offers it
     * in the given groups' table/anvil pool. Takes effect after a server restart (registry object); the pool
     * side applies on the next reload. Returns false if FarmersDelight is unavailable or the definition is null.
     */
    public static boolean register(EnchantmentDefinition definition) {
        if (definition == null || !available()) {
            return false;
        }
        MANAGED.put(definition.id(), definition);
        POOL_ONLY.remove(definition.id());
        refresh();
        return true;
    }

    /**
     * Lighter registration for an enchantment ALREADY in the registry (a vanilla enchant, or one the addon
     * ships in its own datapack): FarmersDelight only adds it to the given groups' candidate pool and writes no
     * datapack. Returns false if FarmersDelight is unavailable or the id is not a namespaced id.
     */
    public static boolean addToPool(String enchantmentId, EnchantGroup... groups) {
        String id = normalize(enchantmentId);
        if (id == null || !available()) {
            return false;
        }
        POOL_ONLY.put(id, groups == null || groups.length == 0 ? Set.of(EnchantGroup.KNIVES) : Set.of(groups));
        MANAGED.remove(id);
        refresh();
        return true;
    }

    /** Removes a previously registered enchant from the managed set and pool. A restart fully clears a managed
     *  enchant's datapack entry. */
    public static boolean unregister(String enchantmentId) {
        String id = normalize(enchantmentId);
        if (id == null) {
            return false;
        }
        boolean removed = (MANAGED.remove(id) != null) | (POOL_ONLY.remove(id) != null);
        if (removed && available()) {
            refresh();
        }
        return removed;
    }

    public static boolean isRegistered(String enchantmentId) {
        String id = normalize(enchantmentId);
        return id != null && (MANAGED.containsKey(id) || POOL_ONLY.containsKey(id));
    }

    public static Set<String> registeredIds() {
        Set<String> ids = new HashSet<>(MANAGED.keySet());
        ids.addAll(POOL_ONLY.keySet());
        return Set.copyOf(ids);
    }

    // ── Internal accessors used by the plugin's datapack installer and enchant filter (public only because
    //    they live in a different package; addons should not call them). ──

    @ApiStatus.Internal
    public static Collection<EnchantmentDefinition> managedDefinitions() {
        return List.copyOf(MANAGED.values());
    }

    /** id -> groups for every registered enchant (managed + pool-only), for the filter to inject each into the
     *  right group pools. */
    @ApiStatus.Internal
    public static Map<String, Set<EnchantGroup>> poolTargets() {
        Map<String, Set<EnchantGroup>> targets = new HashMap<>();
        for (EnchantmentDefinition def : MANAGED.values()) {
            targets.put(def.id(), def.groups());
        }
        targets.putAll(POOL_ONLY);
        return Map.copyOf(targets);
    }

    private static String normalize(String id) {
        if (id == null || id.indexOf(':') <= 0) {
            return null;
        }
        return id.toLowerCase(Locale.ROOT);
    }

    private static boolean available() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }

    private static void refresh() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && FarmersDelightPlugin.isEnabled0()) {
            plugin.refreshEnchantSystem();
        }
    }
}
