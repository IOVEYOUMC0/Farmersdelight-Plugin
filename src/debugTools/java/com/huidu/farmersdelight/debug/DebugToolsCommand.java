package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.DebugToolExtension;
import com.huidu.farmersdelight.api.util.DebugToolRegistry;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.plugin.locale.TranslationManager;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class DebugToolsCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int DEFAULT_MAX_PLACE_COUNT = 4096;
    private static final List<String> TEST_TARGETS = List.of("cooking_pot", "skillet", "stove", "handheld", "all");
    private static final List<String> ACTIONS = List.of("test", "place", "activate", "undo", "stop",
            "inspect", "item", "recipe", "i18n");
    private static final List<String> TARGETS = List.of("cooking_pot", "skillet", "stove", "stove_blocked",
            "cutting_board", "basket", "all");
    // Basket has no Constants block-id entry (only a behavior constant); its block id equals its behavior id.
    private static final String BLOCK_BASKET = "farmersdelight:basket";
    private static final int UNDO_HISTORY_LIMIT = 8;
    private final Deque<PlacementBatch> undoHistory = new ArrayDeque<>();

    private final FarmersDelightPlugin plugin;
    private final DebugBatchRunner batches;
    private final Map<String, ItemStack> debugItemCache = new ConcurrentHashMap<>();
    private final Map<UUID, PluginTask> handheldTests = new ConcurrentHashMap<>();

    public DebugToolsCommand(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.batches = new DebugBatchRunner(plugin);
    }

    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.getComponent("command.player_only"));
            return;
        }
        if (args.length < 2) {
            sendUsage(player);
            return;
        }

        switch (normalize(args[1])) {
            case "test" -> test(player, args);
            case "place" -> place(player, args);
            case "activate" -> activate(player, args);
            case "undo" -> undo(player);
            case "stop" -> stop(player);
            case "inspect", "look" -> inspect(player, args);
            case "item", "hand", "held" -> dumpHeldItem(player, args);
            case "recipe", "recipes" -> recipeValidate(player);
            case "i18n", "lang" -> i18nResolve(player, args);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return complete(ACTIONS, args[1]);
        }
        if (args.length == 3) {
            String action = normalize(args[1]);
            if ("test".equals(action)) return complete(TEST_TARGETS, args[2]);
            if ("item".equals(action) || "hand".equals(action) || "held".equals(action)) {
                return complete(List.of("offhand"), args[2]);
            }
            if ("recipe".equals(action) || "recipes".equals(action)) {
                return complete(List.of("validate"), args[2]);
            }
            if ("undo".equals(action) || "stop".equals(action) || "inspect".equals(action) || "look".equals(action)
                    || "i18n".equals(action) || "lang".equals(action)) {
                return List.of();
            }
            List<String> targets = new ArrayList<>(TARGETS);
            targets.addAll(DebugToolRegistry.registeredNames());
            return complete(targets, args[2]);
        }
        if (args.length == 4 && ("test".equals(normalize(args[1])) || "place".equals(normalize(args[1])))) {
            return complete(List.of("16", "64", "128", "512", "1024"), args[3]);
        }
        if (args.length == 5 && "test".equals(normalize(args[1]))) {
            return complete(List.of("100", "200", "600", "1200"), args[4]);
        }
        return List.of();
    }

    private void test(Player player, String[] args) {
        Integer count = parseOptionalInt(args, 3, 64);
        Integer ticks = parseOptionalInt(args, 4, 200);
        if (args.length < 3 || args.length > 5 || !TEST_TARGETS.contains(normalize(args[2]))
                || count == null || count <= 0 || ticks == null || ticks < 20 || ticks > 12000) {
            player.sendMessage(I18n.getComponent("command.debug_test_usage", player));
            return;
        }
        if (plugin.getTickManager().getPerformanceSnapshot().statsEnabled()) {
            player.sendMessage(I18n.getComponent("command.stats_profile_busy", player));
            return;
        }
        if ("handheld".equals(normalize(args[2]))) {
            testHandheld(player, ticks);
            return;
        }
        place(player, new String[]{args[0], "place", normalize(args[2]), count.toString(), "1", "1"}, true,
                () -> player.performCommand("fd stats profile " + ticks + " " + normalize(args[2])));
    }

    private void testHandheld(Player player, int ticks) {
        SkilletManager manager = plugin.getSkilletManager();
        if (manager == null) {
            player.sendMessage(I18n.getComponent("command.debug_test_handheld_unavailable", player));
            return;
        }
        boolean started = manager.handleHandheldInteract(player, EquipmentSlot.HAND,
                NamespacedKey.fromString("farmersdelight:skillet_cooking"),
                NamespacedKey.fromString("farmersdelight:item/skillet_food"), Map.of());
        if (!started) {
            player.sendMessage(I18n.getComponent("command.debug_test_handheld_setup", player));
            return;
        }
        player.performCommand("fd stats profile " + ticks + " handheld");
        UUID playerId = player.getUniqueId();
        PluginTask previous = handheldTests.remove(playerId);
        if (previous != null) previous.cancel();
        AtomicReference<PluginTask> taskRef = new AtomicReference<>();
        PluginTask task = plugin.scheduler().runLater(() -> plugin.scheduler().runForEntity(player, () -> {
            if (handheldTests.get(playerId) != taskRef.get()) return;
            handheldTests.remove(playerId, taskRef.get());
            manager.stopHandheldUse(player, null);
        }), ticks + 1L);
        taskRef.set(task);
        handheldTests.put(playerId, task);
    }

    private void stop(Player player) {
        boolean stopped = batches.stop(player);
        PluginTask handheldTask = handheldTests.remove(player.getUniqueId());
        if (handheldTask != null) {
            handheldTask.cancel();
            SkilletManager manager = plugin.getSkilletManager();
            if (manager != null) manager.stopHandheldUse(player, null);
            stopped = true;
        }
        player.sendMessage(I18n.getComponent(stopped
                ? "command.debug_batch_stopped" : "command.debug_batch_no_task", player));
    }

    private void place(Player player, String[] args) {
        place(player, args, false, () -> { });
    }

    private void place(Player player, String[] args, boolean activate, Runnable finished) {
        if (args.length < 3 || args.length > 6) {
            sendUsage(player);
            return;
        }
        if (batches.busy()) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return;
        }
        String target = normalizeTarget(args[2]);
        Integer requestedCount = parseOptionalInt(args, 3, 64);
        Integer requestedSpacing = parseOptionalInt(args, 4, 1);
        Integer requestedLayers = parseOptionalInt(args, 5, 1);
        if (requestedCount == null || requestedSpacing == null || requestedLayers == null
                || requestedCount <= 0 || requestedSpacing <= 0 || requestedLayers <= 0) {
            player.sendMessage(I18n.getComponent("command.debug_batch_invalid_numbers", player));
            return;
        }

        int maxPlaceCount = getMaxPlaceCount();
        int count = Math.min(maxPlaceCount, requestedCount);
        int spacing = clamp(requestedSpacing, 1, 16);
        long requestedTotalLong = (long) count * requestedLayers;
        int total = requestedTotalLong > maxPlaceCount ? maxPlaceCount : (int) requestedTotalLong;
        Location origin = ManagerSupport.normalize(player.getLocation());
        PlacementBatch batch = new PlacementBatch(player.getUniqueId());
        if (!isBuiltInTarget(target)) {
            DebugToolExtension extension = DebugToolRegistry.find(target);
            if (extension == null) {
                player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player,
                        Map.of("target", target)));
                return;
            }
            // Extension callbacks may touch nearby cells; they must own those cells themselves.
            if (plugin.scheduler().isFolia()) {
                player.sendMessage(I18n.getComponent("command.debug_batch_addon_folia", player));
                return;
            }
        }
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        boolean started = batches.start(player, total, index -> {
            int layerIndex = index % count;
            Location location = origin.clone().add(2 + layerIndex % grid * spacing,
                    2 + index / count * 3, 2 + layerIndex / grid * spacing);
            if (!isBuiltInTarget(target)) {
                placeAddon(player, batch, location, target);
                return;
            }
            if (!canEdit(player, location)) return;
            PlaceResult result = placeOne(player, location, target, index);
            batch.entries.addAll(result.undoEntries());
            if (result.placed()) batch.placed++;
            if (result.activated()) {
                batch.activations.add(new PendingActivation(location, result.activationTarget()));
            }
        }, () -> {
            player.sendMessage(I18n.getComponent("command.debug_batch_placed", player,
                    Map.of("count", String.valueOf(batch.placed), "total", String.valueOf(total))));
            if (activate) {
                activateBatch(player, batch, "all", () -> {
                    if (batch.placed > 0) finished.run();
                });
            } else {
                finished.run();
            }
        });
        if (started) {
            synchronized (undoHistory) {
                undoHistory.addFirst(batch);
                while (undoHistory.size() > UNDO_HISTORY_LIMIT) undoHistory.removeLast();
            }
            player.sendMessage(I18n.getComponent("command.debug_batch_started", player,
                    Map.of("count", String.valueOf(total))));
        } else {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    private void activate(Player player, String[] args) {
        String target = args.length >= 3 ? normalizeTarget(args[2]) : "all";
        int activated = 0;
        if (isCookingPotTarget(target)) activated += activateCookingPots(player.getWorld());
        if (isSkilletTarget(target)) activated += activateSkillets(player.getWorld());
        if (isStoveTarget(target)) activated += activateStoves(player.getWorld());
        if (!isBuiltInTarget(target)) {
            DebugToolExtension extension = DebugToolRegistry.find(target);
            if (extension != null) activated += extension.activate(player);
        } else if ("all".equals(target)) {
            for (DebugToolExtension extension : DebugToolRegistry.all()) activated += extension.activate(player);
        }
        player.sendMessage(MINI_MESSAGE.deserialize("<green>Debug scanned and filled " + activated + " placed blocks.</green>"));
    }

    private void undo(Player player) {
        PlacementBatch batch = lastBatch(player);
        if (batch == null) return;
        int[] restored = {0};
        if (!batches.start(player, batch.entries.size(), index -> {
            int entryIndex = batch.entries.size() - 1 - index;
            UndoEntry entry = batch.entries.get(entryIndex);
            if (entry == null || !canEdit(player, entry.location())) return;
            if (!hasChangedSinceCapture(entry)) return;
            restoreUndoEntry(entry);
            batch.entries.set(entryIndex, null);
            restored[0]++;
        }, () -> {
            batch.entries.removeIf(Objects::isNull);
            if (batch.entries.isEmpty()) synchronized (undoHistory) { undoHistory.remove(batch); }
            player.sendMessage(I18n.getComponent("command.debug_batch_undone", player,
                    Map.of("count", String.valueOf(restored[0]), "remaining", String.valueOf(batch.entries.size()))));
        })) player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
    }

    private PlacementBatch lastBatch(Player player) {
        synchronized (undoHistory) {
            for (PlacementBatch batch : undoHistory) if (batch.owner.equals(player.getUniqueId())) return batch;
        }
        player.sendMessage(I18n.getComponent("command.debug_batch_empty", player));
        return null;
    }

    private void activateBatch(Player player, PlacementBatch batch, String target, Runnable finished) {
        int[] activated = {0};
        if (!batches.start(player, batch.activations.size(), index -> {
            PendingActivation activation = batch.activations.get(index);
            if (!("all".equals(target) || target.equals(activation.target()))
                    || !canEdit(player, activation.location())) return;
            boolean stillPresent = batch.entries.stream().filter(Objects::nonNull)
                    .anyMatch(entry -> entry.location().equals(activation.location()) && hasChangedSinceCapture(entry));
            if (!stillPresent) return;
            activateScheduled(activation);
            activated[0]++;
        }, () -> {
            player.sendMessage(I18n.getComponent("command.debug_batch_activated", player,
                    Map.of("count", String.valueOf(activated[0]))));
            finished.run();
        })) player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
    }

    private boolean canEdit(Player player, Location location) {
        if (location == null || location.getWorld() == null || player.getWorld() != location.getWorld()) return false;
        World world = location.getWorld();
        return location.getBlockY() >= world.getMinHeight() && location.getBlockY() < world.getMaxHeight()
                && plugin.scheduler().isOwnedByCurrentRegion(location)
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && ProtectionCompat.canBuild(player, location);
    }

    private void placeAddon(Player player, PlacementBatch batch, Location location, String target) {
        if (!canEdit(player, location) || !canReplace(location.getBlock())) return;
        DebugToolExtension extension = DebugToolRegistry.find(target);
        if (extension == null) return;
        List<UndoEntry> captured = new ArrayList<>();
        try {
            batch.placed += extension.place(player, location.clone().subtract(0, 1, 0), 1, 1, 1, loc -> {
                if (!canEdit(player, loc) || !canReplace(loc.getBlock())) {
                    throw new IllegalStateException("Addon debug placement must use an empty, editable cell");
                }
                captured.add(captureUndo(loc));
            });
        } finally {
            for (UndoEntry entry : captured) rememberIfChanged(batch.entries, entry);
        }
    }

    // Read-only diagnostics: inspect a block, dump a held item, validate recipes, trace a translation key.

    private void inspect(Player player, String[] args) {
        Integer requested = parseOptionalInt(args, 2, 6);
        int distance = requested == null ? 6 : clamp(requested, 1, 64);
        Block hit = player.getTargetBlockExact(distance);
        Block target = (hit != null && !hit.getType().isAir())
                ? hit
                : player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Location location = target.getLocation();
        // The custom-block / block-entity / manager-snapshot reads below are documented region-thread-only on
        // Folia, so the whole dump (and the reply to the player) runs on the region that owns the target block —
        // the same pattern profile() uses. On Paper this executes inline.
        plugin.scheduler().runAt(location, () -> dumpBlock(player, target));
    }

    private void dumpBlock(Player player, Block target) {
        Location location = target.getLocation();
        String coords = target.getWorld().getName() + " " + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();

        ImmutableBlockState state = CustomBlockUtils.getState(target);
        if (state == null || state.isEmpty()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>Inspect: no CraftEngine custom block at "
                    + coords + " (bukkit " + target.getType() + ").</yellow>"));
            return;
        }

        String id = CustomBlockUtils.getId(state);
        player.sendMessage(MINI_MESSAGE.deserialize("<green>Inspect</green> <gray>" + id + " @ " + coords + "</gray>"));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>bukkit-material:</gray> " + target.getType()));

        String props = describeProperties(state);
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>properties:</gray> " + (props.isEmpty() ? "(none)" : props)));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>behaviors:</gray> " + describeBehaviors(state)));

        boolean definitionBE = state.hasBlockEntity();
        boolean runtimeBE = false;
        World world = target.getWorld();
        BlockPosKey posKey = new BlockPosKey(location);
        var ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld != null) {
            try {
                runtimeBE = ceWorld.getBlockEntityAtIfLoaded(posKey.toBlockPos()) != null;
            } catch (Exception ignored) {
            }
        }
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>block-entity:</gray> definition=" + definitionBE
                + ", runtime=" + (runtimeBE ? "present" : "none")));

        dumpCookingPotContents(player, world, location, posKey);
        dumpStoveContents(player, location);
        dumpSkilletContents(player, location);

        Block below = target.getRelative(BlockFace.DOWN);
        boolean heat = plugin.getHeatSourceConfig().isHeatSource(below);
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>heat-below:</gray> " + below.getType()
                + " -> heat=" + heat));

        TickManager tickManager = plugin.getTickManager();
        if (tickManager != null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>global active-blocks:</gray> "
                    + tickManager.getPerformanceSnapshot().currentActiveBlocks()));
        }
    }

    private String describeProperties(ImmutableBlockState state) {
        StringBuilder builder = new StringBuilder();
        for (Property<?> property : state.getProperties()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(property.name()).append('=').append(CustomBlockUtils.getPropertyString(state, property.name()));
        }
        return builder.toString();
    }

    private String describeBehaviors(ImmutableBlockState state) {
        var behavior = state.behavior();
        if (behavior == null) {
            return "(none)";
        }
        try {
            Object array = getField(behavior, "behaviors");
            if (array instanceof Object[] behaviors) {
                StringBuilder builder = new StringBuilder(behavior.getClass().getSimpleName()).append("[ ");
                for (int i = 0; i < behaviors.length; i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(behaviors[i] == null ? "null" : behaviors[i].getClass().getSimpleName());
                }
                return builder.append(" ]").toString();
            }
        } catch (ReflectiveOperationException notComposite) {
            // Single-behavior block: no 'behaviors' field, fall through to the concrete class name.
        }
        return behavior.getClass().getSimpleName();
    }

    private void dumpCookingPotContents(Player player, World world, Location location, BlockPosKey posKey) {
        if (!CookingPotBlockBehavior.isCookingPotBlock(world, posKey)
                && !isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
            return;
        }
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(location);
        if (entity == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>pot contents:</gray> (no block-entity)"));
            return;
        }
        var recipe = entity.getCurrentRecipe();
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>pot contents:</gray> stored=" + entity.hasStoredContents()
                + ", input=" + entity.hasInput()
                + ", progress=" + entity.getCookingProgress() + "/" + entity.getCookingDuration()
                + ", comparator=" + entity.getComparatorOutput()
                + ", recipe=" + (recipe == null ? "(none)" : recipe.getId())));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>pot inputs:</gray> ")
                .append(Component.text(entity.debugInputSummary())));
    }

    private void dumpStoveContents(Player player, Location location) {
        StoveManager manager = plugin.getStoveManager();
        if (manager == null || !manager.isStoveStateBlock(location)) {
            return;
        }
        var snapshot = manager.snapshot(location);
        if (snapshot == null) {
            return;
        }
        StringBuilder slots = new StringBuilder();
        var items = snapshot.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) != null) {
                slots.append(" [").append(i).append("] ").append(shortItem(items.get(i)))
                        .append(' ').append(Math.round(snapshot.progressFraction(i) * 100)).append('%');
            }
        }
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>stove contents:</gray> lit=" + snapshot.lit()
                + ", blockedAbove=" + snapshot.blockedAbove() + ", slots=" + snapshot.occupiedSlots() + slots));
    }

    private void dumpSkilletContents(Player player, Location location) {
        SkilletManager manager = plugin.getSkilletManager();
        if (manager == null) {
            return;
        }
        var snapshot = manager.snapshot(location);
        if (snapshot == null) {
            return;
        }
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>skillet contents:</gray> stored="
                + shortItem(snapshot.storedItem())
                + ", recipe=" + (snapshot.recipeId() == null ? "(none)" : snapshot.recipeId())
                + ", progress=" + snapshot.progressTicks() + "/" + snapshot.cookTimeTicks()
                + ", heated=" + snapshot.heated() + ", fireAspect=" + snapshot.fireAspectLevel()));
    }

    private String shortItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "empty";
        }
        String id = ItemUtils.resolveItemId(stack);
        return (id == null ? stack.getType().name() : id) + " x" + stack.getAmount();
    }

    private void dumpHeldItem(Player player, String[] args) {
        boolean offhand = args.length >= 3 && normalize(args[2]).startsWith("off");
        ItemStack held = offhand
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>You are not holding an item"
                    + (offhand ? " in your off hand." : ".") + "</yellow>"));
            return;
        }

        player.sendMessage(MINI_MESSAGE.deserialize("<green>Item</green> <gray>(" + (offhand ? "off hand" : "main hand") + ")</gray>"));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>material:</gray> " + held.getType()
                + "  <gray>count:</gray> " + held.getAmount() + "/" + held.getMaxStackSize()));

        try {
            if (!ItemUtils.isAnyCustomItemLoaded()) {
                player.sendMessage(MINI_MESSAGE.deserialize("<gray>ce-id:</gray> (CraftEngine items not loaded yet)"));
                return;
            }
            String ceId = ItemUtils.getCustomItemId(held);
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>ce-id:</gray> " + (ceId == null ? "(vanilla)" : ceId)
                    + "  <gray>resolved-id:</gray> " + ItemUtils.resolveItemId(held)
                    + "  <gray>custom:</gray> " + ItemUtils.isCustomItem(held)));
            List<String> tags = ItemUtils.getAllItemTagIds(held);
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>tags:</gray> "
                    + (tags.isEmpty() ? "(none)" : String.join(", ", tags))));
            dumpComponent(player, held, DataComponentKeys.CUSTOM_DATA, "custom_data");
            dumpComponent(player, held, DataComponentKeys.BLOCK_ENTITY_DATA, "block_entity_data");
        } catch (RuntimeException | LinkageError e) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>CE identity unavailable: "
                    + e.getClass().getSimpleName() + "</red>"));
        }
    }

    private void dumpComponent(Player player, ItemStack item, Key componentKey, String label) {
        try {
            Item wrapped = BukkitItemManager.instance().wrap(item.clone());
            CompoundTag tag = CustomBlockUtils.getComponentCompound(wrapped, componentKey);
            if (tag == null) {
                player.sendMessage(MINI_MESSAGE.deserialize("<gray>" + label + ":</gray> (absent)"));
                return;
            }
            String text = tag.toString();
            if (text.length() > 512) {
                text = text.substring(0, 512) + "…(truncated)";
            }
            // The NBT text may contain '<' / '>' — append it as a literal component so MiniMessage does not parse it.
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>" + label + ":</gray> ").append(Component.text(text)));
        } catch (Exception unreadable) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>" + label + ":</gray> (unreadable)"));
        }
    }

    private void recipeValidate(Player player) {
        List<String> issues = new ArrayList<>();

        int potCount = 0;
        CookingPotRecipeManager potManager = plugin.getCookingPotRecipes();
        Set<String> seenPotIds = new HashSet<>();
        if (potManager != null) {
            for (var recipe : potManager.getAllRecipes()) {
                if (!seenPotIds.add(recipe.getId())) {
                    continue;
                }
                potCount++;
                if (recipe.getIngredients().isEmpty()) {
                    issues.add("<red>[cooking_pot] " + recipe.getId() + "</red> <gray>has no ingredients</gray>");
                }
                if (recipe.getResult() == null || recipe.getResult().getType().isAir()) {
                    issues.add("<red>[cooking_pot] " + recipe.getId() + "</red> <gray>has no result</gray>");
                }
                if (recipe.getNeedsContainer()
                        && (recipe.getContainer() == null || recipe.getContainer().getType().isAir())) {
                    issues.add("<red>[cooking_pot] " + recipe.getId()
                            + "</red> <gray>requires a container but none resolved</gray>");
                }
                for (RecipeIngredient ingredient : recipe.getIngredients()) {
                    checkIngredient(potManager, "cooking_pot", recipe.getId(), ingredient, issues);
                }
            }
        }

        int boardCount = 0;
        CuttingBoardRecipeManager boardManager = plugin.getCuttingBoardRecipes();
        if (boardManager != null) {
            for (var recipe : boardManager.getSortedRecipes()) {
                boardCount++;
                if (recipe.getInput() == null) {
                    issues.add("<red>[cutting_board] " + recipe.getId() + "</red> <gray>has no input</gray>");
                } else {
                    checkIngredient(potManager, "cutting_board", recipe.getId(), recipe.getInput(), issues);
                }
                if (recipe.getTools().isEmpty()) {
                    issues.add("<red>[cutting_board] " + recipe.getId() + "</red> <gray>has no tool</gray>");
                }
                if (recipe.getResults().isEmpty()) {
                    issues.add("<red>[cutting_board] " + recipe.getId() + "</red> <gray>has no results</gray>");
                } else {
                    for (var result : recipe.getResults()) {
                        if (result == null || result.getItem() == null || result.getItem().getType().isAir()) {
                            issues.add("<red>[cutting_board] " + recipe.getId()
                                    + "</red> <gray>contains an unresolved result</gray>");
                        }
                    }
                }
            }
        }

        player.sendMessage(MINI_MESSAGE.deserialize("<green>Recipe validation</green> <gray>scanned " + potCount
                + " cooking-pot + " + boardCount + " cutting-board recipes (" + (potCount + boardCount) + " total)</gray>"));
        for (String line : issues) {
            player.sendMessage(MINI_MESSAGE.deserialize(line));
        }
        if (issues.isEmpty()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<green>No unresolved item ids found.</green>"));
        } else {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>" + issues.size()
                    + " issue(s). Recipes that failed to PARSE at load are logged separately as 'recipe.load_failed'.</yellow>"));
        }
    }

    private void checkIngredient(CookingPotRecipeManager tagResolver, String kind, String recipeId,
                                 RecipeIngredient ingredient, List<String> issues) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            if (ItemUtils.createItem(item.key()) == null) {
                issues.add("<red>[" + kind + "] " + recipeId + "</red> <gray>unresolved ingredient</gray> <yellow>"
                        + item.key() + "</yellow>");
            }
        } else if (ingredient instanceof RecipeIngredient.Tag tag) {
            boolean empty;
            try {
                var craftEngine = plugin.getCraftEngine();
                boolean ceItems = craftEngine != null && craftEngine.itemManager() != null
                        && !craftEngine.itemManager().itemIdsByTag(tag.key()).isEmpty();
                empty = (tagResolver == null || tagResolver.getVanillaItemIdsByTag(tag.key()).isEmpty())
                        && !ceItems
                        && CommonTagResolver.getMembers(tag.key()).isEmpty();
            } catch (Throwable cannotResolve) {
                return;
            }
            if (empty) {
                issues.add("<red>[" + kind + "] " + recipeId + "</red> <gray>tag resolves to 0 items</gray> <yellow>#"
                        + tag.key() + "</yellow>");
            }
        } else if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                checkIngredient(tagResolver, kind, recipeId, option, issues);
            }
        }
    }

    private void i18nResolve(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>/fd debugtools i18n <key> [locale]</yellow>"));
            return;
        }
        String key = args[2];
        String locale = args.length >= 4 ? normalize(args[3]) : "en_us";
        Locale loc = Locale.forLanguageTag(locale.replace('_', '-'));
        player.sendMessage(MINI_MESSAGE.deserialize("<green>i18n</green> <gray>key=" + key + " locale=" + locale + "</gray>"));

        String fd = I18n.get(key, locale);
        sendLayer(player, "I18n.get", fd, fd.equals(key));

        try {
            String cePlain = TranslationManager.instance().plainTranslation(key, loc);
            sendLayer(player, "CraftEngine.plain", cePlain, cePlain == null || cePlain.equals(key));
        } catch (LinkageError | RuntimeException e) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>CraftEngine.plain:</gray> <red>unavailable ("
                    + e.getClass().getSimpleName() + ")</red>"));
        }

        try {
            Component rendered = GlobalTranslator.render(Component.translatable(key), loc);
            String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
            sendLayer(player, "Adventure", plain, plain.equals(key));
        } catch (Exception e) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>Adventure:</gray> <red>error</red>"));
        }
    }

    private void sendLayer(Player player, String layer, String value, boolean absent) {
        Component line = MINI_MESSAGE.deserialize("<gray>" + layer + ":</gray> ")
                .append(Component.text(value == null ? "null" : value,
                        absent ? NamedTextColor.RED : NamedTextColor.WHITE));
        if (absent) {
            line = line.append(Component.text(" (absent/key)", NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(line);
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
        } else if (isCuttingBoardTarget(target)) {
            // Passive block: just place it. No heat source and nothing to activate.
            UndoEntry blockUndo = captureUndo(location);
            placed = placeBlock(location, Constants.BLOCK_CUTTING_BOARD, false);
            rememberIfChanged(undoEntries, blockUndo);
        } else if (isBasketTarget(target)) {
            // Passive block: the vacuum controller ticks on its own once placed, so no activation step.
            UndoEntry blockUndo = captureUndo(location);
            placed = placeBlock(location, BLOCK_BASKET, false);
            rememberIfChanged(undoEntries, blockUndo);
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
        return activated;
    }

    private int activateStoves(World world) {
        StoveManager manager = plugin.getStoveManager();
        if (manager == null) {
            return 0;
        }

        int activated = 0;
        for (Location location : manager.getTrackedLocations(world)) {
            if (!isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
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

        // Addon extensions release their own block-entity state for this location (e.g. BAC keg) so
        // an undone placement doesn't leak ghost NBT or in-memory entries.
        for (DebugToolExtension extension : DebugToolRegistry.all()) {
            try {
                extension.cleanupBeforeUndo(location);
            } catch (Throwable ignored) {
            }
        }
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
        plugin.getTrayManager().checkAndPlaceTray(location);
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
            // StoveManager has no createVisual of its own; it lives on the inner StoveVisualManager.
            Object visualManager = getField(manager, "visualManager");
            for (int slot = 0; slot < items.length; slot++) {
                ItemStack stack = food.clone();
                stack.setAmount(1);
                items[slot] = stack;
                cookingTime[slot] = 0;
                maxTime[slot] = duration;
                invoke(visualManager, "createVisual",
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

    private ImmutableBlockState withBooleanState(ImmutableBlockState state, String propertyName, boolean value) {
        if (state == null) {
            return null;
        }
        for (Property<?> property : state.getProperties()) {
            if (propertyName.equals(property.name())) {
                return ImmutableBlockState.with(state, property, value);
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

    private boolean isBuiltInTarget(String target) {
        return isCookingPotTarget(target) || isSkilletTarget(target)
                || isStoveTarget(target) || isBlockedStoveTarget(target)
                || isCuttingBoardTarget(target) || isBasketTarget(target);
    }

    private boolean isCuttingBoardTarget(String target) {
        return "cutting_board".equals(target) || "board".equals(target) || "cuttingboard".equals(target);
    }

    private boolean isBasketTarget(String target) {
        return "basket".equals(target);
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
                "<yellow>/fd debugtools test <cooking_pot|skillet|stove|handheld|all> [count] [ticks]</yellow>"
                        + " <gray>- place a bounded local test and start feature sampling</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools place <cooking_pot|skillet|stove|stove_blocked|cutting_board|basket|all> [count] [spacing] [layers]</yellow>"
                        + " <gray>- place a batch only; use activate to start its workload</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools activate <cooking_pot|skillet|stove|all></yellow>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools undo</yellow> <gray>- revert the last debug placement batch</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools inspect [distance]</yellow> <gray>- dump the CE block you look at / stand on</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools item [offhand]</yellow> <gray>- dump the held item's CE id / tags / components</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools recipe validate</yellow> <gray>- validate recipe structure, tags, results and containers</gray>"
        ));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<yellow>/fd debugtools i18n <key> [locale]</yellow> <gray>- trace a translation key through each layer</gray>"
        ));
    }

    private record PlaceResult(boolean placed, boolean activated, String activationTarget, List<UndoEntry> undoEntries) {
    }

    private record PendingActivation(Location location, String target) {
    }

    private static final class PlacementBatch {
        private final UUID owner;
        private final List<UndoEntry> entries = new ArrayList<>();
        private final List<PendingActivation> activations = new ArrayList<>();
        private int placed;

        private PlacementBatch(UUID owner) {
            this.owner = owner;
        }
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
