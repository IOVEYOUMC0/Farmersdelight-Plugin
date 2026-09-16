package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;

public abstract class AbstractInventoryGui implements InventoryHolder {

    // Closing another player's inventory is an entity write: on Folia it must happen on that player's
    // own region. Batch close loops (reload, block break) run on whatever thread triggered them, so
    // every such loop routes through here instead of calling closeInventory directly.
    protected static void closeViewerInventory(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.getScheduler().run(FarmersDelightPlugin.getInstance(), t -> player.closeInventory(), null);
        } catch (Throwable t) {
            try {
                player.closeInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    protected final FarmersDelightPlugin plugin;
    protected UUID playerId;
    protected Player player;
    protected Inventory inventory;
    protected volatile boolean closed = false;
    protected final Consumer<Void> tickCallback;

    protected AbstractInventoryGui(FarmersDelightPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.playerId = player != null ? player.getUniqueId() : null;
        this.tickCallback = v -> onTick();
    }

    @Override
    @Nonnull
    public Inventory getInventory() {
        return inventory;
    }

    protected void onTick() {
    }

    protected final void doOpen(Runnable afterRefresh) {
        closed = false;

        AbstractInventoryGui existingGui = findExistingGui(playerId);
        if (existingGui != null && !existingGui.closed) {
            existingGui.close();
        }

        ensureListenerRegistered();
        putActiveGui(playerId, this);

        if (afterRefresh != null) {
            afterRefresh.run();
        }
        player.openInventory(inventory);

        GuiTickManager.getInstance(plugin).registerCallback(player, tickCallback);
    }

    // ---- Required subclass hooks ----

    protected abstract AbstractInventoryGui findExistingGui(UUID playerId);

    protected abstract void putActiveGui(UUID playerId, AbstractInventoryGui gui);

    protected abstract void removeFromActiveGuis(UUID playerId);

    protected abstract void ensureListenerRegistered();

    // ---- Shared lifecycle ----

    public void close() {
        if (closed) return;
        closed = true;
        GuiTickManager.getInstance(plugin).unregisterCallback(tickCallback);
    }

    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) return;
        close();
        removeFromActiveGuis(event.getPlayer().getUniqueId());
    }

    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        // Cancel all drags into the top inventory by default; subclasses may allow specific drag behavior.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

}
