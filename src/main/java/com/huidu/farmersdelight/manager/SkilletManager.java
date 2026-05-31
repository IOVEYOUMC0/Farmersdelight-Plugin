package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.*;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.storage.LegacyBlockStorageManager;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import fr.ateastudio.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class SkilletManager {

    private static final String BLOCK_TYPE = "skillet";
    
    private static final int MIN_COOK_TIME = 60;
    private static final int HEARTBEAT_LOG_INTERVAL = 20;
    private static final int DEFAULT_TICK_BUDGET = 512;

    private final FarmersDelightPlugin plugin;
    private final Map<Location, SkilletData> skillets = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> skilletsByWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<Location>>> skilletsByChunk = new ConcurrentHashMap<>();
    private final Set<Location> scheduledSkilletTicks = ConcurrentHashMap.newKeySet();
    private final AtomicLong tickLocationsVersion = new AtomicLong();
    private volatile List<Location> tickLocationsSnapshot = List.of();
    private volatile long tickLocationsSnapshotVersion = -1L;
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("skillet", this::debug);
    private PluginTask tickTask;
    private int heartbeatTicks;
    private int tickCursor;
    private int tickBudget;

    public static class SkilletData {
        final Location location;
        ItemStack storedItem;
        ItemStack skilletStack;
        ItemStack displayedItem;
        BlockFace displayedFacing;
        int cookingProgress = 0;
        int cookingDuration = Constants.DEFAULT_COOKING_TIME_SKILLET;
        CookingRecipe<?> currentRecipe;
        int fireAspectLevel = 0;
        UUID ownerId;
        String ownerName;
        final List<Integer> displayEntityIds = new ArrayList<>();
        Boolean lastHeatState;

        SkilletData(Location location) {
            this.location = location;
        }

        boolean hasItem() {
            return storedItem != null && !storedItem.getType().isAir();
        }
    }

    public SkilletManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
        campfireRecipes.rebuild();
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private long chunkKey(Location location) {
        return chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public void reloadConfig() {
        this.tickBudget = Math.max(1, plugin.getConfigInt(DEFAULT_TICK_BUDGET,
                "skillet.tick-budget",
                "performance.skillet-tick-budget"));
    }

    private void ensureTaskRunning() {
        if (tickTask != null) {
            return;
        }
        debug("tick task: starting skillet tick task");
        tickTask = plugin.scheduler().runRepeating(this::tick, 1L, 4L);
    }

    private void stopTaskIfIdle() {
        if (tickTask != null && skillets.isEmpty()) {
            debug("tick task: stopping skillet tick task because no skillets remain");
            tickTask.cancel();
            tickTask = null;
            heartbeatTicks = 0;
        }
    }

    public SkilletData getOrCreateSkillet(Location location) {
        ensureTaskRunning();
        Location normalized = ManagerSupport.normalize(location);
        SkilletData existing = skillets.get(normalized);
        if (existing != null) {
            return existing;
        }

        SkilletData created = new SkilletData(normalized);
        SkilletData previous = skillets.putIfAbsent(normalized, created);
        if (previous != null) {
            return previous;
        }
        indexSkillet(normalized);
        markTickLocationsDirty();
        return created;
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

    public boolean handleInteract(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        Location location = ManagerSupport.normalize(block.getLocation());
        SkilletData skillet = getOrLoadSkillet(location);
        ensurePlacedSkilletState(skillet);
        ItemStack heldItem = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

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
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toReturn);
                for (ItemStack leftover : leftovers.values()) {
                    block.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), leftover);
                }
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

            if (!canStackWithStored(skillet.storedItem, heldItem, skillet.currentRecipe, recipe)) {
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
            skillet.storedItem.setAmount(skillet.storedItem.getAmount() + toMove);
            skillet.ownerId = player.getUniqueId();
            skillet.ownerName = player.getName();
            debug("create state: stacked item now=" + formatItem(skillet.storedItem)
                    + ", recipe=" + skillet.currentRecipe.getKey() + ", location=" + formatLocation(location));
            createVisual(location, skillet);
            saveSkillet(location, skillet);

            if (player.getGameMode() != GameMode.CREATIVE) {
                debug("consume: stacked move=" + toMove + ", before=" + heldItem.getAmount()
                        + ", after=" + (heldItem.getAmount() - toMove) + ", item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
                heldItem.setAmount(heldItem.getAmount() - toMove);
            } else {
                debug("consume: skipped for creative mode while stacking, item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
            }

            awardUseSkillet(player);
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
        skillet.storedItem = toPlace;
        skillet.currentRecipe = recipe;
        skillet.cookingDuration = getAdjustedCookingTime(recipe.getCookingTime(), skillet.fireAspectLevel);
        skillet.cookingProgress = 0;
        skillet.ownerId = player.getUniqueId();
        skillet.ownerName = player.getName();
        debug("create state: stored=" + formatItem(toPlace) + ", recipe=" + recipe.getKey()
                + ", duration=" + skillet.cookingDuration + ", fireAspect=" + skillet.fireAspectLevel
                + ", location=" + formatLocation(location));

        createVisual(location, skillet);
        saveSkillet(location, skillet);

        if (player.getGameMode() != GameMode.CREATIVE) {
            debug("consume: before=" + heldItem.getAmount() + ", after=" + (heldItem.getAmount() - placeAmount)
                    + ", moved=" + placeAmount + ", item=" + formatItem(heldItem) + ", location=" + formatLocation(location));
            heldItem.setAmount(heldItem.getAmount() - placeAmount);
        } else {
            debug("consume: skipped for creative mode, moved=" + placeAmount + ", item=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
        }

        awardUseSkillet(player);
        SoundUtils.play(block.getWorld(), location, getAddFoodSound(location), Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.0f);
        return true;
    }

    public boolean canCook(ItemStack item) {
        return findCampfireRecipe(item) != null;
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
        if (customItemId == null) {
            return false;
        }

        if (customItemId.contains("knife")) {
            return true;
        }

        return plugin.isKnifeItemId(customItemId);
    }

    private boolean isSkilletItem(ItemStack item) {
        String customItemId = ItemUtils.getCustomItemId(item);
        return Constants.ITEM_SKILLET.equals(customItemId);
    }

    private boolean isSkilletBlock(Location location) {
        return CustomBlockUtils.hasBehavior(location, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation) {
        breakSkillet(blockLocation, dropLocation, true);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        SkilletData skillet = removeTrackedSkillet(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
            if (shouldDropItems && skillet.storedItem != null && !skillet.storedItem.getType().isAir()) {
                normalized.getWorld().dropItemNaturally(dropLocation, skillet.storedItem.clone());
            }
            if (shouldDropItems) {
                ItemStack skilletDrop = skillet.skilletStack != null && !skillet.skilletStack.getType().isAir()
                        ? skillet.skilletStack.clone()
                        : ItemUtils.createItem(Constants.ITEM_SKILLET);
                if (skilletDrop != null && !skilletDrop.getType().isAir()) {
                    skilletDrop.setAmount(1);
                    normalized.getWorld().dropItemNaturally(dropLocation, skilletDrop);
                }
            }
        }
        removeStoredData(normalized);
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
        Set<Location> locations = skilletsByWorld.get(world.getUID());
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : locations) {
            SkilletData skillet = skillets.get(location);
            if (skillet != null) {
                saveSkillet(location, skillet);
            }
        }
    }

    public void cleanupWorld(UUID worldId) {
        Set<Location> locations = skilletsByWorld.remove(worldId);
        skilletsByChunk.remove(worldId);
        if (locations == null || locations.isEmpty()) {
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
        Map<Long, Set<Location>> worldChunks = skilletsByChunk.get(world.getUID());
        Set<Location> locations = worldChunks == null ? null : worldChunks.get(chunkKey(minX >> 4, minZ >> 4));
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            if (location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ) {
                SkilletData skillet = skillets.get(location);
                if (skillet != null) {
                    saveSkillet(location, skillet);
                }
                removeSkillet(location, false);
            }
        }
    }

    public void loadSkillet(World world, net.momirealms.craftengine.core.world.BlockPos pos, Map<String, Object> data) {
        loadSkillet(world, new BlockPosKey(pos), data);
    }

    public void loadSkillet(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null) return;

        Location location = ManagerSupport.toLocation(world, posKey);
        if (location == null) return;
        if (!isSkilletBlock(location)) {
            removeStoredData(location);
            removeSkillet(location, false);
            return;
        }

        SkilletData skillet = new SkilletData(location);

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
    }

    private SkilletData getOrLoadSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = skillets.get(normalized);
        if (skillet != null) {
            ensureTaskRunning();
            return skillet;
        }

        LegacyBlockStorageManager storage = plugin.getLegacyBlockStorageManager();
        if (storage != null) {
            Map<String, Object> data = storage.loadBlockData(normalized, BLOCK_TYPE);
            if (data != null) {
                loadSkillet(normalized.getWorld(), new BlockPosKey(normalized), data);
                storage.removeBlockData(normalized);
                skillet = skillets.get(normalized);
                if (skillet != null) {
                    markSkilletDirty(normalized);
                    return skillet;
                }
            }
        }

        return getOrCreateSkillet(normalized);
    }

    public void cleanup() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (SkilletData skillet : skillets.values()) {
            cleanupVisual(skillet);
        }
        skillets.clear();
        skilletsByWorld.clear();
        skilletsByChunk.clear();
        scheduledSkilletTicks.clear();
        tickLocationsSnapshot = List.of();
        markTickLocationsDirty();
    }

    public Collection<Location> getTrackedLocations(World world) {
        if (world == null) {
            return List.of();
        }

        Set<Location> indexed = skilletsByWorld.get(world.getUID());
        if (indexed == null || indexed.isEmpty()) {
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

    public void reloadRecipeCache() {
        campfireRecipes.rebuild();
    }

    private SkilletData putSkillet(Location location, SkilletData skillet) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData previous = skillets.put(normalized, skillet);
        indexSkillet(normalized);
        markTickLocationsDirty();
        return previous;
    }

    private SkilletData removeTrackedSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData removed = skillets.remove(normalized);
        if (removed != null) {
            scheduledSkilletTicks.remove(normalized);
            deindexSkillet(normalized);
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

    private void indexSkillet(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        skilletsByWorld
                .computeIfAbsent(location.getWorld().getUID(), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
        skilletsByChunk
                .computeIfAbsent(location.getWorld().getUID(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(location), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
    }

    private void deindexSkillet(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        UUID worldId = location.getWorld().getUID();
        Set<Location> locations = skilletsByWorld.get(worldId);
        if (locations == null) {
            return;
        }
        locations.remove(location);
        if (locations.isEmpty()) {
            skilletsByWorld.remove(worldId);
        }

        Map<Long, Set<Location>> worldChunks = skilletsByChunk.get(worldId);
        if (worldChunks == null) {
            return;
        }
        long chunkKey = chunkKey(location);
        Set<Location> chunkLocations = worldChunks.get(chunkKey);
        if (chunkLocations == null) {
            return;
        }
        chunkLocations.remove(location);
        if (chunkLocations.isEmpty()) {
            worldChunks.remove(chunkKey);
        }
        if (worldChunks.isEmpty()) {
            skilletsByChunk.remove(worldId);
        }
    }

    private void removeSkillet(Location location, boolean removeStoredData) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = removeTrackedSkillet(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
        }
        stopTaskIfIdle();
        if (removeStoredData) {
            removeStoredData(normalized);
        }
    }

    private int getAdjustedCookingTime(int baseTime, int fireAspectLevel) {
        int cookingTime = baseTime > 0 ? baseTime : Constants.DEFAULT_COOKING_TIME_SKILLET;
        int cookingSeconds = cookingTime / 20;
        float cookingTimeReduction = Constants.SKILLET_COOKING_TIME_REDUCTION;

        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * Constants.SKILLET_FIRE_ASPECT_BONUS;
        }

        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        return Math.max(MIN_COOK_TIME, Math.min(result, cookingTime));
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
        if (skillets.get(location) != skillet) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        if (!isSkilletBlock(location)) {
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

        boolean hasHeat = hasHeatSource(location);
        if (!Objects.equals(skillet.lastHeatState, hasHeat)) {
            debug(() -> "heat state: hasHeat=" + hasHeat + ", progress=" + skillet.cookingProgress
                    + "/" + skillet.cookingDuration + ", recipe="
                    + (skillet.currentRecipe != null ? skillet.currentRecipe.getKey() : "null")
                    + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location)
                    + ", fireAspectLevel=" + skillet.fireAspectLevel);
        }
        skillet.lastHeatState = hasHeat;

        if (!hasHeat || skillet.currentRecipe == null) {
            skillet.cookingProgress = Math.max(0, skillet.cookingProgress - 2);
            return;
        }

        skillet.cookingProgress++;

        if (Math.random() < Constants.SKILLET_PARTICLE_CHANCE) {
            spawnCookingParticles(location);
        }
        if (Math.random() < Constants.SKILLET_SIZZLE_CHANCE) {
            SoundUtils.play(world, location, getSizzleSound(location), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.5f, 1.0f);
        }
        if (skillet.cookingProgress >= skillet.cookingDuration) {
            debug(() -> "tick finish: progress reached duration for " + formatItem(skillet.storedItem)
                    + " at " + formatLocation(location));
            finishCooking(location, skillet);
        }
    }

    private boolean hasHeatSource(Location location) {
        Block blockBelow = location.clone().subtract(0, 1, 0).getBlock();

        if (plugin.getHeatSourceConfig().isHeatSource(blockBelow)) {
            return true;
        }

        if (!plugin.isSkilletConductorsAllowed()) {
            return false;
        }

        if (plugin.getHeatSourceConfig().isConductor(blockBelow)) {
            Block blockTwoBelow = location.clone().subtract(0, 2, 0).getBlock();
            return plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
        }

        return false;
    }

    private void finishCooking(Location location, SkilletData skillet) {
        if (skillet.currentRecipe == null || skillet.storedItem == null) return;
        if (!isSkilletBlock(location)) {
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
                        skillet.currentRecipe.getExperience()
                ));
            }
            Block block = location.getBlock();
            BlockFace facing = CustomBlockUtils.getFacing(block);
            BlockFace clockwise = getClockWise(facing);

            Location dropLoc = location.clone().add(0.5, 0.3, 0.5);
            var droppedItem = location.getWorld().dropItem(dropLoc, result.clone());
            droppedItem.setVelocity(new org.bukkit.util.Vector(
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

    private String getSizzleSound(Location location) {
        SkilletBlockBehavior behavior = SkilletBlockBehavior.getBlockBehavior(location);
        if (behavior != null) {
            return behavior.getSizzleSound();
        }
        return Constants.SOUND_SKILLET_SIZZLE;
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

    private void spawnCookingParticles(Location location) {
        Location particleLocation = location.clone().add(0.5, 0.2, 0.5);
        location.getWorld().spawnParticle(Particle.SMOKE, particleLocation, 2, 0.1, 0.1, 0.1, 0.02);
    }

    private void createVisual(Location location, SkilletData skillet) {
        if (skillet.storedItem == null || skillet.storedItem.getType().isAir()) {
            debug("spawn display: skipped empty stored item at " + formatLocation(location));
            cleanupVisual(skillet);
            return;
        }

        ItemStack visualItem = skillet.storedItem.clone();
        visualItem.setAmount(1);
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock());
        boolean itemChanged = skillet.displayedItem == null || !skillet.displayedItem.isSimilar(visualItem);
        boolean facingChanged = skillet.displayedFacing != facing;
        if (itemChanged || facingChanged) {
            cleanupVisual(skillet);
            skillet.displayedItem = visualItem;
            skillet.displayedFacing = facing;
        }

        int displayCount = getModelCount(skillet.storedItem);
        Random random = new Random(getVisualSeed(skillet.storedItem));
        float yRotation = DisplayTransformUtils.skilletYaw(facing);
        debug("spawn display: target " + displayCount + " displays for " + formatItem(skillet.storedItem)
                + " at " + formatLocation(location));

        while (skillet.displayEntityIds.size() > displayCount) {
            int entityId = skillet.displayEntityIds.remove(skillet.displayEntityIds.size() - 1);
            ItemDisplayManager visualManager = plugin.getItemDisplayManager();
            if (visualManager != null) {
                visualManager.destroyDisplay(entityId);
            }
        }

        for (int i = skillet.displayEntityIds.size(); i < displayCount; i++) {
            double spread = plugin.getSkilletDisplaySpread();
            double offsetX = displayCount == 1 ? 0 : (random.nextDouble() - 0.5D) * spread;
            double offsetZ = displayCount == 1 ? 0 : (random.nextDouble() - 0.5D) * spread;
            double offsetY = plugin.getSkilletDisplayYOffset() + ((i + 1) * 0.03D);

            ItemStack stackForDisplay = visualItem.clone();

            Quaternionf leftRotation = new Quaternionf();
            leftRotation.rotationYXZ(
                    (float) Math.toRadians(yRotation),
                    (float) Math.toRadians(90.0f),
                    0.0f
            );

            Transformation transformation = new Transformation(
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    leftRotation,
                    new Vector3f(plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale()),
                    new Quaternionf()
            );

            ItemDisplayManager visualManager = plugin.getItemDisplayManager();
            if (visualManager == null || !visualManager.isAvailable()) {
                debug("spawn display: visual manager unavailable at " + formatLocation(location));
                continue;
            }

            Location displayLoc = location.clone().add(0.5 + offsetX, offsetY, 0.5 + offsetZ);
            int displayId = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                    displayLoc,
                    stackForDisplay,
                    org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED,
                    transformation
            ));
            if (displayId >= 0) {
                skillet.displayEntityIds.add(displayId);
                debug("spawn display: entityId=" + displayId + ", index=" + i + ", item=" + formatItem(stackForDisplay)
                        + ", location=" + formatLocation(location));
            }
        }
    }

    private int getModelCount(ItemStack stack) {
        int amount = stack.getAmount();
        if (amount > 48) {
            return 5;
        }
        if (amount > 32) {
            return 4;
        }
        if (amount > 16) {
            return 3;
        }
        if (amount > 1) {
            return 2;
        }
        return 1;
    }

    private long getVisualSeed(ItemStack stack) {
        long seed = stack.getType().ordinal();
        String customItemId = ItemUtils.getCustomItemId(stack);
        if (customItemId != null) {
            seed = 31L * seed + customItemId.hashCode();
        }
        if (stack.hasItemMeta() && stack.getItemMeta().hasCustomModelData()) {
            seed = 31L * seed + stack.getItemMeta().getCustomModelData();
        }
        return seed;
    }

    private boolean canStackWithStored(ItemStack stored, ItemStack incoming, CookingRecipe<?> storedRecipe, CookingRecipe<?> incomingRecipe) {
        if (stored == null || incoming == null || storedRecipe == null || incomingRecipe == null) {
            return false;
        }

        if (!storedRecipe.getKey().equals(incomingRecipe.getKey())) {
            return false;
        }

        ItemStack storedSingle = stored.clone();
        storedSingle.setAmount(1);
        ItemStack incomingSingle = incoming.clone();
        incomingSingle.setAmount(1);
        return storedSingle.isSimilar(incomingSingle);
    }

    private void cleanupVisual(SkilletData skillet) {
        if (skillet.displayEntityIds.isEmpty()) {
            skillet.displayedItem = null;
            skillet.displayedFacing = null;
            return;
        }

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null) {
            skillet.displayEntityIds.clear();
            skillet.displayedItem = null;
            skillet.displayedFacing = null;
            return;
        }

        for (Integer entityId : new ArrayList<>(skillet.displayEntityIds)) {
            visualManager.destroyDisplay(entityId);
        }
        skillet.displayEntityIds.clear();
        skillet.displayedItem = null;
        skillet.displayedFacing = null;
    }

    private void ensureVisualsExist(Location location, SkilletData skillet) {
        if (!skillet.hasItem()) {
            return;
        }

        int expectedCount = getModelCount(skillet.storedItem);
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock());
        if (skillet.displayEntityIds.size() != expectedCount || skillet.displayedFacing != facing) {
            createVisual(location, skillet);
        }
    }

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
        LegacyBlockStorageManager storage = plugin.getLegacyBlockStorageManager();
        if (storage != null) {
            storage.removeBlockData(ManagerSupport.normalize(location));
        }
    }

    private void awardUseSkillet(Player player) {
        if (player == null) {
            return;
        }

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
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
