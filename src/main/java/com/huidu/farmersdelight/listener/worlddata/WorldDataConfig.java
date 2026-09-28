package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldDataConfig {

    private static volatile WorldDataConfig instance = createDefaults();

    private final boolean villagerTradesEnabled;
    private final List<TradeOffer> villagerTrades;
    private final boolean wanderingTraderTradesEnabled;
    private final int wanderingGenericTradeCount;
    private final List<TradeOffer> wanderingTrades;

    private WorldDataConfig(Builder builder) {
        this.villagerTradesEnabled = builder.villagerTradesEnabled;
        this.villagerTrades = List.copyOf(builder.villagerTrades);
        this.wanderingTraderTradesEnabled = builder.wanderingTraderTradesEnabled;
        this.wanderingGenericTradeCount = builder.wanderingGenericTradeCount;
        this.wanderingTrades = List.copyOf(builder.wanderingTrades);
    }

    public static WorldDataConfig get() {
        return instance;
    }

    public static void reload(JavaPlugin plugin, FileConfiguration configuration) {
        Builder builder = defaultBuilder();
        if (configuration != null) {
            ConfigurationSection trades = configuration.getConfigurationSection("trades");
            if (trades != null) {
                applyVillagerTrades(builder, childSection(trades, "villager"), plugin);
                applyWanderingTrades(builder, childSection(trades, "wandering-trader"), plugin);
            } else if (configuration.isSet("trades")) {
                I18n.logWarning("plugin.config_value_invalid", "file", "world-data.yml",
                        "path", "trades", "error", "expected a section");
            }
        }
        instance = new WorldDataConfig(builder);
    }

    private static ConfigurationSection childSection(ConfigurationSection parent, String key) {
        if (!parent.isSet(key)) {
            return null;
        }
        ConfigurationSection child = parent.getConfigurationSection(key);
        if (child == null) {
            I18n.logWarning("plugin.config_value_invalid", "file", "world-data.yml",
                    "path", parent.getCurrentPath() + "." + key, "error", "expected a section");
        }
        return child;
    }

    public boolean isVillagerTradesEnabled() {
        return villagerTradesEnabled;
    }

    public List<TradeOffer> villagerTradesFor(String professionPath, int level) {
        if (professionPath == null) {
            return List.of();
        }
        List<TradeOffer> matches = null;
        for (TradeOffer offer : villagerTrades) {
            if (offer.level() == level && professionPath.equals(offer.profession())) {
                if (matches == null) {
                    matches = new ArrayList<>(2);
                }
                matches.add(offer);
            }
        }
        return matches == null ? List.of() : matches;
    }

    public boolean isWanderingTraderTradesEnabled() {
        return wanderingTraderTradesEnabled;
    }

    /** Substitution stops once the merchant has already drawn this many listings. */
    public int wanderingGenericTradeCount() {
        return wanderingGenericTradeCount;
    }

    public List<TradeOffer> wanderingTrades() {
        return wanderingTrades;
    }

    public record TradeOffer(String profession, int level, String ingredient, int ingredientAmount,
                             String result, int resultAmount, int maxUses, int villagerXp,
                             float priceMultiplier, double chance) {
    }

    // defaults

    private static WorldDataConfig createDefaults() {
        return new WorldDataConfig(defaultBuilder());
    }

    private static Builder defaultBuilder() {
        Builder builder = new Builder();
        addDefaultTrades(builder);
        return builder;
    }

    private static void addDefaultTrades(Builder builder) {
        // With two added entries, novice and apprentice farmer pools contain 7 and 5 offers.
        // Each offer therefore has weight 1/7 and 1/5 respectively for a selection.
        builder.villagerTrade(new TradeOffer("farmer", 1, "farmersdelight:onion", 26,
                "minecraft:emerald", 1, 16, 2, 0.05f, 1.0D / 7.0D));
        builder.villagerTrade(new TradeOffer("farmer", 1, "farmersdelight:tomato", 26,
                "minecraft:emerald", 1, 16, 2, 0.05f, 1.0D / 7.0D));
        builder.villagerTrade(new TradeOffer("farmer", 2, "farmersdelight:cabbage", 16,
                "minecraft:emerald", 1, 16, 5, 0.05f, 1.0D / 5.0D));
        builder.villagerTrade(new TradeOffer("farmer", 2, "farmersdelight:rice", 20,
                "minecraft:emerald", 1, 16, 5, 0.05f, 1.0D / 5.0D));

        // The generic trader pool contains 64 base offers plus four additions, giving each weight 1/68.
        double wanderingChance = 1.0D / 68.0D;
        builder.wanderingTrade(new TradeOffer(null, 0, "minecraft:emerald", 1,
                "farmersdelight:cabbage_seeds", 1, 1, 12, 0.05f, wanderingChance));
        builder.wanderingTrade(new TradeOffer(null, 0, "minecraft:emerald", 1,
                "farmersdelight:tomato_seeds", 1, 1, 12, 0.05f, wanderingChance));
        builder.wanderingTrade(new TradeOffer(null, 0, "minecraft:emerald", 1,
                "farmersdelight:rice", 1, 1, 12, 0.05f, wanderingChance));
        builder.wanderingTrade(new TradeOffer(null, 0, "minecraft:emerald", 1,
                "farmersdelight:onion", 1, 1, 12, 0.05f, wanderingChance));
    }

    // config parsing

    private static void applyVillagerTrades(Builder builder, ConfigurationSection section, JavaPlugin plugin) {
        if (section == null) {
            return;
        }
        builder.villagerTradesEnabled = section.getBoolean("enabled", true);
        if (!section.contains("trades", true)) {
            return;
        }
        builder.villagerTrades.clear();
        for (Map<?, ?> raw : section.getMapList("trades")) {
            String profession = string(raw.get("profession"));
            if (profession == null) {
                I18n.logWarning("worlddata.villager_trades_no_profession");
                continue;
            }
            int level = number(raw.get("level"), 1).intValue();
            TradeOffer offer = parseOffer(raw, profession.toLowerCase(Locale.ROOT), level, plugin,
                    "trades.villager.trades");
            if (offer != null) {
                builder.villagerTrade(offer);
            }
        }
    }

    private static void applyWanderingTrades(Builder builder, ConfigurationSection section, JavaPlugin plugin) {
        if (section == null) {
            return;
        }
        builder.wanderingTraderTradesEnabled = section.getBoolean("enabled", true);
        builder.wanderingGenericTradeCount = Math.max(0,
                section.getInt("generic-trade-count", builder.wanderingGenericTradeCount));
        if (!section.contains("trades", true)) {
            return;
        }
        builder.wanderingTrades.clear();
        for (Map<?, ?> raw : section.getMapList("trades")) {
            TradeOffer offer = parseOffer(raw, null, 0, plugin, "trades.wandering-trader.trades");
            if (offer != null) {
                builder.wanderingTrade(offer);
            }
        }
    }

    private static TradeOffer parseOffer(Map<?, ?> raw, String profession, int level, JavaPlugin plugin,
                                         String path) {
        String ingredient = string(raw.get("ingredient"));
        String result = string(raw.get("result"));
        if (!ItemUtils.isValidItemId(ingredient) || !ItemUtils.isValidItemId(result)) {
            I18n.logWarning("worlddata.trades_invalid_entry", "path", path);
            return null;
        }
        ItemStack ingredientItem = ItemUtils.createItem(ingredient);
        ItemStack resultItem = ItemUtils.createItem(result);
        if (ItemUtils.isAnyCustomItemLoaded()
                && (ingredientItem == null || ingredientItem.getType().isAir()
                || resultItem == null || resultItem.getType().isAir())) {
            I18n.logWarning("plugin.item_not_found", "path", path,
                    "id", ingredientItem == null || ingredientItem.getType().isAir() ? ingredient : result);
            return null;
        }
        int ingredientAmount = Math.max(1, number(raw.get("ingredient-amount"), 1).intValue());
        int resultAmount = Math.max(1, number(raw.get("result-amount"), 1).intValue());
        int maxUses = Math.max(1, number(raw.get("max-uses"), 16).intValue());
        int villagerXp = Math.max(0, number(raw.get("villager-xp"), 1).intValue());
        float priceMultiplier = number(raw.get("price-multiplier"), 0.05D).floatValue();
        double chance = number(raw.get("chance"), 1.0D).doubleValue();
        if (chance <= 0.0D) {
            return null;
        }
        return new TradeOffer(profession, level, ingredient.toLowerCase(Locale.ROOT), ingredientAmount,
                result.toLowerCase(Locale.ROOT), resultAmount, maxUses, villagerXp, priceMultiplier,
                Math.min(1.0D, chance));
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Number number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number;
        }
        if (value != null) {
            try {
                return Double.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static final class Builder {
        private boolean villagerTradesEnabled = true;
        private final List<TradeOffer> villagerTrades = new ArrayList<>();
        private boolean wanderingTraderTradesEnabled = true;
        private int wanderingGenericTradeCount = 5;
        private final List<TradeOffer> wanderingTrades = new ArrayList<>();

        private void villagerTrade(TradeOffer offer) {
            villagerTrades.add(offer);
        }

        private void wanderingTrade(TradeOffer offer) {
            wanderingTrades.add(offer);
        }
    }
}
