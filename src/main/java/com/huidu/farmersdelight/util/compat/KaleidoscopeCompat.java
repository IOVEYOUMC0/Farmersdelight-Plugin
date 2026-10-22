package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Optional Kaleidoscope Cookery integration. Only the recipe item's own data is read: the item type is shared by
 * every recorded recipe, so its CraftEngine id identifies the item while the recorded ingredients live on the
 * item stack in that plugin's persistent data. Nothing here writes to the other plugin's data or registries.
 */
public final class KaleidoscopeCompat {

    private static final String PLUGIN_NAME = "KaleidoscopeCookeryPlugin";
    private static final String RECIPE_ITEM_ID = "kaleidoscopecookery:recipe_item_has_recipe";
    private static final NamespacedKey INGREDIENTS_KEY =
            new NamespacedKey("kaleidoscopecookeryplugin", "recipe_ingredients");
    private static final int MAX_INGREDIENTS = 9;
    private static final int MAX_ENCODED_LENGTH = 1024;
    // Recorded ids are CraftEngine keys written by the other plugin; a forged segment is filtered out here
    // instead of being handed to the item resolver.
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    private static volatile boolean autoFill;

    private KaleidoscopeCompat() {
    }

    /** Re-reads the switches from the config; called on every config load so a reload applies without a restart. */
    public static void refresh(FarmersDelightPlugin plugin) {
        if (plugin == null) {
            autoFill = false;
            return;
        }
        boolean enabled = plugin.getConfigBoolean(true, "compatibility.kaleidoscope.enabled");
        autoFill = enabled && plugin.getConfigBoolean(true, "compatibility.kaleidoscope.recipe-auto-fill");
    }

    public static boolean isAutoFillEnabled() {
        return autoFill;
    }

    /** Whether the other plugin is loaded; informational only, the item id itself is the real gate. */
    public static boolean isInstalled() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /** True for a Kaleidoscope recipe item the integration should fill from. */
    public static boolean isRecipeItem(ItemStack stack) {
        return autoFill && stack != null && !stack.getType().isAir()
                && RECIPE_ITEM_ID.equals(ItemUtils.getCustomItemId(stack));
    }

    /** The recorded ingredient ids, or an empty list when the item carries none or the data is unusable. */
    public static List<String> ingredients(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return List.of();
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? List.of()
                : parseIngredients(meta.getPersistentDataContainer().get(INGREDIENTS_KEY, PersistentDataType.STRING));
    }

    // Kept separate from the Bukkit read so the junk-tolerant rules stay unit-testable: the persistent data can
    // be forged by a player, so a bad segment is skipped instead of failing the whole interaction.
    static List<String> parseIngredients(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            return List.of();
        }
        String[] segments = encoded.split(",", MAX_INGREDIENTS + 1);
        if (segments.length > MAX_INGREDIENTS) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (ids.size() >= MAX_INGREDIENTS) {
                break;
            }
            String trimmed = segment.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || !ITEM_ID.matcher(trimmed).matches()) {
                continue;
            }
            ids.add(trimmed);
        }
        return List.copyOf(ids);
    }
}
