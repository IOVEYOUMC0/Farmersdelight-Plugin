package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RopeBlockListener implements Listener {

    private final FarmersDelightPlugin plugin;
    // Positions already queued for a rope refresh next tick. Used to coalesce high-frequency
    // BlockPhysicsEvent storms (flowing water / redstone / pistons near ropes), so each position
    // is scanned and refreshed at most once per tick rather than once per physics event.
    private final Set<String> pendingRopeRefreshes = ConcurrentHashMap.newKeySet();
    // Tracked rope positions. Maintained by CustomBlockPlace/Break + chunk/world unload. Gives the
    // hot-path scheduleRopeRefreshIfNearby a Set.isEmpty() / Set.contains() short-circuit so servers
    // with no ropes (or no nearby ropes) skip 5 CE hasBehavior queries per BlockPhysicsEvent.
    private final Set<Cell> placedRopes = ConcurrentHashMap.newKeySet();

    private record Cell(UUID worldId, int x, int y, int z) {}

    public RopeBlockListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
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
                if (!player.getInventory().addItem(recovered).isEmpty()) {
                    world.dropItemNaturally(bottomBlock.getLocation(), recovered);
                }
            }
        }

        CraftEngineBlocks.remove(bottomBlock);
        world.playSound(bottomBlock.getLocation(), Sound.BLOCK_WOOL_BREAK, 1.0f, 1.0f);

        BlockPos bp = new BlockPos(block.getX(), bottomY, block.getZ());
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (event.getChangedType() != event.getBlock().getType()) {
            scheduleRopeRefreshIfNearby(event.getBlock());
        }
    }

    private void scheduleRopeRefreshIfNearby(Block block) {
        if (block == null) {
            return;
        }
        // Fast-path: no tracked ropes anywhere → skip the 5x cell lookups below. R-PERF-002.
        if (placedRopes.isEmpty()) {
            return;
        }

        World world = block.getWorld();
        String key = world.getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
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
            RopeBlockBehavior.refreshAdjacentRopes(world, pos);
        }, 1L);
    }

    private boolean hasNearbyRope(Block block) {
        // Use the tracked placedRopes set (O(1) hash lookups) instead of 5 CE hasBehavior queries
        // (each of which calls CraftEngineBlocks.getCustomBlockState → NMS getBlockState + Optional alloc).
        // placedRopes is maintained by CustomBlockPlace/Break + chunk/world unload. When a rope exists
        // that wasn't placed during this session (e.g. pre-existing on startup), placedRopes.isEmpty()
        // already short-circuits in scheduleRopeRefreshIfNearby before we get here, so the index is
        // authoritative for the "at least one tracked rope exists" case.
        UUID worldId = block.getWorld().getUID();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (placedRopes.contains(new Cell(worldId, x, y, z))) {
            return true;
        }
        for (BlockFace face : HORIZONTAL_FACES) {
            if (placedRopes.contains(new Cell(worldId, x + face.getModX(), y, z + face.getModZ()))) {
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
        placedRopes.add(new Cell(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRopeBreakCustom(CustomBlockBreakEvent event) {
        ImmutableBlockState state = event.blockState();
        if (state == null || state.isEmpty()) return;
        if (!CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) return;
        Block b = event.bukkitBlock();
        placedRopes.remove(new Cell(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    // Folia chunks unload without firing per-block break events, so leftover entries would linger and
    // grow placedRopes unboundedly on long-running servers. Drop entries inside the unloaded chunk.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (placedRopes.isEmpty()) return;
        UUID worldId = event.getWorld().getUID();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        placedRopes.removeIf(cell ->
                worldId.equals(cell.worldId()) && (cell.x() >> 4) == cx && (cell.z() >> 4) == cz);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (placedRopes.isEmpty()) return;
        UUID worldId = event.getWorld().getUID();
        placedRopes.removeIf(cell -> worldId.equals(cell.worldId()));
    }
}

