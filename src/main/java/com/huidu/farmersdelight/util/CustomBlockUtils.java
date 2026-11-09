package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CustomBlockUtils {

    /**
     * True when the calling thread owns the block's region.
     *
     * <p>CraftEngine hands a block's container to vanilla hopper logic on whatever thread ticks the hopper, and
     * the block-entity stores are only safe to read/write from their own region's thread. Callers of
     * {@code getContainer} fail closed with this check instead of touching the controller from a foreign region.
     * Off Folia the scheduler answers true, so Paper behaviour is unchanged.
     */
    public static boolean ownsBlockRegion(FarmersDelightPlugin plugin, World world, BlockPos pos) {
        if (plugin == null || world == null || pos == null) {
            return false;
        }
        return plugin.scheduler().isOwnedByCurrentRegion(new Location(world, pos.x(), pos.y(), pos.z()));
    }

    private CustomBlockUtils() {
    }

    public static ImmutableBlockState getState(Block block) {
        if (block == null) {
            return null;
        }
        return CraftEngineBlocks.getCustomBlockState(block);
    }

    public static ImmutableBlockState getState(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return getState(location.getBlock());
    }

    /**
     * CE state of a block whose chunk is already resident, null when it is not. The ordinary getState
     * reads through the level, which loads a missing chunk synchronously and fires that chunk's entity-load
     * events — never what a background or neighbour scan wants. Callers that may run next to an unloaded chunk
     * (random ticks, redstone/neighbour hooks, delayed tasks) must use this variant.
     */
    public static ImmutableBlockState getStateIfResident(Block block) {
        if (block == null || block.getWorld() == null) {
            return null;
        }
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return null;
        }
        return getState(block);
    }

    public static ImmutableBlockState getStateIfResident(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return getStateIfResident(location.getBlock());
    }

    public static String getId(Block block) {
        return getId(getState(block));
    }

    public static String getId(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            return normalizeId(state.owner().value().id().toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean hasId(ImmutableBlockState state, String blockId) {
        return normalizeId(blockId).equals(getId(state));
    }

    public static boolean hasId(Block block, String blockId) {
        return normalizeId(blockId).equals(getId(block));
    }

    /** Like hasId(Block, String), but false instead of loading the chunk when it is not resident. */
    public static boolean hasIdIfResident(Block block, String blockId) {
        return normalizeId(blockId).equals(getId(getStateIfResident(block)));
    }

    public static boolean hasId(Location location, String blockId) {
        return location != null && location.getWorld() != null && hasId(location.getBlock(), blockId);
    }

    public static boolean hasBehavior(ImmutableBlockState state, Class<? extends BlockBehavior> behaviorClass) {
        return getBehavior(state, behaviorClass) != null;
    }

    public static <T extends BlockBehavior> T getBehavior(ImmutableBlockState state, Class<T> behaviorClass) {
        if (state == null || state.isEmpty()) return null;
        BlockBehavior behavior = state.behavior();
        if (behavior == null) return null;
        T matched = behavior.getFirst(behaviorClass);
        if (matched != null) return matched;
        return behaviorClass.isInstance(behavior) ? behaviorClass.cast(behavior) : null;
    }

    public static <T extends BlockBehavior> T getBehavior(Block block, Class<T> behaviorClass) {
        return getBehavior(getState(block), behaviorClass);
    }

    public static <T extends BlockBehavior> T getBehavior(Location location, Class<T> behaviorClass) {
        return location != null && location.getWorld() != null
                ? getBehavior(location.getBlock(), behaviorClass)
                : null;
    }

    private static final AtomicBoolean CE_WORLD_LOAD_FAILURE_LOGGED = new AtomicBoolean();

    public static CEWorld getCEWorld(World world) {
        if (world == null) {
            return null;
        }
        BukkitWorldManager worldManager = BukkitWorldManager.instance();
        if (worldManager == null) {
            return null;
        }
        try {
            // Before CE binds its blocks, getWorld() deserializes chunk data against an unbound registry.
            if (!ItemUtils.isAnyCustomItemLoaded()) {
                return null;
            }
            return resolveCEWorld(worldManager, world.getUID());
        } catch (RuntimeException | LinkageError e) {
            // A saved block from an uninstalled pack (or a CE deserialize race) must not crash the caller.
            if (CE_WORLD_LOAD_FAILURE_LOGGED.compareAndSet(false, true)) {
                Bukkit.getServer().getLogger().warning("[FarmersDelight] Skipped a chunk whose CraftEngine block data could not be loaded: " + e);
            }
            return null;
        }
    }

    // Some CraftEngine builds hand back the CEWorld directly, others the platform World wrapper.
    // Accepting both keeps this working across a CE upgrade instead of throwing ClassCastException.
    private static CEWorld resolveCEWorld(BukkitWorldManager worldManager, UUID uuid) {
        Object world = worldManager.getWorld(uuid);
        if (world instanceof CEWorld ceWorld) {
            return ceWorld;
        }
        if (world instanceof net.momirealms.craftengine.core.world.World platformWorld) {
            return platformWorld.ceWorld();
        }
        return null;
    }

    public static World getBukkitWorld(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.world == null) {
            return null;
        }
        if (blockEntity.world.world() != null) {
            Object platformWorld = blockEntity.world.world().platformWorld();
            if (platformWorld instanceof World world) {
                return world;
            }
        }
        return Bukkit.getWorld(blockEntity.world.uuid());
    }

    public static void markBlockEntityDirty(World world, BlockPosKey posKey) {
        if (posKey == null) return;
        CEWorld ceWorld = getCEWorld(world);
        if (ceWorld != null) {
            try {
                ceWorld.blockEntityChanged(posKey.toBlockPos());
            } catch (RuntimeException | LinkageError failure) {
                // A settlement (store result, then consume) runs this push between its two halves; letting a
                // CraftEngine failure escape would leave the result stored and the ingredients unconsumed, so the
                // next tick would produce a second result from the same ingredients. The world copy catches up on
                // the next push instead.
                I18n.logDetail("block", "block.block_entity_sync_failed", "error", String.valueOf(failure));
            }
        }
    }

    public static void markBlockEntityDirty(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.world == null) {
            return;
        }
        try {
            blockEntity.world.blockEntityChanged(blockEntity.pos);
        } catch (RuntimeException | LinkageError failure) {
            // Same reason as the (World, BlockPosKey) overload: this push can sit between the two halves of a
            // settlement, and a CraftEngine failure escaping here would let the same ingredients produce twice.
            I18n.logDetail("block", "block.block_entity_sync_failed", "error", String.valueOf(failure));
        }
    }

    public static void removeCraftEngineBlockEntity(World world, BlockPosKey posKey) {
        if (posKey == null) return;
        CEWorld ceWorld = getCEWorld(world);
        if (ceWorld == null) {
            return;
        }
        try {
            CEChunk chunk = ceWorld.getChunkAtIfLoaded(posKey.toBlockPos());
            if (chunk == null) {
                return;
            }
            chunk.removeBlockEntity(posKey.toBlockPos());
            chunk.setUnsaved(true);
        } catch (Exception ignored) {
        }
    }

    public static <T extends BlockEntityController> boolean notifyControllerChanged(World world,
                                                                                   BlockPosKey posKey,
                                                                                   Class<T> controllerClass,
                                                                                   Integer controllerId,
                                                                                   Consumer<T> action) {
        if (posKey == null || controllerClass == null || action == null) {
            return false;
        }

        CEWorld ceWorld = getCEWorld(world);
        if (ceWorld == null) {
            return false;
        }

        try {
            BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(posKey.toBlockPos());
            if (blockEntity == null) {
                return false;
            }

            boolean[] changed = {false};
            if (controllerId != null) {
                blockEntity.controller.let(controllerClass, controllerId, controller -> {
                    action.accept(controller);
                    changed[0] = true;
                });
            }

            if (!changed[0]) {
                blockEntity.controller.let(controllerClass, controller -> {
                    action.accept(controller);
                    changed[0] = true;
                });
            }

            return changed[0];
        } catch (Exception ignored) {
            return false;
        }
    }

    public static CompoundTag getNestedComponentCompound(Item item, Key componentKey, String nestedKey) {
        if (item == null || componentKey == null || nestedKey == null || nestedKey.isBlank()) {
            return null;
        }

        CompoundTag tag = getComponentCompound(item, componentKey);
        return tag == null ? null : tag.getCompound(nestedKey);
    }

    public static CompoundTag getComponentCompound(Item item, Key componentKey) {
        if (item == null || componentKey == null) {
            return null;
        }

        Tag component;
        try {
            component = item.getComponentAsSparrowTag(componentKey);
        } catch (RuntimeException ignored) {
            return null;
        }

        if (!(component instanceof CompoundTag tag)) {
            return null;
        }
        return tag;
    }

    public static boolean hasBehavior(Block block, Class<? extends BlockBehavior> behaviorClass) {
        return hasBehavior(getState(block), behaviorClass);
    }

    public static boolean hasBehavior(Location location, Class<? extends BlockBehavior> behaviorClass) {
        return location != null && location.getWorld() != null
                && hasBehavior(location.getBlock(), behaviorClass);
    }

    public static String normalizeId(String rawId) {
        if (rawId == null) {
            return null;
        }

        String trimmed = rawId.trim();
        int slashIndex = trimmed.lastIndexOf('/');
        if (slashIndex >= 0) {
            trimmed = trimmed.substring(slashIndex + 1).trim();
        }

        if (trimmed.endsWith("]")) {
            int bracketIndex = trimmed.lastIndexOf('[');
            if (bracketIndex >= 0) {
                trimmed = trimmed.substring(0, bracketIndex).trim();
            } else {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
        }

        return trimmed;
    }

    public static BlockFace getFacing(Block block) {
        ImmutableBlockState state = getState(block);
        BlockFace stateFacing = getFacing(state);
        if (stateFacing != null) {
            return stateFacing;
        }

        if (block != null) {
            var blockData = block.getBlockData();
            if (blockData instanceof Directional directional) {
                return directional.getFacing();
            }
        }

        return BlockFace.NORTH;
    }

    public static BlockFace getFacing(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        Object value = getPropertyValue(state, "facing");
        if (value == null) {
            return null;
        }
        // CE's direction-typed properties carry the engine's Direction enum; mapping it directly
        // skips the toString + string switch on per-tick callers.
        if (value instanceof Direction direction) {
            return switch (direction) {
                case SOUTH -> BlockFace.SOUTH;
                case EAST -> BlockFace.EAST;
                case WEST -> BlockFace.WEST;
                default -> BlockFace.NORTH;
            };
        }
        return parseFacing(value.toString());
    }

    private static Object getPropertyValue(ImmutableBlockState state, String propertyName) {
        Property<?> property = state.getProperty(propertyName);
        if (property != null) {
            return state.getNullable(property);
        }
        for (Property<?> candidate : state.getProperties()) {
            if (candidate.name().equalsIgnoreCase(propertyName)) {
                return state.get(candidate);
            }
        }
        return null;
    }

    public static BlockFace getFullFacing(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        Object value = getPropertyValue(state, "facing");
        if (value == null) {
            return null;
        }
        if (value instanceof Direction direction) {
            return switch (direction) {
                case UP -> BlockFace.UP;
                case DOWN -> BlockFace.DOWN;
                case NORTH -> BlockFace.NORTH;
                case SOUTH -> BlockFace.SOUTH;
                case WEST -> BlockFace.WEST;
                case EAST -> BlockFace.EAST;
            };
        }
        return switch (value.toString().toLowerCase(Locale.ROOT)) {
            case "up" -> BlockFace.UP;
            case "down" -> BlockFace.DOWN;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public static BlockFace parseFacing(String facingValue) {
        return switch (facingValue.toLowerCase(Locale.ROOT)) {
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public static String getPropertyString(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) return null;
        Object value = getPropertyValue(state, propertyName);
        return value != null ? value.toString() : null;
    }

    public static Integer getPropertyInt(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) return null;
        Object value = getPropertyValue(state, propertyName);
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    public static float getYRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> 0.0f;
            case WEST -> -90.0f;
            case EAST -> 90.0f;
            default -> 180.0f;
        };
    }
}
