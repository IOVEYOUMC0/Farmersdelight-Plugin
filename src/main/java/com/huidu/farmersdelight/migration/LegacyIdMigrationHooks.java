package com.huidu.farmersdelight.migration;

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
 * The automatic hooks that rewrite stacks already in the world, so an addon only has to declare its legacy
 * ids.
 *
 *
 * Every hook decides through {@link MigrationDispatch#inlineIfOwned}: it runs on the current thread when
 * that thread already owns the player, item or block, and otherwise hands the work to the owning region.
 * A container is only touched when its owner can be named — a {@code BlockState}, either half of a
 * {@link org.bukkit.block.DoubleChest}, one of our own GUIs (owned by the viewing player) or a player
 * holder. Any other holder is skipped rather than read, because reading it from an unknown thread is the
 * one thing this must not do; a skipped container is retried the next time it is opened. No hook uses
 * {@code Bukkit.getScheduler}.
 *
 * <p>The hooks are:
 * <ul>
 *   <li>player join — the player's inventory and ender chest, on the player's thread;</li>
 *   <li>container open — the opened inventory, once per open (not per click), on the owner of the block;</li>
 *   <li>our own container GUI opening — the view's stored contents, on the viewing player's thread;</li>
 *   <li>item spawn — the dropped stack, on the item entity's thread;</li>
 *   <li>startup — already loaded block inventories, one dispatch per loaded chunk (see
 *       {@link LegacyIdMigrationService#scheduleStartupSweep()}).</li>
 * </ul>
 *
 * <p>Construction only stores the plugin: the scheduler is not consulted until {@link #start()}, so the
 * registration order can still be asserted by a test that builds the registry with a null plugin.
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

    /** The service, or null before {@link #start()}. */
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

    /** Hook 2 (container) and hook 3 (our own GUI): the view's contents, once per open. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        LegacyIdMigrationService active = service;
        if (active == null || !active.hooksEnabled()) {
            return;
        }
        Inventory inventory = event.getInventory();
        Location owner = ownerOf(inventory.getHolder(), event.getPlayer());
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
            if (doubleChest.getLeftSide() instanceof BlockState left) {
                return left.getLocation();
            }
            if (doubleChest.getRightSide() instanceof BlockState right) {
                return right.getLocation();
            }
            return null;
        }
        if (holder instanceof AbstractInventoryGui && viewer instanceof Player player) {
            // Our container GUI: the mirror is loaded from the block entity on open, and the viewer's thread
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
