package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionContext;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionHandler;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.PermissionChecker;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CuttingBoardBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock, WorldlyContainerHolder {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private static final Map<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldBlockEntities = new ConcurrentHashMap<>();
    // Index of block entity positions in the authoritative map by chunk: worldId -> (chunkKey -> posKey set).
    // This index must be maintained in lockstep with structural writes to worldBlockEntities, otherwise
    // missed entities won't be saved on chunk unload, losing cutting board contents.
    private static final Map<UUID, Map<Long, Set<BlockPosKey>>> chunkIndex = new ConcurrentHashMap<>();
    // Inner key is world-scoped (WorldPos) so the same player interacting at the identical x,y,z in two
    // different worlds within the guard window does not have one interaction eaten by the other's guard.
    private static final Map<UUID, Map<WorldPos, Long>> recentManualInsertions = new ConcurrentHashMap<>();
    private static final long MANUAL_INSERT_GUARD_MILLIS = 250L;
    private static final int DEFAULT_RELOAD_VISUAL_REFRESH_BUDGET = 32;
    // Increase each output unit's keep chance by this amount per Fortune level.
    private static final double FORTUNE_BONUS_PER_LEVEL = 0.1d;
    private static final Object displayRefreshLock = new Object();
    private static final Deque<DisplayRefresh> pendingDisplayRefreshes = new ArrayDeque<>();
    private static PluginTask displayRefreshTask;

    private record WorldPos(UUID worldId, BlockPosKey pos) {
    }

    private record DisplayRefresh(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
    }

    // The plugin is handed to the constructor by the behavior factory rather than fetched statically, so the
    // interaction paths below read the same services the factory was built against.
    private final FarmersDelightPlugin plugin;
    private final Property<?> facingProperty;
    private final String knifeSound;
    private final int maxStackAmount;
    private final String customDataKey;
    private final CuttingBoardToolMatcher toolMatcher;
    private final CuttingBoardCutter cutter;
    private int controllerId;

    private CuttingBoardBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, Property<?> facingProperty, List<Key> toolTags, List<Key> toolItems, String knifeSound, int maxStackAmount, String customDataKey) {
        super(block);
        this.plugin = plugin;
        this.facingProperty = facingProperty;
        this.knifeSound = knifeSound;
        this.maxStackAmount = maxStackAmount;
        this.customDataKey = customDataKey;
        this.toolMatcher = new CuttingBoardToolMatcher(plugin, toolTags, toolItems);
        this.cutter = new CuttingBoardCutter(plugin, toolMatcher);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new CuttingBoardBlockEntityController(plugin, blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    String customDataKey() {
        return this.customDataKey;
    }

    public static CuttingBoardBlockEntity getBlockEntity(World world, BlockPos pos) {
        return getBlockEntity(world, new BlockPosKey(pos));
    }

    public static CuttingBoardBlockEntity getBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return null;
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return null;
        CuttingBoardBlockEntity entity = worldEntities.get(posKey);
        if (entity != null) {
            entity.setWorld(world);
        }
        return entity;
    }

    public static Map<BlockPosKey, CuttingBoardBlockEntity> getAllBlockEntities(World world) {
        if (world == null) return Map.of();
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null && !worldEntities.isEmpty()) {
            return worldEntities;
        }
        return Map.of();
    }

    public static Set<Map.Entry<BlockPosKey, CuttingBoardBlockEntity>> getBlockEntityEntries(World world) {
        if (world == null) return Set.of();
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null && !worldEntities.isEmpty()) {
            return worldEntities.entrySet();
        }
        return Set.of();
    }

    public static void collectLiveDisplayIds(Set<Integer> out) {
        for (Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities : worldBlockEntities.values()) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                entity.collectDisplayIds(out);
            }
        }
    }

    private static void indexAdd(UUID worldId, BlockPosKey posKey) {
        chunkIndex.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ManagerSupport.chunkKey(posKey.x() >> 4, posKey.z() >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(posKey);
    }

    private static void indexRemove(UUID worldId, BlockPosKey posKey) {
        Map<Long, Set<BlockPosKey>> worldChunks = chunkIndex.get(worldId);
        if (worldChunks == null) return;
        long ck = ManagerSupport.chunkKey(posKey.x() >> 4, posKey.z() >> 4);
        Set<BlockPosKey> set = worldChunks.get(ck);
        if (set == null) return;
        set.remove(posKey);
        if (set.isEmpty()) worldChunks.remove(ck);
        if (worldChunks.isEmpty()) chunkIndex.remove(worldId);
    }

    public static Map<BlockPosKey, CuttingBoardBlockEntity> getBlockEntitiesInChunk(World world, int chunkX, int chunkZ) {
        Map<BlockPosKey, CuttingBoardBlockEntity> result = new HashMap<>();
        if (world == null) return result;
        Map<Long, Set<BlockPosKey>> worldChunks = chunkIndex.get(world.getUID());
        if (worldChunks == null) return result;
        Set<BlockPosKey> posKeys = worldChunks.get(ManagerSupport.chunkKey(chunkX, chunkZ));
        if (posKeys == null) return result;
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return result;
        for (BlockPosKey posKey : posKeys) {
            CuttingBoardBlockEntity entity = worldEntities.get(posKey);
            if (entity != null) result.put(posKey, entity);
        }
        return result;
    }

    public static void putBlockEntity(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        if (world == null || posKey == null || entity == null) {
            return;
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        entity.setWorld(world);
        CuttingBoardBlockEntity previous = worldEntities.put(posKey, entity);
        if (previous != null && previous != entity) {
            // Overwriting a still-tracked board entity (double load / reload): remove the old one's display
            // entity so it doesn't orphan.
            previous.removeDisplayEntity();
        }
        // put always writes to the authoritative map, so update the index unconditionally.
        indexAdd(world.getUID(), posKey);
    }

    public static void removeBlockEntity(World world, BlockPos pos) {
        removeBlockEntity(world, new BlockPosKey(pos));
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey) {
        removeBlockEntity(world, posKey, true);
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey, boolean removeStoredData) {
        if (world == null || posKey == null) return;
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null) {
            CuttingBoardBlockEntity entity = worldEntities.remove(posKey);
            if (entity != null) {
                // Only remove from the index when actually removed from the authoritative map, keeping the two consistent.
                indexRemove(world.getUID(), posKey);
                entity.removeDisplayEntity();
            }
        }
        if (removeStoredData) {
            CustomBlockUtils.removeCraftEngineBlockEntity(world, posKey);
        }
    }

    public static void cleanupWorld(UUID worldId) {
        cleanupWorld(worldId, true);
    }

    public static void cleanupWorld(UUID worldId, boolean removeDisplayEntities) {
        synchronized (displayRefreshLock) {
            pendingDisplayRefreshes.removeIf(refresh -> refresh.world().getUID().equals(worldId));
            if (pendingDisplayRefreshes.isEmpty()) {
                stopDisplayRefreshTask();
            }
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.remove(worldId);
        if (worldEntities != null) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                if (removeDisplayEntities) {
                    entity.removeDisplayEntity();
                }
            }
            worldEntities.clear();
        }
        // When removing the whole world, also drop that world's chunk index.
        chunkIndex.remove(worldId);
    }

    public static void cleanupAll() {
        cleanupAll(true);
    }

    public static void cleanupAll(boolean removeDisplayEntities) {
        synchronized (displayRefreshLock) {
            pendingDisplayRefreshes.clear();
            stopDisplayRefreshTask();
        }
        for (Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities : worldBlockEntities.values()) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                if (removeDisplayEntities) {
                    entity.removeDisplayEntity();
                }
            }
            worldEntities.clear();
        }
        worldBlockEntities.clear();
        // When clearing the authoritative map, also clear the chunk index.
        chunkIndex.clear();
        recentManualInsertions.clear();
    }

    public static void markManualInsertion(World world, BlockPosKey posKey, UUID playerId) {
        if (world == null || posKey == null || playerId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<WorldPos, Long> guarded = recentManualInsertions.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        // Drop expired guard entries that were never consumed by a later event, so the map doesn't
        // accumulate stale position data.
        guarded.entrySet().removeIf(entry -> now - entry.getValue() > MANUAL_INSERT_GUARD_MILLIS);
        guarded.put(new WorldPos(world.getUID(), posKey), now);
    }

    private static boolean consumeManualInsertionGuard(UUID playerId, World world, BlockPosKey posKey) {
        if (playerId == null || world == null || posKey == null) {
            return false;
        }
        Map<WorldPos, Long> guardedPositions = recentManualInsertions.get(playerId);
        if (guardedPositions == null) {
            return false;
        }

        Long timestamp = guardedPositions.remove(new WorldPos(world.getUID(), posKey));
        if (guardedPositions.isEmpty()) {
            recentManualInsertions.remove(playerId);
        }
        if (timestamp == null) {
            return false;
        }
        return System.currentTimeMillis() - timestamp <= MANUAL_INSERT_GUARD_MILLIS;
    }

    public static void saveAllData() {
        for (Map.Entry<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (Map.Entry<BlockPosKey, CuttingBoardBlockEntity> posEntry : worldEntry.getValue().entrySet()) {
                BlockPosKey posKey = posEntry.getKey();
                saveBlockEntityData(world, posKey);
            }
        }
    }

    public static void markAllBlockEntitiesDirty() {
        for (Map.Entry<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (BlockPosKey posKey : worldEntry.getValue().keySet()) {
                markBlockEntityDirty(world, posKey);
            }
        }
    }

    public static void refreshDisplayEntities() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return;
        }
        synchronized (displayRefreshLock) {
            pendingDisplayRefreshes.clear();
            for (Map.Entry<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
                World world = Bukkit.getWorld(worldEntry.getKey());
                if (world == null) continue;

                for (Map.Entry<BlockPosKey, CuttingBoardBlockEntity> posEntry : worldEntry.getValue().entrySet()) {
                    CuttingBoardBlockEntity entity = posEntry.getValue();
                    if (entity != null) {
                        pendingDisplayRefreshes.addLast(new DisplayRefresh(world, posEntry.getKey(), entity));
                    }
                }
            }
            if (pendingDisplayRefreshes.isEmpty()) {
                stopDisplayRefreshTask();
            } else if (displayRefreshTask == null || displayRefreshTask.isCancelled()) {
                displayRefreshTask = plugin.scheduler().runRepeating(
                        CuttingBoardBlockBehavior::refreshNextDisplayEntities, 1L, 1L);
            }
        }
    }

    private static void refreshNextDisplayEntities() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            synchronized (displayRefreshLock) {
                pendingDisplayRefreshes.clear();
                stopDisplayRefreshTask();
            }
            return;
        }

        int budget = Math.max(1, plugin.getConfigInt(DEFAULT_RELOAD_VISUAL_REFRESH_BUDGET,
                "performance.budgets.reload-visual-refreshes-per-tick"));
        for (int i = 0; i < budget; i++) {
            DisplayRefresh refresh;
            synchronized (displayRefreshLock) {
                refresh = pendingDisplayRefreshes.pollFirst();
            }
            if (refresh == null) {
                break;
            }

            plugin.scheduler().runAt(refresh.posKey().toLocation(refresh.world()), () -> {
                if (getBlockEntity(refresh.world(), refresh.posKey()) == refresh.entity()) {
                    refresh.entity().refreshDisplayEntity(refresh.world(),
                            getStoredBlockFacing(refresh.world(), refresh.posKey()));
                }
            });
        }

        synchronized (displayRefreshLock) {
            if (pendingDisplayRefreshes.isEmpty()) {
                stopDisplayRefreshTask();
            }
        }
    }

    private static void stopDisplayRefreshTask() {
        if (displayRefreshTask != null) {
            displayRefreshTask.cancel();
            displayRefreshTask = null;
        }
    }

    public static void saveBlockEntityData(World world, BlockPos pos) {
        saveBlockEntityData(world, new BlockPosKey(pos));
    }

    public static void saveBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;

        if (isCuttingBoardBlock(world, posKey)) {
            // The CE state can be transiently unresolvable (a /ce reload unbinds states for the parse
            // window); deleting the stored NBT here would destroy a live board's item. Just mark the
            // chunk dirty — a genuinely replaced block is cleaned up by the break/removal callbacks.
            markBlockEntityDirty(world, posKey);
            return;
        }

        CuttingBoardBlockEntity entity = getBlockEntity(world, posKey);
        if (notifyControllerChanged(world, posKey, entity)) {
            return;
        }

        markBlockEntityDirty(world, posKey);
    }

    public static void passivateBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        CuttingBoardBlockEntity entity = getBlockEntity(world, posKey);
        if (entity == null) {
            saveBlockEntityData(world, posKey);
            return;
        }
        CuttingBoardBlockBehavior behavior = getBlockBehavior(posKey.toLocation(world));
        Integer controllerId = behavior == null ? null : behavior.controllerId;
        boolean stashed = CustomBlockUtils.notifyControllerChanged(world, posKey,
                CuttingBoardBlockEntityController.class, controllerId,
                controller -> controller.passivate(entity));
        if (!stashed) {
            saveBlockEntityData(world, posKey);
        }
    }

    public static void flushPendingControllerData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        CuttingBoardBlockBehavior behavior = getBlockBehavior(posKey.toLocation(world));
        Integer controllerId = behavior == null ? null : behavior.controllerId;
        CustomBlockUtils.notifyControllerChanged(world, posKey, CuttingBoardBlockEntityController.class,
                controllerId, CuttingBoardBlockEntityController::loadPendingDataIfReady);
    }

    private static boolean notifyControllerChanged(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        CuttingBoardBlockBehavior behavior = world != null && posKey != null ? getBlockBehavior(posKey.toLocation(world)) : null;
        Integer controllerId = behavior == null ? null : behavior.controllerId;
        return CustomBlockUtils.notifyControllerChanged(world, posKey, CuttingBoardBlockEntityController.class, controllerId,
                controller -> controller.setChangedFromEntity(entity));
    }

    public static void markBlockEntityDirty(World world, BlockPosKey posKey) {
        CustomBlockUtils.markBlockEntityDirty(world, posKey);
    }

    public static void loadBlockEntity(FarmersDelightPlugin plugin, World world, BlockPosKey posKey) {
        if (posKey == null || isCuttingBoardBlock(world, posKey)) return;
        // Saved data may still be parked on the controller (deferred startup load, or a chunk served from
        // CraftEngine's chunk cache); apply it first so the computeIfAbsent below does not create a blank
        // entity that shadows the stored item and gets overwritten by the late apply.
        flushPendingControllerData(world, posKey);
        // Only update the index when an entity was actually newly created.
        boolean[] created = {false};
        CuttingBoardBlockEntity entity = worldBlockEntities.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(posKey, key -> {
                    created[0] = true;
                    return new CuttingBoardBlockEntity(plugin, key, world);
                });
        if (created[0]) {
            indexAdd(world.getUID(), posKey);
        }
        entity.setWorld(world);
    }

    public static boolean isCuttingBoardBlock(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return true;
        }
        var block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        return !CustomBlockUtils.hasBehavior(block, CuttingBoardBlockBehavior.class)
                && !CustomBlockUtils.hasId(block, Constants.BLOCK_CUTTING_BOARD);
    }

    public static final BlockBehaviorFactory<CuttingBoardBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        // The factory runs while CraftEngine parses the pack, which is always after this plugin enabled.
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        Property<?> facingProperty = block.getProperty("facing");
        if (facingProperty == null) {
            plugin.getLogger()
                    .warning("[FarmersDelight] Block " + block.id() + " is missing the 'facing' property"
                            + " — the cutting board will not face any direction and may misbehave when placed.");
        }

        List<String> toolTagStrings = getStringList(arguments, "tool-tags");
        if (toolTagStrings.isEmpty()) {
            toolTagStrings = List.of(Constants.TAG_KNIVES, Constants.TAG_AXES, Constants.TAG_PICKAXES, Constants.TAG_SHOVELS);
        }

        List<Key> toolTags = toolTagStrings.stream()
                .map(CuttingBoardBlockBehavior::normalizeTagKey)
                .toList();

        List<String> toolItemStrings = getStringList(arguments, "tool-items");
        if (toolItemStrings.isEmpty()) {
            toolItemStrings = List.of(Constants.ITEM_SHEARS);
        }

        List<Key> toolItems = toolItemStrings.stream()
                .map(Key::of)
                .toList();

        String knifeSound = BehaviorArgParser.getArgumentString(arguments, "knife-sound", Constants.SOUND_CUTTING_BOARD_KNIFE);
        int maxStackAmount = BehaviorArgParser.getInt(arguments, "max-stack-amount", 64);
        String customDataKey = BehaviorArgParser.getArgumentString(arguments, "data-key", "farmersdelight:cutting_board");
        return new CuttingBoardBlockBehavior(plugin, block, facingProperty, toolTags, toolItems, knifeSound, maxStackAmount, customDataKey);
    };

    public String getKnifeSound() {
        return knifeSound;
    }

    public int getMaxStackAmount() {
        return maxStackAmount;
    }

    public static CuttingBoardBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        return CustomBlockUtils.getBehavior(state, CuttingBoardBlockBehavior.class);
    }

    private static Key normalizeTagKey(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        return Key.of(text);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        BlockPosKey posKey = new BlockPosKey(pos);

        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        if (consumeManualInsertionGuard(bukkitPlayer.getUniqueId(), bukkitPlayer.getWorld(), posKey)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (!PermissionChecker.check(bukkitPlayer, "farmersdelight.use.cutting_board")) {
            return InteractionResult.PASS;
        }

        World world = bukkitPlayer.getWorld();
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        if (!ProtectionCompat.canUse(bukkitPlayer, block, ProtectionCompat.Feature.CUTTING_BOARD)
                || !ProtectionCompat.canBuild(bukkitPlayer, block, ProtectionCompat.Feature.CUTTING_BOARD)) {
            return InteractionResult.PASS;
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        CuttingBoardBlockEntity blockEntity = worldEntities.get(posKey);
        if (blockEntity == null) {
            loadBlockEntity(plugin, world, posKey);
            blockEntity = worldEntities.get(posKey);
        }
        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(plugin, posKey, world);
            worldEntities.put(posKey, blockEntity);
            // put always writes to the authoritative map, so update the index unconditionally.
            indexAdd(world.getUID(), posKey);
        }
        blockEntity.setWorld(world);

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        ItemStack offHand = bukkitPlayer.getInventory().getItemInOffHand();
        boolean allowOffhandInteractions = plugin.isCuttingBoardOffhandInteractionsAllowed();
        boolean stackingEnabled = plugin.isCuttingBoardStackingEnabled();
        BlockFace facing = getFacing(state);
        debug("useOnBlock mode=" + plugin.getCuttingBoardInteractionMode().configKey()
                + ", hasItem=" + blockEntity.hasItem()
                + ", main=" + formatItem(mainHand)
                + ", off=" + formatItem(offHand)
                + ", allowOffhand=" + allowOffhandInteractions
                + ", stacking=" + stackingEnabled
                + ", pos=" + posKey);

        // Addon-registered handlers get the interaction first (after permission / protection checks, before
        // FarmersDelight's own placement, cutting and stacking logic). The first handler that consumes wins.
        if (runExternalInteractionHandlers(plugin, bukkitPlayer, block, blockEntity, facing, world, posKey,
                mainHand, offHand)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (blockEntity.hasItem()) {
            ItemStack tool = cutter.findMatchingTool(blockEntity, mainHand, offHand, allowOffhandInteractions);
            boolean toolIsOffhand = allowOffhandInteractions && tool != null && tool == offHand;
            if (tool != null) {
                boolean result = cutter.processCutting(blockEntity, tool, bukkitPlayer, facing, world, posKey, toolIsOffhand);
                if (result) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }

            if (tryStackOntoBoard(blockEntity, mainHand, bukkitPlayer, facing, world, posKey)) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }

            if (toolMatcher.isTool(mainHand) || (allowOffhandInteractions && toolMatcher.isTool(offHand))) {
                boolean hasRecipe = plugin.getCuttingBoardRecipes()
                        .hasAnyRecipeFor(blockEntity.getStoredItem());
                if (!hasRecipe) {
                    bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.no_recipe", bukkitPlayer));
                    bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
                } else {
                    bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.wrong_tool", bukkitPlayer));
                    bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                }
                return InteractionResult.SUCCESS_AND_CANCEL;
            } else if (!mainHand.getType().isAir() && !bukkitPlayer.isSneaking() && !isTool(mainHand)) {
                bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.need_tool", bukkitPlayer));
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (!blockEntity.hasItem()) {
            boolean mainHandEmpty = mainHand == null || mainHand.getType().isAir();
            boolean offHandEmpty = offHand == null || offHand.getType().isAir();
            boolean mainHandTool = !mainHandEmpty && toolMatcher.isTool(mainHand);
            boolean offHandTool = !offHandEmpty && toolMatcher.isTool(offHand);

            if (allowOffhandInteractions && !offHandEmpty && (mainHandEmpty || mainHandTool) && !offHandTool) {
                if (bukkitPlayer.isSneaking() && toolMatcher.isTool(offHand)) {
                    return InteractionResult.PASS;
                }
                if (tryPlaceOnEmptyBoard(offHand, true, bukkitPlayer, world, posKey, facing, blockEntity)) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }

            if (!mainHandEmpty) {
                if (tryPlaceOnEmptyBoard(mainHand, false, bukkitPlayer, world, posKey, facing, blockEntity)) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }

            if (allowOffhandInteractions && !offHandEmpty && (mainHandEmpty || mainHandTool)) {
                if (bukkitPlayer.isSneaking() && offHandTool) {
                    return InteractionResult.PASS;
                }

                if (tryPlaceOnEmptyBoard(offHand, true, bukkitPlayer, world, posKey, facing, blockEntity)) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }

            if (!allowOffhandInteractions || offHandEmpty
                    || !plugin.getCuttingBoardRecipes().hasAnyRecipeFor(offHand)) {
                bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.no_recipe", bukkitPlayer));
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (blockEntity.hasItem() && mainHand.getType().isAir()) {
            // Atomic getAndClear so two concurrent empty-hand right-clicks (different Folia regions) can't
            // both observe hasItem == true and each take a clone of the same stored item.
            ItemStack storedItem;
            synchronized (blockEntity) {
                if (!blockEntity.hasItem()) {
                    return InteractionResult.PASS;
                }
                storedItem = blockEntity.getStoredItem();
                blockEntity.clearItem();
            }
            removeStoredData(world, posKey);

            if (storedItem != null && !storedItem.getType().isAir() && bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                ItemDelivery.giveOrDrop(bukkitPlayer, posKey.toLocation(world).add(0.5, 0.2, 0.5), storedItem);
            }

            // Sound config is cached on the plugin (reload-refreshed volatile); reading it here instead of
            // re-parsing YAML on every retrieval right-click, matching the other real-time config getters.
            float volume = plugin.getCuttingBoardFailVolume();
            float pitch = plugin.getCuttingBoardFailPitch();
            bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_WOOD_HIT, volume, pitch);
            bukkitPlayer.swingMainHand();
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    // Runs every addon-registered cutting-board handler against this right-click until one consumes. Runs on
    // the world region thread. A throwing handler is logged and skipped so one addon cannot break the board.
    private static boolean runExternalInteractionHandlers(FarmersDelightPlugin plugin, Player player, Block block, CuttingBoardBlockEntity blockEntity,
                                                          BlockFace facing, World world, BlockPosKey posKey,
                                                          ItemStack mainHand, ItemStack offHand) {
        List<CuttingBoardInteractionHandler> handlers =
                plugin.getCuttingBoardInteractionHandlers();
        if (handlers.isEmpty()) {
            return false;
        }
        CuttingBoardInteractionContext context = new CuttingBoardInteractionContext(
                player, block, mainHand, offHand,
                blockEntity::getStoredItem,
                item -> blockEntity.setStoredItem(item, world, posKey, facing),
                block::getLocation);
        for (CuttingBoardInteractionHandler handler : handlers) {
            try {
                if (handler.handle(context)) {
                    return true;
                }
            } catch (RuntimeException e) {
                plugin.getLogger()
                        .warning("cutting board interaction handler failed: " + e);
            }
        }
        return false;
    }

    private boolean tryPlaceOnEmptyBoard(ItemStack sourceItem, boolean offhand, Player player,
                                         World world, BlockPosKey posKey, BlockFace facing,
                                         CuttingBoardBlockEntity blockEntity) {
        if (sourceItem == null || sourceItem.getType().isAir()) {
            return false;
        }
        synchronized (blockEntity) {
            // Re-check inside the lock: another viewer may have placed an item between the outer
            // hasItem() probe and this call, in which case stacking (not placing) is the right path.
            if (blockEntity.hasItem()) {
                return false;
            }
            return tryPlaceOnEmptyBoardLocked(sourceItem, offhand, player, world, posKey, facing, blockEntity);
        }
    }

    private boolean tryPlaceOnEmptyBoardLocked(ItemStack sourceItem, boolean offhand, Player player,
                                                World world, BlockPosKey posKey, BlockFace facing,
                                                CuttingBoardBlockEntity blockEntity) {

        // Optional restriction: only recipe-input items (or tools) may be placed. Rejected items fall
        // through to the existing "no recipe" feedback in useOnBlock. Real-time read so /fd reload applies.
        if (plugin.isCuttingBoardRecipeOnlyPlacement()
                && !toolMatcher.isTool(sourceItem)
                && !plugin.getCuttingBoardRecipes().hasAnyRecipeFor(sourceItem)) {
            return false;
        }

        ItemStack itemToPlace = sourceItem.clone();
        int stackLimit = getBoardStackLimit(sourceItem);
        int amountToMove = itemToPlace.getAmount();
        // Read the stacking switch in real time to avoid using a stale cached value after /fd reload switches interaction-mode
        // (consistent with the checks in useOnBlock and the hopper controller).
        if (plugin.isCuttingBoardStackingEnabled()) {
            amountToMove = Math.min(amountToMove, stackLimit);
        } else {
            amountToMove = 1;
        }
        itemToPlace.setAmount(amountToMove);

        boolean carveTool = !offhand && player.isSneaking() && toolMatcher.isTool(sourceItem);
        // Store, then consume — but atomically: setStoredItem commits the item field before it runs its
        // remaining side effects (container sync / persistence), and on Folia one of those can throw a
        // region thread-check. Without this guard a throw there would leave the item stored while the
        // consume below is skipped — a phantom item retrievable for free (the protected-region dupe). If
        // the store throws, the write is rolled back without consuming, so the player keeps the item and the board
        // stays empty: no dupe and no loss.
        try {
            storeItemInBoard(world, posKey, facing, blockEntity, itemToPlace, carveTool);
        } catch (RuntimeException | LinkageError t) {
            try {
                blockEntity.setStoredItem(null, world, posKey, facing);
            } catch (RuntimeException | LinkageError ignored) {
                // display cleanup is best-effort; block removal proceeds regardless
            }
            debug("place rolled back after store failure: " + t);
            return false;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            sourceItem.setAmount(sourceItem.getAmount() - amountToMove);
            if (sourceItem.getAmount() <= 0) {
                if (offhand) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            }
        }
        Sound placeSound = carveTool ? Sound.ITEM_TRIDENT_HIT : Sound.BLOCK_WOOD_PLACE;
        float pitch = carveTool ? 1.2f : 1.0f;
        String soundKey = carveTool ? knifeSound : null;
        SoundUtils.play(player.getWorld(), player.getLocation(), soundKey, placeSound, 1.0f, pitch);
        if (offhand) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
        return true;
    }

    private boolean tryStackOntoBoard(CuttingBoardBlockEntity blockEntity, ItemStack mainHand, Player player,
                                      BlockFace facing, World world, BlockPosKey posKey) {
        // Read the stacking switch in real time to avoid using a stale cached value after /fd reload switches interaction-mode
        // (consistent with the checks in useOnBlock and the hopper controller).
        if (!plugin.isCuttingBoardStackingEnabled() || player.isSneaking() || toolMatcher.isTool(mainHand)) {
            return false;
        }
        if (mainHand == null || mainHand.getType().isAir()) {
            return false;
        }

        // Atomic: stored-read → setStoredItem must not interleave with another viewer's stack/take/cut.
        int toMove;
        synchronized (blockEntity) {
            ItemStack stored = blockEntity.getStoredItem();
            int stackLimit = Math.min(getBoardStackLimit(stored), getBoardStackLimit(mainHand));
            if (stored == null || !mainHand.isSimilar(stored) || stored.getAmount() >= stackLimit) {
                return false;
            }
            int space = stackLimit - stored.getAmount();
            toMove = Math.min(space, mainHand.getAmount());
            if (toMove <= 0) {
                return false;
            }
            int previousAmount = stored.getAmount();
            stored.setAmount(previousAmount + toMove);
            // Store, then consume — atomically, mirroring tryPlaceOnEmptyBoardLocked. If the commit throws
            // (e.g. a Folia region thread-check) roll the stored amount back and skip the consume below, so
            // the player keeps the item and the board is unchanged: no dupe, no loss.
            try {
                blockEntity.setStoredItem(stored, world, posKey, facing);
            } catch (RuntimeException | LinkageError t) {
                stored.setAmount(previousAmount);
                try {
                    blockEntity.setStoredItem(stored, world, posKey, facing);
                } catch (RuntimeException | LinkageError ignored) {
                    // display restore is best-effort; the rollback has already restored the item stack
                }
                return false;
            }
        }
        saveBlockEntityData(world, posKey);
        if (player.getGameMode() != GameMode.CREATIVE) {
            int remaining = mainHand.getAmount() - toMove;
            if (remaining <= 0) {
                player.getInventory().setItemInMainHand(null);
            } else {
                mainHand.setAmount(remaining);
                player.getInventory().setItemInMainHand(mainHand);
            }
        }
        SoundUtils.play(player.getWorld(), player.getLocation(), null, Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
        player.swingMainHand();
        return true;
    }

    private int getBoardStackLimit(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 1;
        }
        return Math.max(1, Math.min(maxStackAmount, item.getMaxStackSize()));
    }

    @Override
    public boolean hasAnalogOutputSignal(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(Object thisBlock, Object[] args) {
        // args[1] = Level, args[2] = BlockPos. Scale comparator strength by the board's stack limit:
        // a single non-stackable tool yields 15; one item from a 64-item stack yields 1.
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return 0;
        }
        CuttingBoardBlockEntity entity = getBlockEntity(world, pos);
        if (entity == null) {
            return 0;
        }
        ItemStack stored = entity.getStoredItem();
        if (stored == null || stored.getType().isAir() || stored.getAmount() <= 0) {
            return 0;
        }
        float proportions = (float) stored.getAmount() / (float) getBoardStackLimit(stored);
        return (int) Math.floor(proportions * 14.0f) + 1;
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        // Managed by the interaction logic and block entity state.
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
        CuttingBoardBlockEntity entity = getBlockEntity(world, posKey);
        if (entity == null) {
            return;
        }
        saveBlockEntityData(world, posKey);
        entity.removeDisplayEntity();
        removeBlockEntity(world, posKey, false);
    }

    @Override
    public Object getContainer(Object thisBlock, Object[] args) {
        if (plugin == null || !plugin.isCuttingBoardHopperInteractionsEnabled()) {
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
        return blockEntity.controller.let(CuttingBoardBlockEntityController.class, this.controllerId, controller -> {
            CuttingBoardBlockEntity entity = getBlockEntity(world, pos);
            if (entity == null) {
                // Native hoppers reach the board through this injected container path without going through
                // loadBlockEntity, so apply parked controller data here too — otherwise a chunk-cache-dropped
                // board reads an empty container and the hopper pulls nothing until a save/interaction flushes.
                controller.loadPendingDataIfReady();
                entity = getBlockEntity(world, pos);
            }
            if (entity != null) {
                controller.refreshFromEntity(entity);
            }
            return controller.container();
        });
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        try {
            String facingValue = facingProperty != null ? state.get(facingProperty).toString() : "north";
            return CustomBlockUtils.parseFacing(facingValue);
        } catch (Exception e) {
            return BlockFace.NORTH;
        }
    }

    private static BlockFace getStoredBlockFacing(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return BlockFace.NORTH;
        }

        try {
            return CustomBlockUtils.getFacing(posKey.toLocation(world).getBlock());
        } catch (Exception ignored) {
            return BlockFace.NORTH;
        }
    }

    public boolean isTool(ItemStack item) {
        return toolMatcher.isTool(item);
    }

    public boolean tryDispenserCut(World world, BlockPosKey posKey, BlockFace facing, ItemStack tool) {
        return cutter.tryDispenserCut(world, posKey, facing, tool);
    }

    private void removeStoredData(World world, BlockPosKey posKey) {
        saveBlockEntityData(world, posKey);
    }

    private void storeItemInBoard(World world, BlockPosKey posKey, BlockFace facing, CuttingBoardBlockEntity blockEntity, ItemStack itemToPlace, boolean carveTool) {
        blockEntity.setItem(itemToPlace, world, posKey, facing, carveTool);
        saveBlockEntityData(world, posKey);
    }

    private static List<String> getStringList(Map<String, Object> arguments, String key) {
        return BehaviorArgParser.getStringList(arguments, key);
    }

    private void debug(String message) {
        if (plugin != null && plugin.isDebugEnabled("interact")) {
            plugin.getLogger().info(I18n.formatConsole("debug.cutting_board", "message", message));
        }
    }

    private String formatItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "air";
        }
        String customId = ItemUtils.getCustomItemId(item);
        return customId != null ? customId + " x" + item.getAmount() : item.getType().name() + " x" + item.getAmount();
    }
}
