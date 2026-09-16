package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Action;
import com.huidu.farmersdelight.api.event.FarmersDelightRecipeDiscoveryEvent.Source;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeDiscoveryManager {

    public static final String TYPE_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "farmersdelight:cutting_board";

    private static final String DATA_FILE = "recipe-discovery.yml";

    private final FarmersDelightPlugin plugin;
    // playerId -> set of "<typeId> <recipeId>" keys (space-separated; neither id contains a space).
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();
    // Join/quit work is asynchronous; a generation lets stale tasks become no-ops after a fast reconnect.
    private final Map<UUID, Long> lifecycleVersions = new ConcurrentHashMap<>();
    // itemId -> keys of recipes whose result or an exact-item ingredient is that item (obtain trigger). Lazy.
    private volatile Map<String, Set<String>> obtainIndex;
    // Every known recipe id by type. Lazy, and dropped by the same invalidation as obtainIndex.
    private volatile KnownRecipes known;

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

    public void load() {
        readConfig();
        // With the feature off nothing ever reads the unlock table, so parsing the whole player file at
        // boot buys nothing. It is loaded on the reload that turns the feature on instead.
        if (enabled) {
            loadData();
        }
    }

    public void reloadConfig() {
        boolean wasEnabled = enabled;
        save();
        readConfig();
        obtainIndex = null;
        known = null;
        if (enabled && !wasEnabled) {
            loadData();
        }
    }

    private void readConfig() {
        ConfigurationSection section = plugin.getFirstConfigSection("recipes.discovery", "recipe-discovery");
        enabled = section != null && ConfigSectionReader.optionalBoolean(section, "enabled", false);
        String lockedDisplay = section == null ? "placeholder" : ConfigSectionReader.optionalString(section, "locked-display", "placeholder");
        hideLocked = "hidden".equalsIgnoreCase(lockedDisplay);
        unlockOnObtain = section == null || ConfigSectionReader.optionalBoolean(section, "unlock-on-obtain", true);
        notifyOnUnlock = section == null || ConfigSectionReader.optionalBoolean(section, "notify", true);
        Material icon = section == null ? null : Material.matchMaterial(ConfigSectionReader.optionalString(section, "locked-icon", "BARRIER"));
        lockedIcon = icon != null ? icon : Material.BARRIER;
    }

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

    public boolean hidesLocked() {
        return hideLocked;
    }

    public boolean isUnlockOnObtain() {
        return unlockOnObtain;
    }

    public long markJoin(UUID playerId) {
        return markLifecycle(playerId);
    }

    public long markQuit(UUID playerId) {
        return markLifecycle(playerId);
    }

    private synchronized long markLifecycle(UUID playerId) {
        long version = lifecycleVersions.getOrDefault(playerId, 0L) + 1L;
        lifecycleVersions.put(playerId, version);
        return version;
    }

    private synchronized boolean isCurrent(UUID playerId, long version) {
        return lifecycleVersions.getOrDefault(playerId, 0L) == version;
    }

    public boolean isUnlocked(UUID playerId, String typeId, String recipeId) {
        if (!enabled) {
            return true;
        }
        if (playerId == null || typeId == null || recipeId == null) {
            return true;
        }
        ensureLoaded(playerId);
        Set<String> set = unlocked.get(playerId);
        return set != null && set.contains(key(typeId, recipeId));
    }

    public boolean unlock(UUID playerId, String typeId, String recipeId) {
        return unlock(playerId, typeId, recipeId, Source.API);
    }

    public boolean unlock(UUID playerId, String typeId, String recipeId, Source source) {
        if (playerId == null || typeId == null || recipeId == null) {
            return false;
        }
        // Before computeIfAbsent, or an unlock for an evicted player would start from an empty set and the
        // next flush would write that back over everything they had already discovered.
        ensureLoaded(playerId);
        Set<String> set = unlocked.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
        // add() runs after computeIfAbsent has returned, so no map bin lock is held while the event fires.
        boolean added = set.add(key(typeId, recipeId));
        if (added) {
            dirty = true;
            fireChanged(playerId, typeId, recipeId, Action.UNLOCK, source);
        }
        return added;
    }

    public void lock(UUID playerId, String typeId, String recipeId) {
        lock(playerId, typeId, recipeId, Source.API);
    }

    public boolean lock(UUID playerId, String typeId, String recipeId, Source source) {
        if (playerId == null || typeId == null || recipeId == null) {
            return false;
        }
        ensureLoaded(playerId);
        Set<String> set = unlocked.get(playerId);
        boolean removed = set != null && set.remove(key(typeId, recipeId));
        if (removed) {
            dirty = true;
            fireChanged(playerId, typeId, recipeId, Action.LOCK, source);
        }
        return removed;
    }

    public int unlockAll(UUID playerId) {
        return unlockAll(playerId, Source.API);
    }

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

    public boolean isKnownRecipe(String typeId, String recipeId) {
        if (typeId == null || recipeId == null) {
            return false;
        }
        Set<String> ids = known().ids().get(typeId);
        return ids != null && ids.contains(recipeId);
    }

    private void fireChanged(UUID playerId, String typeId, String recipeId, Action action, Source source) {
        FarmersDelightRecipeDiscoveryEvent event =
                new FarmersDelightRecipeDiscoveryEvent(playerId, typeId, recipeId, action, source);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }
        plugin.scheduler().run(() -> Bukkit.getPluginManager().callEvent(event));
    }

    public Set<String> unlockedOf(UUID playerId, String typeId) {
        Set<String> result = new HashSet<>();
        ensureLoaded(playerId);
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

    public void onObtain(Player player, String itemId) {
        if (!enabled || !unlockOnObtain || player == null || itemId == null) {
            return;
        }
        Map<String, Set<String>> index = obtainIndex();
        Set<String> keys = index.get(itemId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // Collect newly-unlocked recipes and notify once per pickup (one item may unlock several). The
        // whole key is kept rather than the recipe id alone, because naming the recipe in the message
        // needs its type to find the recipe again.
        List<String> newlyUnlocked = new ArrayList<>();
        for (String key : keys) {
            int sep = key.indexOf(' ');
            if (sep <= 0) {
                continue;
            }
            String typeId = key.substring(0, sep);
            String recipeId = key.substring(sep + 1);
            if (unlock(player.getUniqueId(), typeId, recipeId, Source.OBTAIN)) {
                newlyUnlocked.add(key);
            }
        }
        notifyUnlock(player, newlyUnlocked);
    }

    private void notifyUnlock(Player player, List<String> unlockedKeys) {
        if (!notifyOnUnlock || player == null || unlockedKeys.isEmpty()) {
            return;
        }
        if (unlockedKeys.size() > 1) {
            player.sendMessage(I18n.getComponent("recipe-discovery.unlocked-multi", player,
                    Map.of("count", String.valueOf(unlockedKeys.size()))));
            return;
        }
        String key = unlockedKeys.getFirst();
        int sep = key.indexOf(' ');
        String typeId = sep <= 0 ? "" : key.substring(0, sep);
        String recipeId = sep <= 0 ? key : key.substring(sep + 1);
        // The placeholder takes a component, not a string, so the item name reaches the client as the
        // translatable it is: a CraftEngine item name is a client-side lang key, and only the client can
        // turn it into the player's own language. The recipe id is the fallback when the recipe cannot be
        // found, which is what this message always used to show.
        ItemStack result = resultOf(typeId, recipeId);
        Component name = result == null
                ? Component.text(recipeId)
                : ItemUtils.getDisplayComponent(result, player);
        player.sendMessage(I18n.getComponent("recipe-discovery.unlocked", player)
                .replaceText(builder -> builder.matchLiteral("{recipe}").replacement(name)));
    }

    // The item a recipe produces, used to name it in the unlock message. Null when the recipe is gone or
    // the type is not one this server knows.
    private ItemStack resultOf(String typeId, String recipeId) {
        if (TYPE_COOKING_POT.equals(typeId)) {
            CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(recipeId);
            if (recipe == null) {
                // getRecipe only sees the default recipes; the obtain index is built from the custom
                // groups as well, so a group-only recipe is found by walking them.
                for (CookingPotRecipe candidate : plugin.getCookingPotRecipes().getAllRecipes()) {
                    if (candidate.getId().equals(recipeId)) {
                        recipe = candidate;
                        break;
                    }
                }
            }
            return recipe == null ? null : recipe.getResult();
        }
        if (TYPE_CUTTING_BOARD.equals(typeId)) {
            CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipes().get(recipeId);
            if (recipe == null || recipe.getResults().isEmpty()) {
                return null;
            }
            return recipe.getResults().getFirst().getItem();
        }
        for (RecipeType type : FarmersDelightApi.get().recipeTypes()) {
            if (!typeId.equals(type.id())) {
                continue;
            }
            for (ViewableRecipe recipe : type.recipes()) {
                if (recipeId.equals(recipe.id())) {
                    return recipe.result();
                }
            }
            return null;
        }
        return null;
    }

    public void invalidateIndex() {
        obtainIndex = null;
        known = null;
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
     * Every known recipe id, grouped by type, in display order. The map and its lists are immutable and
     * shared between callers.
     */
    public Map<String, List<String>> allRecipeKeysByType() {
        return known().byType();
    }

    // Both views of the same snapshot: byType keeps display order for tab completion and the unlock-all
    // walks, ids answers membership without scanning a list.
    private record KnownRecipes(Map<String, List<String>> byType, Map<String, Set<String>> ids) {
    }

    // Building this walks every default cooking pot recipe, every custom group, every cutting board recipe
    // and every addon type. Tab completion asks for it once per keystroke, so it is held until the recipe
    // set changes. Invalidation rides the hook that already drops the obtain index, which covers a
    // FarmersDelight recipe reload and every api register or unregister -- the same contract the obtain
    // index runs on, so an addon that mutates its own recipe list without telling the api is stale in both.
    private KnownRecipes known() {
        KnownRecipes cached = known;
        if (cached == null) {
            cached = buildKnownRecipes();
            known = cached;
        }
        return cached;
    }

    private KnownRecipes buildKnownRecipes() {
        Map<String, List<String>> byType = new HashMap<>();
        Map<String, Set<String>> ids = new HashMap<>();
        Set<String> cookingIds = new LinkedHashSet<>();
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getAllRecipes()) {
            cookingIds.add(recipe.getId());
        }
        put(byType, ids, TYPE_COOKING_POT, cookingIds);
        put(byType, ids, TYPE_CUTTING_BOARD, plugin.getCuttingBoardRecipes().getRecipes().keySet());
        for (RecipeType type : FarmersDelightApi.get().recipeTypes()) {
            // Ids here come from an addon. The snapshot is immutable, and the immutable collection
            // factories reject nulls, so a single null id from one addon would otherwise throw out of
            // every command and book that asks for the known recipes.
            if (type.id() == null) {
                continue;
            }
            Set<String> typeIds = new LinkedHashSet<>();
            for (ViewableRecipe recipe : type.recipes()) {
                if (recipe != null && recipe.id() != null) {
                    typeIds.add(recipe.id());
                }
            }
            put(byType, ids, type.id(), typeIds);
        }
        return new KnownRecipes(Map.copyOf(byType), Map.copyOf(ids));
    }

    private static void put(Map<String, List<String>> byType, Map<String, Set<String>> ids,
                            String typeId, Collection<String> recipeIds) {
        byType.put(typeId, List.copyOf(recipeIds));
        ids.put(typeId, Set.copyOf(recipeIds));
    }

    // ------------------------------------------------------------------ persistence

    private static String key(String typeId, String recipeId) {
        return typeId + " " + recipeId;
    }

    private File dataFile() {
        return new File(plugin.getDataFolder(), DATA_FILE);
    }

    // Only players who are online right now are held in memory. At boot that is nobody, so the file is not
    // read at all; on a reload it is the players whose books can be open. Everyone else is read back on
    // demand by ensureLoaded, which is what keeps this table sized by concurrent players rather than by
    // every player who has ever joined.
    private void loadData() {
        unlocked.clear();
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        if (online.isEmpty()) {
            dirty = false;
            return;
        }
        YamlConfiguration yaml = readData();
        if (yaml != null) {
            for (UUID id : online) {
                List<String> keys = yaml.getStringList(id.toString());
                if (!keys.isEmpty()) {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(keys);
                    unlocked.put(id, set);
                }
            }
        }
        dirty = false;
    }

    private YamlConfiguration readData() {
        File file = dataFile();
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : null;
    }

    /**
     * Reads one player's unlocks off disk if they are not in memory. An entry is left behind even when the
     * player has nothing stored, so the file is parsed once per player rather than once per lookup.
     */
    public void ensureLoaded(UUID playerId) {
        if (playerId == null || unlocked.containsKey(playerId)) {
            return;
        }
        Set<String> set = ConcurrentHashMap.newKeySet();
        YamlConfiguration yaml = readData();
        if (yaml != null) {
            set.addAll(yaml.getStringList(playerId.toString()));
        }
        unlocked.putIfAbsent(playerId, set);
    }

    public void ensureLoaded(UUID playerId, long version) {
        if (playerId == null || !isCurrent(playerId, version) || unlocked.containsKey(playerId)) {
            return;
        }
        Set<String> set = ConcurrentHashMap.newKeySet();
        YamlConfiguration yaml = readData();
        if (yaml != null) {
            set.addAll(yaml.getStringList(playerId.toString()));
        }
        synchronized (this) {
            if (isCurrent(playerId, version)) {
                unlocked.putIfAbsent(playerId, set);
            }
        }
    }

    /**
     * Writes pending unlocks out and drops this player from memory, for when they leave. save merges into
     * the file rather than replacing it, so a dropped player is still there for an offline lookup to read
     * back.
     */
    public void evict(UUID playerId) {
        if (playerId == null) {
            return;
        }
        save();
        unlocked.remove(playerId);
    }

    public void evict(UUID playerId, long version) {
        if (playerId == null) {
            return;
        }
        // Always flush first: a stale quit task must not discard pending unlocks when a rejoin
        // has already advanced the lifecycle version.
        save();
        synchronized (this) {
            if (isCurrent(playerId, version)) {
                unlocked.remove(playerId);
                lifecycleVersions.remove(playerId, version);
            }
        }
    }

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
            RecipeEditorStore.writeAtomically(file, yaml.saveToString());
        } catch (IOException e) {
            dirty = true; // failed write: keep state dirty so the next flush retries
            I18n.logWarning("recipe-discovery.save_failed", "error", String.valueOf(e.getMessage()));
        }
    }
}
