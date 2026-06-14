package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 由 gui.yml 布局驱动的通用确认/取消页面（slot 类型为 confirm、cancel、
 * info、background）。关闭页面（按 ESC）会被视为取消。
 */
public final class ConfirmGui implements EditorGui {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final FarmersDelightPlugin plugin;
    private final Player player;
    private final RecipeViewGuiConfig.BaseConfig config;
    private final Map<String, String> infoPlaceholders;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private final Inventory inventory;
    private boolean acted = false;

    public ConfirmGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                      Map<String, String> infoPlaceholders, Runnable onConfirm, Runnable onCancel) {
        this.plugin = plugin;
        this.player = player;
        this.config = config;
        this.infoPlaceholders = infoPlaceholders == null ? Map.of() : infoPlaceholders;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.inventory = plugin.getServer().createInventory(this, config.getSize(), title(config.getTitle()));
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
        return item == null ? new ItemStack(Material.AIR) : item.createItem(new HashMap<>(placeholders));
    }

    private static Component title(String title) {
        String resolved = title == null ? "" : title;
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        return LEGACY.deserialize(resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§"));
    }
}
