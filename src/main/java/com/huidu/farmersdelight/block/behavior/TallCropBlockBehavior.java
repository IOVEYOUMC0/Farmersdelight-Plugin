package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptors;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class TallCropBlockBehavior extends BlockBehavior {
    private final Property<Integer> ageProperty;
    private final Property<?> halfProperty;
    private final Property<Boolean> supportingProperty;
    private final float growSpeed;
    private final int minGrowLight;
    private final boolean isBoneMealTarget;
    private final boolean randomBoneMealGrowth;
    private final int boneMealMin;
    private final int boneMealMax;
    private final int maxAgeLower;
    private final int maxAgeUpper;
    private final Object halfLowerValue;
    private final Object halfUpperValue;
    private final boolean requiresWater;
    private final boolean resetOnHarvest;
    private final Key upperBlockId;
    private static final Map<Key, TallCropBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<Key, SoilRules> SOIL_RULES = new ConcurrentHashMap<>();

    private TallCropBlockBehavior(CustomBlock block, Property<Integer> ageProperty,
                                   Property<?> halfProperty, Property<Boolean> supportingProperty,
                                   float growSpeed, int minGrowLight, boolean isBoneMealTarget, boolean randomBoneMealGrowth,
                                   int boneMealMin, int boneMealMax,
                                   int maxAgeLower, int maxAgeUpper, Object halfLowerValue, Object halfUpperValue,
                                   boolean requiresWater, boolean resetOnHarvest, Key upperBlockId,
                                   SoilRules soilRules) {
        super(block);
        this.ageProperty = ageProperty;
        this.halfProperty = halfProperty;
        this.supportingProperty = supportingProperty;
        this.growSpeed = growSpeed;
        this.minGrowLight = minGrowLight;
        this.isBoneMealTarget = isBoneMealTarget;
        this.randomBoneMealGrowth = randomBoneMealGrowth;
        this.boneMealMin = boneMealMin;
        this.boneMealMax = boneMealMax;
        this.maxAgeLower = maxAgeLower;
        this.maxAgeUpper = maxAgeUpper;
        this.halfLowerValue = halfLowerValue;
        this.halfUpperValue = halfUpperValue;
        this.requiresWater = requiresWater;
        this.resetOnHarvest = resetOnHarvest;
        this.upperBlockId = upperBlockId;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<TallCropBlockBehavior> FACTORY = new BlockBehaviorFactory<TallCropBlockBehavior>() {
        @Override
        public TallCropBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            String agePropertyName = getString(arguments, "age-property", "age");
            Property<Integer> ageProperty = (Property<Integer>) block.getProperty(agePropertyName);
            if (ageProperty == null) {
                ageProperty = (Property<Integer>) block.getProperty("age");
            }
            
            String halfPropertyName = getString(arguments, "half-property", "half");
            Property<?> halfProperty = block.getProperty(halfPropertyName);
            
            String supportingPropertyName = getString(arguments, "supporting-property", "supporting");
            Property<Boolean> supportingProperty = (Property<Boolean>) block.getProperty(supportingPropertyName);

            float growSpeed = getFloat(arguments, "grow-speed", 0.25f);
            int minGrowLight = getInt(arguments, "light-requirement", 9);
            boolean isBoneMealTarget = getBoolean(arguments, "is-bone-meal-target", true);
            boolean randomBoneMealGrowth = getBoolean(arguments, "random-bone-meal-growth", false);
            int boneMealMin = getInt(arguments, "bone-meal-min", 1);
            int boneMealMax = getInt(arguments, "bone-meal-max", randomBoneMealGrowth ? 4 : 2);
            if (boneMealMin < 1) boneMealMin = 1;
            if (boneMealMax < boneMealMin) boneMealMax = boneMealMin;
            
            int maxAgeLower = hasArgument(arguments, "max-age-lower")
                    ? getInt(arguments, "max-age-lower", 4)
                    : inferMaxIntegerValue(ageProperty, 4);
            int maxAgeUpper = hasArgument(arguments, "max-age-upper")
                    ? getInt(arguments, "max-age-upper", 3)
                    : Math.max(0, maxAgeLower - 1);
            
            Object halfLowerValue = hasArgument(arguments, "half-lower-value")
                    ? getRawPropertyValue(arguments.get("half-lower-value"), halfProperty, inferLowerHalfValue(halfProperty))
                    : inferLowerHalfValue(halfProperty);
            Object halfUpperValue = hasArgument(arguments, "half-upper-value")
                    ? getRawPropertyValue(arguments.get("half-upper-value"), halfProperty, inferUpperHalfValue(halfProperty))
                    : inferUpperHalfValue(halfProperty);
            
            boolean requiresWater = getBoolean(arguments, "requires-water", false);
            boolean resetOnHarvest = getBoolean(arguments, "reset-on-harvest", true);
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            
            String upperBlockStr = getString(arguments, "upper-block", "");
            Key upperBlockId = upperBlockStr.isEmpty() ? null : Key.of(upperBlockStr);

            TallCropBlockBehavior behavior = new TallCropBlockBehavior(
                    block,
                    ageProperty,
                    halfProperty,
                    supportingProperty,
                    growSpeed,
                    minGrowLight,
                    isBoneMealTarget,
                    randomBoneMealGrowth,
                    boneMealMin,
                    boneMealMax,
                    maxAgeLower,
                    maxAgeUpper,
                    halfLowerValue,
                    halfUpperValue,
                    requiresWater,
                    resetOnHarvest,
                    upperBlockId,
                    soilRules
            );
            BEHAVIORS.put(block.id(), behavior);
            SOIL_RULES.put(block.id(), soilRules);
            return behavior;
        }
    };

    public static TallCropBlockBehavior getBehavior(Key cropId) {
        if (cropId == null) {
            return null;
        }
        return BEHAVIORS.get(cropId);
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

    public int getMaxAgeLower() {
        return maxAgeLower;
    }

    public int getMaxAgeUpper() {
        return maxAgeUpper;
    }

    public int getAge(ImmutableBlockState state) {
        if (ageProperty == null) return 0;
        Integer value = state.get(ageProperty);
        if (value != null) {
            return value;
        }
        return 0;
    }

    public Object getHalf(ImmutableBlockState state) {
        if (halfProperty == null) return halfLowerValue;
        Object value = state.get(halfProperty);
        if (value != null) {
            return value;
        }
        return halfLowerValue;
    }

    public boolean isLowerHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), halfLowerValue);
    }

    public boolean isUpperHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), halfUpperValue);
    }

    public boolean isLowerMature(ImmutableBlockState state) {
        return getAge(state) >= maxAgeLower;
    }

    public boolean isUpperMature(ImmutableBlockState state) {
        return getAge(state) >= maxAgeUpper;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        World world = bukkitPlayer.getWorld();

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();

        if (isUpperHalf(state) && isUpperMature(state)) {
            if (resetOnHarvest && mainHand.getType() != Material.BONE_MEAL && !mainHand.getType().isAir()) {
                Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                Location loc = bukkitBlock.getLocation().add(0.5, 0.5, 0.5);

                net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptors.adapt(world);
                WorldPosition wPos = new WorldPosition(ceWorld, loc.getX(), loc.getY(), loc.getZ());
                ContextHolder.Builder builder = new ContextHolder.Builder()
                        .withParameter(DirectContextParameters.POSITION, wPos)
                        .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(bukkitBlock))
                        .withOptionalParameter(DirectContextParameters.PLAYER, BukkitAdaptors.adapt(bukkitPlayer))
                        .withOptionalParameter(DirectContextParameters.ITEM_IN_HAND, BukkitAdaptors.adapt(mainHand));
                List<Item<Object>> drops = state.getDrops(builder, ceWorld, BukkitAdaptors.adapt(bukkitPlayer));
                for (Item<Object> drop : drops) {
                    ceWorld.dropItemNaturally(wPos, drop);
                }

                world.playSound(loc, Sound.BLOCK_CROP_BREAK, 1.0f, 1.0f);
                world.playSound(loc, Sound.ITEM_CROP_PLANT, 1.0f, 0.8f);

                bukkitPlayer.swingMainHand();

                CraftEngineBlocks.remove(bukkitBlock);
                resetLowerAfterUpperHarvest(pos, world);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.PASS;
        }

        if (mainHand.getType() == Material.BONE_MEAL && isBoneMealTarget) {
            if (applyBoneMeal(pos, world, state, bukkitPlayer)) {
                if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                    mainHand.setAmount(mainHand.getAmount() - 1);
                }
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS;
    }

    public void onRemove(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        if (args.length >= 3) {
            ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
            World world = CraftEngineAdapter.toWorld(args[1]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);

            if (state != null && !state.isEmpty() && world != null && pos != null) {
                Object half = getHalf(state);

                if (matchesHalfValue(half, halfLowerValue)) {
                    BlockPos upperPos = new BlockPos(pos.x(), pos.y() + 1, pos.z());
                    Block upperBlock = world.getBlockAt(upperPos.x(), upperPos.y(), upperPos.z());
                    ImmutableBlockState upperState = CraftEngineBlocks.getCustomBlockState(upperBlock);

                    if (upperState != null && !upperState.isEmpty() && isUpperHalf(upperState)) {
                        upperBlock.setType(Material.AIR, false);
                    }
                }

                if (matchesHalfValue(half, halfUpperValue) && resetOnHarvest) {
                    BlockPos lowerPos = new BlockPos(pos.x(), pos.y() - 1, pos.z());
                    Block lowerBlock = world.getBlockAt(lowerPos.x(), lowerPos.y(), lowerPos.z());

                    ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
                    if (lowerState != null && !lowerState.isEmpty()) {
                        ImmutableBlockState resetState = lowerState;
                        if (ageProperty != null) {
                            resetState = resetState.with(ageProperty, Math.max(0, maxAgeLower - 1));
                        }
                        if (halfProperty != null) {
                            resetState = withRaw(resetState, halfProperty, halfLowerValue);
                        }
                        if (supportingProperty != null) {
                            resetState = resetState.with(supportingProperty, false);
                        }
                        CraftEngineBlocks.place(lowerBlock.getLocation(), resetState, false);
                    }
                }
            }
        }

        superMethod.call();
    }

    private boolean applyBoneMeal(BlockPos pos, World world, ImmutableBlockState state, Player player) {
        int currentAge = getAge(state);
        Object half = getHalf(state);

        if (matchesHalfValue(half, halfUpperValue)) {
            return applyBoneMealToUpperHalf(pos, world, state, currentAge);
        }

        if (currentAge >= maxAgeLower) {
            return applyBoneMealToExistingUpperHalf(pos, world);
        }

        int ageBonus = randomBoneMealGrowth
                ? boneMealMin + ThreadLocalRandom.current().nextInt(boneMealMax - boneMealMin + 1)
                : boneMealMin;
        int newAge = currentAge + ageBonus;
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());
        return applyBoneMealToLowerHalf(pos, world, state, newAge);
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        if (args.length < 3) return;

        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;

        int currentAge = getAge(state);
        Object half = getHalf(state);
        boolean isUpper = matchesHalfValue(half, halfUpperValue);

        if (isUpper && currentAge >= maxAgeUpper) return;
        if (!isUpper && currentAge >= maxAgeLower) return;

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
        if (lowerState == null || lowerState.isEmpty() || isUpperHalf(lowerState)) return;

        ImmutableBlockState resetState = lowerState;
        if (ageProperty != null) {
            resetState = resetState.with(ageProperty, Math.max(0, maxAgeLower - 1));
        }
        if (halfProperty != null) {
            resetState = withRaw(resetState, halfProperty, halfLowerValue);
        }
        if (supportingProperty != null) {
            resetState = resetState.with(supportingProperty, false);
        }
        CraftEngineBlocks.place(lowerBlock.getLocation(), resetState, false);
    }

    private boolean applyBoneMealToUpperHalf(BlockPos pos, World world, ImmutableBlockState state, int currentAge) {
        if (currentAge >= maxAgeUpper) {
            return false;
        }

        int ageBonus = boneMealMin + ThreadLocalRandom.current().nextInt(Math.max(1, boneMealMax - boneMealMin + 1));
        int newAge = Math.min(currentAge + ageBonus, maxAgeUpper);
        playBonemealEffect(world, pos.x(), pos.y(), pos.z());

        ImmutableBlockState newState = state.with(ageProperty, newAge);
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
        if (upperAge >= maxAgeUpper) {
            return false;
        }

        int ageBonus = boneMealMin + ThreadLocalRandom.current().nextInt(Math.max(1, boneMealMax - boneMealMin + 1));
        int newUpperAge = Math.min(upperAge + ageBonus, maxAgeUpper);
        playBonemealEffect(world, pos.x(), pos.y() + 1, pos.z());

            ImmutableBlockState newUpperState = upperState.with(ageProperty, newUpperAge);
            CraftEngineBlocks.place(upperBlock.getLocation(), newUpperState, false);
            return true;
    }

    private boolean applyBoneMealToLowerHalf(BlockPos pos, World world, ImmutableBlockState state, int newAge) {
        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());

        if (newAge >= maxAgeLower) {
            placeMatureLowerHalf(bukkitBlock, state);
            Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
            if (upperBlock.getType().isAir()) {
                // Match the expected rice transition: the first bonemeal step that
                // pushes the lower half into its supporting stage only spawns a
                // fresh upper half at age 0, instead of carrying overflow growth
                // into the panicles immediately.
                placeUpperHalfWithAge(upperBlock, 0);
            }
            return true;
        }

        ImmutableBlockState newState = state.with(ageProperty, Math.min(newAge, maxAgeLower));
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
        return true;
    }

    private void tickUpperHalfGrowth(Block bukkitBlock, ImmutableBlockState state, int currentAge) {
        if (currentAge >= maxAgeUpper) return;
        if (bukkitBlock.getLightLevel() < minGrowLight) return;
        if (ThreadLocalRandom.current().nextFloat() >= growSpeed) return;

        ImmutableBlockState newState = state.with(ageProperty, currentAge + 1);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
    }

    private void tickLowerHalfGrowth(BlockPos pos, World world, Block bukkitBlock, ImmutableBlockState state, int currentAge) {
        if (currentAge >= maxAgeLower) return;
        if (bukkitBlock.getLightLevel() < minGrowLight) return;
        if (ThreadLocalRandom.current().nextFloat() >= growSpeed) return;

        int newAge = currentAge + 1;
        if (newAge >= maxAgeLower) {
            placeMatureLowerHalf(bukkitBlock, state);
            Block upperBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
            if (upperBlock.getType().isAir()) {
                placeUpperHalfWithAge(upperBlock, 0);
            }
            return;
        }

        ImmutableBlockState newState = state.with(ageProperty, newAge);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
    }

    private void placeMatureLowerHalf(Block bukkitBlock, ImmutableBlockState state) {
        ImmutableBlockState matureState = state.with(ageProperty, maxAgeLower);
        if (supportingProperty != null) {
            matureState = matureState.with(supportingProperty, true);
        }
        CraftEngineBlocks.place(bukkitBlock.getLocation(), matureState, false);
    }

    private void placeUpperHalfWithAge(Block upperBlock, int age) {
        CustomBlock upperCustomBlock = upperBlockId != null
                ? CraftEngineBlocks.byId(upperBlockId)
                : CraftEngineBlocks.byId(block().id());
        if (upperCustomBlock == null) {
            return;
        }

        ImmutableBlockState upperState = upperCustomBlock.defaultState()
                .with(ageProperty, age)
                ;
        upperState = withRaw(upperState, halfProperty, halfUpperValue);
        CraftEngineBlocks.place(upperBlock.getLocation(), upperState, false);
    }

    private void playBonemealEffect(World world, int x, int y, int z) {
        Location location = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        world.spawnParticle(Particle.HAPPY_VILLAGER, location, 15, 0.5, 0.5, 0.5);
        world.playSound(location, Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f);
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
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
        superMethod.call();
    }

    private static String getString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value != null) {
            return String.valueOf(value);
        }
        return defaultValue;
    }

    private static boolean hasArgument(Map<String, Object> arguments, String key) {
        if (arguments == null || !arguments.containsKey(key)) {
            return false;
        }
        Object value = arguments.get(key);
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private static int getInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static float getFloat(Map<String, Object> arguments, String key, float defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Float.parseFloat(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    private static int inferMaxIntegerValue(Property<Integer> property, int fallback) {
        if (property == null) {
            return fallback;
        }
        try {
            List<Integer> values = property.possibleValues();
            if (values == null || values.isEmpty()) {
                return fallback;
            }
            return Collections.max(values);
        } catch (Exception ignored) {
            return fallback;
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
            return values.get(0);
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
            return values.get(values.size() - 1);
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
        }
        return configuredValue;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ImmutableBlockState withRaw(ImmutableBlockState state, Property<?> property, Object value) {
        if (state == null || property == null || value == null) {
            return state;
        }
        return state.with((Property) property, (Comparable) value);
    }

    private String validatePlacement(World world, BlockPos pos) {
        Block plantingBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Block blockBelow = world.getBlockAt(pos.x(), pos.y() - 1, pos.z());
        Block soilBlock = getSupportingSoilBlock(plantingBlock, blockBelow);

        if (!isValidSoil(soilBlock)) {
            return "crop.invalid_soil";
        }

        if (requiresWater && !isValidWaterPlacement(world, pos, plantingBlock, blockBelow)) {
            return "crop.need_water";
        }

        return null;
    }

    private boolean isValidWaterPlacement(World world, BlockPos pos, Block plantingBlock, Block blockBelow) {
        return RiceCropRules.getWaterSourceBlock(plantingBlock, blockBelow, block().id()) != null;
    }

    private Block getSupportingSoilBlock(Block plantingBlock, Block blockBelow) {
        return RiceCropRules.getSupportingSoilBlock(plantingBlock, blockBelow, block().id(), requiresWater);
    }
    
    private boolean isValidSoil(Block block) {
        return RiceCropRules.isValidSoil(block, block().id());
    }
    
    private void sendPlacementFeedback(Object placerObj, String messageKey) {
        if (placerObj instanceof Player player) {
            player.sendMessage(I18n.get(messageKey, player));
        }
    }
}
