package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeIngredientIcons {

    // Item build cache. Values are cloned in and out so callers can freely mutate the returned meta.
    private static final Map<Key, ItemStack> itemCache = new ConcurrentHashMap<>();
    // Resolved tag ingredient option list (build cost O(items x excludedTags x items) plus CE item creation)
    // is fixed per tag ingredient; cache the result and clear on reload. Callers get clones.
    private static final Map<RecipeIngredient.Tag, List<ItemStack>> tagOptionsCache = new ConcurrentHashMap<>();
    // Choice ingredient display options are likewise fixed. Choice is a record (value equality), safe as a key.
    private static final Map<RecipeIngredient.Choice, List<ItemStack>> choiceOptionsCache = new ConcurrentHashMap<>();
    private static final Collator DISPLAY_NAME_COLLATOR = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

    private RecipeIngredientIcons() {
    }

    public static void clearCaches() {
        itemCache.clear();
        tagOptionsCache.clear();
        choiceOptionsCache.clear();
    }

    public static void clearItemCache() {
        itemCache.clear();
    }

    public static ItemStack createItemFromKey(Key key) {
        ItemStack cached = itemCache.get(key);
        if (cached != null) return cached.clone();
        try {
            ItemStack customItem = ItemUtils.createItem(key);
            if (customItem != null) {
                itemCache.put(key, customItem.clone());
                return customItem;
            }

            NamespacedKey materialKey = NamespacedKey.fromString(key.toString());
            if (materialKey != null) {
                Material material = Registry.MATERIAL.get(materialKey);
                if (material != null) {
                    ItemStack item = new ItemStack(material);
                    itemCache.put(key, item.clone());
                    return item;
                }
            }
        } catch (Exception e) {
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin != null && plugin.isDebugEnabled("gui")) {
                plugin.getLogger().fine(I18n.formatConsole("gui_runtime.create_item_failed", "key", key));
            }
        }
        // On resolution failure (no hit in either CE or the vanilla registry), cache the BARRIER sentinel too, to avoid
        // re-running the CraftEngine + registry lookup every time. All callers treat BARRIER as "unresolved/skip", and it is invalidated when the cache is cleared.
        ItemStack barrier = new ItemStack(Material.BARRIER);
        itemCache.put(key, barrier.clone());
        return barrier;
    }

    public static String buildIngredientDisplayKey(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }

    public static List<ItemStack> sortIngredientDisplayItems(Collection<ItemStack> items) {
        List<ItemStack> sorted = new ArrayList<>(items);
        sorted.sort((left, right) -> {
            String leftName = ItemUtils.getDisplayName(left, (String) null);
            String rightName = ItemUtils.getDisplayName(right, (String) null);
            int displayCompare = DISPLAY_NAME_COLLATOR.compare(leftName, rightName);
            if (displayCompare != 0) {
                return displayCompare;
            }

            String leftId = buildIngredientDisplayKey(left);
            String rightId = buildIngredientDisplayKey(right);
            int keyCompare = leftId.compareTo(rightId);
            if (keyCompare != 0) {
                return keyCompare;
            }

            return left.getType().name().compareTo(right.getType().name());
        });
        return sorted;
    }

    public static List<ItemStack> resolveTagIngredientOptions(RecipeIngredient.Tag tagIngredient) {
        List<ItemStack> cached = tagOptionsCache.computeIfAbsent(tagIngredient, RecipeIngredientIcons::computeTagIngredientOptions);
        List<ItemStack> copy = new ArrayList<>(cached.size());
        for (ItemStack item : cached) {
            copy.add(item.clone());
        }
        return copy;
    }

    public static List<ItemStack> resolveTagIngredientOptionsPreview(RecipeIngredient.Tag tagIngredient, int limit) {
        List<ItemStack> cached = tagOptionsCache.computeIfAbsent(tagIngredient, RecipeIngredientIcons::computeTagIngredientOptions);
        int count = Math.min(limit, cached.size());
        List<ItemStack> copy = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            copy.add(cached.get(i).clone());
        }
        return copy;
    }

    public static int resolveTagIngredientOptionsSize(RecipeIngredient.Tag tagIngredient) {
        return tagOptionsCache.computeIfAbsent(tagIngredient, RecipeIngredientIcons::computeTagIngredientOptions).size();
    }

    public static List<ItemStack> resolveIngredientOptions(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            ItemStack item = itemIngredient.createStack();
            if (item == null || item.getType().isAir() || item.getType() == Material.BARRIER) {
                return List.of();
            }
            return List.of(item);
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return resolveTagIngredientOptions(tagIngredient);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            // Hit the cache (the computed result is not cloned), clone each and return, with semantics identical to resolveTagIngredientOptions,
            // avoiding shared ItemStacks polluting the cache when placed in the GUI and given meta.
            List<ItemStack> cached = choiceOptionsCache.computeIfAbsent(choiceIngredient, RecipeIngredientIcons::computeChoiceOptions);
            List<ItemStack> copy = new ArrayList<>(cached.size());
            for (ItemStack item : cached) {
                copy.add(item.clone());
            }
            return copy;
        }

        return List.of();
    }

    private static List<ItemStack> computeTagIngredientOptions(RecipeIngredient.Tag tagIngredient) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, ItemStack> uniqueDisplays = new LinkedHashMap<>();
        for (UniqueKey uniqueKey : plugin.getCraftEngine().itemManager().itemIdsByTag(tagIngredient.key())) {
            if (tagIngredient.excludedItems().contains(uniqueKey.key())) {
                continue;
            }
            boolean blockedByTag = false;
            for (Key excludedTag : tagIngredient.excludedTags()) {
                if (plugin.getCraftEngine().itemManager().itemIdsByTag(excludedTag).stream()
                        .anyMatch(candidate -> candidate.key().equals(uniqueKey.key()))) {
                    blockedByTag = true;
                    break;
                }
            }
            if (blockedByTag) {
                continue;
            }
            ItemStack item = createItemFromKey(uniqueKey.key());
            if (item == null || item.getType().isAir() || item.getType() == Material.BARRIER) {
                continue;
            }
            uniqueDisplays.putIfAbsent(uniqueKey.toString(), item);
        }
        for (ItemStack item : ItemUtils.createVanillaTagDisplayItems(
                tagIngredient.key(),
                tagIngredient.excludedItems(),
                tagIngredient.excludedTags())) {
            uniqueDisplays.putIfAbsent(buildIngredientDisplayKey(item), item);
        }
        // Expand registered tag (c:... and addon) members from the central registry; CE has no native c: tags.
        if (com.huidu.farmersdelight.util.CommonTagResolver.isCommonTag(tagIngredient.key().toString())) {
            for (String memberId : com.huidu.farmersdelight.util.CommonTagResolver.getMembers(tagIngredient.key())) {
                // ItemUtils.createItem routes mmoitems:/custom/vanilla ids, unlike createItemFromKey.
                ItemStack item = ItemUtils.createItem(memberId);
                if (item == null || item.getType().isAir() || item.getType() == Material.BARRIER) {
                    continue;
                }
                uniqueDisplays.putIfAbsent(buildIngredientDisplayKey(item), item);
            }
        }
        return sortIngredientDisplayItems(uniqueDisplays.values());
    }

    private static List<ItemStack> computeChoiceOptions(RecipeIngredient.Choice choiceIngredient) {
        Map<String, ItemStack> uniqueDisplays = new LinkedHashMap<>();
        for (RecipeIngredient option : choiceIngredient.options()) {
            for (ItemStack display : resolveIngredientOptions(option)) {
                uniqueDisplays.putIfAbsent(buildIngredientDisplayKey(display), display);
            }
        }
        return sortIngredientDisplayItems(uniqueDisplays.values());
    }
}
