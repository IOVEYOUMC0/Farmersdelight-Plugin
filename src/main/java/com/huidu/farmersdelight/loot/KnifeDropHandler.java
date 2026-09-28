package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Extra knife drops for rules an addon registers at runtime through FarmersDelightKnifeDrops.
 *
 * <p>The rules that ship with FarmersDelight are not here: they live in the bundled CraftEngine pack
 * (vanilla_loots.yml), so an operator can read and tune them next to every other drop of the pack
 * instead of in a plugin config the pack cannot see. Dropping the item there is also what puts it in front
 * of loot and quest plugins that edit the death event, because the pack contributes to event.getDrops().
 */
public class KnifeDropHandler implements Listener {

    private final FarmersDelightPlugin plugin;
    // Registered while the server runs (an addon's onEnable) and read on arbitrary Folia region threads
    // (a mob can die anywhere), so the map is concurrent and never rebuilt wholesale.
    private final Map<String, KnifeDropRule> externalRules = new ConcurrentHashMap<>();

    public KnifeDropHandler(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public int getDropRuleCount() {
        return externalRules.size();
    }

    // NORMAL rather than HIGHEST so loot / quest / economy plugins listening at HIGH and HIGHEST still
    // get to see the knife drop before it is spawned. The drop is contributed to
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

        String entityKey = entity.getType().name().toLowerCase(Locale.ROOT);
        KnifeDropRule rule = externalRules.get(entityKey);
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
                AdvancementManager advancementManager = plugin.getAdvancementManager();
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
        String id = itemId.toLowerCase(Locale.ROOT);
        return id.equals(Constants.ITEM_HAM)
                || id.equals(Constants.ITEM_SMOKED_HAM)
                || id.equals(Constants.ITEM_HONEY_GLAZED_HAM);
    }

    // A rule that names its own tools keeps them; one that does not wants the plugin-wide knife definition,
    // which is the same check the pack rules use, so an addon rule and a pack rule agree on what a knife is.
    private boolean isDropTool(ItemStack item, KnifeDropRule rule) {
        if (rule != null && rule.hasToolMatchers()) {
            return matchesDropTool(item, rule.getToolItems(), rule.getToolTags());
        }
        return item != null && !item.getType().isAir() && plugin.isKnife(item);
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

    public void registerExternalDropRule(String entityType, KnifeDropRule rule) {
        if (entityType == null || rule == null) {
            return;
        }
        externalRules.put(entityType.toLowerCase(Locale.ROOT), rule);
    }

    public boolean unregisterExternalDropRule(String entityType) {
        if (entityType == null) {
            return false;
        }
        String key = entityType.toLowerCase(Locale.ROOT);
        return externalRules.remove(key) != null;
    }

    public Map<String, KnifeDropRule> getDropRules() {
        return Collections.unmodifiableMap(externalRules);
    }
}
