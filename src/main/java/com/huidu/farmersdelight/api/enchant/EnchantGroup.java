package com.huidu.farmersdelight.api.enchant;

/**
 * The FarmersDelight item groups a custom enchantment can be offered on. Mirrors the plugin's internal enchant
 * filter groups so addons never touch config internals: KNIVES covers every registered knife, SKILLET the
 * skillet. An enchant registered for a group enters that group's enchanting-table and anvil candidate pool.
 */
public enum EnchantGroup {
    KNIVES,
    SKILLET
}
