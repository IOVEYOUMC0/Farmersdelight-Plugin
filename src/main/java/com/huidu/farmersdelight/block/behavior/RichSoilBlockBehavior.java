package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RichSoilBlockBehavior extends FarmersDelightBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private final float boostChance;
    private final Key brownMushroomColonyId;
    private final Key redMushroomColonyId;
    private final ConfiguredBlockSet brownMushrooms;
    private final ConfiguredBlockSet redMushrooms;
    // Store unaffected blocks per behavior instance so different configurations cannot overwrite each other.
    // Publish the fully parsed final set before region ticks can read it.
    private final ConfiguredBlockSet unaffectedBlocks;

    private RichSoilBlockBehavior(BlockDefinition block, float boostChance,
                                   Key brownMushroomColonyId, Key redMushroomColonyId,
                                   ConfiguredBlockSet unaffectedBlocks,
                                   ConfiguredBlockSet brownMushrooms,
                                   ConfiguredBlockSet redMushrooms) {
        super(block);
        this.boostChance = boostChance;
        this.brownMushroomColonyId = brownMushroomColonyId;
        this.redMushroomColonyId = redMushroomColonyId;
        this.unaffectedBlocks = unaffectedBlocks;
        this.brownMushrooms = brownMushrooms;
        this.redMushrooms = redMushrooms;
    }

    public ConfiguredBlockSet unaffectedBlocks() {
        return unaffectedBlocks;
    }

    public Key getBrownMushroomColonyId() {
        return brownMushroomColonyId;
    }

    public Key getRedMushroomColonyId() {
        return redMushroomColonyId;
    }

    public static final BlockBehaviorFactory<RichSoilBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
        String brownId = BehaviorArgParser.getStringStrict(arguments, "brown-mushroom-colony", "farmersdelight:brown_mushroom_colony");
        String redId = BehaviorArgParser.getStringStrict(arguments, "red-mushroom-colony", "farmersdelight:red_mushroom_colony");
        ConfiguredBlockSet unaffected = ConfiguredBlockSet.parse(arguments.get("unaffected-blocks"));
        ConfiguredBlockSet brownMushrooms = configuredMushrooms(arguments, "brown-mushroom-blocks",
                "minecraft:brown_mushroom", Constants.BLOCK_BROWN_MUSHROOM);
        ConfiguredBlockSet redMushrooms = configuredMushrooms(arguments, "red-mushroom-blocks",
                "minecraft:red_mushroom", Constants.BLOCK_RED_MUSHROOM);
        return new RichSoilBlockBehavior(block, chance, Key.of(brownId), Key.of(redId), unaffected,
                brownMushrooms, redMushrooms);
    };

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (convertMushroomToColony(above)) return;

        tryBoost(world, pos);
    }

    public void tryBoost(World world, BlockPos pos) {
        if (boostChance <= 0F) return;
        if (ThreadLocalRandom.current().nextFloat() > boostChance) return;
        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (boostPlant(above)) return;
        Block below = world.getBlockAt(pos.x(), pos.y() - 1, pos.z());
        boostPlant(below);
    }

    private boolean convertMushroomToColony(Block target) {
        if (brownMushrooms.contains(target)) {
            return replaceWithColony(target, brownMushroomColonyId);
        }
        if (redMushrooms.contains(target)) {
            return replaceWithColony(target, redMushroomColonyId);
        }
        return false;
    }

    private static ConfiguredBlockSet configuredMushrooms(Map<String, Object> arguments, String key,
                                                         String... defaults) {
        Object configured = BehaviorArgParser.getRaw(arguments, key);
        return configured == null ? ConfiguredBlockSet.parse(List.of(defaults))
                : ConfiguredBlockSet.parse(configured);
    }

    private boolean replaceWithColony(Block target, Key colonyId) {
        BlockDefinition colony = CraftEngineBlocks.byId(colonyId);
        if (colony == null) return false;
        // Plant a mushroom colony at age 0 so it still needs to grow.
        // The colony's placement default is mature age 3, which must be overridden on this path.
        ImmutableBlockState young = colonyAgeZero(colony);
        if (young == null) return false;
        CraftEngineBlocks.place(target.getLocation().add(0.5, 0, 0.5), young, true);
        return true;
    }

    private static ImmutableBlockState colonyAgeZero(BlockDefinition colony) {
        Property<Integer> property = BlockBehaviorFactory.getOptionalProperty(colony, "age", Integer.class);
        return property == null ? null : colony.defaultState().with(property, 0);
    }

    private boolean boostPlant(Block plant) {
        // Skip configured unaffected plants so soil does not accelerate their growth or spreading.
        if (unaffectedBlocks.contains(plant)) {
            return false;
        }
        try {
            boolean applied = plant.applyBoneMeal(BlockFace.UP);
            if (applied) {
                plant.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        plant.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3);
            }
            return applied;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

}
