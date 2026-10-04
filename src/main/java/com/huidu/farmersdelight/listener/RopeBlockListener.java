package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.scheduler.RegionTasks;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.CESection;
import net.momirealms.craftengine.core.world.chunk.PalettedContainer;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RopeBlockListener implements Listener {

    private final FarmersDelightPlugin plugin;
    // Positions already queued for a rope refresh next tick. Used to coalesce bursts of neighbour updates
    // (flowing water / redstone / pistons near ropes), so each position is scanned and refreshed at most
    // once per tick rather than once per update.
    private final Set<Cell> pendingRopeRefreshes = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    // Tracked rope positions. Maintained by CustomBlockPlace/Break, rebuilt per chunk on ChunkLoad, and
    // drained on chunk/world unload. Gives scheduleRopeRefreshIfNearby a Set.isEmpty() / Set.contains()
    // short-circuit so a place or break with no rope nearby skips 5 CE hasBehavior queries.
    // Coverage is "ropes placed through CustomBlockPlace, ropes this plugin writes itself and reports
    // through syncRopeIndex, plus every rope in a chunk that has loaded since this listener registered" —
    // not "every rope that exists". A rope written straight into an already-loaded chunk by an external
    // writer (WorldEdit, /ce setblock, another plugin calling CraftEngineBlocks.place) fires no
    // CustomBlockPlaceEvent and gets no ChunkLoad rebuild, so it stays outside the index and its connected
    // texture is not refreshed until its chunk next cycles. The global isEmpty() gate does not save that
    // case: any other rope on the server keeps the set non-empty, so the miss lands on contains() instead.
    // Tracked rope positions, bucketed by chunk. A flat set meant every chunk unload had to scan every
    // rope on the server to find the handful in that chunk; the bucket is dropped whole instead.
    private final Map<ChunkCell, Set<Cell>> placedRopes = new ConcurrentHashMap<>();

    private record ChunkCell(UUID worldId, int chunkX, int chunkZ) {
    }

    private static ChunkCell chunkOf(Cell cell) {
        return new ChunkCell(cell.worldId(), cell.x() >> 4, cell.z() >> 4);
    }

    // The insert happens inside compute so it is atomic against removeRope's drop-if-empty:
    // computeIfAbsent(..).add(..) would publish the set, release the bin lock and only then add, long
    // enough for a concurrent remove to observe an empty set and discard the whole mapping.
    private void addRope(Cell cell) {
        placedRopes.compute(chunkOf(cell), (ignored, cells) -> {
            Set<Cell> target = cells != null ? cells : ConcurrentHashMap.newKeySet();
            target.add(cell);
            return target;
        });
    }

    private void removeRope(Cell cell) {
        placedRopes.computeIfPresent(chunkOf(cell), (ignored, cells) -> {
            cells.remove(cell);
            return cells.isEmpty() ? null : cells;
        });
    }

    private boolean hasRope(Cell cell) {
        Set<Cell> cells = placedRopes.get(chunkOf(cell));
        return cells != null && cells.contains(cell);
    }

    // The registered listener, so rope code outside this class can keep the index honest. Replaced whenever a
    // listener is constructed, which on a plugin reload hands the index over to the new instance.
    private static volatile RopeBlockListener active;

    private static final int SECTION_VOLUME = 16 * 16 * 16;

    private record Cell(UUID worldId, int x, int y, int z) {}

    public RopeBlockListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        active = this;
    }

    /**
     * Detaches the instance used by CraftEngine neighbour callbacks during plugin shutdown.
     */
    public void shutdown() {
        if (active == this) {
            active = null;
        }
        stopped = true;
        pendingRopeRefreshes.clear();
        placedRopes.clear();
    }

    // Brings the index in line with what actually stands at pos. Ropes written straight into the world with
    // CraftEngineBlocks.place or removed with CraftEngineBlocks.remove produce no CustomBlockPlace/BreakEvent,
    // so without this the reel-down and retract paths would leave the index disagreeing with the world. The
    // index is the sole verdict for every refresh entry point, so a rope missing from it never refreshes again.
    public static void syncRopeIndex(World world, BlockPos pos) {
        RopeBlockListener listener = active;
        if (listener == null) {
            return;
        }
        Cell cell = new Cell(world.getUID(), pos.x(), pos.y(), pos.z());
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (CustomBlockUtils.hasBehavior(block, RopeBlockBehavior.class)) {
            listener.addRope(cell);
        } else {
            listener.removeRope(cell);
        }
    }

    // Entry point for RopeBlockBehavior's updateShape/neighborChanged hooks, which replaced a
    // BlockPhysicsEvent listener that ran for every block update in the world. Those hooks fire on the rope
    // itself, so the nearby-rope test the other callers need is already satisfied and only the per-position
    // dedupe and the one-tick defer are left.
    public static void queueNeighborRefresh(World world, BlockPos pos) {
        RopeBlockListener listener = active;
        if (listener == null || world == null || pos == null) {
            return;
        }
        listener.queueRefresh(world, pos);
    }

    private void queueRefresh(World world, BlockPos pos) {
        Cell key = new Cell(world.getUID(), pos.x(), pos.y(), pos.z());
        if (!pendingRopeRefreshes.add(key)) {
            return;
        }
        plugin.scheduler().runLaterAt(new Location(world, pos.x(), pos.y(), pos.z()), () -> {
            pendingRopeRefreshes.remove(key);
            if (stopped) {
                return;
            }
            RopeBlockBehavior.refreshAdjacentRopes(world, pos);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRopeRetract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) return;
        if (event.hand() != InteractionHand.MAIN_HAND) return;

        Player player = event.player();
        if (!player.isSneaking()) return;

        if (!CustomBlockUtils.hasBehavior(event.blockState(), RopeBlockBehavior.class)) return;

        Block block = event.bukkitBlock();
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.ROPE)) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!mainHand.getType().isAir()) return;

        event.setCancelled(true);

        World world = block.getWorld();
        int bottomY = block.getY();
        int checkY = block.getY() - 1;
        while (checkY >= world.getMinHeight()) {
            Block below = world.getBlockAt(block.getX(), checkY, block.getZ());
            if (CustomBlockUtils.hasBehavior(below, RopeBlockBehavior.class)) {
                bottomY = checkY;
                checkY--;
            } else {
                break;
            }
        }

        Block bottomBlock = world.getBlockAt(block.getX(), bottomY, block.getZ());
        if (!ProtectionCompat.canBuild(player, bottomBlock, ProtectionCompat.Feature.ROPE)) return;
        boolean isCreative = player.getGameMode() == GameMode.CREATIVE;

        if (!isCreative) {
            ItemStack recovered = RopeBlockBehavior.createItemForRopeBlock(bottomBlock);
            if (recovered != null) {
                ItemDelivery.giveOrDrop(player, bottomBlock.getLocation(), recovered);
            }
        }

        CraftEngineBlocks.remove(bottomBlock);
        world.playSound(bottomBlock.getLocation(), Sound.BLOCK_WOOL_BREAK, 1.0f, 1.0f);
        player.swingMainHand();

        BlockPos bp = new BlockPos(block.getX(), bottomY, block.getZ());
        // CraftEngineBlocks.remove fires no CustomBlockBreakEvent, so drop the cell here rather than waiting
        // for the scheduled refresh to notice.
        removeRope(new Cell(world.getUID(), bp.x(), bp.y(), bp.z()));
        plugin.scheduler().runAt(bottomBlock.getLocation(),
                () -> RopeBlockBehavior.refreshAdjacentRopes(world, bp));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        scheduleRopeRefreshIfNearby(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        scheduleRopeRefreshIfNearby(event.getBlock());
    }

    private void scheduleRopeRefreshIfNearby(Block block) {
        if (block == null) {
            return;
        }
        // Skip cell lookups when no ropes are tracked.
        if (placedRopes.isEmpty()) {
            return;
        }

        World world = block.getWorld();
        Cell key = new Cell(world.getUID(), block.getX(), block.getY(), block.getZ());
        // Already queued for this position this tick: skip the nearby-rope scan and rescheduling.
        if (pendingRopeRefreshes.contains(key)) {
            return;
        }
        if (!hasNearbyRope(block)) {
            return;
        }
        if (!pendingRopeRefreshes.add(key)) {
            return;
        }

        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        plugin.scheduler().runLaterAt(block.getLocation(), () -> {
            pendingRopeRefreshes.remove(key);
            if (stopped) {
                return;
            }
            RopeBlockBehavior.refreshAdjacentRopes(world, pos);
        }, 1L);
    }

    private boolean hasNearbyRope(Block block) {
        // Use the tracked placedRopes set (O(1) hash lookups) instead of 5 CE hasBehavior queries
        // (each of which calls CraftEngineBlocks.getCustomBlockState → NMS getBlockState + Optional alloc).
        // This is the sole nearby-rope test for every refresh entry point, so a rope missing from the
        // index fails contains() and never refreshes. indexRopesInChunk closes the large gap — ropes placed
        // before the server started, or before this listener existed — by repopulating from the CraftEngine
        // chunk on load, but it does not make contains() authoritative: a rope introduced into a loaded chunk
        // without a CustomBlockPlaceEvent stays missing until that chunk cycles (see placedRopes).
        UUID worldId = block.getWorld().getUID();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (hasRope(new Cell(worldId, x, y, z))) {
            return true;
        }
        for (BlockFace face : HORIZONTAL_FACES) {
            if (hasRope(new Cell(worldId, x + face.getModX(), y, z + face.getModZ()))) {
                return true;
            }
        }
        return false;
    }

    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRopePlace(CustomBlockPlaceEvent event) {
        ImmutableBlockState state = event.blockState();
        if (state == null || state.isEmpty()) return;
        if (!CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) return;
        Block b = event.bukkitBlock();
        addRope(new Cell(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRopeBreakCustom(CustomBlockBreakEvent event) {
        ImmutableBlockState state = event.blockState();
        if (state == null || state.isEmpty()) return;
        if (!CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) return;
        Block b = event.bukkitBlock();
        removeRope(new Cell(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    // Rebuild rope positions from the loading chunk's states; ropes have no block entity to enumerate.
    // ChunkLoadEvent runs on the owning region. Index insertion is idempotent for repeated loads.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        indexRopesInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    // Chunks that were already loaded when this listener registered never fire ChunkLoadEvent, so the
    // ropes in them (typically the spawn area, which often never unloads) would stay outside the index
    // for the lifetime of the server. Each chunk is indexed on its own region.
    public void indexRopesInLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                RegionTasks.runAtLoadedChunk(plugin.scheduler(), world, chunkX, chunkZ,
                        () -> indexRopesInChunk(world, chunkX, chunkZ));
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private void indexRopesInChunk(World world, int chunkX, int chunkZ) {
        CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld == null) {
            return;
        }
        CEChunk ceChunk = ceWorld.getChunkAtIfLoaded(chunkX, chunkZ);
        if (ceChunk == null) {
            return;
        }

        UUID worldId = world.getUID();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (CESection section : ceChunk.sections()) {
            if (section == null || !sectionMayContainRope(section, world, chunkX, chunkZ)) {
                continue;
            }
            int baseY = section.sectionY() << 4;
            for (int index = 0; index < SECTION_VOLUME; index++) {
                ImmutableBlockState state = section.getBlockState(index);
                if (!CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) {
                    continue;
                }
                addRope(new Cell(worldId,
                        baseX + (index & 15),
                        baseY + ((index >> 8) & 15),
                        baseZ + ((index >> 4) & 15)));
            }
        }
    }

    // One-shot latch so a persistently broken world logs once instead of once per section per chunk load.
    private static final AtomicBoolean PALETTE_FAILURE_LOGGED = new AtomicBoolean();

    // Palette probe: a CraftEngine section stores its states in a paletted container, so the distinct
    // states of all 4096 positions are a handful of palette entries. Testing those decides whether the
    // section can hold a rope at all, and only a section that proves it does gets its positions walked.
    //
    // The two probe calls are caught separately on purpose, because both failure modes are
    // IllegalStateException and their messages do not separate them: PalettedContainer.isEmpty reads
    // palette entry 0 directly, so a single-entry palette that has never been written reports the same
    // "Missing Palette entry" text that a genuinely damaged palette would. Which call threw is the
    // reliable discriminator — isEmpty throwing is the ordinary empty-section case, while hasAny
    // throwing means the container changed shape after isEmpty had just proved it readable.
    private boolean sectionMayContainRope(CESection section, World world, int chunkX, int chunkZ) {
        PalettedContainer<ImmutableBlockState> states = section.statesContainer;
        if (states == null) {
            return false;
        }
        try {
            if (states.isEmpty()) {
                return false;
            }
        } catch (IllegalStateException uninitialisedPalette) {
            // Single-entry palette carrying no entry: the section holds no states, so no rope to index.
            return false;
        }
        try {
            return states.hasAny(state -> CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class));
        } catch (IllegalStateException unreadablePalette) {
            // Reporting rather than swallowing: returning "no ropes here" for a section that could not
            // be read leaves its ropes outside the index, which silently reinstates the stale-index
            // defect this rebuild exists to fix. The skip still has to happen, but not in silence.
            if (PALETTE_FAILURE_LOGGED.compareAndSet(false, true)) {
                I18n.logWarning("rope.palette_read_failed",
                        "section_y", section.sectionY(),
                        "chunk_x", chunkX,
                        "chunk_z", chunkZ,
                        "world", world.getName(),
                        "error", unreadablePalette);
            }
            return false;
        }
    }

    // Folia chunks unload without firing per-block break events, so leftover entries would linger and
    // grow placedRopes unboundedly on long-running servers. Drop entries inside the unloaded chunk.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (placedRopes.isEmpty()) return;
        UUID worldId = event.getWorld().getUID();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        // One bucket lookup instead of a scan over every tracked rope on the server.
        placedRopes.remove(new ChunkCell(worldId, cx, cz));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (placedRopes.isEmpty()) return;
        UUID worldId = event.getWorld().getUID();
        placedRopes.keySet().removeIf(key -> worldId.equals(key.worldId()));
    }
}
