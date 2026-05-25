package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DebugToolsCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int DEFAULT_MAX_PLACE_COUNT = 65536;
    private static final List<String> ACTIONS = List.of("place", "activate", "undo");
    private static final List<String> TARGETS = List.of("cooking_pot", "skillet", "stove", "stove_blocked", "all");
    private static final int UNDO_HISTORY_LIMIT = 8;
    private static final Deque<List<UndoEntry>> UNDO_HISTORY = new ArrayDeque<>();

    private final FarmersDelightPlugin plugin;

    public DebugToolsCommand(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("<red>This command can only be used by players.</red>");
            return;
        }
        if (args.length < 2) {
            sendUsage(player);
            return;
        }

        switch (normalize(args[1])) {
            case "place" -> place(player, args);
            case "activate" -> activate(player, args);
            case "undo" -> undo(player);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return complete(ACTIONS, args[1]);
        }
        if (args.length == 3) {
            return complete(TARGETS, args[2]);
        }
        return List.of();
    }

    private void place(Player player, String[] args) {
        if (args.length < 3) {
            sendUsage(player);
            return;
        }

        beginUndoBatch();
        String target = normalize(args[2]);
        Integer requestedCount = parseOptionalInt(args, 3, 64);
        Integer requestedSpacing = parseOptionalInt(args, 4, 1);
        Integer requestedLayers = parseOptionalInt(args, 5, 1);
        if (requestedCount == null || requestedSpacing == null || requestedLayers == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>Count, spacing, and layers must be whole numbers.</red>"));
            return;
        }

        int maxPlaceCount = getMaxPlaceCount();
        int count = Math.max(1, requestedCount);
        int spacing = clamp(requestedSpacing, 1, 16);
        int layers = Math.max(1, requestedLayers);
        long requestedTotalLong = (long) count * layers;
        int total = requestedTotalLong > maxPlaceCount ? maxPlaceCount : (int) requestedTotalLong;
        Location origin = ManagerSupport.normalize(player.getLocation());
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));

        int placed = 0;
        int activated = 0;
        for (int i = 0; i < total; i++) {
            int layer = i / count;
            int layerIndex = i % count;
            int x = layerIndex % grid;
            int z = layerIndex / grid;
            Location location = new Location(
                    player.getWorld(),
                    origin.getBlockX() + x * spacing,
                    origin.getBlockY() + 1 + layer,
                    origin.getBlockZ() + z * spacing
            );
            UndoEntry undoEntry = captureUndo(location);
            PlaceResult result = placeOne(player, location, target, i);
            if (result.placed()) {
                if (undoEntry != null) {
                    rememberUndo(undoEntry);
                }
                placed++;
            }
            if (result.activated()) {
                activated++;
            }
        }

        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>Debug placed " + placed + "/" + total + " blocks and activated " + activated
                        + " states.</green> <gray>requested=" + requestedCount
                        + ", layers=" + layers + ", max=" + maxPlaceCount + ", spacing=" + spacing + "</gray>"
        ));
    }

    private void activate(Player player, String[] args) {
        String target = args.length >= 3 ? normalizeTarget(args[2]) : "all";
        int activated = 0;

        if (isCookingPotTarget(target)) {
            activated += activateCookingPots(player.getWorld());
        }
        if (isSkilletTarget(target)) {
            activated += activateSkillets(player.getWorld());
        }
        if (isStoveTarget(target)) {
            activated += activateStoves(player.getWorld());
        }

        player.sendMessage(MINI_MESSAGE.deserialize("<green>Debug scanned and filled " + activated + " placed blocks.</green>"));
    }

    private void undo(Player player) {
        List<UndoEntry> batch;
        synchronized (UNDO_HISTORY) {
            batch = UNDO_HISTORY.pollFirst();
        }
        if (batch == null || batch.isEmpty()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>No debug placement to undo.</yellow>"));
            return;
        }

        int restored = 0;
        for (UndoEntry entry : batch) {
            if (entry == null || entry.location() == null || entry.location().getWorld() == null) {
                continue;
            }
            restoreUndoEntry(entry);
            restored++;
        }

        player.sendMessage(MINI_MESSAGE.deserialize("<green>Debug undo restored " + restored + " blocks.</green>"));
    }

    private PlaceResult placeOne(Player player, Location location, String target, int index) {
        boolean placed = false;
        boolean activated = false;
        target = normalizeTarget(target);
        if ("all".equals(target)) {
            target = switch (index % 4) {
                case 0 -> "cooking_pot";
                case 1 -> "skillet";
                case 2 -> "stove";
                default -> "stove_blocked";
            };
        }
        if (isCookingPotTarget(target)) {
            placed = placeDebugHeatSource(location.clone().subtract(0, 1, 0))
                    && placeBlock(location, Constants.BLOCK_COOKING_POT, false);
            if (placed) {
                scheduleActivation(location, "cooking_pot");
                activated = true;
            }
        } else if (isSkilletTarget(target)) {
            placed = placeDebugHeatSource(location.clone().subtract(0, 1, 0))
                    && placeBlock(location, Constants.BLOCK_SKILLET, false);
            if (placed) {
                scheduleActivation(location, "skillet");
                activated = true;
            }
        } else if (isStoveTarget(target) || isBlockedStoveTarget(target)) {
            placed = placeBlock(location, Constants.BLOCK_STOVE, true, true);
            if (placed) {
                if (isBlockedStoveTarget(target)) {
                    placeStoveBlockingBlock(location.clone().add(0, 1, 0));
                }
                scheduleActivation(location, "stove");
                activated = true;
            }
        }
        return new PlaceResult(placed, activated);
    }

    private int activateCookingPots(World world) {
        int activated = 0;
        for (Map.Entry<BlockPosKey, CookingPotBlockEntity> entry : CookingPotBlockBehavior.getBlockEntityEntries(world)) {
            Location location = entry.getKey().toLocation(world);
            if (!isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
                continue;
            }
            if (!hasHeatSourceBelow(location)) {
                continue;
            }
            CookingPotBlockEntity entity = entry.getValue();
            if (!entity.hasStoredContents()) {
                applyCookingPotDebugState(entity, location);
                saveCookingPotData(location, entity, entry.getKey());
            }
            markCookingPotActive(world, entry.getKey());
            activated++;
        }

        for (Map.Entry<String, Map<String, Object>> entry : plugin.getBlockStorageManager().getAllBlockDataInWorld(world).entrySet()) {
            Map<String, Object> data = entry.getValue();
            if (!"cooking_pot".equals(String.valueOf(data.get("_blockType")))) {
                continue;
            }
            Location location = parseLocation(world, data);
            if (location == null || !isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
                continue;
            }
            if (!hasHeatSourceBelow(location)) {
                continue;
            }
            CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
            BlockPosKey posKey = new BlockPosKey(location);
            if (!entity.hasStoredContents()) {
                applyCookingPotDebugState(entity, location);
                saveCookingPotData(location, entity, posKey);
            }
            markCookingPotActive(world, posKey);
            activated++;
        }
        return activated;
    }

    private int activateSkillets(World world) {
        SkilletManager manager = plugin.getSkilletManager();
        if (manager == null) {
            return 0;
        }

        int activated = 0;
        for (Location location : manager.getTrackedLocations(world)) {
            if (!isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
                continue;
            }
            if (!hasHeatSourceBelow(location)) {
                continue;
            }
            activateSkillet(location);
            activated++;
        }

        for (Map.Entry<String, Map<String, Object>> entry : plugin.getBlockStorageManager().getAllBlockDataInWorld(world).entrySet()) {
            Map<String, Object> data = entry.getValue();
            if (!"skillet".equals(String.valueOf(data.get("_blockType")))) {
                continue;
            }
            Location location = parseLocation(world, data);
            if (location == null || !isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
                continue;
            }
            if (!hasHeatSourceBelow(location)) {
                continue;
            }
            activateSkillet(location);
            activated++;
        }
        return activated;
    }

    private int activateStoves(World world) {
        StoveManager manager = plugin.getStoveManager();
        if (manager == null) {
            return 0;
        }

        int activated = 0;
        for (Map.Entry<String, Map<String, Object>> entry : plugin.getBlockStorageManager().getAllBlockDataInWorld(world).entrySet()) {
            Map<String, Object> data = entry.getValue();
            if (!"stove".equals(String.valueOf(data.get("_blockType")))) {
                continue;
            }
            Location location = parseLocation(world, data);
            if (location == null || !isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
                continue;
            }
            if (!isStoveLit(location)) {
                continue;
            }
            activateStove(location);
            activated++;
        }
        return activated;
    }

    private void placeStoveBlockingBlock(Location location) {
        if (location == null || location.getWorld() == null || !canReplace(location.getBlock())) {
            return;
        }
        location.getBlock().setType(Material.STONE, false);
    }

    private UndoEntry captureUndo(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Block block = location.getBlock();
        BlockData data = block.getBlockData().clone();
        return new UndoEntry(location.clone(), data, block.getType(), CustomBlockUtils.getId(block));
    }

    private void rememberUndo(UndoEntry entry) {
        synchronized (UNDO_HISTORY) {
            List<UndoEntry> batch = UNDO_HISTORY.peekFirst();
            if (batch == null) {
                batch = new ArrayList<>();
                UNDO_HISTORY.addFirst(batch);
            }
            batch.add(entry);
        }
    }

    private void beginUndoBatch() {
        synchronized (UNDO_HISTORY) {
            UNDO_HISTORY.addFirst(new ArrayList<>());
            while (UNDO_HISTORY.size() > UNDO_HISTORY_LIMIT) {
                UNDO_HISTORY.removeLast();
            }
        }
    }

    private void restoreUndoEntry(UndoEntry entry) {
        if (entry == null || entry.location() == null || entry.location().getWorld() == null) {
            return;
        }
        Block block = entry.location().getBlock();
        if (entry.blockId() != null && entry.blockId().startsWith("farmersdelight:")) {
            block.setType(entry.vanillaFallback(), false);
            return;
        }
        if (entry.blockData() != null) {
            block.setBlockData(entry.blockData(), false);
            return;
        }
        block.setType(entry.vanillaFallback(), false);
    }

    private void scheduleActivation(Location location, String target) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if ("cooking_pot".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
                activateCookingPot(location);
                verifyCookingPotFilled(location);
            } else if ("skillet".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
                activateSkillet(location);
            } else if ("stove".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
                activateStove(location);
            } else if ("cooking_pot".equals(target)) {
                plugin.getLogger().warning("Debug cooking pot activation skipped because placed block was not recognized at "
                        + ManagerSupport.formatLocation(location) + ", id=" + CustomBlockUtils.getId(location.getBlock()));
            }
        }, 2L);
    }

    private void activateCookingPot(Location location) {
        CookingPotBlockBehavior.markRecentlyPlaced(location);
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        applyCookingPotDebugState(entity, location);
        BlockPosKey posKey = new BlockPosKey(location);
        saveCookingPotData(location, entity, posKey);
        markCookingPotActive(location.getWorld(), posKey);
    }

    private void verifyCookingPotFilled(Location location) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(location);
        if (entity == null) {
            plugin.getLogger().warning("Debug cooking pot activation failed: no block entity at "
                    + ManagerSupport.formatLocation(location));
            return;
        }
        if (!entity.hasStoredContents() || !entity.hasInput()) {
            plugin.getLogger().warning("Debug cooking pot activation failed: entity is empty at "
                    + ManagerSupport.formatLocation(location));
            return;
        }
        if (!entity.canCook()) {
            plugin.getLogger().warning("Debug cooking pot activation filled items but no recipe matched at "
                    + ManagerSupport.formatLocation(location));
        }
    }

    private void applyCookingPotDebugState(CookingPotBlockEntity entity, Location location) {
        if (entity == null) {
            return;
        }

        ItemStack ingredient = item(Constants.ITEM_RICE, 16);
        ItemStack container = item("minecraft:bowl", 16);
        if (ingredient != null) {
            for (int i = 0; i < 4; i++) {
                ItemStack stack = ingredient.clone();
                stack.setAmount(16);
                entity.insertIngredientStack(stack);
            }
        }
        if (container != null) {
            entity.insertContainerStack(container);
            ItemStack required = container.clone();
            required.setAmount(1);
            entity.setMealContainer(required);
        }
        entity.setHasHeatSource(location != null
                && location.getWorld() != null
                && plugin.getHeatSourceConfig().isHeatSource(location.clone().subtract(0, 1, 0).getBlock()));
        entity.setCookingDuration(200);
        entity.setCookingProgress(0);
        entity.canCook();
    }

    private void saveCookingPotData(Location location, CookingPotBlockEntity entity, BlockPosKey posKey) {
        if (location == null || location.getWorld() == null || entity == null || posKey == null) {
            return;
        }
        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) {
            return;
        }

        Map<String, Object> data = new java.util.HashMap<>();
        ItemStack[] inventory = entity.getInventory();
        for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
            ItemStack item = inventory[i];
            if (item != null && !item.getType().isAir()) {
                data.put("slot_" + i, item.clone());
            }
        }
        data.put("cookingProgress", entity.getCookingProgress());
        data.put("cookingDuration", entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            data.put("mealContainer", mealContainer.clone());
        }
        storage.saveBlockData(posKey.toLocation(location.getWorld()), "cooking_pot", data);
    }

    private void activateSkillet(Location location) {
        SkilletManager manager = plugin.getSkilletManager();
        if (manager == null) {
            return;
        }
        ItemStack skillet = item(Constants.ITEM_SKILLET, 1);
        manager.recordPlacedSkillet(location, skillet);

        ItemStack food = item("minecraft:beef", 16);
        if (!manager.canCook(food)) {
            food = item("minecraft:porkchop", 16);
        }
        setSkilletStoredItem(manager, location, food);
    }

    private void activateStove(Location location) {
        StoveManager manager = plugin.getStoveManager();
        if (manager == null || location == null || location.getWorld() == null) {
            return;
        }
        if (!isStoveLit(location)) {
            return;
        }

        Object stove = manager.getOrCreateStove(location);
        ItemStack food = item("minecraft:beef", 1);
        if (!manager.canCook(food)) {
            food = item("minecraft:porkchop", 1);
        }
        if (food == null || food.getType().isAir()) {
            return;
        }

        try {
            CookingRecipe<?> recipe = (CookingRecipe<?>) invoke(manager, "findCampfireRecipe", new Class<?>[]{ItemStack.class}, food);
            int duration = recipe != null && recipe.getCookingTime() > 0 ? recipe.getCookingTime() : 600;
            ItemStack[] items = (ItemStack[]) getField(stove, "items");
            int[] cookingTime = (int[]) getField(stove, "cookingTime");
            int[] maxTime = (int[]) getField(stove, "maxTime");
            BlockFace facing = CustomBlockUtils.getFacing(location.getBlock()).getOppositeFace();
            for (int slot = 0; slot < items.length; slot++) {
                ItemStack stack = food.clone();
                stack.setAmount(1);
                items[slot] = stack;
                cookingTime[slot] = 0;
                maxTime[slot] = duration;
                invoke(manager, "createVisual",
                        new Class<?>[]{Location.class, stove.getClass(), int.class, BlockFace.class},
                        location, stove, slot, facing);
            }
            invoke(manager, "saveStove", new Class<?>[]{Location.class, stove.getClass()}, location, stove);
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning("Failed to activate debug stove at " + location + ": " + e.getMessage());
        }
    }

    private void setSkilletStoredItem(SkilletManager manager, Location location, ItemStack food) {
        if (manager == null || location == null || food == null || food.getType().isAir()) {
            return;
        }

        try {
            Object skillet = invoke(manager, "getOrCreateSkillet", new Class<?>[]{Location.class}, location);
            Object recipe = invoke(manager, "findCampfireRecipe", new Class<?>[]{ItemStack.class}, food);
            setField(skillet, "storedItem", food.clone());
            setField(skillet, "currentRecipe", recipe);
            int duration = Constants.DEFAULT_COOKING_TIME_SKILLET;
            if (recipe instanceof CookingRecipe<?> cookingRecipe) {
                int fireAspect = getIntField(skillet, "fireAspectLevel", 0);
                duration = (int) invoke(manager, "getAdjustedCookingTime", new Class<?>[]{int.class, int.class},
                        cookingRecipe.getCookingTime(), fireAspect);
            }
            setField(skillet, "cookingDuration", duration);
            setField(skillet, "cookingProgress", 0);
            invoke(manager, "createVisual", new Class<?>[]{Location.class, skillet.getClass()}, location, skillet);
            invoke(manager, "saveSkillet", new Class<?>[]{Location.class, skillet.getClass()}, location, skillet);
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning("Failed to activate debug skillet at " + location + ": " + e.getMessage());
        }
    }

    private boolean placeDebugHeatSource(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        Block block = location.getBlock();
        if (!canReplace(block) && !plugin.getHeatSourceConfig().isHeatSource(block)) {
            return false;
        }
        if (!plugin.getHeatSourceConfig().isHeatSource(block)) {
            block.setType(Material.CAMPFIRE, false);
        }
        if (block.getBlockData() instanceof Campfire campfire) {
            campfire.setLit(true);
            campfire.setWaterlogged(false);
            campfire.setFacing(BlockFace.NORTH);
            block.setBlockData(campfire, false);
        }
        return plugin.getHeatSourceConfig().isHeatSource(block);
    }

    private void markCookingPotActive(World world, BlockPosKey posKey) {
        if (world == null || posKey == null || plugin.getTickManager() == null) {
            return;
        }
        plugin.getTickManager().markActive(world, posKey, TickManager.BlockType.COOKING_POT);
    }

    private boolean placeBlock(Location location, String blockId, boolean playSound) {
        return placeBlock(location, blockId, playSound, false);
    }

    private boolean placeBlock(Location location, String blockId, boolean playSound, boolean lit) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!canReplace(location.getBlock())) {
            return false;
        }
        BlockDefinition block = CraftEngineBlocks.byId(Key.of(blockId));
        if (block == null) {
            return false;
        }
        ImmutableBlockState state = lit ? withBooleanState(block.defaultState(), "fire", true) : block.defaultState();
        return CraftEngineBlocks.place(location, state, playSound);
    }

    private void ensureLitStove(Location location) {
        if (!isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
            return;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(location.getBlock());
        if (state == null || state.isEmpty() || Boolean.TRUE.equals(getBooleanState(state, "fire"))) {
            return;
        }
        CraftEngineBlocks.place(location, withBooleanState(state, "fire", true), false);
    }

    private boolean hasHeatSourceBelow(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return plugin.getHeatSourceConfig().isHeatSource(location.clone().subtract(0, 1, 0).getBlock());
    }

    private boolean isStoveLit(Location location) {
        if (!isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
            return false;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(location.getBlock());
        Boolean lit = getBooleanState(state, "fire");
        return Boolean.TRUE.equals(lit);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ImmutableBlockState withBooleanState(ImmutableBlockState state, String propertyName, boolean value) {
        if (state == null) {
            return null;
        }
        for (Property<?> property : state.getProperties()) {
            if (propertyName.equals(property.name())) {
                return state.with((Property) property, value);
            }
        }
        return state;
    }

    private Boolean getBooleanState(ImmutableBlockState state, String propertyName) {
        if (state == null) {
            return null;
        }
        for (Property<?> property : state.getProperties()) {
            if (!propertyName.equals(property.name())) {
                continue;
            }
            Object value = state.get(property);
            return value instanceof Boolean bool ? bool : null;
        }
        return null;
    }

    private boolean canReplace(Block block) {
        return block != null && (block.getType() == Material.AIR || BlockStateUtils.isReplaceable(BlockStateUtils.getBlockState(block)));
    }

    private boolean isPlacedCustomBlock(Location location, String blockId) {
        return location != null && CustomBlockUtils.hasId(location, blockId);
    }

    private boolean isCookingPotTarget(String target) {
        return "cooking_pot".equals(target) || "pot".equals(target) || "all".equals(target);
    }

    private boolean isSkilletTarget(String target) {
        return "skillet".equals(target) || "pan".equals(target) || "all".equals(target);
    }

    private boolean isStoveTarget(String target) {
        return "stove".equals(target) || "all".equals(target);
    }

    private boolean isBlockedStoveTarget(String target) {
        return "stove_blocked".equals(target) || "blocked_stove".equals(target);
    }

    private ItemStack item(String itemId, int amount) {
        ItemStack item = ItemUtils.createItem(itemId);
        if (item == null || item.getType().isAir()) {
            return null;
        }
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    private Location parseLocation(World world, Map<String, Object> data) {
        if (world == null || data == null) {
            return null;
        }
        Object x = data.get("_x");
        Object y = data.get("_y");
        Object z = data.get("_z");
        if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
            return null;
        }
        return new Location(world, nx.intValue(), ny.intValue(), nz.intValue());
    }

    private List<String> complete(List<String> options, String partial) {
        String normalized = normalize(partial);
        List<String> completions = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(normalized)) {
                completions.add(option);
            }
        }
        return completions;
    }

    private Integer parseOptionalInt(String[] args, int index, int fallback) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int getMaxPlaceCount() {
        return Math.max(1, plugin.getConfig().getInt("debug-tools.max-place-count", DEFAULT_MAX_PLACE_COUNT));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String normalizeTarget(String value) {
        String normalized = normalize(value);
        return "both".equals(normalized) ? "all" : normalized;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools place <cooking_pot|skillet|stove|stove_blocked|all> [count] [spacing] [layers]</yellow>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools activate <cooking_pot|skillet|stove|all></yellow>"
        ));
    }

    private record PlaceResult(boolean placed, boolean activated) {
    }

    private Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private int getIntField(Object target, String name, int fallback) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(target);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private record UndoEntry(Location location, BlockData blockData, Material vanillaFallback, String blockId) {
    }

}
