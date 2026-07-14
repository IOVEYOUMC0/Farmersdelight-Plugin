package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A pure-GUI builder for "choice" type ingredients (a|b|c). Each filled item slot is one option. On confirm:
 * 0 options clears the ingredient, 1 option produces a plain item, 2 or more produce a choice. Layout and
 * text come from the recipe-choice-builder-gui section of gui.yml.
 */
public final class ChoiceBuilderGui implements EditorGui {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final RecipeViewGuiConfig.BaseConfig config;
    private final int displayIndex;
    private final Consumer<RecipeIngredient> onConfirm;
    private final Runnable onCancel;
    private final List<Integer> optionSlots;
    private final ItemStack[] options;
    private final Inventory inventory;
    private boolean acted = false;
    private boolean closed = false;

    public ChoiceBuilderGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                            int displayIndex, @Nullable RecipeIngredient current,
                            Consumer<RecipeIngredient> onConfirm, Runnable onCancel) {
        this.plugin = plugin;
        this.player = player;
        this.config = config;
        this.displayIndex = displayIndex;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.optionSlots = config.getSlotsByType("option");
        this.options = new ItemStack[Math.max(1, optionSlots.size())];
        initFrom(current);
        this.inventory = plugin.getServer().createInventory(this, config.getSize(), coloredTitle(config.getTitle()));
    }

    private void initFrom(@Nullable RecipeIngredient current) {
        List<RecipeIngredient> sources = new ArrayList<>();
        if (current instanceof RecipeIngredient.Choice choice) {
            sources.addAll(choice.options());
        } else if (current instanceof RecipeIngredient.Item || current instanceof RecipeIngredient.Tag) {
            sources.add(current);
        }
        for (int i = 0; i < options.length && i < sources.size(); i++) {
            if (sources.get(i) instanceof RecipeIngredient.Item item) {
                ItemStack stack = ItemUtils.createItem(item.key().toString());
                if (stack != null && !stack.getType().isAir()) {
                    stack.setAmount(1);
                    options[i] = stack;
                }
            }
        }
    }

    public void open() {
        RecipeEditorListener.ensureRegistered(plugin);
        render();
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void render() {
        for (int i = 0; i < config.getSize(); i++) {
            String type = config.getSlotType(i);
            if ("option".equals(type)) {
                int idx = optionSlots.indexOf(i);
                ItemStack item = idx >= 0 && idx < options.length ? options[idx] : null;
                inventory.setItem(i, item == null ? configItem("option", Map.of()) : item.clone());
            } else if ("info".equals(type)) {
                inventory.setItem(i, configItem("info", Map.of("index", String.valueOf(displayIndex))));
            } else {
                inventory.setItem(i, configItem(type == null ? "background" : type, Map.of()));
            }
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (closed) {
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < config.getSize();
        if (!top) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                player.setItemOnCursor(cleanCopy(clicked));
            }
            return;
        }
        handleTopClick(raw, event.getClick(), event.getCursor());
    }

    private void handleTopClick(int slot, ClickType click, ItemStack cursor) {
        String type = config.getSlotType(slot);
        if (type == null) {
            return;
        }
        switch (type) {
            case "option": {
                int idx = optionSlots.indexOf(slot);
                if (idx < 0 || idx >= options.length) {
                    return;
                }
                boolean hasCursorItem = cursor != null && !cursor.getType().isAir();
                if (hasCursorItem) {
                    options[idx] = cleanCopy(cursor);
                    clearCursor();
                } else if (click.isRightClick()) {
                    options[idx] = null;
                } else if (options[idx] != null) {
                    player.setItemOnCursor(cleanCopy(options[idx]));
                    options[idx] = null;
                }
                render();
                return;
            }
            case "clear-all":
                Arrays.fill(options, null);
                render();
                return;
            case "save":
                confirm();
                return;
            case "cancel":
                acted = true;
                closed = true;
                clearCursor();
                onCancel.run();
                return;
            default:
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        closed = true;
        clearCursor();
        if (!acted) {
            acted = true;
            plugin.scheduler().runLaterForEntity(player, onCancel, 1L);
        }
    }

    private void confirm() {
        List<RecipeIngredient> chosen = new ArrayList<>();
        for (ItemStack option : options) {
            if (option != null && !option.getType().isAir()) {
                chosen.add(new RecipeIngredient.Item(Key.of(RecipeSerializer.itemIdString(option))));
            }
        }
        RecipeIngredient result;
        if (chosen.isEmpty()) {
            result = null;
        } else if (chosen.size() == 1) {
            result = chosen.get(0);
        } else {
            result = new RecipeIngredient.Choice(chosen);
        }
        acted = true;
        closed = true;
        clearCursor();
        onConfirm.accept(result);
    }

    private void clearCursor() {
        player.setItemOnCursor(null);
    }

    private ItemStack configItem(String key, Map<String, String> placeholders) {
        GuiConfig.GuiItem item = config.getItem(key);
        if (item == null) {
            item = config.getItem("background");
        }
        return item == null ? new ItemStack(Material.AIR) : item.createItem(new HashMap<>(placeholders));
    }

    private static ItemStack cleanCopy(ItemStack source) {
        ItemStack copy = source.clone();
        copy.setAmount(1);
        return copy;
    }

    private static Component coloredTitle(String title) {
        String resolved = title == null ? "" : title;
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        return LEGACY.deserialize(resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§"));
    }
}
