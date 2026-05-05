package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for looking up and creating items.
 *
 * <p>This class provides one place to work with:
 * <ul>
 *   <li>CraftEngine custom items</li>
 *   <li>CraftEngine buildable items</li>
 *   <li>Vanilla Minecraft materials</li>
 * </ul>
 *
 * <p>Recipe loading and loot systems should use this helper to avoid
 * duplicating item resolution logic.
 */
public final class ItemUtils {

    private static final Map<Key, List<ItemStack>> vanillaTagCache = new ConcurrentHashMap<>();
    private static final List<Material> ITEM_MATERIALS = new ArrayList<>();

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final Pattern L10N_PATTERN = Pattern.compile("<l10n[:;]([^>]+)>");
    private static final Pattern TRANSLATION_KEY_PATTERN = Pattern.compile("^[a-z0-9_]+(?:\\.[a-z0-9_]+)+$");

    static {
        for (Material material : Material.values()) {
            if (material.isItem()) {
                ITEM_MATERIALS.add(material);
            }
        }
    }

    private ItemUtils() {
    }

    /**
     * Returns the custom item id, or null when the stack is not a CE custom item.
     */
    public static String getCustomItemId(ItemStack item) {
        if (item == null) return null;
        Key key = CraftEngineItems.getCustomItemId(item);
        if (key == null) {
            return null;
        }
        return key.toString();
    }

    public static String getVanillaMaterialItemId(ItemStack item) {
        if (item == null) {
            return null;
        }
        Material type = item.getType();
        if (type.isAir() || !type.isItem()) {
            return null;
        }
        return "minecraft:" + type.name().toLowerCase(Locale.ROOT);
    }

    public static boolean shouldUseBlockStyleDisplay(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String customItemId = getCustomItemId(item);
        if (customItemId == null) {
            return item.getType().isBlock();
        }

        return isKnownBlockLikeCustomItem(customItemId);
    }

    /**
     * Creates an item stack from a namespaced item id.
     *
     * @param itemId item id in the form namespace:item_name
     * @return the created stack, or null when the id cannot be resolved
     */
    public static ItemStack createItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;

        try {
            Key key = Key.of(itemId);
            var itemManager = BukkitCraftEngine.instance().itemManager();

            var customItem = itemManager.getCustomItem(key).orElse(null);
            if (customItem != null) {
                return customItem.buildItemStack();
            }

            var buildableItem = itemManager.getBuildableItem(key).orElse(null);
            if (buildableItem != null) {
                return buildableItem.buildItemStack();
            }

            NamespacedKey materialKey = NamespacedKey.fromString(itemId);
            if (materialKey != null) {
                Material material = Registry.MATERIAL.get(materialKey);
                if (material != null) {
                    return new ItemStack(material);
                }
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }

    public static ItemStack createItem(Key itemId) {
        if (itemId == null) return null;
        return createItem(itemId.toString());
    }

    public static boolean isValidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return false;
        return itemId.matches("^[a-z0-9_]+:[a-z0-9_]+$");
    }

    public static String getDisplayName(ItemStack item) {
        return getDisplayName(item, (String) null);
    }

    public static String getDisplayName(ItemStack item, Player player) {
        String locale = null;
        if (player != null) {
            locale = player.locale().toString().toLowerCase(Locale.ROOT);
        }
        return getDisplayName(item, locale);
    }

    public static String getDisplayName(ItemStack item, String locale) {
        if (item == null || item.getType().isAir()) {
            return translate("gui.recipe.unknown", locale);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasItemName() && meta.itemName() != null) {
            String resolved = resolveSpecialDisplayText(PLAIN_TEXT.serialize(meta.itemName()), locale);
            if (resolved != null) {
                return resolved;
            }
            return PLAIN_TEXT.serialize(meta.itemName());
        }
        if (meta != null && meta.displayName() != null) {
            String resolved = resolveSpecialDisplayText(PLAIN_TEXT.serialize(meta.displayName()), locale);
            if (resolved != null) {
                return resolved;
            }
            return PLAIN_TEXT.serialize(meta.displayName());
        }

        String customItemId = getCustomItemId(item);
        if (customItemId != null) {
            String itemKey = "item." + customItemId.replace(':', '.');
            String translated = translate(itemKey, locale);
            if (!translated.equals(itemKey)) {
                return translated;
            }

            String blockKey = "block." + customItemId.replace(':', '.');
            translated = translate(blockKey, locale);
            if (!translated.equals(blockKey)) {
                return translated;
            }

            return humanizeKey(customItemId.substring(customItemId.indexOf(':') + 1));
        }

        String materialName = item.getType().name().toLowerCase();
        String translated = translate("item.minecraft." + materialName, locale);
        if (!translated.equals("item.minecraft." + materialName)) {
            return translated;
        }
        translated = translate("block.minecraft." + materialName, locale);
        if (!translated.equals("block.minecraft." + materialName)) {
            return translated;
        }

        return humanizeKey(materialName);
    }

    public static Component getDisplayComponent(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) {
            return Component.text(I18n.get("gui.recipe.unknown", player));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasItemName() && meta.itemName() != null) {
            Component resolved = resolveSpecialDisplayComponent(meta.itemName(), player);
            if (resolved != null) {
                return resolved;
            }
            return meta.itemName();
        }
        if (meta != null && meta.displayName() != null) {
            Component resolved = resolveSpecialDisplayComponent(meta.displayName(), player);
            if (resolved != null) {
                return resolved;
            }
            return meta.displayName();
        }

