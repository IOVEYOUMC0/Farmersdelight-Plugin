package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

// Shared base for Farmers Delight block behaviors that leave the fallOn and
// updateEntityMovementAfterFallOn hooks as no-ops. isPathFindable stays abstract
// because the answer varies per block.
abstract class FarmersDelightBlockBehavior extends BlockBehavior {

    public FarmersDelightBlockBehavior(BlockDefinition blockDefinition) {
        super(blockDefinition);
    }

    public abstract boolean isPathFindable(Object thisBlock, Object[] args);

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    protected void playBonemealEffect(World world, int x, int y, int z) {
        Location location = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        world.spawnParticle(Particle.HAPPY_VILLAGER, location, 15, 0.5, 0.5, 0.5);
        world.playSound(location, Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f);
    }
}
