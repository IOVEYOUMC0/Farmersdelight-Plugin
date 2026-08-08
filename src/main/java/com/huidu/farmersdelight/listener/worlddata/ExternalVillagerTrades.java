package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.TradeOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villager / wandering-trader offers registered at runtime by addons through the api
 * (FarmersDelightVillagerTrades), kept separate from the config-driven WorldDataConfig. WorldDataConfig is an
 * immutable snapshot rebuilt from defaults + config.yml on every reload, so pouring addon trades into it would
 * wipe them on the next /fd reload. This registry is a plain static store that survives reloads; VillagerTradeListener
 * unions its offers with the config candidates before its weighted substitution roll.
 *
 * A profession() of null marks a wandering-trader offer; a non-null profession marks a villager offer gated on
 * profession + level.
 */
public final class ExternalVillagerTrades {

    private static final Map<String, TradeOffer> BY_ID = new ConcurrentHashMap<>();

    private ExternalVillagerTrades() {
    }

    public static boolean register(String id, TradeOffer offer) {
        if (id == null || id.isBlank() || offer == null) {
            return false;
        }
        BY_ID.put(id, offer);
        return true;
    }

    public static boolean unregister(String id) {
        return id != null && BY_ID.remove(id) != null;
    }

    public static boolean isRegistered(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    public static Set<String> ids() {
        return Set.copyOf(BY_ID.keySet());
    }

    public static boolean isEmpty() {
        return BY_ID.isEmpty();
    }

    /** Registered villager offers for a profession + level (profession-gated entries only). */
    public static List<TradeOffer> villagerTradesFor(String professionPath, int level) {
        if (professionPath == null || BY_ID.isEmpty()) {
            return List.of();
        }
        List<TradeOffer> matches = null;
        for (TradeOffer offer : BY_ID.values()) {
            if (offer.profession() != null && offer.level() == level
                    && professionPath.equals(offer.profession())) {
                if (matches == null) {
                    matches = new ArrayList<>(2);
                }
                matches.add(offer);
            }
        }
        return matches == null ? List.of() : matches;
    }

    /** Registered wandering-trader offers (entries with a null profession). */
    public static List<TradeOffer> wanderingTrades() {
        if (BY_ID.isEmpty()) {
            return List.of();
        }
        List<TradeOffer> matches = null;
        for (TradeOffer offer : BY_ID.values()) {
            if (offer.profession() == null) {
                if (matches == null) {
                    matches = new ArrayList<>(2);
                }
                matches.add(offer);
            }
        }
        return matches == null ? List.of() : matches;
    }
}
