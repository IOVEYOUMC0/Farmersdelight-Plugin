package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class KnifeDropHandler implements Listener {

    private final FarmersDelightPlugin plugin;
    // onEntityDeath reads these on arbitrary Folia region threads (a mob can die anywhere) while /fd reload
    // rebuilds them on the command thread. dropRules is a ConcurrentHashMap (runtime register/unregister
    // mutate it) republished by an atomic volatile swap in loadConfig so a reader never sees a transiently
    // empty map; the tool lists are volatile freshly-built lists (safe publication, benign default window).
    private volatile Map<String, KnifeDropRule> dropRules = new ConcurrentHashMap<>();
    private volatile List<String> dropToolTags = new ArrayList<>();
    private volatile List<String> dropToolItems = new ArrayList<>();
    // Rules registered at runtime through the api facade rather than read from config.yml. loadConfig
    // rebuilds dropRules from scratch, so these are re-applied on top of every rebuild — otherwise an
    // addon's rules would silently vanish on /fd reload. Same contract as the externally registered
    // cooking-pot and cutting-board recipes.
    private final Map<String, KnifeDropRule> externalRules = new ConcurrentHashMap<>();

    public KnifeDropHandler(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig(ConfigurationSection dropsConfig) {
        loadConfig(dropsConfig, true);
    }

    public void loadConfig() {
        loadConfig(plugin.getDropsConfig(), true);
    }

    public void loadConfig(boolean logSummary) {
        loadConfig(plugin.getDropsConfig(), logSummary);
    }

    public void loadConfig(ConfigurationSection dropsConfig, boolean logSummary) {
        Map<String, KnifeDropRule> newRules = new ConcurrentHashMap<>();
        ConfigurationSection dropsSection = dropsConfig == null ? null : dropsConfig.getConfigurationSection("mob-extra");
        if (dropsSection != null) {
            for (String entityType : dropsSection.getKeys(false)) {
                ConfigurationSection entitySection = dropsSection.getConfigurationSection(entityType);
                if (entitySection == null) continue;

                String normalItem = ConfigSectionReader.optionalString(entitySection, "normal", "minecraft:air");
                String burningItem = ConfigSectionReader.optionalString(entitySection, "burning", null);
                double baseChance = ConfigSectionReader.optionalDouble(entitySection, "chance", 1.0);
                double lootingMultiplier = ConfigSectionReader.optionalDouble(entitySection, "looting-multiplier", 0.0);
                List<String> toolItems = loadRuleToolItems(entitySection);
                List<String> toolTags = loadRuleToolTags(entitySection);

                newRules.put(entityType.toLowerCase(java.util.Locale.ROOT), new KnifeDropRule(
                        entityType, normalItem, burningItem, baseChance, lootingMultiplier, toolItems, toolTags
                ));
            }
        }
        // Runtime-registered rules win over both the defaults and config.yml, and are re-applied here
        // so a reload doesn't drop them.
        newRules.putAll(externalRules);
        // Publish the fully-built rule map in one volatile write, so a concurrent onEntityDeath reader sees
        // the complete old map or the complete new map, never a mid-rebuild state.
        this.dropRules = newRules;

        dropToolTags = List.of();
        dropToolItems = List.of();
        ConfigurationSection dropToolSection = dropsConfig == null ? null
                : dropsConfig.getConfigurationSection("mob-extra-tools");
        if (dropToolSection != null) {
            loadDropToolMatchers(dropToolSection);
        }

        if (logSummary) {
            I18n.logDetail("loot", "knife.loaded_rules", "count", newRules.size());
            I18n.logDetail("loot", "knife.loaded_matchers", "tags", dropToolTags.size(), "items", dropToolItems.size());
        }
    }

    public int getDropRuleCount() {
        return dropRules.size();
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
        if (keys.length == 0) {
            return List.of();
        }
        return ConfigSectionReader.optionalStringList(section, keys[0],
                java.util.Arrays.copyOfRange(keys, 1, keys.length));
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
        String singleItem = firstString(section);
        if (singleItem != null) {
            items.add(singleItem);
        }
        items.addAll(firstStringList(section, "tool-items", "tools.items"));
        return normalizeIds(items);
    }

    private List<String> loadRuleToolTags(ConfigurationSection section) {
        return normalizeIds(firstStringList(section, "tool-tags", "tools.tags"));
    }

    private String firstString(ConfigurationSection section) {
        for (String key : new String[]{"tool", "tool-item", "required-tool", "required-item"}) {
            if (section.contains(key)) {
                String value = section.getString(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    // NORMAL rather than HIGHEST so loot / quest / economy plugins listening at HIGH and HIGHEST still
    // get to see and edit the knife drop before it is spawned. The drop is contributed to
    // event.getDrops() instead of being spawned directly, which is what makes it visible to them at
    // all: Paper hands the event a live view over the server's pending drop list, and every entry
    // still in that list once the event returns is what actually spawns.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
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
            event.getDrops().add(dropItem);

            // When a ham item actually drops, trigger the ham-related advancement.
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

    // Checks whether the configured drop is a ham variant.
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

    public void registerExternalDropRule(String entityType, KnifeDropRule rule) {
        if (entityType == null || rule == null) {
            return;
        }
        String key = entityType.toLowerCase(java.util.Locale.ROOT);
        externalRules.put(key, rule);
        dropRules.put(key, rule);
    }

    public boolean unregisterExternalDropRule(String entityType) {
        if (entityType == null) {
            return false;
        }
        String key = entityType.toLowerCase(java.util.Locale.ROOT);
        if (externalRules.remove(key) == null) {
            return false;
        }
        dropRules.remove(key);
        return true;
    }

    public Map<String, KnifeDropRule> getDropRules() {
        return Collections.unmodifiableMap(dropRules);
    }

    public void reload() {
        plugin.reloadConfigs();
    }
}
