package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig.TradeOffer;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Keyed;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adds the mod's crop trades to farmer villagers and its seed trades to the wandering trader.
 *
 * The mod appends its listings to the static VillagerTrades pools, so its trades take part in the same draw
 * as the vanilla ones: a villager picks two listings for the level it just reached, a wandering trader picks
 * five from the generic pool. Those pools are plain NMS statics with no Bukkit equivalent, so the draw itself
 * cannot be joined. What Bukkit does expose is the result of each draw, through VillagerAcquireTradeEvent,
 * which fires once per acquired trade in AbstractVillager.addOffersFromItemListings and allows the recipe to
 * be replaced. Substituting a configured offer for a drawn one at the probability the mod's listing would
 * have been picked reproduces both the offers and the number of trades a merchant ends up with; what is not
 * reproducible is any interaction with the pool itself, so a server that changes the vanilla pools with a
 * datapack should retune the chance values.
 */
public final class VillagerTradeListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        WorldDataConfig config = WorldDataConfig.get();
        AbstractVillager merchant = event.getEntity();
        List<TradeOffer> candidates;

        if (merchant instanceof Villager villager) {
            if (!config.isVillagerTradesEnabled()) {
                return;
            }
            String profession = professionPath(villager);
            candidates = config.villagerTradesFor(profession, villager.getVillagerLevel());
        } else if (merchant instanceof WanderingTrader) {
            if (!config.isWanderingTraderTradesEnabled()) {
                return;
            }
            // A wandering trader draws its generic offers first and its last offer from a separate rare pool
            // the mod does not touch, so only the draws before that count are eligible for substitution.
            if (merchant.getRecipeCount() >= config.wanderingGenericTradeCount()) {
                return;
            }
            candidates = config.wanderingTrades();
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

    /**
     * True when this merchant already carries an offer trading the same pair. Vanilla never draws the same
     * listing twice within one batch, and the offers acquired earlier in the batch are already on the
     * merchant by the time this event fires, so the same check covers both.
     */
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
