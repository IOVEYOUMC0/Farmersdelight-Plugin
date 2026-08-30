package com.huidu.farmersdelight.api.item;

import com.huidu.farmersdelight.api.text.FarmersDelightText;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@ApiStatus.NonExtendable
public final class FarmersDelightItems {

    private FarmersDelightItems() {
    }

    public static String idOf(ItemStack item) {
        return ItemUtils.resolveItemId(item);
    }

    public static ItemStack create(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    public static boolean matchesId(ItemStack item, String itemId) {
        return ItemUtils.matchesItemId(item, itemId);
    }

    public static boolean matchesTag(ItemStack item, String tagId) {
        return ItemUtils.matchesCustomOrVanillaTag(item, tagId);
    }

    /**
     * Whether two stacks are the same item for recipe/display linking: custom items match by custom id
     * (ignoring base material), everything else by material type. Amount and NBT are not compared, so a
     * single custom item is "the same" as a 64-stack of itself. Cross-references and ingredient checks
     * throughout the addons use this.
     */
    public static boolean isSameItem(ItemStack a, ItemStack b) {
        return ItemUtils.isSameItem(a, b);
    }

    public static boolean isKnife(ItemStack item) {
        return matchesTag(item, "farmersdelight:tools/knives");
    }

    /**
     * Damage a durable custom item (one carrying vanilla durability components, e.g. via the
     * farmersdelight:durable item setting) by amount, the same way FarmersDelight's own tools wear: the
     * Unbreaking enchant is rolled per point of damage, and when the item runs out it is consumed
     * (amount set to 0). Returns true if the item broke. No-op (returns false) for a non-damageable or
     * unbreakable item. This is the durability path decoupled from any weapon/attack behaviour.
     */
    public static boolean damage(ItemStack item, int amount) {
        return damage(item, amount, null);
    }

    /**
     * As damage(item, amount), but plays the vanilla item-break sound at breakSoundLocation when the item
     * breaks (pass null to stay silent).
     */
    public static boolean damage(ItemStack item, int amount, Location breakSoundLocation) {
        if (item == null || item.getType().isAir() || amount <= 0
                || !(item.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) {
            return false;
        }
        int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        if (maxDamage <= 0) {
            return false;
        }
        int unbreaking = damageable.getEnchantLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int i = 0; i < amount; i++) {
            // Vanilla rolls the Unbreaking skip per point of damage, not once for the whole amount.
            if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) > 0) {
                continue;
            }
            applied++;
        }
        if (applied <= 0) {
            return false;
        }
        int next = Math.max(0, damageable.getDamage()) + applied;
        if (next >= maxDamage) {
            item.setAmount(0);
            if (breakSoundLocation != null && breakSoundLocation.getWorld() != null) {
                breakSoundLocation.getWorld().playSound(breakSoundLocation, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            }
            return true;
        }
        damageable.setDamage(next);
        item.setItemMeta(damageable);
        return false;
    }

    public static Component displayNameOf(ItemStack item, Player player) {
        return ItemUtils.getDisplayComponent(item, player);
    }

    public static Component translatableDisplayNameOf(ItemStack item) {
        return ItemUtils.getTranslatableDisplayComponent(item);
    }

    public static Component translatableDisplayNameOfNoAnvilOf(ItemStack item) {
        return ItemUtils.getTranslatableDisplayComponentNoAnvil(item);
    }

    public static Component serverDisplayNameOf(ItemStack item) {
        return ItemUtils.getServerDisplayComponent(item);
    }

    public static Set<String> idsOf(ItemStack item) {
        return ItemUtils.getItemIds(item);
    }

    public static Set<String> tagIdsOf(ItemStack item) {
        return ItemUtils.getItemTagIds(item);
    }

    public static boolean isCustomItem(ItemStack item) {
        return ItemUtils.isCustomItem(item);
    }

    public static String customIdOf(ItemStack item) {
        return ItemUtils.getCustomItemId(item);
    }

    public static ItemStack craftingRemainderOf(ItemStack item) {
        return ItemUtils.craftingRemainderOf(item);
    }

    public static void applyDisplay(ItemStack item, String nameTemplate, List<String> loreTemplates,
                                    Player viewer, Map<String, String> placeholders) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        if (nameTemplate != null) {
            meta.displayName(FarmersDelightText.render(nameTemplate, viewer, placeholders)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (loreTemplates != null) {
            meta.lore(FarmersDelightText.buildLore(loreTemplates, viewer, placeholders));
        }
        item.setItemMeta(meta);
    }

    public static ItemStack buildIcon(String itemId, String nameTemplate, List<String> loreTemplates,
                                      Player viewer, Map<String, String> placeholders) {
        ItemStack item = create(itemId);
        if (item == null) {
            return null;
        }
        applyDisplay(item, nameTemplate, loreTemplates, viewer, placeholders);
        return item;
    }
}
