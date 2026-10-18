package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.DebugToolExtension;
import com.huidu.farmersdelight.api.block.CraftEngineBlockAccess;
import com.huidu.farmersdelight.api.util.DebugToolRegistry;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.manager.HandheldSimulation;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.item.Item;
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
import org.bukkit.entity.Entity;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import static com.huidu.farmersdelight.debug.DebugReadOnlyCommands.REPORT_CAP;
import static com.huidu.farmersdelight.debug.DebugArguments.clamp;
import static com.huidu.farmersdelight.debug.DebugArguments.normalize;
import static com.huidu.farmersdelight.debug.DebugArguments.parseOptionalInt;
import static com.huidu.farmersdelight.debug.DebugReflection.invoke;
import static com.huidu.farmersdelight.debug.DebugReflection.getField;
import static com.huidu.farmersdelight.debug.DebugReflection.setField;
import static com.huidu.farmersdelight.debug.DebugReflection.getIntField;

/** Debug-only commands; compile with -PdebugTools=true. */
public final class DebugToolsCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int DEFAULT_MAX_PLACE_COUNT = 4096;
    // Optional debug-only limit; release config intentionally omits it.
    private static final String CONFIG_MAX_PLACE_COUNT = "debug-tools.max-place-count";
    private static final List<String> ACTIONS = List.of("test", "profile", "place", "activate", "undo", "stop", "inspect", "item", "recipe", "i18n");
    private static final List<String> TARGETS = List.of("cooking_pot", "skillet", "stove", "stove_blocked",
            "cutting_board", "basket");
    // Stations a single recipe can be set up on; a recipe's own type decides which one is placed.
    private static final List<String> RECIPE_STATIONS = List.of("cooking_pot", "cutting_board");
    // Recipe setup places the station, a heat source below it (pot/skillet) and the exact ingredients, so the
    // grid spacing is wider than a bare block placement's to keep neighbouring setups from touching.
    private static final int RECIPE_GRID_SPACING = 3;
    // Basket has no Constants block-id entry (only a behavior constant); its block id equals its behavior id.
    private static final String BLOCK_BASKET = "farmersdelight:basket";
    private static final int UNDO_HISTORY_LIMIT = 8;
    // Reports whose line count follows the loaded content are cut to this many lines with the hidden count
    // appended; one line per loaded recipe would otherwise flood the chat. See sendCapped.
    private final Map<UUID, Deque<PlacementBatch>> undoHistory = new HashMap<>();

    private final FarmersDelightPlugin plugin;
    private final DebugBatchRunner batches;
    private final DebugProfileRunner profiles;
    private final DebugReadOnlyCommands diagnostics;
    private final Map<String, ItemStack> debugItemCache = new ConcurrentHashMap<>();
    private final Map<UUID, HandheldTest> handheldTests = new ConcurrentHashMap<>();

    public DebugToolsCommand(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.batches = new DebugBatchRunner(plugin);
        this.profiles = new DebugProfileRunner(plugin);
        this.diagnostics = new DebugReadOnlyCommands(plugin, batches);
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
            case "profile" -> profile(player, args);
            case "place" -> place(player, args);
            case "activate" -> activate(player, args);
            case "undo" -> undo(player);
            case "stop" -> stop(player);
            case "inspect" -> diagnostics.inspect(player, args);
            case "item" -> diagnostics.dumpHeldItem(player, args);
            case "recipe" -> recipe(player, args);
            case "i18n" -> diagnostics.i18nResolve(player, args);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) return complete(ACTIONS, args[1]);
        if (args.length < 3) return List.of();
        String action = normalize(args[1]);
        if (args.length == 3) {
            return switch (action) {
                case "place" -> complete(withAll(placementTargets()), args[2]);
                case "test" -> complete(withAll(testTargets()), args[2]);
                case "activate" -> complete(withAll(activationTargets()), args[2]);
                case "recipe" -> complete(List.of("place", "random", "validate"), args[2]);
                case "profile" -> complete(List.of("serialization"), args[2]);
                case "item" -> complete(List.of("offhand"), args[2]);
                default -> List.of();
            };
        }
        if ("recipe".equals(action)) {
            String operation = normalize(args[2]);
            if (!List.of("place", "random").contains(operation)) return List.of();
            if (args.length == 4) return complete(withAll(recipeTargets()), args[3]);
            if (!withAll(recipeTargets()).contains(normalize(args[3]))) return List.of();
            if (args.length == 5 && "place".equals(operation)) {
                return complete(recipeIds(recipeChoices(normalize(args[3]))), args[4]);
            }
            if ((args.length == 5 && "random".equals(operation)) || (args.length == 6 && "place".equals(operation))) {
                return complete(List.of("1", "4", "16", "64"), args[args.length - 1]);
            }
            return List.of();
        }
        if (args.length == 4 && "profile".equals(action) && "serialization".equals(normalize(args[2]))) {
            return complete(List.of("200", "600", "1200"), args[3]);
        }
        if (args.length == 4 && (("test".equals(action) && withAll(testTargets()).contains(normalize(args[2])))
                || ("place".equals(action) && withAll(placementTargets()).contains(normalize(args[2]))))) {
            return complete(List.of("16", "64", "128", "512", "1024"), args[3]);
        }
        if (args.length == 5 && "test".equals(action) && withAll(testTargets()).contains(normalize(args[2]))) {
            return complete(List.of("100", "200", "600", "1200"), args[4]);
        }
        return List.of();
    }

    private void recipe(Player player, String[] args) {
        if (args.length < 3) {
            sendUsage(player);
            return;
        }
        switch (normalize(args[2])) {
            case "validate" -> {
                if (args.length == 3) diagnostics.recipeValidate(player, this::resolveDebugItem);
                else sendUsage(player);
            }
            case "place", "random" -> {
                String[] arguments = new String[args.length - 1];
                arguments[0] = args[0];
                System.arraycopy(args, 2, arguments, 1, args.length - 2);
                if ("place".equals(normalize(args[2]))) placeRecipe(player, arguments);
                else placeRecipeRandom(player, arguments);
            }
            default -> sendUsage(player);
        }
    }

    static List<String> placementTargets() {
        List<String> targets = new ArrayList<>(TARGETS);
        DebugToolRegistry.all().stream().filter(DebugToolExtension::supportsPlacement)
                .map(DebugToolExtension::name).sorted().forEach(targets::add);
        return List.copyOf(targets);
    }

    static List<String> activationTargets() {
        List<String> targets = new ArrayList<>(List.of("cooking_pot", "skillet", "stove", "stove_blocked", "basket"));
        DebugToolRegistry.all().stream().filter(extension -> extension.supportsPlacement() && extension.supportsActivation())
                .map(DebugToolExtension::name).sorted().forEach(targets::add);
        return List.copyOf(targets);
    }

    private static List<String> testTargets() {
        List<String> targets = new ArrayList<>(activationTargets());
        targets.add("handheld");
        return targets;
    }

    static List<String> recipeTargets() {
        List<String> targets = new ArrayList<>(RECIPE_STATIONS);
        DebugToolRegistry.all().stream().filter(extension -> extension.supportsPlacement() && !extension.recipeIds().isEmpty())
                .map(DebugToolExtension::name).sorted().forEach(targets::add);
        return List.copyOf(targets);
    }

    private static List<String> withAll(List<String> targets) {
        List<String> choices = new ArrayList<>(targets);
        choices.add("all");
        return choices;
    }

    private void profile(Player player, String[] args) {
        Integer ticks = parseOptionalInt(args, 3, 600);
        if (args.length < 3 || args.length > 4 || !"serialization".equals(normalize(args[2]))
                || ticks == null || ticks < 20 || ticks > 12000) {
            player.sendMessage(I18n.getComponent("command.debug_usage_profile", player));
            return;
        }
        profiles.start(player, ticks, "serialization");
    }

    private void test(Player player, String[] args) {
        Integer count = parseOptionalInt(args, 3, 64);
        // Above the 600-tick campfire/stove cooking time, so a default run reaches completion instead of
        // reporting completed=0 that reads like a failure.
        Integer ticks = parseOptionalInt(args, 4, 700);
        if (args.length < 3 || args.length > 5 || !withAll(testTargets()).contains(normalize(args[2]))
                || count == null || count <= 0 || ticks == null || ticks < 20 || ticks > 12000) {
            player.sendMessage(I18n.getComponent("command.debug_test_usage", player));
            return;
        }
        if ("handheld".equals(normalize(args[2]))) {
            testHandheld(player, Math.min(4096, Math.min(getMaxPlaceCount(), count)), ticks);
            return;
        }
        if (plugin.getTickManager().getPerformanceSnapshot().statsEnabled()) {
            player.sendMessage(I18n.getComponent("command.stats_profile_busy", player));
            return;
        }
        place(player, new String[]{args[0], "place", normalize(args[2]), count.toString(), "1", "1"}, true,
                () -> {
                    player.sendMessage(I18n.getComponent("command.debug_test_metrics", player));
                    profiles.start(player, ticks, "stove_blocked".equals(normalize(args[2])) ? "stove" : normalize(args[2]));
                });
    }

    private void testHandheld(Player player, int count, int ticks) {
        SkilletManager manager = plugin.getSkilletManager();
        ItemStack pan = ItemUtils.createItem(Constants.BLOCK_SKILLET);
        ItemStack ingredient = new ItemStack(Material.BEEF);
        CookingRecipe<?> recipe = manager == null ? null : manager.campfireRecipes().find(ingredient);
        if (pan == null || recipe == null) {
            player.sendMessage(I18n.getComponent("command.debug_test_handheld_unavailable", player));
            return;
        }
        var simulation = new HandheldSimulation(count, pan, ingredient, recipe.getCookingTime(),
                NamespacedKey.fromString("farmersdelight:skillet_cooking"),
                plugin.getConfigBoolean(true, "skillet.handheld.progress-display.enabled",
                        "skillet.handheld-progress-display.enabled"),
                item -> manager.campfireRecipes().find(item) == recipe, recipe::getResult);
        HandheldTest job = new HandheldTest(simulation);
        if (handheldTests.putIfAbsent(player.getUniqueId(), job) != null) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return;
        }
        try {
            job.task = plugin.scheduler().runRepeating(() -> {
                if (handheldTests.get(player.getUniqueId()) != job || !job.scheduled.compareAndSet(false, true)) return;
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try {
                            if (handheldTests.get(player.getUniqueId()) != job) return;
                            if (!player.isOnline()) {
                                discardHandheldTest(player.getUniqueId(), job);
                                return;
                            }
                            simulation.tick();
                            if (simulation.snapshot().ticks() >= ticks) finishHandheldTest(player, job);
                        } catch (RuntimeException e) {
                            discardHandheldTest(player.getUniqueId(), job);
                            plugin.getLogger().log(Level.WARNING, "Handheld simulation failed", e);
                            player.sendMessage(I18n.getComponent("command.debug_batch_failed", player,
                                    Map.of("count", String.valueOf(simulation.snapshot().ticks()))));
                        } finally {
                            job.scheduled.set(false);
                        }
                    }, () -> discardHandheldTest(player.getUniqueId(), job));
                } catch (RuntimeException e) {
                    discardHandheldTest(player.getUniqueId(), job);
                    plugin.getLogger().log(Level.WARNING, "Handheld simulation scheduling failed", e);
                }
            }, 1L, 1L);
            if (handheldTests.get(player.getUniqueId()) != job) job.task.cancel();
        } catch (RuntimeException e) {
            discardHandheldTest(player.getUniqueId(), job);
            throw e;
        }
        player.sendMessage(I18n.getComponent("command.debug_test_handheld_started", player,
                Map.of("count", String.valueOf(count), "ticks", String.valueOf(ticks))));
    }

    private boolean discardHandheldTest(UUID owner, HandheldTest job) {
        if (!handheldTests.remove(owner, job)) return false;
        if (job.task != null) job.task.cancel();
        return true;
    }

    private void finishHandheldTest(Player player, HandheldTest job) {
        if (!discardHandheldTest(player.getUniqueId(), job)) return;
        var result = job.simulation.snapshot();
        player.sendMessage(I18n.getComponent("command.debug_test_handheld_result", player, Map.of(
                "count", String.valueOf(result.sessions()), "ticks", String.valueOf(result.ticks()),
                "calls", String.valueOf(result.calls()), "displays", String.valueOf(result.displayCalls()),
                "completed", String.valueOf(result.completions()),
                "avg", String.format(Locale.ROOT, "%.3f", result.totalNanos() / 1_000_000.0 / Math.max(1, result.ticks())),
                "max", String.format(Locale.ROOT, "%.3f", result.maxTickNanos() / 1_000_000.0))));
    }

    private void stop(Player player) {
        boolean stopped = batches.stop(player);
        removeEmptyBatches(player.getUniqueId());
        stopped |= profiles.stop(player);
        HandheldTest job = handheldTests.get(player.getUniqueId());
        if (job != null) {
            finishHandheldTest(player, job);
            stopped = true;
        }
        player.sendMessage(I18n.getComponent(stopped
                ? "command.debug_batch_stopped" : "command.debug_batch_no_task", player));
    }

    private static final class HandheldTest {
        final HandheldSimulation simulation;
        final AtomicBoolean scheduled = new AtomicBoolean();
        volatile PluginTask task;

        HandheldTest(HandheldSimulation simulation) {
            this.simulation = simulation;
        }
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
        String target = normalize(args[2]);
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
        List<String> targets = activate ? activationTargets() : placementTargets();
        if (!"all".equals(target) && !targets.contains(target)) {
            player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player, Map.of("target", target)));
            return;
        }
        List<String> selected = "all".equals(target) ? targets : List.of(target);
        if (total < selected.size()) {
            player.sendMessage(I18n.getComponent("command.debug_all_minimum", player,
                    Map.of("count", String.valueOf(selected.size()))));
            return;
        }
        player.sendMessage(I18n.getComponent("command.debug_scope", player,
                Map.of("targets", String.join(", ", selected), "count", String.valueOf(total))));
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        boolean started = batches.start(player, total, index -> {
            int layerIndex = index % count;
            Location location = origin.clone().add(2 + layerIndex % grid * spacing,
                    2 + index / count * 3, 2 + layerIndex / grid * spacing);
            String selectedTarget = selected.get(index % selected.size());
            if (!isBuiltInTarget(selectedTarget)) {
                placeAddon(player, batch, location, selectedTarget);
                return;
            }
            if (!canEdit(player, location)) return;
            PlaceResult result = placeOne(player, location, selectedTarget);
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
            rememberBatch(batch);
            player.sendMessage(I18n.getComponent("command.debug_batch_started", player,
                    Map.of("count", String.valueOf(total))));
        } else {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    // Resolve complete inputs before making any world changes.
    private void placeRecipe(Player player, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sendUsage(player);
            return;
        }
        if (batches.busy()) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return;
        }
        String type = normalizeRecipeStation(args[2]);
        if (type == null) {
            player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player,
                    Map.of("target", normalize(args[2]))));
            return;
        }
        String recipeId = args[3];
        Integer requestedCount = parseOptionalInt(args, 4, 1);
        if (requestedCount == null || requestedCount <= 0) {
            player.sendMessage(I18n.getComponent("command.debug_batch_invalid_numbers", player));
            return;
        }
        int count = Math.min(getMaxPlaceCount(), requestedCount);

        List<RecipeChoice> all = recipeChoices(type);
        RecipeChoice exact = null;
        for (RecipeChoice choice : all) {
            if (choice.id().equals(recipeId) || choice.id().equals(normalize(recipeId))) {
                exact = choice;
                break;
            }
        }
        if (exact == null) {
            reportUnknownRecipe(player, type, recipeId, all);
            return;
        }
        // Refuse before placing anything: a setup whose ingredients cannot all be built would leave the
        // station empty (or half full) and misrepresent what the plugin matched.
        RecipeSetup exactSetup = resolveRecipeSetup(exact);
        if (exactSetup == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>Recipe <yellow>" + exact.id()
                    + "</yellow> is loaded but its ingredients no longer resolve to concrete items, so no"
                    + " station was placed.</red>"));
            return;
        }
        // The optional count places the same recipe the requested number of times, so several copies of one
        // station can be watched side by side. The one resolved setup is reused: resolving reads the recipe,
        // not the world, so every copy is identical to the one that was checked above.
        List<RecipeSetup> selected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) selected.add(exactSetup);
        placeRecipeSetups(player, type, all.size(), selected);
    }

    /**
     * placeRecipeRandom <cooking_pot|cutting_board|all> [count]: pick count distinct random recipes
     * uniformly from the loaded set of the chosen type and place one setup each, so a tester can cycle through
     * recipe matching quickly. The id and station of every placed setup are reported to be compared against the
     * plugin's own match. The count is clamped by the same max-place-count guard and by the number of loaded
     * recipes; a request larger than the pool places the pool and says so.
     */
    private void placeRecipeRandom(Player player, String[] args) {
        if (args.length < 3 || args.length > 4) {
            sendUsage(player);
            return;
        }
        if (batches.busy()) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return;
        }
        String type = normalizeRecipeStation(args[2]);
        if (type == null) {
            player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player,
                    Map.of("target", normalize(args[2]))));
            return;
        }
        Integer requestedCount = parseOptionalInt(args, 3, 1);
        if (requestedCount == null || requestedCount <= 0) {
            player.sendMessage(I18n.getComponent("command.debug_batch_invalid_numbers", player));
            return;
        }
        int count = Math.min(getMaxPlaceCount(), requestedCount);

        List<RecipeChoice> available = recipeChoices(type);
        int loaded = available.size();
        if (count > loaded) count = loaded;
        if (count <= 0) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>No " + type + " recipe is loaded, so nothing"
                    + " can be placed. Run /fd debugtools recipe validate to see load issues.</red>"));
            return;
        }
        // A uniform distinct draw that keeps only the recipes it takes: the loaded pool is read in place and
        // every later recipe replaces one of the kept slots with a decreasing probability, so the whole set is
        // never copied just to shuffle it. The placement order then comes from sorting the resolved setups.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<RecipeChoice> selected = new ArrayList<>(count);
        int seen = 0;
        for (RecipeChoice choice : available) {
            if (seen < count) {
                selected.add(choice);
            } else {
                int slot = random.nextInt(seen + 1);
                if (slot < count) {
                    selected.set(slot, choice);
                }
            }
            seen++;
        }
        // A random recipe that cannot be resolved is dropped from this run instead of aborting it, so the
        // distinct count actually placed can be lower than requested; the draw stays uniform over the recipes
        // that can be set up. Everything is resolved here, once, before the first station is placed, so a
        // partially resolvable draw never leaves an empty station behind and no recipe is read twice.
        List<RecipeSetup> resolvable = new ArrayList<>(selected.size());
        Set<String> dropped = null;
        for (RecipeChoice choice : selected) {
            RecipeSetup setup = resolveRecipeSetup(choice);
            if (setup == null) {
                if (dropped == null) dropped = new HashSet<>();
                dropped.add(choice.id());
            } else {
                resolvable.add(setup);
            }
        }
        if (resolvable.isEmpty()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>None of the " + selected.size()
                    + " randomly drawn recipe(s) resolve to concrete items, so nothing was placed.</red>"));
            return;
        }
        int placed = placeRecipeSetups(player, type, loaded, resolvable);
        if (placed == 0) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>No random recipe setup could be placed.</red>"));
            return;
        }
        if (placed < requestedCount) {
            player.sendMessage(MINI_MESSAGE.deserialize("<yellow>Placed " + placed + " random setup(s); "
                    + requestedCount + " requested, " + loaded + " recipe(s) for " + type + " loaded, "
                    + (dropped == null ? 0 : dropped.size())
                    + " drawn recipe(s) could not be resolved.</yellow>"));
        }
    }

    // Place first, then fill in bounded slices; both passes use the same captured positions.
    private int placeRecipeSetups(Player player, String type, int loaded, List<RecipeSetup> setups) {
        if (setups.isEmpty()) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>No recipe setup resolved, so nothing was"
                    + " placed.</red>"));
            return 0;
        }
        setups.sort(Comparator.comparing(RecipeSetup::recipeId));

        Location origin = ManagerSupport.normalize(player.getLocation());
        int grid = Math.max(1, (int) Math.ceil(Math.sqrt(setups.size())));
        List<RecipeSetup> placements = new ArrayList<>(setups.size());
        for (int slot = 0; slot < setups.size(); slot++) {
            RecipeSetup setup = setups.get(slot);
            placements.add(new RecipeSetup(recipeSetupLocation(origin, grid, slot), setup.recipeId(),
                    setup.target(), setup.ingredients(), setup.container(), setup.addon()));
        }

        PlacementBatch batch = new PlacementBatch(player.getUniqueId());
        List<RecipeSetup> live = new ArrayList<>();
        int[] skipped = {0};
        if (!batches.start(player, placements.size(), index -> {
            RecipeSetup setup = placements.get(index);
            if (!canEdit(player, setup.location())) {
                skipped[0]++;
                return;
            }
            if (!isBuiltInTarget(setup.target())) {
                int before = batch.placed;
                placeAddon(player, batch, setup.location(), setup.target());
                if (batch.placed > before) live.add(setup);
                else skipped[0]++;
                return;
            }
            PlaceResult result = placeOne(player, setup.location(), setup.target());
            batch.entries.addAll(result.undoEntries());
            if (result.placed()) {
                batch.placed++;
                live.add(setup);
            } else skipped[0]++;
        }, () -> {
            if (live.isEmpty()) {
                reportRecipeSummary(player, type, loaded, List.of(), skipped[0]);
                return;
            }
            fillRecipeSetups(player, type, loaded, live, skipped[0], batch);
        })) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return 0;
        }
        rememberBatch(batch);
        player.sendMessage(MINI_MESSAGE.deserialize("<green>Recipe setup</green> <gray>station=" + type
                + " loaded=" + loaded + " requested=" + placements.size() + "</gray>"));
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>Placing " + placements.size() + " station(s) in"
                + " slices of at most 16, then filling them the same way.</gray>"));
        return placements.size();
    }

    /** Second half of a recipe setup: fill each placed station with the recipe's exact ingredients. */
    private void fillRecipeSetups(Player player, String type, int loaded, List<RecipeSetup> setups, int skipped, PlacementBatch batch) {
        List<RecipeSetup> filled = new ArrayList<>();
        if (!batches.start(player, setups.size(), index -> {
            RecipeSetup setup = setups.get(index);
            if (!canEdit(player, setup.location())) return;
            UndoEntry entry = batch.entries.stream().filter(Objects::nonNull)
                    .filter(saved -> saved.location().equals(setup.location())).findFirst().orElse(null);
            if (!isStillOwned(entry)) return;
            if (fillRecipeStation(player, setup.location(), setup)) filled.add(setup);
            entry.after = currentState(setup.location());
        }, () -> reportRecipeSummary(player, type, loaded, filled, skipped + setups.size() - filled.size()))) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    // Cap per-station diagnostics; always include aggregate totals.
    private void reportRecipeSummary(Player player, String type, int loaded, List<RecipeSetup> setups, int skipped) {
        List<String> lines = new ArrayList<>(setups.size());
        for (RecipeSetup setup : setups) {
            Location location = setup.location();
            String coords = location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
            lines.add("<green>Verified</green> <yellow>" + setup.recipeId()
                    + "</yellow> <gray>station=" + setup.target() + " @ " + coords + " with "
                    + setup.ingredients().size() + " ingredient(s): " + ingredientSummary(setup) + "</gray>");
            if (setup.addon() != null && !setup.addon().hint().isEmpty()) {
                lines.add("<gray>" + MINI_MESSAGE.escapeTags(setup.addon().hint()) + "</gray>");
            }
        }
        diagnostics.sendCapped(player, lines, REPORT_CAP, "verified setup(s)");
        // One verdict line so a run is machine-readable at a glance: any unverified fill is a failure, not a
        // detail buried in the per-station list.
        player.sendMessage(I18n.getComponent(skipped == 0
                        ? "command.debug_recipe_verified" : "command.debug_recipe_unverified", player,
                Map.of("setups", String.valueOf(setups.size()), "skipped", String.valueOf(skipped),
                        "type", type, "loaded", String.valueOf(loaded))));
    }

    // Stable spacing keeps recipe stations and heat sources from overlapping.
    private static Location recipeSetupLocation(Location origin, int grid, int slot) {
        if (origin == null) return null;
        int spacing = RECIPE_GRID_SPACING;
        int column = slot % grid;
        int row = slot / grid;
        return origin.clone().add(2 + column * spacing, 2, 2 + row * spacing);
    }

    private void activate(Player player, String[] args) {
        String target = args.length >= 3 ? normalize(args[2]) : "all";
        if (args.length > 3 || !withAll(activationTargets()).contains(target)) {
            player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player, Map.of("target", target)));
            return;
        }
        PlacementBatch batch = lastBatch(player);
        if (batch != null) activateBatch(player, batch, target, () -> { });
    }

    private void undo(Player player) {
        PlacementBatch batch = lastBatch(player);
        if (batch == null) return;
        int[] restored = {0};
        int[] skipped = {0};
        if (!batches.start(player, batch.entries.size(), index -> {
            int entryIndex = batch.entries.size() - 1 - index;
            UndoEntry entry = batch.entries.get(entryIndex);
            if (entry == null || !canEdit(player, entry.location())) return;
            removeGeneratedEntity(entry);
            if (!isStillOwned(entry)) {
                batch.entries.set(entryIndex, null);
                skipped[0]++;
                return;
            }
            restoreUndoEntry(entry);
            batch.entries.set(entryIndex, null);
            restored[0]++;
        }, () -> {
            batch.entries.removeIf(Objects::isNull);
            removeEmptyBatches(player.getUniqueId());
            player.sendMessage(I18n.getComponent("command.debug_batch_undone", player,
                    Map.of("count", String.valueOf(restored[0]), "remaining", String.valueOf(batch.entries.size()))));
            if (skipped[0] > 0) player.sendMessage(I18n.getComponent("command.debug_undo_skipped", player,
                    Map.of("count", String.valueOf(skipped[0]))));
        })) player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
    }

    private void rememberBatch(PlacementBatch batch) {
        synchronized (undoHistory) {
            Deque<PlacementBatch> history = undoHistory.computeIfAbsent(batch.owner, ignored -> new ArrayDeque<>());
            history.addFirst(batch);
            while (history.size() > UNDO_HISTORY_LIMIT) history.removeLast();
        }
    }

    private void removeEmptyBatches(UUID owner) {
        synchronized (undoHistory) {
            Deque<PlacementBatch> history = undoHistory.get(owner);
            if (history == null) return;
            // Active undo slices retain their indices until completion.
            history.removeIf(batch -> batch.entries.stream().allMatch(Objects::isNull));
            if (history.isEmpty()) undoHistory.remove(owner);
        }
    }

    private PlacementBatch lastBatch(Player player) {
        removeEmptyBatches(player.getUniqueId());
        synchronized (undoHistory) {
            Deque<PlacementBatch> history = undoHistory.get(player.getUniqueId());
            if (history != null && !history.isEmpty()) return history.getFirst();
        }
        player.sendMessage(I18n.getComponent("command.debug_batch_empty", player));
        return null;
    }

    private void activateBatch(Player player, PlacementBatch batch, String target, Runnable finished) {
        int[] activated = {0};
        int[] failed = {0};
        if (!batches.start(player, batch.activations.size(), index -> {
            PendingActivation activation = batch.activations.get(index);
            if (!("all".equals(target) || target.equals(activation.target()))
                    || !canEdit(player, activation.location())) return;
            UndoEntry owned = batch.entries.stream().filter(Objects::nonNull)
                    .filter(entry -> entry.location().equals(activation.location()) && isStillOwned(entry)).findFirst().orElse(null);
            if (owned == null) return;
            if ("basket".equals(activation.target())) {
                if (owned.generatedEntity != null) return;
                owned.generatedEntity = activation.location().getWorld().dropItem(
                        activation.location().clone().add(0.5, 0.5, 0.5), new ItemStack(Material.WHEAT_SEEDS, 16));
                activated[0]++;
            } else if (isBuiltInTarget(activation.target())) {
                // Count only a verified activation: the counter must move after the station actually changed
                // state, or a failed reflection write reads as a success.
                if (activateScheduled(activation)) activated[0]++;
                else failed[0]++;
            } else {
                DebugToolExtension extension = DebugToolRegistry.find(activation.target());
                if (extension != null && extension.supportsActivation()) {
                    activated[0] += extension.activateAt(player, activation.location(), this::resolveDebugItem);
                }
            }
            for (UndoEntry entry : batch.entries) {
                if (entry != null && entry.location().equals(activation.location())) entry.after = currentState(entry.location());
            }
        }, () -> {
            player.sendMessage(I18n.getComponent("command.debug_batch_activated", player,
                    Map.of("count", String.valueOf(activated[0]), "failed", String.valueOf(failed[0]))));
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
        if (extension == null || !extension.supportsPlacement()) return;
        List<UndoEntry> captured = new ArrayList<>();
        try {
            batch.placed += extension.place(player, location.clone().subtract(0, 1, 0), 1, 1, 1, loc -> {
                if (!canEdit(player, loc) || !canReplace(loc.getBlock())) {
                    throw new IllegalStateException("Addon debug placement must use an empty, editable cell");
                }
                captured.add(captureUndo(loc));
            });
        } finally {
            for (UndoEntry entry : captured) {
                rememberIfChanged(batch.entries, entry);
                if (entry != null && entry.after != null && extension.supportsActivation()) batch.activations.add(new PendingActivation(entry.location(), target));
            }
        }
    }

    private PlaceResult placeOne(Player player, Location location, String target) {
        boolean placed = false;
        boolean activated = false;
        List<UndoEntry> undoEntries = new ArrayList<>(3);
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
            activated = placed;
        }
        return new PlaceResult(placed, activated, activated ? target : null, undoEntries);
    }

    // Every loaded recipe the chosen station can run, in the manager's own order. "all" reads both managers;
    // each recipe still carries its own station, so the caller never has to know which one it came from. This is
    // the one place the loaded set is enumerated, and each command hands the result down instead of rebuilding it.
    private List<RecipeChoice> recipeChoices(String type) {
        List<RecipeChoice> choices = new ArrayList<>();
        if (plugin != null && ("all".equals(type) || isCookingPotTarget(type))) {
            CookingPotRecipeManager manager = plugin.getCookingPotRecipes();
            if (manager != null) {
                for (CookingPotRecipe recipe : manager.getAllRecipes()) {
                    choices.add(new RecipeChoice(recipe.getId(), "cooking_pot", recipe));
                }
            }
        }
        if (plugin != null && ("all".equals(type) || isCuttingBoardTarget(type))) {
            CuttingBoardRecipeManager manager = plugin.getCuttingBoardRecipes();
            if (manager != null) {
                for (CuttingBoardRecipe recipe : manager.getSortedRecipes()) {
                    choices.add(new RecipeChoice(recipe.getId(), "cutting_board", recipe));
                }
            }
        }
        for (String target : recipeTargets()) {
            if (!("all".equals(type) || target.equals(type))) continue;
            DebugToolExtension extension = DebugToolRegistry.find(target);
            if (extension == null) continue;
            for (String id : extension.recipeIds()) choices.add(new RecipeChoice(id, target, extension));
        }
        return choices;
    }

    /** Canonical station name, or null when the argument is not one of the two recipe stations. */
    private String normalizeRecipeStation(String value) {
        String normalized = normalize(value);
        return withAll(recipeTargets()).contains(normalized) ? normalized : null;
    }

    /**
     * Reports an unknown recipe id with the size of the loaded set and the closest ids as a hint. The set can
     * be large, so only the first few matches are printed.
     */
    private void reportUnknownRecipe(Player player, String type, String requested, List<RecipeChoice> candidates) {
        player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown recipe id: <yellow>" + requested + "</yellow></red>"
                + " <gray>(" + candidates.size() + " " + type + " recipe(s) loaded)</gray>"));
        if (candidates.isEmpty()) {
            return;
        }
        String needle = normalize(requested);
        List<String> closest = new ArrayList<>();
        for (RecipeChoice choice : candidates) {
            if (normalize(choice.id()).contains(needle)) closest.add(choice.id());
        }
        if (closest.isEmpty()) {
            for (RecipeChoice choice : candidates) {
                for (String part : needle.split("[^a-z0-9]+")) {
                    if (part.length() >= 3 && normalize(choice.id()).contains(part)) {
                        closest.add(choice.id());
                        break;
                    }
                }
            }
        }
        if (closest.isEmpty()) {
            for (int index = 0; index < Math.min(5, candidates.size()); index++) {
                closest.add(candidates.get(index).id());
            }
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>No close match. First loaded ids: "
                    + String.join(", ", closest) + "</gray>"));
            return;
        }
        List<String> shown = closest.size() > 5 ? closest.subList(0, 5) : closest;
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>Closest matches: " + String.join(", ", shown)
                + (closest.size() > shown.size() ? " (+" + (closest.size() - shown.size()) + " more)" : "")
                + "</gray>"));
    }

    private List<String> recipeIds(List<RecipeChoice> choices) {
        List<String> ids = new ArrayList<>(choices.size());
        for (RecipeChoice choice : choices) ids.add(choice.id());
        return ids;
    }

    // Resolve concrete inputs using the station's own matcher.
    private RecipeSetup resolveRecipeSetup(RecipeChoice choice) {
        if (choice.recipe() instanceof DebugToolExtension extension) {
            DebugToolExtension.RecipeSetup resolved = extension.recipe(choice.id(), this::resolveDebugItem);
            return resolved == null ? null : new RecipeSetup(null, choice.id(), choice.station(),
                    resolved.ingredients(), null, resolved);
        }
        if (choice.recipe() instanceof CuttingBoardRecipe recipe) {
            ItemStack input = recipe.getInputDisplay();
            if (input == null || input.getType().isAir()) return null;
            return new RecipeSetup(null, recipe.getId(), "cutting_board", List.of(), input, null);
        }
        if (choice.recipe() instanceof CookingPotRecipe recipe) {
            CookingPotRecipeManager manager = plugin.getCookingPotRecipes();
            List<ItemStack> ingredients = new ArrayList<>(recipe.getIngredients().size());
            for (RecipeIngredient ingredient : recipe.getIngredients()) {
                ItemStack resolved = resolveIngredient(ingredient,
                        manager == null ? null : manager::matchesIngredient);
                if (resolved == null) return null;
                ingredients.add(resolved);
            }
            if (ingredients.isEmpty()) return null;
            // needsContainer with an unresolvable container is exactly the case that would place a pot that can
            // never finish the recipe, so it refuses the setup rather than dropping the container silently.
            ItemStack container = recipe.getNeedsContainer() ? recipe.getContainer() : null;
            if (recipe.getNeedsContainer() && (container == null || container.getType().isAir())) return null;
            return new RecipeSetup(null, recipe.getId(), "cooking_pot", List.copyOf(ingredients), container, null);
        }
        return null;
    }

    // Prefer CE tag members, then vanilla and family tags; every candidate must match.
    private ItemStack resolveIngredient(RecipeIngredient ingredient, BiPredicate<ItemStack, RecipeIngredient> matcher) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            return stack == null || stack.getType().isAir() ? null : stack;
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) {
                ItemStack resolved = resolveIngredient(option, matcher);
                if (resolved != null) return resolved;
            }
            return null;
        }
        if (!(ingredient instanceof RecipeIngredient.Tag tag)) return null;

        try {
            var craftEngine = plugin.getCraftEngine();
            if (craftEngine != null && craftEngine.itemManager() != null) {
                for (var member : craftEngine.itemManager().itemIdsByTag(tag.key())) {
                    ItemStack stack = ItemUtils.createItem(member.toString());
                    if (accepted(stack, ingredient, matcher)) return stack;
                }
            }
            CookingPotRecipeManager potManager = plugin.getCookingPotRecipes();
            if (potManager != null) {
                for (String vanillaId : potManager.getVanillaItemIdsByTag(tag.key())) {
                    ItemStack stack = ItemUtils.createItem(vanillaId);
                    if (accepted(stack, ingredient, matcher)) return stack;
                }
            }
            for (String memberId : CommonTagResolver.getMembers(tag.key())) {
                ItemStack stack = ItemUtils.createItem(memberId);
                if (accepted(stack, ingredient, matcher)) return stack;
            }
        } catch (RuntimeException | LinkageError notResolvable) {
            return null;
        }
        return null;
    }

    private ItemStack resolveDebugItem(String spec) {
        if (spec == null || spec.isBlank()) return null;
        try {
            RecipeIngredient ingredient = spec.startsWith("#")
                    ? new RecipeIngredient.Tag(Key.of(spec.substring(1)))
                    : new RecipeIngredient.Item(Key.of(spec));
            CookingPotRecipeManager manager = plugin == null ? null : plugin.getCookingPotRecipes();
            return resolveIngredient(ingredient, manager == null ? null : manager::matchesIngredient);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private boolean accepted(ItemStack stack, RecipeIngredient ingredient,
                             BiPredicate<ItemStack, RecipeIngredient> matcher) {
        return stack != null && !stack.getType().isAir() && matcher != null && matcher.test(stack, ingredient);
    }

    // Fill the operation-owned station with the inputs resolved before placement.
    private boolean fillRecipeStation(Player player, Location location, RecipeSetup setup) {
        if (setup.addon() != null) return setup.addon().fill().test(player, location);
        if ("cutting_board".equals(setup.target())) {
            CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(
                    location.getWorld(), new BlockPosKey(location));
            if (entity == null || setup.container() == null) return false;
            entity.setItem(setup.container().clone(), location.getWorld(), new BlockPosKey(location),
                    CustomBlockUtils.getFacing(location.getBlock()), false);
            CuttingBoardBlockBehavior.saveBlockEntityData(location.getWorld(), new BlockPosKey(location));
            return true;
        }
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        if (entity == null) return false;
        for (ItemStack ingredient : setup.ingredients()) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            ItemStack stack = ingredient.clone();
            stack.setAmount(Math.max(1, stack.getAmount()));
            entity.insertIngredientStack(stack);
        }
        if (setup.container() != null && !setup.container().getType().isAir()) {
            ItemStack container = setup.container().clone();
            container.setAmount(1);
            entity.insertContainerStack(container);
            entity.setMealContainer(container.clone());
        }
        BlockPosKey posKey = new BlockPosKey(location);
        saveCookingPotData(location, entity, posKey);
        markCookingPotActive(location.getWorld(), posKey);
        syncCookingPotTray(location);
        // Verify the insert actually resolved to the intended recipe: tag membership and amounts can make a
        // "complete" insert match a different recipe or none, and an unverified insert would report a fill the
        // station never accepted.
        entity.canCook();
        CookingPotRecipe matched = entity.getCurrentRecipe();
        if (matched == null || !setup.recipeId().equals(matched.id())) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_recipe_mismatch",
                    "location", ManagerSupport.formatLocation(location),
                    "expected", setup.recipeId(),
                    "actual", matched == null ? "(none)" : matched.id()));
            return false;
        }
        return true;
    }

    /** The item names a setup was filled with, in recipe-ingredient order, for the report line. */
    private String ingredientSummary(RecipeSetup setup) {
        List<ItemStack> stacks = new ArrayList<>();
        if ("cutting_board".equals(setup.target())) {
            if (setup.container() != null) stacks.add(setup.container());
        } else {
            stacks.addAll(setup.ingredients());
            if (setup.container() != null) stacks.add(setup.container());
        }
        if (stacks.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ItemStack stack : stacks) {
            String id = ItemUtils.resolveItemId(stack);
            if (builder.length() > 0) builder.append(", ");
            builder.append(id == null ? stack.getType().name() : id);
        }
        return builder.toString();
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

    private void removeGeneratedEntity(UndoEntry entry) {
        Entity entity = entry.generatedEntity;
        if (entity != null) {
            plugin.scheduler().runForEntity(entity, entity::remove, () -> { });
            entry.generatedEntity = null;
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
            entry.after = currentState(entry.location());
            entries.add(entry);
        }
    }

    private DebugUndoGuard.State currentState(Location at) {
        Block block = at.getBlock();
        return new DebugUndoGuard.State(CustomBlockUtils.getId(block), block.getBlockData().getAsString(),
                CraftEngineBlockAccess.blockEntityController(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }

    private boolean isStillOwned(UndoEntry entry) {
        return entry != null && DebugUndoGuard.stillOwned(entry.after, currentState(entry.location()));
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
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().log(Level.WARNING, "Debug undo addon cleanup failed at " + location, failure);
                throw failure;
            }
        }
    }

    private boolean activateScheduled(PendingActivation activation) {
        if (activation == null) {
            return false;
        }
        Location location = activation.location();
        String target = activation.target();
        if (location == null || location.getWorld() == null) {
            return false;
        }

        if ("cooking_pot".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT)) {
            return activateCookingPot(location, true);
        }
        if ("skillet".equals(target) && isPlacedCustomBlock(location, Constants.BLOCK_SKILLET)) {
            return activateSkillet(location);
        }
        if (("stove".equals(target) || "stove_blocked".equals(target)) && isPlacedCustomBlock(location, Constants.BLOCK_STOVE)) {
            return activateStove(location);
        }
        if ("cooking_pot".equals(target)) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_activation_skipped",
                    "location", ManagerSupport.formatLocation(location),
                    "id", CustomBlockUtils.getId(location.getBlock())));
        }
        return false;
    }

    // Only stations captured in the player's placement batch can reach this mutation.
    private boolean activateCookingPot(Location location, boolean verify) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        applyCookingPotDebugState(entity, location);
        BlockPosKey posKey = new BlockPosKey(location);
        saveCookingPotData(location, entity, posKey);
        markCookingPotActive(location.getWorld(), posKey);
        syncCookingPotTray(location);
        return !verify || verifyCookingPotFilled(location);
    }

    private void syncCookingPotTray(Location location) {
        if (location == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().checkAndPlaceTray(location);
    }

    private boolean verifyCookingPotFilled(Location location) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(location);
        if (entity == null) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_no_entity",
                    "location", ManagerSupport.formatLocation(location)));
            return false;
        }
        if (!entity.hasStoredContents() || !entity.hasInput()) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_empty",
                    "location", ManagerSupport.formatLocation(location)));
            return false;
        }
        if (!entity.canCook()) {
            plugin.getLogger().warning(I18n.formatConsole("debug.cooking_pot_no_recipe",
                    "location", ManagerSupport.formatLocation(location)));
            return false;
        }
        return true;
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

    private boolean activateSkillet(Location location) {
        SkilletManager manager = plugin.getSkilletManager();
        if (manager == null) {
            return false;
        }
        ItemStack skillet = item(Constants.ITEM_SKILLET, 1);
        manager.recordPlacedSkillet(location, skillet);

        ItemStack food = item("minecraft:beef", 16);
        if (!manager.canCook(food)) {
            food = item("minecraft:porkchop", 16);
        }
        return setSkilletStoredItem(manager, location, food);
    }

    private boolean activateStove(Location location) {
        StoveManager manager = plugin.getStoveManager();
        if (manager == null || location == null || location.getWorld() == null) {
            return false;
        }
        if (!isStoveLit(location)) {
            return false;
        }

        Object stove = manager.getOrCreateStove(location);
        ItemStack food = item("minecraft:beef", 1);
        if (!manager.canCook(food)) {
            food = item("minecraft:porkchop", 1);
        }
        if (food == null || food.getType().isAir()) {
            return false;
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
            // The reflective write is the only proof; count only a slot that actually holds the food now.
            return items.length > 0 && items[0] != null && !items[0].getType().isAir();
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning(I18n.formatConsole("debug.stove_activation_failed",
                    "location", location,
                    "error", e.getMessage()));
            return false;
        }
    }

    private boolean setSkilletStoredItem(SkilletManager manager, Location location, ItemStack food) {
        if (manager == null || location == null || food == null || food.getType().isAir()) {
            return false;
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
            // Verify through the manager's public snapshot, not another reflective field read.
            ItemStack stored = manager.getStoredItemSnapshot(location);
            return stored != null && !stored.getType().isAir();
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().warning(I18n.formatConsole("debug.skillet_activation_failed",
                    "location", location,
                    "error", e.getMessage()));
            return false;
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
        return "cutting_board".equals(target);
    }

    private boolean isBasketTarget(String target) {
        return "basket".equals(target);
    }

    private boolean isCookingPotTarget(String target) {
        return "cooking_pot".equals(target);
    }

    private boolean isSkilletTarget(String target) {
        return "skillet".equals(target);
    }

    private boolean isStoveTarget(String target) {
        return "stove".equals(target);
    }

    private boolean isBlockedStoveTarget(String target) {
        return "stove_blocked".equals(target);
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

    private int getMaxPlaceCount() {
        return Math.max(1, plugin.getConfig().getInt(CONFIG_MAX_PLACE_COUNT, DEFAULT_MAX_PLACE_COUNT));
    }

    private void sendUsage(CommandSender sender) {
        for (String topic : List.of("test", "profile", "place", "place_recipe", "place_random", "activate",
                "undo", "stop", "inspect", "item", "recipe", "i18n")) {
            sender.sendMessage(I18n.getComponent("command.debug_usage_" + topic));
        }
        sender.sendMessage(I18n.getComponent("command.debug_capabilities", Map.of(
                "place", String.join(", ", placementTargets()),
                "activate", String.join(", ", activationTargets()),
                "recipe", String.join(", ", recipeTargets()))));
    }

    private record PlaceResult(boolean placed, boolean activated, String activationTarget, List<UndoEntry> undoEntries) {
    }

    private record PendingActivation(Location location, String target) {
    }

    /**
     * One loaded recipe, kept with the station it belongs to so a random draw across both managers still knows
     * which block to place.
     */
    private record RecipeChoice(String id, String station, Object recipe) {
    }

    /**
     * A recipe read into placement form: the station to place and the exact stack(s) to load into it. The
     * location is filled in when the setup is given a grid slot; ingredients is the pot's ingredient
     * list (empty for a board) and container is the pot's required container — the board's input item
     * is carried in container too, since a board stores exactly one item.
     */
    private record RecipeSetup(Location location, String recipeId, String target, List<ItemStack> ingredients,
                               ItemStack container, DebugToolExtension.RecipeSetup addon) {
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

    private static final class UndoEntry {
        private final Location location;
        private final BlockData blockData;
        private final Material vanillaFallback;
        private final String blockId;
        private DebugUndoGuard.State after;
        private Entity generatedEntity;

        UndoEntry(Location location, BlockData blockData, Material vanillaFallback, String blockId) {
            this.location = location;
            this.blockData = blockData;
            this.vanillaFallback = vanillaFallback;
            this.blockId = blockId;
        }
        Location location() { return location; }
        BlockData blockData() { return blockData; }
        Material vanillaFallback() { return vanillaFallback; }
        String blockId() { return blockId; }
    }

}
