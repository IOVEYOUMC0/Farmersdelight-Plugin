package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.RugConfig;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBoxConfig;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitboxPart;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couples "rug" furnitures (canvas_rug / half_tatami_mat / full_tatami_mat, or any id registered in
 * rugs.yml) with a real vanilla block placed under each footprint cell: the furniture supplies
 * the custom 3D visual, the underlying block supplies real player collision (interaction hitboxes
 * don't collide with entities physically). Which furnitures are rugs, and which block goes under each,
 * come entirely from RugConfig — nothing is hard-coded here.
 *
 * <p>Multi-cell rugs (full_tatami_mat spans two cells, per its YAML hitbox list) get one underlying
 * block per cell. "Any cell loses its support → the whole rug goes" holds because every removal path
 * of any cell's block routes through removeRug, which destroys the furniture and clears ALL
 * of its cells at once.
 *
 * <p>Removal paths covered — each is needed because CraftEngine's FurnitureBreakEvent fires
 * ONLY when a player attacks the furniture entity, never for programmatic removals:
 * <ul>
 *   <li>player mines the underlying block (BlockBreakEvent)</li>
 *   <li>support lost → block self-destructs silently (BlockPhysicsEvent / ItemSpawnEvent)</li>
 *   <li>piston push (BlockPistonExtendEvent / BlockPistonRetractEvent)</li>
 *   <li>liquid washes over it (BlockFromToEvent)</li>
 *   <li><b>fire burns it</b> (BlockBurnEvent) — otherwise a burnt carpet would orphan the furniture</li>
 * </ul>
 *
 * <p>Tracking: a Map&lt;cellPosKey, BukkitFurniture&gt; populated on FurniturePlace, drained on removal.
 * <b>Known limitation</b>: rugs that exist before a server restart aren't in the map until a chunk
 * roundtrip or re-place; an entity-scan fallback keeps BlockBreak working, but water/burn on those
 * legacy rugs is missed until they re-register.
 */
public final class RugListener implements Listener {

    private static final BlockFace[] FACE_6 = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    // Delay before the underlying collision block is placed after the furniture spawns. The furniture
    // display entity takes a few ticks to reach and render on the client; placing the block on the same
    // tick lets the plain block show before the furniture, so it is deferred to keep the furniture first.
    private static final long UNDERLYING_PLACE_DELAY_TICKS = 3L;

    private final FarmersDelightPlugin plugin;
    // Cell record is value-typed: equals/hashCode inline to primitive compares, no per-event alloc on
    // the get/put hot side (BlockPhysicsEvent is hit by redstone, water flow and neighbour updates).
    private final Map<Cell, BukkitFurniture> cellToRug = new ConcurrentHashMap<>();

    private record Cell(java.util.UUID worldId, int x, int y, int z) {}

    public RugListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    private RugConfig config() {
        return plugin.getRugConfig();
    }

    /**
     * Validates that every cell of a rug furniture's footprint has a solid block beneath it before the
     * place is committed. CE's stock placement check only verifies the base hitbox; for a multi-cell
     * rug that leaves the other cells free to dangle over air. Cancelling here keeps placement atomic —
     * the furniture is never spawned, so there's no orphan visual and no follow-on block to manage.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFurnitureAttemptPlace(FurnitureAttemptPlaceEvent event) {
        Key id = event.furniture().id();
        if (id == null || !config().isRug(id.toString())) return;
        Location base = event.location();
        World world = base.getWorld();
        if (world == null) return;

        WorldPosition basePos = new WorldPosition(BukkitAdaptor.adapt(world),
                base.getX(), base.getY(), base.getZ(), 0f, base.getYaw());

        for (FurnitureHitBoxConfig<?> hitboxConfig : event.variant().hitBoxConfigs()) {
            Vec3d worldVec = Furniture.getRelativePosition(basePos, hitboxConfig.position());
            int bx = (int) Math.floor(worldVec.x);
            int by = (int) Math.floor(worldVec.y);
            int bz = (int) Math.floor(worldVec.z);
            Block below = world.getBlockAt(bx, by - 1, bz);
            if (below.getType().isAir() || !below.getType().isSolid()) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(I18n.getComponent("rug.needs_solid_support", event.getPlayer()));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        BukkitFurniture furn = event.furniture();
        Material underlying = underlyingOf(furn);
        if (underlying == null) return;
        World world = event.location().getWorld();
        if (world == null) return;
        for (Location cell : cellsOf(furn)) {
            cellToRug.put(posKey(cell), furn);
            Bukkit.getRegionScheduler().runDelayed(plugin, cell, t -> {
                // The rug can be removed within this delay window — only FurnitureBreakEvent can fire that
                // early, since every other removal path keys off the not-yet-placed underlying block. If it
                // was removed, its tracking entry is gone; skip the place so we don't strand an orphan
                // collision block (a free vanilla carpet) with no furniture over it.
                if (!furn.isValid() || cellToRug.get(posKey(cell)) != furn) {
                    return;
                }
                Block b = world.getBlockAt(cell);
                if (b.getType().isAir()) {
                    b.setType(underlying, false);
                }
            }, UNDERLYING_PLACE_DELAY_TICKS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        BukkitFurniture furn = event.furniture();
        Material underlying = underlyingOf(furn);
        if (underlying == null) return;
        World world = event.location().getWorld();
        if (world == null) return;
        // Player attacked the furniture entity directly (the one path that DOES fire this event).
        clearUnderlying(world, cellsOf(furn), underlying);
    }

    /**
     * Removes a rug furniture AND clears its companion underlying blocks + tracking.
     * CraftEngineFurniture#remove does NOT fire FurnitureBreakEvent (only a player
     * attacking the furniture entity does), so the block cleanup can't be left to
     * onFurnitureBreak for programmatic removals. Capture the cells + material WHILE the
     * furniture is still valid, remove it, then clear each cell.
     *
     * @param dropLoot whether the rug drops its item (true for break/support/piston/water; false for fire)
     */
    private void removeRug(BukkitFurniture rug, org.bukkit.entity.Player player, boolean dropLoot) {
        if (rug == null || !rug.isValid()) return;
        Material underlying = underlyingOf(rug);
        Location loc = rug.location();
        World world = loc != null ? loc.getWorld() : null;
        List<Location> cells = cellsOf(rug);
        if (player != null) {
            CraftEngineFurniture.remove(rug, player, dropLoot, true);
        } else {
            CraftEngineFurniture.remove(rug, dropLoot, true);
        }
        if (underlying != null) {
            clearUnderlying(world, cells, underlying);
        }
    }

    /** Drops each cell's tracking entry and schedules removal of its underlying collision block. */
    private void clearUnderlying(World world, List<Location> cells, Material underlying) {
        if (world == null) return;
        for (Location cell : cells) {
            cellToRug.remove(posKey(cell));
            Bukkit.getRegionScheduler().run(plugin, cell, t -> {
                Block b = world.getBlockAt(cell);
                if (b.getType() == underlying) {
                    b.setType(Material.AIR, false);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnderlyingBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!config().isUnderlyingMaterial(block.getType())) return;
        BukkitFurniture rug = resolveRugAt(block.getLocation());
        if (rug == null) return;
        event.setDropItems(false);
        removeRug(rug, event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnderlyingDropFromPhysics(ItemSpawnEvent event) {
        if (cellToRug.isEmpty()) return;
        if (!config().isUnderlyingMaterial(event.getEntity().getItemStack().getType())) return;
        Location loc = event.getLocation();
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        BukkitFurniture rug = resolveRugAt(blockLoc);
        if (rug == null) return;
        event.setCancelled(true);
        removeRug(rug, null, true);
    }

    /**
     * Vanilla CarpetBlock.updateShape returns AIR when support is lost — the block silently
     * transitions without firing BlockBreakEvent or ItemSpawnEvent. BlockPhysicsEvent is the only hook
     * on that path. Defer one tick: if the block is gone after the physics update, route through
     * furniture-remove so the whole multi-cell rug collapses and drops the correct item.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnderlyingPhysics(BlockPhysicsEvent event) {
        // Three cheap gates protect the hot path: ① empty-map check skips everything when no rugs
        // exist, ② material check, ③ O(1) map lookup. posKey is only allocated once a tracked
        // underlying block actually updates, which is rare.
        if (cellToRug.isEmpty()) return;
        Block block = event.getBlock();
        if (!config().isUnderlyingMaterial(block.getType())) return;
        BukkitFurniture rug = cellToRug.get(posKey(block.getLocation()));
        if (rug == null) return;
        Bukkit.getRegionScheduler().run(plugin, block.getLocation(), task -> {
            if (config().isUnderlyingMaterial(block.getType()) || !rug.isValid()) return;
            removeRug(rug, null, true);
        });
    }

    /**
     * Fire burns the underlying block away silently — no BlockBreakEvent, no drop. Without this the
     * furniture visual would be left floating over a hole. Match vanilla burning: remove the rug with
     * no drop.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnderlyingBurn(BlockBurnEvent event) {
        if (cellToRug.isEmpty()) return;
        Block block = event.getBlock();
        if (!config().isUnderlyingMaterial(block.getType())) return;
        BukkitFurniture rug = cellToRug.get(posKey(block.getLocation()));
        if (rug == null) return;
        removeRug(rug, null, false);
    }

    // Piston push: a carpet's push_reaction is normal, so a piston would slide the block to a new
    // location while the furniture entity stays put. Mimic vanilla "decoration pops off when pushed":
    // if any pushed block is a tracked underlying block, cancel the push this tick and break the rug
    // next tick. Once the rug is gone the cell is air; the piston succeeds on retry.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Set<BukkitFurniture> affected = collectAffectedRugs(event.getBlocks());
        if (affected.isEmpty()) return;
        event.setCancelled(true);
        breakAffectedRugs(affected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        Set<BukkitFurniture> affected = collectAffectedRugs(event.getBlocks());
        if (affected.isEmpty()) return;
        event.setCancelled(true);
        breakAffectedRugs(affected);
    }

    // Explosions destroy the low-blast-resistance underlying block directly: no BlockBreakEvent fires, and the
    // furniture visual is blast-immune, so without this the rug would be left floating with no collision. The
    // underlying block's own drop is only handled by onUnderlyingDropFromPhysics when the blast happens to roll a
    // drop; this covers the common no-drop case. breakAffectedRugs defers removeRug to next tick, so the rug stays
    // valid through the blast and onUnderlyingDropFromPhysics still suppresses the underlying block's item in the
    // drop-rolled case (the deferred removeRug then no-ops on the already-removed rug).
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnderlyingBlockExplode(BlockExplodeEvent event) {
        Set<BukkitFurniture> affected = collectAffectedRugs(event.blockList());
        if (affected.isEmpty()) return;
        breakAffectedRugs(affected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnderlyingEntityExplode(EntityExplodeEvent event) {
        Set<BukkitFurniture> affected = collectAffectedRugs(event.blockList());
        if (affected.isEmpty()) return;
        breakAffectedRugs(affected);
    }

    private Set<BukkitFurniture> collectAffectedRugs(List<Block> blocks) {
        if (cellToRug.isEmpty()) return Set.of();
        Set<BukkitFurniture> rugs = new HashSet<>();
        for (Block block : blocks) {
            if (!config().isUnderlyingMaterial(block.getType())) continue;
            BukkitFurniture rug = cellToRug.get(posKey(block.getLocation()));
            if (rug != null) rugs.add(rug);
        }
        return rugs;
    }

    private void breakAffectedRugs(Set<BukkitFurniture> rugs) {
        for (BukkitFurniture rug : rugs) {
            Location anchor = rug.location();
            if (anchor == null) continue;
            Bukkit.getRegionScheduler().run(plugin, anchor, task -> {
                if (rug.isValid()) {
                    removeRug(rug, null, true);
                }
            });
        }
    }

    /**
     * Folia chunks unload without firing FurnitureBreakEvent, so cellToRug entries for that chunk would
     * linger and keep the (now-despawned) furniture's ItemDisplay strong-referenced — blocking GC of
     * every rug ever loaded on a long-running server. Scan + drop matching entries.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (cellToRug.isEmpty()) return;
        java.util.UUID worldId = event.getWorld().getUID();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        cellToRug.keySet().removeIf(cell ->
                worldId.equals(cell.worldId()) && (cell.x() >> 4) == cx && (cell.z() >> 4) == cz);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (cellToRug.isEmpty()) return;
        java.util.UUID worldId = event.getWorld().getUID();
        cellToRug.keySet().removeIf(cell -> worldId.equals(cell.worldId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (cellToRug.isEmpty()) return;
        if (!event.getBlock().isLiquid()) return;
        Block dest = event.getToBlock();
        // Check the destination block AND its 6 neighbours: water flowing next to a rug washes it.
        washIfRug(dest);
        for (BlockFace face : FACE_6) {
            washIfRug(dest.getRelative(face));
        }
    }

    private void washIfRug(Block target) {
        if (!config().isUnderlyingMaterial(target.getType())) return;
        BukkitFurniture rug = cellToRug.get(posKey(target.getLocation()));
        if (rug == null) return;
        Material underlying = target.getType();
        // Defer one tick: BlockFromToEvent fires mid-tick; re-check the block so we don't double-fire
        // if another listener already cleaned up.
        Bukkit.getRegionScheduler().run(plugin, target.getLocation(), task -> {
            if (rug.isValid() && target.getType() == underlying) {
                removeRug(rug, null, true);
            }
        });
    }

    /** Tracker first (O(1)), entity scan as fallback for rugs placed before this listener registered. */
    private BukkitFurniture resolveRugAt(Location loc) {
        BukkitFurniture rug = cellToRug.get(posKey(loc));
        if (rug != null) return rug;
        return findRugByScan(loc);
    }

    private BukkitFurniture findRugByScan(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        // Scan a small radius so we catch the base entity of a multi-cell rug when the player breaks the
        // block at its non-base cell (base entity is up to 1 block away in the rotated facing direction).
        Location center = loc.toBlockLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : loc.getWorld().getNearbyEntities(center, 1.5, 1.2, 1.5)) {
            if (!CraftEngineFurniture.isFurniture(entity)) continue;
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null) continue;
            Key id = furniture.id();
            if (id == null || !config().isRug(id.toString())) continue;
            for (Location cell : cellsOf(furniture)) {
                if (cell.getBlockX() == loc.getBlockX()
                        && cell.getBlockY() == loc.getBlockY()
                        && cell.getBlockZ() == loc.getBlockZ()) {
                    return furniture;
                }
            }
        }
        return null;
    }

    private static List<Location> cellsOf(BukkitFurniture furn) {
        List<Location> cells = new ArrayList<>();
        World world = furn.location().getWorld();
        if (world == null) return cells;
        for (FurnitureHitBox hitbox : furn.hitboxes()) {
            for (FurnitureHitboxPart part : hitbox.parts()) {
                Vec3d pos = part.pos();
                cells.add(new Location(world,
                        Math.floor(pos.x),
                        Math.floor(pos.y),
                        Math.floor(pos.z)));
            }
        }
        return cells;
    }

    private static Cell posKey(Location loc) {
        return new Cell(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** The configured underlying block for furn if it is a rug, otherwise null. */
    private Material underlyingOf(BukkitFurniture furn) {
        if (furn == null || furn.id() == null) return null;
        String id = furn.id().toString();
        return config().isRug(id) ? config().underlyingOf(id) : null;
    }
}
