package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BushBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class TomatoVineBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock, BonemealableBlock {

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        return true;
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        // Always advertise as a valid target so vanilla doesn't reject the bonemeal use early — the
        // sibling crop_block behavior controls the real "is at max age" gate, and our climb attempt
        // has its own guards inside tryClimb (rope present, column height, light).
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        // Lets the dispatch reach performBonemeal regardless of tryClimb's eventual outcome — if no
        // rope is above, tryClimb is a no-op and the crop_block sibling still bumps the age. Returning
        // false here would only matter for vanilla particles, which CE drives off this method.
        return true;
    }

    @Override
    public void performBonemeal(Object thisBlock, Object[] args) {
        // args[0] = level, args[1] = random, args[2] = pos, args[3] = state — per CE's BonemealableBlock
        // bridge contract (mirrors NMS BonemealableBlock.performBonemeal(level, random, pos, state)).
        //
        // 3-block dispatch mirroring original FD 1.21:
        //   - budding (sibling crop_block has bone-meal-age-bonus=0, so its performBonemeal is a no-op):
        //     owns the age math directly. bonusAge = bonemealBonusMin..Max; overflow past maxAge=3
        //     transitions to ground tomatoes at age (newAge - 4), clamped to [0, 3] — mirrors original
        //     BuddingTomatoBlock.performBonemeal.
        //   - hanging (cropOnRope): sibling crop_block already advanced age. 30% chance to climb,
        //     matching original TomatoBlock.performBonemeal "normal increment" branch (HangingTomato
        //     inherits TomatoBlock).
        //   - ground tomatoes age < 3: same 30% climb chance after sibling advance.
        //   - ground tomatoes age == 3 (= original TomatoBlock vine_age 3 / max): original
        //     "newAge > maxAge" branch — forward +1 age to a non-max hanging directly above if one
        //     exists, else attempt climb.
        if (args == null || args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return;
        Key currentBlockId = atState.owner().value().id();

        if (currentBlockId.equals(config.buddingBlockId())) {
            performBuddingBonemeal(world, pos, atState);
            return;
        }

        if (currentBlockId.equals(config.cropOnRopeBlockId())) {
            if (ThreadLocalRandom.current().nextFloat() < config.bonemealClimbChance()) {
                tryClimb(world, pos);
            }
            return;
        }

        if (!currentBlockId.equals(config.tomatoesBlockId())) return;

        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) atState.owner().value().getProperty(AGE_PROPERTY);
        if (ageProp == null) return;
        Integer currentAge = atState.get(ageProp);
        if (currentAge == null) return;

        if (currentAge < config.tomatoesMaxAge()) {
            if (ThreadLocalRandom.current().nextFloat() < config.bonemealClimbChance()) {
                tryClimb(world, pos);
            }
            return;
        }

        Block aboveBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        ImmutableBlockState aboveState = CraftEngineBlocks.getCustomBlockState(aboveBlock);
        if (aboveState != null && !aboveState.isEmpty()
                && aboveState.owner().value().id().equals(config.cropOnRopeBlockId())) {
            @SuppressWarnings("unchecked")
            Property<Integer> aboveAgeProp = (Property<Integer>) aboveState.owner().value().getProperty(AGE_PROPERTY);
            if (aboveAgeProp != null) {
                Integer aboveAge = aboveState.get(aboveAgeProp);
                if (aboveAge != null && aboveAge < config.hangingMaxAge()) {
                    ImmutableBlockState newAboveState = aboveState.with(aboveAgeProp, aboveAge + 1);
                    Location aboveLoc = new Location(world,
                            aboveBlock.getX() + 0.5, aboveBlock.getY(), aboveBlock.getZ() + 0.5);
                    CraftEngineBlocks.place(aboveLoc, newAboveState, false);
                    return;
                }
            }
        }
        tryClimb(world, pos);
    }

    private void performBuddingBonemeal(World world, BlockPos pos, ImmutableBlockState state) {
        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty(AGE_PROPERTY);
        if (ageProp == null) return;
        Integer currentAge = state.get(ageProp);
        if (currentAge == null) return;
        int bonusRange = Math.max(1, config.bonemealBonusMax() - config.bonemealBonusMin() + 1);
        int bonusAge = config.bonemealBonusMin() + ThreadLocalRandom.current().nextInt(bonusRange);
        int newAge = currentAge + bonusAge;
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        if (newAge <= config.buddingMaxAge()) {
            ImmutableBlockState updated = state.with(ageProp, newAge);
            CraftEngineBlocks.place(loc, updated, false);
            return;
        }
        BlockDefinition tomatoes = CraftEngineBlocks.byId(config.tomatoesBlockId());
        if (tomatoes == null) return;
        int tomatoesAgeValue = Math.min(config.tomatoesMaxAge(), Math.max(0, newAge - (config.buddingMaxAge() + 1)));
        ImmutableBlockState newState = tomatoes.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> tomatoesAgeProp = (Property<Integer>) tomatoes.getProperty(AGE_PROPERTY);
        if (tomatoesAgeProp != null) {
            newState = newState.with(tomatoesAgeProp, tomatoesAgeValue);
        }
        CraftEngineBlocks.place(loc, newState, true);
    }

    public static final String AGE_PROPERTY = "age";

    private static final Map<Key, TomatoVineBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    private record Config(
            Key buddingBlockId,
            Key tomatoesBlockId,
            Key cropOnRopeBlockId,
            Key ropeBlockId,
            int matureAge,
            int minLight,
            int maxStackHeight,
            int buddingMaxAge,
            int tomatoesMaxAge,
            int hangingMaxAge,
            float bonemealClimbChance,
            int bonemealBonusMin,
            int bonemealBonusMax
    ) {}

    private final Config config;
    // Lazily resolved from the hanging block's sibling bush_block.max-height so the climb cap and the
    // survival cap share ONE authority (the CE bush_block config). -1 = not yet resolved. Recomputed
    // per behavior instance, and instances are rebuilt on /ce reload, so config edits take effect
    // without a stale cache. A benign race just recomputes the same value; volatile guards visibility.
    private volatile int resolvedMaxStackHeight = -1;

    private TomatoVineBlockBehavior(BlockDefinition block, Config config) {
        super(block);
        this.config = config;
    }

    public static final BlockBehaviorFactory<TomatoVineBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        // Every stage of the vine is driven by the age of the block the behavior sits on: budding
        // transitions at max age, the ground and hanging blocks gate climbing and harvesting on it.
        // A block that declares this behavior without an int age property aborts its own load here,
        // naming the property, instead of loading a vine that never grows, never climbs and never
        // reports itself harvest-ready. Validation only: the behavior is shared by three block ids
        // and reads whichever block actually occupies a position, so it resolves the age property
        // per state at runtime rather than holding this one handle.
        String path = section != null ? section.path() : Constants.BEHAVIOR_TOMATO_VINE;
        BlockBehaviorFactory.getProperty(path, block, AGE_PROPERTY, Integer.class);
        // Reads both the nested sections (blocks / max-age / bonemeal) and the flat keys they
        // group, with the nested spelling winning where an author wrote both. Anything worth
        // telling the operator about comes back as a warning list rather than being logged from
        // the resolution itself, and none of it stops the block from loading. See
        // TomatoVineSettings for the accepted shapes and the resolution rule.
        // max-stack-height is a fallback only: the live climb cap is derived from the hanging
        // block's bush_block max-height at runtime (see effectiveMaxStackHeight), and this value
        // is used solely when the hanging block has no bush_block behavior. Default 3 matches
        // original FD 1.21 (TomatoBlock.climbRopeAbove uses `vineHeight < 3` = 3 hangings +
        // 1 ground = 4 total).
        TomatoVineSettings settings = TomatoVineSettings.parse(arguments, block.id().toString());
        for (TomatoVineSettings.Warning warning : settings.warnings()) {
            I18n.logWarning(warning.key(), warning.arguments());
        }
        TomatoVineBlockBehavior behavior = new TomatoVineBlockBehavior(block, new Config(
                Key.of(settings.buddingBlock()), Key.of(settings.tomatoesBlock()),
                Key.of(settings.cropOnRopeBlock()), Key.of(settings.ropeBlock()),
                settings.matureAge(), settings.minLight(), settings.maxStackHeight(),
                settings.buddingMaxAge(), settings.tomatoesMaxAge(), settings.hangingMaxAge(),
                settings.bonemealClimbChance(), settings.bonemealBonusMin(), settings.bonemealBonusMax()
        ));
        BEHAVIORS.put(block.id(), behavior);
        return behavior;
    };

    public static TomatoVineBlockBehavior getBehavior(Key blockId) {
        return blockId == null ? null : BEHAVIORS.get(blockId);
    }

    public static void warmAll() {
        for (TomatoVineBlockBehavior behavior : BEHAVIORS.values()) {
            behavior.effectiveMaxStackHeight();
        }
    }

    public boolean isHarvestReady(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return false;
        Key id = state.owner().value().id();
        int max;
        if (id.equals(config.tomatoesBlockId())) {
            max = config.tomatoesMaxAge();
        } else if (id.equals(config.cropOnRopeBlockId())) {
            max = config.hangingMaxAge();
        } else {
            return false;
        }
        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty(AGE_PROPERTY);
        if (ageProp == null) return false;
        Integer age = state.get(ageProp);
        return age != null && age >= max;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return;
        Key currentBlockId = atState.owner().value().id();
        if (currentBlockId.equals(config.buddingBlockId())) {
            tryGrowPastMaxAge(world, pos, atState, atPos);
        } else {
            tryClimb(world, pos);
        }
    }

    private void tryGrowPastMaxAge(World world, BlockPos pos, ImmutableBlockState state, Block atPos) {
        if (atPos.getLightLevel() < config.minLight()) return;
        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty(AGE_PROPERTY);
        if (ageProp == null) return;
        Integer age = state.get(ageProp);
        if (age == null || age < config.buddingMaxAge()) return;
        BlockDefinition tomatoes = CraftEngineBlocks.byId(config.tomatoesBlockId());
        if (tomatoes == null) return;
        ImmutableBlockState newState = tomatoes.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> tomatoesAge = (Property<Integer>) tomatoes.getProperty(AGE_PROPERTY);
        if (tomatoesAge != null) {
            newState = newState.with(tomatoesAge, 0);
        }
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        CraftEngineBlocks.place(loc, newState, true);
    }

    public boolean tryClimb(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return false;
        Key currentBlockId = atState.owner().value().id();
        if (config.matureAge() > 0) {
            @SuppressWarnings("unchecked")
            Property<Integer> ageProperty = (Property<Integer>) atState.owner().value().getProperty(AGE_PROPERTY);
            if (ageProperty != null) {
                Integer currentAge = atState.get(ageProperty);
                if (currentAge != null && currentAge < config.matureAge()) return false;
            }
        }
        if (atPos.getLightLevel() < config.minLight()) return false;

        int height = countSameIdHeightBelow(world, pos, currentBlockId);
        if (height >= effectiveMaxStackHeight()) return false;

        Block aboveTop = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!CustomBlockUtils.hasBehavior(aboveTop, RopeBlockBehavior.class)) return false;

        BlockDefinition cropOnRope = CraftEngineBlocks.byId(config.cropOnRopeBlockId());
        if (cropOnRope == null) return false;
        ImmutableBlockState newState = cropOnRope.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> targetAge = (Property<Integer>) cropOnRope.getProperty(AGE_PROPERTY);
        if (targetAge != null) {
            newState = newState.with(targetAge, 0);
        }

        Location placeLoc = new Location(world,
                aboveTop.getX() + 0.5, aboveTop.getY(), aboveTop.getZ() + 0.5);
        boolean placed = CraftEngineBlocks.place(placeLoc, newState, true);
        if (placed) {
            RopeBlockBehavior.refreshAdjacentRopes(world,
                    new BlockPos(aboveTop.getX(), aboveTop.getY(), aboveTop.getZ()));
        }
        return placed;
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        Key removedId = state.owner().value().id();
        if (!removedId.equals(config.cropOnRopeBlockId())) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        BlockDefinition ropeBlock = CraftEngineBlocks.byId(config.ropeBlockId());
        if (ropeBlock == null) return;

        Location placeLoc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        BlockPos finalPos = pos;
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Runnable placeRope = () -> {
            ImmutableBlockState connected = RopeBlockBehavior.computeConnectionState(
                    ropeBlock.defaultState(), world, finalPos);
            CraftEngineBlocks.place(placeLoc, connected, true);
            RopeBlockBehavior.refreshAdjacentRopes(world, finalPos);
        };
        if (plugin != null) {
            plugin.scheduler().runAt(placeLoc, placeRope);
        } else {
            placeRope.run();
        }
    }

    /*
     * Effective climb cap = the hanging block's own bush_block max-height. Reading it from the
     * sibling CE behavior means the range is configured in ONE place (the bush_block YAML) and the
     * climb pre-check can never drift from the survival check — no more "grow a doomed block, then
     * bush_block removes it next tick" flicker. Falls back to the max-stack-height behavior arg if
     * the hanging block has no bush_block (e.g. someone stripped it from the config).
     * The counting in countSameIdHeightBelow is provably equivalent to CE BushBlockBehavior.mayStackOn
     * at the same cap value (for cap >= 2): both allow at most `cap` hanging cells on the column.
     */
    private int effectiveMaxStackHeight() {
        int cached = resolvedMaxStackHeight;
        if (cached > 0) return cached;
        int resolved = config.maxStackHeight();
        BlockDefinition cropOnRope = CraftEngineBlocks.byId(config.cropOnRopeBlockId());
        if (cropOnRope != null) {
            BushBlockBehavior bush = CustomBlockUtils.getBehavior(cropOnRope.defaultState(), BushBlockBehavior.class);
            if (bush != null && bush.maxHeight > 0) {
                resolved = bush.maxHeight;
            }
        }
        resolvedMaxStackHeight = resolved;
        return resolved;
    }

    private int countSameIdHeightBelow(World world, BlockPos pos, Key currentBlockId) {
        int count = 1;
        int y = pos.y() - 1;
        while (y >= world.getMinHeight()) {
            ImmutableBlockState bs = CraftEngineBlocks.getCustomBlockState(world.getBlockAt(pos.x(), y, pos.z()));
            if (bs == null || bs.isEmpty()) break;
            if (!bs.owner().value().id().equals(currentBlockId)) break;
            count++;
            y--;
        }
        return count;
    }

}
