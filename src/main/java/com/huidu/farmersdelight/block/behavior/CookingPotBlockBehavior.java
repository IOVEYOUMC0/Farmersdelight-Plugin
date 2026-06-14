package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
    // 按区块索引权威 map 中的方块实体位置：worldId -> (chunkKey -> posKey 集合)。
    // 该索引必须与 worldBlockEntities 的结构性写入紧耦合维护，否则区块卸载时
    // 漏掉的实体将不会被保存，导致锅内内容丢失。
    private static final Map<UUID, Map<Long, Set<BlockPosKey>>> chunkIndex = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPosKey, TextDisplay>> worldProgressDisplays = new ConcurrentHashMap<>();
    // 这些每个锅的显示缓存也必须以世界作为键：BlockPosKey 不包含世界信息，因此在不同世界中
    // 位于相同 x,y,z 坐标的两个锅，否则会共享进度文本 / 可见性
    // 节流 / 配方名称状态。
    private static final Map<DisplayStateKey, Set<UUID>> displayVisibleToPlayers = new ConcurrentHashMap<>();
    private static final Map<DisplayStateKey, Long> displayVisibilityLastCheckTick = new ConcurrentHashMap<>();
    private static final Map<DisplayStateKey, Long> progressDisplayCreateLastCheckTick = new ConcurrentHashMap<>();
    private static final Map<DisplayStateKey, String> progressDisplayTextCache = new ConcurrentHashMap<>();
    private static final int VISIBILITY_CHECK_INTERVAL_TICKS = 100;
    private static final Map<DisplayStateKey, ItemStack> cookingRecipeItems = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, Long> recentPlacements = new ConcurrentHashMap<>();
    private static final long PLACE_INTERACTION_COOLDOWN_MS = 1000L;

    /** 上述每个锅的显示缓存所用的世界范围键。 */
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
        // 仅在确实新建了实体时才维护索引:mapping 函数被调用即表示进行了一次结构性写入。
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

    /** 由方块坐标计算区块键(高 32 位为 chunkX,低 32 位为 chunkZ)。 */
    private static long chunkKey(int blockX, int blockZ) {
        return (((long) (blockX >> 4)) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL);
    }

    /** 把一个位置加入区块索引。必须与 worldEntities 的注册写入紧耦合调用。 */
    private static void indexAdd(UUID worldId, BlockPosKey posKey) {
        chunkIndex.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(posKey.x(), posKey.z()), k -> ConcurrentHashMap.newKeySet())
                .add(posKey);
    }

    /** 把一个位置从区块索引移除。必须与 worldEntities 的移除写入紧耦合调用。 */
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
     * 仅返回指定区块内的厨锅方块实体,避免对整世界做线性扫描。
     * 以权威 map(worldBlockEntities)为准:索引里可能存在的陈旧多余项若在权威 map 中查不到则跳过。
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

    /** 是否存在任何被跟踪的厨锅方块实体(廉价判断,只遍历世界数,不分配位置列表)。 */
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
            plugin.getTickManager().unregisterActiveBlock(world, posKey, TickManager.BlockType.COOKING_POT);
        }
        removeProgressDisplay(world, posKey);
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null) {
            // 仅在确实从权威 map 移除时才同步从索引移除,保持二者一致。
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
        // 整世界移除时同步丢弃该世界的区块索引。
        chunkIndex.remove(worldId);
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.remove(worldId);
        if (displays != null) {
            for (Map.Entry<BlockPosKey, TextDisplay> entry : displays.entrySet()) {
                BlockPosKey posKey = entry.getKey();
                TextDisplay display = entry.getValue();
                if (removeDisplayEntities && display != null && display.isValid()) {
                    display.remove();
                }
                DisplayStateKey stateKey = new DisplayStateKey(worldId, posKey);
                displayVisibleToPlayers.remove(stateKey);
                displayVisibilityLastCheckTick.remove(stateKey);
                progressDisplayCreateLastCheckTick.remove(stateKey);
                progressDisplayTextCache.remove(stateKey);
                cookingRecipeItems.remove(stateKey);
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
        // 清空权威 map 时同步清空区块索引。
        chunkIndex.clear();
        for (Map<BlockPosKey, TextDisplay> displays : worldProgressDisplays.values()) {
            for (TextDisplay display : displays.values()) {
                if (removeDisplayEntities && display != null && display.isValid()) {
                    display.remove();
                }
            }
            displays.clear();
        }
        worldProgressDisplays.clear();
        displayVisibleToPlayers.clear();
        displayVisibilityLastCheckTick.clear();
        progressDisplayCreateLastCheckTick.clear();
        progressDisplayTextCache.clear();
        cookingRecipeItems.clear();
        recentPlacements.clear();
    }

    public static void markRecentlyPlaced(Location location) {
        if (location == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 这里也要清除过期的条目：isRecentlyPlaced() 仅在查询时进行清理，因此一个被放置
        // 但从未交互过的锅，否则会一直泄漏其条目，直到清理为止。
        recentPlacements.entrySet().removeIf(entry -> now - entry.getValue() > PLACE_INTERACTION_COOLDOWN_MS);
        recentPlacements.put(new BlockPosKey(location), now);
    }

    private static boolean isRecentlyPlaced(BlockPosKey posKey) {
        Long placedAt = recentPlacements.get(posKey);
        if (placedAt == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - placedAt > PLACE_INTERACTION_COOLDOWN_MS) {
            recentPlacements.remove(posKey, placedAt);
            return false;
        }
        return true;
    }

    public static void updateProgressDisplay(World world, BlockPosKey posKey, int progressPercent) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (world == null || posKey == null || progressPercent <= 0
                || (plugin != null && !plugin.isCookingPotProgressDisplayEnabled())) {
            removeProgressDisplay(world, posKey);
            return;
        }

        DisplayStateKey stateKey = stateKey(world, posKey);
        String text;
        if (plugin != null && plugin.isShowRecipeNameInProgressDisplay()) {
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

        TextDisplay display = getProgressDisplay(world, posKey);
        String previousText = progressDisplayTextCache.get(stateKey);
        if (display != null && text.equals(previousText)) {
            updateDisplayVisibility(world, posKey, display);
            return;
        }

        if (display == null && !shouldCreateProgressDisplay(world, posKey)) {
            return;
        }
        if (display == null) {
            display = getOrCreateProgressDisplay(world, posKey);
        }
        if (display == null) {
            return;
        }

        progressDisplayTextCache.put(stateKey, text);
        display.text(net.kyori.adventure.text.Component.text(text));
        updateDisplayVisibility(world, posKey, display);
    }

    public static void setCookingRecipeItem(World world, BlockPosKey posKey, ItemStack recipeItem) {
        if (world == null || posKey == null) {
            return;
        }
        DisplayStateKey stateKey = stateKey(world, posKey);
        if (recipeItem != null && !recipeItem.getType().isAir()) {
            cookingRecipeItems.put(stateKey, recipeItem);
        } else {
            cookingRecipeItems.remove(stateKey);
        }
    }

    public static void removeProgressDisplay(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return;
        }

        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.get(world.getUID());
        if (displays != null) {
            TextDisplay display = displays.remove(posKey);
            if (display != null && display.isValid()) {
                display.remove();
            }
        }

        DisplayStateKey stateKey = stateKey(world, posKey);
        displayVisibleToPlayers.remove(stateKey);
        displayVisibilityLastCheckTick.remove(stateKey);
        progressDisplayCreateLastCheckTick.remove(stateKey);
        progressDisplayTextCache.remove(stateKey);
        cookingRecipeItems.remove(stateKey);
    }

    private static TextDisplay getProgressDisplay(World world, BlockPosKey posKey) {
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.get(world.getUID());
        if (displays == null) {
            return null;
        }
        TextDisplay display = displays.get(posKey);
        if (display != null && display.isValid()) {
            return display;
        }
        displays.remove(posKey);
        DisplayStateKey stateKey = stateKey(world, posKey);
        displayVisibleToPlayers.remove(stateKey);
        displayVisibilityLastCheckTick.remove(stateKey);
        progressDisplayTextCache.remove(stateKey);
        return null;
    }

    private static TextDisplay getOrCreateProgressDisplay(World world, BlockPosKey posKey) {
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.computeIfAbsent(
                world.getUID(), ignored -> new ConcurrentHashMap<>());

        TextDisplay existing = displays.get(posKey);
        if (existing != null && existing.isValid()) {
            return existing;
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        double yOffset = plugin != null ? plugin.getCookingPotProgressDisplayYOffset() : 1.2D;
        float scale = plugin != null ? plugin.getCookingPotProgressDisplayScale() : 0.5F;
        Location location = posKey.toLocation(world).clone().add(0.5, yOffset, 0.5);
        TextDisplay created = world.spawn(location, TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setPersistent(false);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            entity.setVisibleByDefault(false);
            entity.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));
        });
        displays.put(posKey, created);
        return created;
    }

    private static boolean shouldCreateProgressDisplay(World world, BlockPosKey posKey) {
        // 使用真实的服务器 tick（而非系统时钟），以便节流间隔反映实际经过的 tick 数，
        // 并在 TPS 卡顿时优雅地降级，而不是更频繁地重新检查。
        long currentTick = Bukkit.getCurrentTick();
        DisplayStateKey stateKey = stateKey(world, posKey);
        Long lastCheckTick = progressDisplayCreateLastCheckTick.get(stateKey);
        if (lastCheckTick != null && currentTick - lastCheckTick < VISIBILITY_CHECK_INTERVAL_TICKS) {
            return false;
        }
        progressDisplayCreateLastCheckTick.put(stateKey, currentTick);

        Location potLoc = posKey.toLocation(world).add(0.5, 0, 0.5);
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.scheduler().isFolia()) {
            return true;
        }
        double visibleDistanceSquared = plugin != null
                ? plugin.getCookingPotProgressDisplayVisibilityDistanceSquared()
                : 100.0D;
        double lookDotThreshold = plugin != null
                ? plugin.getCookingPotProgressDisplayLookDotThreshold()
                : 0.95D;

        for (Player player : world.getPlayers()) {
            if (canSeeProgressDisplay(player, potLoc, visibleDistanceSquared, lookDotThreshold)) {
                return true;
            }
        }
        return false;
    }

    private static void updateDisplayVisibility(World world, BlockPosKey posKey, TextDisplay display) {
        if (display == null || !display.isValid()) return;

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        DisplayStateKey stateKey = stateKey(world, posKey);
        if (plugin != null && plugin.scheduler().isFolia()) {
            display.setVisibleByDefault(true);
            displayVisibleToPlayers.remove(stateKey);
            return;
        }

        // 使用真实的服务器 tick（而非系统时钟），以便节流间隔反映实际经过的 tick 数，
        // 并在 TPS 卡顿时优雅地降级，而不是更频繁地重新检查。
        long currentTick = Bukkit.getCurrentTick();
        Long lastCheckTick = displayVisibilityLastCheckTick.get(stateKey);
        if (lastCheckTick != null && currentTick - lastCheckTick < VISIBILITY_CHECK_INTERVAL_TICKS) {
            return;
        }
        displayVisibilityLastCheckTick.put(stateKey, currentTick);

        Location potLoc = posKey.toLocation(world).add(0.5, 0, 0.5);
        Set<UUID> nearbyUUIDs = new HashSet<>();
        Set<UUID> visibleTo = displayVisibleToPlayers.computeIfAbsent(stateKey, k -> ConcurrentHashMap.newKeySet());
        double visibleDistanceSquared = plugin != null
                ? plugin.getCookingPotProgressDisplayVisibilityDistanceSquared()
                : 100.0D;
        double lookDotThreshold = plugin != null
                ? plugin.getCookingPotProgressDisplayLookDotThreshold()
                : 0.95D;

        for (Player p : world.getPlayers()) {
            if (!p.isOnline() || p.getLocation().distanceSquared(potLoc) > visibleDistanceSquared) {
                continue;
            }

            nearbyUUIDs.add(p.getUniqueId());

            boolean isLooking = isLookingAt(p, potLoc, lookDotThreshold);

            if (isLooking) {
                if (!visibleTo.contains(p.getUniqueId())) {
                    p.showEntity(FarmersDelightPlugin.getInstance(), display);
                    visibleTo.add(p.getUniqueId());
                }
            } else {
                if (visibleTo.contains(p.getUniqueId())) {
                    p.hideEntity(FarmersDelightPlugin.getInstance(), display);
                    visibleTo.remove(p.getUniqueId());
                }
            }
        }

        visibleTo.removeIf(uuid -> {
            if (nearbyUUIDs.contains(uuid)) {
                return false;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.hideEntity(FarmersDelightPlugin.getInstance(), display);
            }
            return true;
        });
    }

    private static boolean canSeeProgressDisplay(Player player, Location potLoc,
                                                 double visibleDistanceSquared,
                                                 double lookDotThreshold) {
        return player != null
                && player.isOnline()
                && player.getLocation().distanceSquared(potLoc) <= visibleDistanceSquared
                && isLookingAt(player, potLoc, lookDotThreshold);
    }

    private static boolean isLookingAt(Player player, Location potLoc, double lookDotThreshold) {
        Location eyeLoc = player.getEyeLocation();
        double dx = potLoc.getX() - eyeLoc.getX();
        double dy = potLoc.getY() - eyeLoc.getY();
        double dz = potLoc.getZ() - eyeLoc.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0001D) {
            return false;
        }
        org.bukkit.util.Vector lookDir = eyeLoc.getDirection();
        double dot = lookDir.getX() * (dx / length)
                + lookDir.getY() * (dy / length)
                + lookDir.getZ() * (dz / length);
        return dot > lookDotThreshold;
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

    public static void migrateLegacyBlockData(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null || !hasCookingPotBehavior(world, posKey)) return;

        CookingPotBlockEntity entity = getOrCreateBlockEntity(posKey.toLocation(world));
        if (entity == null) return;

        for (int i = 0; i < entity.getInventorySize(); i++) {
            Object item = data.get("slot_" + i);
            if (item instanceof ItemStack itemStack) {
                entity.setInventorySlot(i, itemStack);
                Object experience = data.get("slot_" + i + "_experience");
                if (experience instanceof Number number) {
                    entity.setSlotExperience(i, number.doubleValue());
                }
            }
        }
        if (data.get("cookingProgress") instanceof Number progress) {
            entity.setCookingProgress(progress.intValue());
        }
        if (data.get("cookingDuration") instanceof Number duration) {
            entity.setCookingDuration(duration.intValue());
        }
        if (data.get("mealContainer") instanceof ItemStack container) {
            entity.setMealContainer(container);
        }

        if (entity.hasStoredContents()) {
            TickManager tickManager = FarmersDelightPlugin.getInstance().getTickManager();
            if (tickManager != null) {
                tickManager.markActive(world, posKey, TickManager.BlockType.COOKING_POT);
            }
        }
        saveBlockEntityData(world, posKey);
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
            String permission = getString(arguments, "permission", "farmersdelight.use.cooking_pot");
            boolean openWhileSneaking = getBoolean(arguments, "open-while-sneaking", false);
            boolean placeTrayOnOpen = getBoolean(arguments, "place-tray-on-open", true);
            String boilSound = getNullableString(arguments, "boil-sound");
            String soupBoilSound = getNullableString(arguments, "soup-boil-sound");
            Double soundChance = getNullableDouble(arguments, "sound-chance");
            Double soundVolume = getNullableDouble(arguments, "sound-volume");
            Double soundPitchMin = getNullableDouble(arguments, "sound-pitch-min");
            Double soundPitchMax = getNullableDouble(arguments, "sound-pitch-max");
            String customDataKey = getString(arguments, "data-key", "farmersdelight:cooking_pot");
            Map<String, Object> custom = getMap(arguments, "custom");
            CookingPotLayout layout = CookingPotLayout.DEFAULT;
            String customRecipeGroupId = null;
            String titleOverride = null;
            if (custom != null && !custom.isEmpty()) {
                int inputSlots = getInt(custom, "input-slots", CookingPotLayout.DEFAULT.inputSlots().length);
                int pendingOutputSlots = getInt(custom, "pending-output-slots", CookingPotLayout.DEFAULT.pendingOutputSlots().length);
                int outputSlots = getInt(custom, "output-slots", CookingPotLayout.DEFAULT.outputSlots().length);
                int containerSlots = getInt(custom, "container-slots", CookingPotLayout.DEFAULT.containerSlots().length);
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

        if (bukkitPlayer.isSneaking() && !openWhileSneaking) {
            return InteractionResult.PASS;
        }

        if (isRecentlyPlaced(posKey)) {
            return InteractionResult.PASS;
        }

        if (!permission.isBlank() && !bukkitPlayer.hasPermission(permission)) {
            bukkitPlayer.sendActionBar(I18n.getComponent("general.no_permission", bukkitPlayer));
            return InteractionResult.FAIL;
        }

        World world = bukkitPlayer.getWorld();
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());

        // 仅在确实新建了实体时才维护索引(同 getOrCreateBlockEntity)。
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
        // 由 TickManager 管理。
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

    private static String getString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = getArgument(arguments, key);
        if (value != null) {
            return String.valueOf(value);
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = getArgument(arguments, key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        return defaultValue;
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

    private static int getInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = getArgument(arguments, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
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
