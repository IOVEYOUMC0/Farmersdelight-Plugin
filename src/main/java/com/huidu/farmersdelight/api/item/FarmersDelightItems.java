package com.huidu.farmersdelight.api.item;

import com.huidu.farmersdelight.api.text.FarmersDelightText;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static boolean isKnife(ItemStack item) {
        return matchesTag(item, "farmersdelight:tools/knives");
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
