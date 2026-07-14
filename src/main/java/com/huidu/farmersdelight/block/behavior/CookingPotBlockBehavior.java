package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CookingPotBlockBehavior extends BlockBehavior implements EntityBlock, WorldlyContainerHolder {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    public static final int SLOT_MEAL_DISPLAY = 6;
    public static final int SLOT_CONTAINER = 7;
    public static final int SLOT_OUTPUT = 8;
    public static final int INVENTORY_SIZE = 9;

    private static final Map<UUID, Map<BlockPosKey, CookingPotBlockEntity>> worldBlockEntities = new ConcurrentHashMap<>();
    // Per-chunk index of block entity positions in the authoritative map: worldId -> (chunkKey -> set of posKeys).
    // Must be maintained in lockstep with structural writes to worldBlockEntities, otherwise on chunk unload
    // missed entities won't be saved, losing pot contents.
    private static final Map<UUID, Map<Long, Set<BlockPosKey>>> chunkIndex = new ConcurrentHashMap<>();
    // Progress text now rides on ProxyItemDisplayManager (packet-only TextDisplay) — value is the
    // proxy entityId. The proxy manager handles chunk-tracked viewer selection, distance filter, and
    // text diff internally, so per-pot visibility / throttle caches are gone.
    private static final Map<UUID, Map<BlockPosKey, Integer>> worldProgressDisplays = new ConcurrentHashMap<>();
    // cookingRecipeItems is per-world-keyed (via DisplayStateKey) so two pots at the same x,y,z in
    // different worlds don't share recipe-name state.
    private static final Map<DisplayStateKey, ItemStack> cookingRecipeItems = new ConcurrentHashMap<>();
    // World-scoped (via DisplayStateKey) so a pot placed at some x,y,z does not suppress the first interaction
    // with a different pot at the identical x,y,z in another world. BlockPosKey omits the world by design.
    private static final Map<DisplayStateKey, Long> recentPlacements = new ConcurrentHashMap<>();

    private static int visibilityCheckIntervalTicks() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? Constants.DEFAULT_COOKING_POT_DISPLAY_VISIBILITY_CHECK_INTERVAL_TICKS
                : Math.max(1, plugin.getConfigInt(
                        Constants.DEFAULT_COOKING_POT_DISPLAY_VISIBILITY_CHECK_INTERVAL_TICKS,
                        "cooking-pot.display.visibility-check-interval-ticks"));
    }

    private static long placeInteractionCooldownMs() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? Constants.DEFAULT_COOKING_POT_PLACE_INTERACTION_COOLDOWN_MS
                : Math.max(0, plugin.getConfigInt(
                        Constants.DEFAULT_COOKING_POT_PLACE_INTERACTION_COOLDOWN_MS,
                        "cooking-pot.place-interaction-cooldown-ms"));
    }

    /** World-scoped key used by the per-pot display caches above. */
    private record DisplayStateKey(UUID worldId, BlockPosKey pos) {
    }

    private static DisplayStateKey stateKey(World world, BlockPosKey posKey) {
        return new DisplayStateKey(world.getUID(), posKey);
    }

    private final String permission;
    private final boolean openWhileSneaking;
    private final boolean placeTrayOnOpen;
    private final String boilSound;
    private final String soupBoilSound;
    private final Double soundChance;
    private final Double soundVolume;
    private final Double soundPitchMin;
    private final Double soundPitchMax;
    final String customDataKey;
    private final CookingPotLayout layout;
    private final String customRecipeGroupId;
    private final String titleOverride;
    private int controllerId;

    private CookingPotBlockBehavior(
            BlockDefinition block,
            String permission,
            boolean openWhileSneaking,
            boolean placeTrayOnOpen,
            String boilSound,
            String soupBoilSound,
            Double soundChance,
            Double soundVolume,
            Double soundPitchMin,
            Double soundPitchMax,
            String customDataKey,
            CookingPotLayout layout,
            String customRecipeGroupId,
            String titleOverride
    ) {
        super(block);
        this.permission = permission;
        this.openWhileSneaking = openWhileSneaking;
        this.placeTrayOnOpen = placeTrayOnOpen;
        this.boilSound = boilSound;
        this.soupBoilSound = soupBoilSound;
        this.soundChance = soundChance;
        this.soundVolume = soundVolume;
        this.soundPitchMin = soundPitchMin;
        this.soundPitchMax = soundPitchMax;
        this.customDataKey = customDataKey;
        this.layout = layout != null ? layout : CookingPotLayout.DEFAULT;
        this.customRecipeGroupId = normalizeBlank(customRecipeGroupId);
        this.titleOverride = normalizeBlank(titleOverride);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new CookingPotBlockEntityController(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    public static CookingPotBlockEntity getBlockEntity(World world, BlockPos pos) {
        return getBlockEntity(world, new BlockPosKey(pos));
    }

    public static CookingPotBlockEntity getBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return null;
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return null;
        CookingPotBlockEntity entity = worldEntities.get(posKey);
        if (entity != null) {
            entity.setWorld(world);
        }
        return entity;
    }

    public static CookingPotBlockEntity getBlockEntity(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return getBlockEntity(location.getWorld(), new BlockPosKey(location));
    }

    public static CookingPotBlockEntity getOrCreateBlockEntity(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        World world = location.getWorld();
        BlockPosKey posKey = new BlockPosKey(location);
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        CookingPotBlockBehavior behavior = getBlockBehavior(location);
        // Maintain the index only when a new entity is actually created: the mapping function running means a structural write happened.
        boolean[] created = {false};
        CookingPotBlockEntity entity = worldEntities.computeIfAbsent(posKey, key -> {
            created[0] = true;
            return createBlockEntity(key, world, behavior);
        });
        if (created[0]) {
            indexAdd(world.getUID(), posKey);
        }
        if (behavior != null) {
            entity.applyBehavior(behavior);
        }
        entity.setWorld(world);
        return entity;
    }

    private static CookingPotBlockEntity createBlockEntity(BlockPosKey key, World world, CookingPotBlockBehavior behavior) {
        CookingPotLayout layout = behavior != null ? behavior.getLayout() : CookingPotLayout.DEFAULT;
        String recipeGroup = behavior != null ? behavior.getCustomRecipeGroupId() : null;
        return new CookingPotBlockEntity(key, world, layout, recipeGroup);
    }

    public static CookingPotBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) return null;
        Block block = location.getBlock();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null) return null;
        return CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
    }

    public static Map<BlockPosKey, CookingPotBlockEntity> getAllBlockEntities(World world) {
        if (world == null) return Map.of();
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null && !worldEntities.isEmpty()) {
            return worldEntities;
        }
        return Map.of();
    }

    public static Set<Map.Entry<BlockPosKey, CookingPotBlockEntity>> getBlockEntityEntries(World world) {
        if (world == null) return Set.of();
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null && !worldEntities.isEmpty()) {
            return worldEntities.entrySet();
        }
        return Set.of();
    }

    /** Compute the chunk key from block coordinates (high 32 bits = chunkX, low 32 bits = chunkZ). */
    private static long chunkKey(int blockX, int blockZ) {
        return (((long) (blockX >> 4)) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL);
    }

    /** Add a position to the chunk index. Must be called in lockstep with the registration write to worldEntities. */
    private static void indexAdd(UUID worldId, BlockPosKey posKey) {
        chunkIndex.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(posKey.x(), posKey.z()), k -> ConcurrentHashMap.newKeySet())
                .add(posKey);
    }

    /** Remove a position from the chunk index. Must be called in lockstep with the removal write to worldEntities. */
    private static void indexRemove(UUID worldId, BlockPosKey posKey) {
        Map<Long, Set<BlockPosKey>> worldChunks = chunkIndex.get(worldId);
        if (worldChunks == null) return;
        long ck = chunkKey(posKey.x(), posKey.z());
        Set<BlockPosKey> set = worldChunks.get(ck);
        if (set == null) return;
        set.remove(posKey);
        if (set.isEmpty()) worldChunks.remove(ck);
        if (worldChunks.isEmpty()) chunkIndex.remove(worldId);
    }

    /**
     * Returns only the cooking pot block entities within the given chunk, avoiding a linear scan of the whole world.
     * The authoritative map (worldBlockEntities) wins: stale leftover entries in the index that aren't found there are skipped.
     */
    public static Map<BlockPosKey, CookingPotBlockEntity> getBlockEntitiesInChunk(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CookingPotBlockEntity> result = new HashMap<>();
        if (world == null) return result;
        Map<Long, Set<BlockPosKey>> worldChunks = chunkIndex.get(world.getUID());
        if (worldChunks == null) return result;
        Set<BlockPosKey> posKeys = worldChunks.get((((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL));
        if (posKeys == null) return result;
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return result;
        for (BlockPosKey posKey : posKeys) {
            CookingPotBlockEntity entity = worldEntities.get(posKey);
            if (entity != null) result.put(posKey, entity);
        }
        return result;
    }

    public static List<Location> getBlockEntityLocations() {
        List<Location> locations = new ArrayList<>();
        for (Map<BlockPosKey, CookingPotBlockEntity> worldEntities : worldBlockEntities.values()) {
            for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : worldEntities.entrySet()) {
                World world = entry.getValue().getWorld();
                if (world != null) {
                    locations.add(entry.getKey().toLocation(world));
                }
            }
        }
        return locations;
    }

    /** Whether any tracked cooking pot block entity exists (cheap check, only iterates worlds, allocates no location list). */
    public static boolean hasAnyBlockEntities() {
        for (Map<BlockPosKey, CookingPotBlockEntity> worldEntities : worldBlockEntities.values()) {
            if (!worldEntities.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public String getBoilSound() {
        return boilSound;
    }

    public String getSoupBoilSound() {
        return soupBoilSound;
    }

    public Double getSoundChance() {
        return soundChance;
    }

    public Double getSoundVolume() {
        return soundVolume;
    }

    public Double getSoundPitchMin() {
        return soundPitchMin;
    }

    public Double getSoundPitchMax() {
        return soundPitchMax;
    }

    public String getCustomDataKey() {
        return customDataKey;
    }

    public CookingPotLayout getLayout() {
        return layout;
    }

    public String getCustomRecipeGroupId() {
        return customRecipeGroupId;
    }

    public String getTitleOverride() {
        return titleOverride;
    }

    public static void removeBlockEntity(World world, BlockPos pos) {
        removeBlockEntity(world, new BlockPosKey(pos));
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey) {
        removeBlockEntity(world, posKey, true);
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey, boolean removeStoredData) {
        if (world == null || posKey == null) return;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.getTickManager() != null) {
            plugin.getTickManager().markInactive(world, posKey, TickManager.BlockType.COOKING_POT);
        }
        removeProgressDisplay(world, posKey);
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null) {
            // Remove from the index only when actually removed from the authoritative map, keeping the two in sync.
            if (worldEntities.remove(posKey) != null) {
                indexRemove(world.getUID(), posKey);
            }
        }

        TrayManager trayManager = null;
        if (plugin != null) {
            trayManager = plugin.getTrayManager();
        }
        if (trayManager != null) {
            trayManager.removeTrayIfAutoPlaced(world, posKey.toBlockPos());
        }

        if (removeStoredData) {
            CustomBlockUtils.removeCraftEngineBlockEntity(world, posKey);
        }
    }

    public static void cleanupWorld(UUID worldId) {
        cleanupWorld(worldId, true);
    }

    public static void cleanupWorld(UUID worldId, boolean removeDisplayEntities) {
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.remove(worldId);
        if (worldEntities != null) {
            worldEntities.clear();
        }
        // On whole-world removal, also discard that world's chunk index.
        chunkIndex.remove(worldId);
        Map<BlockPosKey, Integer> displays = worldProgressDisplays.remove(worldId);
        if (displays != null) {
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            ItemDisplayManager visualManager = plugin == null ? null : plugin.getItemDisplayManager();
            for (Map.Entry<BlockPosKey, Integer> entry : displays.entrySet()) {
                if (removeDisplayEntities && visualManager != null) {
                    visualManager.destroyDisplay(entry.getValue());
                }
                cookingRecipeItems.remove(new DisplayStateKey(worldId, entry.getKey()));
            }
            displays.clear();
        }
    }

    public static void cleanupAll() {
        cleanupAll(true);
    }

    public static void cleanupAll(boolean removeDisplayEntities) {
        for (Map<BlockPosKey, CookingPotBlockEntity> worldEntities : worldBlockEntities.values()) {
            worldEntities.clear();
        }
        worldBlockEntities.clear();
        // When clearing the authoritative map, also clear the chunk index.
        chunkIndex.clear();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        ItemDisplayManager visualManager = plugin == null ? null : plugin.getItemDisplayManager();
        for (Map<BlockPosKey, Integer> displays : worldProgressDisplays.values()) {
            if (removeDisplayEntities && visualManager != null) {
                for (Integer entityId : displays.values()) {
                    visualManager.destroyDisplay(entityId);
                }
            }
            displays.clear();
        }
        worldProgressDisplays.clear();
        cookingRecipeItems.clear();
        recentPlacements.clear();
    }

    /** Adds every progress-text proxy display id tracked cooking pots still reference, so /fd
     *  cleanup removes only orphaned displays and leaves live pot progress text alone. */
    public static void collectLiveDisplayIds(java.util.Set<Integer> out) {
        for (Map<BlockPosKey, Integer> displays : worldProgressDisplays.values()) {
            out.addAll(displays.values());
        }
    }

    public static void markRecentlyPlaced(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // Also evict expired entries here: isRecentlyPlaced() only cleans up on query, so a pot that is placed
        // but never interacted with would otherwise leak its entry until cleanup.
        recentPlacements.entrySet().removeIf(entry -> now - entry.getValue() > placeInteractionCooldownMs());
        recentPlacements.put(new DisplayStateKey(location.getWorld().getUID(), new BlockPosKey(location)), now);
    }

    private static boolean isRecentlyPlaced(World world, BlockPosKey posKey) {
        if (world == null) {
            return false;
        }
        DisplayStateKey key = stateKey(world, posKey);
        Long placedAt = recentPlacements.get(key);
        if (placedAt == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - placedAt > placeInteractionCooldownMs()) {
            recentPlacements.remove(key, placedAt);
            return false;
        }
        return true;
    }

    public static void updateProgressDisplay(World world, BlockPosKey posKey, int progressPercent) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (world == null || posKey == null || progressPercent <= 0
                || (plugin == null || !plugin.isCookingPotProgressDisplayEnabled())) {
            removeProgressDisplay(world, posKey);
            return;
        }

        DisplayStateKey stateKey = stateKey(world, posKey);
        String text;
        if (plugin.isShowRecipeNameInProgressDisplay()) {
            ItemStack recipeItem = cookingRecipeItems.get(stateKey);
            if (recipeItem != null) {
                String recipeName = com.huidu.farmersdelight.util.ItemUtils.getDisplayName(recipeItem);
                text = recipeName + " " + progressPercent + "%";
            } else {
                text = progressPercent + "%";
            }
        } else {
            text = progressPercent + "%";
        }

        ItemDisplayManager visualManager = plugin.getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) {
            return;
        }

        net.kyori.adventure.text.Component component = net.kyori.adventure.text.Component.text(text);
        Map<BlockPosKey, Integer> worldDisplays = worldProgressDisplays.computeIfAbsent(
                world.getUID(), ignored -> new ConcurrentHashMap<>());
        Integer existingId = worldDisplays.get(posKey);
        if (existingId != null) {
            // updateText returns false only when the proxy id is gone (e.g. cleanup raced); re-spawn.
            if (visualManager.updateText(existingId, component)) {
                return;
            }
            worldDisplays.remove(posKey);
        }

        double yOffset = plugin.getCookingPotProgressDisplayYOffset();
        float scale = plugin.getCookingPotProgressDisplayScale();
        Location displayLoc = posKey.toLocation(world).clone().add(0.5, yOffset, 0.5);
        Transformation transformation = new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 0f, 1f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0f, 0f, 0f, 1f));
        int newId = visualManager.createTextDisplay(new ItemDisplayManager.TextDisplaySpec(
                displayLoc,
                component,
                transformation,
                org.bukkit.Color.fromARGB(0, 0, 0, 0),
                true,
                false));
        if (newId >= 0) {
            worldDisplays.put(posKey, newId);
        }
    }

    public static void setCookingRecipeItem(World world, BlockPosKey posKey, ItemStack recipeItem) {
        if (world == null || posKey == null) {
            return;
        }
        DisplayStateKey stateKey = stateKey(world, posKey);
        if (recipeItem != null && !recipeItem.getType().isAir()) {
            // Clone: the caller passes the recipe's shared result stack; store a private copy so a later
            // mutation of that stack elsewhere can't corrupt the cached display item.
            cookingRecipeItems.put(stateKey, recipeItem.clone());
        } else {
            cookingRecipeItems.remove(stateKey);
        }
    }

    public static void removeProgressDisplay(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;

        Map<BlockPosKey, Integer> displays = worldProgressDisplays.get(world.getUID());
        if (displays != null) {
            Integer entityId = displays.remove(posKey);
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            ItemDisplayManager visualManager = plugin == null ? null : plugin.getItemDisplayManager();
            if (entityId != null && visualManager != null) {
                visualManager.destroyDisplay(entityId);
            }
        }
        cookingRecipeItems.remove(stateKey(world, posKey));
    }

    public static void saveAllData() {
        for (Map.Entry<UUID, Map<BlockPosKey, CookingPotBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (Map.Entry<BlockPosKey, CookingPotBlockEntity> posEntry : worldEntry.getValue().entrySet()) {
                BlockPosKey posKey = posEntry.getKey();
                saveBlockEntityData(world, posKey);
            }
        }
    }

    public static void markAllBlockEntitiesDirty() {
        for (Map.Entry<UUID, Map<BlockPosKey, CookingPotBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (BlockPosKey posKey : worldEntry.getValue().keySet()) {
                markBlockEntityDirty(world, posKey);
            }
        }
    }

    public static void saveBlockEntityData(World world, BlockPos pos) {
        saveBlockEntityData(world, new BlockPosKey(pos));
    }

    public static void saveBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        if (!hasCookingPotBehavior(world, posKey)) {
            removeBlockEntity(world, posKey, true);
            return;
        }

        CookingPotBlockEntity entity = getBlockEntity(world, posKey);
        if (notifyControllerChanged(world, posKey, entity)) {
            return;
        }

        markBlockEntityDirty(world, posKey);
    }

    private static boolean notifyControllerChanged(World world, BlockPosKey posKey, CookingPotBlockEntity entity) {
        CookingPotBlockBehavior behavior = world != null && posKey != null ? getBlockBehavior(posKey.toLocation(world)) : null;
        Integer controllerId = behavior == null ? null : behavior.controllerId;
        return CustomBlockUtils.notifyControllerChanged(world, posKey, CookingPotBlockEntityController.class, controllerId,
                controller -> controller.setChangedFromEntity(entity));
    }

    public static void markBlockEntityDirty(World world, BlockPosKey posKey) {
        CustomBlockUtils.markBlockEntityDirty(world, posKey);
    }

    public static void loadBlockEntity(World world, BlockPos pos) {
        loadBlockEntity(world, new BlockPosKey(pos));
    }

    public static void loadBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null || !hasCookingPotBehavior(world, posKey)) return;
        getOrCreateBlockEntity(posKey.toLocation(world));
    }

    public static boolean isCookingPotBlock(World world, BlockPosKey posKey) {
        return hasCookingPotBehavior(world, posKey);
    }

    public static boolean hasCookingPotBehavior(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        return com.huidu.farmersdelight.util.CustomBlockUtils.hasBehavior(block, CookingPotBlockBehavior.class);
    }

    public static ItemStack insertIngredientLikeHopper(Location location, ItemStack item) {
        return insertThroughFace(location, item, Direction.UP);
    }

    public static ItemStack insertContainerLikeHopper(Location location, ItemStack item) {
        return insertThroughFace(location, item, Direction.NORTH);
    }

    private static ItemStack insertThroughFace(Location location, ItemStack item, Direction direction) {
        if (location == null || location.getWorld() == null || item == null || item.getType().isAir()) {
            return item == null ? null : item.clone();
        }

        World world = location.getWorld();
        BlockPosKey posKey = new BlockPosKey(location);
        CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld == null) {
            return item.clone();
        }

        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(posKey.toBlockPos());
        if (blockEntity == null) {
            return item.clone();
        }

        CookingPotBlockBehavior behavior = getBlockBehavior(location);
        if (behavior == null) {
            return item.clone();
        }

        CookingPotBlockEntity entity = getOrCreateBlockEntity(location);
        if (entity == null) {
            return item.clone();
        }

        AtomicBoolean handled = new AtomicBoolean(false);
        ItemStack[] fallbackResult = new ItemStack[1];
        ItemStack result = blockEntity.controller.let(CookingPotBlockEntityController.class, behavior.controllerId, controller -> {
            handled.set(true);
            return insertThroughController(controller, entity, item, direction);
        });
        if (!handled.get()) {
            blockEntity.controller.let(CookingPotBlockEntityController.class, controller -> {
                handled.set(true);
                fallbackResult[0] = insertThroughController(controller, entity, item, direction);
            });
            result = fallbackResult[0];
        }
        if (!handled.get()) {
            return item.clone();
        }
        return result == null ? null : result.clone();
    }

    private static ItemStack insertThroughController(CookingPotBlockEntityController controller,
                                                     CookingPotBlockEntity entity,
                                                     ItemStack item,
                                                     Direction direction) {
        synchronized (entity.getLock()) {
            controller.refreshFromEntity(entity);
            return controller.insertStackThroughFace(item, direction);
        }
    }

    public static final BlockBehaviorFactory<CookingPotBlockBehavior> FACTORY = new BlockBehaviorFactory<CookingPotBlockBehavior>() {
        @Override
        public CookingPotBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String permission = BehaviorArgParser.getString(arguments, "permission", "farmersdelight.use.cooking_pot");
            boolean openWhileSneaking = BehaviorArgParser.getBoolean(arguments, "open-while-sneaking", false);
            boolean placeTrayOnOpen = BehaviorArgParser.getBoolean(arguments, "place-tray-on-open", true);
            String boilSound = getNullableString(arguments, "boil-sound");
            String soupBoilSound = getNullableString(arguments, "soup-boil-sound");
            Double soundChance = getNullableDouble(arguments, "sound-chance");
            Double soundVolume = getNullableDouble(arguments, "sound-volume");
            Double soundPitchMin = getNullableDouble(arguments, "sound-pitch-min");
            Double soundPitchMax = getNullableDouble(arguments, "sound-pitch-max");
            String customDataKey = BehaviorArgParser.getString(arguments, "data-key", "farmersdelight:cooking_pot");
            Map<String, Object> custom = getMap(arguments, "custom");
            CookingPotLayout layout = CookingPotLayout.DEFAULT;
            String customRecipeGroupId = null;
            String titleOverride = null;
            if (custom != null && !custom.isEmpty()) {
                int inputSlots = BehaviorArgParser.getInt(custom, "input-slots", CookingPotLayout.DEFAULT.inputSlots().length);
                int pendingOutputSlots = BehaviorArgParser.getInt(custom, "pending-output-slots", CookingPotLayout.DEFAULT.pendingOutputSlots().length);
                int outputSlots = BehaviorArgParser.getInt(custom, "output-slots", CookingPotLayout.DEFAULT.outputSlots().length);
                int containerSlots = BehaviorArgParser.getInt(custom, "container-slots", CookingPotLayout.DEFAULT.containerSlots().length);
                layout = CookingPotLayout.custom(inputSlots, pendingOutputSlots, outputSlots, containerSlots);
                customRecipeGroupId = getNullableString(custom, "id");
                titleOverride = getNullableString(custom, "title");
                if (titleOverride == null) {
                    titleOverride = getNullableString(custom, "gui-title");
                }
            }
            return new CookingPotBlockBehavior(
                    block,
                    permission,
                    openWhileSneaking,
                    placeTrayOnOpen,
                    boilSound,
                    soupBoilSound,
                    soundChance,
                    soundVolume,
                    soundPitchMin,
                    soundPitchMax,
                    customDataKey,
                    layout,
                    customRecipeGroupId,
                    titleOverride
            );
        }
    };

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        BlockPosKey posKey = new BlockPosKey(pos);

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        if (context.getHand() == InteractionHand.MAIN_HAND
                && bukkitPlayer.isSneaking()
                && (bukkitPlayer.getInventory().getItemInMainHand() == null
                        || bukkitPlayer.getInventory().getItemInMainHand().getType().isAir())) {
            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            com.huidu.farmersdelight.manager.HandleManager hm = plugin == null ? null : plugin.getHandleManager();
            if (hm != null) {
                hm.toggleHandle(bukkitPlayer.getWorld(), pos, bukkitPlayer);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (bukkitPlayer.isSneaking() && !openWhileSneaking) {
            return InteractionResult.PASS;
        }

        if (isRecentlyPlaced(bukkitPlayer.getWorld(), posKey)) {
            return InteractionResult.PASS;
        }

        if (!permission.isBlank() && !bukkitPlayer.hasPermission(permission)) {
            bukkitPlayer.sendActionBar(I18n.getComponent("general.no_permission", bukkitPlayer));
            return InteractionResult.FAIL;
        }

        World world = bukkitPlayer.getWorld();
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());

        // Maintain the index only when a new entity is actually created (same as getOrCreateBlockEntity).
        boolean[] created = {false};
        CookingPotBlockEntity blockEntity = worldEntities.computeIfAbsent(posKey, key -> {
            created[0] = true;
            return createBlockEntity(key, world, this);
        });
        if (created[0]) {
            indexAdd(world.getUID(), posKey);
        }
        blockEntity.applyBehavior(this);
        blockEntity.setWorld(world);
        
        TickManager tickManager = FarmersDelightPlugin.getInstance().getTickManager();
        if (tickManager != null) {
            tickManager.markActive(world, posKey, TickManager.BlockType.COOKING_POT);
        }

        if (handleHeldContainerServing(bukkitPlayer, world, posKey, blockEntity)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        TrayManager trayManager = FarmersDelightPlugin.getInstance().getTrayManager();
        if (placeTrayOnOpen && trayManager != null) {
            trayManager.checkAndPlaceTray(world, pos);
        }

        CookingPotGui gui = new CookingPotGui(
                FarmersDelightPlugin.getInstance(), 
                blockEntity, 
                this, 
                world,
                posKey.toLocation(world)
        );
        gui.open(bukkitPlayer);

        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private boolean handleHeldContainerServing(Player player, World world, BlockPosKey posKey, CookingPotBlockEntity blockEntity) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) {
            return false;
        }
        if (!blockEntity.doesMealHaveContainer() || !blockEntity.isContainerValid(heldItem)) {
            return false;
        }

        CookingPotBlockEntity.TakenMeal takenMeal = blockEntity.useHeldContainerOnPendingMeal(heldItem);
        ItemStack meal = takenMeal == null ? null : takenMeal.item();
        if (meal == null || meal.getType().isAir()) {
            return false;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            heldItem.setAmount(heldItem.getAmount() - 1);
            if (heldItem.getAmount() <= 0) {
                player.getInventory().setItemInMainHand(null);
            }
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(meal);
        if (!leftovers.isEmpty()) {
            Location dropLocation = posKey.toLocation(world).add(0.5, 0.7, 0.5);
            leftovers.values().forEach(item -> world.dropItemNaturally(dropLocation, item));
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (takenMeal.experience() > 0.0D) {
            if (plugin.shouldDropCookingPotVanillaExperience()) {
                blockEntity.dropExperience(world, takenMeal.experience());
            }
            plugin.awardCookingPotAuraSkillsExperience(player, takenMeal.experience());
        }
        plugin.callCookingPotExperienceEvent(player, meal, takenMeal.experience());

        saveBlockEntityData(world, posKey);
        world.playSound(posKey.toLocation(world), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        player.updateInventory();
        return true;
    }

    public boolean checkHeatSource(BlockPos pos, World world) {
        HeatSourceConfig config = FarmersDelightPlugin.getInstance().getHeatSourceConfig();
        if (config == null) return false;
        
        Location locationBelow = new Location(world, pos.x(), pos.y() - 1, pos.z());
        Block blockBelow = locationBelow.getBlock();

        if (config.isHeatSource(blockBelow)) {
            return true;
        }

        if (config.isConductor(blockBelow)) {
            Location locationFurtherBelow = new Location(world, pos.x(), pos.y() - 2, pos.z());
            Block blockFurtherBelow = locationFurtherBelow.getBlock();
            return config.isHeatSource(blockFurtherBelow);
        }

        return false;
    }

    @Override
    public int getAnalogOutputSignal(Object thisBlock, Object[] args) {
        if (args.length >= 3) {
            World world = CraftEngineAdapter.toWorld(args[1]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
            if (world == null || pos == null) return 0;

            BlockPosKey posKey = new BlockPosKey(pos);
            Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
            if (worldEntities == null) return 0;

            CookingPotBlockEntity entity = worldEntities.get(posKey);
            if (entity == null) return 0;

            CookingPotLayout layout = entity.getLayout();
            int filledSlots = entity.countFilledInputSlots();

            if (entity.hasMealDisplayItem()) {
                filledSlots++;
            }

            return (filledSlots * 15) / (layout.inputSlots().length + 1);
        }
        return 0;
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        // Managed by TickManager.
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleStateRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleStateRemoval(args);
    }

    private static void handleStateRemoval(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        // Force-close any open viewers at this position FIRST, BEFORE the null-entity check: a player/explosion
        // break removes the entity in the CustomBlockBreakEvent path before this removal callback fires, so
        // gating the close on getBlockEntity != null would skip it for the common case and leave a still-open
        // Bukkit Inventory as a free pool of items the viewer can click out (dupe). closeOpenGuisAt only needs
        // the world + coords and is a cheap no-op when no viewer is open at this position.
        com.huidu.farmersdelight.gui.CookingPotGui.closeOpenGuisAt(world, pos.x(), pos.y(), pos.z());
        BlockPosKey posKey = new BlockPosKey(pos);
        if (getBlockEntity(world, posKey) == null) {
            return;
        }
        saveBlockEntityData(world, posKey);
        removeBlockEntity(world, posKey, false);
    }

    @Override
    public Object getContainer(Object thisBlock, Object[] args) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !plugin.isCookingPotHopperInteractionsEnabled()) {
            return null;
        }
        if (args == null || args.length < 3) {
            return null;
        }

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return null;
        }

        CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld == null) {
            return null;
        }

        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) {
            return null;
        }
        return blockEntity.controller.let(CookingPotBlockEntityController.class, this.controllerId, controller -> {
            CookingPotBlockEntity entity = getBlockEntity(world, pos);
            if (entity != null) {
                controller.refreshFromEntity(entity);
            }
            return controller.container();
        });
    }

    private static String getNullableString(Map<String, Object> arguments, String key) {
        Object value = getArgument(arguments, key);
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        if (string.isEmpty()) {
            return null;
        }
        return string;
    }

    private static Double getNullableDouble(Map<String, Object> arguments, String key) {
        Object value = getArgument(arguments, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object getArgument(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        return arguments.get(key);
    }

    private static Map<String, Object> getMap(Map<String, Object> arguments, String key) {
        Object value = getArgument(arguments, key);
        if (value instanceof net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            return section.values();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return null;
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
