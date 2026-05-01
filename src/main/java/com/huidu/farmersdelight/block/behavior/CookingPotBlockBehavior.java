package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.manager.TrayManager;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class CookingPotBlockBehavior extends BlockBehavior {

    public static final int SLOT_MEAL_DISPLAY = 6;
    public static final int SLOT_CONTAINER = 7;
    public static final int SLOT_OUTPUT = 8;
    public static final int INVENTORY_SIZE = 9;

    private static final Map<UUID, Map<BlockPosKey, CookingPotBlockEntity>> worldBlockEntities = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BlockPosKey, TextDisplay>> worldProgressDisplays = new ConcurrentHashMap<>();
    private static final Map<BlockPosKey, Long> recentPlacements = new ConcurrentHashMap<>();
    private static final String BLOCK_TYPE = "cooking_pot";
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

    private CookingPotBlockBehavior(
            CustomBlock block,
            String permission,
            boolean openWhileSneaking,
            boolean placeTrayOnOpen,
            String boilSound,
            String soupBoilSound,
            Double soundChance,
            Double soundVolume,
            Double soundPitchMin,
            Double soundPitchMax
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
    }

    public static CookingPotBlockEntity getBlockEntity(World world, BlockPos pos) {
        return getBlockEntity(world, new BlockPosKey(pos));
    }

    public static CookingPotBlockEntity getBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return null;
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return null;
        return worldEntities.get(posKey);
    }

    public static CookingPotBlockEntity getBlockEntity(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return getBlockEntity(location.getWorld(), new BlockPosKey(location));
    }

    public static CookingPotBlockEntity getOrCreateBlockEntity(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        BlockPosKey posKey = new BlockPosKey(location);
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                location.getWorld().getUID(), k -> new ConcurrentHashMap<>());
        return worldEntities.computeIfAbsent(posKey, CookingPotBlockEntity::new);
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
        if (worldEntities != null) {
            return Map.copyOf(worldEntities);
        }
        return Map.of();
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

        BlockStorageManager storage = null;
        if (plugin != null) {
            storage = plugin.getBlockStorageManager();
        }
        if (removeStoredData && storage != null) {
            storage.removeBlockData(posKey.toLocation(world));
        }
    }

    public static void cleanupWorld(UUID worldId) {
        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.remove(worldId);
        if (worldEntities != null) {
            worldEntities.clear();
        }
        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.remove(worldId);
        if (displays != null) {
            for (TextDisplay display : displays.values()) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            displays.clear();
        }
    }

    public static void cleanupAll() {
        for (Map<BlockPosKey, CookingPotBlockEntity> worldEntities : worldBlockEntities.values()) {
            worldEntities.clear();
        }
        worldBlockEntities.clear();
        for (Map<BlockPosKey, TextDisplay> displays : worldProgressDisplays.values()) {
            for (TextDisplay display : displays.values()) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
            }
            displays.clear();
        }
        worldProgressDisplays.clear();
        recentPlacements.clear();
    }

    public static void markRecentlyPlaced(Location location) {
        if (location == null) {
            return;
        }

        BlockPosKey posKey = new BlockPosKey(location);
        long now = System.currentTimeMillis();
        recentPlacements.put(posKey, now);
        recentPlacements.entrySet().removeIf(entry -> now - entry.getValue() > PLACE_INTERACTION_COOLDOWN_MS);
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

        TextDisplay display = getOrCreateProgressDisplay(world, posKey);
        if (display == null) {
            return;
        }

        display.text(net.kyori.adventure.text.Component.text(progressPercent + "%"));
    }

    public static void removeProgressDisplay(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return;
        }

        Map<BlockPosKey, TextDisplay> displays = worldProgressDisplays.get(world.getUID());
        if (displays == null) {
            return;
        }

        TextDisplay display = displays.remove(posKey);
        if (display != null && display.isValid()) {
            display.remove();
        }
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

    public static void saveAllData() {
        BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
        if (storage == null) return;

        for (Map.Entry<UUID, Map<BlockPosKey, CookingPotBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (Map.Entry<BlockPosKey, CookingPotBlockEntity> posEntry : worldEntry.getValue().entrySet()) {
                BlockPosKey posKey = posEntry.getKey();
                saveBlockEntityData(world, posKey);
            }
        }
        storage.saveAll();
    }

    public static void saveBlockEntityData(World world, BlockPos pos) {
        saveBlockEntityData(world, new BlockPosKey(pos));
    }

    public static void saveBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (!isCookingPotBlock(world, posKey)) {
            removeBlockEntity(world, posKey, true);
            return;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) return;

        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return;

        CookingPotBlockEntity entity = worldEntities.get(posKey);
        if (entity == null) return;

        Map<String, Object> data = new HashMap<>();
        synchronized (entity.getLock()) {
            ItemStack[] inventory = entity.getInventoryInternal();
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                ItemStack item = inventory[i];
                if (item != null && !item.getType().isAir()) {
                    data.put("slot_" + i, item.clone());
                }
            }
        }
        data.put("cookingProgress", entity.getCookingProgress());
        data.put("cookingDuration", entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            data.put("mealContainer", mealContainer);
        }

        Location loc = posKey.toLocation(world);
        storage.saveBlockData(loc, BLOCK_TYPE, data);
    }

    public static void loadBlockEntity(World world, BlockPos pos) {
        loadBlockEntity(world, new BlockPosKey(pos));
    }

    public static void loadBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) return;

        Location loc = posKey.toLocation(world);
        if (!isCookingPotBlock(world, posKey)) {
            storage.removeBlockData(loc);
            removeBlockEntity(world, posKey, false);
            return;
        }

        Map<String, Object> data = storage.loadBlockData(loc, BLOCK_TYPE);
        if (data == null) return;

        CookingPotBlockEntity entity = new CookingPotBlockEntity(posKey);
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            Object item = data.get("slot_" + i);
            if (item instanceof ItemStack itemStack) {
                entity.setInventorySlot(i, itemStack);
            }
        }

        if (data.get("cookingProgress") instanceof Integer progress) {
            entity.setCookingProgress(progress);
        }
        if (data.get("cookingDuration") instanceof Integer duration) {
            entity.setCookingDuration(duration);
        }
        if (data.get("mealContainer") instanceof ItemStack container) {
            entity.setMealContainer(container);
        }

        Map<BlockPosKey, CookingPotBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        worldEntities.put(posKey, entity);
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
                    .filter(id -> id.contains("cooking_pot"))
                    .isPresent();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static final BlockBehaviorFactory<CookingPotBlockBehavior> FACTORY = new BlockBehaviorFactory<CookingPotBlockBehavior>() {
        @Override
        public CookingPotBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            String permission = getString(arguments, "permission", "farmersdelight.use.cooking_pot");
            boolean openWhileSneaking = getBoolean(arguments, "open-while-sneaking", false);
            boolean placeTrayOnOpen = getBoolean(arguments, "place-tray-on-open", true);
            String boilSound = getNullableString(arguments, "boil-sound");
            String soupBoilSound = getNullableString(arguments, "soup-boil-sound");
            Double soundChance = getNullableDouble(arguments, "sound-chance");
            Double soundVolume = getNullableDouble(arguments, "sound-volume");
            Double soundPitchMin = getNullableDouble(arguments, "sound-pitch-min");
            Double soundPitchMax = getNullableDouble(arguments, "sound-pitch-max");
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
                    soundPitchMax
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

        CookingPotBlockEntity blockEntity = worldEntities.computeIfAbsent(posKey, CookingPotBlockEntity::new);
        
        TickManager tickManager = FarmersDelightPlugin.getInstance().getTickManager();
        if (tickManager != null) {
            tickManager.markActive(world, posKey, TickManager.BlockType.COOKING_POT);
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
    public int getAnalogOutputSignal(Object thisBlock, Object[] args) throws Exception {
        if (args.length >= 3 && args[1] instanceof net.momirealms.craftengine.core.world.World ceWorld && args[2] instanceof BlockPos pos) {
            World world = Bukkit.getWorld(ceWorld.uuid());
            if (world == null) return 0;
            
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
    public void tick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        // Managed by TickManager.
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
