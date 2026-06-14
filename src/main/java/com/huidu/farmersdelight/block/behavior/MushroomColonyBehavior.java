package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MushroomColonyBehavior extends BlockBehavior {

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
    private static final Map<Key, MushroomColonyBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    private final Property<Integer> ageProperty;
    private final int maxAge;
    private final float growSpeed;
    private final int minGrowLight;
    private final Set<Key> harvestToolTags;
    private final Set<String> harvestToolItems;
    private final SoilRuleSupport.SoilRules growSoilRules;
    private final String mushroomItemId;

    private MushroomColonyBehavior(
            BlockDefinition block,
            Property<Integer> ageProperty,
            int maxAge,
            float growSpeed,
            int minGrowLight,
            Set<Key> harvestToolTags,
            Set<String> harvestToolItems,
            SoilRuleSupport.SoilRules growSoilRules,
            String mushroomItemId
    ) {
        super(block);
        this.ageProperty = ageProperty;
        this.maxAge = maxAge;
        this.growSpeed = growSpeed;
        this.minGrowLight = minGrowLight;
        this.harvestToolTags = harvestToolTags;
        this.harvestToolItems = harvestToolItems;
        this.growSoilRules = growSoilRules;
        this.mushroomItemId = mushroomItemId;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<MushroomColonyBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public MushroomColonyBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String agePropertyName = BehaviorArgParser.getString(arguments, "age-property", "age");
            Property<Integer> ageProperty = (Property<Integer>) block.getProperty(agePropertyName);
            if (ageProperty == null) {
                ageProperty = (Property<Integer>) block.getProperty("age");
            }

            int maxAge = BehaviorArgParser.hasArgument(arguments, "max-age")
                    ? BehaviorArgParser.getInt(arguments, "max-age", 3)
                    : BehaviorArgParser.inferMaxIntegerValue(ageProperty, 3);
            float growSpeed = BehaviorArgParser.getFloat(arguments, "grow-speed", 0.25f);
            int minGrowLight = BehaviorArgParser.getInt(arguments, "light-requirement", 0);
            Set<Key> harvestToolTags = SoilRuleSupport.parseKeys(arguments, "harvest-tool-tags");
            Set<String> harvestToolItems = parseConfiguredItemIds(arguments, "harvest-tool-items");
            SoilRuleSupport.SoilRules growSoilRules = parseGrowSoilRules(arguments);
            String mushroomItemId = BehaviorArgParser.getString(arguments, "mushroom-type", "");

            MushroomColonyBehavior behavior = new MushroomColonyBehavior(
                    block,
                    ageProperty,
                    maxAge,
                    growSpeed,
                    minGrowLight,
                    harvestToolTags,
                    harvestToolItems,
                    growSoilRules,
                    mushroomItemId
            );
            BEHAVIORS.put(block.id(), behavior);
            return behavior;
        }
    };

    public static MushroomColonyBehavior getBehavior(Key blockId) {
        if (blockId == null) {
            return null;
        }
        return BEHAVIORS.get(blockId);
    }

    public static void cleanupAll() {
        BEHAVIORS.clear();
    }

    public static MushroomColonyBehavior getBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return getBehavior(state.owner().value().id());
    }

    public int getAge(ImmutableBlockState state) {
        if (ageProperty == null || state == null || state.isEmpty()) {
            return 0;
        }
        Integer value = state.get(ageProperty);
        return value != null ? value : 0;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }

        Player player = Bukkit.getPlayer(context.getPlayer().uuid());
        if (player == null) {
            return InteractionResult.PASS;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) {
            return InteractionResult.PASS;
        }

        int currentAge = getAge(state);
        if (currentAge <= 0) {
            return InteractionResult.PASS;
        }

        boolean shearsHarvest = isShearsHarvestTool(mainHand);
        boolean knifeHarvest = !shearsHarvest && isKnifeHarvestTool(mainHand);
        if (!shearsHarvest && !knifeHarvest) {
            return InteractionResult.PASS;
        }

        ItemStack mushroomDrop = ItemUtils.createItem(mushroomItemId);
        if (mushroomDrop == null || mushroomDrop.getType().isAir()) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = player.getWorld();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);

        if (shearsHarvest) {
            mushroomDrop.setAmount(1);
            ImmutableBlockState nextState = state.with(ageProperty, Math.max(0, currentAge - 1));
            CraftEngineBlocks.place(block.getLocation(), nextState, false);
            world.playSound(loc, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);
        } else {
            mushroomDrop.setAmount(currentAge);
            ImmutableBlockState resetState = state.with(ageProperty, 0);
            CraftEngineBlocks.place(block.getLocation(), resetState, false);
            world.playSound(loc, Sound.BLOCK_GROWING_PLANT_CROP, 1.0f, 1.0f);
        }

        world.dropItemNaturally(loc, mushroomDrop);
        player.swingMainHand();
        damageHeldTool(player, mainHand);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private void damageHeldTool(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (!(item.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) {
            return;
        }
        // 使用物品的有效最大损伤值（自定义物品携带自定义的 max_damage 组件），
        // 并在达到最大值时真正消耗掉该工具，而不是让损伤值无限制地超过耐久度继续增长。
        int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        if (maxDamage <= 0) {
            return;
        }
        int newDamage = damageable.getDamage() + 1;
        if (newDamage >= maxDamage) {
            item.setAmount(0);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else {
            damageable.setDamage(newDamage);
            item.setItemMeta(damageable);
        }
    }

    private boolean isShearsHarvestTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (matchesConfiguredToolItem(item, Constants.ITEM_SHEARS)) {
            return true;
        }

        for (Key harvestToolTag : harvestToolTags) {
            if (ItemUtils.matchesVanillaItemTag(item, harvestToolTag, Collections.emptySet(), Collections.emptySet())) {
                return true;
            }
            if (matchesCustomTag(item, harvestToolTag)) {
                return true;
            }
        }

        String vanillaItemId = ItemUtils.getVanillaMaterialItemId(item);
        return Constants.ITEM_SHEARS.equals(vanillaItemId);
    }

    private boolean isKnifeHarvestTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (matchesConfiguredToolItem(item, null)) {
            return true;
        }
        return matchesLegacyKnifeItem(item);
    }

    private boolean matchesConfiguredToolItem(ItemStack item, String specificItemId) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            if (specificItemId == null || specificItemId.equalsIgnoreCase(customId)) {
                if (harvestToolItems.contains(customId)) {
                    return true;
                }
            }
        }

        String vanillaItemId = ItemUtils.getVanillaMaterialItemId(item);
        if (vanillaItemId != null) {
            if (specificItemId == null || specificItemId.equalsIgnoreCase(vanillaItemId)) {
                if (harvestToolItems.contains(vanillaItemId)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean matchesCustomTag(ItemStack item, Key harvestToolTag) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId == null) {
            return false;
        }
        return FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                .itemIdsByTag(harvestToolTag)
                .stream()
                .anyMatch(uniqueKey -> uniqueKey.key().toString().equalsIgnoreCase(customId));
    }

    private boolean matchesLegacyKnifeItem(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        return FarmersDelightPlugin.getInstance().isKnifeItemId(customId);
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) {
            return;
        }

        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) {
            return;
        }

        int currentAge = getAge(state);
        if (currentAge >= maxAge) {
            return;
        }

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (growSoilRules.isConfigured() && !SoilRuleSupport.matches(block.getRelative(0, -1, 0), growSoilRules)) {
            return;
        }
        if (minGrowLight > 0 && block.getLightLevel() < minGrowLight) {
            return;
        }
        if (ThreadLocalRandom.current().nextFloat() >= growSpeed) {
            return;
        }

        ImmutableBlockState newState = state.with(ageProperty, currentAge + 1);
        CraftEngineBlocks.place(block.getLocation(), newState, false);
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        
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

    private static SoilRuleSupport.SoilRules parseGrowSoilRules(Map<String, Object> arguments) {
        if (BehaviorArgParser.hasArgument(arguments, "grow-on-blocks")
                || BehaviorArgParser.hasArgument(arguments, "grow-on-block-tags")) {
            Map<String, Object> aliasedArguments = new java.util.HashMap<>();
            if (arguments != null) {
                aliasedArguments.putAll(arguments);
            }
            aliasedArguments.put("bottom-blocks", aliasedArguments.get("grow-on-blocks"));
            aliasedArguments.put("bottom-block-tags", aliasedArguments.get("grow-on-block-tags"));
            return SoilRuleSupport.parseSoilRules(aliasedArguments);
        }
        return SoilRuleSupport.parseSoilRules(arguments);
    }
}

