package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockEntityController;
import com.huidu.farmersdelight.api.block.SkilletSnapshot;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.util.compat.CraftEngineModelMappings;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacketProxy;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class SkilletManager {

    // ---- hand-held cooking, delegated to SkilletHandheldCooking -------------------------------
    // Only these five members are read from outside (SkilletItemBehavior, SkilletLifecycleListener);
    // everything else about hand-held sessions lives in the extracted class.
    //
    // Every one of them guards for null even though the constructor always assigns the field: listeners are
    // registered after it, but a partially-built manager (a throw part-way through the constructor) would
    // otherwise turn every later skillet interaction into an NPE instead of a no-op.

    /** Hand-held cooking; assigned in the constructor after the display models it depends on exist. */
    private SkilletHandheldCooking handheldCooking;

    public boolean handleHandheldInteract(Player player, EquipmentSlot skilletHand,
                                          NamespacedKey cookingModel, NamespacedKey overlayModel,
                                          Map<String, NamespacedKey> ingredientModels) {
        return handheldCooking != null && handheldCooking.handleHandheldInteract(player, skilletHand,
                cookingModel, overlayModel, ingredientModels);
    }

    public void stopHandheldUse(Player player, ItemStack releasedItem) {
        if (handheldCooking != null) {
            handheldCooking.stopHandheldUse(player, releasedItem);
        }
    }

    public boolean isHandheldCooking(Player player) {
        return handheldCooking != null && handheldCooking.isHandheldCooking(player);
    }

    public boolean hasHandheldIngredient(ItemStack skillet) {
        return handheldCooking != null && handheldCooking.hasHandheldIngredient(skillet);
    }

    /** Defaults to true, matching the config default, so an unbuilt manager does not report "disabled". */
    public boolean isHandheldCookingEnabled() {
        return handheldCooking == null || handheldCooking.cookingEnabled();
    }


    private static final int HEARTBEAT_LOG_INTERVAL = 20;
    private static final int DEFAULT_TICK_BUDGET = 512;
    static final int PLACED_TICK_INTERVAL = 4;
    // Upper bound on the ticks one placed-skillet visit may credit. A skillet starved by the tick budget is
    // revisited every 4 * (count / budget) ticks rather than every 4, and that real elapsed time is credited
    // so it cooks in real time; the cap only keeps a long region stall from crediting an absurd batch at once.
    private static final int MAX_ELAPSED_CREDIT_TICKS = 1200;
    private static final int DEFAULT_COOK_TIME = Constants.DEFAULT_COOKING_TIME_SKILLET;
    private static final int DEFAULT_MIN_COOK_TIME = 60;
    private static final int DEFAULT_COOLING_DECREMENT = 2;
    private static final int DEFAULT_RELOAD_VISUAL_REFRESH_BUDGET = 32;
    private static final double DEFAULT_COOK_TIME_MULTIPLIER = Constants.SKILLET_COOKING_TIME_REDUCTION;
    private static final double DEFAULT_FIRE_ASPECT_BONUS = Constants.SKILLET_FIRE_ASPECT_BONUS;
    // Heat-source state changes rarely (only on block break/place below the skillet). Cache the result
    // for this many ticks so the per-tick hasHeatSource probe — which does two getBlockAt + HeatSourceConfig
    // queries — is skipped in the steady state. 20 ticks = 1s max staleness, acceptable for cook progress.
    private static final long HEAT_SOURCE_CACHE_TTL = 20L;

    private final FarmersDelightPlugin plugin;
    private final SkilletVisualManager visualManager;
    private final SkilletEffectManager effectManager;
    private final Map<Location, SkilletData> skillets = new ConcurrentHashMap<>();
    private final WorldChunkLocationIndex locationIndex = new WorldChunkLocationIndex();
    private final Set<Location> scheduledSkilletTicks = ConcurrentHashMap.newKeySet();
    private final Object visualRefreshLock = new Object();
    private final Deque<SkilletData> pendingVisualRefreshes = new ArrayDeque<>();
    private final AtomicLong tickLocationsVersion = new AtomicLong();
    private volatile List<Location> tickLocationsSnapshot = List.of();
    private volatile long tickLocationsSnapshotVersion = -1L;
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("skillet", this::debug);
    // Region events start tickTask; the global task may cancel it when idle.
    // Volatile publishes the handle, and the lock makes start/stop checks atomic to prevent duplicate tasks.
    private volatile PluginTask tickTask;
    private PluginTask visualRefreshTask;
    private final Object tickTaskLock = new Object();
    private int heartbeatTicks;
    private int tickCursor;
    private volatile int tickBudget;
    private volatile int defaultCookingTime = DEFAULT_COOK_TIME;
    private volatile int minCookingTime = DEFAULT_MIN_COOK_TIME;
    private volatile int coolingDecrement = DEFAULT_COOLING_DECREMENT;
    private volatile int reloadVisualRefreshBudget = DEFAULT_RELOAD_VISUAL_REFRESH_BUDGET;
    private volatile double cookTimeMultiplier = DEFAULT_COOK_TIME_MULTIPLIER;
    private volatile double fireAspectBonus = DEFAULT_FIRE_ASPECT_BONUS;

    public SkilletManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.visualManager = new SkilletVisualManager(plugin);
        this.effectManager = new SkilletEffectManager(plugin);
        // Read the settings before the hand-held block exists: this call runs with handheldCooking still null
        // (it only pushes the two switches when the field is set), and it fixes the definite-assignment order,
        // because the supplier below closes over handheldCooking.
        reloadConfig();
        HandheldCookingModels models = new HandheldCookingModels(plugin,
                plugin.getDataFolder().toPath().resolve("generated/handheld-cooking"),
                () -> handheldCooking != null && handheldCooking.cookingEnabled(),
                () -> campfireRecipes.ingredients());
        this.handheldCooking = new SkilletHandheldCooking(plugin, campfireRecipes, models,
                this::awardUseSkillet, defaultCookingTime, minCookingTime, cookTimeMultiplier, fireAspectBonus);
        plugin.getServer().getPluginManager().registerEvents(models, plugin);
        // Second pass so the two hand-held switches actually land in the extracted block.
        reloadConfig();
        campfireRecipes.rebuild();
    }


    public void reloadConfig() {
        this.tickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_TICK_BUDGET,
                "skillet.tick-budget",
                "performance.skillet-tick-budget"));
        this.defaultCookingTime = Math.max(1, plugin.getConfigInt(DEFAULT_COOK_TIME,
                "skillet.cooking.default-cook-time",
                "skillet.default-cook-time"));
        this.minCookingTime = Math.max(1, plugin.getConfigInt(DEFAULT_MIN_COOK_TIME,
                "skillet.cooking.min-cook-time",
                "skillet.min-cook-time"));
        this.coolingDecrement = Math.max(0, plugin.getConfigInt(DEFAULT_COOLING_DECREMENT,
                "skillet.cooking.cooling-decrement",
                "skillet.cooling-decrement"));
        this.reloadVisualRefreshBudget = Math.max(1, plugin.getConfigInt(DEFAULT_RELOAD_VISUAL_REFRESH_BUDGET,
                "performance.budgets.reload-visual-refreshes-per-tick"));
        this.cookTimeMultiplier = ManagerSupport.clampChance(plugin.getConfigDouble(DEFAULT_COOK_TIME_MULTIPLIER,
                "skillet.cooking.cook-time-multiplier",
                "skillet.cooking-time-reduction"));
        this.fireAspectBonus = ManagerSupport.clampChance(plugin.getConfigDouble(DEFAULT_FIRE_ASPECT_BONUS,
                "skillet.cooking.fire-aspect-bonus",
                "skillet.fire-aspect-bonus"));
        if (handheldCooking != null) {
            handheldCooking.applyConfig(
                    plugin.getConfigBoolean(true, "skillet.handheld.enabled", "skillet.handheld-cooking.enabled"),
                    plugin.getConfigBoolean(true,
                            "skillet.handheld.progress-display.enabled", "skillet.handheld-progress-display.enabled"),
                    defaultCookingTime, minCookingTime, cookTimeMultiplier, fireAspectBonus);
        }
        effectManager.reloadConfig();
        refreshVisualsAfterConfigReload();
    }

    private void ensureTaskRunning() {
        if (tickTask != null) {
            return;
        }
        synchronized (tickTaskLock) {
            if (tickTask != null) {
                return;
            }
            debug("tick task: starting skillet tick task");
            tickTask = plugin.scheduler().runRepeating(this::tick, 1L, PLACED_TICK_INTERVAL);
        }
    }

    private void stopTaskIfIdle() {
        if (tickTask == null || !skillets.isEmpty()) {
            return;
        }
        synchronized (tickTaskLock) {
            if (tickTask != null && skillets.isEmpty()) {
                debug("tick task: stopping skillet tick task because no skillets remain");
                tickTask.cancel();
                tickTask = null;
                heartbeatTicks = 0;
            }
        }
    }

    public SkilletData getOrCreateSkillet(Location location) {
        ensureTaskRunning();
        Location normalized = ManagerSupport.normalize(location);
        SkilletData existing = skillets.get(normalized);
        if (existing != null) {
            return existing;
        }

        // Saved data may still be parked on the controller (deferred startup load, or a chunk served from
        // CraftEngine's chunk cache where loadCustomData never re-ran); apply it before creating a blank
        // entry that would shadow the stored contents and let the late apply overwrite this interaction.
        if (normalized.getWorld() != null) {
            flushControllerPendingData(normalized);
            SkilletData loaded = skillets.get(normalized);
            if (loaded != null) {
                return loaded;
            }
        }

        SkilletData created = new SkilletData(normalized, defaultCookingTime);
        SkilletData previous = skillets.putIfAbsent(normalized, created);
        if (previous != null) {
            return previous;
        }
        locationIndex.add(normalized);
        markTickLocationsDirty();
        return created;
    }

    public void collectLiveDisplayIds(Set<Integer> out) {
        for (SkilletData skillet : skillets.values()) {
            out.addAll(skillet.displayEntityIds);
        }
    }

    public void recordPlacedSkillet(Location location, ItemStack skilletItem) {
        if (location == null || skilletItem == null || skilletItem.getType().isAir()) return;

        SkilletData skillet = getOrCreateSkillet(location);
        skillet.skilletStack = skilletItem.clone();
        skillet.skilletStack.setAmount(1);
        skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        saveSkillet(location, skillet);

        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.checkAndPlaceTray(location);
        }
    }

    /**
     * The item a placed skillet hands over when a player picks the block (creative middle-click): the stack the
     * block was placed from, i.e. the item it would also drop. Handing over the snapshot keeps the enchantments
     * and, because it is the very stack the player still holds, lets the pick select that stack instead of
     * adding a second skillet next to it. Null means the block is not tracked (or its item is unknown), and
     * CraftEngine falls back to the block's own item id.
     */
    public ItemStack pickupSnapshot(Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return null;
        }
        SkilletData skillet = skillets.get(ManagerSupport.normalize(blockLocation));
        if (skillet == null) {
            return null;
        }
        // Same monitor the tick and break paths use, so the snapshot cannot be read mid-replacement.
        synchronized (skillet) {
            if (skillet.skilletStack == null || skillet.skilletStack.getType().isAir()) {
                return null;
            }
            ItemStack snapshot = skillet.skilletStack.clone();
            snapshot.setAmount(1);
            return snapshot;
        }
    }

    public boolean handleInteract(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        // Inner defense: never consume equippable items as cooking ingredients, even if the
        // CraftEngine useOnBlock PASSTHROUGH path didn't catch them (armor-swap timing race).
        if (itemInHand != null && !itemInHand.getType().isAir()
                && SkilletBlockBehavior.isEquippable(itemInHand)) {
            return false;
        }
        Location location = ManagerSupport.normalize(block.getLocation());
        SkilletData skillet = getOrLoadSkillet(location);
        ensurePlacedSkilletState(skillet);
        ItemStack heldItem = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        // The SkilletData object is the per-block monitor: concurrent empty-hand takes, stacks, and a
        // racing break (which also locks on the same SkilletData) all serialize so storedItem can only
        // leave the skillet once.
        synchronized (skillet) {
        if (heldItem == null || heldItem.getType().isAir()) {
            if (!skillet.hasItem()) {
                return false;
            }

            ItemStack toReturn = skillet.storedItem.clone();
            debug("retrieve: returning=" + formatItem(toReturn) + ", hand=" + hand + ", location=" + formatLocation(location));
            skillet.storedItem = null;
            skillet.currentRecipe = null;
            skillet.cookingProgress = 0;
            cleanupVisual(skillet);
            saveSkillet(location, skillet);

            if (hand == EquipmentSlot.OFF_HAND && player.getInventory().getItemInOffHand().getType().isAir()) {
                player.getInventory().setItemInOffHand(toReturn);
            } else if (hand != EquipmentSlot.OFF_HAND && player.getInventory().getItemInMainHand().getType().isAir()) {
                player.getInventory().setItemInMainHand(toReturn);
            } else {
                ItemDelivery.giveOrDrop(player, location.clone().add(0.5, 1.0, 0.5), toReturn);
            }
            block.getWorld().playSound(location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            return true;
        }

        if (skillet.hasItem()) {
            CookingRecipe<?> recipe = findCampfireRecipe(heldItem);
            if (recipe == null || skillet.currentRecipe == null) {
                debug("recipe match: stack add failed, recipe missing. input=" + formatItem(heldItem)
                        + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location));
                return false;
            }

            if (canStackWithStored(skillet.storedItem, heldItem, skillet.currentRecipe, recipe)) {
                debug("recipe match: stack add failed, recipe/item mismatch. inputRecipe=" + recipe.getKey()
                        + ", storedRecipe=" + skillet.currentRecipe.getKey() + ", input=" + formatItem(heldItem)
                        + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location));
                return false;
            }

            int maxStack = skillet.storedItem.getMaxStackSize();
            int freeSpace = Math.max(0, maxStack - skillet.storedItem.getAmount());
            if (freeSpace <= 0) {
                return false;
            }

            int requestedAmount = heldItem.getAmount();
            int toMove = Math.min(freeSpace, requestedAmount);
            if (toMove <= 0) {
                return false;
            }
            debug("recipe match: stacking onto skillet, recipe=" + recipe.getKey() + ", move=" + toMove
                    + ", storedBefore=" + formatItem(skillet.storedItem) + ", input=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
            int previousAmount = skillet.storedItem.getAmount();
            skillet.storedItem.setAmount(previousAmount + toMove);
            UUID previousOwnerId = skillet.ownerId;
            String previousOwnerName = skillet.ownerName;
            skillet.ownerId = player.getUniqueId();
            skillet.ownerName = player.getName();
            debug("create state: stacked item now=" + formatItem(skillet.storedItem)
                    + ", recipe=" + skillet.currentRecipe.getKey() + ", location=" + formatLocation(location));
            try {
                createVisual(location, skillet);
                saveSkillet(location, skillet);
            } catch (RuntimeException | LinkageError failure) {
                // Nothing has been taken from the player yet, so the stored food is put back exactly as it was:
                // a failing display or save must not leave the same items in the hand and in the skillet.
                skillet.storedItem.setAmount(previousAmount);
                skillet.ownerId = previousOwnerId;
                skillet.ownerName = previousOwnerName;
                throw failure;
            }

            if (player.getGameMode() != GameMode.CREATIVE) {
                debug("consume: stacked move=" + toMove + ", before=" + heldItem.getAmount()
                        + ", after=" + (heldItem.getAmount() - toMove) + ", item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
                heldItem.setAmount(heldItem.getAmount() - toMove);
            } else {
                debug("consume: skipped for creative mode while stacking, item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
            }

            SoundUtils.play(block.getWorld(), location, getAddFoodSound(location), Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.0f);
            return true;
        }

        if (isTool(heldItem) || isSkilletItem(heldItem)) {
            return false;
        }

        CookingRecipe<?> recipe = findCampfireRecipe(heldItem);
        if (recipe == null) {
            debug("recipe match: no campfire recipe for input=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
            return false;
        }
        debug("recipe match: recipe=" + recipe.getKey() + ", input=" + formatItem(heldItem)
                + ", location=" + formatLocation(location));

        ItemStack toPlace = heldItem.clone();
        int placeAmount = heldItem.getAmount();
        toPlace.setAmount(placeAmount);
        StoredState previousState = captureStoredState(skillet);
        skillet.storedItem = toPlace;
        skillet.currentRecipe = recipe;
        skillet.cookingDuration = getAdjustedCookingTime(recipe.getCookingTime(), skillet.fireAspectLevel);
        skillet.cookingProgress = 0;
        skillet.ownerId = player.getUniqueId();
        skillet.ownerName = player.getName();
        debug("create state: stored=" + formatItem(toPlace) + ", recipe=" + recipe.getKey()
                + ", duration=" + skillet.cookingDuration + ", fireAspect=" + skillet.fireAspectLevel
                + ", location=" + formatLocation(location));

        try {
            createVisual(location, skillet);
            saveSkillet(location, skillet);
        } catch (RuntimeException | LinkageError failure) {
            // The hand stack has not been consumed yet, so restoring the previous stored state keeps the food
            // in exactly one place when the display or the persistence step fails.
            restoreStoredState(skillet, previousState);
            throw failure;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            debug("consume: before=" + heldItem.getAmount() + ", after=" + (heldItem.getAmount() - placeAmount)
                    + ", moved=" + placeAmount + ", item=" + formatItem(heldItem) + ", location=" + formatLocation(location));
            heldItem.setAmount(heldItem.getAmount() - placeAmount);
        } else {
            debug("consume: skipped for creative mode, moved=" + placeAmount + ", item=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
        }

        SoundUtils.play(block.getWorld(), location, getAddFoodSound(location), Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.0f);
        return true;
        }
    }

    public ItemStack getStoredItemSnapshot(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null) {
            return null;
        }
        SkilletData skillet = skillets.get(normalized);
        if (skillet == null || !skillet.hasItem()) {
            return null;
        }
        return skillet.storedItem.clone();
    }

    // Stored-food fields of one skillet, captured before an interaction mutates them so a failure in the
    // display or persistence step can be rolled back instead of duplicating the item.
    private record StoredState(ItemStack item, CookingRecipe<?> recipe, int duration, int progress,
                               UUID ownerId, String ownerName) {
    }

    private static StoredState captureStoredState(SkilletData skillet) {
        return new StoredState(skillet.storedItem, skillet.currentRecipe, skillet.cookingDuration,
                skillet.cookingProgress, skillet.ownerId, skillet.ownerName);
    }

    private static void restoreStoredState(SkilletData skillet, StoredState state) {
        skillet.storedItem = state.item();
        skillet.currentRecipe = state.recipe();
        skillet.cookingDuration = state.duration();
        skillet.cookingProgress = state.progress();
        skillet.ownerId = state.ownerId();
        skillet.ownerName = state.ownerName();
    }

    public SkilletSnapshot snapshot(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null) {
            return null;
        }
        SkilletData skillet = skillets.get(normalized);
        if (skillet == null) {
            return null;
        }
        boolean heated = computeHasHeatSource(normalized);
        synchronized (skillet) {
            CookingRecipe<?> recipe = skillet.currentRecipe;
            return new SkilletSnapshot(
                    normalized,
                    skillet.storedItem,
                    skillet.skilletStack,
                    recipe == null ? null : recipe.getKey().toString(),
                    skillet.cookingProgress,
                    skillet.cookingDuration,
                    Math.max(0, skillet.cookingDuration - skillet.cookingProgress),
                    heated,
                    skillet.fireAspectLevel);
        }
    }

    public boolean canAcceptHopperInput(Location location, ItemStack item) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || isSkilletBlock(normalized) || isValidHopperInput(item)) {
            return false;
        }

        SkilletData skillet = skillets.get(normalized);
        if (skillet == null) {
            return true;
        }
        // The skillet's data object is the per-block monitor (interactions and breaks lock on it too), so the
        // emptiness/stack checks read a state no concurrent path can change underneath them.
        synchronized (skillet) {
            if (!skillet.hasItem()) {
                return true;
            }

            CookingRecipe<?> incomingRecipe = findCampfireRecipe(item);
            CookingRecipe<?> storedRecipe = skillet.currentRecipe;
            if (storedRecipe == null) {
                storedRecipe = findCampfireRecipe(skillet.storedItem);
            }
            if (canStackWithStored(skillet.storedItem, item, storedRecipe, incomingRecipe)) {
                return false;
            }

            return skillet.storedItem.getAmount() < skillet.storedItem.getMaxStackSize();
        }
    }

    public ItemStack insertHopperInput(Location location, ItemStack item) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || item == null) {
            return ItemUtils.cloneOrNull(item);
        }
        if (isSkilletBlock(normalized) || isValidHopperInput(item)) {
            return item.clone();
        }

        SkilletData skillet = getOrLoadSkillet(normalized);
        ensurePlacedSkilletState(skillet);
        // Same per-block monitor as the interaction and break paths: a hopper insert runs on the region thread
        // that owns the hopper, which is not necessarily the one owning the block entity, so without the lock
        // a concurrent take could hand the same stored stack out twice (or drop an insert).
        synchronized (skillet) {
            CookingRecipe<?> incomingRecipe = findCampfireRecipe(item);
            ItemStack pending = item.clone();
            if (incomingRecipe == null) {
                return pending;
            }

            if (skillet.hasItem()) {
                CookingRecipe<?> storedRecipe = skillet.currentRecipe;
                if (storedRecipe == null) {
                    storedRecipe = findCampfireRecipe(skillet.storedItem);
                    skillet.currentRecipe = storedRecipe;
                }
                if (canStackWithStored(skillet.storedItem, pending, storedRecipe, incomingRecipe)) {
                    return pending;
                }

                int freeSpace = Math.max(0, skillet.storedItem.getMaxStackSize() - skillet.storedItem.getAmount());
                int toMove = Math.min(freeSpace, pending.getAmount());
                if (toMove <= 0) {
                    return pending;
                }

                debug("hopper input: stacking onto skillet, move=" + toMove + ", storedBefore="
                        + formatItem(skillet.storedItem) + ", input=" + formatItem(pending)
                        + ", location=" + formatLocation(normalized));
                skillet.storedItem.setAmount(skillet.storedItem.getAmount() + toMove);
                createVisual(normalized, skillet);
                saveSkillet(normalized, skillet);
                return remainingAfterMove(pending, toMove);
            }

            int toMove = Math.min(pending.getAmount(), pending.getMaxStackSize());
            if (toMove <= 0) {
                return pending;
            }

            ItemStack toPlace = pending.clone();
            toPlace.setAmount(toMove);
            skillet.storedItem = toPlace;
            skillet.currentRecipe = incomingRecipe;
            skillet.cookingDuration = getAdjustedCookingTime(incomingRecipe.getCookingTime(), skillet.fireAspectLevel);
            skillet.cookingProgress = 0;
            skillet.ownerId = null;
            skillet.ownerName = null;
            debug("hopper input: stored=" + formatItem(toPlace) + ", recipe=" + incomingRecipe.getKey()
                    + ", duration=" + skillet.cookingDuration + ", fireAspect=" + skillet.fireAspectLevel
                    + ", location=" + formatLocation(normalized));

            createVisual(normalized, skillet);
            saveSkillet(normalized, skillet);
            return remainingAfterMove(pending, toMove);
        }
    }

    public boolean canCook(ItemStack item) {
        return findCampfireRecipe(item) != null;
    }

    private boolean isValidHopperInput(ItemStack item) {
        return item == null
                || item.getType().isAir()
                || isTool(item)
                || isSkilletItem(item)
                || findCampfireRecipe(item) == null;
    }

    private ItemStack remainingAfterMove(ItemStack source, int moved) {
        if (source == null || source.getType().isAir()) {
            return null;
        }
        int remaining = source.getAmount() - moved;
        if (remaining <= 0) {
            return null;
        }
        ItemStack result = source.clone();
        result.setAmount(remaining);
        return result;
    }

    public String findRecipeId(ItemStack item) {
        CookingRecipe<?> recipe = findCampfireRecipe(item);
        if (recipe != null) {
            return recipe.getKey().toString();
        }
        return "null";
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String materialName = item.getType().name();
        if (materialName.endsWith("_SWORD")
                || materialName.endsWith("_AXE")
                || materialName.endsWith("_HOE")
                || materialName.endsWith("_SHOVEL")
                || materialName.endsWith("_PICKAXE")
                || materialName.equals("SHEARS")
                || materialName.equals("FLINT_AND_STEEL")) {
            return true;
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null && customItemId.contains("knife")) {
            return true;
        }

        return plugin.isKnife(item);
    }

    private boolean isSkilletItem(ItemStack item) {
        String customItemId = ItemUtils.getCustomItemId(item);
        return Constants.ITEM_SKILLET.equals(customItemId);
    }

    private boolean isSkilletBlock(Location location) {
        return !CustomBlockUtils.hasBehavior(location, SkilletBlockBehavior.class)
                && !CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
    }

    private boolean isSkilletBlock(ImmutableBlockState state) {
        return CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)
                || Constants.BLOCK_SKILLET.equals(CustomBlockUtils.getId(state));
    }

    public void breakSkillet(Location blockLocation, Location dropLocation) {
        breakSkillet(blockLocation, dropLocation, true);
    }

    private void flushControllerPendingData(Location location) {
        if (location == null || location.getWorld() == null) return;
        CustomBlockUtils.notifyControllerChanged(location.getWorld(), new BlockPosKey(location),
                    SkilletBlockEntityController.class, null,
                    SkilletBlockEntityController::loadPendingDataIfReady);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        breakSkillet(blockLocation, dropLocation, shouldDropItems, false);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems, boolean explosion) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        flushControllerPendingData(normalized);
        SkilletData skillet = removeTrackedSkillet(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
            // Lock on the same SkilletData monitor as handleInteract so a racing empty-hand take can't
            // observe storedItem mid-clear and pocket a clone that is then also dropped here (dup).
            synchronized (skillet) {
                if (shouldDropItems && skillet.storedItem != null && !skillet.storedItem.getType().isAir()) {
                    normalized.getWorld().dropItemNaturally(dropLocation, skillet.storedItem.clone());
                    skillet.storedItem = null;
                }
                // Skip the manual base-item drop on explosion: CraftEngine's block loot table (default/self)
                // already drops the skillet item on an explosion (like the cooking pot), so dropping it here
                // too would duplicate it. The player-break path suppresses that CE loot (setDropItems(false))
                // and relies on this manual drop to preserve enchantments; an exploded skillet drops a plain
                // one from the loot table instead. The stored food (not in the loot table) still drops above.
                if (shouldDropItems && !explosion) {
                    ItemStack skilletDrop = skillet.skilletStack != null && !skillet.skilletStack.getType().isAir()
                            ? skillet.skilletStack.clone()
                            : ItemUtils.createItem(Constants.ITEM_SKILLET);
                    if (skilletDrop != null && !skilletDrop.getType().isAir()) {
                        skilletDrop.setAmount(1);
                        normalized.getWorld().dropItemNaturally(dropLocation, skilletDrop);
                    }
                }
            }
        }
        // Only clean up when a skillet actually exists (in memory), to avoid a wasted CEWorld dirty mark on every normal block break.
        if (skillet != null) {
            removeStoredData(normalized);
        }
        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.removeTrayIfAutoPlaced(normalized);
        }
    }

    public void saveAllData() {
        ManagerSupport.saveAllData(skillets, this::saveSkillet);
    }

    public void saveWorldData(World world) {
        if (world == null) {
            return;
        }
        List<Location> locations = locationIndex.worldLocations(world.getUID());
        if (locations.isEmpty()) {
            return;
        }
        for (Location location : locations) {
            SkilletData skillet = skillets.get(location);
            if (skillet == null) {
                continue;
            }
            // Unlike the chunk path this runs at NORMAL: CE serializes the world's chunks at
            // WorldUnloadEvent HIGHEST, after this handler. If the unload is cancelled by another plugin,
            // the first interaction re-hydrates from the snapshot via the entry-creation flush.
            passivateOrSave(world, location, skillet);
        }
    }

    public void cleanupWorld(UUID worldId) {
        effectManager.cleanupWorld(worldId);
        List<Location> locations = locationIndex.removeWorld(worldId);
        if (locations.isEmpty()) {
            return;
        }

        for (Location location : locations) {
            SkilletData skillet = skillets.remove(location);
            if (skillet != null) {
                scheduledSkilletTicks.remove(location);
                cleanupVisual(skillet);
            }
        }
        markTickLocationsDirty();
        stopTaskIfIdle();
    }

    public void saveAndUnloadChunk(World world, int minX, int maxX, int minZ, int maxZ) {
        if (world == null) {
            return;
        }
        long chunkKey = ManagerSupport.chunkKey(minX >> 4, minZ >> 4);
        effectManager.cleanupChunk(world.getUID(), chunkKey);
        List<Location> locations = locationIndex.chunkLocationsAtBlock(world.getUID(), minX, minZ);
        if (locations.isEmpty()) {
            return;
        }
        for (Location location : locations) {
            if (location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ) {
                SkilletData skillet = skillets.get(location);
                if (skillet == null) {
                    removeSkillet(location);
                    continue;
                }
                passivateOrSave(world, location, skillet);
            }
        }
    }

    /**
     * Snapshots the skillet into its block-entity controller and drops the live entry, falling back to a
     * plain save when the controller is unreachable (the entry then stays for the next load to reconcile).
     * Both unload paths must snapshot BEFORE removing the entry: CE serializes at HIGHEST, after their
     * handlers, by pulling from this manager — removing first would export nothing and wipe the data.
     */
    private void passivateOrSave(World world, Location location, SkilletData skillet) {
        if (passivateToController(world, location)) {
            removeSkillet(location);
        } else {
            saveSkillet(location, skillet);
        }
    }

    private boolean passivateToController(World world, Location location) {
        boolean[] stashed = {false};
        CustomBlockUtils.notifyControllerChanged(world, new BlockPosKey(location),
                    SkilletBlockEntityController.class, null,
                controller -> stashed[0] = controller.passivate());
        return stashed[0];
    }

    public boolean loadSkillet(World world, BlockPos pos, Map<String, Object> data) {
        return loadSkillet(world, new BlockPosKey(pos), data);
    }

    public boolean loadSkillet(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null) return true;

        Location location = ManagerSupport.toLocation(world, posKey);
        if (location == null) return false;
        if (isSkilletBlock(location)) {
            // The CE state can be transiently unresolvable (a /ce reload unbinds states for the parse
            // window); keep the data parked instead of discarding it, so a live skillet's contents are
            // not destroyed. A genuinely replaced block just carries inert leftover NBT.
            return false;
        }
        if (skillets.containsKey(ManagerSupport.normalize(location))) {
            // A live entry exists (created by an interaction before this deferred load applied); the live
            // state is newer than the saved snapshot, so consume the snapshot without overwriting it.
            return true;
        }

        SkilletData skillet = new SkilletData(location, defaultCookingTime);

        if (data.get("storedItem") instanceof ItemStack storedItem) {
            skillet.storedItem = storedItem.clone();
        }
        if (data.get("skilletStack") instanceof ItemStack skilletStack) {
            skillet.skilletStack = skilletStack.clone();
            skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        }
        if (data.get("cookingProgress") instanceof Number progress) {
            skillet.cookingProgress = progress.intValue();
        }
        if (data.get("cookingDuration") instanceof Number duration) {
            skillet.cookingDuration = duration.intValue();
        }
        if (data.get("ownerId") instanceof String ownerId) {
            try {
                skillet.ownerId = UUID.fromString(ownerId);
            } catch (IllegalArgumentException ignored) {
                skillet.ownerId = null;
            }
        }
        if (data.get("ownerName") instanceof String ownerName) {
            skillet.ownerName = ownerName;
        }
        if (skillet.storedItem != null && !skillet.storedItem.getType().isAir()) {
            skillet.currentRecipe = findCampfireRecipe(skillet.storedItem);
            createVisual(location, skillet);
        }
        if (shouldPersistSkillet(skillet)) {
            putSkillet(location, skillet);
            markSkilletDirty(location);
            ensureTaskRunning();
        } else {
            removeStoredData(location);
        }
        return true;
    }

    private SkilletData getOrLoadSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = skillets.get(normalized);
        if (skillet != null) {
            ensureTaskRunning();
            return skillet;
        }

        return getOrCreateSkillet(normalized);
    }

    /**
     * Cancels the placed/handheld tick tasks without dropping any state, for the window between the
     * shutdown-time save and the in-memory cleanup: a skillet that keeps cooking after its data was written
     * would drop items the disk copy still lists, and both tasks read the plugin's tick manager, which
     * shutdown clears first.
     */
    public void suspendTickTasks() {
        handheldCooking.suspendTickTask();
        synchronized (tickTaskLock) {
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
        }
        synchronized (visualRefreshLock) {
            stopVisualRefreshTask();
        }
    }

    public void cleanup() {
        handheldCooking.shutdown();
        synchronized (visualRefreshLock) {
            pendingVisualRefreshes.clear();
            stopVisualRefreshTask();
        }
        synchronized (tickTaskLock) {
            if (tickTask != null) {
                tickTask.cancel();
                tickTask = null;
            }
        }
        for (SkilletData skillet : skillets.values()) {
            cleanupVisual(skillet);
        }
        skillets.clear();
        effectManager.cleanup();
        locationIndex.clear();
        scheduledSkilletTicks.clear();
        tickLocationsSnapshot = List.of();
        markTickLocationsDirty();
    }

    public Collection<Location> getTrackedLocations(World world) {
        if (world == null) {
            return List.of();
        }

        List<Location> indexed = locationIndex.worldLocations(world.getUID());
        if (indexed.isEmpty()) {
            return List.of();
        }

        List<Location> result = new ArrayList<>(indexed.size());
        for (Location loc : indexed) {
            result.add(loc.clone());
        }
        return result;
    }

    public Collection<Location> getTrackedLocations() {
        List<Location> result = new ArrayList<>(skillets.size());
        for (Location location : skillets.keySet()) {
            if (location != null && location.getWorld() != null) {
                result.add(location.clone());
            }
        }
        return result;
    }

    public boolean hasTrackedSkillets() {
        return !skillets.isEmpty();
    }

    public void reloadRecipeCache() {
        campfireRecipes.rebuild();
    }

    /**
     * The shared campfire recipe cache (one instance, one rebuild lifecycle). The handheld skewer path queries
     * the same cache instead of scanning the recipe table a second time.
     */
    public CampfireRecipeCache campfireRecipes() {
        return campfireRecipes;
    }

    private void putSkillet(Location location, SkilletData skillet) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData previous = skillets.put(normalized, skillet);
        if (previous != null && previous != skillet) {
            // Overwriting a still-tracked skillet (double chunk-load / reload re-scan): destroy the old
            // entry's item display so it doesn't orphan (the incoming skillet already created its own).
            cleanupVisual(previous);
        }
        locationIndex.add(normalized);
        markTickLocationsDirty();
    }

    private SkilletData removeTrackedSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData removed = skillets.remove(normalized);
        if (removed != null) {
            scheduledSkilletTicks.remove(normalized);
            locationIndex.remove(normalized);
            markTickLocationsDirty();
        }
        return removed;
    }

    private void markTickLocationsDirty() {
        tickLocationsVersion.incrementAndGet();
    }

    private List<Location> getTickLocationsSnapshot() {
        long version = tickLocationsVersion.get();
        List<Location> snapshot = tickLocationsSnapshot;
        if (tickLocationsSnapshotVersion == version) {
            return snapshot;
        }

        List<Location> refreshed = new ArrayList<>(skillets.size());
        for (Location location : skillets.keySet()) {
            if (location != null && location.getWorld() != null) {
                refreshed.add(location);
            }
        }
        List<Location> updated = refreshed.isEmpty() ? List.of() : Collections.unmodifiableList(refreshed);
        tickLocationsSnapshot = updated;
        tickLocationsSnapshotVersion = version;
        return updated;
    }

    private void removeSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = removeTrackedSkillet(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
        }
        stopTaskIfIdle();
    }

    private boolean canStackWithStored(ItemStack stored, ItemStack incoming, CookingRecipe<?> storedRecipe, CookingRecipe<?> incomingRecipe) {
        if (stored == null || incoming == null || storedRecipe == null || incomingRecipe == null) {
            return true;
        }

        if (!storedRecipe.getKey().equals(incomingRecipe.getKey())) {
            return true;
        }

        ItemStack storedSingle = stored.clone();
        storedSingle.setAmount(1);
        ItemStack incomingSingle = incoming.clone();
        incomingSingle.setAmount(1);
        return !storedSingle.isSimilar(incomingSingle);
    }

    private int getAdjustedCookingTime(int baseTime, int fireAspectLevel) {
        int cookingTime = baseTime > 0 ? baseTime : defaultCookingTime;
        int cookingSeconds = cookingTime / 20;
        double cookingTimeReduction = cookTimeMultiplier;

        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * fireAspectBonus;
        }
        cookingTimeReduction = Math.max(0.0D, cookingTimeReduction);

        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        return Math.min(cookingTime, Math.max(minCookingTime, result));
    }

    private void tick() {
        if (skillets.isEmpty()) {
            stopTaskIfIdle();
            return;
        }
        if (++heartbeatTicks >= HEARTBEAT_LOG_INTERVAL) {
            heartbeatTicks = 0;
            debug(() -> "tick heartbeat: activeSkillets=" + skillets.size());
        }
        List<Location> snapshot = getTickLocationsSnapshot();
        int size = snapshot.size();
        if (size == 0) {
            tickCursor = 0;
            stopTaskIfIdle();
            return;
        }
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;

        for (int processed = 0; processed < budget; processed++) {
            Location location = snapshot.get((start + processed) % size);
            SkilletData skillet = skillets.get(location);
            if (skillet == null) {
                markTickLocationsDirty();
                continue;
            }

            scheduleSkilletTick(location, skillet);
        }
        tickCursor = size == 0 ? 0 : (start + Math.max(1, budget)) % size;
        stopTaskIfIdle();
    }

    private void scheduleSkilletTick(Location location, SkilletData skillet) {
        if (!plugin.scheduler().isFolia()) {
            tickSkillet(location, skillet);
            return;
        }

        if (!scheduledSkilletTicks.add(location)) {
            return;
        }
        try {
            plugin.scheduler().runAt(location, () -> {
                try {
                    tickSkillet(location, skillet);
                } finally {
                    scheduledSkilletTicks.remove(location);
                }
            });
        } catch (RuntimeException e) {
            scheduledSkilletTicks.remove(location);
        }
    }

    private void tickSkillet(Location location, SkilletData skillet) {
        PerformanceMonitor.Timing timing = plugin.getTickManager().featureTiming(PerformanceMonitor.Feature.SKILLET);
        long started = timing == null ? 0L : System.nanoTime();
        try {
            advancePlacedCooking(location, skillet);
        } catch (Throwable t) {
            // One bad block must not escape the repeating task: on Paper it would abort the whole pass, and
            // on Folia the throw would leave this location's in-flight guard set forever. Throttled per world.
            plugin.getTickManager().warnFeatureFailure("tick-skillet", I18n.formatNamedArgs(
                    "console.tick.error_ticking",
                    "type", "skillet",
                    "pos", new BlockPosKey(location).toString(),
                    "error", String.valueOf(t.getMessage())), location.getWorld(), t);
        } finally {
            if (timing != null) timing.record(System.nanoTime() - started);
        }
    }

    private void advancePlacedCooking(Location location, SkilletData skillet) {
        if (skillets.get(location) != skillet) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        ImmutableBlockState carrierState = CustomBlockUtils.getState(location.getBlock());
        if (!isSkilletBlock(carrierState)) {
            // A /ce reload unbinds custom states for its parse window while the injected server block
            // is still in the world; skip the tick instead of tearing the skillet down mid-reload
            // (the teardown below marks the chunk dirty with the entry gone = wipes the saved data).
            if (CraftEngineBlocks.isCustomBlock(location.getBlock())) {
                return;
            }
            debug(() -> "tick remove: skillet carrier block is gone at " + formatLocation(location));
            cleanupVisual(skillet);
            removeStoredData(location);
            removeTrackedSkillet(location);
            stopTaskIfIdle();
            return;
        }
        if (!skillet.hasItem()) {
            if (!hasPlacedSkillet(skillet)) {
                debug(() -> "tick remove: skillet has no base item snapshot and no stored food at " + formatLocation(location));
                removeStoredData(location);
                removeTrackedSkillet(location);
            }
            return;
        }

        ensureVisualsExist(location, skillet);

        // A skillet with no matching recipe can never cook, so just cool down and return, skipping the per-tick heat-source probe
        // (heat detection often does a CraftEngine custom block-state lookup).
        if (skillet.currentRecipe == null) {
            skillet.advanceCookingProgress(skillet.elapsedSinceLastCredit(
                    Bukkit.getCurrentTick(), PLACED_TICK_INTERVAL, MAX_ELAPSED_CREDIT_TICKS), false, coolingDecrement);
            return;
        }

        boolean hasHeat;
        long currentBukkitTick = Bukkit.getCurrentTick();
        if (skillet.lastHeatState != null
                && skillet.heatSourceCheckedTick != Long.MIN_VALUE
                && currentBukkitTick - skillet.heatSourceCheckedTick < HEAT_SOURCE_CACHE_TTL) {
            // Reuse the cached heat-source result within the TTL window. Heat source changes are
            // block-event driven (break/place below the skillet), so 1s max staleness is acceptable
            // for cook-progress gating and saves two getBlockAt + HeatSourceConfig queries per tick.
            hasHeat = skillet.lastHeatState;
        } else {
            hasHeat = computeHasHeatSource(location);
            skillet.heatSourceCheckedTick = currentBukkitTick;
            if (!Objects.equals(skillet.lastHeatState, hasHeat)) {
                debug(() -> "heat state: hasHeat=" + hasHeat + ", progress=" + skillet.cookingProgress
                        + "/" + skillet.cookingDuration + ", recipe="
                        + (skillet.currentRecipe != null ? skillet.currentRecipe.getKey() : "null")
                        + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location)
                        + ", fireAspectLevel=" + skillet.fireAspectLevel);
            }
            skillet.lastHeatState = hasHeat;
        }

        skillet.advanceCookingProgress(skillet.elapsedSinceLastCredit(
                currentBukkitTick, PLACED_TICK_INTERVAL, MAX_ELAPSED_CREDIT_TICKS), hasHeat, coolingDecrement);
        if (!hasHeat) return;

        effectManager.dispatchTickEffects(world, location, carrierState);
        if (skillet.cookingProgress >= skillet.cookingDuration) {
            debug(() -> "tick finish: progress reached duration for " + formatItem(skillet.storedItem)
                    + " at " + formatLocation(location));
            finishCooking(location, skillet);
        }
    }

    private boolean computeHasHeatSource(Location location) {
        // Use world.getBlockAt with raw integer coords instead of location.clone().subtract(...).getBlock(),
        // avoiding two Location object allocations per call (this is a hot path — called once per skillet per
        // tick when the TTL cache is cold).
        World world = location.getWorld();
        if (world == null) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        Block blockBelow = world.getBlockAt(x, y - 1, z);

        // Pre-fetch the CE state of blockBelow once, then share it across isHeatSource
        // and isConductor to avoid two independent CE lookups on the same block.
        ImmutableBlockState belowState = CraftEngineBlocks.getCustomBlockState(blockBelow);
        if (plugin.getHeatSourceConfig().isHeatSource(blockBelow, belowState)) {
            return true;
        }

        if (!plugin.isSkilletConductorsAllowed()) {
            return false;
        }

        if (plugin.getHeatSourceConfig().isConductor(blockBelow, belowState)) {
            Block blockTwoBelow = world.getBlockAt(x, y - 2, z);
            return plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
        }

        return false;
    }

    private void finishCooking(Location location, SkilletData skillet) {
        if (skillet.currentRecipe == null || skillet.storedItem == null) return;
        if (isSkilletBlock(location)) {
            debug("finish cooking: skipped because block is no longer a skillet at " + formatLocation(location));
            cleanupVisual(skillet);
            removeStoredData(location);
            removeTrackedSkillet(location);
            stopTaskIfIdle();
            return;
        }

        ItemStack result = skillet.currentRecipe.getResult();
        debug("finish cooking: result=" + formatItem(result) + ", storedBefore=" + formatItem(skillet.storedItem)
                + ", location=" + formatLocation(location));
        if (result != null) {
            if (skillet.ownerId != null) {
                Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                        skillet.ownerId,
                        skillet.ownerName,
                        "skillet",
                        result,
                        skillet.currentRecipe.getExperience(),
                        location
                ));
            }
            Block block = location.getBlock();
            BlockFace facing = CustomBlockUtils.getFacing(block);
            BlockFace clockwise = getClockWise(facing);

            Location dropLoc = location.clone().add(0.5, 0.3, 0.5);
            var droppedItem = location.getWorld().dropItem(dropLoc, result.clone());
            droppedItem.setVelocity(new Vector(
                    clockwise.getModX() * 0.08,
                    0.25,
                    clockwise.getModZ() * 0.08
            ));
        }

        skillet.storedItem.setAmount(skillet.storedItem.getAmount() - 1);
        if (skillet.storedItem.getAmount() <= 0) {
            skillet.storedItem = null;
            skillet.currentRecipe = null;
            skillet.ownerId = null;
            skillet.ownerName = null;
            cleanupVisual(skillet);
        } else {
            createVisual(location, skillet);
        }

        skillet.cookingProgress = 0;
        saveSkillet(location, skillet);
        location.getWorld().playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
    }

    private void ensurePlacedSkilletState(SkilletData skillet) {
        if (skillet == null || hasPlacedSkillet(skillet)) {
            return;
        }

        ItemStack skilletItem = ItemUtils.createItem(Constants.ITEM_SKILLET);
        if (skilletItem == null || skilletItem.getType().isAir()) {
            return;
        }

        skillet.skilletStack = skilletItem;
        skillet.skilletStack.setAmount(1);
        skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        debug("create state: synthesized missing skillet base item snapshot");
    }

    private String getAddFoodSound(Location location) {
        SkilletBlockBehavior behavior = SkilletBlockBehavior.getBlockBehavior(location);
        if (behavior != null) {
            return behavior.getAddFoodSound();
        }
        return Constants.SOUND_SKILLET_ADD_FOOD;
    }

    private BlockFace getClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private void createVisual(Location location, SkilletData skillet) {
        visualManager.createVisual(location, skillet);
    }

    private void cleanupVisual(SkilletData skillet) {
        visualManager.cleanupVisual(skillet);
    }

    private void ensureVisualsExist(Location location, SkilletData skillet) {
        visualManager.ensureVisualsExist(location, skillet);
    }

    private void refreshVisualsAfterConfigReload() {
        synchronized (visualRefreshLock) {
            pendingVisualRefreshes.clear();
            for (SkilletData skillet : skillets.values()) {
                if (skillet != null && skillet.hasItem() && skillet.location != null) {
                    pendingVisualRefreshes.addLast(skillet);
                }
            }
            if (pendingVisualRefreshes.isEmpty()) {
                stopVisualRefreshTask();
            } else if (visualRefreshTask == null || visualRefreshTask.isCancelled()) {
                visualRefreshTask = plugin.scheduler().runRepeating(this::refreshNextVisuals, 1L, 1L);
            }
        }
    }

    private void refreshNextVisuals() {
        for (int i = 0; i < reloadVisualRefreshBudget; i++) {
            SkilletData skillet;
            synchronized (visualRefreshLock) {
                skillet = pendingVisualRefreshes.pollFirst();
            }
            if (skillet == null) {
                break;
            }

            Location location = skillet.location;
            if (location == null) {
                continue;
            }
            plugin.scheduler().runAt(location, () -> {
                if (skillets.get(location) == skillet) {
                    cleanupVisual(skillet);
                    createVisual(location, skillet);
                }
            });
        }

        synchronized (visualRefreshLock) {
            if (pendingVisualRefreshes.isEmpty()) {
                stopVisualRefreshTask();
            }
        }
    }

    private void stopVisualRefreshTask() {
        if (visualRefreshTask != null) {
            visualRefreshTask.cancel();
            visualRefreshTask = null;
        }
    }


    /**
     * The placed-skillet side looks campfire recipes up directly; hand-held cooking has its own copy of this
     * one-liner inside SkilletHandheldCooking because it owns its own lookups.
     */
    private CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        return campfireRecipes.find(item);
    }

    private boolean hasPlacedSkillet(SkilletData skillet) {
        return skillet != null && skillet.skilletStack != null && !skillet.skilletStack.getType().isAir();
    }

    private boolean shouldPersistSkillet(SkilletData skillet) {
        return skillet != null && (skillet.hasItem() || hasPlacedSkillet(skillet));
    }

    private void saveSkillet(Location location, SkilletData skillet) {
        Location normalized = ManagerSupport.normalize(location);
        ensurePlacedSkilletState(skillet);
        if (!shouldPersistSkillet(skillet)) {
            debug("save state: removing persisted skillet state at " + formatLocation(normalized));
            markSkilletDirty(normalized);
            return;
        }

        markSkilletDirty(normalized);
        debug("save state: hasItem=" + skillet.hasItem() + ", stored=" + formatItem(skillet.storedItem)
                + ", duration=" + skillet.cookingDuration + ", progress=" + skillet.cookingProgress
                + ", hasPlacedSkillet=" + hasPlacedSkillet(skillet) + ", location=" + formatLocation(normalized));
    }

    public Map<String, Object> exportSkilletData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return Map.of();
        }
        SkilletData skillet = skillets.get(ManagerSupport.toLocation(world, posKey));
        if (!shouldPersistSkillet(skillet)) {
            return Map.of();
        }
        Map<String, Object> data = new HashMap<>();
        if (skillet.hasItem()) {
            data.put("storedItem", skillet.storedItem.clone());
            data.put("cookingProgress", skillet.cookingProgress);
            data.put("cookingDuration", skillet.cookingDuration);
            if (skillet.ownerId != null) {
                data.put("ownerId", skillet.ownerId.toString());
            }
            if (skillet.ownerName != null) {
                data.put("ownerName", skillet.ownerName);
            }
        }
        if (skillet.skilletStack != null && !skillet.skilletStack.getType().isAir()) {
            data.put("skilletStack", skillet.skilletStack.clone());
        }
        return data;
    }

    private void markSkilletDirty(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        CustomBlockUtils.markBlockEntityDirty(normalized.getWorld(), new BlockPosKey(normalized));
    }

    private void removeStoredData(Location location) {
        markSkilletDirty(location);
    }

    private void awardUseSkillet(Player player) {
        if (player == null) {
            return;
        }

        AdvancementManager advancementManager = plugin.getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "use_skillet");
        }
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("skillet")) {
            plugin.getLogger().info(I18n.formatConsole("debug.skillet", "message", message));
        }
    }

    private void debug(Supplier<String> messageSupplier) {
        if (plugin.isDebugEnabled("skillet")) {
            plugin.getLogger().info(I18n.formatConsole("debug.skillet", "message", messageSupplier.get()));
        }
    }

    private String formatItem(ItemStack item) {
        return ManagerSupport.formatItem(item);
    }

    private String formatLocation(Location location) {
        return ManagerSupport.formatLocation(location);
    }
}
