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

/**
 * The /fd debugtools command set (test/place/placeRecipe/placeRecipeRandom/activate/undo/stop/
 * inspect/item/recipe/i18n), reached through the reflective command.DebugToolsSubCommand shim. This
 * source set is compiled into the plugin only when the build passes -PdebugTools=true, so a main-side
 * rename or removal that this class calls into is caught by that build alone.
 *
 * It reads one key that no shipped config.yml defines:
 * CONFIG_MAX_PLACE_COUNT caps how many blocks a single place, test or recipe-setup
 * run may create, falling back to DEFAULT_MAX_PLACE_COUNT. The debug and release builds package the
 * same src/main/resources/config.yml, so a debug-only entry would ship to every server and is
 * deliberately not added there; set the key by hand in config.yml to raise or lower the bound. The
 * key is read with getConfig().getInt(key, default), so an absent key always means the default, and
 * tools/check_config_paths.py scans this source set too.
 */
public final class DebugToolsCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int DEFAULT_MAX_PLACE_COUNT = 4096;
    // Optional operator knob for the debug build only; absent from the shipped config on purpose (see the
    // class comment). Read with getConfig().getInt(key, default), so an absent key always means the default.
    private static final String CONFIG_MAX_PLACE_COUNT = "debug-tools.max-place-count";
    private static final List<String> TEST_TARGETS = List.of("cooking_pot", "skillet", "stove", "handheld", "all");
    private static final List<String> ACTIONS = List.of("test", "profile", "place", "activate", "undo", "stop", "inspect", "item", "recipe", "i18n");
    private static final List<String> TARGETS = List.of("cooking_pot", "skillet", "stove", "stove_blocked",
            "cutting_board", "basket", "all");
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

        args = normalizePlacementArguments(args);
        switch (normalize(args[1])) {
            case "test" -> test(player, args);
            case "profile" -> profile(player, args);
            case "place" -> place(player, args);
            case "placerecipe", "place_recipe", "place-recipe" -> placeRecipe(player, args);
            case "placereciperandom", "place_random_recipe", "place-recipe-random" -> placeRecipeRandom(player, args);
            case "activate" -> activate(player, args);
            case "undo" -> undo(player);
            case "stop" -> stop(player);
            case "inspect", "look" -> diagnostics.inspect(player, args);
            case "item", "hand", "held" -> diagnostics.dumpHeldItem(player, args);
            case "recipe", "recipes" -> diagnostics.recipeValidate(player);
            case "i18n", "lang" -> diagnostics.i18nResolve(player, args);
            default -> sendUsage(player);
        }
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 3 && "place".equals(normalize(args[1]))) {
            List<String> targets = new ArrayList<>(TARGETS);
            targets.addAll(List.of("recipe", "random"));
            targets.addAll(DebugToolRegistry.registeredNames());
            return complete(targets, args[2]);
        }
        args = normalizePlacementArguments(args);
        if (args.length == 2) {
            return complete(ACTIONS, args[1]);
        }
        if (args.length == 3) {
            String action = normalize(args[1]);
            if ("test".equals(action)) return complete(TEST_TARGETS, args[2]);
            if ("profile".equals(action)) return complete(List.of("serialization"), args[2]);
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
            if (isPlaceRecipeAction(action) || isPlaceRecipeRandomAction(action)) {
                return complete(RECIPE_STATIONS, args[2]);
            }
            List<String> targets = new ArrayList<>(TARGETS);
            targets.addAll(DebugToolRegistry.registeredNames());
            return complete(targets, args[2]);
        }
        if (args.length == 4 && (isPlaceRecipeAction(normalize(args[1])) || isPlaceRecipeRandomAction(normalize(args[1])))) {
            if (isPlaceRecipeRandomAction(normalize(args[1]))) {
                return complete(List.of("1", "4", "16", "64"), args[3]);
            }
            List<String> ids = recipeIds(recipeChoices(normalize(args[2])));
            return complete(ids, args[3]);
        }
        if (args.length == 4 && "profile".equals(normalize(args[1]))) {
            return complete(List.of("200", "600", "1200"), args[3]);
        }
        if (args.length == 4 && ("test".equals(normalize(args[1])) || "place".equals(normalize(args[1])))) {
            return complete(List.of("16", "64", "128", "512", "1024"), args[3]);
        }
        if (args.length == 5 && "test".equals(normalize(args[1]))) {
            return complete(List.of("100", "200", "600", "1200"), args[4]);
        }
        return List.of();
    }

    static String[] normalizePlacementArguments(String[] args) {
        if (args.length < 3 || !"place".equalsIgnoreCase(args[1])) return args;
        String action;
        if ("recipe".equalsIgnoreCase(args[2])) {
            action = "place_recipe";
        } else if ("random".equalsIgnoreCase(args[2])) {
            action = "place_random_recipe";
        } else {
            return args;
        }
        String[] normalized = new String[args.length - 1];
        normalized[0] = args[0];
        normalized[1] = action;
        System.arraycopy(args, 3, normalized, 2, args.length - 3);
        return normalized;
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
        Integer ticks = parseOptionalInt(args, 4, 200);
        if (args.length < 3 || args.length > 5 || !TEST_TARGETS.contains(normalize(args[2]))
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
                () -> profiles.start(player, ticks, normalize(args[2])));
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
            rememberBatch(batch);
            player.sendMessage(I18n.getComponent("command.debug_batch_started", player,
                    Map.of("count", String.valueOf(total))));
        } else {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    /**
     * placeRecipe <cooking_pot|cutting_board|all> <recipeId> [count]: place one recipe's station and
     * fill it with exactly the ingredients that recipe declares, so a tester can watch whether the plugin's own
     * matcher picks the same recipe. Passed through DebugBatchRunner — first the stations, then the
     * fills, both bounded — and every block is checked with canEdit() before it is touched. A recipe
     * that cannot be resolved into concrete item stacks is refused outright, never half-placed.
     */
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

    /**
     * Places the stations of the resolved recipes and then fills them, both through the batch runner. The
     * station pass owns the block writes (and their undo entries), the fill pass owns the block-entity writes;
     * chaining the two keeps each slice inside the runner's budget instead of making one slice place and fill
     * at once. Returns how many setups were attempted, so the caller can report a shortfall.
     *
     * The setups arrive already resolved, so no recipe is read a second time here, and the caller's list is
     * sorted in place before a grid slot is assigned to each setup. Every location exists before the first
     * slice, so the station and fill passes cannot disagree about where the block went, and a position that
     * turns out to be uneditable only skips itself.
     */
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
                    setup.target(), setup.ingredients(), setup.container()));
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
            PlaceResult result = placeOne(player, setup.location(), setup.target(), 0);
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
        if (!batches.start(player, setups.size(), index -> {
            RecipeSetup setup = setups.get(index);
            if (!canEdit(player, setup.location())) return;
            UndoEntry entry = batch.entries.stream().filter(Objects::nonNull)
                    .filter(saved -> saved.location().equals(setup.location())).findFirst().orElse(null);
            if (!isStillOwned(entry)) return;
            fillRecipeStation(player, setup.location(), setup);
            entry.after = currentState(setup.location());
        }, () -> reportRecipeSummary(player, type, loaded, setups, skipped))) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    // Cap per-station diagnostics; always include aggregate totals.
    private void reportRecipeSummary(Player player, String type, int loaded, List<RecipeSetup> setups, int skipped) {
        List<String> lines = new ArrayList<>(setups.size());
        for (RecipeSetup setup : setups) {
            Location location = setup.location();
            String coords = location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
            lines.add("<green>Filled</green> <yellow>" + setup.recipeId()
                    + "</yellow> <gray>station=" + setup.target() + " @ " + coords + " with "
                    + setup.ingredients().size() + " ingredient(s): " + ingredientSummary(setup) + "</gray>");
        }
        diagnostics.sendCapped(player, lines, REPORT_CAP, "placed setup(s)");
        player.sendMessage(MINI_MESSAGE.deserialize("<gray>Recipe setups placed: " + setups.size()
                + ", skipped positions=" + skipped + ", loaded recipes for " + type + "=" + loaded + "</gray>"));
    }

    // Stable spacing keeps recipe stations and heat sources from overlapping.
    private static Location recipeSetupLocation(Location origin, int grid, int slot) {
        if (origin == null) return null;
        int spacing = RECIPE_GRID_SPACING;
        int column = slot % grid;
        int row = slot / grid;
        return origin.clone().add(2 + column * spacing, 2 + row * spacing, 2 + row * spacing);
    }

    private void activate(Player player, String[] args) {
        String target = args.length >= 3 ? normalizeTarget(args[2]) : "all";
        if (!isBuiltInTarget(target) && DebugToolRegistry.find(target) == null) {
            player.sendMessage(I18n.getComponent("command.debug_batch_unknown", player, Map.of("target", target)));
            return;
        }
        PlacementBatch batch = lastBatch(player);
        if (batch != null) activateBatch(player, batch, target, () -> { });
    }

    private boolean isPlaceRecipeAction(String action) {
        return "placerecipe".equals(action) || "place_recipe".equals(action) || "place-recipe".equals(action);
    }

    private boolean isPlaceRecipeRandomAction(String action) {
        return "placereciperandom".equals(action) || "place_random_recipe".equals(action)
                || "place-recipe-random".equals(action) || "place_random".equals(action);
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
        if (!batches.start(player, batch.activations.size(), index -> {
            PendingActivation activation = batch.activations.get(index);
            if (!("all".equals(target) || target.equals(activation.target()))
                    || !canEdit(player, activation.location())) return;
            boolean stillPresent = batch.entries.stream().filter(Objects::nonNull)
                    .anyMatch(entry -> entry.location().equals(activation.location()) && isStillOwned(entry));
            if (!stillPresent) return;
            if (isBuiltInTarget(activation.target())) {
                activateScheduled(activation);
                activated[0]++;
            } else {
                DebugToolExtension extension = DebugToolRegistry.find(activation.target());
                if (extension != null && !plugin.scheduler().isFolia()) {
                    activated[0] += extension.activateAt(player, activation.location());
                }
            }
            for (UndoEntry entry : batch.entries) {
                if (entry != null && entry.location().equals(activation.location())) entry.after = currentState(entry.location());
            }
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
            for (UndoEntry entry : captured) {
                rememberIfChanged(batch.entries, entry);
                if (entry != null && entry.after != null) batch.activations.add(new PendingActivation(entry.location(), target));
            }
        }
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

    // Every loaded recipe the chosen station can run, in the manager's own order. "all" reads both managers;
    // each recipe still carries its own station, so the caller never has to know which one it came from. This is
    // the one place the loaded set is enumerated, and each command hands the result down instead of rebuilding it.
    private List<RecipeChoice> recipeChoices(String type) {
        List<RecipeChoice> choices = new ArrayList<>();
        if ("all".equals(type) || isCookingPotTarget(type)) {
            CookingPotRecipeManager manager = plugin.getCookingPotRecipes();
            if (manager != null) {
                for (CookingPotRecipe recipe : manager.getAllRecipes()) {
                    choices.add(new RecipeChoice(recipe.getId(), "cooking_pot", recipe));
                }
            }
        }
        if ("all".equals(type) || isCuttingBoardTarget(type)) {
            CuttingBoardRecipeManager manager = plugin.getCuttingBoardRecipes();
            if (manager != null) {
                for (CuttingBoardRecipe recipe : manager.getSortedRecipes()) {
                    choices.add(new RecipeChoice(recipe.getId(), "cutting_board", recipe));
                }
            }
        }
        return choices;
    }

    /** Canonical station name, or null when the argument is not one of the two recipe stations. */
    private String normalizeRecipeStation(String value) {
        String normalized = normalizeTarget(value);
        if ("all".equals(normalized)) return "all";
        if (isCookingPotTarget(normalized)) return "cooking_pot";
        if (isCuttingBoardTarget(normalized)) return "cutting_board";
        return null;
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

    /**
     * Reads one recipe into everything a placement needs, or null when it cannot be read. This never touches
     * the world: the caller resolves the whole selection first, so a recipe that cannot be set up refuses the
     * command instead of half-placing a station. Each station's own manager decides whether a candidate item
     * satisfies an ingredient, so the stack placed here is one that station's matcher accepts.
     */
    private RecipeSetup resolveRecipeSetup(RecipeChoice choice) {
        if (choice.recipe() instanceof CuttingBoardRecipe recipe) {
            ItemStack input = recipe.getInputDisplay();
            if (input == null || input.getType().isAir()) return null;
            return new RecipeSetup(null, recipe.getId(), "cutting_board", List.of(), input);
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
            return new RecipeSetup(null, recipe.getId(), "cooking_pot", List.copyOf(ingredients), container);
        }
        return null;
    }

    /**
     * The concrete stack one ingredient accepts: the ingredient's own item, else a CraftEngine tag member that
     * the station's manager confirms matches, else a vanilla tag member, else a common-tag member. Null when
     * nothing resolves.
     */
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

    private boolean accepted(ItemStack stack, RecipeIngredient ingredient,
                             BiPredicate<ItemStack, RecipeIngredient> matcher) {
        return stack != null && !stack.getType().isAir() && matcher != null && matcher.test(stack, ingredient);
    }

    /**
     * The per-block half of place: the same station write place/activate makes, without
     * the batch-busy guard, so the recipe flows can chain station placement and filling through the runner. The
     * caller has already checked canEdit for this location.
     */
    private boolean isRecipeStation(Location location, RecipeSetup setup) {
        return "cutting_board".equals(setup.target())
                ? isPlacedCustomBlock(location, Constants.BLOCK_CUTTING_BOARD)
                : isPlacedCustomBlock(location, Constants.BLOCK_COOKING_POT);
    }

    /**
     * The fill half: insert the recipe's exact ingredient stacks, and its required container for a pot. Reads
     * the ingredient list the setup resolved before placement, so the stacks are fixed and cannot half-fill in
     * a way that differs from what was announced.
     */
    private boolean fillRecipeStation(Player player, Location location, RecipeSetup setup) {
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
            activateCookingPot(location, true);
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

    // Only stations captured in the player's placement batch can reach this mutation.
    private void activateCookingPot(Location location, boolean verify) {
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        applyCookingPotDebugState(entity, location);
        BlockPosKey posKey = new BlockPosKey(location);
        saveCookingPotData(location, entity, posKey);
        markCookingPotActive(location.getWorld(), posKey);
        syncCookingPotTray(location);
        if (verify) {
            verifyCookingPotFilled(location);
        }
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

    private int getMaxPlaceCount() {
        return Math.max(1, plugin.getConfig().getInt(CONFIG_MAX_PLACE_COUNT, DEFAULT_MAX_PLACE_COUNT));
    }

    private String normalizeTarget(String value) {
        String normalized = normalize(value);
        return "both".equals(normalized) ? "all" : normalized;
    }

    private void sendUsage(CommandSender sender) {
        for (String topic : List.of("test", "profile", "place", "place_recipe", "place_random", "activate",
                "undo", "stop", "inspect", "item", "recipe", "i18n")) {
            sender.sendMessage(I18n.getComponent("command.debug_usage_" + topic));
        }
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
                               ItemStack container) {
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
