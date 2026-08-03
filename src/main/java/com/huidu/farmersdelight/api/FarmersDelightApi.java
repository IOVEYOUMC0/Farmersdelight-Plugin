package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.scheduler.ApiTask;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stable, addon-facing entry point for FarmersDelight services (scheduling + experience). Lives in the
 * name-stable api package; every signature uses only Bukkit / java / other api types so
 * addons keep working against the obfuscated jar. Method bodies delegate to renamed internals freely.
 *
 * Version your integration with apiVersion() and hasFeature(String) rather than
 * with the plugin's version string — the plugin version tracks content, the api version tracks the
 * surface addons compile against.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightApi {

    /**
     * Current api surface revision. Monotonic and bumped by exactly one on every release that ADDS to
     * the addon-facing surface (a new api class, method, event, or feature flag). Never
     * decremented, never reused, and never bumped for internal refactors that leave the surface
     * unchanged. Removals / incompatible changes are not made under this scheme at all: an addon that
     * compiled against revision N keeps compiling and linking against every revision greater than N.
     *
     * Revision 1 is the first build to expose apiVersion(); on older builds the method is
     * absent, so a call throws NoSuchMethodError — treat a caught NoSuchMethodError (or
     * NoSuchFieldError) as "revision 0, pre-versioning".
     */
    private static final int API_VERSION = 1;

    /**
     * Feature ids answered by hasFeature(String). An id is added here in the same release that
     * adds the capability and is never removed, so a probe for an unknown id simply returns false on
     * older builds. Comparing against apiVersion() works too; feature ids exist so an addon can
     * ask about one capability without tracking which revision introduced it.
     */
    private static final java.util.Set<String> FEATURES = java.util.Set.of(
            // Runtime recipe registration + the generic recipe book / editor (registerRecipeType,
            // registerCookingPotRecipe, registerCuttingBoardRecipe, openRecipeBook, openRecipeEditor).
            "recipes",
            // Packet-only item displays (createItemDisplay / updateItemDisplay / removeItemDisplay).
            "item-displays",
            // Folia-safe scheduling helpers (runAtLocation / runLaterAtLocation / runRepeating).
            "scheduler",
            // Custom buff registry + the buff bossbar render channels.
            "buffs",
            // com.huidu.farmersdelight.api.block: station identification and read-only snapshots.
            "station-query",
            // FarmersDelightHarvestEvent for the Java-side harvest handlers.
            "harvest-event",
            // FarmersDelightCookStartEvent on the cooking pot's idle-to-cooking transition.
            "cook-start-event",
            // FarmersDelightBuffChangeEvent on real custom-buff level transitions.
            "buff-change-event",
            // ProfessionCookingExperienceEvent carries the station location.
            "cooking-experience-location",
            // Knife extra-drop rule registration (FarmersDelightKnifeDrops).
            "knife-drop-rules",
            // com.huidu.farmersdelight.api.util cross-version compatibility helpers.
            "compat-util",
            // Debug tool extension hooks for /fd debugtools.
            "debug-tools"
    );

    private static final FarmersDelightApi INSTANCE = new FarmersDelightApi();

    private final Map<String, RecipeType> recipeTypes = Collections.synchronizedMap(new LinkedHashMap<>());
    // Block namespaces of registered addons (e.g. "brewinandchewin"), so the CraftEngine block-state usage
    // report attributes addon blocks alongside FarmersDelight's own.
    private final java.util.Set<String> addonBlockNamespaces =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private FarmersDelightApi() {
    }

    public static FarmersDelightApi get() {
        return INSTANCE;
    }

    /**
     * The api surface revision of the running FarmersDelight build (see the bump policy on API_VERSION:
     * monotonic, +1 per additive release, never decremented, no removals). Compare with a minimum your
     * addon needs:
     *
     * int version;
     * try {
     *     version = FarmersDelightApi.get().apiVersion();
     * } catch (NoSuchMethodError pre) {
     *     version = 0;
     * }
     * if (version >= 1) { ... }
     *
     * A build older than the one that introduced this method has no such method, so the call throws
     * NoSuchMethodError — catch it and treat it as revision 0. Unlike isAvailable this reports the
     * compiled-in surface, so it stays meaningful even while the plugin is still enabling.
     */
    public int apiVersion() {
        return API_VERSION;
    }

    /**
     * True when the running build exposes the capability named by feature. Feature ids are
     * lowercase hyphenated ("station-query", "harvest-event", "cook-start-event", "buff-change-event",
     * "cooking-experience-location", "knife-drop-rules", "compat-util", "recipes", "item-displays",
     * "scheduler", "buffs", "debug-tools"). Unknown or null ids return false, so probing an id that a
     * newer build introduces is safe on an older one. Ids are never removed once published.
     *
     * Prefer this over a version comparison when you care about one capability; prefer
     * apiVersion() when you need an ordering. Like apiVersion, calling this on a build older
     * than the one that introduced it throws NoSuchMethodError — guard the first probe if you support
     * those builds.
     */
    public boolean hasFeature(String feature) {
        return feature != null && FEATURES.contains(feature.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Registers an addon's block namespace (e.g. "brewinandchewin") so its CraftEngine blocks are
     * counted in FarmersDelight's block-state usage report. Idempotent; a trailing ':' is tolerated. */
    public void registerAddonBlockNamespace(String namespace) {
        if (namespace == null) {
            return;
        }
        String trimmed = namespace.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.isEmpty()) {
            addonBlockNamespaces.add(trimmed);
        }
    }

    /** The registered addon block namespaces (without trailing ':'). For the block-state usage monitor. */
    public java.util.Set<String> addonBlockNamespaces() {
        return java.util.Set.copyOf(addonBlockNamespaces);
    }

    /** Registers an addon recipe type so it appears in the generic recipe book (and editor, if provided). */
    public void registerRecipeType(RecipeType type) {
        if (type != null && type.id() != null) {
            recipeTypes.put(type.id(), type);
            invalidateRecipeDiscoveryIndex();
        }
    }

    /** Removes a previously registered recipe type (e.g. on addon disable). */
    public void unregisterRecipeType(String typeId) {
        if (typeId != null) {
            recipeTypes.remove(typeId);
            invalidateRecipeDiscoveryIndex();
        }
    }

    /** Keeps the recipe-discovery obtain-trigger index in sync when the set of recipe types changes. */
    private void invalidateRecipeDiscoveryIndex() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.getRecipeDiscoveryManager() != null) {
            plugin.getRecipeDiscoveryManager().invalidateIndex();
        }
    }

    /**
     * Registers (or replaces) a FarmersDelight cooking-pot recipe at runtime, so addon dishes are cooked
     * by the real cooking pot. Ingredient specs use the recipe-file syntax ("ns:id", "#ns:tag", "a|b").
     * container is the required bowl/bottle (null = none); result carries its own amount.
     * The recipe persists across /fd reload. No-op when FarmersDelight is unavailable.
     */
    public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                         ItemStack result, double experience, int cookTime, String category) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || id == null || ingredients == null || result == null) {
            return;
        }
        plugin.getCookingPotRecipes().registerExternalRecipe(id, ingredients,
                container == null ? null : container.clone(), result.clone(),
                (float) experience, cookTime, category);
    }

    /** Removes a cooking-pot recipe registered via #registerCookingPotRecipe. */
    public void unregisterCookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCookingPotRecipes().unregisterExternalRecipe(id);
        }
    }

    /**
     * Registers (or replaces) a FarmersDelight cutting-board recipe at runtime, so addon items can be cut
     * on the real cutting board. input/tool use the recipe-file syntax ("ns:id", "#ns:tag");
     * each results stack carries its own amount; sound is a sound id (null = default knife).
     * The recipe persists across /fd reload. No-op when FarmersDelight is unavailable.
     */
    public void registerCuttingBoardRecipe(String id, String input, String tool,
                                           List<ItemStack> results, String sound) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || id == null || input == null || tool == null || results == null) {
            return;
        }
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack result : results) {
            if (result != null) {
                copies.add(result.clone());
            }
        }
        plugin.getCuttingBoardRecipes().registerExternalRecipe(id, input, tool, copies, sound);
    }

    /** Removes a cutting-board recipe registered via #registerCuttingBoardRecipe. */
    public void unregisterCuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCuttingBoardRecipes().unregisterExternalRecipe(id);
        }
    }

    /** All registered recipe types, in registration order. */
    public List<RecipeType> recipeTypes() {
        synchronized (recipeTypes) {
            return new ArrayList<>(recipeTypes.values());
        }
    }

    public RecipeType recipeType(String typeId) {
        return typeId == null ? null : recipeTypes.get(typeId);
    }

    /** Opens the standalone recipe book (no Fill button) for player. */
    public void openRecipeBook(Player player) {
        openRecipeBook(player, null);
    }

    /**
     * Opens the recipe book for player with a RecipeFiller. When non-null, recipe detail
     * shows a Fill button that fills the filler's station; pass null for a read-only standalone book.
     */
    public void openRecipeBook(Player player, RecipeFiller filler) {
        if (player != null) {
            RecipeBookGui.openMenu(player, filler);
        }
    }

    /**
     * Opens the book directly to a single registered type (typeId), as an independent book — never
     * the shared category menu, so it won't pile in with other addons' types. If the type provides its own
     * RecipeType#listLayout()/RecipeType#detailLayout(), those drive the look. Falls back to
     * the shared book when typeId isn't registered.
     */
    public void openRecipeBook(Player player, String typeId, RecipeFiller filler) {
        if (player == null) {
            return;
        }
        RecipeType type = recipeType(typeId);
        if (type == null) {
            RecipeBookGui.openMenu(player, filler);
        } else {
            RecipeBookGui.openType(player, type, filler);
        }
    }

    /** Opens the generic recipe editor for recipeId of a registered type (null id = new recipe). */
    public void openRecipeEditor(Player player, String typeId, String recipeId) {
        RecipeType type = recipeType(typeId);
        if (player != null && type != null && type.editor() != null) {
            RecipeBookGui.openEditor(player, type, recipeId);
        }
    }

    /** True when FarmersDelight is present and enabled; addons should guard calls with this. */
    public boolean isAvailable() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }

    public boolean isFolia() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.scheduler().isFolia();
    }

    /**
     * Resolves translation tags (<l10n:key> / <lang:key> / <i18n:key>) in text
     * to player's locale (falling back to the default/en locale, then the raw key), leaving other
     * content — including MiniMessage markup — untouched. Keys resolve through FarmersDelight's lang files and
     * CraftEngine's translations, so addons can put localized placeholders in their own GUI config strings.
     * player may be null (uses the default locale).
     */
    public String resolveTranslations(String text, Player player) {
        return com.huidu.farmersdelight.util.ItemUtils.resolveTranslationTags(text, player);
    }

    /**
     * Formats a server-console log line from FarmersDelight's lang files: key is resolved under the
     * console. prefix in the active console locale, with args substituted as name/value pairs
     * ("count", 3, "file", name, ...); an unknown key returns itself. Lets addons emit console logs through the
     * same shared lang system rather than hardcoding English. Callers still choose the log level/logger.
     */
    public static String consoleMessage(String key, Object... args) {
        return com.huidu.farmersdelight.i18n.I18n.formatConsole(key, args);
    }

    /**
     * True when FarmersDelight's shared debug switch is on and the given category is listed in
     * debug.categories (or the list is all / *). Lets an addon apply the same console
     * policy as FarmersDelight itself: keep a healthy boot to a single summary line, and log the
     * per-subsystem counts through its own logger at INFO only when the operator asked for that
     * category, at FINE otherwise. Use the startup category for boot census lines.
     */
    public static boolean isDebugEnabled(String category) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.isDebugEnabled(category);
    }

    /** True if block is a configured heat source (cooking-pot heating). */
    public boolean isHeatSource(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isHeatSource(block);
    }

    /** True if block is a configured heat conductor (passes heat from a source below it). */
    public boolean isConductor(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isConductor(block);
    }

    // Packet item displays
    // Server-side, packet-only ItemDisplay proxies (no real entity is spawned): FarmersDelight tracks them,
    // syncs them to nearby players (join / chunk-load / teleport) and cleans them up on world unload. Use
    // these instead of world.spawn(ItemDisplay) so an addon's decoration displays don't persist to disk,
    // never become orphans, and share FarmersDelight's Folia-safe visibility handling. The returned int is a
    // handle for updateItemDisplay / removeItemDisplay; a return of -1 means the display was not created.

    /**
     * Creates a packet-only ItemDisplay at location showing item, with the given item display context (e.g.
     * ItemDisplay.ItemDisplayTransform.FIXED) and transformation (translation / rotation / scale). Returns a
     * handle, or -1 if unavailable or the packet build failed. Call on the region that owns location (Folia).
     */
    public int createItemDisplay(Location location, ItemStack item,
                                 org.bukkit.entity.ItemDisplay.ItemDisplayTransform itemTransform,
                                 org.bukkit.util.Transformation transformation) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || location == null || item == null) {
            return -1;
        }
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (manager == null) {
            return -1;
        }
        return manager.createDisplay(new com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec(
                location, item, itemTransform, transformation));
    }

    /** Updates an existing packet ItemDisplay (from createItemDisplay) in place. Returns false if the handle
     *  is unknown or FarmersDelight is unavailable. */
    public boolean updateItemDisplay(int handle, Location location, ItemStack item,
                                     org.bukkit.entity.ItemDisplay.ItemDisplayTransform itemTransform,
                                     org.bukkit.util.Transformation transformation) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || location == null || item == null) {
            return false;
        }
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (manager == null) {
            return false;
        }
        return manager.updateDisplay(handle, new com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec(
                location, item, itemTransform, transformation));
    }

    /** Removes a packet ItemDisplay by its handle (from createItemDisplay). No-op for an unknown handle. */
    public void removeItemDisplay(int handle) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable()) {
            return;
        }
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (manager != null) {
            manager.destroyDisplay(handle);
        }
    }

    /** Runs task on the region owning location (Folia-safe; immediate on Paper). */
    public void runAtLocation(Location location, Runnable task) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runAt(location, task);
    }

    /** Runs task delayTicks later on the region owning location (Folia-safe). */
    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runLaterAt(location, task, delayTicks);
    }

    /** Schedules a repeating task; returns a handle to cancel it. Never null (NOOP when FD absent). */
    public ApiTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return ApiTask.NOOP;
        }
        PluginTask pluginTask = plugin.scheduler().runRepeating(task, delayTicks, periodTicks);
        return new ApiTask() {
            @Override
            public void cancel() {
                pluginTask.cancel();
            }

            @Override
            public boolean isCancelled() {
                return pluginTask.isCancelled();
            }
        };
    }

    /**
     * Awards crafting experience to player for a produced result, mirroring the cooking
     * pot: optional vanilla XP orbs dropped at location (gated by the cooking-pot XP config),
     * AuraSkills XP, and a ProfessionCookingExperienceEvent carrying source. Folia-safe.
     */
    public void awardCraftingExperience(Player player, Location location, ItemStack result,
                                        double baseExperience, String source) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || player == null) {
            return;
        }
        ItemStack resultCopy = result == null ? null : result.clone();
        plugin.scheduler().runAt(location, () -> {
            if (baseExperience > 0.0D && plugin.shouldDropCookingPotVanillaExperience()
                    && location != null && location.getWorld() != null) {
                dropExperienceOrbs(location.getWorld(), location, baseExperience);
            }
            plugin.awardCookingPotAuraSkillsExperience(player, baseExperience);
            Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                    player.getUniqueId(), player.getName(), source, resultCopy, (float) baseExperience,
                    location));
        });
    }

    private static void dropExperienceOrbs(World world, Location location, double totalExp) {
        // Probabilistic rounding so sub-1.0 exp isn't floored away; expected total holds over many takes.
        int expValue = (int) Math.floor(totalExp);
        double fraction = totalExp - expValue;
        if (fraction > 0.0D && ThreadLocalRandom.current().nextDouble() < fraction) {
            expValue += 1;
        }
        if (expValue > 0) {
            int amount = expValue;
            world.spawn(location, ExperienceOrb.class, orb -> orb.setExperience(amount));
        }
    }
}
