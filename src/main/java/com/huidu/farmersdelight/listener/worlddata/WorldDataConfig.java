package com.huidu.farmersdelight.listener.worlddata;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorldDataConfig {

    private static volatile WorldDataConfig instance = createDefaults();

    private final boolean compostingEnabled;
    private final Map<String, Float> compostChances;
    private final boolean fuelEnabled;
    private final Map<String, Integer> fuelTimes;
    private final boolean villagerTradesEnabled;
    private final List<TradeOffer> villagerTrades;
    private final boolean wanderingTraderTradesEnabled;
    private final int wanderingGenericTradeCount;
    private final List<TradeOffer> wanderingTrades;

    private WorldDataConfig(Builder builder) {
        this.compostingEnabled = builder.compostingEnabled;
        this.compostChances = Map.copyOf(builder.compostChances);
        this.fuelEnabled = builder.fuelEnabled;
        this.fuelTimes = Map.copyOf(builder.fuelTimes);
        this.villagerTradesEnabled = builder.villagerTradesEnabled;
        this.villagerTrades = List.copyOf(builder.villagerTrades);
        this.wanderingTraderTradesEnabled = builder.wanderingTraderTradesEnabled;
        this.wanderingGenericTradeCount = builder.wanderingGenericTradeCount;
        this.wanderingTrades = List.copyOf(builder.wanderingTrades);
    }

    public static WorldDataConfig get() {
        return instance;
    }

    public static void reload(JavaPlugin plugin) {
        Builder builder = defaultBuilder();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("world-data");
        if (root != null) {
            applyComposting(builder, root.getConfigurationSection("composting"), plugin);
            applyFuel(builder, root.getConfigurationSection("furnace-fuel"), plugin);
            ConfigurationSection trades = root.getConfigurationSection("trades");
            if (trades != null) {
                applyVillagerTrades(builder, trades.getConfigurationSection("villager"), plugin);
                applyWanderingTrades(builder, trades.getConfigurationSection("wandering-trader"), plugin);
            }
        }
        instance = new WorldDataConfig(builder);
        // The burn times only reach a furnace's fuel slot once they are also pushed into CraftEngine's item
        // definitions; do it here so an operator editing the list and running a reload gets the new values
        // without waiting for a CraftEngine reload. No-op while CraftEngine items are still loading, which
        // the warmup listener then covers.
        FurnaceFuelListener.applyFuelTimesToCraftEngine();
    }

    public boolean isCompostingEnabled() {
        return compostingEnabled;
    }

    public Float compostChance(String itemId) {
        return itemId == null ? null : compostChances.get(itemId.toLowerCase(Locale.ROOT));
    }

    public boolean hasCompostables() {
        return !compostChances.isEmpty();
    }

    public boolean isFuelEnabled() {
        return fuelEnabled;
    }

    public Integer fuelTime(String itemId) {
        return itemId == null ? null : fuelTimes.get(itemId.toLowerCase(Locale.ROOT));
    }

    public Map<String, Integer> fuelTimes() {
        return fuelTimes;
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
        addDefaultCompostables(builder);
        addDefaultFuels(builder);
        addDefaultTrades(builder);
        return builder;
    }

    private static void addDefaultCompostables(Builder builder) {
        builder.compost("farmersdelight:tree_bark", 0.3f);
        builder.compost("farmersdelight:straw", 0.3f);
        builder.compost("farmersdelight:cabbage_seeds", 0.3f);
        builder.compost("farmersdelight:tomato_seeds", 0.3f);
        builder.compost("farmersdelight:rice", 0.3f);
        builder.compost("farmersdelight:rice_panicle", 0.3f);
        builder.compost("farmersdelight:sandy_shrub", 0.3f);
        builder.compost("farmersdelight:pumpkin_slice", 0.5f);
        builder.compost("farmersdelight:cabbage_leaf", 0.5f);
        builder.compost("farmersdelight:kelp_roll_slice", 0.5f);
        builder.compost("farmersdelight:cabbage", 0.65f);
        builder.compost("farmersdelight:onion", 0.65f);
        builder.compost("farmersdelight:tomato", 0.65f);
        builder.compost("farmersdelight:wild_cabbages", 0.65f);
        builder.compost("farmersdelight:wild_onions", 0.65f);
        builder.compost("farmersdelight:wild_tomatoes", 0.65f);
        builder.compost("farmersdelight:wild_carrots", 0.65f);
        builder.compost("farmersdelight:wild_potatoes", 0.65f);
        builder.compost("farmersdelight:wild_beetroots", 0.65f);
        builder.compost("farmersdelight:wild_rice", 0.65f);
        builder.compost("farmersdelight:pie_crust", 0.65f);
        builder.compost("farmersdelight:rice_bale", 0.85f);
        builder.compost("farmersdelight:sweet_berry_cookie", 0.85f);
        builder.compost("farmersdelight:honey_cookie", 0.85f);
        builder.compost("farmersdelight:cake_slice", 0.85f);
        builder.compost("farmersdelight:apple_pie_slice", 0.85f);
        builder.compost("farmersdelight:sweet_berry_cheesecake_slice", 0.85f);
        builder.compost("farmersdelight:chocolate_pie_slice", 0.85f);
        builder.compost("farmersdelight:raw_pasta", 0.85f);
        builder.compost("farmersdelight:rotten_tomato", 0.85f);
        builder.compost("farmersdelight:kelp_roll", 0.85f);
        builder.compost("farmersdelight:apple_pie", 1.0f);
        builder.compost("farmersdelight:sweet_berry_cheesecake", 1.0f);
        builder.compost("farmersdelight:chocolate_pie", 1.0f);
        builder.compost("farmersdelight:dumplings", 1.0f);
        builder.compost("farmersdelight:stuffed_pumpkin_block", 1.0f);
        builder.compost("farmersdelight:brown_mushroom_colony", 1.0f);
        builder.compost("farmersdelight:red_mushroom_colony", 1.0f);
    }

    private static void addDefaultFuels(Builder builder) {
        builder.fuel("farmersdelight:half_tatami_mat", 100);
        builder.fuel("farmersdelight:straw", 100);
        builder.fuel("farmersdelight:cutting_board", 200);
        builder.fuel("farmersdelight:rope", 200);
        builder.fuel("farmersdelight:safety_net", 200);
        builder.fuel("farmersdelight:full_tatami_mat", 200);
        builder.fuel("farmersdelight:canvas_rug", 200);
        builder.fuel("farmersdelight:rope_fence", 200);
        builder.fuel("farmersdelight:rope_fence_gate", 200);
        builder.fuel("farmersdelight:tree_bark", 200);
        builder.fuel("farmersdelight:wooden_basket", 300);
        builder.fuel("farmersdelight:bamboo_basket", 300);
        // The mod applies 300 through the farmersdelight:cabinets/wooden item tag and then removes the two
        // nether-wood cabinets, which do not burn. Expanded here because the burn time is looked up per item id.
        builder.fuel("farmersdelight:oak_cabinet", 300);
        builder.fuel("farmersdelight:spruce_cabinet", 300);
        builder.fuel("farmersdelight:birch_cabinet", 300);
        builder.fuel("farmersdelight:jungle_cabinet", 300);
        builder.fuel("farmersdelight:acacia_cabinet", 300);
        builder.fuel("farmersdelight:dark_oak_cabinet", 300);
        builder.fuel("farmersdelight:mangrove_cabinet", 300);
        builder.fuel("farmersdelight:cherry_cabinet", 300);
        builder.fuel("farmersdelight:bamboo_cabinet", 300);
        builder.fuel("farmersdelight:tatami", 400);
        builder.fuel("farmersdelight:canvas", 400);
        builder.fuel("farmersdelight:straw_bale", 1000);
    }

    private static void addDefaultTrades(Builder builder) {
        // Novice and Apprentice farmer pools hold 5 and 3 vanilla listings; the mod adds 2 to each, so a
        // listing wins one of the two draws for that level with probability 1/7 and 1/5 respectively.
        builder.villagerTrade(new TradeOffer("farmer", 1, "farmersdelight:onion", 26,
                "minecraft:emerald", 1, 16, 2, 0.05f, 1.0D / 7.0D));
        builder.villagerTrade(new TradeOffer("farmer", 1, "farmersdelight:tomato", 26,
                "minecraft:emerald", 1, 16, 2, 0.05f, 1.0D / 7.0D));
        builder.villagerTrade(new TradeOffer("farmer", 2, "farmersdelight:cabbage", 16,
                "minecraft:emerald", 1, 16, 5, 0.05f, 1.0D / 5.0D));
        builder.villagerTrade(new TradeOffer("farmer", 2, "farmersdelight:rice", 20,
                "minecraft:emerald", 1, 16, 5, 0.05f, 1.0D / 5.0D));

        // The generic wandering trader pool holds 64 vanilla listings and the mod adds 4, so one listing wins
        // one of the five generic draws with probability 1/68.
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

    private static void applyComposting(Builder builder, ConfigurationSection section, JavaPlugin plugin) {
        if (section == null) {
            return;
        }
        builder.compostingEnabled = section.getBoolean("enabled", true);
        // Only when the operator's own file carries the list does it replace the built-in one, and only the
        // ids their file still has are kept: Bukkit backs getConfigurationSection with the jar's defaults, so
        // reading the merged keys would resurrect every entry they deleted.
        if (!section.contains("items", true)) {
            return;
        }
        ConfigurationSection items = section.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        builder.compostChances.clear();
        for (String itemId : items.getKeys(false)) {
            if (!items.contains(itemId, true)) {
                continue;
            }
            if (!ItemUtils.isValidItemId(itemId)) {
                I18n.logWarning("worlddata.composting_invalid_item", "id", itemId);
                continue;
            }
            double chance = items.getDouble(itemId, -1.0D);
            if (chance < 0.0D || chance > 1.0D) {
                I18n.logWarning("worlddata.composting_invalid_chance", "id", itemId);
                continue;
            }
            builder.compost(itemId, (float) chance);
        }
    }

    private static void applyFuel(Builder builder, ConfigurationSection section, JavaPlugin plugin) {
        if (section == null) {
            return;
        }
        builder.fuelEnabled = section.getBoolean("enabled", true);
        // Same default-backing caveat as the compostables list above.
        if (!section.contains("items", true)) {
            return;
        }
        ConfigurationSection items = section.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        builder.fuelTimes.clear();
        for (String itemId : items.getKeys(false)) {
            if (!items.contains(itemId, true)) {
                continue;
            }
            if (!ItemUtils.isValidItemId(itemId)) {
                I18n.logWarning("worlddata.fuel_invalid_item", "id", itemId);
                continue;
            }
            int ticks = items.getInt(itemId, 0);
            if (ticks <= 0) {
                I18n.logWarning("worlddata.fuel_invalid_burn_time", "id", itemId);
                continue;
            }
            builder.fuel(itemId, ticks);
        }
    }

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
                    "world-data.trades.villager.trades");
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
            TradeOffer offer = parseOffer(raw, null, 0, plugin, "world-data.trades.wandering-trader.trades");
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
        private boolean compostingEnabled = true;
        private final Map<String, Float> compostChances = new LinkedHashMap<>();
        private boolean fuelEnabled = true;
        private final Map<String, Integer> fuelTimes = new LinkedHashMap<>();
        private boolean villagerTradesEnabled = true;
        private final List<TradeOffer> villagerTrades = new ArrayList<>();
        private boolean wanderingTraderTradesEnabled = true;
        private int wanderingGenericTradeCount = 5;
        private final List<TradeOffer> wanderingTrades = new ArrayList<>();

        private void compost(String itemId, float chance) {
            compostChances.put(itemId.toLowerCase(Locale.ROOT), chance);
        }

        private void fuel(String itemId, int ticks) {
            fuelTimes.put(itemId.toLowerCase(Locale.ROOT), ticks);
        }

        private void villagerTrade(TradeOffer offer) {
            villagerTrades.add(offer);
        }

        private void wanderingTrade(TradeOffer offer) {
            wanderingTrades.add(offer);
        }
    }
}
