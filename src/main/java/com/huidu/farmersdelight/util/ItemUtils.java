package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.block.behavior.BlockBehaviorConfigs;
import com.huidu.farmersdelight.block.behavior.ConfiguredBlockSet;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ItemUtils {

    private static final Map<Key, List<ItemStack>> vanillaTagCache = new ConcurrentHashMap<>();
    // Resolved Bukkit item tags stay constant for the server's lifetime; cache them so tag
    // checks during matching skip rerunning NamespacedKey.fromString + Bukkit.getTag per call.
    private static final Map<Key, Optional<Tag<Material>>> vanillaItemTagResolveCache = new ConcurrentHashMap<>();
    // Built CE item stacks memoized by id. buildBukkitItem() re-parses the item's MiniMessage name on every
    // call (all FD/BAC names are <l10n:> DynamicLines), so cache the built base and hand out clones. Cleared
    // on CE/config reload so redefined items rebuild.
    private static final Map<Key, ItemStack> itemBuildCache = new ConcurrentHashMap<>();
    private static final List<Material> ITEM_MATERIALS = new ArrayList<>();
    // Pre-built reverse index: material → all vanilla item-tag IDs it belongs to.
    // Replaces the O(n) getAllItemTagIds iteration over every registered tag per call.
    private static final Map<Material, List<String>> MATERIAL_TAG_INDEX = new ConcurrentHashMap<>();

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final Pattern L10N_PATTERN = Pattern.compile("<(?:l10n|i18n)[:;]([^>]+)>");
    private static final Pattern TRANSLATION_KEY_PATTERN = Pattern.compile("^[a-z0-9_]+(?:\\.[a-z0-9_]+)+$");
    // Translation tags resolvable in arbitrary config text: <l10n:key>, <lang:key>, <i18n:key> (':' or ';').
    private static final Pattern TRANSLATION_TAG_PATTERN = Pattern.compile("<(?:l10n|lang|i18n)[:;]([^>]+)>");

    static {
        for (Material material : Registry.MATERIAL) {
            if (!material.isLegacy() && material.isItem()) {
                ITEM_MATERIALS.add(material);
            }
        }
    }

    private ItemUtils() {
    }

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

    public static ItemStack createItem(String itemId) {
        if (isEmptyItemId(itemId)) return null;
        // MMOItems items are addressed as mmoitems:<TYPE>:<ID>; build them through MMOItems so the
        // item carries its full identity (custom_data etc.) instead of a plain vanilla base.
        ItemStack mmoItem = MMOItemsCompat.tryCreate(itemId);
        if (mmoItem != null) {
            return mmoItem;
        }

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

    public static boolean isAnyCustomItemLoaded() {
        return !CraftEngineItems.loadedItems().isEmpty();
    }

    public static Set<Key> getCustomItemTags(Key key) {
        BukkitItemDefinition item = CraftEngineItems.byId(key);
        if (item == null) {
            return Set.of();
        }
        return Set.copyOf(item.settings().tags());
    }

    public static boolean hasCustomItemTag(ItemStack item, Key tag) {
        if (item == null || tag == null) {
            return false;
        }
        Key id = CraftEngineItems.getCustomItemId(item);
        if (id == null) {
            return false;
        }
        BukkitItemDefinition definition = CraftEngineItems.byId(id);
        return definition != null && definition.settings().tags().contains(tag);
    }

    public static ItemStack createItem(Key itemId) {
        if (itemId == null) return null;
        return createItem(itemId.toString());
    }

    /**
     * Resolves a slot entry's item id into concrete items for display. Supports "#namespace:tag"
     * references: the tag expands to its merged CraftEngine members (vanilla + custom items via
     * itemIdsByTag). Returns an empty list when nothing resolves.
     */
    public static List<ItemStack> createSlotItems(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        if (!itemId.startsWith("#")) {
            ItemStack item = createItem(itemId);
            return item == null || item.getType().isAir() ? List.of() : List.of(item);
        }
        List<ItemStack> items = new ArrayList<>();
        try {
            Key tag = Key.of(itemId.substring(1));
            for (UniqueKey member : BukkitItemManager.instance().itemIdsByTag(tag)) {
                ItemStack item = createItem(member.toString());
                if (item != null && !item.getType().isAir()) {
                    items.add(item);
                }
            }
        } catch (Exception ex) {
            // Unknown tag or CraftEngine not ready: the slot renders as empty/barrier elsewhere, but
            // surface the failure so a misconfigured tag is not silently swallowed.
            Bukkit.getLogger().warning("Failed to resolve CraftEngine tag '" + itemId + "' in slot items: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        return items;
    }

    /**
     * Resolves a slot entry that may be a block-behavior config list reference (behaviorBlockId +
     * behaviorListKey) instead of a plain item id. Behavior-list entries expand to the block set's
     * member items, so the GUI mirrors exactly what the block behavior reads.
     */
    public static List<ItemStack> createSlotItems(String itemId, String behaviorBlockId, String behaviorListKey) {
        if (behaviorBlockId != null && behaviorListKey != null && !behaviorListKey.isBlank()) {
            return createBehaviorListItems(behaviorBlockId, behaviorListKey);
        }
        return createSlotItems(itemId);
    }

    /**
     * Expands a block behavior's configured block list (via BlockBehaviorConfigs) into item stacks.
     * Concrete members resolve as items; custom tag members (#...) expand through the same path as
     * slot "#tag" references.
     */
    public static List<ItemStack> createBehaviorListItems(String blockId, String listKey) {
        if (blockId == null || listKey == null || listKey.isBlank()) {
            return List.of();
        }
        ConfiguredBlockSet set = BlockBehaviorConfigs.get(Key.of(blockId), listKey);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        for (String id : set.memberIds()) {
            ItemStack item = createItem(id);
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        for (Key tag : set.tags()) {
            items.addAll(createSlotItems("#" + tag));
        }
        return items;
    }

    private static ItemStack createCustomItem(Key key) {
        ItemStack cached = itemBuildCache.get(key);
        if (cached != null) {
            return cached.clone();
        }
        BukkitItemDefinition item = CraftEngineItems.byId(key);
        if (item == null) {
            return null;
        }
        ItemStack built = item.buildBukkitItem();
        if (built != null) {
            itemBuildCache.put(key, built.clone());
        }
        return built;
    }

    public static void clearItemCache() {
        itemBuildCache.clear();
        vanillaTagCache.clear();
        vanillaItemTagResolveCache.clear();
        MATERIAL_TAG_INDEX.clear();
    }

    public static int warmItems(String namespace) {
        int built = 0;
        for (Key key : CraftEngineItems.loadedItems().keySet()) {
            if (namespace != null && !namespace.equals(key.namespace())) {
                continue;
            }
            if (createItem(key) != null) {
                built++;
            }
        }
        return built;
    }

    public static net.momirealms.craftengine.libraries.nbt.Tag saveBukkitItemAsTag(ItemStack item) {
        return net.momirealms.craftengine.bukkit.util.ItemStackUtils.saveBukkitItemAsTag(item);
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
            String resolved = resolveNameFromComponent(meta.itemName(), item, locale);
            if (resolved != null) {
                return resolved;
            }
        }
        if (meta != null && meta.displayName() != null) {
            String resolved = resolveNameFromComponent(meta.displayName(), item, locale);
            if (resolved != null) {
                return resolved;
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

        String materialName = item.getType().name().toLowerCase(java.util.Locale.ROOT);
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

    private static String resolveNameFromComponent(Component nameMeta, ItemStack item, String locale) {
        Component special = resolveSpecialDisplayComponent(nameMeta, item, locale);
        if (special instanceof TranslatableComponent translatable) {
            return resolveComponentText(translatable, locale);
        }
        if (special != null) {
            return PLAIN_TEXT.serialize(special);
        }
        String plain = resolveComponentText(nameMeta, locale);
        if (plain != null && !plain.isBlank()) {
            String resolved = resolveSpecialDisplayText(plain, item, locale);
            if (resolved != null) {
                return resolved;
            }
            return plain;
        }
        return null;
    }

    public static Component getDisplayComponent(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) {
            return Component.text(I18n.get("gui.recipe.unknown", player));
        }

        String locale = I18n.getPlayerLocale(player);
        Component nameMeta = null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasItemName() && meta.itemName() != null) {
            nameMeta = meta.itemName();
        } else if (meta != null && meta.displayName() != null) {
            nameMeta = meta.displayName();
        }

        // Vanilla items (no custom id, or minecraft-namespaced CraftEngine wrapper items) should be
        // rendered via their client translation key, so the player's own locale localizes them.
        // CraftEngine may bake the translation key onto the item as a literal name (an unresolved
        // server-side <l10n:...>); returning it as-is shows raw "item.minecraft.cod" in the recipe GUI.
        String customId = getCustomItemId(item);
        boolean vanillaItem = (customId == null || customId.startsWith("minecraft:")) && item.getType().isItem();
        String vanillaKey = vanillaItem ? item.getType().getItemTranslationKey() : null;

        if (nameMeta != null) {
            Component resolved = resolveSpecialDisplayComponent(nameMeta, item, locale);
            if (resolved != null) {
                return resolved;
            }
            // Only override when the baked name is exactly the item's own translation key; genuine
            // custom names (e.g. anvil-renamed items) are left untouched.
            if (vanillaKey != null && PLAIN_TEXT.serialize(nameMeta).trim().equals(vanillaKey)) {
                return Component.translatable(vanillaKey);
            }
            return nameMeta;
        }

        if (vanillaItem) {
            return Component.translatable(vanillaKey);
        }

        return Component.text(getDisplayName(item, player));
    }

    public static Component getTranslatableDisplayComponent(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Component.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        String customId = getCustomItemId(item);
        if (customId != null && !customId.startsWith("minecraft:")) {
            String key = "item." + customId.replace(':', '.');
            return Component.translatable(key).fallback(translationFallback(key, customId));
        }
        if (item.getType().isItem()) {
            // Vanilla translation keys ARE shipped client-side, so no fallback needed.
            return Component.translatable(item.getType().getItemTranslationKey());
        }
        return Component.text(item.getType().name());
    }

    public static Component getTranslatableDisplayComponentNoAnvil(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Component.empty();
        }
        String customId = getCustomItemId(item);
        if (customId != null && !customId.startsWith("minecraft:")) {
            String key = "item." + customId.replace(':', '.');
            return Component.translatable(key).fallback(translationFallback(key, customId));
        }
        if (item.getType().isItem()) {
            return Component.translatable(item.getType().getItemTranslationKey());
        }
        return Component.text(item.getType().name());
    }

    public static Component getServerDisplayComponent(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Component.empty();
        }
        String defaultLocale = I18n.getDefaultLocale();
        String customId = getCustomItemId(item);
        String key;
        if (customId != null && !customId.startsWith("minecraft:")) {
            key = "item." + customId.replace(':', '.');
        } else if (item.getType().isItem()) {
            key = item.getType().getItemTranslationKey();
        } else {
            return Component.text(item.getType().name());
        }
        String translated = translate(key, defaultLocale);
        if (translated.equals(key)) {
            translated = customId != null ? translationFallback(key, customId) : humanizeTranslationKey(key);
        }
        return Component.text(translated);
    }

    private static String translationFallback(String key, String customId) {
        String resolved = I18n.get(key);
        if (resolved != null && !resolved.equals(key) && !resolved.isBlank()) {
            return resolved;
        }
        // No server translation either — show a humanised id (cooked_rice -> "Cooked Rice") so the
        // tooltip stays readable instead of leaking the raw l10n key.
        String suffix = customId.substring(customId.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(suffix.length());
        boolean capitalize = true;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c == '_' || c == '-' || c == '/') {
                out.append(' ');
                capitalize = true;
            } else {
                out.append(capitalize ? Character.toUpperCase(c) : c);
                capitalize = false;
            }
        }
        return out.toString();
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

    public static String translate(String key, String locale) {
        return I18n.get(key, locale);
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
                // If the server lacks a translation, send a translatable component for the resource pack to resolve.
                return Component.translatable(key).fallback(humanizeTranslationKey(key));
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
            return Component.translatable(key).fallback(humanizeTranslationKey(key));
        }

        if (TRANSLATION_KEY_PATTERN.matcher(normalized).matches()) {
            String translated = translate(normalized, locale);
            if (!translated.equals(normalized)) {
                return Component.text(translated);
            }
            if (isVanillaClientTranslationKey(normalized)) {
                return Component.translatable(normalized);
            }
            return Component.translatable(normalized).fallback(humanizeTranslationKey(normalized));
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

    public static String resolveTranslationTags(String text, Player player) {
        if (text == null || text.isEmpty() || text.indexOf('<') < 0) {
            return text;
        }
        String locale = I18n.getPlayerLocale(player);
        Matcher matcher = TRANSLATION_TAG_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String resolved = translate(matcher.group(1).trim(), locale);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
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

        Key itemKey = Key.of("minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT));
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
        if (customId != null) {
            // CraftEngine custom items are identified only by their custom id, never by their
            // base vanilla material, so they cannot match the base material's vanilla id.
            return customId.equalsIgnoreCase(normalized);
        }
        String mmoId = MMOItemsCompat.getItemId(item);
        if (mmoId != null) {
            // MMOItems items carry their identity in custom_data; only their mmoitems id matches,
            // so a vanilla id cannot be satisfied by an MMOItems item of the same base material.
            return mmoId.equalsIgnoreCase(normalized);
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
        String mmoId = customId == null ? MMOItemsCompat.getItemId(item) : null;
        String vanillaId = getVanillaMaterialItemId(item);
        if (customId == null && mmoId == null) {
            return vanillaId == null ? Set.of() : Set.of(vanillaId);
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (customId != null) {
            ids.add(customId);
        }
        if (mmoId != null) {
            ids.add(mmoId);
        }
        if (vanillaId != null) {
            ids.add(vanillaId);
        }
        return Set.copyOf(ids);
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

    public static List<String> getAllItemTagIds(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>(getItemTagIds(item));
        ensureMaterialTagIndex();
        List<String> vanillaTags = MATERIAL_TAG_INDEX.get(item.getType());
        if (vanillaTags != null) {
            tags.addAll(vanillaTags);
        }
        List<String> sorted = new ArrayList<>(tags);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static void ensureMaterialTagIndex() {
        if (!MATERIAL_TAG_INDEX.isEmpty()) {
            return;
        }
        synchronized (MATERIAL_TAG_INDEX) {
            if (!MATERIAL_TAG_INDEX.isEmpty()) {
                return;
            }
            for (Tag<Material> tag : Bukkit.getTags("items", Material.class)) {
                String tagId = tag.getKey().toString();
                Collection<Material> taggedMaterials = tag.getValues();
                if (taggedMaterials.isEmpty()) {
                    continue;
                }
                for (Material material : taggedMaterials) {
                    MATERIAL_TAG_INDEX.computeIfAbsent(material, k -> new ArrayList<>()).add(tagId);
                }
            }
        }
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
        Tag<Material> tag = vanillaItemTagResolveCache
                .computeIfAbsent(tagKey, ItemUtils::resolveVanillaItemTag)
                .orElse(null);
        return tag != null && tag.isTagged(material);
    }

    private static Optional<Tag<Material>> resolveVanillaItemTag(Key tagKey) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(tagKey.toString());
        if (namespacedKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Bukkit.getTag("items", namespacedKey, Material.class));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
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
        // MMOItems items have no CraftEngine id; resolve their identity so station matching and
        // display keys can use the same mmoitems:<TYPE>:<ID> id the recipes are written with.
        String mmoId = MMOItemsCompat.getItemId(item);
        if (mmoId != null) {
            return mmoId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }

    // Identity comparison used by recipe cross-reference / GUI linking: custom items match by custom id
    // (ignoring base material), everything else by material type. Amount and NBT are not compared.
    public static boolean isSameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType().isAir() || b.getType().isAir()) {
            return false;
        }
        String aId = getCustomItemId(a);
        String bId = getCustomItemId(b);
        if (aId != null || bId != null) {
            return aId != null && aId.equals(bId);
        }
        return a.getType() == b.getType();
    }

    public static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean isCustomItem(ItemStack item) {
        return getCustomItemId(item) != null;
    }

    public static boolean isContainerNestingHazard(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (Constants.BLOCK_COOKING_POT.equals(getCustomItemId(item))) {
            return true;
        }
        // Vanilla containers store their contents in components the block_entity_data probe below cannot see:
        // shulker boxes keep a block-entity Container (minecraft:container), bundles keep bundle_contents.
        // Detect both through item meta so a filled shulker/bundle can't smuggle a nested payload into the pot.
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof org.bukkit.block.Container) {
            return true;
        }
        if (meta instanceof org.bukkit.inventory.meta.BundleMeta) {
            return true;
        }
        if (!isAnyCustomItemLoaded()) {
            return false;
        }
        try {
            Item wrapped = BukkitItemManager.instance().wrap(item.clone());
            return CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.BLOCK_ENTITY_DATA) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static ItemStack craftingRemainderOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String customId = getCustomItemId(item);
        com.huidu.farmersdelight.FarmersDelightPlugin plugin =
                com.huidu.farmersdelight.FarmersDelightPlugin.getInstance();
        if (customId != null && plugin != null && plugin.getContainerReturnConfig() != null) {
            return plugin.getContainerReturnConfig().getReturnItem(customId, 1);
        }
        Material remainderType = item.getType().getCraftingRemainingItem();
        if (remainderType != null && !remainderType.isAir()) {
            return new ItemStack(remainderType, 1);
        }
        return switch (item.getType()) {
            case MILK_BUCKET, WATER_BUCKET, LAVA_BUCKET -> new ItemStack(Material.BUCKET, 1);
            case HONEY_BOTTLE -> new ItemStack(Material.GLASS_BOTTLE, 1);
            default -> null;
        };
    }
}

