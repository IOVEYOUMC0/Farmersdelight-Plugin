package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.migration.LegacyIdMigration;
import com.huidu.farmersdelight.util.scheduler.RegionTasks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the legacy-id rewrites: the config gates, the counters, and the startup sweep over already loaded
 * block inventories.
 *
 *
 * Everything here is either called on a thread that owns the object it touches (the hooks hand work over
 * through a {@link MigrationDispatch}) or is a pure stack/inventory rewrite. The startup sweep is the one
 * place that walks loaded chunks: it reuses {@link RegionTasks#runAtLoadedChunk} so each chunk is read from
 * inside the region that owns it.
 *
 * <p>While the migration table is empty — which is the case until an addon registers something — the hooks
 * and the sweep do nothing at all.
 */
public final class LegacyIdMigrationService {

    private final FarmersDelightPlugin plugin;
    private final MigrationDispatch dispatch;
    private final AtomicInteger migratedStacks = new AtomicInteger();
    private final AtomicInteger migratedInventories = new AtomicInteger();
    private volatile boolean hooksEnabled = true;
    private volatile boolean logSummary = true;
    private volatile int lastLoggedTotal;

    public LegacyIdMigrationService(FarmersDelightPlugin plugin, MigrationDispatch dispatch) {
        this.plugin = plugin;
        this.dispatch = dispatch;
    }

    public MigrationDispatch dispatch() {
        return dispatch;
    }

    /** Reads the two config switches; called at startup and whenever the plugin re-reads its config. */
    public void reloadConfig() {
        hooksEnabled = plugin.getConfigBoolean(true, "legacy-id-migration.enabled");
        logSummary = plugin.getConfigBoolean(true, "legacy-id-migration.log-summary");
        // Duplicate registrations are reported once, through the plugin's logger.
        LegacyIdMigration.setConflictReporter(message -> plugin.getLogger().warning(message));
    }

    /**
     * Whether the automatic hooks should do anything. False when the feature is switched off in config.yml
     * or when no addon has registered a legacy id; {@link LegacyIdMigration#migrate(ItemStack)} itself stays
     * available either way, so an addon can always migrate a stack at its own boundary.
     */
    public boolean hooksEnabled() {
        return hooksAllowed(hooksEnabled, LegacyIdMigration.isEmpty());
    }

    /** The gate decision on its own, so the four combinations are testable without a running plugin. */
    static boolean hooksAllowed(boolean configured, boolean tableEmpty) {
        return configured && !tableEmpty;
    }

    public boolean summaryEnabled() {
        return logSummary;
    }

    /** Reads the config and schedules the one-off sweep over already loaded inventories. */
    public void start() {
        reloadConfig();
        scheduleStartupSweep();
    }

    /**
     * Migrates every legacy stack in an inventory, returning how many slots changed. Must run on the thread
     * that owns the inventory.
     */
    public int migrateInventory(@Nullable Inventory inventory) {
        if (inventory == null || !hooksEnabled()) {
            return 0;
        }
        int changed = 0;
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack current = inventory.getItem(slot);
            ItemStack migrated = migrateStack(current);
            if (migrated != null) {
                inventory.setItem(slot, migrated);
                changed++;
            }
        }
        if (changed > 0) {
            migratedInventories.incrementAndGet();
            migratedStacks.addAndGet(changed);
        }
        return changed;
    }

    /** The whole player-facing storage: inventory and ender chest. Must run on the player's thread. */
    public int migratePlayer(@Nullable Player player) {
        if (player == null || !hooksEnabled()) {
            return 0;
        }
        return migrateInventory(player.getInventory()) + migrateInventory(player.getEnderChest());
    }

    /** Rewrites a dropped item; must run on the item's thread. */
    public boolean migrateItemEntity(@Nullable Item item) {
        if (item == null || !hooksEnabled()) {
            return false;
        }
        ItemStack migrated = migrateStack(item.getItemStack());
        if (migrated == null) {
            return false;
        }
        item.setItemStack(migrated);
        migratedStacks.incrementAndGet();
        return true;
    }

    /** Migrates the stored contents behind one of our block entities. Must run on the owning thread. */
    public int migrateBlockHolder(@Nullable InventoryHolder holder) {
        if (holder == null) {
            return 0;
        }
        return migrateInventory(holder.getInventory());
    }

    /**
     * The replacement for a stack, or null when it is not legacy (so callers can skip the write). Runs no
     * world access, so it is safe on any thread.
     */
    @Nullable
    public ItemStack migrateStack(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !hooksEnabled()) {
            return null;
        }
        ItemStack migrated = LegacyIdMigration.migrate(stack);
        // migrate() returns the same instance when nothing matched, so identity is the change signal.
        return migrated == stack ? null : migrated;
    }

    /**
     * Walks the loaded chunks once and migrates the inventories of the block entities in them. Each chunk is
     * handed to its own region, and the chunk is re-checked inside the task, so nothing is read across
     * regions and an unloaded chunk is skipped.
     */
    public void scheduleStartupSweep() {
        if (!hooksEnabled()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                RegionTasks.runAtLoadedChunk(plugin.scheduler(), world, chunkX, chunkZ, () -> {
                    for (BlockState state : world.getChunkAt(chunkX, chunkZ).getTileEntities()) {
                        if (state instanceof InventoryHolder holder) {
                            migrateBlockHolder(holder);
                        }
                    }
                    logSummary();
                });
            }
        }
        logSummary();
    }

    /** Logs one aggregated line; only when something changed since the previous line, so it cannot spam. */
    public void logSummary() {
        if (!summaryEnabled()) {
            return;
        }
        int total = migratedStacks.get();
        if (total == 0 || total == lastLoggedTotal) {
            return;
        }
        lastLoggedTotal = total;
        plugin.getLogger().info("Legacy id migration: rewrote " + total + " stack(s) across "
                + migratedInventories.get() + " inventory(ies).");
    }
}
