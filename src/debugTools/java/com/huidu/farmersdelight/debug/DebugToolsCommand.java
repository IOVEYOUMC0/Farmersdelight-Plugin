package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.storage.LegacyBlockStorageManager;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugToolsCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int DEFAULT_MAX_PLACE_COUNT = 65536;
    private static final int DEFAULT_PROFILE_TICKS = 200;
    private static final int MAX_PROFILE_TICKS = 12_000;
    private static final List<String> ACTIONS = List.of("place", "activate", "status", "profile", "undo");
    private static final List<String> TARGETS = List.of("cooking_pot", "skillet", "stove", "stove_blocked", "all");
    private static final List<String> PROFILE_DURATIONS = List.of("100", "200", "600", "1200");
    private static final int UNDO_HISTORY_LIMIT = 8;
    private static final Deque<List<UndoEntry>> UNDO_HISTORY = new ArrayDeque<>();

    private final FarmersDelightPlugin plugin;
    private final Map<String, ItemStack> debugItemCache = new ConcurrentHashMap<>();

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
            case "status", "stats" -> status(player);
            case "profile", "sample" -> profile(player, args);
            case "undo" -> undo(player);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return complete(ACTIONS, args[1]);
        }
        if (args.length == 3 && args.length > 1) {
            String action = normalize(args[1]);
            if ("profile".equals(action) || "sample".equals(action)) {
                return complete(PROFILE_DURATIONS, args[2]);
            }
            if ("status".equals(action) || "stats".equals(action) || "undo".equals(action)) {
                return List.of();
            }
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
        List<PendingActivation> pendingActivations = new ArrayList<>();
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
            PlaceResult result = placeOne(player, location, target, i);
            if (!result.undoEntries().isEmpty()) {
                rememberUndo(result.undoEntries());
            }
            if (result.placed()) {
                placed++;
            }
            if (result.activated()) {
                pendingActivations.add(new PendingActivation(location.clone(), result.activationTarget()));
                activated++;
            }
        }
        scheduleActivationBatch(pendingActivations);

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

    private void status(Player player) {
        TickManager tickManager = plugin.getTickManager();
        if (tickManager == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>TickManager is not available.</red>"));
            return;
        }
        sendPerformanceSnapshot(player, tickManager.getPerformanceSnapshot(), 0, "status");
    }

    private void profile(Player player, String[] args) {
        TickManager tickManager = plugin.getTickManager();
        if (tickManager == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>TickManager is not available.</red>"));
            return;
        }

        Integer requestedTicks = parseOptionalInt(args, 2, DEFAULT_PROFILE_TICKS);
        if (requestedTicks == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>Profile ticks must be a whole number.</red>"));
            return;
        }

        int durationTicks = clamp(requestedTicks, 20, MAX_PROFILE_TICKS);
        Location anchor = ManagerSupport.normalize(player.getLocation());
        tickManager.resetPerformanceStats();
        player.sendMessage(MINI_MESSAGE.deserialize("<green>Debug profile started.</green> <gray>duration="
                + durationTicks + " ticks, currentWorldPots=" + countCookingPots(player.getWorld()) + "</gray>"));

        plugin.scheduler().runLaterAt(anchor, () -> {
            if (!player.isOnline()) {
                tickManager.setPerformanceStatsEnabled(false);
                return;
            }
            TickManager.PerformanceSnapshot snapshot = tickManager.getPerformanceSnapshot();
            tickManager.setPerformanceStatsEnabled(false);
            sendPerformanceSnapshot(player, snapshot, durationTicks, "profile");
        }, durationTicks);
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
        for (int index = batch.size() - 1; index >= 0; index--) {
            UndoEntry entry = batch.get(index);
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
        List<UndoEntry> undoEntries = new ArrayList<>(3);
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
            Location heatLocation = location.clone().subtract(0, 1, 0);
            UndoEntry heatUndo = captureUndo(heatLocation);
            boolean heatReady = placeDebugHeatSource(heatLocation);
            rememberIfChanged(undoEntries, heatUndo);
            if (heatReady) {
                UndoEntry blockUndo = captureUndo(location);
                placed = placeBlock(location, Constants.BLOCK_COOKING_POT, false);
                rememberIfChanged(undoEntries, blockUndo);
            }
            if (placed) {
                activated = true;
                target = "cooking_pot";
            }
        } else if (isSkilletTarget(target)) {
            Location heatLocation = location.clone().subtract(0, 1, 0);
            UndoEntry heatUndo = captureUndo(heatLocation);
            boolean heatReady = placeDebugHeatSource(heatLocation);
            rememberIfChanged(undoEntries, heatUndo);
            if (heatReady) {
                UndoEntry blockUndo = captureUndo(location);
                placed = placeBlock(location, Constants.BLOCK_SKILLET, false);
                rememberIfChanged(undoEntries, blockUndo);
            }
            if (placed) {
                activated = true;
                target = "skillet";
            }
        } else if (isStoveTarget(target) || isBlockedStoveTarget(target)) {
            UndoEntry blockUndo = captureUndo(location);
            placed = placeBlock(location, Constants.BLOCK_STOVE, true, true);
            rememberIfChanged(undoEntries, blockUndo);
            if (placed) {
                if (isBlockedStoveTarget(target)) {
                    Location blockingLocation = location.clone().add(0, 1, 0);
                    UndoEntry blockingUndo = captureUndo(blockingLocation);
                    placeStoveBlockingBlock(blockingLocation);
                    rememberIfChanged(undoEntries, blockingUndo);
                }
                activated = true;
                target = "stove";
            }
        }
        return new PlaceResult(placed, activated, activated ? target : null, undoEntries);
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

        for (Map.Entry<String, Map<String, Object>> entry : getLegacyBlockData(world).entrySet()) {
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

        for (Map.Entry<String, Map<String, Object>> entry : getLegacyBlockData(world).entrySet()) {
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
        for (Map.Entry<String, Map<String, Object>> entry : getLegacyBlockData(world).entrySet()) {
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

    private void rememberUndo(List<UndoEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        synchronized (UNDO_HISTORY) {
            List<UndoEntry> batch = UNDO_HISTORY.peekFirst();
            if (batch == null) {
                batch = new ArrayList<>();
                UNDO_HISTORY.addFirst(batch);
            }
            batch.addAll(entries);
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
        cleanupPlacedState(entry.location());
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

    private void rememberIfChanged(List<UndoEntry> entries, UndoEntry entry) {
        if (entries != null && hasChangedSinceCapture(entry)) {
            entries.add(entry);
        }
    }

    private boolean hasChangedSinceCapture(UndoEntry entry) {
        if (entry == null || entry.location() == null || entry.location().getWorld() == null) {
            return false;
        }

        Block block = entry.location().getBlock();
        String currentBlockId = CustomBlockUtils.getId(block);
        if (!Objects.equals(entry.blockId(), currentBlockId)) {
            return true;
        }
        if (entry.blockData() == null) {
            return false;
        }
        return !entry.blockData().getAsString().equals(block.getBlockData().getAsString());
    }

    private void cleanupPlacedState(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        World world = location.getWorld();
        BlockPosKey posKey = new BlockPosKey(location);
        if (CookingPotBlockBehavior.isCookingPotBlock(world, posKey)
                || isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
            CookingPotBlockBehavior.removeBlockEntity(world, posKey);
        }

        Location dropLocation = location.clone().add(0.5, 0.5, 0.5);
        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager != null && isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
            skilletManager.breakSkillet(location, dropLocation, false);
        }

        StoveManager stoveManager = plugin.getStoveManager();
        if (stoveManager != null && stoveManager.isStoveStateBlock(location)) {
            stoveManager.breakStove(location, dropLocation, false);
        }
    }

    private void scheduleActivationBatch(List<PendingActivation> activations) {
        if (activations == null || activations.isEmpty()) {
            return;
        }
        if (plugin.scheduler().isFolia()) {
            for (PendingActivation activation : activations) {
                plugin.scheduler().runLaterAt(activation.location(), () -> activateScheduled(activation), 2L);
            }
            return;
        }
        plugin.scheduler().runLater(() -> {
            for (PendingActivation activation : activations) {
                activateScheduled(activation);
            }
        }, 2L);
    }

    private void activateScheduled(PendingActivation activation) {
        if (activation == null) {
            return;
        }
        Location location = activation.location();
        String target = activation.target();
        if (location == null || location.getWorld() == null) {
            return;
        }

        if ("cooking_pot".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
            activateCookingPot(location);
            verifyCookingPotFilled(location);
        } else if ("skillet".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
            activateSkillet(location);
        } else if ("stove".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
            activateStove(location);
        } else if ("cooking_pot".equals(target)) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_activation_skipped",
                    "location", ManagerSupport.formatLocation(location),
                    "id", CustomBlockUtils.getId(location.getBlock())));
        }
    }

    private void activateCookingPot(Location location) {
        CookingPotBlockBehavior.markRecentlyPlaced(location);
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        applyCookingPotDebugState(entity, location);
        BlockPosKey posKey = new BlockPosKey(location);
        saveCookingPotData(location, entity, posKey);
        markCookingPotActive(location.getWorld(), posKey);
        syncCookingPotTray(location);
    }

    private void syncCookingPotTray(Location location) {
        if (location == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().queueTraySync(location);
    }

    private void verifyCookingPotFilled(Location location) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(location);
        if (entity == null) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_no_entity",
                    "location", ManagerSupport.formatLocation(location)));
            return;
        }
        if (!entity.hasStoredContents() || !entity.hasInput()) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_empty",
                    "location", ManagerSupport.formatLocation(location)));
            return;
        }
        if (!entity.canCook()) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_no_recipe",
                    "location", ManagerSupport.formatLocation(location)));
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
        CookingPotBlockBehavior.saveBlockEntityData(location.getWorld(), posKey);

        LegacyBlockStorageManager storage = plugin.getLegacyBlockStorageManager();
        if (storage != null) {
            storage.removeBlockData(posKey.toLocation(location.getWorld()));
        }
    }

    private Map<String, Map<String, Object>> getLegacyBlockData(World world) {
        LegacyBlockStorageManager storage = plugin.getLegacyBlockStorageManager();
        return storage == null ? Map.of() : storage.getAllBlockDataInWorld(world);
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
            plugin.getLogger().warning(I18n.formatConsole("debug.stove_activation_failed",
                    "location", location,
                    "error", e.getMessage()));
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
            plugin.getLogger().warning(I18n.formatConsole("debug.skillet_activation_failed",
                    "location", location,
                    "error", e.getMessage()));
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

    private void sendPerformanceSnapshot(Player player, TickManager.PerformanceSnapshot snapshot,
                                         int durationTicks, String label) {
        int worldPots = countCookingPots(player.getWorld());
        player.sendMessage(MINI_MESSAGE.deserialize("<green>Debug " + label + ":</green> <gray>samples="
                + snapshot.samples() + ", durationTicks=" + durationTicks
                + ", tickInterval=" + snapshot.tickInterval()
                + ", budget=" + snapshot.tickBudget()
                + ", sampling=" + (snapshot.statsEnabled() ? "on" : "off") + "</gray>"));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>active current=" + snapshot.currentActiveBlocks()
                + ", snapshot=" + snapshot.snapshotActiveBlocks()
                + ", last=" + snapshot.lastActiveBlocks()
                + ", pending=+" + snapshot.pendingAdditions() + "/-" + snapshot.pendingRemovals()
                + ", worldPots=" + worldPots + "</gray>"));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>tick avg=" + formatMillis(snapshot.averageNanos())
                + "ms, max=" + formatMillis(snapshot.maxNanos())
                + "ms, last=" + formatMillis(snapshot.lastNanos())
                + "ms, lastProcessed=" + snapshot.lastProcessedBlocks() + "</gray>"));
    }

    private int countCookingPots(World world) {
        return world == null ? 0 : CookingPotBlockBehavior.getAllBlockEntities(world).size();
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
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        ItemStack template = debugItemCache.get(itemId);
        if (template == null || template.getType().isAir()) {
            ItemStack created = ItemUtils.createItem(itemId);
            if (created == null || created.getType().isAir()) {
                return null;
            }
            created.setAmount(1);
            ItemStack previous = debugItemCache.putIfAbsent(itemId, created.clone());
            template = previous != null ? previous : created;
        }

        ItemStack item = template.clone();
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

    private String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
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
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools status</yellow> <gray>- show current TickManager profile counters</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools profile [ticks]</yellow> <gray>- sample TickManager cost, default "
                        + DEFAULT_PROFILE_TICKS + " ticks</gray>"
        ));
    }

    private record PlaceResult(boolean placed, boolean activated, String activationTarget, List<UndoEntry> undoEntries) {
    }

    private record PendingActivation(Location location, String target) {
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
