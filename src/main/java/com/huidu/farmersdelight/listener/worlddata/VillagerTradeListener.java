package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.TradeOffer;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class VillagerTradeListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        WorldDataConfig config = WorldDataConfig.get();
        AbstractVillager merchant = event.getEntity();
        // Addon-registered offers (via the api) are unioned in ALWAYS; only the config-driven offers are gated
        // on the world-data.trades enable flags, so disabling the built-in trades does not silently kill
        // addon trades too.
        List<TradeOffer> candidates = new ArrayList<>();

        if (merchant instanceof Villager villager) {
            String profession = professionPath(villager);
            int level = villager.getVillagerLevel();
            if (config.isVillagerTradesEnabled()) {
                candidates.addAll(config.villagerTradesFor(profession, level));
            }
            candidates.addAll(ExternalVillagerTrades.villagerTradesFor(profession, level));
        } else if (merchant instanceof WanderingTrader) {
            // Buying slots (item -> emerald) are left untouched so their direction is not flipped. The draw
            // count guard is version-dependent: older versions draw a separate rare listing after the count,
            // newer versions fill buying/uncommon/common pools from datapack trade sets.
            if (isBuyingOffer(event.getRecipe())) {
                return;
            }
            if (merchant.getRecipeCount() >= config.wanderingGenericTradeCount()) {
                return;
            }
            if (config.isWanderingTraderTradesEnabled()) {
                candidates.addAll(config.wanderingTrades());
            }
            candidates.addAll(ExternalVillagerTrades.wanderingTrades());
        } else {
            return;
        }

        if (candidates.isEmpty()) {
            return;
        }

        // One roll against the summed chance, then a weighted pick, so the substitution probability matches
        // the share the mod's listings hold in that pool rather than compounding per candidate.
        double total = 0.0D;
        for (TradeOffer offer : candidates) {
            if (!alreadyOffered(merchant, offer)) {
                total += offer.chance();
            }
        }
        if (total <= 0.0D) {
            return;
        }
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll >= total) {
            return;
        }
        double cursor = 0.0D;
        for (TradeOffer offer : candidates) {
            if (alreadyOffered(merchant, offer)) {
                continue;
            }
            cursor += offer.chance();
            if (roll < cursor) {
                MerchantRecipe recipe = buildRecipe(offer);
                if (recipe != null) {
                    event.setRecipe(recipe);
                }
                return;
            }
        }
    }

    private static String professionPath(Villager villager) {
        Villager.Profession profession = villager.getProfession();
        if (profession == null) {
            return null;
        }
        // Villager.Profession moved from an enum to a registry-backed type; going through Keyed keeps the id
        // lookup working on both shapes.
        return ((Keyed) profession).getKey().getKey().toLowerCase(Locale.ROOT);
    }

    private static boolean isBuyingOffer(MerchantRecipe recipe) {
        // Vanilla buying listings always pay out emeralds, so a result of emerald marks an item -> emerald
        // slot. Substituting it with a selling listing would flip the direction of that slot.
        ItemStack result = recipe.getResult();
        return result != null && result.getType() == Material.EMERALD;
    }

    private static boolean alreadyOffered(AbstractVillager merchant, TradeOffer offer) {
        for (MerchantRecipe recipe : merchant.getRecipes()) {
            List<ItemStack> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) {
                continue;
            }
            if (!offer.ingredient().equals(ItemUtils.resolveItemId(ingredients.getFirst()))) {
                continue;
            }
            if (offer.result().equals(ItemUtils.resolveItemId(recipe.getResult()))) {
                return true;
            }
        }
        return false;
    }

    private static MerchantRecipe buildRecipe(TradeOffer offer) {
        ItemStack result = ItemUtils.createItem(offer.result());
        ItemStack ingredient = ItemUtils.createItem(offer.ingredient());
        if (result == null || ingredient == null) {
            return null;
        }
        result.setAmount(offer.resultAmount());
        ingredient.setAmount(offer.ingredientAmount());
        MerchantRecipe recipe = new MerchantRecipe(result, 0, offer.maxUses(), true, offer.villagerXp(),
                offer.priceMultiplier());
        recipe.addIngredient(ingredient);
        return recipe;
    }
}
