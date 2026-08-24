package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionHandler;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.recipe.ChanceResult;
import com.huidu.farmersdelight.api.recipe.RecipeFiller;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
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
            "debug-tools",
            // Programmatic special recipe registration (registerSpecialRecipe / unregisterSpecialRecipe /
            // specialRecipes) with per-recipe display types (recipe / item_description).
            "special-recipes",
            // CraftEngine content existence checks (FarmersDelightContent).
            "content-check",
            // Datapack world whitelist (isWorldWhitelisted): addons writing their own datapacks can
            // follow FarmersDelight's datapacks.world-whitelist instead of installing into every world.
            "datapack-whitelist"
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

    // Shared rule for the runtime-mutating register/unregister methods below: get the plugin and let it pass
    // through only if it is available. Returning null makes the caller's null-guard double as the
    // availability check, so we avoid repeating getInstance() plus isAvailable() in every method.
    private static FarmersDelightPlugin availablePlugin() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return (plugin != null && plugin.isEnabled0()) ? plugin : null;
    }

    public void registerCookingPotRecipe(String id, List<String> ingredients, ItemStack container,
                                         ItemStack result, double experience, int cookTime, String category) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null || id == null || ingredients == null || result == null) {
            return;
        }
        plugin.getCookingPotRecipes().registerExternalRecipe(id, ingredients,
                container == null ? null : container.clone(), result.clone(),
                (float) experience, cookTime, category);
    }

    public void unregisterCookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getCookingPotRecipes().unregisterExternalRecipe(id);
        }
    }

    public void registerCuttingBoardRecipe(String id, String input, String tool,
                                           List<ItemStack> results, String sound) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null || id == null || input == null || tool == null || results == null) {
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

    /**
     * Register a cutting-board recipe whose results carry a per-result drop chance (mirrors the mod's
     * addResultWithChance). A chance of 1.0 is a guaranteed result; 0.5 drops half the time. Use this
     * instead of the plain registerCuttingBoardRecipe when any result is not guaranteed.
     */
    public void registerCuttingBoardRecipeWithChances(String id, String input, String tool,
                                                      List<ChanceResult> results, String sound) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null || id == null || input == null || tool == null || results == null) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        for (ChanceResult result : results) {
            if (result != null && result.item() != null) {
                items.add(result.item());
                chances.add((double) result.chance());
            }
        }
        plugin.getCuttingBoardRecipes().registerExternalRecipe(id, input, tool, items, chances, sound);
    }

    public void unregisterCuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getCuttingBoardRecipes().unregisterExternalRecipe(id);
        }
    }

    /**
     * Register a special recipe (composting, sunlight/water conditions, catalysts...). The recipe's
     * displayType selects how it is shown: SpecialRecipeInfo#DISPLAY_RECIPE keeps the
     * input → output → condition layout, SpecialRecipeInfo#DISPLAY_ITEM_DESCRIPTION renders a
     * description-only info page.
     */
    public void registerSpecialRecipe(SpecialRecipeInfo info) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && info != null) {
            plugin.getSpecialRecipeRegistry().register(info);
        }
    }

    /**
     * Config-driven registration: parses one special-recipe entry from a YAML section and registers it.
     * Same format as FarmersDelight's own special_recipes.yml; lets addons drive their special
     * recipes from a released config file like their other recipes. Throws on a malformed section so the
     * addon loader can fail the specific entry and keep going (matching FD's per-entry isolation).
     */
    public void registerSpecialRecipeFromSection(String id, org.bukkit.configuration.ConfigurationSection section) {
        if (id == null || section == null) {
            return;
        }
        registerSpecialRecipe(com.huidu.farmersdelight.recipe.SpecialRecipeLoader.parseRecipe(id, section));
    }

    public void unregisterSpecialRecipe(String id) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && id != null) {
            plugin.getSpecialRecipeRegistry().unregister(id);
        }
    }

    public List<SpecialRecipeInfo> specialRecipes() {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin == null) {
            return List.of();
        }
        return plugin.getSpecialRecipeRegistry().getAll();
    }

    /** Add a right-click handler for FarmersDelight cutting boards; first to consume wins. */
    public void registerCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && handler != null) {
            plugin.registerCuttingBoardInteractionHandler(handler);
        }
    }

    public void unregisterCuttingBoardInteractionHandler(CuttingBoardInteractionHandler handler) {
        FarmersDelightPlugin plugin = availablePlugin();
        if (plugin != null && handler != null) {
            plugin.unregisterCuttingBoardInteractionHandler(handler);
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

    /**
     * Whether CraftEngine has finished its deferred item-load pass, so recipe results and advancement
     * icons resolve to real custom items. False during an addon's own onEnable on a normal server start
     * (CE items load later, announced by FarmersDelightWarmupEvent); an addon that registers
     * content both eagerly at enable and again on warmup should gate the eager path on this to avoid
     * registering — and logging — twice.
     */
    public boolean isContentLoaded() {
        return com.huidu.farmersdelight.util.ItemUtils.isAnyCustomItemLoaded();
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

    /**
     * Whether a world is on FarmersDelight's datapacks.world-whitelist ($primary, * or exact
     * names). Addons that install their own datapacks should skip worlds this rejects so a Multiverse
     * server keeps only the primary world's datapacks folder populated.
     */
    public boolean isWorldWhitelisted(org.bukkit.World world) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && world != null && plugin.datapackWorldWhitelist().allows(world.getName());
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

    // Shared gate + manager lookup for the packet display/text methods below. Returns null when the plugin
    // is not available so each caller's single null-check doubles as the availability guard.
    private com.huidu.farmersdelight.visual.ItemDisplayManager displayManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return (plugin != null && plugin.isEnabled0()) ? plugin.getItemDisplayManager() : null;
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
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || item == null) {
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
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || item == null) {
            return false;
        }
        return manager.updateDisplay(handle, new com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec(
                location, item, itemTransform, transformation, Math.max(0, interpolationDurationTicks), 0));
    }

    public void removeItemDisplay(int handle) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        if (manager != null) {
            manager.destroyDisplay(handle);
        }
    }

    /**
     * Whether a handle previously returned by createItemDisplay still refers to a managed display.
     * FD removes displays on chunk unload / world unload / /fd cleanup without notifying the owner,
     * so callers that cache handles should probe this periodically and recreate the display when it
     * turns false. Pure map lookup, no packets, safe to call from a region thread.
     */
    public boolean isItemDisplayActive(int handle) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        return manager != null && manager.isActive(handle);
    }

    // Packet text displays
    // Same packet-only lifecycle as the item displays above, but renders text (TextDisplay). Handles
    // returned here are only valid for the text-* methods below.

    public int createTextDisplay(Location location, net.kyori.adventure.text.Component text,
                                 org.bukkit.util.Transformation transformation,
                                 org.bukkit.Color backgroundColor, boolean shadowed, boolean seeThrough) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        if (manager == null || location == null || text == null) {
            return -1;
        }
        return manager.createTextDisplay(new com.huidu.farmersdelight.visual.ItemDisplayManager.TextDisplaySpec(
                location, text, transformation, backgroundColor, shadowed, seeThrough));
    }

    public boolean updateTextDisplay(int handle, net.kyori.adventure.text.Component text) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        return manager != null && text != null && manager.updateText(handle, text);
    }

    public void removeTextDisplay(int handle) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        if (manager != null) {
            manager.destroyDisplay(handle);
        }
    }

    public boolean isTextDisplayActive(int handle) {
        com.huidu.farmersdelight.visual.ItemDisplayManager manager = displayManager();
        return manager != null && manager.isActive(handle);
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
