package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.CeItemInterop;
import com.huidu.farmersdelight.block.behavior.BlockBehaviorConfigs;
import com.huidu.farmersdelight.block.behavior.ConfiguredBlockSet;
import com.huidu.farmersdelight.config.ContainerReturnConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseRemainder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
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
import org.bukkit.block.Container;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
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
    // A CraftEngine item's declared tags, as the strings the tag comparisons use, keyed by its item id.
    // Both the set copy and one String per tag were being rebuilt on every membership test; the answer
    // only changes when CraftEngine reloads, which is exactly when this map is cleared.
    private static final Map<Key, Set<String>> customItemTagIdCache = new ConcurrentHashMap<>();
    private static final List<Material> ITEM_MATERIALS = new ArrayList<>();
    // Pre-built reverse index: material → all vanilla item-tag IDs it belongs to.
    // Replaces the O(n) getAllItemTagIds iteration over every registered tag per call.
    // Published as a whole once built. "Map is non-empty" cannot serve as the built flag: the first
    // computeIfAbsent makes it non-empty while most materials are still missing, and a reader would then
    // see a half-built index and iterate an ArrayList that the builder is still appending to.
    private static volatile Map<Material, List<String>> MATERIAL_TAG_INDEX = Map.of();
    private static final Object MATERIAL_TAG_INDEX_LOCK = new Object();

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

    /** Returns the Bukkit stack represented by a CraftEngine interaction hand. */
    public static ItemStack getItemInHand(Player player, InteractionHand hand) {
        if (player == null) {
            return null;
        }
        return hand == InteractionHand.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    /** Sends the matching arm animation for a CraftEngine interaction hand. */
    public static void swingHand(Player player, InteractionHand hand) {
        if (player == null) {
            return;
        }
        if (hand == InteractionHand.OFF_HAND) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
    }

    /** Returns the Bukkit player already wrapped by CraftEngine, or null for non-Bukkit contexts. */
    public static org.bukkit.entity.Player getBukkitPlayer(
            net.momirealms.craftengine.core.entity.player.Player player) {
        return player != null && player.platformPlayer() instanceof Player bukkitPlayer
                ? bukkitPlayer : null;
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

    /** The declared tags of a CraftEngine item as strings, built once per item id. */
    private static Set<String> customItemTagIds(Key key) {
        return customItemTagIdCache.computeIfAbsent(key, id -> {
            Set<Key> tags = getCustomItemTags(id);
            if (tags.isEmpty()) {
                return Set.of();
            }
        Set<String> ids = new LinkedHashSet<>(tags.size());
            for (Key tag : tags) {
                ids.add(tag.toString());
            }
            return Set.copyOf(ids);
        });
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
            String tagString = itemId.substring(1);
            // Common tags (c:...) expand through the mapping table; CE / vanilla tags expand via CraftEngine.
            if (CommonTagResolver.isCommonTag(tagString)) {
                // CE items declare c: tags in their settings.tags instead of tags.yml, so merge
                // CraftEngine members onto the mapping-table members to keep every tagged item visible.
                Set<UniqueKey> seen = new HashSet<>();
                for (String memberId : CommonTagResolver.getMembers(tagString)) {
                    ItemStack item = createItem(memberId);
                    if (item != null && !item.getType().isAir()) {
                        items.add(item);
                    }
                }
                CommonTagResolver.getMembers(tagString).forEach(memberId -> seen.add(UniqueKey.create(Key.of(memberId))));
                for (UniqueKey member : BukkitItemManager.instance().itemIdsByTag(Key.of(tagString))) {
                    if (seen.add(member)) {
                        ItemStack item = createItem(member.toString());
                        if (item != null && !item.getType().isAir()) {
                            items.add(item);
                        }
                    }
                }
                return items;
            }
            Key tag = Key.of(tagString);
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
        customItemTagIdCache.clear();
        vanillaTagCache.clear();
        vanillaItemTagResolveCache.clear();
        MATERIAL_TAG_INDEX = Map.of();
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

    public static CompoundTag saveBukkitItemAsTag(ItemStack item) {
        return ItemStackUtils.saveBukkitItemAsTag(item);
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

        String materialName = item.getType().name().toLowerCase(Locale.ROOT);
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
            Locale loc = locale != null && !locale.isEmpty()
                    ? Locale.forLanguageTag(locale.replace('_', '-'))
                    : Locale.getDefault();
            Component rendered = GlobalTranslator.render(component, loc);
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

        // A custom item may use a vanilla material as its render/base type, but that does not make
        // it a member of the material's vanilla tags. Custom tags are checked by getItemTagIds().
        if (getCustomItemId(item) != null || MMOItemsCompat.getItemId(item) != null) {
            return false;
        }

        // Past the check above the item is plain vanilla, so the one id it matches by is its material id:
        // a string build rather than a CraftEngine wrap. Resolving it once here is the point — every
        // matchesItemId below would otherwise re-resolve the custom id, once per candidate id, only to
        // find it null again.
        String vanillaId = getVanillaMaterialItemId(item);
        if (vanillaId == null) {
            return false;
        }
        for (Key excluded : excludedItems) {
            if (excluded != null && vanillaId.equalsIgnoreCase(excluded.toString())) {
                return false;
            }
        }
        boolean inVanillaTag = isVanillaMaterialInTag(item.getType(), tagKey);
        // CommonTagResolver stores members lower-cased and the material id is already lower-case, so
        // membership is an exact set lookup instead of a scan over every member of the tag.
        boolean inRegisteredTag = CommonTagResolver.getMembers(tagKey).contains(vanillaId);
        if (!inVanillaTag && !inRegisteredTag) {
            return false;
        }
        for (Key excludedTag : excludedTags) {
            if (isVanillaMaterialInTag(item.getType(), excludedTag)
                    || CommonTagResolver.getMembers(excludedTag).contains(vanillaId)) {
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
        LinkedHashSet<String> ids = new LinkedHashSet<>();
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
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        // CE-declared tags (self-identifying, e.g. a knife declaring farmersdelight:tools/knives).
        String customId = getCustomItemId(item);
        if (customId != null) {
            tags.addAll(customItemTagIds(Key.of(customId)));
        }
        // Registered tags (c:... conventions plus any addon-registered tags) are resolved against
        // the item's authoritative identity. Do not include the base material for custom items: a CE
        // custom apple rendered as cooked beef must not inherit cooked-beef recipe tags.
        String identity = customId;
        if (identity == null) {
            identity = MMOItemsCompat.getItemId(item);
        }
        if (identity == null) {
            identity = getVanillaMaterialItemId(item);
        }
        tags.addAll(CommonTagResolver.getTagsForItemId(identity));
        return Set.copyOf(tags);
    }

    public static List<String> getAllItemTagIds(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>(getItemTagIds(item));
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
        synchronized (MATERIAL_TAG_INDEX_LOCK) {
            if (!MATERIAL_TAG_INDEX.isEmpty()) {
                return;
            }
            Map<Material, List<String>> building = new EnumMap<>(Material.class);
            for (Tag<Material> tag : Bukkit.getTags("items", Material.class)) {
                String tagId = tag.getKey().toString();
                Collection<Material> taggedMaterials = tag.getValues();
                if (taggedMaterials.isEmpty()) {
                    continue;
                }
                for (Material material : taggedMaterials) {
                    building.computeIfAbsent(material, k -> new ArrayList<>()).add(tagId);
                }
            }
            Map<Material, List<String>> published = new EnumMap<>(Material.class);
            building.forEach((material, tags) -> published.put(material, List.copyOf(tags)));
            MATERIAL_TAG_INDEX = Map.copyOf(published);
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
        // toString outside the loop: it was being rebuilt once per tag the item carries.
        String wanted = tagKey.toString();
        for (String tag : getItemTagIds(item)) {
            if (tag.equalsIgnoreCase(wanted)) {
                return true;
            }
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
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }

    public static ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }

    // Identity comparison used by recipe cross-reference / GUI linking: any item with its own identity
    // (a CraftEngine id or an mmoitems:<TYPE>:<ID>) matches that identity instead of its base material;
    // everything else matches by material type. Amount and NBT are not compared.
    public static boolean isSameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType().isAir() || b.getType().isAir()) {
            return false;
        }
        String aId = resolveItemId(a);
        String bId = resolveItemId(b);
        return aId != null && aId.equals(bId);
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
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof Container) {
            return true;
        }
        if (meta instanceof BundleMeta) {
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
        return craftingRemainderOf(item, null);
    }

    /**
     * The item a stack leaves behind once it is consumed: a water bucket becomes a bucket, a milk bottle a
     * glass bottle. Resolution order, most explicit first:
     * - the container-returns map, by CE custom id and then by vanilla item id (operator override),
     * - the item's own CE craft-remainder (settings.craft-remainder): a fixed remainder always
     *       answers, a recipe_based one answers through recipeId or through its fallback,
     * - the item's use-remainder component (what eating/drinking it leaves behind),
     * - the vanilla crafting remainder of the item's material — for a CE item that is its base material's,
     *       so a CE drink built on minecraft:honey_bottle returns a glass bottle with no configuration,
     * - the bucket/bottle fallback for containers vanilla declares no remainder for.
     * Returns null when the stack leaves nothing behind.
     *
     * @param recipeId id of the recipe consuming the item, matched by recipe_based CE remainders; null when no
     *                 recipe is involved (a fixed CE remainder still answers, a recipe_based one uses its
     *                 fallback).
     */
    public static ItemStack craftingRemainderOf(ItemStack item, String recipeId) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemStack configured = configuredCraftingRemainder(item);
        if (configured != null) {
            return configured;
        }
        ItemStack declared = declaredCraftingRemainder(item, recipeId);
        if (declared != null) {
            return declared;
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

    // container-returns, by custom id first and then by the vanilla id, so one map covers both kinds of item.
    private static ItemStack configuredCraftingRemainder(ItemStack item) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        ContainerReturnConfig config = plugin == null ? null : plugin.getContainerReturnConfig();
        if (config == null) {
            return null;
        }
        String customId = getCustomItemId(item);
        if (customId != null) {
            ItemStack configured = config.getReturnItem(customId, 1);
            if (configured != null) {
                return configured;
            }
        }
        String vanillaId = getVanillaMaterialItemId(item);
        return vanillaId == null ? null : config.getReturnItem(vanillaId, 1);
    }

    // What the item definition itself declares: craft-remainder first (recipe aware), then the use-remainder
    // component. The component is read through the Paper API, which resolves it for vanilla items (a stew's
    // bowl) and for CE items that declare it in their pack data alike; CraftEngine's own wrapper only answers
    // for items that carry the component as a patch, and vanilla `Material#getCraftingRemainingItem` does not
    // cover the data-driven remainders (stews, potions) at all.
    private static ItemStack declaredCraftingRemainder(ItemStack item, String recipeId) {
        ItemStack craftRemainder = ceCraftRemainder(item, recipeId);
        return craftRemainder != null ? craftRemainder : useRemainderComponent(item);
    }

    private static ItemStack ceCraftRemainder(ItemStack item, String recipeId) {
        try {
            Item wrapped = BukkitItemManager.instance().wrap(item.clone());
            if (wrapped == null) {
                return null;
            }
            var definition = wrapped.getDefinition().orElse(null);
            var craftRemainder = definition == null ? null : definition.settings().craftRemainder();
            if (craftRemainder == null) {
                return null;
            }
            Item result = craftRemainder.remainder(recipeId == null ? null : Key.of(recipeId), wrapped);
            if (result == null) {
                return null;
            }
            ItemStack stack = CeItemInterop.toBukkitStack(result);
            return stack != null && !stack.getType().isAir() && stack.getAmount() > 0 ? stack : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static ItemStack useRemainderComponent(ItemStack item) {
        try {
            UseRemainder useRemainder = item.getData(DataComponentTypes.USE_REMAINDER);
            if (useRemainder == null) {
                return null;
            }
            ItemStack converted = useRemainder.transformInto();
            return converted != null && !converted.getType().isAir() ? converted : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
