package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
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

    private final FarmersDelightPlugin plugin;
    private final Map<String, KnifeDropRule> dropRules = new HashMap<>();
    private List<String> knifeTags = new ArrayList<>();
    private List<String> knifeItems = new ArrayList<>();

    public KnifeDropHandler(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        dropRules.clear();
        loadDefaultDropRules();

        ConfigurationSection dropsSection = plugin.getConfig().getConfigurationSection("knife-drops");
        if (dropsSection != null) {
            for (String entityType : dropsSection.getKeys(false)) {
                ConfigurationSection entitySection = dropsSection.getConfigurationSection(entityType);
                if (entitySection == null) continue;

                String normalItem = entitySection.getString("normal", "minecraft:air");
                String burningItem = entitySection.getString("burning", null);
                double baseChance = entitySection.getDouble("chance", 1.0);
                double lootingMultiplier = entitySection.getDouble("looting-multiplier", 0.0);

                dropRules.put(entityType.toLowerCase(), new KnifeDropRule(
                        entityType, normalItem, burningItem, baseChance, lootingMultiplier
                ));
            }
        }

        knifeTags = new ArrayList<>(List.of(Constants.TAG_KNIVES));
        knifeItems = new ArrayList<>(List.of(
                "farmersdelight:flint_knife",
                "farmersdelight:iron_knife",
                "farmersdelight:golden_knife",
                "farmersdelight:diamond_knife",
                "farmersdelight:netherite_knife"
        ));
        ConfigurationSection knifeSection = plugin.getConfig().getConfigurationSection("knife-config");
        if (knifeSection != null) {
            List<String> configuredTags = knifeSection.getStringList("tags");
            if (!configuredTags.isEmpty()) {
                knifeTags = configuredTags;
            }
            List<String> configuredItems = knifeSection.getStringList("items");
            if (!configuredItems.isEmpty()) {
                knifeItems = configuredItems;
            }
        }

        I18n.logInfo("knife.loaded_rules", "count", dropRules.size());
        I18n.logInfo("knife.loaded_matchers", "tags", knifeTags.size(), "items", knifeItems.size());
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

        ItemStack mainHand = killer.getInventory().getItemInMainHand();
        if (!isKnife(mainHand)) return;

        String entityKey = entity.getType().name().toLowerCase();
        KnifeDropRule rule = dropRules.get(entityKey);
        if (rule == null) return;

        int lootingLevel = getLootingLevel(killer);
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

            // Fire ham-related advancements when a ham item actually drops.
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

    // Checks whether the configured drop is one of the ham variants.
    private boolean isHamItem(String itemId) {
        if (itemId == null) return false;
        String id = itemId.toLowerCase();
        return id.equals(Constants.ITEM_HAM)
                || id.equals(Constants.ITEM_SMOKED_HAM)
                || id.equals(Constants.ITEM_HONEY_GLAZED_HAM);
    }

    private boolean isKnife(ItemStack item) {
        if (item.getType() == Material.AIR) return false;

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null && knifeItems.contains(customItemId)) {
            return true;
        }

        if (customItemId != null) {
            Set<Key> itemTags = ItemUtils.getCustomItemTags(Key.of(customItemId));
            for (Key tag : itemTags) {
                for (String knifeTag : knifeTags) {
                    if (tag.toString().equalsIgnoreCase(knifeTag)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
    private int getLootingLevel(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand == null || mainHand.getType().isAir()) return 0;

        ItemMeta meta = mainHand.getItemMeta();
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
        dropRules.put(entityType.toLowerCase(), rule);
    }

    public void removeDropRule(String entityType) {
        dropRules.remove(entityType.toLowerCase());
    }

    public Map<String, KnifeDropRule> getDropRules() {
        return Collections.unmodifiableMap(dropRules);
    }

    public void reload() {
        plugin.reloadConfig();
        loadConfig();
    }
}