        if (getCustomItemId(item) == null && item.getType().isItem()) {
            return Component.translatable(item.getType().getItemTranslationKey());
        }

        return Component.text(getDisplayName(item, player));
    }

    private static String translate(String key, String locale) {
        String translated;
        if (locale != null) {
            translated = I18n.get(key, locale);
        } else {
            translated = I18n.get(key);
        }
        if (!translated.equals(key)) {
            return translated;
        }

        translated = I18n.get(key, "en_us");
        if (!translated.equals(key)) {
            return translated;
        }

        try {
            java.util.Locale loc = locale != null && !locale.isEmpty()
                    ? java.util.Locale.forLanguageTag(locale.replace('_', '-'))
                    : java.util.Locale.getDefault();
            Component rendered = net.kyori.adventure.translation.GlobalTranslator.render(
                    Component.translatable(key), loc);
            String plain = PLAIN_TEXT.serialize(rendered);
            if (!plain.equals(key)) {
                return plain;
            }
        } catch (Exception ignored) {
        }

        return key;
    }

    private static String resolveSpecialDisplayText(String rawText, String locale) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String normalized = rawText.trim();
        Matcher matcher = L10N_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String key = matcher.group(1);
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return translated;
            }
            // 如果翻译失败，尝试作为翻译键处理
            return humanizeTranslationKey(key);
        }
        return resolveTranslationKeyText(normalized, locale);
    }

    private static boolean isKnownBlockLikeCustomItem(String customItemId) {
        String path = customItemId;
        int separator = customItemId.indexOf(':');
        if (separator >= 0 && separator + 1 < customItemId.length()) {
            path = customItemId.substring(separator + 1);
        }

        return path.endsWith("_crate")
                || path.endsWith("_cabinet")
                || path.endsWith("_basket")
                || path.endsWith("_bale")
                || path.endsWith("_bag")
                || path.endsWith("_tray")
                || path.endsWith("_rug")
                || path.endsWith("_tatami")
                || path.endsWith("_mat")
                || path.endsWith("_soil")
                || path.endsWith("_farmland")
                || path.endsWith("_compost")
                || path.endsWith("_block")
                || path.equals("rope");
    }

    private static Component resolveSpecialDisplayComponent(Component component, Player player) {
        String rawText = PLAIN_TEXT.serialize(component);
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String locale = null;
        if (player != null) {
            locale = player.locale().toString().toLowerCase(Locale.ROOT);
        }
        String normalized = rawText.trim();
        Matcher matcher = L10N_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String key = matcher.group(1);
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return Component.text(translated);
            }
            // 如果翻译失败，尝试返回可翻译组件
            return Component.translatable(key);
        }
        String translated = resolveTranslationKeyText(normalized, locale);
        if (translated != null) {
            return Component.text(translated);
        }
        return null;
    }

    private static String resolveTranslationKeyText(String rawText, String locale) {
        if (!TRANSLATION_KEY_PATTERN.matcher(rawText).matches()) {
            return null;
        }
        String translated = translate(rawText, locale);
        if (!translated.equals(rawText)) {
            return translated;
        }
        return humanizeTranslationKey(rawText);
    }

    private static String humanizeTranslationKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        int lastDot = key.lastIndexOf('.');
        String leaf = key;
        if (lastDot >= 0) {
            leaf = key.substring(lastDot + 1);
        }
        return humanizeKey(leaf);
    }

    public static String humanizeKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }

        String[] parts = key.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase());
            }
        }
        return builder.toString();
    }

    public static boolean matchesVanillaItemTag(ItemStack item, Key tagKey, Set<Key> excludedItems, Set<Key> excludedTags) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        Key itemKey = Key.of("minecraft:" + item.getType().name().toLowerCase());
        if (excludedItems.contains(itemKey)) {
            return false;
        }
        if (!isVanillaMaterialInTag(item.getType(), tagKey)) {
            return false;
        }
        for (Key excludedTag : excludedTags) {
            if (isVanillaMaterialInTag(item.getType(), excludedTag)) {
                return false;
            }
        }
        return true;
    }

    public static List<ItemStack> createVanillaTagDisplayItems(Key tagKey, Set<Key> excludedItems, Set<Key> excludedTags) {
        if (excludedItems.isEmpty() && excludedTags.isEmpty()) {
            List<ItemStack> cached = vanillaTagCache.get(tagKey);
            if (cached != null) return cached;
        }

        List<ItemStack> items = new ArrayList<>();
        for (Material material : ITEM_MATERIALS) {
            ItemStack candidate = new ItemStack(material);
            if (matchesVanillaItemTag(candidate, tagKey, excludedItems, excludedTags)) {
                items.add(candidate);
            }
        }

        if (excludedItems.isEmpty() && excludedTags.isEmpty()) {
            List<ItemStack> unmodifiable = Collections.unmodifiableList(items);
            vanillaTagCache.put(tagKey, unmodifiable);
            return unmodifiable;
        }
        return items;
    }

    private static boolean isVanillaMaterialInTag(Material material, Key tagKey) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(tagKey.toString());
        if (namespacedKey == null) {
            return false;
        }
        try {
            Tag<Material> tag = Bukkit.getTag("items", namespacedKey, Material.class);
            return tag != null && tag.isTagged(material);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String customId = getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase();
    }

    public static ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }
}
