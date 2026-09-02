package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.AbstractCanSurviveBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
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
import org.bukkit.inventory.meta.Damageable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MushroomColonyBehavior extends AbstractCanSurviveBlockBehavior implements BonemealableBlock, RandomTickBlock {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private record Config(
            Property<Integer> ageProperty,
            int maxAge,
            float growSpeed,
            int minGrowLight,
            int bonemealMinAgeBonus,
            int bonemealMaxAgeBonus,
            Set<Key> harvestToolTags,
            Set<String> harvestToolItems,
            SoilRuleSupport.SoilRules growSoilRules,
            SoilRuleSupport.SoilRules placementSoilRules,
            boolean placementOverridesDefault,
            String mushroomItemId
    ) {}

    private static final Map<Key, MushroomColonyBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Key MUSHROOM_GROW_BLOCK_TAG = Key.of("minecraft:mushroom_grow_block");
    private static final Set<String> DEFAULT_MUSHROOM_ALWAYS_VALID_SUPPORTS = Set.of(
            "minecraft:mycelium",
            "minecraft:podzol",
            "minecraft:crimson_nylium",
            "minecraft:warped_nylium",
            "minecraft:mushroom_stem"
    );
    private static volatile Set<String> cachedMushroomSupports = DEFAULT_MUSHROOM_ALWAYS_VALID_SUPPORTS;

    private final Config config;

    private MushroomColonyBehavior(BlockDefinition block, Config config) {
        super(block, 0);
        this.config = config;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    @Override
    protected boolean canSurvive(Object thisBlock, Object state, Object level, Object blockPos) {
        World world = CraftEngineAdapter.toWorld(level);
        BlockPos pos = CraftEngineAdapter.toBlockPos(blockPos);
        if (world == null || pos == null) {
            return false;
        }
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        return canSurviveAtWithRules(world, target, target.getRelative(BlockFace.DOWN));
    }

    public static boolean canSurviveAt(World world, Block target, Block blockBelow) {
        MushroomColonyBehavior behavior = target == null ? null
                : getBehavior(CustomBlockUtils.getState(target));
        return behavior == null
                ? canSurviveAtDefault(world, target, blockBelow)
                : behavior.canSurviveAtWithRules(world, target, blockBelow);
    }

    public static boolean canSurviveAt(String customBlockId, World world, Block target, Block blockBelow) {
        MushroomColonyBehavior behavior = null;
        if (customBlockId != null) {
            try {
                behavior = getBehavior(Key.of(customBlockId));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return behavior == null
                ? canSurviveAtDefault(world, target, blockBelow)
                : behavior.canSurviveAtWithRules(world, target, blockBelow);
    }

    private boolean canSurviveAtWithRules(World world, Block target, Block blockBelow) {
        if (config.placementSoilRules().isConfigured()
                && SoilRuleSupport.matches(blockBelow, config.placementSoilRules())) {
            return true;
        }
        if (config.placementOverridesDefault()) {
            return false;
        }
        return canSurviveAtDefault(world, target, blockBelow);
    }

    private static boolean canSurviveAtDefault(World world, Block target, Block blockBelow) {
        if (world == null || target == null || blockBelow == null) {
            return false;
        }
        if (isMushroomGrowBlock(blockBelow) || isAlwaysValidSupport(blockBelow)) {
            return true;
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && !plugin.getConfigBoolean(true,
                "mushroom-colonies.placement.allow-solid-supports-below-max-light")) {
            return false;
        }
        int maxLight = plugin == null ? 12 : Math.max(0, Math.min(15, plugin.getConfigInt(12,
                "mushroom-colonies.placement.max-light")));
        return target.getLightLevel() <= maxLight && blockBelow.isSolid();
    }

    private static boolean isMushroomGrowBlock(Block block) {
        if (Tag.MUSHROOM_GROW_BLOCK.isTagged(block.getType())) {
            return true;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        return state != null && !state.isEmpty() && state.settings().tags().contains(MUSHROOM_GROW_BLOCK_TAG);
    }

    private static boolean isAlwaysValidSupport(Block block) {
        String customId = CustomBlockUtils.getId(block);
        if (customId != null && cachedMushroomSupports.contains(customId.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return cachedMushroomSupports.contains("minecraft:" + block.getType().name().toLowerCase(Locale.ROOT));
    }

    public static void reloadMushroomSupportCache(FarmersDelightPlugin plugin) {
        if (plugin == null) {
            cachedMushroomSupports = DEFAULT_MUSHROOM_ALWAYS_VALID_SUPPORTS;
            return;
        }
        Set<String> normalized = new HashSet<>();
        for (String support : ConfigSectionReader.optionalStringList(
                plugin.getConfig(), "mushroom-colonies.placement.always-valid-supports")) {
            String value = support == null ? "" : support.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            normalized.add(value.contains(":") ? value : "minecraft:" + value);
        }
        cachedMushroomSupports = normalized.isEmpty()
                ? DEFAULT_MUSHROOM_ALWAYS_VALID_SUPPORTS
                : Set.copyOf(normalized);
    }

    public static final BlockBehaviorFactory<MushroomColonyBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        // The age is not optional: it carries how many mushrooms the colony holds, so without it the
        // colony reads as empty and can never be harvested, while growth and bone meal fail on every
        // write. A block that declares this behavior without an int age property aborts its own load
        // here, naming the property, instead of loading a colony that silently does nothing. The
        // property name stays configurable; an unresolvable name is an error without a default fallback.
        String path = section != null ? section.path() : Constants.BEHAVIOR_MUSHROOM_COLONY;
        String agePropertyName = BehaviorArgParser.getString(arguments, "age-property", "age");
        Property<Integer> ageProperty =
                BlockBehaviorFactory.getProperty(path, block, agePropertyName, Integer.class);

        int maxAge = BehaviorArgParser.hasArgument(arguments, "max-age")
                ? BehaviorArgParser.getInt(arguments, "max-age", 3)
                : BehaviorArgParser.inferMaxIntegerValue(ageProperty, 3);
        float growSpeed = BehaviorArgParser.getFloat(arguments, "grow-speed", 0.25f);
        int minGrowLight = BehaviorArgParser.getInt(arguments, "light-requirement", 0);
        int bonemealMinAgeBonus = BehaviorArgParser.getInt(arguments, "bonemeal-min-age-bonus", 1);
        int bonemealMaxAgeBonus = BehaviorArgParser.getInt(arguments, "bonemeal-max-age-bonus", 2);
        if (bonemealMaxAgeBonus < bonemealMinAgeBonus) {
            bonemealMaxAgeBonus = bonemealMinAgeBonus;
        }
        Set<Key> harvestToolTags = SoilRuleSupport.parseKeys(arguments, "harvest-tool-tags");
        Set<String> harvestToolItems = parseConfiguredItemIds(arguments);
        SoilRuleSupport.SoilRules growSoilRules = parseGrowSoilRules(arguments);
        SoilRuleSupport.SoilRules placementSoilRules = parsePlacementSoilRules(arguments);
        boolean placementOverridesDefault = BehaviorArgParser.getBoolean(arguments,
                "place-on-overrides-default", false);
        String mushroomItemId = BehaviorArgParser.getString(arguments, "mushroom-type", "");

        MushroomColonyBehavior behavior = new MushroomColonyBehavior(block, new Config(
                ageProperty, maxAge, growSpeed, minGrowLight,
                bonemealMinAgeBonus, bonemealMaxAgeBonus,
                harvestToolTags, harvestToolItems, growSoilRules, placementSoilRules,
                placementOverridesDefault, mushroomItemId
        ));
        BEHAVIORS.put(block.id(), behavior);
        return behavior;
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
        if (state == null || state.isEmpty()) {
            return 0;
        }
        Integer value = state.getNullable(config.ageProperty());
        return value != null ? value : 0;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }

        Player player = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (player == null) {
            return InteractionResult.PASS;
        }

        ItemStack heldItem = ItemUtils.getItemInHand(player, context.getHand());
        if (heldItem == null || heldItem.getType().isAir()) {
            return InteractionResult.PASS;
        }

        int currentAge = getAge(state);
        if (currentAge <= 0) {
            return InteractionResult.PASS;
        }

        boolean shearsHarvest = isShearsHarvestTool(heldItem);
        boolean knifeHarvest = !shearsHarvest && isKnifeHarvestTool(heldItem);
        if (!shearsHarvest && !knifeHarvest) {
            return InteractionResult.PASS;
        }

        ItemStack mushroomDrop = ItemUtils.createItem(config.mushroomItemId());
        if (mushroomDrop == null || mushroomDrop.getType().isAir()) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = player.getWorld();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.MUSHROOM_COLONY)
                || !ProtectionCompat.canBuild(player, block, ProtectionCompat.Feature.MUSHROOM_COLONY)) {
            return InteractionResult.PASS;
        }
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);

        if (shearsHarvest) {
            mushroomDrop.setAmount(1);
            ImmutableBlockState nextState = state.with(config.ageProperty(), Math.max(0, currentAge - 1));
            if (!CraftEngineBlocks.place(block.getLocation(), nextState, false)) {
                return InteractionResult.PASS;
            }
            world.playSound(loc, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);
            spawnHarvestParticles(world, loc, 3, 0.1, 0.001);
        } else {
            mushroomDrop.setAmount(currentAge);
            ImmutableBlockState resetState = state.with(config.ageProperty(), 0);
            if (!CraftEngineBlocks.place(block.getLocation(), resetState, false)) {
                return InteractionResult.PASS;
            }
            // Mirrors original MushroomColonyBlock: knife harvest plays the block's break sound
            // (colony copies vanilla mushroom = SoundType.GRASS) rather than a crop-growth sound.
            world.playSound(loc, Sound.BLOCK_GRASS_BREAK, 1.0f, 1.0f);
            spawnHarvestParticles(world, loc, 10, 0.2, 0.1);
        }

        // Fired after the protection check and after the block state has been stepped down, but before
        // the drop exists, so a listener sees the harvest exactly once with the final drop amount.
        // Outside any block-entity monitor: this behavior holds none.
        Bukkit.getPluginManager().callEvent(new FarmersDelightHarvestEvent(
                player, block.getLocation(), CustomBlockUtils.getId(state), heldItem,
                java.util.List.of(mushroomDrop)));

        world.dropItemNaturally(loc, mushroomDrop);
        ItemUtils.swingHand(player, context.getHand());
        damageHeldTool(player, heldItem);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private void spawnHarvestParticles(World world, Location loc, int count, double offset, double speed) {
        Material particleMaterial = config.mushroomItemId() != null && config.mushroomItemId().contains("red")
                ? Material.RED_MUSHROOM_BLOCK : Material.BROWN_MUSHROOM_BLOCK;
        world.spawnParticle(Particle.BLOCK, loc, count, offset, offset, offset, speed, particleMaterial.createBlockData());
    }

    private void damageHeldTool(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (com.huidu.farmersdelight.tool.ToolAttackListener.resolveToolData(item) != null) {
            com.huidu.farmersdelight.tool.ToolAttackListener.consumeDurability(item, player.getLocation());
            return;
        }
        if (!(item.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) {
            return;
        }
        // Use the item's effective max damage (custom items carry a custom max_damage component),
        // and actually consume the tool when reached instead of letting damage grow past durability.
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

        for (Key harvestToolTag : config.harvestToolTags()) {
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
                if (config.harvestToolItems().contains(customId)) {
                    return true;
                }
            }
        }

        String vanillaItemId = ItemUtils.getVanillaMaterialItemId(item);
        if (vanillaItemId != null) {
            if (specificItemId == null || specificItemId.equalsIgnoreCase(vanillaItemId)) {
                return config.harvestToolItems().contains(vanillaItemId);
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
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        if (args.length < 3) return false;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
        if (state == null || state.isEmpty()) return false;
        return getAge(state) < config.maxAge();
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        if (args.length >= 3) {
            World world = CraftEngineAdapter.toWorld(args[0]);
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
            if (world != null && pos != null) {
                world.spawnParticle(Particle.HAPPY_VILLAGER,
                        pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5,
                        15, 0.25, 0.25, 0.25);
            }
        }
        return true;
    }

    @Override
    public void performBonemeal(Object thisBlock, Object[] args) {
        if (args.length < 4) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[3]).orElse(null);
        if (state == null || state.isEmpty()) return;

        int currentAge = getAge(state);
        if (currentAge >= config.maxAge()) return;

        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        int increase = ThreadLocalRandom.current().nextInt(config.bonemealMinAgeBonus(), config.bonemealMaxAgeBonus() + 1);
        int newAge = Math.min(config.maxAge(), currentAge + increase);
        ImmutableBlockState newState = state.with(config.ageProperty(), newAge);
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        CraftEngineBlocks.place(block.getLocation(), newState, false);
    }

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        return getAge(state) < config.maxAge();
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
        if (currentAge >= config.maxAge()) {
            return;
        }

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (config.growSoilRules().isConfigured() && !SoilRuleSupport.matches(block.getRelative(0, -1, 0), config.growSoilRules())) {
            return;
        }
        if (config.minGrowLight() > 0 && block.getLightLevel() < config.minGrowLight()) {
            return;
        }
        if (ThreadLocalRandom.current().nextFloat() >= config.growSpeed()) {
            return;
        }

        ImmutableBlockState newState = state.with(config.ageProperty(), currentAge + 1);
        CraftEngineBlocks.place(block.getLocation(), newState, false);
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        
    }

    private static Set<String> parseConfiguredItemIds(Map<String, Object> arguments) {
        Object raw = arguments != null ? arguments.get("harvest-tool-items") : null;
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

    private static SoilRuleSupport.SoilRules parsePlacementSoilRules(Map<String, Object> arguments) {
        if (!BehaviorArgParser.hasArgument(arguments, "place-on-blocks")
                && !BehaviorArgParser.hasArgument(arguments, "place-on-block-tags")) {
            return new SoilRuleSupport.SoilRules(Set.of(), Set.of(), Set.of(), List.of(), Set.of());
        }
        Map<String, Object> aliasedArguments = new java.util.HashMap<>();
        if (arguments != null) {
            aliasedArguments.putAll(arguments);
        }
        aliasedArguments.put("bottom-blocks", BehaviorArgParser.getRaw(arguments, "place-on-blocks"));
        aliasedArguments.put("bottom-block-tags", BehaviorArgParser.getRaw(arguments, "place-on-block-tags"));
        return SoilRuleSupport.parseSoilRules(aliasedArguments);
    }
}
