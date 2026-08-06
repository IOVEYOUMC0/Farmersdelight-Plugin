package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
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

    public static final BlockBehaviorFactory<RichSoilFarmlandBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
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

        // Mirrors RichSoilFarmlandBlock.canSurvive + turnToRichSoil (1.21 reference): a solid block placed
        // directly above suffocates the farmland, which reverts to rich soil in place rather than dropping.
        // Melons/pumpkins (which grow on farmland) and fence gates / moving pistons are exempt, as in vanilla.
        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!isSuffocatingCover(above)) return;

        BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
        if (richSoil == null) return;
        try {
            CraftEngineBlocks.place(new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5),
                    richSoil.defaultState(), true);
        } catch (Throwable ignored) {
            // block placement is best-effort during ticking; rich soil conversion continues next tick
        }
    }

    private static boolean isSuffocatingCover(Block above) {
        // Use Block.isSolid() (the Block instance method), NOT Material.isSolid(). They are different checks:
        // Block.isSolid() maps to the collision-based BlockState.blocksMotion() the vanilla FarmBlock.canSurvive
        // tests (a block whose collision shape blocks entity motion). Material.isSolid() / BlockType.isSolid()
        // is the unrelated "can be built upon" notion, which is true for pass-through plant states — sugar
        // cane / tripwire / kelp / twisting vines. CraftEngine custom crops are backed by exactly those plant
        // states, so a Material.isSolid() check reverted the soil on every planting. The collision-based check
        // is false for all crop backings (they have empty collision) and true only for genuine solid covers,
        // matching the reference mod where crops are non-solid CropBlocks. Do not switch this to a Material
        // check.
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

        // Mirrors RichSoilFarmlandBlock.randomTick (1.21 reference): a water source or rain drives moisture
        // straight to the maximum; without either the soil loses one moisture level per random tick. Only
        // fully wet soil boosts the plant above (the original boosts at moisture 7). The soil appears wet
        // at moisture 7 and dry below, so the shared dry appearance covers 0..6 while 7 shows the moist model.
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
        // Rain check — vanilla farmland uses level.isRainingAt(pos.above()), which is a storm AND the block
        // above being exposed to the sky, so covered / indoor / underground farmland is never rained on.
        // We approximate the storm with world.hasStorm() + the world.isClearWeather() inverse, and the
        // sky-exposure part with the block-above sky light reaching its maximum (the same getLightFromSky
        // reading OrganicCompostBlockBehavior uses); a solid cover, roof, or ceiling drops it below 15.
        // Biome precipitation is still left out: hasStorm() is true during snowfall in cold biomes too, so a
        // sky-exposed heap in a snowy biome will still rehydrate where the mod's RAIN-only check would not.
        if (world.hasStorm() && !world.isClearWeather()
                && world.getBlockAt(pos.x(), pos.y() + 1, pos.z()).getLightFromSky() == 15) {
            return true;
        }
        // Same 9×9×2 box as RichSoilFarmlandBlock.isNearWater (1.21 reference): pos.offset(-4,0,-4) to
        // pos.offset(4,1,4).
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block neighbor = world.getBlockAt(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    Material type = neighbor.getType();
                    if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return true;
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
            // Bukkit's applyBoneMeal delegates to NMS BonemealableBlock.performBonemeal for vanilla
            // crops + custom CE blocks that implement the interface, so this matches the original
            // mod's direct performBonemeal call (just routed through the Bukkit bridge).
            if (plant.applyBoneMeal(BlockFace.UP)) {
                plant.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        plant.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3);
            }
        } catch (Throwable ignored) {
            // cosmetic only; effect failure does not block growth
        }
    }

}
