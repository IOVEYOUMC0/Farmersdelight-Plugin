package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.api.util.PerformanceProbe;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Migrates stacks on join, container open, plugin GUI open, item spawn and startup.
 * Work runs inline when owned, otherwise on the inventory, entity or block owner.
 * Containers with unknown ownership are skipped and retried on their next open.
 * Startup scans loaded chunks; player migration also includes the ender chest.
 */
public final class LegacyIdMigrationHooks implements Listener {

    private final FarmersDelightPlugin plugin;
    // One rewrite per open: InventoryOpenEvent fires once per open, and a click must not rescan the view.
    // Regions run this concurrently, so the check-and-record lives in OpenDedupe (synchronized weak map).
    private final OpenDedupe migratedOpens = new OpenDedupe();
    private LegacyIdMigrationService service;

    public LegacyIdMigrationHooks(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Reads the config and schedules the startup sweep. Called once the listeners are registered. */
    public void start() {
        if (service == null) {
            service = new LegacyIdMigrationService(plugin, new SchedulerMigrationDispatch(plugin.scheduler()));
        }
        service.start();
    }

    /** The service, or null before start(). */
    @Nullable
    public LegacyIdMigrationService service() {
        return service;
    }

    /** Hook 1: a joining player's own storage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        LegacyIdMigrationService active = service;
        Player player = event.getPlayer();
        if (active == null || !active.hooksEnabled()) {
            return;
        }
        MigrationDispatch.inlineIfOwned(active.dispatch(), player, () -> {
            active.migratePlayer(player);
            active.logSummary();
        });
    }

    /** Hook 2 (container) and hook 3 (the plugin's own GUI): the view's contents, once per open. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        LegacyIdMigrationService active = service;
        if (active == null || !active.hooksEnabled()) {
            return;
        }
        Inventory inventory = event.getInventory();
        Location owner = ownerOf(PerformanceProbe.holder(inventory), event.getPlayer());
        if (owner == null) {
            // No attributable owner: skip this open instead of reading a container from a thread that may not
            // own it. The next open tries again.
            return;
        }
        if (!migratedOpens.firstOpen(inventory)) {
            return;
        }
        MigrationDispatch.inlineIfOwned(active.dispatch(), owner, () -> {
            active.migrateInventory(inventory);
            active.logSummary();
        });
    }

    /**
     * The owner a container belongs to, or null when it cannot be named. Null means "skip": a container
     * whose owner is unknown must not be read from whichever thread happens to see the event.
     */
    @Nullable
    static Location ownerOf(@Nullable InventoryHolder holder, @Nullable Entity viewer) {
        if (holder instanceof BlockState block) {
            return block.getLocation();
        }
        if (holder instanceof DoubleChest doubleChest) {
            // A double chest is one inventory over two blocks in the same region pair, so either half names
            // the owner; the left side is preferred only for determinism.
            if (doubleChest.getLeftSide(false) instanceof BlockState left) {
                return left.getLocation();
            }
            if (doubleChest.getRightSide(false) instanceof BlockState right) {
                return right.getLocation();
            }
            return null;
        }
        if (holder instanceof AbstractInventoryGui && viewer instanceof Player player) {
            // The plugin's container GUI: the mirror is loaded from the block entity on open, and the viewer's thread
            // owns the view.
            return player.getLocation();
        }
        if (holder instanceof Player player) {
            return player.getLocation();
        }
        return null;
    }

    /** Hook 4: a stack that just dropped into the world. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        LegacyIdMigrationService active = service;
        if (active == null || !active.hooksEnabled()) {
            return;
        }
        Item item = event.getEntity();
        MigrationDispatch.inlineIfOwned(active.dispatch(), item, () -> {
            active.migrateItemEntity(item);
            active.logSummary();
        });
    }
}
