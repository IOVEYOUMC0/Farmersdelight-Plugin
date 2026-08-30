package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
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

public class OrganicCompostBlockBehavior extends FarmersDelightBlockBehavior implements ConfiguredBlockSetProvider {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private static final String COMPOSTING_PROPERTY = "composting";
    // Any item carrying this CraftEngine item tag accelerates composting one stage the same way a water
    // bucket does (data-driven, so addons like CrabbersDelight's worm only tag their item — the block keeps
    // ownership of the stage/rich-soil logic). Mirrors the farmersdelight:milk cleanser tag pattern.
    private static final Key ACCELERANT_TAG = Key.of("farmersdelight:compost_accelerant");

    private record Config(
            Property<Integer> compostingProperty,
            int maxStage,
            Key richSoilBlockId,
            Key brownMushroomColonyId,
            Key redMushroomColonyId,
            ConfiguredBlockSet activators,
            float activatorBonusPerNeighbor,
            float waterBonus,
            float lightHighBonus,
            float lightLowBonus,
            int lightThreshold
    ) {}

    private final Config config;

    private OrganicCompostBlockBehavior(BlockDefinition block, Config config) {
        super(block);
        this.config = config;
    }

    public Key getBrownMushroomColonyId() {
        return config.brownMushroomColonyId();
    }

    public Key getRedMushroomColonyId() {
        return config.redMushroomColonyId();
    }

    @Override
    public ConfiguredBlockSet configuredBlockSet(String key) {
        return "activators".equals(key) ? config.activators() : null;
    }

    public static final BlockBehaviorFactory<OrganicCompostBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
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

        OrganicCompostBlockBehavior behavior = new OrganicCompostBlockBehavior(block, new Config(
                compostingProperty, maxStage, Key.of(richSoilId),
                Key.of(brownId), Key.of(redId),
                activators, activatorBonus, waterBonus, lightHighBonus, lightLowBonus, lightThreshold
        ));
        BlockBehaviorConfigs.register(block.id(), behavior);
        return behavior;
    };

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        ItemStack held = ItemUtils.getItemInHand(bukkitPlayer, context.getHand());
        if (held == null || held.getType().isAir()) return InteractionResult.PASS;
        boolean waterBucket = held.getType() == Material.WATER_BUCKET;
        boolean accelerant = !waterBucket && ItemUtils.hasCustomItemTag(held, ACCELERANT_TAG);
        if (!waterBucket && !accelerant) return InteractionResult.PASS;

        Integer currentStage = state.get(config.compostingProperty());
        if (currentStage == null) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = bukkitPlayer.getWorld();
        Block targetBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!ProtectionCompat.canUse(bukkitPlayer, targetBlock, (String) null)
                || !ProtectionCompat.canBuild(bukkitPlayer, targetBlock, (String) null)) {
            return InteractionResult.PASS;
        }
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);

        boolean placed;
        if (currentStage >= config.maxStage()) {
            // Already at max stage — convert to rich soil immediately
            BlockDefinition richSoil = CraftEngineBlocks.byId(config.richSoilBlockId());
            if (richSoil == null) return InteractionResult.PASS;
            placed = CraftEngineBlocks.place(loc, richSoil.defaultState(), true);
        } else {
            // A water bucket or a tagged accelerant advances one stage immediately
            ImmutableBlockState next = state.with(config.compostingProperty(), currentStage + 1);
            placed = CraftEngineBlocks.place(loc, next, true);
        }
        if (!placed) {
            return InteractionResult.PASS;
        }

        if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
            org.bukkit.inventory.EquipmentSlot slot = context.getHand() == InteractionHand.OFF_HAND
                    ? org.bukkit.inventory.EquipmentSlot.OFF_HAND
                    : org.bukkit.inventory.EquipmentSlot.HAND;
            if (waterBucket) {
                // Survival mode: consume water bucket, return empty bucket
                held.setAmount(held.getAmount() - 1);
                ItemStack emptyBucket = new ItemStack(Material.BUCKET);
                if (held.getAmount() <= 0) {
                    bukkitPlayer.getInventory().setItem(slot, emptyBucket);
                } else {
                    bukkitPlayer.getInventory().setItem(slot, held);
                    // If the hand still has stacked water buckets, add the empty bucket separately.
                    Map<Integer, ItemStack> leftovers = bukkitPlayer.getInventory().addItem(emptyBucket);
                    for (ItemStack leftover : leftovers.values()) {
                        world.dropItemNaturally(bukkitPlayer.getLocation(), leftover);
                    }
                }
            } else {
                // A tagged accelerant (e.g. a worm) is simply consumed one at a time
                held.setAmount(held.getAmount() - 1);
                if (held.getAmount() <= 0) {
                    bukkitPlayer.getInventory().setItem(slot, null);
                } else {
                    bukkitPlayer.getInventory().setItem(slot, held);
                }
            }
        }

        world.playSound(loc, waterBucket ? Sound.ITEM_BUCKET_EMPTY : Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f);
        world.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, loc.clone().add(0, 0.5, 0), 8, 0.3, 0.2, 0.3, 0.0);
        ItemUtils.swingHand(bukkitPlayer, context.getHand());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        Integer currentStage = state.get(config.compostingProperty());
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
                    // The reference mod scans the whole 3x3x3 box against its COMPOST_ACTIVATORS
                    // block tag; the configured activators list below mirrors that tag's members,
                    // including the composting block itself, so a compost block always contributes
                    // one activator to its own chance.
                    if (config.activators().contains(neighbor)) activatorCount++;
                    Block above = world.getBlockAt(nx, Math.min(ny + 1, world.getMaxHeight() - 1), nz);
                    int sky = above.getLightFromSky();
                    if (sky > maxSkyLight) maxSkyLight = sky;
                }
            }
        }

        chance += activatorCount * config.activatorBonusPerNeighbor();
        chance += maxSkyLight > config.lightThreshold() ? config.lightHighBonus() : config.lightLowBonus();
        chance += hasWater ? config.waterBonus() : 0F;

        if (ThreadLocalRandom.current().nextFloat() > chance) return;

        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        if (currentStage >= config.maxStage()) {
            BlockDefinition richSoil = CraftEngineBlocks.byId(config.richSoilBlockId());
            if (richSoil == null) return;
            CraftEngineBlocks.place(loc, richSoil.defaultState(), true);
        } else {
            ImmutableBlockState next = state.with(config.compostingProperty(), currentStage + 1);
            CraftEngineBlocks.place(loc, next, true);
        }
    }

}
