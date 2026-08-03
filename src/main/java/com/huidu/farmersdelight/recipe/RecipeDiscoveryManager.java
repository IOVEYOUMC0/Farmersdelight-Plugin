package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Action;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Source;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player recipe "discovery" state for the recipe books. When enabled, recipes start locked and are
 * revealed once a player unlocks them — via obtaining an ingredient/result (config-toggled) or via the API
 * (e.g. an addon unlocks on advancement or on craft). Locking only affects the books' DISPLAY; it never
 * blocks actual crafting at a station.
 *
 * Synthetic type ids identify FarmersDelight's own recipes: TYPE_COOKING_POT / TYPE_CUTTING_BOARD. Addon
 * recipes use their RecipeType id. Disabled by default; when disabled every recipe reads as unlocked, so the
 * books behave exactly as before.
 */
public final class RecipeDiscoveryManager {

    public static final String TYPE_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "farmersdelight:cutting_board";

    private static final String DATA_FILE = "recipe-discovery.yml";

    private final FarmersDelightPlugin plugin;
    // playerId -> set of "<typeId> <recipeId>" keys (space-separated; neither id contains a space).
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();
    // itemId -> keys of recipes whose result or an exact-item ingredient is that item (obtain trigger). Lazy.
    private volatile Map<String, Set<String>> obtainIndex;

    // Written by readConfig on the reload thread and read from region threads (books, obtain trigger,
    // command), so every one of them needs the happens-before edge a volatile read gives.
    private volatile boolean enabled;
    private volatile boolean hideLocked;     // true = omit locked recipes; false = show a placeholder
    private volatile boolean unlockOnObtain; // unlock when a recipe's result / exact ingredient is obtained
    private volatile boolean notifyOnUnlock; // chat message when a recipe unlocks
    private volatile Material lockedIcon = Material.BARRIER;
    private volatile boolean dirty;

    public RecipeDiscoveryManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Full init on plugin enable: read config and load saved per-player state from disk. */
    public void load() {
        readConfig();
        loadData();
    }

    /** On /fd reload: flush any unsaved state, re-read config toggles, and rebuild the obtain index.
     * Does NOT reload per-player data from disk (that would drop unsaved in-memory unlocks). */
    public void reloadConfig() {
        save();
        readConfig();
        obtainIndex = null;
    }

    private void readConfig() {
        ConfigurationSection section = plugin.getFirstConfigSection("recipes.discovery", "recipe-discovery");
        enabled = section != null && section.getBoolean("enabled", false);
        String lockedDisplay = section == null ? "placeholder" : section.getString("locked-display", "placeholder");
        hideLocked = "hidden".equalsIgnoreCase(lockedDisplay);
        unlockOnObtain = section == null || section.getBoolean("unlock-on-obtain", true);
        notifyOnUnlock = section == null || section.getBoolean("notify", true);
        Material icon = section == null ? null : Material.matchMaterial(section.getString("locked-icon", "BARRIER"));
        lockedIcon = icon != null ? icon : Material.BARRIER;
    }

