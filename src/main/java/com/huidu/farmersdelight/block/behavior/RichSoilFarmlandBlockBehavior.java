package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
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

public class RichSoilFarmlandBlockBehavior extends BlockBehavior {

    private static final int MAX_MOISTURE = 7;

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    private final float boostChance;
    private final Property<Integer> moistureProperty;
    private final Key richSoilBlockId;

    private RichSoilFarmlandBlockBehavior(BlockDefinition block, float boostChance, Property<Integer> moistureProperty,
                                          Key richSoilBlockId) {
        super(block);
        this.boostChance = boostChance;
        this.moistureProperty = moistureProperty;
        this.richSoilBlockId = richSoilBlockId;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<RichSoilFarmlandBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public RichSoilFarmlandBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
            String moisturePropertyName = BehaviorArgParser.getString(arguments, "moisture-property", "moisture");
            Property<Integer> moistureProperty = (Property<Integer>) block.getProperty(moisturePropertyName);
            String richSoilId = BehaviorArgParser.getStringStrict(arguments, "rich-soil-block", "farmersdelight:rich_soil");
            return new RichSoilFarmlandBlockBehavior(block, chance, moistureProperty, Key.of(richSoilId));
        }
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
        if (state == null || state.isEmpty() || moistureProperty == null) return;
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
        // Rain check — vanilla farmland uses level.isRainingAt(pos.above()) which is hasStorm + biome
        // can-rain. We approximate with world.hasStorm() + Bukkit world.isClearWeather() inverse, and
        // assume the admin places the block in a biome that supports rain (biome-level temperature
        // gating is left out — the cost of a wrong rain-during-snowstorm boost is tiny).
        if (world.hasStorm() && !world.isClearWeather()) {
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
        try {
            // Bukkit's applyBoneMeal delegates to NMS BonemealableBlock.performBonemeal for vanilla
            // crops + custom CE blocks that implement the interface, so this matches the original
            // mod's direct performBonemeal call (just routed through the Bukkit bridge).
            if (plant.applyBoneMeal(BlockFace.UP)) {
                plant.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        plant.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3);
            }
        } catch (Throwable ignored) {
        }
    }

}
