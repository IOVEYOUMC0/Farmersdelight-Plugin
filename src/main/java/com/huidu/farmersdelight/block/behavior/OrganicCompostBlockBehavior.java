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
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final Property<Integer> compostingProperty;
    private final int maxStage;
    private final Key richSoilBlockId;
    private final Key brownMushroomColonyId;
    private final Key redMushroomColonyId;
    private final Set<Material> activatorMaterials;
    private final Set<String> activatorCustomIds;
    private final float activatorBonusPerNeighbor;
    private final float waterBonus;
    private final float lightHighBonus;
    private final float lightLowBonus;
    private final int lightThreshold;

    private OrganicCompostBlockBehavior(BlockDefinition block, Property<Integer> compostingProperty, int maxStage,
                                         Key richSoilBlockId, Key brownMushroomColonyId, Key redMushroomColonyId,
                                         Set<Material> activatorMaterials,
                                         Set<String> activatorCustomIds, float activatorBonusPerNeighbor,
                                         float waterBonus, float lightHighBonus, float lightLowBonus, int lightThreshold) {
        super(block);
        this.compostingProperty = compostingProperty;
        this.maxStage = maxStage;
        this.richSoilBlockId = richSoilBlockId;
        this.brownMushroomColonyId = brownMushroomColonyId;
        this.redMushroomColonyId = redMushroomColonyId;
        this.activatorMaterials = activatorMaterials;
        this.activatorCustomIds = activatorCustomIds;
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

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<OrganicCompostBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public OrganicCompostBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String agePropName = BehaviorArgParser.getStringStrict(arguments, "composting-property", "composting");
            Property<Integer> compostingProperty = (Property<Integer>) block.getProperty(agePropName);
            int maxStage = BehaviorArgParser.getInt(arguments, "max-stage", 7);
            String richSoilId = BehaviorArgParser.getStringStrict(arguments, "rich-soil-block", "farmersdelight:rich_soil");
            String brownId = BehaviorArgParser.getStringStrict(arguments, "brown-mushroom-colony", "farmersdelight:brown_mushroom_colony");
            String redId = BehaviorArgParser.getStringStrict(arguments, "red-mushroom-colony", "farmersdelight:red_mushroom_colony");
            float activatorBonus = BehaviorArgParser.getFloat(arguments, "activator-bonus-per-neighbor", 0.02f);
            float waterBonus = BehaviorArgParser.getFloat(arguments, "water-bonus", 0.10f);
            float lightHighBonus = BehaviorArgParser.getFloat(arguments, "light-high-bonus", 0.10f);
            float lightLowBonus = BehaviorArgParser.getFloat(arguments, "light-low-bonus", 0.05f);
            int lightThreshold = BehaviorArgParser.getInt(arguments, "light-threshold", 12);

            Set<Material> materials = new HashSet<>();
            Set<String> customIds = new HashSet<>();
            Object activatorsRaw = arguments.get("activators");
            if (activatorsRaw instanceof Iterable<?> iter) {
                for (Object entry : iter) {
                    if (entry == null) continue;
                    String id = entry.toString().trim();
                    if (id.isEmpty()) continue;
                    if (id.startsWith("minecraft:")) {
                        Material material = Material.matchMaterial(id);
                        if (material != null) materials.add(material);
                    } else {
                        customIds.add(id.toLowerCase(Locale.ROOT));
                    }
                }
            }

            return new OrganicCompostBlockBehavior(block, compostingProperty, maxStage, Key.of(richSoilId),
                    Key.of(brownId), Key.of(redId),
                    materials, customIds, activatorBonus, waterBonus, lightHighBonus, lightLowBonus, lightThreshold);
        }
    };

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3 || compostingProperty == null) return;
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
                    Material material = neighbor.getType();
                    if (material == Material.WATER) hasWater = true;
                    if (isActivator(material, neighbor)) activatorCount++;
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

    private boolean isActivator(Material material, Block block) {
        if (activatorMaterials.contains(material)) return true;
        if (activatorCustomIds.isEmpty()) return false;
        ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(block);
        if (customState == null || customState.isEmpty()) return false;
        return activatorCustomIds.contains(customState.owner().value().id().toString().toLowerCase(Locale.ROOT));
    }

}
