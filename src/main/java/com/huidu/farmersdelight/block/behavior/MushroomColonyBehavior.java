package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.ItemUtils;
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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MushroomColonyBehavior extends BlockBehavior {
    private static final Map<Key, MushroomColonyBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    private final Property<Integer> ageProperty;
    private final int maxAge;
    private final float growSpeed;
    private final int minGrowLight;

    private MushroomColonyBehavior(
            CustomBlock block,
            Property<Integer> ageProperty,
            int maxAge,
            float growSpeed,
            int minGrowLight
    ) {
        super(block);
        this.ageProperty = ageProperty;
        this.maxAge = maxAge;
        this.growSpeed = growSpeed;
        this.minGrowLight = minGrowLight;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<MushroomColonyBehavior> FACTORY = new BlockBehaviorFactory<MushroomColonyBehavior>() {
        @Override
        public MushroomColonyBehavior create(CustomBlock block, Map<String, Object> arguments) {
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

            MushroomColonyBehavior behavior = new MushroomColonyBehavior(
                    block, ageProperty, maxAge, growSpeed, minGrowLight
            );
            BEHAVIORS.put(block.id(), behavior);
            return behavior;
        }
    };

    public static MushroomColonyBehavior getBehavior(Key blockId) {
        if (blockId == null) return null;
        return BEHAVIORS.get(blockId);
    }

    public static MushroomColonyBehavior getBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return null;
        return getBehavior(state.owner().value().id());
    }

    public int getAge(ImmutableBlockState state) {
        if (ageProperty == null || state == null || state.isEmpty()) return 0;
        Integer value = state.get(ageProperty);
        return value != null ? value : 0;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = bukkitPlayer.getWorld();

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return InteractionResult.PASS;
        if (!isShearsOrKnife(mainHand)) return InteractionResult.PASS;

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

        world.playSound(loc, Sound.BLOCK_GROWING_PLANT_CROP, 1.0f, 1.0f);
        bukkitPlayer.swingMainHand();

        ImmutableBlockState resetState = state.with(ageProperty, 0);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), resetState, false);

        if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
            if (mainHand.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                damageable.setDamage(damageable.getDamage() + 1);
                mainHand.setItemMeta(damageable);
            }
        }

        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private boolean isShearsOrKnife(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (item.getType() == Material.SHEARS) return true;

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            List<String> knives = FarmersDelightPlugin.getInstance()
                    .getConfig()
                    .getStringList("knife-config.items");
            if (knives.stream().anyMatch(knife -> knife.equalsIgnoreCase(customId))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        if (args.length < 3) return;

        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;

        int currentAge = getAge(state);
        if (currentAge >= maxAge) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Block bukkitBlock = world.getBlockAt(pos.x(), pos.y(), pos.z());

        if (minGrowLight > 0 && bukkitBlock.getLightLevel() < minGrowLight) return;

        if (ThreadLocalRandom.current().nextFloat() >= growSpeed) return;

        ImmutableBlockState newState = state.with(ageProperty, currentAge + 1);
        CraftEngineBlocks.place(bukkitBlock.getLocation(), newState, false);
    }

    @Override
    public void onRemove(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        superMethod.call();
    }
}
