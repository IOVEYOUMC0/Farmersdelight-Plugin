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
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RichSoilBlockBehavior extends FarmersDelightBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private final float boostChance;
    private final Key brownMushroomColonyId;
    private final Key redMushroomColonyId;
    // The reference mod keeps a single UNAFFECTED_BY_RICH_SOIL block tag consulted by both the rich soil block
    // and the rich soil farmland. Each configured block carries its own parsed copy: the list belongs to the
    // behavior instance the factory built it from, so two blocks declaring this behavior cannot overwrite one
    // another and load order cannot decide the winner. Final field of an instance published by the factory
    // before any tick thread can reach it, so tick-thread reads see the fully built set.
    private final ConfiguredBlockSet unaffectedBlocks;

    private RichSoilBlockBehavior(BlockDefinition block, float boostChance,
                                   Key brownMushroomColonyId, Key redMushroomColonyId,
                                   ConfiguredBlockSet unaffectedBlocks) {
        super(block);
        this.boostChance = boostChance;
        this.brownMushroomColonyId = brownMushroomColonyId;
        this.redMushroomColonyId = redMushroomColonyId;
        this.unaffectedBlocks = unaffectedBlocks;
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

    public static final BlockBehaviorFactory<RichSoilBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
        String brownId = BehaviorArgParser.getStringStrict(arguments, "brown-mushroom-colony", "farmersdelight:brown_mushroom_colony");
        String redId = BehaviorArgParser.getStringStrict(arguments, "red-mushroom-colony", "farmersdelight:red_mushroom_colony");
        ConfiguredBlockSet unaffected = ConfiguredBlockSet.parse(arguments.get("unaffected-blocks"));
        return new RichSoilBlockBehavior(block, chance, Key.of(brownId), Key.of(redId), unaffected);
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
        if (target.getType() == Material.BROWN_MUSHROOM) {
            return replaceWithColony(target, brownMushroomColonyId);
        }
        if (target.getType() == Material.RED_MUSHROOM) {
            return replaceWithColony(target, redMushroomColonyId);
        }
        // A vanilla mushroom cannot survive on the rich-soil series (CraftEngine block tags are client-only),
        // so a mushroom planted here is our look-alike custom block instead. Convert it the same way, mirroring
        // the mod where the rich soil turns the mushroom sitting above it into a colony on its own random tick.
        if (CustomBlockUtils.hasId(target, Constants.BLOCK_BROWN_MUSHROOM)) {
            return replaceWithColony(target, brownMushroomColonyId);
        }
        if (CustomBlockUtils.hasId(target, Constants.BLOCK_RED_MUSHROOM)) {
            return replaceWithColony(target, redMushroomColonyId);
        }
        return false;
    }

    private boolean replaceWithColony(Block target, Key colonyId) {
        BlockDefinition colony = CraftEngineBlocks.byId(colonyId);
        if (colony == null) return false;
        // Convert to a young (age 0) colony that then grows to maturity, matching the mod: planting a
        // mushroom must never yield a fully grown colony. The colony's default state is age 3 (used when the
        // colony item is placed directly), so explicitly drop it to age 0 for this growth path.
        ImmutableBlockState young = colonyAgeZero(colony);
        if (young == null) return false;
        CraftEngineBlocks.place(target.getLocation().add(0.5, 0, 0.5), young, true);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static ImmutableBlockState colonyAgeZero(BlockDefinition colony) {
        Property<?> property = colony.getProperty("age");
        if (property == null || property.valueClass() != Integer.class) return null;
        return colony.defaultState().with((Property<Integer>) property, 0);
    }

    private boolean boostPlant(Block plant) {
        // Mirrors RichSoilBlock.boostPlant, which bails out before the bone meal call for anything in the
        // UNAFFECTED_BY_RICH_SOIL tag, so grass, moss, nylium, dripleaf, tall flowers, wild crops and mushroom
        // colonies keep spreading at their own rate instead of being fertilised by the soil under them.
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
        } catch (Throwable ignored) {
            return false;
        }
    }

}
