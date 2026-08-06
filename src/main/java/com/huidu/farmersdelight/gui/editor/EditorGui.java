package com.huidu.farmersdelight.gui.editor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public interface EditorGui extends InventoryHolder {

    LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    void handleClick(InventoryClickEvent event);

    default void handleDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    default void handleClose(InventoryCloseEvent event) {
    }

    static Component coloredComponent(String title) {
        String resolved = title == null ? "" : title;
        if (resolved.contains("<") && resolved.contains(">")) {
            return MINI_MESSAGE.deserialize(resolved);
        }
        return LEGACY.deserialize(resolved.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "§"));
    }
}
