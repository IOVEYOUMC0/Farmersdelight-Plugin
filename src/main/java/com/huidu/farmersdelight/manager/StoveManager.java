package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.*;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import fr.ateastudio.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class StoveManager {

    private static final String BLOCK_TYPE = "stove";
    private static final int SLOT_COUNT = 6;
    private static final int DEFAULT_COOK_TIME = 600;
    private static final int HEARTBEAT_LOG_INTERVAL = 20;
    private static final int DEFAULT_TICK_BUDGET = 512;

    private final FarmersDelightPlugin plugin;
    private final Map<Location, StoveData> stoves = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> stovesByWorld = new ConcurrentHashMap<>();
    private final Map<Location, Boolean> blockedAboveCache = new ConcurrentHashMap<>();
    private final Set<Location> scheduledStoveTicks = ConcurrentHashMap.newKeySet();
    private static final long BLOCKED_CACHE_TTL_MS = 30_000;
    private long lastBlockedCacheCleanup;
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("stove", this::debug);
    private PluginTask tickTask;
    private int heartbeatTicks;
    private int tickCursor;
    private int tickBudget;
    private volatile Property<?> fireProperty;

    private final double[][] slotOffsets = {
            {0.3, 1.02, 0.2}, {0.0, 1.02, 0.2}, {-0.3, 1.02, 0.2},
            {0.3, 1.02, -0.2}, {0.0, 1.02, -0.2}, {-0.3, 1.02, -0.2}
    };

    public static class StoveData {
        final Location location;
        final ItemStack[] items = new ItemStack[SLOT_COUNT];
        final int[] cookingTime = new int[SLOT_COUNT];
        final int[] maxTime = new int[SLOT_COUNT];
        final int[] displayEntities = new int[SLOT_COUNT];
        final UUID[] ownerIds = new UUID[SLOT_COUNT];
        final String[] ownerNames = new String[SLOT_COUNT];

        StoveData(Location location) {
            this.location = location;
            Arrays.fill(maxTime, DEFAULT_COOK_TIME);
            Arrays.fill(displayEntities, -1);
        }
    }

    public StoveManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
        campfireRecipes.rebuild();
    }

    public void reloadConfig() {
        this.tickBudget = Math.max(1, plugin.getConfig().getInt(
                "performance.stove-tick-budget", DEFAULT_TICK_BUDGET));
    }

    private void ensureTaskRunning() {
        if (tickTask != null) {
            return;
        }
        debug("tick task: starting stove tick task");
        tickTask = plugin.scheduler().runRepeating(this::tick, 1L, 4L);
    }

    private void stopTaskIfIdle() {
        if (tickTask != null && stoves.isEmpty()) {
            debug("tick task: stopping stove tick task because no stoves remain");
            tickTask.cancel();
            tickTask = null;
            heartbeatTicks = 0;
        }
    }

    public StoveData getOrCreateStove(Location location) {
        ensureTaskRunning();
        Location normalized = ManagerSupport.normalize(location);
        StoveData existing = stoves.get(normalized);
        if (existing != null) {
            return existing;
        }

        StoveData created = new StoveData(normalized);
        StoveData previous = stoves.putIfAbsent(normalized, created);
        if (previous != null) {
            return previous;
        }
        indexStove(normalized);
        return created;
    }

    public boolean handleInteract(Player player, Block block, ItemStack itemInHand) {
        Location location = ManagerSupport.normalize(block.getLocation());
        if (isStoveBlockedAboveCached(location)) {
            debug("Stove interact blocked above for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        StoveData stove = getOrLoadStove(location);
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return false;
        }

        int emptySlot = findEmptySlot(stove);
        if (emptySlot < 0) {
            debug("Stove interact no empty slot for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        CookingRecipe<?> recipe = findCampfireRecipe(itemInHand);
        if (recipe == null) {
            debug("Stove interact no campfire recipe for " + formatItem(itemInHand) + " at " + formatLocation(location));
            return false;
        }

        debug("recipe match: recipe=" + recipe.getKey() + ", input=" + formatItem(itemInHand) + ", slot=" + emptySlot
                + ", location=" + formatLocation(location));

        ItemStack toPlace = itemInHand.clone();
        toPlace.setAmount(1);
        stove.items[emptySlot] = toPlace;
        stove.cookingTime[emptySlot] = 0;
        stove.maxTime[emptySlot] = recipe.getCookingTime() > 0 ? recipe.getCookingTime() : DEFAULT_COOK_TIME;
        stove.ownerIds[emptySlot] = player.getUniqueId();
        stove.ownerNames[emptySlot] = player.getName();
        debug("create state: slot=" + emptySlot + ", stored=" + formatItem(toPlace)
                + ", duration=" + stove.maxTime[emptySlot] + ", location=" + formatLocation(location));

        createVisual(location, stove, emptySlot, CustomBlockUtils.getFacing(block).getOppositeFace());
        saveStove(location, stove);
        if (player.getGameMode() != GameMode.CREATIVE) {
            debug("consume: slot=" + emptySlot + ", before=" + itemInHand.getAmount() + ", after=" + (itemInHand.getAmount() - 1)
                    + ", item=" + formatItem(itemInHand) + ", location=" + formatLocation(location));
            itemInHand.setAmount(itemInHand.getAmount() - 1);
        } else {
            debug("consume: skipped for creative mode, slot=" + emptySlot + ", item=" + formatItem(itemInHand)
                    + ", location=" + formatLocation(location));
        }

        location.getWorld().playSound(location, Sound.BLOCK_LANTERN_PLACE, 0.5f, 1.0f);
        return true;
    }

    public boolean handleRetrieve(Player player, Block block) {
        if (player == null || block == null) {
            return false;
        }

        Location location = ManagerSupport.normalize(block.getLocation());
        StoveData stove = getOrLoadStove(location);
        return retrieveItem(player, location, stove);
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

    public void breakStove(Location blockLocation, Location dropLocation) {
        breakStove(blockLocation, dropLocation, true);
    }

    public void breakStove(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        StoveData stove = removeTrackedStove(normalized);
        if (stove != null) {
            cleanupAllVisuals(stove);
            if (shouldDropItems) {
                for (ItemStack item : stove.items) {
                    if (item != null && !item.getType().isAir()) {
                        normalized.getWorld().dropItemNaturally(dropLocation, item.clone());
                    }
                }
            }
        }
        removeStoredData(normalized);
    }

    public boolean isStoveStateBlock(Location location) {
        return CustomBlockUtils.hasId(location, Constants.BLOCK_STOVE);
    }

    public void saveAllData() {
        ManagerSupport.saveAllData(stoves, this::saveStove);
    }

    public void saveWorldData(World world) {
        if (world == null) {
            return;
        }
        Set<Location> locations = stovesByWorld.get(world.getUID());
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            StoveData stove = stoves.get(location);
            if (stove != null) {
                saveStove(location, stove);
            }
        }
    }

    public void cleanupWorld(UUID worldId) {
        Set<Location> locations = stovesByWorld.remove(worldId);
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            StoveData stove = stoves.remove(location);
            if (stove != null) {
                scheduledStoveTicks.remove(location);
                cleanupAllVisuals(stove);
            }
        }
        stopTaskIfIdle();
    }

    public void saveAndUnloadChunk(World world, int minX, int maxX, int minZ, int maxZ) {
        if (world == null) {
            return;
        }
        Set<Location> locations = stovesByWorld.get(world.getUID());
        if (locations == null || locations.isEmpty()) {
            return;
        }
        for (Location location : List.copyOf(locations)) {
            if (location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ) {
                StoveData stove = stoves.get(location);
                if (stove != null) {
                    saveStove(location, stove);
                }
                removeStove(location, false);
            }
        }
    }

    public void loadStove(World world, net.momirealms.craftengine.core.world.BlockPos pos, Map<String, Object> data) {
        loadStove(world, new BlockPosKey(pos), data);
    }

    public void loadStove(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null) return;

        Location location = ManagerSupport.toLocation(world, posKey);
        if (location == null) return;
        if (!isStoveStateBlock(location)) {
            removeStoredData(location);
            removeStove(location, false);
            return;
        }

        StoveData stove = new StoveData(location);
        boolean hasAnyItem = false;
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock()).getOppositeFace();

        for (int i = 0; i < SLOT_COUNT; i++) {
            Object itemObject = data.get("slot_" + i + "_item");
            if (itemObject instanceof ItemStack item && !item.getType().isAir()) {
                stove.items[i] = item.clone();
                stove.cookingTime[i] = data.get("slot_" + i + "_progress") instanceof Number progress ? progress.intValue() : 0;
                stove.maxTime[i] = data.get("slot_" + i + "_duration") instanceof Number duration ? duration.intValue() : DEFAULT_COOK_TIME;
                if (data.get("slot_" + i + "_owner_id") instanceof String ownerId) {
                    try {
                        stove.ownerIds[i] = UUID.fromString(ownerId);
                    } catch (IllegalArgumentException ignored) {
                        stove.ownerIds[i] = null;
                    }
                }
                if (data.get("slot_" + i + "_owner_name") instanceof String ownerName) {
                    stove.ownerNames[i] = ownerName;
                }
                createVisual(location, stove, i, facing);
                hasAnyItem = true;
            }
        }

        if (hasAnyItem) {
            putStove(location, stove);
            ensureTaskRunning();
        } else {
            removeStoredData(location);
        }
    }

    private StoveData getOrLoadStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData stove = stoves.get(normalized);
        if (stove != null) {
            ensureTaskRunning();
            return stove;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage != null) {
            Map<String, Object> data = storage.loadBlockData(normalized, BLOCK_TYPE);
            if (data != null) {
                loadStove(normalized.getWorld(), new BlockPosKey(normalized), data);
                stove = stoves.get(normalized);
                if (stove != null) {
                    return stove;
                }
            }
        }

        return getOrCreateStove(normalized);
    }

    public void cleanup() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (StoveData stove : stoves.values()) {
            cleanupAllVisuals(stove);
        }
        stoves.clear();
        stovesByWorld.clear();
        scheduledStoveTicks.clear();
    }

    public void reloadRecipeCache() {
        campfireRecipes.rebuild();
    }

    private void removeStove(Location location, boolean removeStoredData) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData stove = removeTrackedStove(normalized);
        if (stove != null) {
            cleanupAllVisuals(stove);
        }
        stopTaskIfIdle();
        if (removeStoredData) {
            removeStoredData(normalized);
        }
    }

    private StoveData putStove(Location location, StoveData stove) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData previous = stoves.put(normalized, stove);
        indexStove(normalized);
        return previous;
    }

    private StoveData removeTrackedStove(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        StoveData removed = stoves.remove(normalized);
        if (removed != null) {
            scheduledStoveTicks.remove(normalized);
            deindexStove(normalized);
        }
        return removed;
    }

    private void indexStove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        stovesByWorld
                .computeIfAbsent(location.getWorld().getUID(), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
    }

    private void deindexStove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        UUID worldId = location.getWorld().getUID();
        Set<Location> locations = stovesByWorld.get(worldId);
        if (locations == null) {
            return;
        }
        locations.remove(location);
        if (locations.isEmpty()) {
            stovesByWorld.remove(worldId);
        }
    }

    private int findEmptySlot(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] == null || stove.items[i].getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasAnyItem(StoveData stove) {
        for (ItemStack item : stove.items) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private void tick() {
        if (stoves.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBlockedCacheCleanup > BLOCKED_CACHE_TTL_MS) {
            blockedAboveCache.clear();
            lastBlockedCacheCleanup = now;
        }
        if (++heartbeatTicks >= HEARTBEAT_LOG_INTERVAL) {
            heartbeatTicks = 0;
            debug(() -> "tick heartbeat: activeStoves=" + stoves.size());
        }
        List<Map.Entry<Location, StoveData>> snapshot = List.copyOf(stoves.entrySet());
        int size = snapshot.size();
        int budget = Math.min(tickBudget, size);
        int start = tickCursor >= size ? 0 : tickCursor;

        for (int processed = 0; processed < budget; processed++) {
            Map.Entry<Location, StoveData> entry = snapshot.get((start + processed) % size);
            Location location = entry.getKey();
            StoveData stove = entry.getValue();
            if (stoves.get(location) != stove) {
                continue;
            }

            scheduleStoveTick(location, stove);
        }
        tickCursor = size == 0 ? 0 : (start + Math.max(1, budget)) % size;
        stopTaskIfIdle();
    }

    private void scheduleStoveTick(Location location, StoveData stove) {
        if (!plugin.scheduler().isFolia()) {
            tickStove(location, stove);
            return;
        }

        if (!scheduledStoveTicks.add(location)) {
            return;
        }
        try {
            plugin.scheduler().runAt(location, () -> {
                try {
                    tickStove(location, stove);
                } finally {
                    scheduledStoveTicks.remove(location);
                }
            });
        } catch (RuntimeException e) {
            scheduledStoveTicks.remove(location);
        }
    }

    private void tickStove(Location location, StoveData stove) {
        if (stoves.get(location) != stove) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Block block = location.getBlock();
        if (block.getType().isAir()) {
            debug(() -> "tick remove: stove carrier block is air at " + formatLocation(location));
            cleanupAllVisuals(stove);
            removeStoredData(location);
            removeTrackedStove(location);
            return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            debug(() -> "tick state: stove custom state not available yet, skipping this tick at " + formatLocation(location));
            return;
        }

        if (!CustomBlockUtils.hasId(state, Constants.BLOCK_STOVE)) {
            debug(() -> "tick state: stove state id mismatch, skipping this tick at " + formatLocation(location));
            return;
        }

        if (isStoveBlockedAboveCached(location)) {
            debug(() -> "tick remove: stove blocked above, ejecting all items at " + formatLocation(location));
            ejectAllItems(location, stove);
            cleanupAllVisuals(stove);
            removeStoredData(location);
            removeTrackedStove(location);
            return;
        }

        boolean isLit = isStoveLit(state);
        BlockFace facing = CustomBlockUtils.getFacing(block).getOppositeFace();
        debug(() -> "tick state: lit=" + isLit + ", hasAnyItem=" + hasAnyItem(stove)
                + ", location=" + formatLocation(location));
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] == null || stove.items[i].getType().isAir()) {
                continue;
            }

            ensureVisualExists(location, stove, i, facing);
            int slot = i;
            debug(() -> "tick slot: slot=" + slot + ", progress=" + stove.cookingTime[slot] + "/" + stove.maxTime[slot]
                    + ", item=" + formatItem(stove.items[slot]) + ", lit=" + isLit
                    + ", location=" + formatLocation(location));

            if (isLit) {
                stove.cookingTime[i]++;

                if (Math.random() < Constants.STOVE_PARTICLE_CHANCE) {
                    spawnCookingParticles(location, i, facing);
                }
                if (Math.random() < Constants.STOVE_CRACKLE_CHANCE) {
                    SoundUtils.play(world, location, getCrackleSound(location), Sound.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 1.0f);
                }
                if (stove.cookingTime[i] >= stove.maxTime[i]) {
                    int finishedSlot = i;
                    debug(() -> "tick finish: slot=" + finishedSlot + ", item=" + formatItem(stove.items[finishedSlot])
                            + ", location=" + formatLocation(location));
                    finishCooking(location, stove, i);
                }
            } else {
                stove.cookingTime[i] = Math.max(0, stove.cookingTime[i] - 2);
            }
        }

        if (!hasAnyItem(stove)) {
            removeStoredData(location);
            removeTrackedStove(location);
        }
    }

    private void resolveFireProperty(ImmutableBlockState state) {
        if (fireProperty != null) return;
        for (Property<?> prop : state.getProperties()) {
            if ("fire".equals(prop.name())) {
                fireProperty = prop;
                return;
            }
        }
    }

    private boolean isStoveLit(ImmutableBlockState state) {
        if (fireProperty == null) {
            resolveFireProperty(state);
        }
        if (fireProperty == null) {
            return true;
        }
        try {
            Object fireValue = state.get(fireProperty);
            return fireValue instanceof Boolean lit && lit;
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isStoveBlockedAboveCached(Location location) {
        return blockedAboveCache.computeIfAbsent(location, this::isStoveBlockedAbove);
    }

    public void invalidateBlockedAboveCache(Location location) {
        blockedAboveCache.remove(location);
    }

    public void invalidateBlockedAboveCacheNear(Location location) {
        blockedAboveCache.remove(ManagerSupport.normalize(location));
        blockedAboveCache.remove(ManagerSupport.normalize(location.clone().add(0, -1, 0)));
    }

    private boolean isStoveBlockedAbove(Location location) {
        Block aboveBlock = location.clone().add(0, 1, 0).getBlock();
        var blockShape = aboveBlock.getBlockData().getCollisionShape(aboveBlock.getLocation());
        if (blockShape == null || blockShape.getBoundingBoxes().isEmpty()) {
            return false;
        }

        org.bukkit.util.BoundingBox grillingArea = new org.bukkit.util.BoundingBox(
                3.0D / 16.0D,
                0.0D,
                3.0D / 16.0D,
                13.0D / 16.0D,
                1.0D / 16.0D,
                13.0D / 16.0D
        );
        return blockShape.overlaps(grillingArea);
    }

    private void finishCooking(Location location, StoveData stove, int slot) {
        ItemStack input = stove.items[slot];
        if (input == null) return;

        CookingRecipe<?> recipe = findCampfireRecipe(input);
        debug("finish cooking: slot=" + slot + ", input=" + formatItem(input)
                + ", recipe=" + (recipe != null ? recipe.getKey() : "null")
                + ", location=" + formatLocation(location));
        ItemStack result = recipe != null ? recipe.getResult() : input;
        if (result != null && !result.getType().isAir()) {
            if (recipe != null && stove.ownerIds[slot] != null) {
                Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                        stove.ownerIds[slot],
                        stove.ownerNames[slot],
                        "stove",
                        result,
                        recipe.getExperience()
                ));
            }
            location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), result.clone());
        }

        stove.items[slot] = null;
        stove.cookingTime[slot] = 0;
        stove.maxTime[slot] = DEFAULT_COOK_TIME;
        stove.ownerIds[slot] = null;
        stove.ownerNames[slot] = null;
        removeVisual(location, stove, slot);

        if (!hasAnyItem(stove)) {
            ManagerSupport.removeStoredData(plugin, location);
        }
    }

    private void ejectAllItems(Location location, StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), stove.items[i].clone());
                stove.items[i] = null;
                stove.cookingTime[i] = 0;
                stove.maxTime[i] = DEFAULT_COOK_TIME;
                stove.ownerIds[i] = null;
                stove.ownerNames[i] = null;
            }
        }
    }

    private void spawnCookingParticles(Location location, int slot, BlockFace facing) {
        double[] offset = getRotatedSlotOffset(slot, facing);
        Location particleLocation = location.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        location.getWorld().spawnParticle(Particle.SMOKE, particleLocation, 1, 0, 0, 0, 0.02);
    }

    private void createVisual(Location location, StoveData stove, int slot, BlockFace facing) {
        removeVisual(location, stove, slot);

        ItemStack item = stove.items[slot];
        if (item == null || item.getType().isAir()) {
            debug("spawn display: skipped empty item for slot=" + slot + ", location=" + formatLocation(location));
            return;
        }

        double[] offset = getRotatedSlotOffset(slot, facing);
        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) {
            debug("spawn display: visual manager unavailable for slot=" + slot + ", item=" + formatItem(item)
                    + ", location=" + formatLocation(location));
            return;
        }

        Location displayLocation = location.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        ItemStack visualItem = item.clone();
        visualItem.setAmount(1);

        float yRotation = CustomBlockUtils.getYRotation(facing);

        Quaternionf leftRotation = new Quaternionf();
        leftRotation.rotationYXZ(
                (float) Math.toRadians(yRotation),
                (float) Math.toRadians(90.0f),
                0.0f
        );

        Transformation transformation = new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                leftRotation,
                new Vector3f(plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale(), plugin.getStoveDisplayScale()),
                new Quaternionf()
        );

        stove.displayEntities[slot] = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                displayLocation,
                visualItem,
                org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED,
                transformation
        ));
        debug("spawn display: slot=" + slot + ", entityId=" + stove.displayEntities[slot] + ", item=" + formatItem(visualItem)
                + ", location=" + formatLocation(location));
    }

    private void ensureVisualExists(Location location, StoveData stove, int slot, BlockFace facing) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) {
            createVisual(location, stove, slot, facing);
        }
    }

    private double[] getRotatedSlotOffset(int slot, BlockFace facing) {
        double[] offset = slotOffsets[slot];

        return switch (facing) {
            case EAST -> new double[]{offset[2], offset[1], -offset[0]};
            case SOUTH -> new double[]{-offset[0], offset[1], -offset[2]};
            case WEST -> new double[]{-offset[2], offset[1], offset[0]};
            default -> new double[]{offset[0], offset[1], offset[2]};
        };
    }

    private void removeVisual(Location location, StoveData stove, int slot) {
        int entityId = stove.displayEntities[slot];
        if (entityId < 0) return;
        stove.displayEntities[slot] = -1;

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    private void cleanupAllVisuals(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            removeVisual(stove.location, stove, i);
        }
    }

    private boolean retrieveItem(Player player, Location location, StoveData stove) {
        int slot = findBestRetrievalSlot(stove);
        if (slot < 0) {
            return false;
        }

        ItemStack item = stove.items[slot];
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemStack toReturn = item.clone();
        stove.items[slot] = null;
        stove.cookingTime[slot] = 0;
        stove.maxTime[slot] = DEFAULT_COOK_TIME;
        stove.ownerIds[slot] = null;
        stove.ownerNames[slot] = null;
        removeVisual(location, stove, slot);

        if (player.getInventory().getItemInMainHand().getType().isAir()) {
            player.getInventory().setItemInMainHand(toReturn);
        } else {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toReturn);
            for (ItemStack leftover : leftovers.values()) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), leftover);
            }
        }

        if (!hasAnyItem(stove)) {
            ManagerSupport.removeStoredData(plugin, location);
        }
        location.getWorld().playSound(location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.0f);
        return true;
    }

    private int findBestRetrievalSlot(StoveData stove) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir() && stove.cookingTime[i] >= stove.maxTime[i]) {
                return i;
            }
        }
        for (int i = SLOT_COUNT - 1; i >= 0; i--) {
            if (stove.items[i] != null && !stove.items[i].getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private String getCrackleSound(Location location) {
        StoveCookingBlockBehavior behavior = StoveCookingBlockBehavior.getBlockBehavior(location);
        if (behavior != null) {
            return behavior.getCrackleSound();
        }
        return Constants.SOUND_STOVE_CRACKLE;
    }

    private CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        return campfireRecipes.find(item);
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("stove")) {
            plugin.getLogger().info("[StoveDebug] " + message);
        }
    }

    private void debug(Supplier<String> messageSupplier) {
        if (plugin.isDebugEnabled("stove")) {
            plugin.getLogger().info("[StoveDebug] " + messageSupplier.get());
        }
    }

    private String formatItem(ItemStack item) {
        return ManagerSupport.formatItem(item);
    }

    private String formatLocation(Location location) {
        return ManagerSupport.formatLocation(location);
    }

    private void saveStove(Location location, StoveData stove) {
        Location normalized = ManagerSupport.normalize(location);
        if (stove == null || !hasAnyItem(stove)) {
            debug("save state: removing persisted stove state at " + formatLocation(normalized));
            ManagerSupport.removeStoredData(plugin, normalized);
            return;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) {
            debug("save state: skipped because storage manager is null at " + formatLocation(normalized));
            return;
        }

        Map<String, Object> data = new HashMap<>();
        int savedSlots = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack item = stove.items[i];
            if (item != null && !item.getType().isAir()) {
                data.put("slot_" + i + "_item", item.clone());
                data.put("slot_" + i + "_progress", stove.cookingTime[i]);
                data.put("slot_" + i + "_duration", stove.maxTime[i]);
                if (stove.ownerIds[i] != null) {
                    data.put("slot_" + i + "_owner_id", stove.ownerIds[i].toString());
                }
                if (stove.ownerNames[i] != null) {
                    data.put("slot_" + i + "_owner_name", stove.ownerNames[i]);
                }
                savedSlots++;
            }
        }
        storage.saveBlockData(normalized, BLOCK_TYPE, data);
        debug("save state: slots=" + savedSlots + ", location=" + formatLocation(normalized));
    }

    private void removeStoredData(Location location) {
        ManagerSupport.removeStoredData(plugin, location);
    }

}
