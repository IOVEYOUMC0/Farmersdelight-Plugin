package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.BehaviorArguments;
import com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Cancellable;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import com.huidu.farmersdelight.listener.RicePlantListener;
import net.momirealms.craftengine.proxy.minecraft.world.level.ScheduledTickAccessProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidsProxy;

public class TallCropBlockBehavior extends FarmersDelightBlockBehavior implements BonemealableBlock {
    // Vanilla's water tick delay; Fluids.WATER.getTickDelay returns this in every dimension.
    private static final int WATER_TICK_DELAY = 5;


    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        // Rice is the only block using this behavior and CraftEngine builds it as a single behavior,
        // so this method is called directly for mob navigation. Returning false marked it BLOCKED, so
        // mobs (villager farmers included) could not path across a rice paddy the way they swim through
        // vanilla kelp. Returning true lets the node evaluator derive the passable WATER/OPEN path type
        // from the block's own water fluid state, matching vanilla kelp; rice has no collision so
        // nothing else is affected.
        return true;
    }

    // Rice validates its own support when something changes next to it. These are the NMS hooks vanilla
    // crops use for exactly this; they replaced a BlockPhysicsEvent listener that had to inspect every
    // block update in the world before it could tell whether the block was rice. neighborChanged covers
    // updateNeighborsAt and updateShape covers updateNeighbourShapes, which is the pair the old listener saw.
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length >= 3) {
            notifyRice(args[1], args[2]);
        }
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        if (args.length >= 7) {
            scheduleWaterTick(args);
            notifyRice(args[1], args[3]);
        }
        return args[0];
    }

    // The lower half uses a water-bearing kelp state. CE replaces updateShape, so this behavior
    // must schedule water ticks to keep surrounding fluid flowing. The upper tripwire half has no fluid.
    private void scheduleWaterTick(Object[] args) {
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty() || !isLowerHalf(state)) {
            return;
        }
        ScheduledTickAccessProxy.INSTANCE.scheduleTick$1(args[2], args[3], FluidsProxy.WATER, WATER_TICK_DELAY);
    }

    private static void notifyRice(Object levelHandle, Object posHandle) {
        World world = CraftEngineAdapter.toWorld(levelHandle);
        BlockPos pos = CraftEngineAdapter.toBlockPos(posHandle);
        if (world == null || pos == null) {
            return;
        }
        RicePlantListener.onRiceNeighborChanged(world.getBlockAt(pos.x(), pos.y(), pos.z()));
    }


    private record Config(
            Property<Integer> ageProperty,
            Property<?> halfProperty,
            Property<Boolean> supportingProperty,
            float growSpeed,
            int minGrowLight,
            boolean isBoneMealTarget,
            NumberProvider boneMealAgeBonus,
            int maxAgeLower,
            int maxAgeUpper,
            int upperMinAge,
            boolean vanillaGrowth,
            boolean boneMealOverflow,
            Object halfLowerValue,
            Object halfUpperValue,
            boolean requiresWater,
            boolean resetOnHarvest,
            Key upperBlockId,
            Set<Key> harvestToolTags,
            Set<String> harvestToolItems,
            Set<Key> extraPlantingItems,
            SoilRules soilRules
    ) {}

    private final Config config;
    private static final Map<Key, TallCropBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<Key, SoilRules> SOIL_RULES = new ConcurrentHashMap<>();
    private static final Map<Key, Key> EXTRA_PLANTING_ITEMS = new ConcurrentHashMap<>();
    private static final NumberProvider DEFAULT_BONE_MEAL_AGE_BONUS = new NumberProvider() {
        @Override
        public float getFloat(Context context) {
            return 1.0F;
        }

        @Override
        public double getDouble(Context context) {
            return 1.0D;
        }

        @Override
        public boolean isConstant() {
            return true;
        }
    };

    private TallCropBlockBehavior(BlockDefinition block, Config config) {
        super(block);
        this.config = config;
    }

    public static final BlockBehaviorFactory<TallCropBlockBehavior> FACTORY = new BlockBehaviorFactory<TallCropBlockBehavior>() {
        @Override
        public TallCropBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String path = section != null ? section.path() : Constants.BEHAVIOR_TALL_CROP;

            // Age and half are not optional: the crop's whole growth and harvest cycle is expressed
            // through them. A block that declares this behavior without them aborts its own load here,
            // naming the property that is missing, instead of loading a crop that never grows and can
            // never be harvested. The property name stays configurable; an unresolvable name is an error
            // without a default fallback.
            String agePropertyName = BehaviorArgParser.getString(arguments, "age-property", "age");
            Property<Integer> ageProperty =
                    BlockBehaviorFactory.getProperty(path, block, agePropertyName, Integer.class);

            String halfPropertyName = BehaviorArgParser.getString(arguments, "half.property", "half");
            Property<DoubleBlockHalf> halfProperty =
                    BlockBehaviorFactory.getProperty(path, block, halfPropertyName, DoubleBlockHalf.class);

            // Optional by design: a crop may express its mature supporting stage as a distinct age value
            // instead of a separate boolean, in which case there is no such property to write. Resolved
            // leniently so its absence is not an error, but type-checked so a non-boolean property of
            // that name is ignored rather than failing at the first write.
            String supportingPropertyName = BehaviorArgParser.getString(arguments, "supporting-property", "supporting");
            Property<Boolean> supportingProperty =
                    BlockBehaviorFactory.getOptionalProperty(block, supportingPropertyName, Boolean.class);

            float growSpeed = BehaviorArgParser.getFloat(arguments, "grow-speed", 0.25f);
            int minGrowLight = BehaviorArgParser.getInt(arguments, "light.requirement", 9);
            boolean isBoneMealTarget = BehaviorArgParser.getBoolean(arguments, "bone-meal.is-target",
                    "is-bone-meal-target", true);
            // The bone meal age bonus is a number provider, so the nested value is parsed through a section view
            // of the same path; the legacy flat keys (bone-meal-age-bonus / bone_meal_age_bonus) stay readable.
            Object nestedAgeBonus = BehaviorArguments.rawWithLegacy(arguments, "bone-meal.age-bonus", "bone_meal_age_bonus");
            NumberProvider boneMealAgeBonus = section == null
                    ? DEFAULT_BONE_MEAL_AGE_BONUS
                    : nestedAgeBonus != null
                            ? section.withSamePath(Map.of("bone-meal.age-bonus", nestedAgeBonus))
                                    .getNumber("bone-meal.age-bonus", DEFAULT_BONE_MEAL_AGE_BONUS)
                            : section.getNumber(new String[]{"bone_meal_age_bonus", "bone-meal-age-bonus"},
                                    DEFAULT_BONE_MEAL_AGE_BONUS);
            
            int maxAgeLower = BehaviorArgParser.hasArgument(arguments, "max-age.lower")
                    ? BehaviorArgParser.getInt(arguments, "max-age.lower", 4)
                    : inferMaxIntegerValue(ageProperty);
            int maxAgeUpper = BehaviorArgParser.hasArgument(arguments, "max-age.upper")
                    ? BehaviorArgParser.getInt(arguments, "max-age.upper", 3)
                    : Math.max(0, maxAgeLower - 1);

            // Age at which the lower half starts growing an upper half. Defaults to the lower half's own
            // maturity, which is the rice cycle: the panicle appears the moment the stalk matures. Crops
            // whose original block spawns the top earlier (MMLib's HighCropBlock exposes that as
            // getGrowUpperAge) configure a smaller value and then get the original's two independent
            // rolls per random tick instead of the single mature-tick spawn.
            int upperMinAge = BehaviorArgParser.hasArgument(arguments, "upper.min-age")
                    ? Math.max(0, BehaviorArgParser.getInt(arguments, "upper.min-age", maxAgeLower))
                    : maxAgeLower;
            // Vanilla crop growth (CropBlock.getGrowthSpeed over the 3x3 below plus the row/diagonal
            // penalty, and a raw-brightness light check that ignores the day/night reduction) instead of
            // the flat grow-speed used by rice. Off by default so existing crops keep their cycle.
            boolean vanillaGrowth = BehaviorArgParser.getBoolean(arguments, "vanilla-growth", false);
            // Vanilla HighCropBlock carries bone meal growth beyond the lower half's maturity into the
            // upper half's age; without this the excess is dropped and the top always starts at age 0.
            boolean boneMealOverflow = BehaviorArgParser.getBoolean(arguments, "bone-meal.overflow", false);
            
            Object halfLowerValue = BehaviorArgParser.hasArgument(arguments, "half.lower-value")
                    ? getRawPropertyValue(BehaviorArgParser.getRaw(arguments, "half.lower-value"), halfProperty, inferLowerHalfValue(halfProperty))
                    : inferLowerHalfValue(halfProperty);
            Object halfUpperValue = BehaviorArgParser.hasArgument(arguments, "half.upper-value")
                    ? getRawPropertyValue(BehaviorArgParser.getRaw(arguments, "half.upper-value"), halfProperty, inferUpperHalfValue(halfProperty))
                    : inferUpperHalfValue(halfProperty);
            
            boolean requiresWater = BehaviorArgParser.getBoolean(arguments, "requires-water", false);
            boolean resetOnHarvest = BehaviorArgParser.getBoolean(arguments, "reset-on-harvest", true);
            Set<Key> harvestToolTags = SoilRuleSupport.parseKeys(arguments, "harvest-tool.tags");
            Set<String> harvestToolItems = parseConfiguredItemIds(arguments);
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            Set<Key> extraPlantingItems = parseConfiguredKeys(arguments
            );
            
            String upperBlockStr = BehaviorArgParser.getString(arguments, "upper.block", "");
            Key upperBlockId = upperBlockStr.isEmpty() ? null : Key.of(upperBlockStr);

            TallCropBlockBehavior behavior = new TallCropBlockBehavior(block, new Config(
                    ageProperty, halfProperty, supportingProperty,
                    growSpeed, minGrowLight, isBoneMealTarget, boneMealAgeBonus,
                    maxAgeLower, maxAgeUpper, upperMinAge, vanillaGrowth, boneMealOverflow,
                    halfLowerValue, halfUpperValue,
                    requiresWater, resetOnHarvest, upperBlockId,
                    harvestToolTags, harvestToolItems, extraPlantingItems, soilRules
            ));
            BEHAVIORS.put(block.id(), behavior);
            SOIL_RULES.put(block.id(), soilRules);
            registerExtraPlantingItems(block.id(), extraPlantingItems);
            return behavior;
        }
    };

    public static TallCropBlockBehavior getBehavior(Key cropId) {
        if (cropId == null) {
            return null;
        }
        return BEHAVIORS.get(cropId);
    }

    public static void cleanupAll() {
        BEHAVIORS.clear();
        SOIL_RULES.clear();
        EXTRA_PLANTING_ITEMS.clear();
    }

    public static TallCropBlockBehavior getBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return getBehavior(state.owner().value().id());
    }

    public static SoilRules getSoilRules(Key cropId) {
        if (cropId == null) {
            return null;
        }
        return SOIL_RULES.get(cropId);
    }

    public static Key getExtraPlantingCrop(Key itemId) {
        if (itemId == null) {
            return null;
        }
        return EXTRA_PLANTING_ITEMS.get(itemId);
    }

    public Set<Key> extraPlantingItems() {
        return config.extraPlantingItems();
    }

    public int getMaxAgeLower() {
        return config.maxAgeLower();
    }

    public boolean resetsOnHarvest() {
        return config.resetOnHarvest();
    }

    public int getMaxAgeUpper() {
        return config.maxAgeUpper();
    }

    public int getUpperMinAge() {
        return config.upperMinAge();
    }

    public boolean usesVanillaGrowth() {
        return config.vanillaGrowth();
    }

    public boolean carriesBoneMealOverflow() {
        return config.boneMealOverflow();
    }

    public int getAge(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return 0;
        }
        Integer value = state.getNullable(config.ageProperty());
        return value != null ? value : 0;
    }

    public Object getHalf(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return state.getNullable(config.halfProperty());
    }

    public boolean isLowerHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), config.halfLowerValue());
    }

    public boolean isUpperHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), config.halfUpperValue());
    }

    public boolean isLowerMature(ImmutableBlockState state) {
        return getAge(state) >= config.maxAgeLower();
    }

    public boolean isUpperMature(ImmutableBlockState state) {
        return getAge(state) == config.maxAgeUpper();
    }

    static ProtectionCompat.Feature protectionFeature(Key cropId) {
        return cropId != null && Constants.BLOCK_RICE.equals(cropId.toString())
                ? ProtectionCompat.Feature.RICE : null;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();

        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        World world = bukkitPlayer.getWorld();

        ItemStack mainHand = ItemUtils.getItemInHand(bukkitPlayer, context.getHand());
        if (mainHand == null || mainHand.getType().isAir()) {
            return InteractionResult.PASS;
        }

        if (isUpperHalf(state) && isUpperMature(state)) {
            if (config.resetOnHarvest() && isValidHarvestTool(mainHand)) {
                Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                // Harvesting removes the block and drops loot while cancelling native interaction.
                // Check land protection before allowing either change.
                if (!ProtectionCompat.canBreak(bukkitPlayer, bukkitBlock, protectionFeature(block().id()))) {
                    return InteractionResult.PASS;
                }
                Location loc = bukkitBlock.getLocation().add(0.5, 0.5, 0.5);

                // Fired after the protection check and before any drop is spawned. The drop list is
                // empty because the loot for this crop is produced inside CraftEngine (a loot table or
                // a configured break-loot function chain) and never passes through as a list — see the
                // event's javadoc. Outside any block-entity monitor: this behavior holds none.
                Bukkit.getPluginManager().callEvent(new FarmersDelightHarvestEvent(
                        bukkitPlayer, bukkitBlock.getLocation(), CustomBlockUtils.getId(state),
                        mainHand, List.of()));

                net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
                WorldPosition wPos = new WorldPosition(ceWorld, loc.getX(), loc.getY(), loc.getZ());

                if (state.owner().value().loot() == null) {
                    runConfiguredBreakLoot(state, bukkitBlock, bukkitPlayer, mainHand, wPos);
                } else {
                    dropLootTableDrops(state, bukkitBlock, bukkitPlayer, mainHand, wPos);
                }
                awardHarvestStraw(bukkitBlock, bukkitPlayer);

                world.playSound(loc, Sound.BLOCK_CROP_BREAK, 1.0f, 1.0f);
                world.playSound(loc, Sound.ITEM_CROP_PLANT, 1.0f, 0.8f);

                ItemUtils.swingHand(bukkitPlayer, context.getHand());

                CraftEngineBlocks.remove(bukkitBlock);
                resetLowerAfterUpperHarvest(pos, world);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.PASS;
        }

        if (mainHand.getType() == Material.BONE_MEAL && config.isBoneMealTarget()) {
            // Bone meal changes the crop while cancelling native interaction, so check protection first.
            if (!ProtectionCompat.canBuild(bukkitPlayer, world.getBlockAt(pos.x(), pos.y(), pos.z()),
                    protectionFeature(block().id()))) {
                return InteractionResult.PASS;
            }
            if (applyBoneMeal(pos, world, state)) {
                if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                    mainHand.setAmount(mainHand.getAmount() - 1);
                }
                ItemUtils.swingHand(bukkitPlayer, context.getHand());
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        return InteractionResult.PASS;
    }

    @Override
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        if (!config.isBoneMealTarget() || args == null || args.length < 3) {
            return false;
        }
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
        if (state == null || state.isEmpty()) {
            return false;
        }
        Object half = getHalf(state);
        if (isUpperHalf(state)) {
            return getAge(state) < config.maxAgeUpper();
        }
        if (getAge(state) < config.maxAgeLower()) {
            return true;
        }
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (world == null || pos == null || !matchesHalfValue(half, config.halfLowerValue())) {
            return false;
        }
        Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (config.boneMealOverflow() && upperBlock.getType().isAir()) {
            // Vanilla accepts bone meal on a mature lower half so it can grow the missing upper half.
            return true;
        }
        ImmutableBlockState upper = CraftEngineBlocks.getCustomBlockState(upperBlock);
        return upper != null && !upper.isEmpty() && isUpperHalf(upper)
                && getAge(upper) < config.maxAgeUpper();
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public void performBonemeal(Object thisBlock, Object[] args) {
        if (args == null || args.length < 4) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[3]).orElse(null);
        if (world != null && pos != null && state != null && !state.isEmpty()) {
            applyBoneMeal(pos, world, state);
        }
    }

    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        if (args.length >= 3) {
            ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
            World world = CraftEngineAdapter.toWorld(args[1]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);

            if (state != null && !state.isEmpty() && world != null && pos != null) {
                Object half = getHalf(state);

                if (matchesHalfValue(half, config.halfLowerValue())) {
                    BlockPos upperPos = new BlockPos(pos.x(), pos.y() + 1, pos.z());
                    Block upperBlock = world.getBlockAt(upperPos.x(), upperPos.y(), upperPos.z());
                    ImmutableBlockState upperState = CraftEngineBlocks.getCustomBlockState(upperBlock);

                    if (upperState != null && !upperState.isEmpty() && isUpperHalf(upperState)) {
                        CraftEngineBlocks.remove(upperBlock);
                    }
                }

                if (matchesHalfValue(half, config.halfUpperValue()) && config.resetOnHarvest()) {
                    BlockPos lowerPos = new BlockPos(pos.x(), pos.y() - 1, pos.z());
                    Block lowerBlock = world.getBlockAt(lowerPos.x(), lowerPos.y(), lowerPos.z());

                    ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
                    if (isSameCropState(lowerState)) {
                        CraftEngineBlocks.place(lowerBlock.getLocation(), buildLowerResetState(lowerState), false);
                    }
                }
            }
        }

        
    }

    private boolean applyBoneMeal(BlockPos pos, World world, ImmutableBlockState state) {
        int currentAge = getAge(state);
        Object half = getHalf(state);

        if (matchesHalfValue(half, config.halfUpperValue())) {
            return applyBoneMealToUpperHalf(pos, world, state, currentAge);
        }

        if (currentAge >= config.maxAgeLower()) {
            return applyBoneMealToExistingUpperHalf(pos, world);
        }

        int ageBonus = Math.max(0, computeBoneMealAgeBonus());
        int newAge = currentAge + ageBonus;
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());
        return applyBoneMealToLowerHalf(pos, world, state, newAge);
    }

    private int computeBoneMealAgeBonus() {
        return config.boneMealAgeBonus().getInt();
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;

        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;

        int currentAge = getAge(state);
        Object half = getHalf(state);
        boolean isUpper = matchesHalfValue(half, config.halfUpperValue());

        if (isUpper && currentAge >= config.maxAgeUpper()) return;
        if (!isUpper && currentAge >= config.maxAgeLower()) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());

        if (isUpper) {
            tickUpperHalfGrowth(bukkitBlock, state, currentAge);
            return;
        }
        tickLowerHalfGrowth(pos, world, bukkitBlock, state, currentAge);
    }

    private void resetLowerAfterUpperHarvest(BlockPos upperPos, World world) {
        BlockPos lowerPos = new BlockPos(upperPos.x(), upperPos.y() - 1, upperPos.z());
        Block lowerBlock = world.getBlockAt(lowerPos.x(), lowerPos.y(), lowerPos.z());
        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
        if (!isSameCropState(lowerState) || isUpperHalf(lowerState)) return;

        CraftEngineBlocks.place(lowerBlock.getLocation(), buildLowerResetState(lowerState), false);
    }

    private boolean isSameCropState(ImmutableBlockState state) {
        return state != null && !state.isEmpty() && state.owner().value().id().equals(block().id());
    }

    private ImmutableBlockState buildLowerResetState(ImmutableBlockState lowerState) {
        ImmutableBlockState resetState = lowerState;
        if (config.ageProperty() != null) {
            resetState = resetState.with(config.ageProperty(), Math.max(0, config.maxAgeLower() - 1));
        }
        if (config.halfProperty() != null) {
            resetState = withRaw(resetState, config.halfProperty(), config.halfLowerValue());
        }
        if (config.supportingProperty() != null) {
            resetState = resetState.with(config.supportingProperty(), false);
        }
        return resetState;
    }

    private boolean applyBoneMealToUpperHalf(BlockPos pos, World world, ImmutableBlockState state, int currentAge) {
        if (currentAge >= config.maxAgeUpper()) {
            return false;
        }

        int ageBonus = Math.max(0, computeBoneMealAgeBonus());
        int newAge = Math.min(currentAge + ageBonus, config.maxAgeUpper());
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());

        ImmutableBlockState newState = state.with(config.ageProperty(), newAge);
        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
        return true;
    }

    private boolean applyBoneMealToExistingUpperHalf(BlockPos pos, World world) {
        BlockPos upperPos = new BlockPos(pos.x(), pos.y() + 1, pos.z());
        Block upperBlock = world.getBlockAt(upperPos.x(), upperPos.y(), upperPos.z());
        ImmutableBlockState upperState = CraftEngineBlocks.getCustomBlockState(upperBlock);

        if (upperState == null || upperState.isEmpty()) {
            return false;
        }

        int upperAge = getAge(upperState);
        if (upperAge >= config.maxAgeUpper()) {
            return false;
        }

        int ageBonus = Math.max(0, computeBoneMealAgeBonus());
        int newUpperAge = Math.min(upperAge + ageBonus, config.maxAgeUpper());
        playBonemealEffect(world, pos.x(), pos.y() + 1, pos.z());

            ImmutableBlockState newUpperState = upperState.with(config.ageProperty(), newUpperAge);
            CraftEngineBlocks.place(upperBlock.getLocation(), newUpperState, false);
            return true;
    }

    private boolean applyBoneMealToLowerHalf(BlockPos pos, World world, ImmutableBlockState state, int newAge) {
        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());

        if (newAge >= config.maxAgeLower()) {
            placeMatureLowerHalf(bukkitBlock, state);
            Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
            if (upperBlock.getType().isAir()) {
                // Match expected rice growth transition: the first bone meal
                // that pushes the lower half into its supporting stage only
                // spawns a fresh upper half at age 0, without immediately
                // carrying overflow growth into the panicles. A crop configured for the vanilla
                // HighCropBlock cycle does carry it, so the excess becomes the upper half's age.
                int upperAge = config.boneMealOverflow() ? newAge - config.maxAgeLower() - 1 : 0;
                placeUpperHalfWithAge(upperBlock, upperAge);
            } else if (config.boneMealOverflow()) {
                // Vanilla grows an upper half that is already there instead of adding another one.
                applyBoneMealToExistingUpperHalf(pos, world);
            }
            return true;
        }

        ImmutableBlockState newState = state.with(config.ageProperty(), Math.min(newAge, config.maxAgeLower()));
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
        return true;
    }

    private void tickUpperHalfGrowth(Block bukkitBlock, ImmutableBlockState state, int currentAge) {
        if (currentAge >= config.maxAgeUpper()) return;
        if (!passesGrowthLight(bukkitBlock)) return;
        if (!rollGrowth(growthSpeedOf(bukkitBlock))) return;

        ImmutableBlockState newState = state.with(config.ageProperty(), currentAge + 1);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
    }

    // Rice (upper-min-age reaching the lower half's maturity) spawns its panicle in the same tick the
    // stalk matures. A crop configured with an earlier upper-min-age follows its original cycle instead:
    // the lower half ages on one roll and spawns the upper half on a second, independent roll, using the
    // age from before this tick for the spawn condition.
    private void tickLowerHalfGrowth(BlockPos pos, World world, Block bukkitBlock, ImmutableBlockState state, int currentAge) {
        if (config.upperMinAge() >= config.maxAgeLower() && currentAge >= config.maxAgeLower()) return;
        if (!passesGrowthLight(bukkitBlock)) return;

        float growthSpeed = growthSpeedOf(bukkitBlock);
        boolean singleSpawnCycle = config.upperMinAge() >= config.maxAgeLower();
        boolean canAge = currentAge < config.maxAgeLower();
        boolean canSpawnUpper = !singleSpawnCycle && currentAge >= config.upperMinAge();

        if (canAge && rollGrowth(growthSpeed)) {
            int newAge = currentAge + 1;
            if (newAge >= config.maxAgeLower()) {
                placeMatureLowerHalf(bukkitBlock, state);
                if (singleSpawnCycle) {
                    Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
                    if (upperBlock.getType().isAir()) {
                        placeUpperHalfWithAge(upperBlock, 0);
                    }
                    return;
                }
            } else {
                ImmutableBlockState newState = state.with(config.ageProperty(), newAge);
                CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
            }
        }

        if (canSpawnUpper && rollGrowth(growthSpeed)) {
            Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
            if (upperBlock.getType().isAir()) {
                placeUpperHalfWithAge(upperBlock, 0);
            }
        }
    }

    // Vanilla CropBlock tests raw brightness, which counts full sky light, so crops keep growing at
    // night under open sky. The flat model uses Bukkit's light level instead, which the server reduces
    // by the sky darkening, so it stops at night.
    private boolean passesGrowthLight(Block bukkitBlock) {
        if (config.vanillaGrowth()) {
            return rawBrightness(bukkitBlock) >= config.minGrowLight();
        }
        return bukkitBlock.getLightLevel() >= config.minGrowLight();
    }

    private static int rawBrightness(Block bukkitBlock) {
        return Math.max(bukkitBlock.getLightFromSky(), bukkitBlock.getLightFromBlocks());
    }

    // Zero means "roll against the configured flat grow-speed"; anything positive is the vanilla growth
    // speed resolved for this position.
    private float growthSpeedOf(Block bukkitBlock) {
        return config.vanillaGrowth() ? vanillaGrowthSpeed(bukkitBlock) : 0.0F;
    }

    private boolean rollGrowth(float vanillaGrowthSpeed) {
        if (vanillaGrowthSpeed > 0.0F) {
            return VanillaCropGrowth.passes(vanillaGrowthSpeed, ThreadLocalRandom.current().nextFloat());
        }
        return ThreadLocalRandom.current().nextFloat() < config.growSpeed();
    }

    // Mirrors CropBlock.getGrowthSpeed: the 3x3 block area below the crop with moist farmland counting
    // three times, then the halving for a crop standing in a row or diagonal of the same crop. The crop's
    // own soil rules stand in for the grows-crops tag, so a custom farmland counts exactly where the
    // placement check already accepts it.
    private float vanillaGrowthSpeed(Block bukkitBlock) {
        World world = bukkitBlock.getWorld();
        int x = bukkitBlock.getX();
        int y = bukkitBlock.getY();
        int z = bukkitBlock.getZ();

        float[] soilFactors = new float[VanillaCropGrowth.SOIL_FACTOR_COUNT];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                soilFactors[(dx + 1) * 3 + (dz + 1)] = soilFactor(world.getBlockAt(x + dx, y - 1, z + dz));
            }
        }

        boolean horizontal = isSameCrop(world.getBlockAt(x - 1, y, z))
                || isSameCrop(world.getBlockAt(x + 1, y, z));
        boolean vertical = isSameCrop(world.getBlockAt(x, y, z - 1))
                || isSameCrop(world.getBlockAt(x, y, z + 1));
        boolean diagonal = isSameCrop(world.getBlockAt(x - 1, y, z - 1))
                || isSameCrop(world.getBlockAt(x + 1, y, z - 1))
                || isSameCrop(world.getBlockAt(x + 1, y, z + 1))
                || isSameCrop(world.getBlockAt(x - 1, y, z + 1));

        return VanillaCropGrowth.growthSpeed(soilFactors, horizontal, vertical, diagonal);
    }

    // Vanilla reads the moisture property with a default of 0, so a supporting block that does not carry
    // it (a custom farmland) contributes the dry factor while still counting as soil.
    private float soilFactor(Block soil) {
        if (soil == null || !isResident(soil) || !RiceCropRules.isValidSoil(soil, block().id())) {
            return 0.0F;
        }
        if (soil.getType() == Material.FARMLAND
                && soil.getBlockData() instanceof Farmland farmland
                && farmland.getMoisture() > 0) {
            return 3.0F;
        }
        return 1.0F;
    }

    private boolean isSameCrop(Block block) {
        if (block == null || !isResident(block)) {
            return false;
        }
        return isSameCropState(CustomBlockUtils.getStateIfResident(block));
    }

    // Neighbour reads run from a random tick: a block in a chunk that is not loaded must be skipped instead of
    // loading that chunk (and firing its entity-load events) just to answer a growth-speed question.
    private static boolean isResident(Block block) {
        return block.getWorld() != null
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private void placeMatureLowerHalf(Block bukkitBlock, ImmutableBlockState state) {
        ImmutableBlockState matureState = state.with(config.ageProperty(), config.maxAgeLower());
        if (config.supportingProperty() != null) {
            matureState = matureState.with(config.supportingProperty(), true);
        }
        CraftEngineBlocks.place(bukkitBlock.getLocation(), matureState, false);
    }

    private void placeUpperHalfWithAge(Block upperBlock, int age) {
        BlockDefinition upperBlockDefinition = config.upperBlockId() != null
                ? CraftEngineBlocks.byId(config.upperBlockId())
                : CraftEngineBlocks.byId(block().id());
        if (upperBlockDefinition == null) {
            return;
        }

        ImmutableBlockState upperState = upperBlockDefinition.defaultState()
                .with(config.ageProperty(), Math.max(0, Math.min(age, config.maxAgeUpper())))
                ;
        upperState = withRaw(upperState, config.halfProperty(), config.halfUpperValue());
        CraftEngineBlocks.place(upperBlock.getLocation(), upperState, false);
    }

    private void runConfiguredBreakLoot(ImmutableBlockState state, Block bukkitBlock, Player player,
                                        ItemStack tool, WorldPosition position) {
        var cePlayer = BukkitAdaptor.adapt(player);
        Cancellable cancellable = Cancellable.dummy();
        ContextHolder.Builder builder = createLootContext(bukkitBlock, player, tool, position)
                .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state)
                .withParameter(DirectContextParameters.EVENT, cancellable);
        Function.execute(PlayerOptionalContext.of(cePlayer, builder),
                state.owner().value().eventFunctions(EventTrigger.BLOCK_BREAK));
    }

    private void dropLootTableDrops(ImmutableBlockState state, Block bukkitBlock, Player player,
                                    ItemStack tool, WorldPosition position) {
         net.momirealms.craftengine.core.world.World ceWorld = position.world();
        var cePlayer = BukkitAdaptor.adapt(player);
        ContextHolder.Builder builder = createLootContext(bukkitBlock, player, tool, position);
        List<Item> drops = state.getDrops(builder.build(), ceWorld, cePlayer);
        if (drops.isEmpty()) {
            Block lowerBlock = bukkitBlock.getWorld().getBlockAt(
                    bukkitBlock.getX(), bukkitBlock.getY() - 1, bukkitBlock.getZ());
            ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
            if (lowerState != null && !lowerState.isEmpty() && isLowerHalf(lowerState)) {
                drops = lowerState.getDrops(createLootContext(lowerBlock, player, tool, position).build(), ceWorld, cePlayer);
            }
        }
        for (Item drop : drops) {
            ceWorld.dropItemNaturally(position, drop);
        }
    }

    private ContextHolder.Builder createLootContext(Block block, Player player, ItemStack tool, WorldPosition position) {
        ContextHolder.Builder builder = ContextHolder.builder()
                .withParameter(DirectContextParameters.POSITION, position)
                .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(block));
        var cePlayer = BukkitAdaptor.adapt(player);
        if (cePlayer != null) {
            builder.withOptionalParameter(DirectContextParameters.PLAYER, cePlayer);
        }
        if (tool != null && !tool.getType().isAir()) {
            builder.withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, BukkitAdaptor.adapt(tool));
        }
        return builder;
    }

    // The straw item is part of the pack's break-loot chain (see the drop_loot function in crops.yml),
    // which the harvest path runs just above through runConfiguredBreakLoot, so only the advancement is
    // awarded here.
    private void awardHarvestStraw(Block block, Player player) {
        if (block == null || player == null || protectionFeature(block().id()) != ProtectionCompat.Feature.RICE) {
            return;
        }

        var strawDropConfig = FarmersDelightPlugin.getInstance().getStrawDropConfig();
        if (strawDropConfig == null || !strawDropConfig.hasRule("mature_rice")) {
            return;
        }

        var advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "harvest_straw");
        }
    }

    private boolean isValidHarvestTool(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getType() == Material.BONE_MEAL) {
            return false;
        }

        if (matchesConfiguredHarvestItem(item)) {
            return true;
        }

        if (matchesConfiguredHarvestTag(item)) {
            return true;
        }

        return matchesLegacyKnife(item);
    }

    private boolean matchesConfiguredHarvestItem(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null && config.harvestToolItems().contains(customId)) {
            return true;
        }

        String vanillaItemId = ItemUtils.getVanillaMaterialItemId(item);
        return vanillaItemId != null && config.harvestToolItems().contains(vanillaItemId);
    }

    private boolean matchesConfiguredHarvestTag(ItemStack item) {
        for (Key tag : config.harvestToolTags()) {
            if (ItemUtils.matchesVanillaItemTag(item, tag, Collections.emptySet(), Collections.emptySet())) {
                return true;
            }

            String customId = ItemUtils.getCustomItemId(item);
            if (customId == null) {
                continue;
            }

            boolean matchesCustomTag = FarmersDelightPlugin.getInstance()
                    .getCraftEngine()
                    .itemManager()
                    .itemIdsByTag(tag)
                    .stream()
                    .anyMatch(uniqueKey -> uniqueKey.key().toString().equalsIgnoreCase(customId));
            if (matchesCustomTag) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLegacyKnife(ItemStack item) {
        return FarmersDelightPlugin.getInstance().isKnife(item);
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length >= 5) {
            Object placerObj = args[3];
            World world = CraftEngineAdapter.toWorld(args[0]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
            String errorKey = world != null && pos != null ? validatePlacement(world, pos) : null;
            if (errorKey != null) {
                sendPlacementFeedback(placerObj, errorKey);
                return;
            }
        }
        
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        if (context == null || state == null) {
            return null;
        }

        World world = CraftEngineAdapter.toWorld(context.getLevel().platformWorld());
        BlockPos pos = context.getClickedPos();
        String errorKey = world != null && pos != null ? validatePlacement(world, pos) : "crop.invalid_soil";
        if (errorKey != null) {
            sendPlacementFeedback(context.getPlayer(), errorKey);
            return null;
        }
        return state;
    }

    private static Set<String> parseConfiguredItemIds(Map<String, Object> arguments) {
        Object raw = arguments != null ? BehaviorArgParser.getRaw(arguments, "harvest-tool.items") : null;
        if (!(raw instanceof Iterable<?> iterable)) {
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();
        for (Object value : iterable) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    private static Set<Key> parseConfiguredKeys(Map<String, Object> arguments) {
        Object raw = null;
        if (arguments != null) {
            for (String key : new String[]{"extra-planting-items", "extra_planting_items", "extraPlantingItems"}) {
                if (arguments.containsKey(key)) {
                    raw = arguments.get(key);
                    break;
                }
            }
        }
        if (raw == null) {
            return Collections.emptySet();
        }

        Set<Key> result = new HashSet<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                addKey(result, value);
            }
        } else {
            addKey(result, raw);
        }
        return result;
    }

    private static void addKey(Set<Key> result, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            result.add(Key.of(text));
        }
    }

    private static void registerExtraPlantingItems(Key cropId, Set<Key> extraPlantingItems) {
        EXTRA_PLANTING_ITEMS.entrySet().removeIf(entry -> cropId.equals(entry.getValue()));
        for (Key itemId : extraPlantingItems) {
            if (cropId.equals(itemId)) {
                continue;
            }
            Key existing = EXTRA_PLANTING_ITEMS.putIfAbsent(itemId, cropId);
            if (existing != null && !existing.equals(cropId)) {
                warn("Ignoring extra planting item '" + itemId + "' for crop '" + cropId
                        + "' because it is already mapped to crop '" + existing + "'.");
            }
        }
    }

    private static void warn(String message) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    private static int inferMaxIntegerValue(Property<Integer> property) {
        if (property == null) {
            return 4;
        }
        try {
            List<Integer> values = property.possibleValues();
            if (values == null || values.isEmpty()) {
                return 4;
            }
            return Collections.max(values);
        } catch (Exception ignored) {
            return 4;
        }
    }

    private static Object inferLowerHalfValue(Property<?> property) {
        if (property == null) {
            return "lower";
        }
        try {
            List<?> values = property.possibleValues();
            if (values == null || values.isEmpty()) {
                return "lower";
            }

            Object named = findNamedHalfValue(values, "lower");
            if (named != null) {
                return named;
            }
            return values.getFirst();
        } catch (Exception ignored) {
            return "lower";
        }
    }

    private static Object inferUpperHalfValue(Property<?> property) {
        if (property == null) {
            return "upper";
        }
        try {
            List<?> values = property.possibleValues();
            if (values == null || values.isEmpty()) {
                return "upper";
            }

            Object named = findNamedHalfValue(values, "upper");
            if (named != null) {
                return named;
            }
            return values.getLast();
        } catch (Exception ignored) {
            return "upper";
        }
    }

    private static Object findNamedHalfValue(List<?> values, String target) {
        for (Object value : values) {
            if (matchesHalfValue(value, target)) {
                return value;
            }
        }
        return null;
    }

    private static boolean matchesHalfValue(Object actual, Object expected) {
        if (actual == expected) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        String actualText = normalizeHalfValue(actual);
        String expectedText = normalizeHalfValue(expected);
        return !actualText.isEmpty() && actualText.equals(expectedText);
    }

    private static String normalizeHalfValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toLowerCase();
    }

    private static Object getRawPropertyValue(Object configuredValue, Property<?> property, Object fallback) {
        if (configuredValue == null || property == null) {
            return fallback;
        }

        try {
            List<?> values = property.possibleValues();
            if (values != null) {
                for (Object candidate : values) {
                    if (matchesHalfValue(candidate, configuredValue)) {
                        return candidate;
                    }
                }
            }
        } catch (Exception ignored) {
            // property resolution is bound-safe; unreachable under normal conditions
        }
        return configuredValue;
    }

    private static ImmutableBlockState withRaw(ImmutableBlockState state, Property<?> property, Object value) {
        if (state == null || property == null || value == null) {
            return state;
        }
        return ImmutableBlockState.with(state, property, value);
    }

    private String validatePlacement(World world, BlockPos pos) {
        Block plantingBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Block blockBelow = world.getBlockAt(pos.x(), pos.y() - 1, pos.z());
        Block soilBlock = getSupportingSoilBlock(plantingBlock, blockBelow);

        if (!isValidSoil(soilBlock)) {
            return "crop.invalid_soil";
        }

        if (config.requiresWater() && !isValidWaterPlacement(world, pos, plantingBlock, blockBelow)) {
            return "crop.need_water";
        }

        return null;
    }

    private boolean isValidWaterPlacement(World world, BlockPos pos, Block plantingBlock, Block blockBelow) {
        Block waterBlock = RiceCropRules.getWaterSourceBlock(plantingBlock, blockBelow, block().id());
        return waterBlock != null && RiceCropRules.isSingleSourceWater(waterBlock);
    }

    private Block getSupportingSoilBlock(Block plantingBlock, Block blockBelow) {
        return RiceCropRules.getSupportingSoilBlock(plantingBlock, blockBelow, block().id(), config.requiresWater());
    }
    
    private boolean isValidSoil(Block block) {
        return RiceCropRules.isValidSoil(block, block().id());
    }
    
    private void sendPlacementFeedback(Object placerObj, String messageKey) {
        if (placerObj instanceof Player player) {
            player.sendMessage(I18n.getComponent(messageKey, player));
        }
    }
}
