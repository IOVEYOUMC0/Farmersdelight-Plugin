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
import org.bukkit.entity.ItemDisplay;
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
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
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
 * Multi-cell rugs (full_tatami_mat spans two cells, per its YAML hitbox list) get one underlying
 * block per cell. "Any cell loses its support → the whole rug goes" holds because every removal path
 * of any cell's block routes through #removeRug, which destroys the furniture and clears ALL
 * of its cells at once.
 *
 * Removal paths covered — each is needed because CraftEngine's FurnitureBreakEvent fires
 * ONLY when a player attacks the furniture entity, never for programmatic removals:
 * 
 *   - player mines the underlying block (BlockBreakEvent)
 *   - support lost → block self-destructs silently (BlockPhysicsEvent / ItemSpawnEvent)
 *   - piston push (BlockPistonExtendEvent / BlockPistonRetractEvent)
 *   - liquid washes over it (BlockFromToEvent)
 *   - <b>fire burns it</b> (BlockBurnEvent) — otherwise a burnt carpet would orphan the furniture
 * 
 *
 *
 * Tracking: a map from cell position to furniture. Entries are added when a player places a rug
 * (FurniturePlace), when a chunk's entities come back (onEntitiesLoad), and for the chunks that were
 * already loaded when this listener registered (indexRugsInLoadedChunks). Without that rebuild every
 * path with no fallback lookup (water, burn, piston, explosion) would silently stop working on a rug
 * once its chunk had cycled, since FurniturePlace fires for player placement alone. Coverage is
 * therefore "every rug whose base entity has been seen loaded", not "every rug that exists": a rug
 * furniture introduced into an already-loaded chunk by something other than a player place stays
 * untracked until its chunk next cycles, which is why the break path keeps its entity-scan fallback.
 *
 * Eviction is keyed on the chunk holding the rug's BASE ENTITY, not on the chunk holding each cell,
 * so it pairs with the rebuild — onEntitiesLoad only ever sees a rug from the chunk its entity lives
 * in. Evicting per cell would drop the entry for a cell that straddles into a neighbouring chunk when
 * that neighbour unloaded, and reloading the neighbour would never restore it (it holds no rug entity).
 * The tradeoff is that a straddling cell stays mapped while its own chunk is unloaded, which is inert:
 * no block event fires for an unloaded cell, and the entry goes when the entity's chunk unloads.
 */
public final class RugListener implements Listener {

