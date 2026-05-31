package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.util.*;
import fr.ateastudio.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
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
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CuttingBoardBlockBehavior extends BlockBehavior implements EntityBlock, WorldlyContainerHolder {

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

    private static final Map<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldBlockEntities = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPosKey, Long>> recentManualInsertions = new ConcurrentHashMap<>();
    private static final long MANUAL_INSERT_GUARD_MILLIS = 250L;

    private final Property<?> facingProperty;
    private final List<Key> toolTags;
    private final List<Key> toolItems;
    private final String knifeSound;
    private final boolean enableStacking;
    private final int maxStackAmount;
    private final String customDataKey;
    private int controllerId;

    private CuttingBoardBlockBehavior(BlockDefinition block, Property<?> facingProperty, List<Key> toolTags, List<Key> toolItems, String knifeSound, boolean enableStacking, int maxStackAmount, String customDataKey) {
        super(block);
        this.facingProperty = facingProperty;
        this.toolTags = toolTags;
        this.toolItems = toolItems;
        this.knifeSound = knifeSound;
        this.enableStacking = enableStacking;
        this.maxStackAmount = maxStackAmount;
        this.customDataKey = customDataKey;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new CuttingBoardBlockEntityController(blockEntity, this);
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

    public static CuttingBoardBlockEntity putBlockEntity(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        if (world == null || posKey == null || entity == null) {
            return entity;
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        entity.setWorld(world);
        worldEntities.put(posKey, entity);
        return entity;
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
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.remove(worldId);
        if (worldEntities != null) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                if (removeDisplayEntities) {
                    entity.removeDisplayEntity();
                }
            }
            worldEntities.clear();
        }
    }

    public static void cleanupAll() {
        cleanupAll(true);
    }

    public static void cleanupAll(boolean removeDisplayEntities) {
        for (Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities : worldBlockEntities.values()) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                if (removeDisplayEntities) {
                    entity.removeDisplayEntity();
                }
            }
            worldEntities.clear();
        }
        worldBlockEntities.clear();
        recentManualInsertions.clear();
    }

    public static void markManualInsertion(World world, BlockPosKey posKey, UUID playerId) {
        if (world == null || posKey == null || playerId == null) {
            return;
        }
        recentManualInsertions
                .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(posKey, System.currentTimeMillis());
    }

    private static boolean consumeManualInsertionGuard(UUID playerId, BlockPosKey posKey) {
        if (playerId == null || posKey == null) {
            return false;
        }
        Map<BlockPosKey, Long> guardedPositions = recentManualInsertions.get(playerId);
        if (guardedPositions == null) {
            return false;
        }

        Long timestamp = guardedPositions.remove(posKey);
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

    public static void saveBlockEntityData(World world, BlockPos pos) {
        saveBlockEntityData(world, new BlockPosKey(pos));
    }

    public static void saveBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;

        if (!isCuttingBoardBlock(world, posKey)) {
            removeBlockEntity(world, posKey, true);
            return;
        }

        CuttingBoardBlockEntity entity = getBlockEntity(world, posKey);
        if (notifyControllerChanged(world, posKey, entity)) {
            return;
        }

        markBlockEntityDirty(world, posKey);
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

    public static void loadBlockEntity(World world, BlockPos pos) {
        loadBlockEntity(world, new BlockPosKey(pos));
    }

    public static void loadBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null || !isCuttingBoardBlock(world, posKey)) return;
        CuttingBoardBlockEntity entity = worldBlockEntities.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(posKey, key -> new CuttingBoardBlockEntity(key, world));
        entity.setWorld(world);
    }

    public static void migrateLegacyBlockData(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null || !isCuttingBoardBlock(world, posKey)) return;

        CuttingBoardBlockEntity entity = new CuttingBoardBlockEntity(posKey, world);
        if (data.get("storedItem") instanceof ItemStack storedItem && storedItem != null && !storedItem.getType().isAir()) {
            boolean itemCarved = data.get("itemCarved") instanceof Boolean carved && carved;
            entity.setItem(storedItem, world, posKey, getStoredBlockFacing(world, posKey), itemCarved);
        }
        putBlockEntity(world, posKey, entity);
        saveBlockEntityData(world, posKey);
    }

    public static boolean isCuttingBoardBlock(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }
        var block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        return CustomBlockUtils.hasBehavior(block, CuttingBoardBlockBehavior.class)
                || CustomBlockUtils.hasId(block, Constants.BLOCK_CUTTING_BOARD);
    }

    public static final BlockBehaviorFactory<CuttingBoardBlockBehavior> FACTORY = new BlockBehaviorFactory<CuttingBoardBlockBehavior>() {
        @Override
        public CuttingBoardBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            Property<?> facingProperty = block.getProperty("facing");

            List<String> toolTagStrings = getStringList(arguments, "tool-tags");
            if (toolTagStrings.isEmpty()) {
                toolTagStrings = List.of(Constants.TAG_KNIVES, Constants.TAG_AXES, Constants.TAG_PICKAXES);
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

            String knifeSound = getArgumentString(arguments, "knife-sound", Constants.SOUND_CUTTING_BOARD_KNIFE);
            boolean enableStacking = FarmersDelightPlugin.getInstance().isCuttingBoardStackingEnabled();
            int maxStackAmount = getIntValue(arguments, "max-stack-amount", 64);
            String customDataKey = getArgumentString(arguments, "data-key", "farmersdelight:cutting_board");
            return new CuttingBoardBlockBehavior(block, facingProperty, toolTags, toolItems, knifeSound, enableStacking, maxStackAmount, customDataKey);
        }
    };

    public String getKnifeSound() {
        return knifeSound;
    }

    public boolean isStackingEnabled() {
        return enableStacking;
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

    private static String getArgumentString(Map<String, Object> arguments, String key, String defaultValue) {
        if (arguments == null) {
            return defaultValue;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text;
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

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        if (consumeManualInsertionGuard(bukkitPlayer.getUniqueId(), posKey)) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (!bukkitPlayer.hasPermission("farmersdelight.use.cutting_board")) {
            bukkitPlayer.sendActionBar(I18n.getComponent("general.no_permission", bukkitPlayer));
            return InteractionResult.FAIL;
        }

        World world = bukkitPlayer.getWorld();
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        if (!WorldGuardCompat.canUse(bukkitPlayer, block) || !WorldGuardCompat.canBuild(bukkitPlayer, block)) {
            return InteractionResult.PASS;
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        CuttingBoardBlockEntity blockEntity = worldEntities.get(posKey);
        if (blockEntity == null) {
            loadBlockEntity(world, posKey);
            blockEntity = worldEntities.get(posKey);
        }
        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(posKey, world);
            worldEntities.put(posKey, blockEntity);
        }
        blockEntity.setWorld(world);

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        ItemStack offHand = bukkitPlayer.getInventory().getItemInOffHand();
        boolean allowOffhandInteractions = FarmersDelightPlugin.getInstance().isCuttingBoardOffhandInteractionsAllowed();
        boolean stackingEnabled = FarmersDelightPlugin.getInstance().isCuttingBoardStackingEnabled();
        BlockFace facing = getFacing(state);
        debug("useOnBlock mode=" + FarmersDelightPlugin.getInstance().getCuttingBoardInteractionMode()
                + ", hasItem=" + blockEntity.hasItem()
                + ", main=" + formatItem(mainHand)
                + ", off=" + formatItem(offHand)
                + ", allowOffhand=" + allowOffhandInteractions
                + ", stacking=" + stackingEnabled
                + ", pos=" + posKey);

        if (blockEntity.hasItem()) {
            ItemStack tool = findMatchingTool(blockEntity, mainHand, offHand, allowOffhandInteractions);
            boolean toolIsOffhand = allowOffhandInteractions && tool != null && tool == offHand;
            if (tool != null) {
                boolean result = processCutting(blockEntity, tool, bukkitPlayer, facing, world, posKey, toolIsOffhand);
                if (result) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }

            if (tryStackOntoBoard(blockEntity, mainHand, bukkitPlayer, facing, world, posKey)) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }

            if (isTool(mainHand) || (allowOffhandInteractions && isTool(offHand))) {
                boolean hasRecipe = FarmersDelightPlugin.getInstance().getCuttingBoardRecipes()
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
            boolean mainHandTool = !mainHandEmpty && isTool(mainHand);
            boolean offHandTool = !offHandEmpty && isTool(offHand);

            if (allowOffhandInteractions && !offHandEmpty && (mainHandEmpty || mainHandTool) && !offHandTool) {
                if (bukkitPlayer.isSneaking() && isTool(offHand)) {
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
                    || !FarmersDelightPlugin.getInstance().getCuttingBoardRecipes().hasAnyRecipeFor(offHand)) {
                bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.no_recipe", bukkitPlayer));
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (blockEntity.hasItem() && mainHand.getType().isAir()) {
            ItemStack storedItem = blockEntity.getStoredItem();
            blockEntity.clearItem();
            removeStoredData(world, posKey);

            if (storedItem != null && !storedItem.getType().isAir() && bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                Map<Integer, ItemStack> leftovers = bukkitPlayer.getInventory().addItem(storedItem);
                if (!leftovers.isEmpty()) {
                    Location dropLocation = posKey.toLocation(world).add(0.5, 0.2, 0.5);
                    leftovers.values().forEach(item -> world.dropItemNaturally(dropLocation, item));
                }
            }

            bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_WOOD_HIT, Constants.CUTTING_BOARD_FAIL_VOLUME, Constants.CUTTING_BOARD_FAIL_PITCH);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    private boolean tryPlaceOnEmptyBoard(ItemStack sourceItem, boolean offhand, Player player,
                                         World world, BlockPosKey posKey, BlockFace facing,
                                         CuttingBoardBlockEntity blockEntity) {
        if (sourceItem == null || sourceItem.getType().isAir()) {
            return false;
        }

        ItemStack itemToPlace = sourceItem.clone();
        int stackLimit = getBoardStackLimit(sourceItem);
        int amountToMove = itemToPlace.getAmount();
        if (enableStacking) {
            amountToMove = Math.min(amountToMove, stackLimit);
        } else {
            amountToMove = 1;
        }
        itemToPlace.setAmount(amountToMove);

        boolean carveTool = !offhand && player.isSneaking() && isTool(sourceItem);
        storeItemInBoard(world, posKey, facing, blockEntity, itemToPlace, carveTool);
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
        return true;
    }

    private boolean tryStackOntoBoard(CuttingBoardBlockEntity blockEntity, ItemStack mainHand, Player player,
                                      BlockFace facing, World world, BlockPosKey posKey) {
        if (!enableStacking || player.isSneaking() || isTool(mainHand)) {
            return false;
        }
        if (mainHand == null || mainHand.getType().isAir()) {
            return false;
        }

        ItemStack stored = blockEntity.getStoredItem();
        int stackLimit = Math.min(getBoardStackLimit(stored), getBoardStackLimit(mainHand));
        if (stored == null || !mainHand.isSimilar(stored) || stored.getAmount() >= stackLimit) {
            return false;
        }

        int space = stackLimit - stored.getAmount();
        int toMove = Math.min(space, mainHand.getAmount());
        if (toMove <= 0) {
            return false;
        }

        stored.setAmount(stored.getAmount() + toMove);
        blockEntity.setStoredItem(stored, world, posKey, facing);
        saveBlockEntityData(world, posKey);
        if (player.getGameMode() != GameMode.CREATIVE) {
            mainHand.setAmount(mainHand.getAmount() - toMove);
        }
        SoundUtils.play(player.getWorld(), player.getLocation(), null, Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
        return true;
    }

    private int getBoardStackLimit(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 1;
        }
        return Math.max(1, Math.min(maxStackAmount, item.getMaxStackSize()));
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        // Managed by interaction and block entity state.
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
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
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
            if (entity != null) {
                controller.refreshFromEntity(entity);
            }
            return controller.container();
        });
    }

    private ItemStack findMatchingTool(CuttingBoardBlockEntity blockEntity, ItemStack mainHand, ItemStack offHand,
                                       boolean allowOffhandInteractions) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }

        if (matchesAnyRecipe(storedItem, mainHand)) {
            return mainHand;
        }
        if (allowOffhandInteractions && matchesAnyRecipe(storedItem, offHand)) {
            return offHand;
        }
        return null;
    }

    private boolean matchesAnyRecipe(ItemStack storedItem, ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        ItemStack singleItem = storedItem.clone();
        singleItem.setAmount(1);
        return FarmersDelightPlugin.getInstance().getCuttingBoardRecipes().matchRecipe(singleItem, tool) != null;
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
        if (item == null || item.getType().isAir()) return false;

        if (isKnifeTool(item) || isAxeTool(item) || isPickaxeTool(item) || isConfiguredToolItem(item)) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);

        if (customId != null) {
            Set<Key> itemTags = ItemUtils.getCustomItemTags(Key.of(customId));
            for (Key tag : toolTags) {
                if (itemTags.contains(tag)) return true;
            }
        }

        for (Key toolTag : toolTags) {
            var vanillaItems = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .vanillaItemIdsByTag(toolTag);
            for (var vanillaItem : vanillaItems) {
                if (vanillaItem.toString().equals("minecraft:" + item.getType().name().toLowerCase()))
                    return true;
            }
        }

        return false;
    }

    private boolean isKnifeTool(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        return FarmersDelightPlugin.getInstance().isKnifeItemId(customId);
    }

    private boolean isAxeTool(ItemStack item) {
        return item.getType().name().endsWith("_AXE");
    }

    private boolean isPickaxeTool(ItemStack item) {
        return item.getType().name().endsWith("_PICKAXE");
    }

    private boolean isConfiguredToolItem(ItemStack item) {
        return toolItems.stream().anyMatch(toolItem -> ItemUtils.matchesItemId(item, toolItem));
    }

    private boolean processCutting(CuttingBoardBlockEntity blockEntity, ItemStack tool, Player player, 
                                    BlockFace facing, World world, BlockPosKey posKey, boolean toolIsOffhand) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null) return false;

        ItemStack recipeInput = storedItem.clone();
        recipeInput.setAmount(1);
        CuttingBoardRecipe recipe = FarmersDelightPlugin.getInstance().getCuttingBoardRecipes()
                .matchRecipe(recipeInput, tool);

        if (recipe == null) return false;

        Location location = player.getLocation();

        int fortuneLevel = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE);

        ItemStack firstResult = null;
        boolean hasPossibleResult = false;
        for (CuttingBoardRecipe.ResultEntry resultEntry : recipe.getResults()) {
            ItemStack configuredResult = resultEntry.item();
            if (configuredResult != null && !configuredResult.getType().isAir() && configuredResult.getAmount() > 0) {
                hasPossibleResult = true;
            }
            if (resultEntry.chance() < 1.0d && ThreadLocalRandom.current().nextDouble() > resultEntry.chance()) {
                continue;
            }

            ItemStack result = resultEntry.item().clone();

            if (fortuneLevel > 0 && resultEntry.chance() < 1.0d) {
                double adjustedChance = Math.min(1.0d, resultEntry.chance() + 0.1d * fortuneLevel);
                if (ThreadLocalRandom.current().nextDouble() > adjustedChance) {
                    result.setAmount(Math.max(0, result.getAmount() - 1));
                }
            }

            if (result.getAmount() <= 0 || result.getType().isAir()) {
                continue;
            }
            if (firstResult == null) {
                firstResult = result.clone();
            }
            spawnItemEntity(world, posKey, result, facing);
        }

        if (!hasPossibleResult) {
            debug("recipe=" + recipe.getId()
                    + " matched input=" + formatItem(storedItem)
                    + " tool=" + formatItem(tool)
                    + " but produced no output");
            player.sendActionBar(I18n.getComponent("messages.cutting_board.no_output", player));
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            return true;
        }

        Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                player.getUniqueId(),
                player.getName(),
                "cutting_board",
                firstResult != null ? firstResult : storedItem,
                0.0f
        ));

        playCuttingFeedback(world, posKey, storedItem, recipe);
        if (toolIsOffhand) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            if (tool.getItemMeta() instanceof Damageable damageable) {
                int maxDamage = tool.getType().getMaxDurability();
                int currentDamage = damageable.getDamage();
                if (currentDamage + 1 >= maxDamage) {
                    tool.setAmount(0);
                    player.playSound(location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                } else {
                    damageable.setDamage(currentDamage + 1);
                    tool.setItemMeta(damageable);
                }
            }
        }

        if (storedItem.getAmount() > 1) {
            storedItem.setAmount(storedItem.getAmount() - 1);
            blockEntity.setStoredItem(storedItem, world, posKey, facing);
            saveBlockEntityData(world, posKey);
        } else {
            blockEntity.clearItem();
            removeStoredData(world, posKey);
        }

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "use_cutting_board");
        }

        return true;
    }

    
    private void playCuttingFeedback(World world, BlockPosKey posKey, ItemStack storedItem, CuttingBoardRecipe recipe) {
        if (world == null || posKey == null) {
            return;
        }

        Location effectLocation = posKey.toLocation(world).add(0.5, 0.1, 0.5);
        SoundUtils.play(world, effectLocation, recipe.getSound(), Sound.BLOCK_WOOD_BREAK, 1.0f, 1.0f);
        world.spawnParticle(Particle.ITEM, effectLocation, 5, 0.1, 0.1, 0.1, 0.0, storedItem);
    }

    private void spawnItemEntity(World world, BlockPosKey posKey, ItemStack item, BlockFace facing) {
        if (world == null) return;

        BlockFace ejectFace = getCounterClockWise(facing);
        double offsetX = ejectFace.getModX() * 0.2;
        double offsetZ = ejectFace.getModZ() * 0.2;

        Location location = new Location(world,
                posKey.x() + 0.5 + offsetX,
                posKey.y() + 0.2,
                posKey.z() + 0.5 + offsetZ);

        int remaining = item.getAmount();
        int maxStackSize = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            ItemStack droppedStack = item.clone();
            droppedStack.setAmount(Math.min(remaining, maxStackSize));
            remaining -= droppedStack.getAmount();

            org.bukkit.entity.Item droppedItem = world.dropItem(location, droppedStack);
            droppedItem.setVelocity(new Vector(
                    ejectFace.getModX() * 0.2,
                    0.0,
                    ejectFace.getModZ() * 0.2
            ));
        }
    }

    private BlockFace getCounterClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private void removeStoredData(World world, BlockPosKey posKey) {
        saveBlockEntityData(world, posKey);
    }

    private void storeItemInBoard(World world, BlockPosKey posKey, BlockFace facing, CuttingBoardBlockEntity blockEntity, ItemStack itemToPlace, boolean carveTool) {
        blockEntity.setItem(itemToPlace, world, posKey, facing, carveTool);
        saveBlockEntityData(world, posKey);
    }

    private static List<String> getStringList(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return List.of();
        }
        Object value = arguments.get(key);
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static boolean getBooleanValue(Map<String, Object> arguments, String key, boolean defaultValue) {
        if (arguments == null) return defaultValue;
        Object value = arguments.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private static int getIntValue(Map<String, Object> arguments, String key, int defaultValue) {
        if (arguments == null) return defaultValue;
        Object value = arguments.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private void debug(String message) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
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
