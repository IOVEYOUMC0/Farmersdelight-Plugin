package com.huidu.farmersdelight.api.trade;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.listener.worlddata.ExternalVillagerTrades;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.Set;

/**
 * Register custom villager and wandering-trader trades from an addon. FarmersDelight already gates and applies
 * trades through its VillagerAcquireTradeEvent listener (profession + level, one weighted substitution roll,
 * de-duplication, Folia-correct application, and persistence in the villager's entity data); this facade lets
 * an addon feed offers into that same machinery without touching FarmersDelight internals or the event itself.
 *
 * Trades are keyed by an addon-owned id (namespaced, e.g. "barbequesdelight:butcher_chilli_powder") used for
 * de-dupe and unregister. Ingredient and result are item ids — a plain vanilla id ("minecraft:emerald") or a
 * CraftEngine custom item id ("barbequesdelight:chilli_powder"); both work as inputs and outputs. Registrations
 * survive a /fd reload. A trade substitutes one offer slot in the target pool rather than adding a slot on top,
 * matching FarmersDelight's own config-driven trades.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightVillagerTrades {

    private FarmersDelightVillagerTrades() {
    }

    /**
     * Register a profession villager trade. profession is a profession id path ("butcher", "farmer", ...);
     * level is the villager level pool 1 (Novice) .. 5 (Master); chance is this trade's substitution share of
     * that pool (the pool is rolled once, so shares add up rather than compounding). Returns false if
     * FarmersDelight is unavailable or the arguments are invalid.
     */
    public static boolean registerVillagerTrade(String id, String profession, int level,
                                                String ingredient, int ingredientAmount,
                                                String result, int resultAmount,
                                                int maxUses, int villagerXp, float priceMultiplier, double chance) {
        if (!available() || profession == null || profession.isBlank()) {
            return false;
        }
        return ExternalVillagerTrades.register(id, new WorldDataConfig.TradeOffer(
                profession.trim().toLowerCase(Locale.ROOT), level,
                ingredient, ingredientAmount, result, resultAmount,
                maxUses, villagerXp, priceMultiplier, chance));
    }

    /**
     * Register a wandering-trader trade (no profession or level). chance is this trade's substitution share of
     * the wandering trader's generic offer pool.
     */
    public static boolean registerWanderingTrade(String id, String ingredient, int ingredientAmount,
                                                 String result, int resultAmount,
                                                 int maxUses, int villagerXp, float priceMultiplier, double chance) {
        if (!available()) {
            return false;
        }
        return ExternalVillagerTrades.register(id, new WorldDataConfig.TradeOffer(
                null, 0, ingredient, ingredientAmount, result, resultAmount,
                maxUses, villagerXp, priceMultiplier, chance));
    }

    public static boolean unregister(String id) {
        return available() && ExternalVillagerTrades.unregister(id);
    }

    public static boolean isRegistered(String id) {
        return available() && ExternalVillagerTrades.isRegistered(id);
    }

    public static Set<String> registeredIds() {
        return available() ? ExternalVillagerTrades.ids() : Set.of();
    }

    private static boolean available() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && FarmersDelightPlugin.isEnabled0();
    }
}