    private static final BlockFace[] FACE_6 = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final BlockFace[] HORIZONTAL = {
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
            plugin.scheduler().runLaterAt(cell, () -> {
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
        // 底层方块放置后，更新新地毯自身及相邻地毯的变体
        if (world != null && !cellsOf(furn).isEmpty()) {
            List<Location> cells = cellsOf(furn);
            Location anchor = event.location();
            plugin.scheduler().runLaterAt(anchor, () -> {
                if (furn.isValid()) {
                    updateRugVariant(furn);
                    refreshAdjacentRugs(world, cells);
                }
            }, UNDERLYING_PLACE_DELAY_TICKS + 2);
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
     * #onFurnitureBreak for programmatic removals. Capture the cells + material WHILE the
     * furniture is still valid, remove it, then clear each cell.
     *
     * dropLoot whether the rug drops its item (true for break/support/piston/water; false for fire)
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
            plugin.scheduler().runAt(cell, () -> {
                Block b = world.getBlockAt(cell);
                if (b.getType() == underlying) {
                    b.setType(Material.AIR, false);
                }
            });
        }
        // 底层方块清除后，刷新相邻地毯的变体
        if (!cells.isEmpty()) {
            Location anchor = cells.getFirst();
            plugin.scheduler().runLaterAt(anchor, () -> refreshAdjacentRugs(world, cells), 2);
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
        // Cheap gates protect the hot path: ① empty-map check skips everything when no rugs exist,
        // ② carpet-material check, ③ O(1) map lookup. The posKey is allocated for every carpet-material
        // physics event (tracked or not), because the key must be built to perform the lookup.
        if (cellToRug.isEmpty()) return;
        Block block = event.getBlock();
        if (!config().isUnderlyingMaterial(block.getType())) return;
        BukkitFurniture rug = cellToRug.get(posKey(block.getLocation()));
        if (rug == null) return;
        plugin.scheduler().runAt(block.getLocation(), () -> {
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
            plugin.scheduler().runAt(anchor, () -> {
                if (rug.isValid()) {
                    removeRug(rug, null, true);
                }
            });
        }
    }

    // Repopulates cellToRug for a chunk as its entities come back, undoing onChunkUnload. Rug furnitures
    // are only ever added to the map by FurniturePlace, which fires for player placement and nothing
    // else, so a rug that survived a chunk roundtrip (or predates this listener) would otherwise never be
    // tracked again.
    // EntitiesLoadEvent rather than ChunkLoadEvent: entity sections load independently of block sections,
    // so a chunk's entities are not necessarily present when ChunkLoadEvent fires, and CraftEngine
    // populates its furniture registry from this very event at LOWEST priority — running at MONITOR is
    // what guarantees the furniture handles are resolvable here. The event is delivered on the region
    // owning the chunk, so the furniture entities are read on the correct thread.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        // No configured rugs at all: nothing can ever be tracked, so skip before walking the entity list.
        if (config().isEmpty()) return;
        for (Entity entity : event.getEntities()) {
            indexRugEntity(entity);
        }
    }

    // Chunks whose entities were already loaded when this listener registered never fire
    // EntitiesLoadEvent, so the rugs in them — typically the spawn area, which often never unloads —
    // would stay untracked for the lifetime of the server. Each chunk is scanned on its own region.
    public void indexRugsInLoadedChunks() {
        if (config().isEmpty()) return;
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                plugin.scheduler().runAt(world, chunkX, chunkZ, () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) return;
                    for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                        indexRugEntity(entity);
                    }
                });
            }
        }
    }

    // Adds every footprint cell of the entity to the tracker if it is a rug furniture.
    // Gate order matters: this runs for every entity in a loading chunk (mobs, items, arrows, XP orbs),
    // so the cheap type check comes first — CraftEngine only ever registers ItemDisplay as a furniture
    // meta entity. The registry lookup that follows is an int-keyed map get and is already conclusive:
    // a non-null result proves the entity is a registered furniture, so probing the entity's persistent
    // data container first would only add a deserialize per entity for a weaker answer. R-PERF-002.
    private void indexRugEntity(Entity entity) {
        if (!(entity instanceof ItemDisplay)) return;
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        // underlyingOf resolves the rug by its CraftEngine furniture id, never by Bukkit material, and
        // returns null for any furniture that is not a configured rug.
        if (furniture == null || underlyingOf(furniture) == null) return;
        for (Location cell : cellsOf(furniture)) {
            cellToRug.put(posKey(cell), furniture);
        }
    }

    /**
     * Folia chunks unload without firing FurnitureBreakEvent, so cellToRug entries for a departed rug
     * would linger and keep the (now-despawned) furniture's ItemDisplay strong-referenced — blocking GC
     * of every rug ever loaded on a long-running server.
     *
     * Matching is on the chunk of the rug's base entity, so ALL cells of a rug leave together exactly
     * when the furniture despawns, including cells that straddle into a neighbouring chunk. Matching per
     * cell instead would evict a straddling cell when its own chunk unloaded, and the rebuild — which
     * only sees a rug from the chunk its entity is in — would never put it back.
     *
     * BukkitFurniture.location() reads a field cached at spawn/teleport, touching neither the entity nor
     * any chunk, so it is safe to call for a rug owned by another region (R-CONC-006).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (cellToRug.isEmpty()) return;
        java.util.UUID worldId = event.getWorld().getUID();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        cellToRug.entrySet().removeIf(entry -> {
            Location base = entry.getValue().location();
            World baseWorld = base == null ? null : base.getWorld();
            if (baseWorld == null) {
                // Base position unusable (world already gone): fall back to the cell's own chunk so the
                // entry can still be reclaimed rather than pinned for the server's lifetime.
                Cell cell = entry.getKey();
                return worldId.equals(cell.worldId()) && (cell.x() >> 4) == cx && (cell.z() >> 4) == cz;
            }
            return worldId.equals(baseWorld.getUID())
                    && (base.getBlockX() >> 4) == cx && (base.getBlockZ() >> 4) == cz;
        });
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
        plugin.scheduler().runAt(target.getLocation(), () -> {
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
        World world = loc.getWorld();
        // Scan chunk entity list instead of getNearbyEntities to avoid blocking on Folia region threads
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return null;
        List<Entity> chunkEntities = java.util.Arrays.asList(world.getChunkAt(cx, cz).getEntities());
        // Scan a small radius so we catch the base entity of a multi-cell rug when the player breaks the
        // block at its non-base cell (base entity is up to 1 block away in the rotated facing direction).
        Location center = loc.toBlockLocation().add(0.5, 0.5, 0.5);
        double minX = center.getX() - 1.5;
        double minY = center.getY() - 1.2;
        double minZ = center.getZ() - 1.5;
        double maxX = center.getX() + 1.5;
        double maxY = center.getY() + 1.2;
        double maxZ = center.getZ() + 1.5;
        for (Entity entity : chunkEntities) {
            if (!CraftEngineFurniture.isFurniture(entity)) continue;
            Location eloc = entity.getLocation();
            if (eloc.getX() < minX || eloc.getX() > maxX
                    || eloc.getY() < minY || eloc.getY() > maxY
                    || eloc.getZ() < minZ || eloc.getZ() > maxZ) continue;
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

    /**
     * 根据四周相邻地毯底层方块计算当前应使用的变体名称。
     * 优先级：全包 > 对边相连 > 单边相连 > 独立
     */
    private String computeRugVariant(World world, BukkitFurniture furn) {
        java.util.Set<BlockFace> connected = java.util.EnumSet.noneOf(BlockFace.class);
        for (Location cell : cellsOf(furn)) {
            for (BlockFace face : HORIZONTAL) {
                if (connected.contains(face)) continue;
                Location neighbor = cell.clone().add(face.getDirection());
                if (config().isUnderlyingMaterial(world.getBlockAt(neighbor).getType())) {
                    connected.add(face);
                }
            }
        }
        boolean north = connected.contains(BlockFace.NORTH);
        boolean south = connected.contains(BlockFace.SOUTH);
        boolean east = connected.contains(BlockFace.EAST);
        boolean west = connected.contains(BlockFace.WEST);

        if (north && south && east && west) return "surrounded";
        if (north && south) return "no_north_south";
        if (east && west) return "no_east_west";
        if (north) return "no_north";
        if (south) return "no_south";
        if (east) return "no_east";
        if (west) return "no_west";
        return "standalone";
    }

    /**
     * 更新单个地毯的变体（根据相邻连接状态选择正确的变体名称并调用 setVariant）。
     */
    private void updateRugVariant(BukkitFurniture furn) {
        if (furn == null || !furn.isValid()) return;
        Location loc = furn.location();
        if (loc == null || loc.getWorld() == null) return;
        String target = computeRugVariant(loc.getWorld(), furn);
        furn.setVariant(target, true);
    }

    /**
     * 刷新指定位置四周相邻地毯的变体。
     */
    private void refreshAdjacentRugs(World world, List<Location> cells) {
        if (world == null || cells.isEmpty()) return;
        java.util.Set<BukkitFurniture> seen = new java.util.HashSet<>();
        for (Location cell : cells) {
            for (BlockFace face : HORIZONTAL) {
                Location neighbor = cell.clone().add(face.getDirection());
                BukkitFurniture neighborRug = cellToRug.get(posKey(neighbor));
                if (neighborRug != null && neighborRug.isValid() && seen.add(neighborRug)) {
                    updateRugVariant(neighborRug);
                }
            }
        }
    }

    /**
     * 当 item_display 实体被非正常移除（如 /kill 命令）时，CE 家具实体丢失但底层方块仍保留。
     * 此监听器检测已追踪地毯的元实体被移除，并清理残留的底层方块和 cellToRug 追踪。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemoved(EntityRemoveEvent event) {
        if (cellToRug.isEmpty()) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof ItemDisplay)) return;

        Location loc = entity.getLocation();
        if (loc.getWorld() == null) return;
        // 跳过区块卸载——已由 onChunkUnload 处理
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!loc.getWorld().isChunkLoaded(cx, cz)) return;

        // 元实体被移除时 CE 已将其从家具注册表中清除，getLoadedFurnitureByMetaEntity 返回 null
        // 因此遍历 cellToRug 按位置匹配，找到该实体对应的地毯并清理
        World world = loc.getWorld();
        java.util.UUID worldId = world.getUID();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        java.util.Set<BukkitFurniture> orphaned = new java.util.HashSet<>();
        for (java.util.Map.Entry<Cell, BukkitFurniture> entry : cellToRug.entrySet()) {
            Cell cell = entry.getKey();
            if (!cell.worldId().equals(worldId)) continue;
            // 元实体通常位于地毯锚点位置，匹配约1格范围
            if (Math.abs(cell.x() - bx) <= 1 && cell.y() == by && Math.abs(cell.z() - bz) <= 1) {
                orphaned.add(entry.getValue());
            }
        }

        for (BukkitFurniture furn : orphaned) {
            Material underlying = underlyingOf(furn);
            // 从追踪表中清除该家具的所有格子
            java.util.List<Location> rugCells = new java.util.ArrayList<>();
            cellToRug.entrySet().removeIf(e -> {
                if (e.getValue() == furn) {
                    rugCells.add(new Location(world, e.getKey().x(), e.getKey().y(), e.getKey().z()));
                    return true;
                }
                return false;
            });
            // 清除底层方块
            if (underlying != null && !rugCells.isEmpty()) {
                clearUnderlying(world, rugCells, underlying);
            } else if (!rugCells.isEmpty()) {
                for (Location c : rugCells) {
                    plugin.scheduler().runAt(c, () -> {
                        Block b = world.getBlockAt(c);
                        if (config().isUnderlyingMaterial(b.getType())) {
                            b.setType(Material.AIR, false);
                        }
                    });
                }
            }
        }
    }
}
