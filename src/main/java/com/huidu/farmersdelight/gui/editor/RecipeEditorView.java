package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.EditableRecipe;
import com.huidu.farmersdelight.api.recipe.NumericField;
import com.huidu.farmersdelight.api.recipe.RecipeEditor;
import com.huidu.farmersdelight.api.recipe.RecipeStationType;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.gui.RecipeViewGui;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class RecipeEditorView implements InventoryHolder {

    private static final int RESULT_SLOT = 16;
    private static final int RESULT_COUNT_SLOT = 25;
    private static final int NUMERIC_START = 28;
    private static final int SLOT_SAVE = 48;
    private static final int SLOT_CANCEL = 49;
    private static final int SLOT_DELETE = 50;
    private static final int MAX_ITEM_SLOTS = 6;

    private static volatile RecipeEditorGuiConfig guiConfig;
    private static volatile RecipeEditorStore store;

    private final RecipeEditor editor;
    private final EditableRecipe draft;
    private final List<String> slotLabels;
    private final List<NumericField> numericFields;
    private final int[] itemSlots;
    private Inventory inventory;

    private RecipeEditorView(RecipeType type, String recipeId) {
        this.editor = type.editor();
        this.slotLabels = editor.itemSlotLabels();
        this.numericFields = editor.numericFields();
        EditableRecipe loaded = editor.load(recipeId);
        this.draft = loaded != null ? loaded : new EditableRecipe(recipeId, slotLabels.size());
        int count = Math.min(MAX_ITEM_SLOTS, slotLabels.size());
        this.itemSlots = new int[count];
        for (int i = 0; i < count; i++) {
            itemSlots[i] = 10 + i;
        }
    }

    /** Reloads the editor layouts from the reloaded gui.yml. */
    public static void reloadGui(ConfigurationSection guiRoot) {
        guiConfig = RecipeEditorGuiConfig.fromConfig(guiRoot);
    }

    static RecipeEditorGuiConfig guiConfig() {
        RecipeEditorGuiConfig current = guiConfig;
        if (current == null) {
            synchronized (RecipeEditorView.class) {
                current = guiConfig;
                if (current == null) {
                    current = RecipeEditorGuiConfig.fromConfig(null);
                    guiConfig = current;
                }
            }
        }
        return current;
    }

    static RecipeEditorStore store() {
        RecipeEditorStore current = store;
        if (current == null) {
            synchronized (RecipeEditorView.class) {
                current = store;
                if (current == null) {
                    current = new RecipeEditorStore(FarmersDelightPlugin.getInstance());
                    store = current;
                }
            }
        }
        return current;
    }

    /** Opens the editor an addon exposes through api.recipe.RecipeEditor. */
    public static void open(Player player, RecipeType type, String recipeId) {
        if (type == null || type.editor() == null) {
            return;
        }
        if (!editorEnabled()) {
            player.sendMessage(I18n.getComponent("gui.editor.disabled", player));
            return;
        }
        RecipeEditorListener.ensureRegistered(FarmersDelightPlugin.getInstance());
        RecipeEditorView view = new RecipeEditorView(type, recipeId);
        view.draw();
        player.openInventory(view.inventory);
    }

    /**
     * Handles /fd recipe edit <pot|board> [id] [group].
     *
     * @param recipeId the recipe to edit, or null to list the editable recipes of the station
     * @param group    the custom cooking pot group, or null for the default group
     */
    public static void open(FarmersDelightPlugin plugin, Player player, String type, String recipeId,
                            String group) {
        if (!editorEnabled()) {
            player.sendMessage(I18n.getComponent("gui.editor.disabled", player));
            return;
        }
        if (RecipeStationType.isCookingPot(type)) {
            if (recipeId == null) {
                new RecipeViewGui(plugin, player).openCookingPotRecipesForEdit(player);
                return;
            }
            RecipeViewGuiConfig.BaseConfig editorConfig = guiConfig().getCookingPotConfig(group);
            if (editorConfig == null) {
                // Same text as the editor GUIs' own message: resolve it on the client from the pack.
                player.sendMessage(Component.translatable("gui.editor.feedback.not_configured"));
                return;
            }
            String storedId = resolveStoredId(type, recipeId,
                    (group == null || group.isBlank())
                            ? plugin.getCookingPotRecipes().getRecipes().keySet()
                            : plugin.getCookingPotRecipes().getRecipes(group).keySet());
            CookingPotRecipe existing = (group == null || group.isBlank())
                    ? plugin.getCookingPotRecipes().getRecipe(storedId)
                    : plugin.getCookingPotRecipes().getRecipe(group, storedId);
            new CookingPotEditorGui(plugin, player, storedId, group, existing, editorConfig).open();
            return;
        }
        if (RecipeStationType.isCuttingBoard(type)) {
            if (recipeId == null) {
                new RecipeViewGui(plugin, player).openCuttingBoardRecipesForEdit(player);
                return;
            }
            RecipeViewGuiConfig.BaseConfig boardConfig = guiConfig().getCuttingBoardConfig();
            if (boardConfig == null) {
                player.sendMessage(Component.translatable("gui.editor.feedback.not_configured"));
                return;
            }
            String storedId = resolveStoredId(type, recipeId,
                    plugin.getCuttingBoardRecipes().getRecipes().keySet());
            CuttingBoardRecipe existing = plugin.getCuttingBoardRecipes().getRecipe(storedId);
            new CuttingBoardEditorGui(plugin, player, storedId, existing, boardConfig).open();
            return;
        }
        player.sendMessage(I18n.getComponent("gui.editor.usage", player));
    }

    /**
     * The id the files store for what the operator typed: a namespaced spelling of an existing recipe has to
     * find that recipe, or the editor would open a second one under a different id and save a duplicate. A
     * token that names no existing recipe is kept as typed, because that is how a new recipe is created.
     */
    private static String resolveStoredId(String type, String recipeId, Collection<String> knownIds) {
        String canonical = RecipeIds.canonical(type, recipeId, new HashSet<>(knownIds));
        return canonical == null ? recipeId : canonical;
    }

    /** Opens the recipe viewer's edit button target one tick later. */
    public static void openFromViewerLater(FarmersDelightPlugin plugin, Player player, String recipeId,
                                           boolean cookingPot) {
        plugin.scheduler().runLaterForEntity(player, () -> {
            if (player.isOnline()) {
                openFromViewer(plugin, player, recipeId, cookingPot);
            }
        }, 1L);
    }

    /** Opens the editor for one recipe straight from the recipe viewer's edit button. */
    public static void openFromViewer(FarmersDelightPlugin plugin, Player player, String recipeId,
                                      boolean cookingPot) {
        if (!editorEnabled()) {
            player.sendMessage(I18n.getComponent("gui.editor.disabled", player));
            return;
        }
        if (cookingPot) {
            RecipeViewGuiConfig.BaseConfig editorConfig = guiConfig().getCookingPotConfig(null);
            if (editorConfig == null) {
                return;
            }
            String storedId = resolveStoredId(RecipeStationType.COOKING_POT.discoveryTypeId(), recipeId,
                    plugin.getCookingPotRecipes().getRecipes().keySet());
            CookingPotRecipe existing = plugin.getCookingPotRecipes().getRecipe(storedId);
            new CookingPotEditorGui(plugin, player, storedId, null, existing, editorConfig).open();
            return;
        }
        RecipeViewGuiConfig.BaseConfig boardConfig = guiConfig().getCuttingBoardConfig();
        if (boardConfig == null) {
            return;
        }
        String storedId = resolveStoredId(RecipeStationType.CUTTING_BOARD.discoveryTypeId(), recipeId,
                plugin.getCuttingBoardRecipes().getRecipes().keySet());
        CuttingBoardRecipe existing = plugin.getCuttingBoardRecipes().getRecipe(storedId);
        new CuttingBoardEditorGui(plugin, player, storedId, existing, boardConfig).open();
    }

    /** Validates a recipe id before anything is opened. */
    public static boolean isValidRecipeId(String recipeId) {
        return recipeId != null && recipeId.matches("[a-z0-9_.-]+(?::[a-z0-9/._-]+)?");
    }

    /** recipe-editor.enabled in config.yml; an absent key means enabled. */
    private static boolean editorEnabled() {
        return FarmersDelightPlugin.getInstance().getConfigBoolean(true, "recipe-editor.enabled");
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void draw() {
        inventory = Bukkit.createInventory(this, 54,
                tr("gui.editor.recipe_book.title", NamedTextColor.DARK_GRAY,
                        Component.text(String.valueOf(draft.id()), NamedTextColor.DARK_AQUA)));

        List<Component> labelLore = new ArrayList<>();
        for (int i = 0; i < itemSlots.length; i++) {
            // The slot label itself is supplied (and localized) by the addon's editor; only the "Slot N:"
            // chrome is translated here.
            labelLore.add(tr("gui.editor.recipe_book.slot_line", NamedTextColor.GRAY, i + 1, slotLabels.get(i)));
        }
        inventory.setItem(4, named(new ItemStack(Material.KNOWLEDGE_BOOK),
                tr("gui.editor.recipe_book.input_slots", NamedTextColor.AQUA), labelLore));

        for (int i = 0; i < itemSlots.length; i++) {
            inventory.setItem(itemSlots[i], draft.item(i));
        }
        inventory.setItem(RESULT_SLOT, draft.result());
        inventory.setItem(RESULT_COUNT_SLOT, countButton());

        for (int i = 0; i < numericFields.size() && NUMERIC_START + i < SLOT_SAVE; i++) {
            inventory.setItem(NUMERIC_START + i, numericButton(numericFields.get(i)));
        }

        inventory.setItem(SLOT_SAVE, named(new ItemStack(Material.LIME_CONCRETE),
                tr("gui.editor.button.save", NamedTextColor.GREEN), null));
        inventory.setItem(SLOT_CANCEL, named(new ItemStack(Material.BARRIER),
                tr("gui.editor.button.cancel", NamedTextColor.RED), null));
        inventory.setItem(SLOT_DELETE, named(new ItemStack(Material.LAVA_BUCKET),
                tr("gui.editor.button.delete", NamedTextColor.DARK_RED), null));
    }

    private static Component tr(String key, NamedTextColor color) {
        return Component.translatable(key).color(color);
    }

    private static Component tr(String key, NamedTextColor color, Object... args) {
        Component[] components = new Component[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            components[i] = a instanceof Component c ? c : Component.text(String.valueOf(a));
        }
        return Component.translatable(key, components).color(color);
    }

    public boolean isEditableSlot(int rawSlot) {
        if (rawSlot == RESULT_SLOT) {
            return true;
        }
        for (int slot : itemSlots) {
            if (slot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    public void handleButton(Player player, int rawSlot, boolean rightClick) {
        if (rawSlot == RESULT_COUNT_SLOT) {
            draft.setResultCount(Math.max(1, draft.resultCount() + (rightClick ? -1 : 1)));
            inventory.setItem(RESULT_COUNT_SLOT, countButton());
            return;
        }
        int numericIndex = rawSlot - NUMERIC_START;
        if (numericIndex >= 0 && numericIndex < numericFields.size()) {
            NumericField field = numericFields.get(numericIndex);
            double current = draft.number(field.key(), field.min());
            double next = current + (rightClick ? -field.step() : field.step());
            next = Math.max(field.min(), Math.min(field.max(), next));
            draft.setNumber(field.key(), next);
            inventory.setItem(rawSlot, numericButton(field));
            return;
        }
        if (rawSlot == SLOT_SAVE) {
            commitItems();
            String id = draft.id() == null ? "" : draft.id().trim();
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
                player.sendMessage(tr("gui.editor.feedback.invalid_id", NamedTextColor.RED));
                return;
            }
            draft.setId(id.toLowerCase(Locale.ROOT));
            if (draft.result() == null || draft.result().getType().isAir()) {
                player.sendMessage(tr("gui.editor.feedback.no_result", NamedTextColor.RED));
                return;
            }
            if (draft.itemSlotCount() > 0) {
                boolean hasInput = false;
                for (int i = 0; i < draft.itemSlotCount(); i++) {
                    ItemStack item = draft.item(i);
                    if (item != null && !item.getType().isAir()) {
                        hasInput = true;
                        break;
                    }
                }
                if (!hasInput) {
                    player.sendMessage(tr("gui.editor.feedback.no_input", NamedTextColor.RED));
                    return;
                }
            }
            boolean ok = editor.save(draft);
            player.sendMessage(ok
                    ? tr("gui.editor.recipe_book.saved", NamedTextColor.GREEN, draft.id())
                    : tr("gui.editor.recipe_book.save_failed", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        if (rawSlot == SLOT_CANCEL) {
            player.closeInventory();
            return;
        }
        if (rawSlot == SLOT_DELETE) {
            boolean ok = editor.delete(draft.id());
            player.sendMessage(ok
                    ? tr("gui.editor.recipe_book.deleted", NamedTextColor.GREEN, draft.id())
                    : tr("gui.editor.recipe_book.delete_failed", NamedTextColor.RED));
            player.closeInventory();
        }
    }

    private void commitItems() {
        for (int i = 0; i < itemSlots.length; i++) {
            draft.setItem(i, inventory.getItem(itemSlots[i]));
        }
        draft.setResult(inventory.getItem(RESULT_SLOT));
    }

    private ItemStack countButton() {
        return named(new ItemStack(Material.PAPER, Math.max(1, Math.min(64, draft.resultCount()))),
                tr("gui.editor.result_count", NamedTextColor.YELLOW, draft.resultCount()),
                List.of(tr("gui.editor.hint_pm1", NamedTextColor.GRAY)));
    }

    private ItemStack numericButton(NumericField field) {
        double value = draft.number(field.key(), field.min());
        String shown = field.decimals() <= 0
                ? String.valueOf((long) value)
                : String.format("%." + field.decimals() + "f", value);
        // field.label() is the addon's own (already-localized) field name; only the +/- hint chrome is translated.
        return named(new ItemStack(Material.COMPARATOR),
                Component.text(field.label() + ": " + shown, NamedTextColor.YELLOW),
                List.of(tr("gui.editor.recipe_book.step_hint", NamedTextColor.GRAY, field.step(), field.step())));
    }

    private static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        RecipeBookGui.rename(item, name);
        if (lore != null && !lore.isEmpty()) {
            RecipeBookGui.applyLore(item, lore);
        }
        return item;
    }
}