    /** The placeholder item shown in a book for a locked recipe (placeholder display mode), localized for the viewer. */
    public ItemStack lockedPlaceholder(Player viewer) {
        ItemStack item = new ItemStack(lockedIcon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(I18n.getComponent("recipe-discovery.locked-name", viewer));
            meta.lore(List.of(I18n.getComponent("recipe-discovery.locked-lore", viewer)));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when locked recipes should be omitted from the book entirely (else a placeholder is shown). */
    public boolean hidesLocked() {
        return hideLocked;
    }

    /** True if the player may see this recipe: always when discovery is off, else only once unlocked. */
    public boolean isUnlocked(UUID playerId, String typeId, String recipeId) {
        if (!enabled) {
            return true;
        }
        if (playerId == null || typeId == null || recipeId == null) {
            return true;
        }
        Set<String> set = unlocked.get(playerId);
        return set != null && set.contains(key(typeId, recipeId));
    }

    /** Marks a recipe unlocked for a player. Returns true if it was newly unlocked. */
    public boolean unlock(UUID playerId, String typeId, String recipeId) {
        return unlock(playerId, typeId, recipeId, Source.API);
    }

    /** Marks a recipe unlocked for a player, attributing the change to the given source in the fired event. */
    public boolean unlock(UUID playerId, String typeId, String recipeId, Source source) {
        if (playerId == null || typeId == null || recipeId == null) {
            return false;
        }
        Set<String> set = unlocked.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        // add() runs after computeIfAbsent has returned, so no map bin lock is held while the event fires.
        boolean added = set.add(key(typeId, recipeId));
        if (added) {
            dirty = true;
            fireChanged(playerId, typeId, recipeId, Action.UNLOCK, source);
        }
        return added;
    }

    /** Re-locks a recipe for a player. */
    public void lock(UUID playerId, String typeId, String recipeId) {
        lock(playerId, typeId, recipeId, Source.API);
    }

    /** Re-locks a recipe for a player. Returns true if it had been unlocked and is now locked again. */
    public boolean lock(UUID playerId, String typeId, String recipeId, Source source) {
        if (playerId == null || typeId == null || recipeId == null) {
            return false;
        }
        Set<String> set = unlocked.get(playerId);
        boolean removed = set != null && set.remove(key(typeId, recipeId));
        if (removed) {
            dirty = true;
            fireChanged(playerId, typeId, recipeId, Action.LOCK, source);
        }
        return removed;
    }

    /** Unlocks every known recipe (FarmersDelight + addon) for a player. */
    public int unlockAll(UUID playerId) {
        return unlockAll(playerId, Source.API);
    }

    /** Unlocks every known recipe of every type; returns how many were newly unlocked. */
    public int unlockAll(UUID playerId, Source source) {
        if (playerId == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, List<String>> entry : allRecipeKeysByType().entrySet()) {
            for (String recipeId : entry.getValue()) {
                if (unlock(playerId, entry.getKey(), recipeId, source)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Unlocks every known recipe of one type; returns how many were newly unlocked. */
    public int unlockAllOfType(UUID playerId, String typeId, Source source) {
        if (playerId == null || typeId == null) {
            return 0;
        }
        int count = 0;
        for (String recipeId : allRecipeKeysByType().getOrDefault(typeId, List.of())) {
            if (unlock(playerId, typeId, recipeId, source)) {
                count++;
            }
        }
        return count;
    }

    /** Re-locks every known recipe of every type; returns how many were actually re-locked. Keys of types
     *  that are not currently registered (an addon that is temporarily absent) are left untouched rather
     *  than discarded, so removing an addon for one restart does not wipe its players' progress. */
    public int lockAll(UUID playerId, Source source) {
        if (playerId == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, List<String>> entry : allRecipeKeysByType().entrySet()) {
            for (String recipeId : entry.getValue()) {
                if (lock(playerId, entry.getKey(), recipeId, source)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Re-locks every known recipe of one type; returns how many were actually re-locked. */
    public int lockAllOfType(UUID playerId, String typeId, Source source) {
        if (playerId == null || typeId == null) {
            return 0;
        }
        int count = 0;
        for (String recipeId : allRecipeKeysByType().getOrDefault(typeId, List.of())) {
            if (lock(playerId, typeId, recipeId, source)) {
                count++;
            }
        }
        return count;
    }

    /** True when the type is registered and declares this recipe id. */
    public boolean isKnownRecipe(String typeId, String recipeId) {
        if (typeId == null || recipeId == null) {
            return false;
        }
        return allRecipeKeysByType().getOrDefault(typeId, List.of()).contains(recipeId);
    }

    /**
     * Announces a committed state change. The state itself is concurrent-map work that any thread may
     * perform, but PluginManager.callEvent rejects a synchronous event dispatched from a non-tick thread by
     * throwing. Calling it inline would therefore let an addon that unlocks from an async callback mutate
     * the map, mark it dirty and then blow up half-committed. Off a tick thread the finished event is handed
     * to the global region instead, so the caller always returns normally and listeners still see it.
     */
    private void fireChanged(UUID playerId, String typeId, String recipeId, Action action, Source source) {
        FarmersDelightRecipeDiscoveryEvent event =
                new FarmersDelightRecipeDiscoveryEvent(playerId, typeId, recipeId, action, source);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }
        plugin.scheduler().run(() -> Bukkit.getPluginManager().callEvent(event));
    }

    /** The unlocked recipe ids of one type for a player (empty when discovery is off — everything is open). */
    public Set<String> unlockedOf(UUID playerId, String typeId) {
        Set<String> result = new HashSet<>();
        Set<String> set = unlocked.get(playerId);
        if (set == null) {
            return result;
        }
        String prefix = typeId + " ";
        for (String entry : set) {
            if (entry.startsWith(prefix)) {
                result.add(entry.substring(prefix.length()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ obtain trigger

    /** Called when a player obtains an item id; unlocks any recipe keyed to it (when enabled). */
    public void onObtain(Player player, String itemId) {
        if (!enabled || !unlockOnObtain || player == null || itemId == null) {
            return;
        }
        Map<String, Set<String>> index = obtainIndex();
        Set<String> keys = index.get(itemId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // Collect newly-unlocked recipes and notify once per pickup (one item may unlock several).
        List<String> newlyUnlocked = new ArrayList<>();
        for (String key : keys) {
            int sep = key.indexOf(' ');
            if (sep <= 0) {
                continue;
            }
            String typeId = key.substring(0, sep);
            String recipeId = key.substring(sep + 1);
            if (unlock(player.getUniqueId(), typeId, recipeId, Source.OBTAIN)) {
                newlyUnlocked.add(recipeId);
            }
        }
        notifyUnlock(player, newlyUnlocked);
    }

    private void notifyUnlock(Player player, List<String> recipeIds) {
        if (!notifyOnUnlock || player == null || recipeIds.isEmpty()) {
            return;
        }
        if (recipeIds.size() == 1) {
            player.sendMessage(I18n.getComponent("recipe-discovery.unlocked", player,
                    Map.of("recipe", recipeIds.getFirst())));
        } else {
            player.sendMessage(I18n.getComponent("recipe-discovery.unlocked-multi", player,
                    Map.of("count", String.valueOf(recipeIds.size()))));
        }
    }

    /** Invalidates the obtain index (call after recipes reload or an addon registers/unregisters a type). */
    public void invalidateIndex() {
        obtainIndex = null;
    }

    private Map<String, Set<String>> obtainIndex() {
        Map<String, Set<String>> index = obtainIndex;
        if (index == null) {
            index = buildObtainIndex();
            obtainIndex = index;
        }
        return index;
    }

    private Map<String, Set<String>> buildObtainIndex() {
        Map<String, Set<String>> index = new ConcurrentHashMap<>();
        // FarmersDelight cooking pot: result + exact-item ingredients. Walks the custom groups' recipes as
        // well as the default ones, because the books display the group-merged list — indexing only the
        // defaults would leave every group-only recipe permanently locked with no way to trigger it.
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getAllRecipes()) {
            String key = key(TYPE_COOKING_POT, recipe.getId());
            indexItem(index, idOf(recipe.getResult()), key);
            for (RecipeIngredient ingredient : recipe.getIngredients()) {
                for (String id : exactItemIds(ingredient)) {
                    indexItem(index, id, key);
                }
            }
        }
        // FarmersDelight cutting board: results + exact-item input.
        for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getRecipes().values()) {
            String key = key(TYPE_CUTTING_BOARD, recipe.getId());
            for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
                indexItem(index, idOf(entry.getItem()), key);
            }
            for (String id : exactItemIds(recipe.getInput())) {
                indexItem(index, id, key);
            }
        }
        // Addon recipe types: result + resolved inputs.
        for (RecipeType type : FarmersDelightApi.get().recipeTypes()) {
            for (ViewableRecipe recipe : type.recipes()) {
                String key = key(type.id(), recipe.id());
                indexItem(index, idOf(recipe.result()), key);
                for (ItemStack input : recipe.inputs()) {
                    indexItem(index, idOf(input), key);
                }
            }
        }
        return index;
    }

    private static void indexItem(Map<String, Set<String>> index, String itemId, String key) {
        if (itemId != null) {
            index.computeIfAbsent(itemId, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    /** Exact item ids of an ingredient (tags excluded — too broad for an auto-unlock trigger). */
    private static List<String> exactItemIds(RecipeIngredient ingredient) {
        List<String> ids = new ArrayList<>();
        collectExactItemIds(ingredient, ids);
        return ids;
    }

    private static void collectExactItemIds(RecipeIngredient ingredient, List<String> out) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            out.add(String.valueOf(item.key()));
        } else if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                collectExactItemIds(option, out);
            }
        }
        // Tag ingredients are intentionally skipped.
    }

    private static String idOf(ItemStack item) {
        return ItemUtils.resolveItemId(item);
    }

    /**
     * Every known recipe id grouped by type id: FarmersDelight's own two types plus each registered addon
     * type. The unlock key is id-only, so a cooking-pot recipe id declared by several custom groups appears
     * once and shares a single unlock bit across groups.
     */
    public Map<String, List<String>> allRecipeKeysByType() {
        Map<String, List<String>> byType = new HashMap<>();
        Set<String> cookingIds = new LinkedHashSet<>();
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getAllRecipes()) {
            cookingIds.add(recipe.getId());
        }
        List<String> cooking = new ArrayList<>(cookingIds);
        byType.put(TYPE_COOKING_POT, cooking);
        List<String> cutting = new ArrayList<>(plugin.getCuttingBoardRecipes().getRecipes().keySet());
        byType.put(TYPE_CUTTING_BOARD, cutting);
        for (RecipeType type : FarmersDelightApi.get().recipeTypes()) {
            List<String> ids = new ArrayList<>();
            for (ViewableRecipe recipe : type.recipes()) {
                ids.add(recipe.id());
            }
            byType.put(type.id(), ids);
        }
        return byType;
    }

    // ------------------------------------------------------------------ persistence

    private static String key(String typeId, String recipeId) {
        return typeId + " " + recipeId;
    }

    private File dataFile() {
        return new File(plugin.getDataFolder(), DATA_FILE);
    }

    private void loadData() {
        unlocked.clear();
        File file = dataFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String uuidString : yaml.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<String> keys = yaml.getStringList(uuidString);
            if (!keys.isEmpty()) {
                Set<String> set = ConcurrentHashMap.newKeySet();
                set.addAll(keys);
                unlocked.put(id, set);
            }
        }
        dirty = false;
    }

    /**
     * Writes the discovery state to disk when changed. Safe to call from a periodic flush and on disable.
     *
     * Merges rather than rewrites: the file on disk is loaded first and only the players held in memory are
     * overwritten, so any entry this instance does not know about survives. Rebuilding the file purely from
     * the in-memory map would make the map the sole authority, and any later eviction or pruning of idle
     * players would silently erase those players' saved progress on the next flush.
     */
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        // Clear dirty before snapshotting so a concurrent unlock re-marks it and is caught by the next flush.
        dirty = false;
        File file = dataFile();
        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : unlocked.entrySet()) {
            Set<String> keys = entry.getValue();
            // An in-memory player with nothing unlocked is an explicit "everything locked" state, so clear
            // the stored key rather than leaving a stale list behind.
            yaml.set(entry.getKey().toString(), keys.isEmpty() ? null : new ArrayList<>(keys));
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            dirty = true; // failed write: keep state dirty so the next flush retries
            I18n.logWarning("recipe-discovery.save_failed", "error", String.valueOf(e.getMessage()));
        }
    }
}
