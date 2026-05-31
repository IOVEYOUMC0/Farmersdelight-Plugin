package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Cancellable;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class TallCropBlockBehavior extends BlockBehavior {

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
    private final Set<Key> harvestToolTags;
    private final Set<String> harvestToolItems;
    private final Set<Key> extraPlantingItems;
    private static final Map<Key, TallCropBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<Key, SoilRules> SOIL_RULES = new ConcurrentHashMap<>();
    private static final Map<Key, Key> EXTRA_PLANTING_ITEMS = new ConcurrentHashMap<>();

    private TallCropBlockBehavior(BlockDefinition block, Property<Integer> ageProperty,
                                   Property<?> halfProperty, Property<Boolean> supportingProperty,
                                   float growSpeed, int minGrowLight, boolean isBoneMealTarget, boolean randomBoneMealGrowth,
                                   int boneMealMin, int boneMealMax,
                                    int maxAgeLower, int maxAgeUpper, Object halfLowerValue, Object halfUpperValue,
                                    boolean requiresWater, boolean resetOnHarvest, Key upperBlockId,
                                    Set<Key> harvestToolTags, Set<String> harvestToolItems,
                                    Set<Key> extraPlantingItems, SoilRules soilRules) {
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
        this.harvestToolTags = harvestToolTags;
        this.harvestToolItems = harvestToolItems;
        this.extraPlantingItems = Set.copyOf(extraPlantingItems);
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<TallCropBlockBehavior> FACTORY = new BlockBehaviorFactory<TallCropBlockBehavior>() {
        @Override
        public TallCropBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
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
            Set<Key> harvestToolTags = SoilRuleSupport.parseKeys(arguments, "harvest-tool-tags");
            Set<String> harvestToolItems = parseConfiguredItemIds(arguments, "harvest-tool-items");
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            Set<Key> extraPlantingItems = parseConfiguredKeys(arguments,
                    "extra-planting-items", "extra_planting_items", "extraPlantingItems");
            
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
                    harvestToolTags,
                    harvestToolItems,
                    extraPlantingItems,
                    soilRules
            );
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
        return extraPlantingItems;
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
        return getAge(state) == maxAgeUpper;
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
            if (resetOnHarvest && isValidHarvestTool(mainHand)) {
                Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());
                Location loc = bukkitBlock.getLocation().add(0.5, 0.5, 0.5);

                net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
                WorldPosition wPos = new WorldPosition(ceWorld, loc.getX(), loc.getY(), loc.getZ());

                if (state.owner().value().loot() == null) {
                    runConfiguredBreakLoot(state, bukkitBlock, bukkitPlayer, mainHand, wPos);
                } else {
                    dropLootTableDrops(state, bukkitBlock, bukkitPlayer, mainHand, wPos);
                }
                dropHarvestStraw(bukkitBlock, bukkitPlayer);

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
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        return InteractionResult.PASS;
    }

    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
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
    public void randomTick(Object thisBlock, Object[] args) {
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
        BlockDefinition upperBlockDefinition = upperBlockId != null
                ? CraftEngineBlocks.byId(upperBlockId)
                : CraftEngineBlocks.byId(block().id());
        if (upperBlockDefinition == null) {
            return;
        }

        ImmutableBlockState upperState = upperBlockDefinition.defaultState()
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

    private void runConfiguredBreakLoot(ImmutableBlockState state, Block bukkitBlock, Player player,
                                        ItemStack tool, WorldPosition position) {
        var cePlayer = BukkitAdaptor.adapt(player);
        Cancellable cancellable = Cancellable.dummy();
        ContextHolder.Builder builder = createLootContext(bukkitBlock, player, tool, position)
                .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state)
                .withParameter(DirectContextParameters.EVENT, cancellable);
        state.owner().value().execute(PlayerOptionalContext.of(cePlayer, builder), EventTrigger.BREAK);
    }

    private void dropLootTableDrops(ImmutableBlockState state, Block bukkitBlock, Player player,
                                    ItemStack tool, WorldPosition position) {
        net.momirealms.craftengine.core.world.World ceWorld = position.world();
        var cePlayer = BukkitAdaptor.adapt(player);
        ContextHolder.Builder builder = createLootContext(bukkitBlock, player, tool, position);
        List<Item> drops = state.getDrops(builder, ceWorld, cePlayer);
        if (drops.isEmpty()) {
            Block lowerBlock = bukkitBlock.getWorld().getBlockAt(
                    bukkitBlock.getX(), bukkitBlock.getY() - 1, bukkitBlock.getZ());
            ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
            if (lowerState != null && !lowerState.isEmpty() && isLowerHalf(lowerState)) {
                drops = lowerState.getDrops(createLootContext(lowerBlock, player, tool, position), ceWorld, cePlayer);
            }
        }
        for (Item drop : drops) {
            ceWorld.dropItemNaturally(position, drop);
        }
    }

    private ContextHolder.Builder createLootContext(Block block, Player player, ItemStack tool, WorldPosition position) {
        ContextHolder.Builder builder = new ContextHolder.Builder()
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

    private void dropHarvestStraw(Block block, Player player) {
        if (block == null || player == null) {
            return;
        }

        var rule = FarmersDelightPlugin.getInstance().getStrawDropConfig().getRule("mature_rice");
        if (rule == null || rule.getDropItem() == null) {
            return;
        }

        ItemStack straw = ItemUtils.createItem(rule.getDropItem());
        if (straw == null || straw.getType().isAir()) {
            return;
        }

        int minAmount = Math.max(1, rule.getMinAmount());
        int maxAmount = Math.max(minAmount, rule.getMaxAmount());
        straw.setAmount(minAmount + ThreadLocalRandom.current().nextInt(maxAmount - minAmount + 1));
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), straw);

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
        if (customId != null && harvestToolItems.contains(customId)) {
            return true;
        }

        String vanillaItemId = ItemUtils.getVanillaMaterialItemId(item);
        return vanillaItemId != null && harvestToolItems.contains(vanillaItemId);
    }

    private boolean matchesConfiguredHarvestTag(ItemStack item) {
        for (Key tag : harvestToolTags) {
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
        String customId = ItemUtils.getCustomItemId(item);
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin.isKnifeItemId(customId)) {
            return true;
        }

        for (String configuredTag : plugin.getKnifeTagIds()) {
            Key key = Key.of(configuredTag.startsWith("#") ? configuredTag.substring(1) : configuredTag);
            if (ItemUtils.matchesVanillaItemTag(item, key, Collections.emptySet(), Collections.emptySet())) {
                return true;
            }
        }

        return false;
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

    private static Set<String> parseConfiguredItemIds(Map<String, Object> arguments, String key) {
        Object raw = arguments != null ? arguments.get(key) : null;
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

    private static Set<Key> parseConfiguredKeys(Map<String, Object> arguments, String... keys) {
        Object raw = null;
        if (arguments != null) {
            for (String key : keys) {
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

