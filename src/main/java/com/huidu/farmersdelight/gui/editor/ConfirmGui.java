package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ConfirmGui extends AbstractInventoryGui implements EditorGui {

    private final RecipeViewGuiConfig.BaseConfig config;
    private final Map<String, String> infoPlaceholders;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private boolean acted = false;

    public ConfirmGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                      Map<String, String> infoPlaceholders, Runnable onConfirm, Runnable onCancel) {
        super(plugin, player);
        this.config = config;
        this.infoPlaceholders = infoPlaceholders == null ? Map.of() : infoPlaceholders;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.inventory = plugin.getServer().createInventory(this, config.getSize(), EditorGui.coloredComponent(config.getTitle()));
    }

    public void open() {
        doOpen(this::render);
    }

    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return null;
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
    }

    @Override
    protected void ensureListenerRegistered() {
        RecipeEditorListener.ensureRegistered(plugin);
    }

    private void render() {
        for (int i = 0; i < config.getSize(); i++) {
            String type = config.getSlotType(i);
            inventory.setItem(i, "info".equals(type) ? configItem("info", infoPlaceholders)
                    : configItem(type == null ? "background" : type, Map.of()));
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (acted) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= config.getSize()) {
            return;
        }
        String type = config.getSlotType(raw);
        if ("confirm".equals(type)) {
            acted = true;
            onConfirm.run();
        } else if ("cancel".equals(type)) {
            acted = true;
            onCancel.run();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        super.close();
        if (acted) {
            return;
        }
        acted = true;
        plugin.scheduler().runLaterForEntity(player, onCancel, 1L);
    }

    private ItemStack configItem(String key, Map<String, String> placeholders) {
        GuiConfig.GuiItem item = config.getItem(key);
        if (item == null) {
            item = config.getItem("background");
        }
        if (item == null) {
            return new ItemStack(Material.AIR);
        }
        // Reuse the immutable-empty createItem() (with its cached item) for empty placeholders instead of
        // allocating a HashMap per slot on every render.
        return placeholders.isEmpty() ? item.createItem() : item.createItem(new HashMap<>(placeholders));
    }
}
