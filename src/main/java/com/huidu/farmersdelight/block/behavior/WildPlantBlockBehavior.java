package com.huidu.farmersdelight.block.behavior;

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
 * 静态野生植物（卷心菜/洋葱/番茄）的骨粉传播逻辑，移植自该模组的
 * WildCropBlock：成功判定通过后，将一个副本散布到附近合法土壤上方的空气中，散布数量受周围
 * 已有相同植物数量的上限限制。放置/存活逻辑仍由 bush_block 处理；永远不进行随机刻更新。
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

    // 放置、碰撞和寻路逻辑仍由 bush_block 处理；这些抽象钩子保持为可通过的空实现。
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

        // 原版在合法目标上会消耗骨粉；而传播本身仅在成功判定通过时才发生。
        if (ThreadLocalRandom.current().nextDouble() < successChance) {
            spread(world, origin, state);
        }
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());
        if (player.getGameMode() != GameMode.CREATIVE) {
            mainHand.setAmount(mainHand.getAmount() - 1);
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    // 忠实移植 WildCropBlock.performBonemeal：若 9x3x9 区域内已有 spreadLimit 个相同植物则中止，
    // 否则随机游走到一个目标位置，并在泥土/沙子上方的空气中放置一个副本。
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
        // 仅在该植物实际能存活的位置传播：若设置了自身的土壤规则则使用该规则，否则使用泥土/沙子。
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
            boolean isBoneMealTarget = getBoolean(arguments, "is-bone-meal-target", true);
            double successChance = getDouble(arguments, "bone-meal-success-chance", 0.8);
            int spreadLimit = Math.max(1, getInt(arguments, "spread-limit", 10));
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            return new WildPlantBlockBehavior(block, isBoneMealTarget, successChance, spreadLimit, soilRules);
        }
    };

    private static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Boolean b) return b;
        return value != null ? Boolean.parseBoolean(value.toString()) : defaultValue;
    }

    private static int getInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number n) return n.intValue();
        try {
            return value != null ? Integer.parseInt(value.toString()) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double getDouble(Map<String, Object> arguments, String key, double defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value != null ? Double.parseDouble(value.toString()) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
