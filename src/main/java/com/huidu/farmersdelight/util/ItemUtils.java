package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ItemUtils {

    private static final Map<Key, List<ItemStack>> vanillaTagCache = new ConcurrentHashMap<>();
    private static final List<Material> ITEM_MATERIALS = new ArrayList<>();

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final Pattern L10N_PATTERN = Pattern.compile("<(?:l10n|i18n)[:;]([^>]+)>");
    private static final Pattern TRANSLATION_KEY_PATTERN = Pattern.compile("^[a-z0-9_]+(?:\\.[a-z0-9_]+)+$");

    static {
        for (Material material : Registry.MATERIAL) {
            if (!material.isLegacy() && material.isItem()) {
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
        return customItemId == null && item.getType().isBlock();
    }

    /**
     * Creates an item stack from a namespaced item id.
     *
     * @param itemId item id in the form namespace:item_name
     * @return the created stack, or null when the id cannot be resolved
     */
    public static ItemStack createItem(String itemId) {
        if (isEmptyItemId(itemId)) return null;

        try {
            Key key = Key.of(itemId);

            ItemStack customItem = createCustomItem(key);
            if (customItem != null) {
                return customItem;
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

    public static boolean isCustomItemLoaded(Key key) {
        if (key == null) {
            return false;
        }
        return CraftEngineItems.byId(key) != null;
    }

    public static Set<Key> getCustomItemTags(Key key) {
        BukkitItemDefinition item = CraftEngineItems.byId(key);
        if (item == null) {
            return Set.of();
        }
        return Set.copyOf(item.settings().tags());
    }

    public static ItemStack createItem(Key itemId) {
        if (itemId == null) return null;
        return createItem(itemId.toString());
    }

    private static ItemStack createCustomItem(Key key) {
        BukkitItemDefinition item = CraftEngineItems.byId(key);
        if (item == null) {
            return null;
        }
        return item.buildBukkitItem();
    }

    public static boolean isValidItemId(String itemId) {
        if (isEmptyItemId(itemId)) return false;
        return itemId.matches("^[a-z0-9_]+:[a-z0-9_./-]+$");
    }

    public static boolean isEmptyItemId(String itemId) {
        if (itemId == null) return true;
        String normalized = itemId.trim();
        return normalized.isEmpty()
                || normalized.equalsIgnoreCase("none")
                || normalized.equalsIgnoreCase("null")
                || normalized.equalsIgnoreCase("empty")
                || normalized.equalsIgnoreCase("air")
                || normalized.equalsIgnoreCase("minecraft:air");
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
            Component special = resolveSpecialDisplayComponent(meta.itemName(), item, locale);
            if (special instanceof TranslatableComponent translatable) {
                return resolveComponentText(translatable, locale);
            }
            if (special != null) {
                return PLAIN_TEXT.serialize(special);
            }
            String plain = resolveComponentText(meta.itemName(), locale);
            if (plain != null && !plain.isBlank()) {
                String resolved = resolveSpecialDisplayText(plain, item, locale);
                if (resolved != null) {
                    return resolved;
                }
                return plain;
            }
        }
        if (meta != null && meta.displayName() != null) {
            Component special = resolveSpecialDisplayComponent(meta.displayName(), item, locale);
            if (special instanceof TranslatableComponent translatable) {
                return resolveComponentText(translatable, locale);
            }
            if (special != null) {
                return PLAIN_TEXT.serialize(special);
            }
            String plain = resolveComponentText(meta.displayName(), locale);
            if (plain != null && !plain.isBlank()) {
                String resolved = resolveSpecialDisplayText(plain, item, locale);
                if (resolved != null) {
                    return resolved;
                }
                return plain;
            }
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
            Component resolved = resolveSpecialDisplayComponent(meta.itemName(), item, playerLocale(player));
            if (resolved != null) {
                return resolved;
            }
            return meta.itemName();
        }
        if (meta != null && meta.displayName() != null) {
            Component resolved = resolveSpecialDisplayComponent(meta.displayName(), item, playerLocale(player));
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

    private static String resolveComponentText(Component component, String locale) {
        if (component == null) return "";
        if (component instanceof TranslatableComponent translatable) {
            String key = translatable.key();
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return translated;
            }
            if (TRANSLATION_KEY_PATTERN.matcher(key).matches()) {
                return humanizeTranslationKey(key);
            }
        }
        try {
            java.util.Locale loc = locale != null && !locale.isEmpty()
                    ? java.util.Locale.forLanguageTag(locale.replace('_', '-'))
                    : java.util.Locale.getDefault();
            Component rendered = net.kyori.adventure.translation.GlobalTranslator.render(component, loc);
            String plain = PLAIN_TEXT.serialize(rendered);
            if (!plain.isBlank() && !plain.equals(PLAIN_TEXT.serialize(component))) {
                return plain;
            }
            return PLAIN_TEXT.serialize(component);
        } catch (Exception e) {
            return PLAIN_TEXT.serialize(component);
        }
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

        translated = translateViaCraftEngine(key, locale);
        if (translated != null && !translated.equals(key)) {
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

    private static String translateViaCraftEngine(String key, String locale) {
        try {
            Class<?> translationManagerClass = Class.forName(
                    "net.momirealms.craftengine.core.plugin.locale.TranslationManager");
            Object manager = translationManagerClass.getMethod("instance").invoke(null);
            if (manager == null) {
                return null;
            }

            Locale loc = locale != null && !locale.isEmpty()
                    ? Locale.forLanguageTag(locale.replace('_', '-'))
                    : null;

            try {
                Method plainTranslation = manager.getClass().getMethod(
                        "plainTranslation", String.class, Locale.class, String[].class);
                Object result = plainTranslation.invoke(manager, key, loc, new String[0]);
                if (result instanceof String text && !text.equals(key)) {
                    return text;
                }
            } catch (NoSuchMethodException ignored) {
            }

            Method miniMessageTranslation = manager.getClass().getMethod(
                    "miniMessageTranslation", String.class, Locale.class);
            Object result = miniMessageTranslation.invoke(manager, key, loc);
            if (result instanceof String text && !text.equals(key)) {
                return stripMiniMessageTags(text);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }

    private static String stripMiniMessageTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("<[^>]+>", "");
    }

    private static String resolveSpecialDisplayText(String rawText, ItemStack item, String locale) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String normalized = rawText.trim();
        Matcher matcher = L10N_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String key = resolveDisplayTranslationKey(matcher.group(1), item);
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return translated;
            }
            return humanizeTranslationKey(key);
        }
        return resolveTranslationKeyText(normalized, locale);
    }

    private static Component resolveSpecialDisplayComponent(Component component, ItemStack item, String locale) {
        if (component instanceof TranslatableComponent translatable) {
            String key = translatable.key();
            if (isVanillaClientTranslationKey(key)) {
                return Component.translatable(key);
            }
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return Component.text(translated);
            }
            if (TRANSLATION_KEY_PATTERN.matcher(key).matches()) {
                return Component.text(humanizeTranslationKey(key));
            }
        }

        String rawText = PLAIN_TEXT.serialize(component);
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String normalized = rawText.trim();
        Matcher matcher = L10N_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String key = resolveDisplayTranslationKey(matcher.group(1), item);
            if (isVanillaClientTranslationKey(key)) {
                return Component.translatable(key);
            }
            String translated = translate(key, locale);
            if (!translated.equals(key)) {
                return Component.text(translated);
            }
            return Component.text(humanizeTranslationKey(key));
        }

        if (TRANSLATION_KEY_PATTERN.matcher(normalized).matches()) {
            String translated = translate(normalized, locale);
            if (!translated.equals(normalized)) {
                return Component.text(translated);
            }
            if (isVanillaClientTranslationKey(normalized)) {
                return Component.translatable(normalized);
            }
            return Component.text(humanizeTranslationKey(normalized));
        }
        return null;
    }

    private static boolean isVanillaClientTranslationKey(String key) {
        return key != null
                && (key.startsWith("item.minecraft.") || key.startsWith("block.minecraft."))
                && TRANSLATION_KEY_PATTERN.matcher(key).matches();
    }

    private static String resolveDisplayTranslationKey(String configuredKey, ItemStack item) {
        if (configuredKey == null) {
            return "";
        }
        String key = configuredKey.trim();
        String itemId = getDisplayItemId(item);
        String idPath = itemId;
        int separator = itemId.indexOf(':');
        if (separator >= 0 && separator + 1 < itemId.length()) {
            idPath = itemId.substring(separator + 1);
        }
        return key.replace("${__ID__}", idPath)
                .replace("{__ID__}", idPath)
                .replace("${__ITEM_ID__}", itemId)
                .replace("{__ITEM_ID__}", itemId);
    }

    private static String getDisplayItemId(ItemStack item) {
        String customItemId = getCustomItemId(item);
        if (customItemId != null && !customItemId.isBlank()) {
            return customItemId;
        }
        String vanillaItemId = getVanillaMaterialItemId(item);
        if (vanillaItemId != null && !vanillaItemId.isBlank()) {
            return vanillaItemId;
        }
        return "";
    }

    private static String playerLocale(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.locale().toString().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
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

    public static boolean matchesItemId(ItemStack item, String itemId) {
        if (item == null || item.getType().isAir() || isEmptyItemId(itemId)) {
            return false;
        }
        String normalized = itemId.trim();
        String customId = getCustomItemId(item);
        if (customId != null && customId.equalsIgnoreCase(normalized)) {
            return true;
        }
        String vanillaId = getVanillaMaterialItemId(item);
        return vanillaId != null && vanillaId.equalsIgnoreCase(normalized);
    }

    public static boolean matchesItemId(ItemStack item, Key itemId) {
        return itemId != null && matchesItemId(item, itemId.toString());
    }

    public static Set<String> getItemIds(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Set.of();
        }
        String customId = getCustomItemId(item);
        String vanillaId = getVanillaMaterialItemId(item);
        if (customId == null) {
            return vanillaId == null ? Set.of() : Set.of(vanillaId);
        }
        if (vanillaId == null || customId.equals(vanillaId)) {
            return Set.of(customId);
        }
        return Set.of(customId, vanillaId);
    }

    public static Set<String> getItemTagIds(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        String customId = getCustomItemId(item);
        if (customId != null) {
            for (Key tag : getCustomItemTags(Key.of(customId))) {
                tags.add(tag.toString());
            }
        }
        return Set.copyOf(tags);
    }

    public static boolean matchesCustomOrVanillaTag(ItemStack item, String tagId) {
        if (item == null || item.getType().isAir() || tagId == null || tagId.isBlank()) {
            return false;
        }
        String normalized = tagId.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        Key tagKey = Key.of(normalized);
        Set<String> customTags = getItemTagIds(item);
        if (customTags.stream().anyMatch(tag -> tag.equalsIgnoreCase(tagKey.toString()))) {
            return true;
        }
        return matchesVanillaItemTag(item, tagKey, Set.of(), Set.of());
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

