package com.huidu.farmersdelight.debug;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.util.DebugToolExtension;
import com.huidu.farmersdelight.api.util.DebugToolRegistry;
import java.util.function.Function;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
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
import com.huidu.farmersdelight.util.OutputCap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.plugin.locale.TranslationManager;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import static com.huidu.farmersdelight.debug.DebugArguments.clamp;
import static com.huidu.farmersdelight.debug.DebugArguments.normalize;
import static com.huidu.farmersdelight.debug.DebugArguments.parseOptionalInt;
import static com.huidu.farmersdelight.debug.DebugReflection.getField;

final class DebugReadOnlyCommands {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    static final int REPORT_CAP = 64;
    private final FarmersDelightPlugin plugin;
    private final DebugBatchRunner batches;
    DebugReadOnlyCommands(FarmersDelightPlugin plugin, DebugBatchRunner batches) {
        this.plugin = plugin;
        this.batches = batches;
    }

    // Read-only diagnostics: inspect a block, dump a held item, validate recipes, trace a translation key.

    void inspect(Player player, String[] args) {
        Integer requested = parseOptionalInt(args, 2, 6);
        int distance = requested == null ? 6 : clamp(requested, 1, 64);
        Block hit = player.getTargetBlockExact(distance);
        Block target = (hit != null && !hit.getType().isAir())
                ? hit
                : player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Location location = target.getLocation();
        // The custom-block / block-entity / manager-snapshot reads below are documented region-thread-only on
        // Folia, so the whole dump (and the reply to the player) runs on the region that owns the target block —
        // the target position is read only on its owner region. On Paper this executes inline.
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
                && !CustomBlockUtils.hasId(location, Constants.BLOCK_COOKING_POT)) {
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

    void dumpHeldItem(Player player, String[] args) {
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

    void recipeValidate(Player player, Function<String, ItemStack> resolver) {
        if (batches.busy()) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
            return;
        }
        CookingPotRecipeManager potManager = plugin.getCookingPotRecipes();
        List<CookingPotRecipe> potRecipes = new ArrayList<>();
        if (potManager != null) {
            Set<String> seenPotIds = new HashSet<>();
            for (CookingPotRecipe recipe : potManager.getAllRecipes()) {
                if (seenPotIds.add(recipe.getId())) {
                    potRecipes.add(recipe);
                }
            }
        }
        CuttingBoardRecipeManager boardManager = plugin.getCuttingBoardRecipes();
        List<CuttingBoardRecipe> boardRecipes = boardManager == null ? List.of() : boardManager.getSortedRecipes();

        List<String> issues = new ArrayList<>();
        List<Runnable> addonChecks = new ArrayList<>();
        for (DebugToolExtension extension : DebugToolRegistry.all()) {
            for (String id : extension.recipeIds()) {
                addonChecks.add(() -> {
                    if (extension.recipe(id, resolver) == null) {
                        issues.add("<red>[" + extension.name() + "] " + MINI_MESSAGE.escapeTags(id)
                                + "</red> <gray>cannot resolve a complete setup</gray>");
                    }
                });
            }
        }
        int total = potRecipes.size() + boardRecipes.size() + addonChecks.size();
        // One loaded recipe per slice, so the runner walks the set over several ticks instead of one pass; the
        // report is sent after the last slice, when the issue list can no longer grow.
        if (!batches.start(player, total, index -> {
            if (index < potRecipes.size()) {
                checkPotRecipe(potManager, potRecipes.get(index), issues);
            } else if (index < potRecipes.size() + boardRecipes.size()) {
                checkBoardRecipe(potManager, boardRecipes.get(index - potRecipes.size()), issues);
            } else {
                addonChecks.get(index - potRecipes.size() - boardRecipes.size()).run();
            }
        }, () -> {
            player.sendMessage(MINI_MESSAGE.deserialize("<green>Recipe validation</green> <gray>scanned "
                    + potRecipes.size() + " cooking-pot + " + boardRecipes.size() + " cutting-board + "
                    + addonChecks.size() + " addon-station recipes (" + total + " total)</gray>"));
            sendCapped(player, issues, REPORT_CAP, "issue(s)");
            if (issues.isEmpty()) {
                player.sendMessage(MINI_MESSAGE.deserialize("<green>No unresolved item ids found.</green>"));
            } else {
                player.sendMessage(MINI_MESSAGE.deserialize("<yellow>" + issues.size()
                        + " issue(s). Recipes that failed to PARSE at load are logged separately as 'recipe.load_failed'.</yellow>"));
            }
        })) {
            player.sendMessage(I18n.getComponent("command.debug_batch_busy", player));
        }
    }

    private void checkPotRecipe(CookingPotRecipeManager tagResolver, CookingPotRecipe recipe, List<String> issues) {
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
            checkIngredient(tagResolver, "cooking_pot", recipe.getId(), ingredient, issues);
        }
    }

    private void checkBoardRecipe(CookingPotRecipeManager tagResolver, CuttingBoardRecipe recipe, List<String> issues) {
        if (recipe.getInput() == null) {
            issues.add("<red>[cutting_board] " + recipe.getId() + "</red> <gray>has no input</gray>");
        } else {
            checkIngredient(tagResolver, "cutting_board", recipe.getId(), recipe.getInput(), issues);
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

    void i18nResolve(Player player, String[] args) {
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

    // Every report whose line count follows the loaded content goes through here: at most cap lines, then how
    // many were left out. The lines are literal report text, like the other report lines in this class.
    void sendCapped(Player player, List<String> lines, int cap, String moreLabel) {
        for (String line : OutputCap.cap(lines, cap)) {
            player.sendMessage(MINI_MESSAGE.deserialize(line));
        }
        int hidden = OutputCap.overflow(lines.size(), cap);
        if (hidden > 0) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gray>" + hidden + " more " + moreLabel
                    + " not shown</gray>"));
        }
    }

}
