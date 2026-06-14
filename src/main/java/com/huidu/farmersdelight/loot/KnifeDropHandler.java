package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class KnifeDropHandler implements Listener {

    private static final String[] DROP_RULE_PATHS = {"mob-extra-drops", "entity-extra-drops", "knife-drops"};
    private static final String[] DROP_TOOL_PATHS = {"mob-extra-drop-tools", "entity-extra-drop-tools", "knife-drop-tools"};
    private static final List<String> DEFAULT_DROP_TOOL_ITEMS = List.of(
            "farmersdelight:flint_knife",
            "farmersdelight:iron_knife",
            "farmersdelight:golden_knife",
            "farmersdelight:diamond_knife",
            "farmersdelight:netherite_knife"
    );

    private final FarmersDelightPlugin plugin;
    private final Map<String, KnifeDropRule> dropRules = new HashMap<>();
    private List<String> dropToolTags = new ArrayList<>();
    private List<String> dropToolItems = new ArrayList<>();

    public KnifeDropHandler(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        loadConfig(true);
    }

    public void loadConfig(boolean logSummary) {
        dropRules.clear();
        loadDefaultDropRules();

        ConfigurationSection dropsSection = getFirstConfiguredSection(DROP_RULE_PATHS);
        if (dropsSection == null) {
            dropsSection = plugin.getFirstConfigSection(DROP_RULE_PATHS);
        }
        if (dropsSection != null) {
            for (String entityType : dropsSection.getKeys(false)) {
                ConfigurationSection entitySection = dropsSection.getConfigurationSection(entityType);
                if (entitySection == null) continue;

                String normalItem = entitySection.getString("normal", "minecraft:air");
                String burningItem = entitySection.getString("burning", null);
                double baseChance = entitySection.getDouble("chance", 1.0);
                double lootingMultiplier = entitySection.getDouble("looting-multiplier", 0.0);
                List<String> toolItems = loadRuleToolItems(entitySection);
                List<String> toolTags = loadRuleToolTags(entitySection);

                dropRules.put(entityType.toLowerCase(java.util.Locale.ROOT), new KnifeDropRule(
                        entityType, normalItem, burningItem, baseChance, lootingMultiplier, toolItems, toolTags
                ));
            }
        }

        dropToolTags = new ArrayList<>(List.of(Constants.TAG_KNIVES));
        dropToolItems = new ArrayList<>(DEFAULT_DROP_TOOL_ITEMS);
        ConfigurationSection knifeSection = plugin.getConfig().isSet("knife-config")
                ? plugin.getConfig().getConfigurationSection("knife-config")
                : null;
        if (knifeSection != null) {
            loadDropToolMatchers(knifeSection);
        }
        ConfigurationSection dropToolSection = getFirstConfiguredSection(DROP_TOOL_PATHS);
        if (dropToolSection != null) {
            loadDropToolMatchers(dropToolSection);
        } else if (knifeSection == null) {
            loadDropToolMatchers(plugin.getFirstConfigSection(DROP_TOOL_PATHS));
        }

        if (logSummary) {
            I18n.logInfo("knife.loaded_rules", "count", dropRules.size());
            I18n.logInfo("knife.loaded_matchers", "tags", dropToolTags.size(), "items", dropToolItems.size());
        }
    }

    private ConfigurationSection getFirstConfiguredSection(String... paths) {
        for (String path : paths) {
            if (!plugin.getConfig().isSet(path)) {
                continue;
            }
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private void loadDropToolMatchers(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        if (section.contains("tags") || section.contains("tool-tags")) {
            dropToolTags = normalizeIds(firstStringList(section, "tags", "tool-tags"));
        }
        if (section.contains("items") || section.contains("tool-items")) {
            dropToolItems = normalizeIds(firstStringList(section, "items", "tool-items"));
        }
    }

    private List<String> firstStringList(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getStringList(key);
            }
        }
        return List.of();
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> id.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(id -> !id.isEmpty())
                .toList();
    }

    private List<String> loadRuleToolItems(ConfigurationSection section) {
        List<String> items = new ArrayList<>();
        String singleItem = firstString(section, "tool", "tool-item", "required-tool", "required-item");
        if (singleItem != null) {
            items.add(singleItem);
        }
        items.addAll(firstStringList(section, "tool-items", "tools.items"));
        return normalizeIds(items);
    }

    private List<String> loadRuleToolTags(ConfigurationSection section) {
        return normalizeIds(firstStringList(section, "tool-tags", "tools.tags"));
    }

    private String firstString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                String value = section.getString(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private void loadDefaultDropRules() {
        putDefaultDrop("pig", "farmersdelight:ham", "farmersdelight:smoked_ham", 0.5D, 0.1D);
        putDefaultDrop("hoglin", "farmersdelight:ham", "farmersdelight:smoked_ham", 0.5D, 0.1D);
        for (String entityType : List.of("cow", "mooshroom", "donkey", "horse", "mule", "llama", "trader_llama")) {
            putDefaultDrop(entityType, "minecraft:leather", null, 1.0D, 0.0D);
        }
        putDefaultDrop("chicken", "minecraft:feather", null, 1.0D, 0.0D);
        putDefaultDrop("spider", "minecraft:string", null, 1.0D, 0.0D);
        putDefaultDrop("cave_spider", "minecraft:string", null, 1.0D, 0.0D);
        putDefaultDrop("rabbit", "minecraft:rabbit_hide", null, 1.0D, 0.0D);
        putDefaultDrop("shulker", "minecraft:shulker_shell", null, 1.0D, 0.0D);
    }

    private void putDefaultDrop(String entityType, String normalItem, String burningItem, double chance, double lootingMultiplier) {
        dropRules.put(entityType, new KnifeDropRule(entityType, normalItem, burningItem, chance, lootingMultiplier));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!FarmersDelightPlugin.isEnabled0()) return;

        var entity = event.getEntity();
        Player killer = entity.getKiller();

        if (!(killer instanceof Player)) return;

        if (entity instanceof Ageable ageable && !ageable.isAdult()) {
            return;
        }

        String entityKey = entity.getType().name().toLowerCase(java.util.Locale.ROOT);
        KnifeDropRule rule = dropRules.get(entityKey);
        if (rule == null) return;

        ItemStack mainHand = killer.getInventory().getItemInMainHand();
        if (!isDropTool(mainHand, rule)) return;

        int lootingLevel = getLootingLevel(mainHand);
        double finalChance = Math.min(1.0, rule.getBaseChance() + (lootingLevel * rule.getLootingMultiplier()));

        if (ThreadLocalRandom.current().nextDouble() > finalChance) return;

        boolean isBurning = entity.getFireTicks() > 0;
        String itemId;

        if (isBurning && rule.getBurningItem() != null) {
            itemId = rule.getBurningItem();
        } else {
            itemId = rule.getNormalItem();
        }
        if (ItemUtils.isEmptyItemId(itemId)) {
            return;
        }

        ItemStack dropItem = createItem(itemId);
        if (dropItem != null) {
            entity.getWorld().dropItemNaturally(entity.getLocation(), dropItem);

            // 当确实掉落 ham 物品时，触发与 ham 相关的进度（advancement）。
            if (isHamItem(itemId)) {
                AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
                if (advancementManager != null) {
                    advancementManager.award(killer, "get_ham");
                }
            }

            if (plugin.isDebugEnabled()) {
                I18n.logInfo("knife.dropped",
                        "item", itemId,
                        "entity", entity.getType().name(),
                        "location", entity.getLocation());
            }
        }
    }

    // 检查所配置的掉落物是否为 ham 的某个变体。
    private boolean isHamItem(String itemId) {
        if (itemId == null) return false;
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        return id.equals(Constants.ITEM_HAM)
                || id.equals(Constants.ITEM_SMOKED_HAM)
                || id.equals(Constants.ITEM_HONEY_GLAZED_HAM);
    }

    private boolean isDropTool(ItemStack item, KnifeDropRule rule) {
        if (rule != null && rule.hasToolMatchers()) {
            return matchesDropTool(item, rule.getToolItems(), rule.getToolTags());
        }
        return matchesDropTool(item, dropToolItems, dropToolTags);
    }

    private boolean matchesDropTool(ItemStack item, List<String> toolItems, List<String> toolTags) {
        if (item == null || item.getType() == Material.AIR) return false;

        for (String toolItem : toolItems) {
            if (ItemUtils.matchesItemId(item, toolItem)) {
                return true;
            }
        }

        for (String toolTag : toolTags) {
            try {
                if (ItemUtils.matchesCustomOrVanillaTag(item, toolTag)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }
    private int getLootingLevel(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) return 0;

        ItemMeta meta = tool.getItemMeta();
        if (meta == null) return 0;

        return meta.getEnchantLevel(Enchantment.LOOTING);
    }

    private ItemStack createItem(String itemId) {
        if (ItemUtils.isEmptyItemId(itemId)) {
            return null;
        }
        if (!ItemUtils.isValidItemId(itemId)) {
            I18n.logWarning("knife.invalid_item_id", "id", itemId);
            return null;
        }
        return ItemUtils.createItem(itemId);
    }

    public void addDropRule(String entityType, KnifeDropRule rule) {
        dropRules.put(entityType.toLowerCase(java.util.Locale.ROOT), rule);
    }

    public void removeDropRule(String entityType) {
        dropRules.remove(entityType.toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, KnifeDropRule> getDropRules() {
        return Collections.unmodifiableMap(dropRules);
    }

    public void reload() {
        plugin.reloadConfig();
        loadConfig(false);
    }
}
