package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
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

    /**
     * Returns the custom item id; null when the stack is not a CE custom item.
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
     * itemId item id in the form namespace:item_name
     * the created item stack; null when the id cannot be resolved
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

    /** True once CraftEngine has loaded at least one custom item (any namespace). A readiness probe that CE
     * finished its item-load pass, independent of any specific item id or namespace: unlike probing one item,
     * this survives an admin deleting that item or repacking the plugin's items under a different namespace. */
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

    /** True when the item is a CraftEngine custom item that carries the given item tag. */
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

    /** Drops the memoized CE item builds so a CE/config reload rebuilds them from the new definitions. */
    public static void clearItemCache() {
        itemBuildCache.clear();
    }

    /**
     * Pre-builds every loaded CE item in namespace once (or all namespaces when null), priming the
     * item-build cache and paying CraftEngine's one-time global item-build inits (ASM proxies, MiniMessage /
     * serializer setup) off the first-interaction hot path. Pure computation — safe on the global/main thread.
     *
     * the number of items successfully built
     */
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

    /**
     * Serialize a Bukkit item to a CraftEngine NBT tag via CE's ItemStackUtils.
     */
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

    /** Returns a purely-translatable display Component for item so the receiving client renders
     *  it in its own locale via the resource pack lang files. Use this for lore lines that ship to many
     *  viewers (e.g. the packed-cooking-pot tooltip), where the standard #getDisplayComponent —
     *  which bakes CE items' <l10n:> names to the server's default locale — would freeze the
     *  text to one language.
     *
     *  An anvil-renamed name (set on displayName()) is honoured as-is. Otherwise:
     *  CE items map to item.<namespace>.<path> (matches the <l10n:> key convention used
     *  by the project's resource pack); vanilla items use their native translation key.
     *
     *  Both branches embed a server-default-locale .fallback(...) so clients whose resource
     *  pack lacks the lang JSON entry don't see a raw item.farmersdelight.foo key — they render
     *  the server-resolved text instead. Clients whose pack DOES have the key still get per-locale
     *  translation. */
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

    /** Same as #getTranslatableDisplayComponent(ItemStack) but does NOT honour a player-applied
     *  anvil rename — always returns the Component.translatable(key).fallback(server-text) for
     *  the item's CE/vanilla id. Use for lore lines where the embedded item name should follow each
     *  viewer's client locale yet stay independent of one player's anvil typo. */
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

    /** Display name resolved entirely on the server in the server's default locale. Ignores any
     *  player-applied anvil rename and never returns a Component.translatable — the text is
     *  fully baked here so all clients render the same characters regardless of locale or pack state.
     *  For custom items keys derive from the CE id (item.<ns>.<id>); for vanilla items from
     *  Material.getItemTranslationKey(). */
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

    /** Server-resolved fallback text for a CE item's translation key, used when the receiving client's
     *  resource pack lacks the key. Falls back to FD's I18n (loaded from lang/<locale>.yml), then
     *  to a humanised id, never to the raw translation key. */
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

    /** Server-resolved plain text for key. Routes through I18n.get() fallback chain (CraftEngine + Adventure translation built in). */
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
                // 服务端找不到翻译时，发送可翻译组件给客户端，利用资源包 lang JSON 完成翻译
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

    /**
     * Replaces translation tags (<l10n:key> / <lang:key> / <i18n:key>) in text
     * with their localized strings for player's locale (falling back to the default/en locale, then the
     * raw key). Non-tag content is left untouched, so the result can still carry MiniMessage markup. Resolution
     * goes through the same chain as item names (plugin lang files -> CraftEngine translations -> GlobalTranslator).
     */
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

    public static List<String> getAllItemTagIds(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>(getItemTagIds(item));
        for (Tag<Material> tag : Bukkit.getTags("items", Material.class)) {
            if (tag.isTagged(item.getType())) {
                tags.add(tag.getKey().toString());
            }
        }
        List<String> sorted = new ArrayList<>(tags);
        sorted.sort(String::compareTo);
        return sorted;
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
        return "minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }

    /** Normalize null or blank strings to null, otherwise return the trimmed value. */
    public static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True if item is a CraftEngine custom item (not a plain vanilla material). */
    public static boolean isCustomItem(ItemStack item) {
        return getCustomItemId(item) != null;
    }

    /**
     * True when the item must not be inserted into a cooking pot's input or container slots: a cooking pot
     * itself (empty or packed), or any item already carrying a stored block-entity inventory (a skillet,
     * basket, shulker box or other packed container). A cooking pot saves its whole inventory onto the
     * picked-up item as NBT, so nesting a container inside it lets a player grow that NBT recursively into a
     * client-crashing "NBT bomb". Plain ingredients and meal containers (bowls, bottles) carry neither marker
     * and are unaffected.
     */
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

    /**
     * The crafting remainder for a single item: the CraftEngine container-return mapping for custom
     * items, then the vanilla Material#getCraftingRemainingItem(), then the milk/water/lava bucket and
     * honey-bottle special cases. Returns null when the item leaves no remainder. Mirrors the cooking pot.
     */
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

