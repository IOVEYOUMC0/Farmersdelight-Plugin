package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RichSoilFarmlandBlockBehavior extends FarmersDelightBlockBehavior {

    private static final int MAX_MOISTURE = 7;
    private static final String MOISTURE_PROPERTY = "moisture";

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private final float boostChance;
    private final Property<Integer> moistureProperty;
    private final Key richSoilBlockId;
    // Null when the configuration declares no unaffected-blocks key of its own, in which case the list is
    // borrowed from the rich soil block named by rich-soil-block. An explicitly configured empty list is a
    // parsed empty set, not null, so writing an empty list does disable the exclusions.
    private final ConfiguredBlockSet configuredUnaffectedBlocks;
    // Resolved copy of the borrowed list. The rich soil block may not be registered yet when this behavior is
    // built, so the lookup is deferred to first use and cached once it succeeds. Written from the tick thread
    // that first resolves it and read from every tick thread, hence volatile; the referenced set is immutable
    // and the field only ever moves from null to a fully built set, so a racing reader either misses the cache
    // and repeats the lookup or sees the finished set.
    private volatile ConfiguredBlockSet borrowedUnaffectedBlocks;

    private RichSoilFarmlandBlockBehavior(BlockDefinition block, float boostChance, Property<Integer> moistureProperty,
                                          Key richSoilBlockId, ConfiguredBlockSet configuredUnaffectedBlocks) {
        super(block);
        this.boostChance = boostChance;
        this.moistureProperty = moistureProperty;
        this.richSoilBlockId = richSoilBlockId;
        this.configuredUnaffectedBlocks = configuredUnaffectedBlocks;
    }

    private ConfiguredBlockSet unaffectedBlocks() {
        if (configuredUnaffectedBlocks != null) {
            return configuredUnaffectedBlocks;
        }
        ConfiguredBlockSet cached = borrowedUnaffectedBlocks;
        if (cached != null) {
            return cached;
        }
        BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
        if (richSoil == null) {
            return ConfiguredBlockSet.EMPTY;
        }
        RichSoilBlockBehavior behavior = CustomBlockUtils.getBehavior(richSoil.defaultState(), RichSoilBlockBehavior.class);
        if (behavior == null) {
            return ConfiguredBlockSet.EMPTY;
        }
        ConfiguredBlockSet resolved = behavior.unaffectedBlocks();
        borrowedUnaffectedBlocks = resolved;
        return resolved;
    }

    public static final BlockBehaviorFactory<RichSoilFarmlandBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
        // Moisture drives the whole random tick: drying out, rehydrating from water or rain, and the boost
        // that only fully wet soil performs. The property may carry another name, but one of that name has
        // to exist: a block declaring this behavior without it aborts its own load here, with the config
        // node and the name in the message, instead of loading as farmland whose moisture never changes
        // and which never boosts anything.
        String path = section != null ? section.path() : Constants.BEHAVIOR_RICH_SOIL_FARMLAND;
        String moisturePropertyName = BehaviorArgParser.getString(arguments, "moisture-property", MOISTURE_PROPERTY);
        Property<Integer> moistureProperty =
                BlockBehaviorFactory.getProperty(path, block, moisturePropertyName, Integer.class);
        String richSoilId = BehaviorArgParser.getStringStrict(arguments, "rich-soil-block", "farmersdelight:rich_soil");
        ConfiguredBlockSet unaffected = BehaviorArgParser.hasArgument(arguments, "unaffected-blocks")
                ? ConfiguredBlockSet.parse(arguments.get("unaffected-blocks"))
                : null;
        return new RichSoilFarmlandBlockBehavior(block, chance, moistureProperty, Key.of(richSoilId), unaffected);
    };

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        // Convert farmland to rich soil when a solid block covers it.
        // Melons, pumpkins, fence gates and moving pistons do not cause this conversion.
        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!isSuffocatingCover(above)) return;

        BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
        if (richSoil == null) return;
        try {
            CraftEngineBlocks.place(new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5),
                    richSoil.defaultState(), true);
        } catch (RuntimeException | LinkageError ignored) {
            // block placement is best-effort during ticking; rich soil conversion continues next tick
        }
    }

    private static boolean isSuffocatingCover(Block above) {
        // Block.isSolid() checks the placed state's collision; Material.isSolid() tests the base material.
        // CE crops may use plant carrier materials whose material flag is solid but whose collision is empty.
        // Use the state check so planting crops does not revert the farmland.
        if (!above.isSolid()) return false;
        Material type = above.getType();
        if (type == Material.MELON || type == Material.PUMPKIN || type == Material.MOVING_PISTON) return false;
        return !type.name().endsWith("_FENCE_GATE");
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        // Water or rain restores maximum moisture; otherwise each random tick removes one level.
        // Only fully wet soil boosts the plant above. Moisture 7 uses the wet appearance, while 0..6 appear dry.
        Integer currentMoisture = state.get(moistureProperty);
        int moisture = currentMoisture == null ? 0 : currentMoisture;
        boolean hydrated = isHydrated(world, pos);

        if (!hydrated) {
            if (moisture > 0) {
                setMoisture(world, pos, state, moisture - 1);
            }
        } else if (moisture < MAX_MOISTURE) {
            setMoisture(world, pos, state, MAX_MOISTURE);
        } else if (boostChance > 0F && ThreadLocalRandom.current().nextFloat() <= boostChance) {
            boostAbove(world, pos);
        }
    }

    private void setMoisture(World world, BlockPos pos, ImmutableBlockState state, int value) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        CraftEngineBlocks.place(block.getLocation(), state.with(moistureProperty, value), false);
    }

    private static boolean isHydrated(World world, BlockPos pos) {
        // Rain requires a storm and maximum sky light above the soil, excluding covered farmland.
        // This approximation includes snowfall because it does not inspect biome precipitation.
        if (world.hasStorm() && !world.isClearWeather()
                && world.getBlockAt(pos.x(), pos.y() + 1, pos.z()).getLightFromSky() == 15) {
            return true;
        }
        // Search the 9x9x2 box from offset (-4,0,-4) through (4,1,4) for water.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    // A neighbour in a chunk that is not loaded cannot hydrate anything: reading it would load
                    // that chunk (and fire its entity-load events) from a random tick, so it is skipped.
                    if (!world.isChunkLoaded((pos.x() + dx) >> 4, (pos.z() + dz) >> 4)) {
                        continue;
                    }
                    Block neighbor = world.getBlockAt(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    Material type = neighbor.getType();
                    if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return true;
                    // A CE custom block that keeps a water fluid (e.g. the lower half of a planted
                    // rice plant, kelp-backed with fluid_state: water) still hydrates the farmland
                    // just like the water source it replaced.
                    ImmutableBlockState ce = BlockStateUtils.getOptionalCustomBlockState(neighbor).orElse(null);
                    if (ce != null && ce.settings().fluidState()) return true;
                }
            }
        }
        return false;
    }

    private void boostAbove(World world, BlockPos pos) {
        Block plant = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (plant.getType() == Material.AIR) return;
        if (unaffectedBlocks().contains(plant)) return;
        try {
            // applyBoneMeal invokes BonemealableBlock growth for vanilla crops and CE blocks
            // that implement the interface.
            if (plant.applyBoneMeal(BlockFace.UP)) {
                plant.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        plant.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // cosmetic only; effect failure does not block growth
        }
    }

}
