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

@ApiStatus.NonExtendable
public final class FarmersDelightApi {

    private static final int API_VERSION = 2;

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
            // Runtime villager / wandering-trader trade registration (FarmersDelightVillagerTrades).
            "villager-trades",
            // Durability capability decoupled from the sword: the farmersdelight:durable item setting +
            // FarmersDelightItems.damage(...).
            "durable-items",
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

    public int apiVersion() {
        return API_VERSION;
    }

    public boolean hasFeature(String feature) {
        return feature != null && FEATURES.contains(feature.trim().toLowerCase(java.util.Locale.ROOT));
    }

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

    public java.util.Set<String> addonBlockNamespaces() {
        return java.util.Set.copyOf(addonBlockNamespaces);
    }

    public void registerRecipeType(RecipeType type) {
        if (type != null && type.id() != null) {
            recipeTypes.put(type.id(), type);
            invalidateRecipeDiscoveryIndex();
        }
    }

    public void unregisterRecipeType(String typeId) {
        if (typeId != null) {
            recipeTypes.remove(typeId);
            invalidateRecipeDiscoveryIndex();
        }
    }

    private void invalidateRecipeDiscoveryIndex() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.getRecipeDiscoveryManager() != null) {
            plugin.getRecipeDiscoveryManager().invalidateIndex();
        }
    }

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

    public void unregisterCookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCookingPotRecipes().unregisterExternalRecipe(id);
        }
    }

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

    public void unregisterCuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && isAvailable() && id != null) {
            plugin.getCuttingBoardRecipes().unregisterExternalRecipe(id);
        }
    }

    public List<RecipeType> recipeTypes() {
        synchronized (recipeTypes) {
            return new ArrayList<>(recipeTypes.values());
        }
    }

    public RecipeType recipeType(String typeId) {
        return typeId == null ? null : recipeTypes.get(typeId);
    }

    public void openRecipeBook(Player player) {
        openRecipeBook(player, null);
    }

    public void openRecipeBook(Player player, RecipeFiller filler) {
        if (player != null) {
            RecipeBookGui.openMenu(player, filler);
        }
    }

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

    public void openRecipeEditor(Player player, String typeId, String recipeId) {
        RecipeType type = recipeType(typeId);
        if (player != null && type != null && type.editor() != null) {
            RecipeBookGui.openEditor(player, type, recipeId);
        }
    }

    public boolean isAvailable() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }

    public boolean isFolia() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.scheduler().isFolia();
    }

    public String resolveTranslations(String text, Player player) {
        return com.huidu.farmersdelight.util.ItemUtils.resolveTranslationTags(text, player);
    }

    public static String consoleMessage(String key, Object... args) {
        return com.huidu.farmersdelight.i18n.I18n.formatConsole(key, args);
    }

    public static boolean isDebugEnabled(String category) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.isDebugEnabled(category);
    }

    public boolean isHeatSource(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isHeatSource(block);
    }

    public boolean isConductor(Block block) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && block != null && plugin.getHeatSourceConfig().isConductor(block);
    }

    public void registerHeatSource(String vanillaBlockId) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getHeatSourceConfig().addVanillaBlock(vanillaBlockId);
        }
    }

    public void registerConductor(String vanillaBlockId) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getHeatSourceConfig().addVanillaConductor(vanillaBlockId);
        }
    }

    public void registerCustomHeatSourceTag(String tagId) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && tagId != null && !tagId.isBlank()) {
            plugin.getHeatSourceConfig().addCustomBlockTag(net.momirealms.craftengine.core.util.Key.of(tagId));
        }
    }

    // Packet item displays
    // Server-side, packet-only ItemDisplay proxies (no real entity is spawned): FarmersDelight tracks them,
    // syncs them to nearby players (join / chunk-load / teleport) and cleans them up on world unload. Use
    // these instead of world.spawn(ItemDisplay) so an addon's decoration displays don't persist to disk,
    // never become orphans, and share FarmersDelight's Folia-safe visibility handling. The returned int is a
    // handle for updateItemDisplay / removeItemDisplay; a return of -1 means the display was not created.

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

    public boolean updateItemDisplay(int handle, Location location, ItemStack item,
                                     org.bukkit.entity.ItemDisplay.ItemDisplayTransform itemTransform,
                                     org.bukkit.util.Transformation transformation) {
        return updateItemDisplay(handle, location, item, itemTransform, transformation, 0);
    }

    /**
     * As updateItemDisplay, but animate the transform change: the client smoothly interpolates from the
     * display's current transform to the given one over interpolationDurationTicks ticks (0 = snap, the
     * default overload). Use this for animated station displays such as flipping a skewer on the grill.
     */
    public boolean updateItemDisplay(int handle, Location location, ItemStack item,
                                     org.bukkit.entity.ItemDisplay.ItemDisplayTransform itemTransform,
                                     org.bukkit.util.Transformation transformation, int interpolationDurationTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !isAvailable() || location == null || item == null) {
            return false;
        }
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = plugin.getItemDisplayManager();
        if (manager == null) {
            return false;
        }
        return manager.updateDisplay(handle, new com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec(
                location, item, itemTransform, transformation, Math.max(0, interpolationDurationTicks), 0));
    }

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

    public void runAtLocation(Location location, Runnable task) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runAt(location, task);
    }

    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || task == null) {
            return;
        }
        plugin.scheduler().runLaterAt(location, task, delayTicks);
    }

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

    public void awardCraftingExperience(Player player, Location location, ItemStack result,
                                        double baseExperience, String source) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || player == null) {
            return;
        }
        ItemStack resultCopy = result == null ? null : result.clone();
        Location locationCopy = location == null ? null : location.clone();
        if (baseExperience > 0.0D && plugin.shouldDropCookingPotVanillaExperience()
                && locationCopy != null && locationCopy.getWorld() != null) {
            plugin.scheduler().runAt(locationCopy, () ->
                    dropExperienceOrbs(locationCopy.getWorld(), locationCopy, baseExperience));
        }
        // The player may be in a different Folia region than the crafting station, or teleport before
        // execution. Keep all player-owned integrations and the player event on the entity scheduler.
        plugin.scheduler().runForEntity(player, () -> {
            plugin.awardCookingPotAuraSkillsExperience(player, baseExperience);
            Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                    player.getUniqueId(), player.getName(), source, resultCopy, (float) baseExperience,
                    locationCopy));
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
