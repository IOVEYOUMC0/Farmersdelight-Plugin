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
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Map<UUID, Map<BlockPosKey, TextDisplay>> worldProgressDisplays = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, Set<UUID>> displayVisibleToPlayers = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, Long> displayVisibilityLastCheckTick = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, String> progressDisplayTextCache = new ConcurrentHashMap<>();
    private static final int VISIBILITY_CHECK_INTERVAL_TICKS = 100;
    private static final double PROGRESS_DISPLAY_VISIBILITY_DISTANCE_SQUARED = 100.0D;
    private static final double PROGRESS_DISPLAY_LOOK_DOT_THRESHOLD = 0.95D;
    private static final Map<BlockPosKey, ItemStack> cookingRecipeItems = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, Long> recentPlacements = new ConcurrentHashMap<>();
    private static final long PLACE_INTERACTION_COOLDOWN_MS = 1000L;

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
            String customDataKey
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
        CookingPotBlockEntity entity = worldEntities.computeIfAbsent(posKey, key -> new CookingPotBlockEntity(key, world));
        entity.setWorld(world);
        return entity;
    }

    public static CookingPotBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) return null;
        Block block = location.getBlock();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null) return null;
        var behavior = state.behavior();
        if (behavior instanceof CookingPotBlockBehavior potBehavior) {
            return potBehavior;
        }
        return null;
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
            worldEntities.remove(posKey);
        }

        TrayManager trayManager = null;
        if (plugin != null) {
            trayManager = plugin.getTrayManager();
        }
        if (trayManager != null) {
            trayManager.removeTrayIfAutoPlaced(world, posKey.toBlockPos());
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
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.remove(worldId);
        if (displays != null) {
            for (Map.Entry<BlockPosKey, TextDisplay> entry : displays.entrySet()) {
                BlockPosKey posKey = entry.getKey();
                TextDisplay display = entry.getValue();
                if (removeDisplayEntities && display != null && display.isValid()) {
                    display.remove();
                }
                displayVisibleToPlayers.remove(posKey);
                displayVisibilityLastCheckTick.remove(posKey);
                progressDisplayTextCache.remove(posKey);
                cookingRecipeItems.remove(posKey);
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
        progressDisplayTextCache.clear();
        cookingRecipeItems.clear();
        recentPlacements.clear();
    }

    public static void markRecentlyPlaced(Location location) {
        if (location == null) {
            return;
        }
        long now = System.currentTimeMillis();
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
        if (world == null || posKey == null || progressPercent <= 0) {
            removeProgressDisplay(world, posKey);
            return;
        }

        String text;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.isShowRecipeNameInProgressDisplay()) {
            ItemStack recipeItem = cookingRecipeItems.get(posKey);
            if (recipeItem != null) {
                String recipeName = com.huidu.farmersdelight.util.ItemUtils.getDisplayName(recipeItem);
                text = recipeName + " " + progressPercent + "%";
            } else {
                text = progressPercent + "%";
            }
        } else {
            text = progressPercent + "%";
        }

        String previousText = progressDisplayTextCache.get(posKey);
        if (text.equals(previousText)) {
            return;
        }

        TextDisplay display = getOrCreateProgressDisplay(world, posKey);
        if (display == null) {
            return;
        }

        progressDisplayTextCache.put(posKey, text);
        display.text(net.kyori.adventure.text.Component.text(text));
        updateDisplayVisibility(world, posKey, display);
    }

    public static void setCookingRecipeItem(BlockPosKey posKey, ItemStack recipeItem) {
        if (recipeItem != null && !recipeItem.getType().isAir()) {
            cookingRecipeItems.put(posKey, recipeItem);
        } else {
            cookingRecipeItems.remove(posKey);
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

        displayVisibleToPlayers.remove(posKey);
        displayVisibilityLastCheckTick.remove(posKey);
        progressDisplayTextCache.remove(posKey);
        cookingRecipeItems.remove(posKey);
    }

    private static TextDisplay getOrCreateProgressDisplay(World world, BlockPosKey posKey) {
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.computeIfAbsent(
                world.getUID(), ignored -> new ConcurrentHashMap<>());

        TextDisplay existing = displays.get(posKey);
        if (existing != null && existing.isValid()) {
            return existing;
        }

        Location location = posKey.toLocation(world).clone().add(0.5, 1.2, 0.5);
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
                    new Vector3f(0.5f, 0.5f, 0.5f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));
        });
        displays.put(posKey, created);
        return created;
    }

    private static void updateDisplayVisibility(World world, BlockPosKey posKey, TextDisplay display) {
        if (display == null || !display.isValid()) return;

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.scheduler().isFolia()) {
            display.setVisibleByDefault(true);
            displayVisibleToPlayers.remove(posKey);
            return;
        }

        long currentTick = System.nanoTime() / 50_000_000L;
        Long lastCheckTick = displayVisibilityLastCheckTick.get(posKey);
        if (lastCheckTick != null && currentTick - lastCheckTick < VISIBILITY_CHECK_INTERVAL_TICKS) {
            return;
        }
        displayVisibilityLastCheckTick.put(posKey, currentTick);

        Location potLoc = posKey.toLocation(world).add(0.5, 0, 0.5);
        Set<UUID> nearbyUUIDs = new HashSet<>();
        Set<UUID> visibleTo = displayVisibleToPlayers.computeIfAbsent(posKey, k -> ConcurrentHashMap.newKeySet());

        for (Player p : world.getPlayers()) {
            if (!p.isOnline() || p.getLocation().distanceSquared(potLoc) > PROGRESS_DISPLAY_VISIBILITY_DISTANCE_SQUARED) {
                continue;
            }

            nearbyUUIDs.add(p.getUniqueId());

            Location eyeLoc = p.getEyeLocation();
            double dx = potLoc.getX() - eyeLoc.getX();
            double dy = potLoc.getY() - eyeLoc.getY();
            double dz = potLoc.getZ() - eyeLoc.getZ();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean isLooking = false;
            if (length > 0.0001D) {
                org.bukkit.util.Vector lookDir = eyeLoc.getDirection();
                double dot = lookDir.getX() * (dx / length)
                        + lookDir.getY() * (dy / length)
                        + lookDir.getZ() * (dz / length);
                isLooking = dot > PROGRESS_DISPLAY_LOOK_DOT_THRESHOLD;
            }

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

        markBlockEntityDirty(world, posKey);
    }

    public static void markBlockEntityDirty(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;

        CEWorld ceWorld = BukkitWorldManager.instance().getWorld(world.getUID());
        if (ceWorld != null) {
            ceWorld.blockEntityChanged(posKey.toBlockPos());
        }
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

        for (int i = 0; i < INVENTORY_SIZE; i++) {
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
        if (world == null || posKey == null) {
            return false;
        }

        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return false;
        }

        try {
            return state.owner().keyOptional()
                    .map(Object::toString)
                    .filter(Constants.BLOCK_COOKING_POT::equals)
                    .isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean hasCookingPotBehavior(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        return com.huidu.farmersdelight.util.CustomBlockUtils.hasBehavior(block, CookingPotBlockBehavior.class);
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
                    customDataKey
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

        CookingPotBlockEntity blockEntity = worldEntities.computeIfAbsent(posKey, key -> new CookingPotBlockEntity(key, world));
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

        ItemStack meal = blockEntity.useHeldContainerOnPendingMeal(world, heldItem);
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

            int filledSlots = 0;
            for (int i = 0; i < SLOT_MEAL_DISPLAY; i++) {
                ItemStack item = entity.getInventory()[i];
                if (item != null && !item.getType().isAir()) {
                    filledSlots++;
                }
            }

            if (entity.getMealDisplayItem() != null) {
                filledSlots++;
            }

            return (filledSlots * 15) / (SLOT_MEAL_DISPLAY + 1);
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

        CEWorld ceWorld = BukkitWorldManager.instance().getWorld(world.getUID());
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
}

