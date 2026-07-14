package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bone meal spread logic for static wild plants (cabbage/onion/tomato), ported from the mod's
 * WildCropBlock: on success, scatters a copy into the air above nearby valid soil, with spread count
 * capped by the number of identical plants already around. Placement/survival stays in bush_block; never does random tick updates.
 */
public class WildPlantBlockBehavior extends BlockBehavior {

    private final boolean isBoneMealTarget;
    private final double successChance;
    private final int spreadLimit;
    private final SoilRules soilRules;

    private WildPlantBlockBehavior(BlockDefinition block, boolean isBoneMealTarget, double successChance,
                                   int spreadLimit, SoilRules soilRules) {
        super(block);
        this.isBoneMealTarget = isBoneMealTarget;
        this.successChance = successChance;
        this.spreadLimit = spreadLimit;
        this.soilRules = soilRules;
    }

    // Placement, collision, and pathfinding stay in bush_block; these abstract hooks remain pass-through no-ops.
    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (!isBoneMealTarget || context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        Player player = Bukkit.getPlayer(context.getPlayer().uuid());
        if (player == null) {
            return InteractionResult.PASS;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() != Material.BONE_MEAL) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = player.getWorld();
        Block origin = world.getBlockAt(pos.x(), pos.y(), pos.z());

        // Vanilla consumes bone meal on a valid target; the spread itself only happens on a successful roll.
        if (ThreadLocalRandom.current().nextDouble() < successChance) {
            spread(world, origin, state);
        }
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());
        if (player.getGameMode() != GameMode.CREATIVE) {
            mainHand.setAmount(mainHand.getAmount() - 1);
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    // Faithful port of WildCropBlock.performBonemeal: aborts if the 9x3x9 area already has spreadLimit identical plants,
    // otherwise random-walks to a target position and places a copy in the air above dirt/sand.
    private void spread(World world, Block origin, ImmutableBlockState state) {
        String selfId = block().id().toString();
        int remaining = spreadLimit;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block near = world.getBlockAt(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (CustomBlockUtils.hasId(near, selfId) && --remaining <= 0) {
                        return;
                    }
                }
            }
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Block target = randomNeighbor(origin, random);
        for (int k = 0; k < 4; k++) {
            if (canPlaceAt(target)) {
                origin = target;
            }
            target = randomNeighbor(origin, random);
        }
        if (canPlaceAt(target)) {
            CraftEngineBlocks.place(target.getLocation(), state, false);
        }
    }

    private static Block randomNeighbor(Block from, ThreadLocalRandom random) {
        return from.getRelative(random.nextInt(3) - 1,
                random.nextInt(2) - random.nextInt(2),
                random.nextInt(3) - 1);
    }

    private boolean canPlaceAt(Block target) {
        if (!target.getType().isAir()) {
            return false;
        }
        Block below = target.getRelative(BlockFace.DOWN);
        // Only spread where the plant can actually survive: use its own soil rules if configured, otherwise dirt/sand.
        if (soilRules.isConfigured()) {
            return SoilRuleSupport.matches(below, soilRules);
        }
        Material belowType = below.getType();
        return Tag.DIRT.isTagged(belowType) || Tag.SAND.isTagged(belowType);
    }

    private void playBonemealEffect(World world, int x, int y, int z) {
        Location location = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        world.spawnParticle(Particle.HAPPY_VILLAGER, location, 15, 0.5, 0.5, 0.5);
        world.playSound(location, Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f);
    }

    public static final BlockBehaviorFactory<WildPlantBlockBehavior> FACTORY = new BlockBehaviorFactory<WildPlantBlockBehavior>() {
        @Override
        public WildPlantBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            boolean isBoneMealTarget = BehaviorArgParser.getBoolean(arguments, "is-bone-meal-target", true);
            double successChance = BehaviorArgParser.getDouble(arguments, "bone-meal-success-chance", 0.8);
            int spreadLimit = Math.max(1, BehaviorArgParser.getInt(arguments, "spread-limit", 10));
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            return new WildPlantBlockBehavior(block, isBoneMealTarget, successChance, spreadLimit, soilRules);
        }
    };

}
