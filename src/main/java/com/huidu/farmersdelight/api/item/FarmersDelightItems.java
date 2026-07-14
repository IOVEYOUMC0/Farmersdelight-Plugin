package com.huidu.farmersdelight.api.item;

import com.huidu.farmersdelight.api.text.FarmersDelightText;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable, addon-facing item helpers for FarmersDelight. Resolves and matches items across CraftEngine
 * custom items, vanilla materials, and item tags (custom + vanilla).
 *
 * This class lives in com.huidu.farmersdelight.api.**, the only package kept name-stable
 * through obfuscation, so addons may call it directly. Signatures use only Bukkit / java types.
 */
public final class FarmersDelightItems {

    private FarmersDelightItems() {
    }

    /** CraftEngine custom id, else minecraft:<material>, else null for empty/air. */
    public static String idOf(ItemStack item) {
        return ItemUtils.resolveItemId(item);
    }

    /** Builds a stack from a namespaced id (CraftEngine custom item or vanilla material), or null. */
    public static ItemStack create(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    /** True if item resolves to the given namespaced id (CraftEngine or vanilla). */
    public static boolean matchesId(ItemStack item, String itemId) {
        return ItemUtils.matchesItemId(item, itemId);
    }

    /** True if item carries the given tag id (#ns:tag or ns:tag); matches both CraftEngine custom and vanilla tags. */
    public static boolean matchesTag(ItemStack item, String tagId) {
        return ItemUtils.matchesCustomOrVanillaTag(item, tagId);
    }

    /** The item's display name component, localized for player (player may be null). */
    public static Component displayNameOf(ItemStack item, Player player) {
        return ItemUtils.getDisplayComponent(item, player);
    }

    /**
     * Purely-translatable display name (no player viewer): a Component.translatable(key) the
     * receiving client can render in its own locale via the resource pack's lang JSON, with a
     * server-side-resolved .fallback(...) text so clients whose pack lacks the lang entry
     * still see a readable name (server default locale) rather than the raw item.ns.id key.
     *
     * <p>Use for lore lines / chat broadcasts persisted on an ItemStack or sent to many
     * viewers, where displayNameOf(ItemStack, Player) would freeze the text to one player's
     * locale. An anvil-renamed name is honoured as-is.
     */
    public static Component translatableDisplayNameOf(ItemStack item) {
        return ItemUtils.getTranslatableDisplayComponent(item);
    }

    /**
     * Same as translatableDisplayNameOf(ItemStack) but ignores any player-applied anvil rename
     * — the visible name follows each viewer's client locale via Component.translatable(key),
     * with a server-resolved .fallback(...) so missing-pack clients still see readable text.
     *
     * <p>Use for lore lines persisted on an ItemStack where the embedded item name should
     * switch with each viewer's language but must not freeze to one player's anvil typo.
     */
    public static Component translatableDisplayNameOfNoAnvilOf(ItemStack item) {
        return ItemUtils.getTranslatableDisplayComponentNoAnvil(item);
    }

    /**
     * Display name resolved entirely on the server in the server's default locale. Ignores any
     * player-applied anvil rename and never returns a Component.translatable — the text is
     * fully baked here so every client renders the same characters regardless of its own locale or
     * resource-pack state.
     *
     * <p>Use for lore lines persisted on an ItemStack when the visible name must match the
     * server language (and stay stable across viewers) rather than the receiver's client locale.
     */
    public static Component serverDisplayNameOf(ItemStack item) {
        return ItemUtils.getServerDisplayComponent(item);
    }

    /** All namespaced ids the item resolves to (custom id and/or vanilla material). */
    public static Set<String> idsOf(ItemStack item) {
        return ItemUtils.getItemIds(item);
    }

    /** All tag ids the item carries (CraftEngine custom tags + vanilla tags). */
    public static Set<String> tagIdsOf(ItemStack item) {
        return ItemUtils.getItemTagIds(item);
    }

    /** True if item is a CraftEngine custom item (not a plain vanilla material). */
    public static boolean isCustomItem(ItemStack item) {
        return ItemUtils.isCustomItem(item);
    }

    /** The CraftEngine custom id of item, or null when it is a plain vanilla material / empty. */
    public static String customIdOf(ItemStack item) {
        return ItemUtils.getCustomItemId(item);
    }

    /**
     * The crafting remainder for a single item (e.g. bucket from a milk bucket, glass bottle from a
     * honey bottle, or a CraftEngine-configured container return), or null when none. Mirrors FarmersDelight's
     * own stations, so addon recipes can return containers consistently.
     */
    public static ItemStack craftingRemainderOf(ItemStack item) {
        return ItemUtils.craftingRemainderOf(item);
    }

    /**
     * Sets item's display name and lore from templates, rendered per viewer via
     * FarmersDelightText (string/Component placeholders, <l10n:> tags, CraftEngine glyphs,
     * MiniMessage + legacy colors), with the vanilla lore italic stripped. Null name/lore templates are left
     * untouched. Use this so addon item tooltips and GUI icons share FarmersDelight's rendering.
     */
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

    /**
     * Builds a GUI icon from itemId (CraftEngine custom item or vanilla material) with a rendered name
     * and lore (see applyDisplay). Returns null when itemId can't be resolved.
     */
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
