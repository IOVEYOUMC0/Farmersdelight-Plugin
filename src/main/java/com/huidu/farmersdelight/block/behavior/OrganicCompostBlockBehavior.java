package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class OrganicCompostBlockBehavior extends BlockBehavior {

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

    private static final String COMPOSTING_PROPERTY = "composting";

    private final Property<Integer> compostingProperty;
    private final int maxStage;
    private final Key richSoilBlockId;
    private final Key brownMushroomColonyId;
    private final Key redMushroomColonyId;
    private final ConfiguredBlockSet activators;
    private final float activatorBonusPerNeighbor;
    private final float waterBonus;
    private final float lightHighBonus;
    private final float lightLowBonus;
    private final int lightThreshold;

    private OrganicCompostBlockBehavior(BlockDefinition block, Property<Integer> compostingProperty, int maxStage,
                                         Key richSoilBlockId, Key brownMushroomColonyId, Key redMushroomColonyId,
                                         ConfiguredBlockSet activators, float activatorBonusPerNeighbor,
                                         float waterBonus, float lightHighBonus, float lightLowBonus, int lightThreshold) {
        super(block);
        this.compostingProperty = compostingProperty;
        this.maxStage = maxStage;
        this.richSoilBlockId = richSoilBlockId;
        this.brownMushroomColonyId = brownMushroomColonyId;
        this.redMushroomColonyId = redMushroomColonyId;
        this.activators = activators;
        this.activatorBonusPerNeighbor = activatorBonusPerNeighbor;
        this.waterBonus = waterBonus;
        this.lightHighBonus = lightHighBonus;
        this.lightLowBonus = lightLowBonus;
        this.lightThreshold = lightThreshold;
    }

    public Key getBrownMushroomColonyId() {
        return brownMushroomColonyId;
    }

    public Key getRedMushroomColonyId() {
        return redMushroomColonyId;
    }

    public static final BlockBehaviorFactory<OrganicCompostBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public OrganicCompostBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            // The composting stage is the whole behavior: every random tick reads it and writes it back one
            // higher until the block turns into rich soil. The property may carry another name, but one of that
            // name has to exist: a block declaring this behavior without it aborts its own load here, with the
            // config node and the name in the message, instead of loading as a compost heap that can never
            // mature.
            String path = section != null ? section.path() : Constants.BEHAVIOR_ORGANIC_COMPOST;
            String compostingPropertyName =
                    BehaviorArgParser.getStringStrict(arguments, "composting-property", COMPOSTING_PROPERTY);
            Property<Integer> compostingProperty =
                    BlockBehaviorFactory.getProperty(path, block, compostingPropertyName, Integer.class);
            int maxStage = BehaviorArgParser.getInt(arguments, "max-stage", 7);
            String richSoilId = BehaviorArgParser.getStringStrict(arguments, "rich-soil-block", "farmersdelight:rich_soil");
            String brownId = BehaviorArgParser.getStringStrict(arguments, "brown-mushroom-colony", "farmersdelight:brown_mushroom_colony");
            String redId = BehaviorArgParser.getStringStrict(arguments, "red-mushroom-colony", "farmersdelight:red_mushroom_colony");
            float activatorBonus = BehaviorArgParser.getFloat(arguments, "activator-bonus-per-neighbor", 0.02f);
            float waterBonus = BehaviorArgParser.getFloat(arguments, "water-bonus", 0.10f);
            float lightHighBonus = BehaviorArgParser.getFloat(arguments, "light-high-bonus", 0.10f);
            float lightLowBonus = BehaviorArgParser.getFloat(arguments, "light-low-bonus", 0.05f);
            int lightThreshold = BehaviorArgParser.getInt(arguments, "light-threshold", 12);

            ConfiguredBlockSet activators = ConfiguredBlockSet.parse(arguments.get("activators"));

            return new OrganicCompostBlockBehavior(block, compostingProperty, maxStage, Key.of(richSoilId),
                    Key.of(brownId), Key.of(redId),
                    activators, activatorBonus, waterBonus, lightHighBonus, lightLowBonus, lightThreshold);
        }
    };

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        ItemStack held = bukkitPlayer.getInventory().getItemInMainHand();
        if (held.getType() != Material.WATER_BUCKET) return InteractionResult.PASS;

        Integer currentStage = state.get(compostingProperty);
        if (currentStage == null) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = bukkitPlayer.getWorld();
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);

        if (currentStage >= maxStage) {
            // Already max — convert to rich soil immediately
            BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
            if (richSoil == null) return InteractionResult.PASS;
            CraftEngineBlocks.place(loc, richSoil.defaultState(), true);
        } else {
            // 水桶加速堆肥：立即推进一个阶段
            ImmutableBlockState next = state.with(compostingProperty, currentStage + 1);
            CraftEngineBlocks.place(loc, next, true);
        }

        // 生存模式消耗水桶，返还空桶
        if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
            ItemStack emptyBucket = new ItemStack(Material.BUCKET);
            if (held.getAmount() <= 0) {
                bukkitPlayer.getInventory().setItemInMainHand(emptyBucket);
            } else {
                // 如果主手还有堆叠的水桶，空桶掉落在玩家位置
                Map<Integer, ItemStack> leftovers = bukkitPlayer.getInventory().addItem(emptyBucket);
                for (ItemStack leftover : leftovers.values()) {
                    world.dropItemNaturally(bukkitPlayer.getLocation(), leftover);
                }
            }
        }

        world.playSound(loc, Sound.ITEM_BUCKET_EMPTY, 1.0f, 1.0f);
        bukkitPlayer.swingMainHand();
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        Integer currentStage = state.get(compostingProperty);
        if (currentStage == null) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        float chance = 0F;
        boolean hasWater = false;
        int maxSkyLight = 0;
        int activatorCount = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = pos.x() + dx, ny = pos.y() + dy, nz = pos.z() + dz;
                    if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue;
                    Block neighbor = world.getBlockAt(nx, ny, nz);
                    if (neighbor.getType() == Material.WATER) hasWater = true;
                    // The reference mod scans the whole 3x3x3 box against the COMPOST_ACTIVATORS block tag,
                    // including the composting block itself, so a compost block always contributes one
                    // activator to its own chance.
                    if (activators.contains(neighbor)) activatorCount++;
                    Block above = world.getBlockAt(nx, Math.min(ny + 1, world.getMaxHeight() - 1), nz);
                    int sky = above.getLightFromSky();
                    if (sky > maxSkyLight) maxSkyLight = sky;
                }
            }
        }

        chance += activatorCount * activatorBonusPerNeighbor;
        chance += maxSkyLight > lightThreshold ? lightHighBonus : lightLowBonus;
        chance += hasWater ? waterBonus : 0F;

        if (ThreadLocalRandom.current().nextFloat() > chance) return;

        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        if (currentStage >= maxStage) {
            BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
            if (richSoil == null) return;
            CraftEngineBlocks.place(loc, richSoil.defaultState(), true);
        } else {
            ImmutableBlockState next = state.with(compostingProperty, currentStage + 1);
            CraftEngineBlocks.place(loc, next, true);
        }
    }

}
