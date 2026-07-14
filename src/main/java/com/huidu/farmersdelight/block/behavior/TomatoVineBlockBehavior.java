package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CraftEngineAdapter;
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

/**
 * Three-block tomato vine controller mirroring original FD 1.21 structure:
 * <ul>
 *   <li>farmersdelight:budding_tomatoes (age 0-3) — BuddingTomatoBlock equivalent. No rope
 *       climbing. random tick at age 3 + light &ge; minLight → swap to ground tomatoes age 0
 *       (tryGrowPastMaxAge). The sibling crop_block has bone-meal-age-bonus: 0, so its
 *       own performBonemeal is a no-op (after == before) and bonemeal age growth is owned entirely by
 *       this behavior, replicating original BuddingTomatoBlock.performBonemeal overflow rules. Note
 *       is-bone-meal-target: false alone is NOT enough: CE crop_block.performBonemeal ignores
 *       that flag and always adds its bonus, so the bonus must be zeroed to avoid a double application.</li>
 *   <li>farmersdelight:tomatoes (age 0-3) — ground TomatoBlock equivalent. Any age attempts
 *       rope climb on random tick / 30% on bonemeal. Right-click at age 3 harvests + resets age 0.</li>
 *   <li>farmersdelight:tomato_crop_on_rope (age 0-3) — HangingTomatoBlock equivalent. Any
 *       age can extend the chain. Removed cell restores a connected rope via
 *       affectNeighborsAfterRemoval.</li>
 * </ul>
 *
 * Climb guards mirror original TomatoBlock.climbRopeAbove: brightness >= minLight, same-id column
 * below + self < the effective stack cap, and the cell directly above must carry a
 * RopeBlockBehavior. The cap is read from the hanging block's sibling bush_block max-height (see
 * effectiveMaxStackHeight) so the climb pre-check and CE's survival check share ONE config value
 * and can never drift (no grow-then-decay flicker). Default 3 = original 1.21's 1 ground + 3
 * hangings = 4 total.
 *
 * Bonemeal dispatch (4 paths) tracks the original closely:
 * <ul>
 *   <li>budding: 2 + rand(3) bonus age; overflow past 3 transitions to ground tomatoes at
 *       clamp(newAge - 4, 0, 3) (original BuddingTomato overflow).</li>
 *   <li>hanging: 30% chance to climb (sibling crop_block already advanced age).</li>
 *   <li>ground age &lt; 3: 30% chance to climb.</li>
 *   <li>ground age == 3: original "newAge &gt; maxAge" branch — forward +1 age to a non-max hanging
 *       directly above if one exists, else attempt climb.</li>
 * </ul>
 */
public class TomatoVineBlockBehavior extends BlockBehavior implements RandomTickBlock, BonemealableBlock {

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

        if (currentBlockId.equals(buddingBlockId)) {
            performBuddingBonemeal(world, pos, atState);
            return;
        }

        if (currentBlockId.equals(cropOnRopeBlockId)) {
            if (ThreadLocalRandom.current().nextFloat() < bonemealClimbChance) {
                tryClimb(world, pos);
            }
            return;
        }

        if (!currentBlockId.equals(tomatoesBlockId)) return;

        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) atState.owner().value().getProperty("age");
        if (ageProp == null) return;
        Integer currentAge = atState.get(ageProp);
        if (currentAge == null) return;

        if (currentAge < tomatoesMaxAge) {
            if (ThreadLocalRandom.current().nextFloat() < bonemealClimbChance) {
                tryClimb(world, pos);
            }
            return;
        }

        Block aboveBlock = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        ImmutableBlockState aboveState = CraftEngineBlocks.getCustomBlockState(aboveBlock);
        if (aboveState != null && !aboveState.isEmpty()
                && aboveState.owner().value().id().equals(cropOnRopeBlockId)) {
            @SuppressWarnings("unchecked")
            Property<Integer> aboveAgeProp = (Property<Integer>) aboveState.owner().value().getProperty("age");
            if (aboveAgeProp != null) {
                Integer aboveAge = aboveState.get(aboveAgeProp);
                if (aboveAge != null && aboveAge < hangingMaxAge) {
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
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty("age");
        if (ageProp == null) return;
        Integer currentAge = state.get(ageProp);
        if (currentAge == null) return;
        int bonusRange = Math.max(1, bonemealBonusMax - bonemealBonusMin + 1);
        int bonusAge = bonemealBonusMin + ThreadLocalRandom.current().nextInt(bonusRange);
        int newAge = currentAge + bonusAge;
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        if (newAge <= buddingMaxAge) {
            ImmutableBlockState updated = state.with(ageProp, newAge);
            CraftEngineBlocks.place(loc, updated, false);
            return;
        }
        BlockDefinition tomatoes = CraftEngineBlocks.byId(tomatoesBlockId);
        if (tomatoes == null) return;
        int tomatoesAgeValue = Math.min(tomatoesMaxAge, Math.max(0, newAge - (buddingMaxAge + 1)));
        ImmutableBlockState newState = tomatoes.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> tomatoesAgeProp = (Property<Integer>) tomatoes.getProperty("age");
        if (tomatoesAgeProp != null) {
            newState = newState.with(tomatoesAgeProp, tomatoesAgeValue);
        }
        CraftEngineBlocks.place(loc, newState, true);
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }

    /** Index for external lookup (bonemeal listener) by block id. */
    private static final Map<Key, TomatoVineBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    private final Key buddingBlockId;
    private final Key tomatoesBlockId;
    private final Key cropOnRopeBlockId;
    private final Key ropeBlockId;
    private final int matureAge;
    private final int minLight;
    private final int maxStackHeight;
    private final int buddingMaxAge;
    private final int tomatoesMaxAge;
    private final int hangingMaxAge;
    private final float bonemealClimbChance;
    private final int bonemealBonusMin;
    private final int bonemealBonusMax;
    // Lazily resolved from the hanging block's sibling bush_block.max-height so the climb cap and the
    // survival cap share ONE authority (the CE bush_block config). -1 = not yet resolved. Recomputed
    // per behavior instance, and instances are rebuilt on /ce reload, so config edits take effect
    // without a stale cache. A benign race just recomputes the same value; volatile guards visibility.
    private volatile int resolvedMaxStackHeight = -1;

    private TomatoVineBlockBehavior(BlockDefinition block, Key buddingBlockId, Key tomatoesBlockId,
                                     Key cropOnRopeBlockId, Key ropeBlockId,
                                     int matureAge, int minLight, int maxStackHeight,
                                     int buddingMaxAge, int tomatoesMaxAge, int hangingMaxAge,
                                     float bonemealClimbChance,
                                     int bonemealBonusMin, int bonemealBonusMax) {
        super(block);
        this.buddingBlockId = buddingBlockId;
        this.tomatoesBlockId = tomatoesBlockId;
        this.cropOnRopeBlockId = cropOnRopeBlockId;
        this.ropeBlockId = ropeBlockId;
        this.matureAge = matureAge;
        this.minLight = minLight;
        this.maxStackHeight = maxStackHeight;
        this.buddingMaxAge = buddingMaxAge;
        this.tomatoesMaxAge = tomatoesMaxAge;
        this.hangingMaxAge = hangingMaxAge;
        this.bonemealClimbChance = bonemealClimbChance;
        this.bonemealBonusMin = bonemealBonusMin;
        this.bonemealBonusMax = bonemealBonusMax;
    }

    public static final BlockBehaviorFactory<TomatoVineBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public TomatoVineBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String buddingId = BehaviorArgParser.getStringStrict(arguments, "budding-block", "farmersdelight:budding_tomatoes");
            String tomatoesId = BehaviorArgParser.getStringStrict(arguments, "tomatoes-block", "farmersdelight:tomatoes");
            String cropOnRopeId = BehaviorArgParser.getStringStrict(arguments, "crop-on-rope-block", "farmersdelight:tomato_crop_on_rope");
            String ropeId = BehaviorArgParser.getStringStrict(arguments, "rope-block", "farmersdelight:rope");
            int matureAge = BehaviorArgParser.getInt(arguments, "mature-age", 0);
            int minLight = BehaviorArgParser.getInt(arguments, "min-light", 9);
            // Fallback only: the live climb cap is derived from the hanging block's bush_block
            // max-height at runtime (see effectiveMaxStackHeight). This arg is used solely when the
            // hanging block has no bush_block behavior. Default 3 matches original FD 1.21
            // (TomatoBlock.climbRopeAbove uses `vineHeight < 3` = 3 hangings + 1 ground = 4 total).
            int maxStackHeight = BehaviorArgParser.getInt(arguments, "max-stack-height", 3);
            int buddingMaxAge = BehaviorArgParser.getInt(arguments, "budding-max-age", 3);
            int tomatoesMaxAge = BehaviorArgParser.getInt(arguments, "tomatoes-max-age", 3);
            int hangingMaxAge = BehaviorArgParser.getInt(arguments, "hanging-max-age", 3);
            float bonemealClimbChance = BehaviorArgParser.getFloat(arguments, "bonemeal-climb-chance", 0.3F);
            int bonemealBonusMin = BehaviorArgParser.getInt(arguments, "bonemeal-bonus-min", 1);
            int bonemealBonusMax = BehaviorArgParser.getInt(arguments, "bonemeal-bonus-max", 4);
            TomatoVineBlockBehavior behavior = new TomatoVineBlockBehavior(
                    block, Key.of(buddingId), Key.of(tomatoesId), Key.of(cropOnRopeId), Key.of(ropeId),
                    matureAge, minLight, maxStackHeight,
                    buddingMaxAge, tomatoesMaxAge, hangingMaxAge,
                    bonemealClimbChance, bonemealBonusMin, bonemealBonusMax);
            BEHAVIORS.put(block.id(), behavior);
            return behavior;
        }
    };

    /** Lookup by block id (e.g. farmersdelight:tomatoes). Null if no behavior registered for that id. */
    public static TomatoVineBlockBehavior getBehavior(Key blockId) {
        return blockId == null ? null : BEHAVIORS.get(blockId);
    }

    /** Pre-resolves every vine behavior's sibling-derived max stack height so the first climb tick does not
     *  pay the CraftEngineBlocks.byId + getBehavior sibling read. Pure registry/field reads. */
    public static void warmAll() {
        for (TomatoVineBlockBehavior behavior : BEHAVIORS.values()) {
            behavior.effectiveMaxStackHeight();
        }
    }

    /**
     * True when a right-click on this state would run the YAML on: right_click harvest — i.e.
     * a ground tomatoes or hanging tomato_crop_on_rope block at its max age. Budding
     * tomatoes have no right-click harvest, so they never qualify. Used by the WorldGuard protection
     * listener to cancel only the harvest interaction and leave eating/placing/bonemeal untouched.
     */
    public boolean isHarvestReady(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return false;
        Key id = state.owner().value().id();
        int max;
        if (id.equals(tomatoesBlockId)) {
            max = tomatoesMaxAge;
        } else if (id.equals(cropOnRopeBlockId)) {
            max = hangingMaxAge;
        } else {
            return false;
        }
        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty("age");
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
        if (currentBlockId.equals(buddingBlockId)) {
            tryGrowPastMaxAge(world, pos, atState, atPos);
        } else {
            tryClimb(world, pos);
        }
    }

    /** Mirrors original FD 1.21 BuddingTomatoBlock.growPastMaxAge: when budding reaches
     *  its max age (3) under enough light, swap to ground tomatoes at age 0. */
    private void tryGrowPastMaxAge(World world, BlockPos pos, ImmutableBlockState state, Block atPos) {
        if (atPos.getLightLevel() < minLight) return;
        @SuppressWarnings("unchecked")
        Property<Integer> ageProp = (Property<Integer>) state.owner().value().getProperty("age");
        if (ageProp == null) return;
        Integer age = state.get(ageProp);
        if (age == null || age < buddingMaxAge) return;
        BlockDefinition tomatoes = CraftEngineBlocks.byId(tomatoesBlockId);
        if (tomatoes == null) return;
        ImmutableBlockState newState = tomatoes.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> tomatoesAge = (Property<Integer>) tomatoes.getProperty("age");
        if (tomatoesAge != null) {
            newState = newState.with(tomatoesAge, 0);
        }
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        CraftEngineBlocks.place(loc, newState, true);
    }

    /**
     * Attempt to extend the vine one cell upward at the given position. Returns true when a rope was
     * consumed and replaced with a fresh hanging tomato.
     *
     * Guards mirror original FD 1.21 TomatoBlock.climbRopeAbove: brightness >= minLight, the cell
     * directly above must already carry a RopeBlockBehavior, and the column count of *same-id* blocks
     * including-self below this cell must be < maxStackHeight (3). same-id check: the count only
     * walks downward and only sees the current block's own id, matching the original
     * level.getBlockState(pos.below(...)).is(this) loop.
     */
    public boolean tryClimb(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return false;
        Key currentBlockId = atState.owner().value().id();
        if (matureAge > 0) {
            @SuppressWarnings("unchecked")
            Property<Integer> ageProperty = (Property<Integer>) atState.owner().value().getProperty("age");
            if (ageProperty != null) {
                Integer currentAge = atState.get(ageProperty);
                if (currentAge != null && currentAge < matureAge) return false;
            }
        }
        if (atPos.getLightLevel() < minLight) return false;

        int height = countSameIdHeightBelow(world, pos, currentBlockId);
        if (height >= effectiveMaxStackHeight()) return false;

        Block aboveTop = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!CustomBlockUtils.hasBehavior(aboveTop, RopeBlockBehavior.class)) return false;

        BlockDefinition cropOnRope = CraftEngineBlocks.byId(cropOnRopeBlockId);
        if (cropOnRope == null) return false;
        ImmutableBlockState newState = cropOnRope.defaultState();
        @SuppressWarnings("unchecked")
        Property<Integer> targetAge = (Property<Integer>) cropOnRope.getProperty("age");
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

    /**
     * Restore a rope (with computed N/S/E/W connections) at the removed cell's position whenever a
     * hanging tomato is removed via any path (player mine, cascade canSurvive fail, explosion,
     * piston). Skipped for the ground tomato — only the hanging variant occupied a rope to restore.
     */
    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        Key removedId = state.owner().value().id();
        if (!removedId.equals(cropOnRopeBlockId)) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        BlockDefinition ropeBlock = CraftEngineBlocks.byId(ropeBlockId);
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
        int resolved = maxStackHeight;
        BlockDefinition cropOnRope = CraftEngineBlocks.byId(cropOnRopeBlockId);
        if (cropOnRope != null) {
            BushBlockBehavior bush = CustomBlockUtils.getBehavior(cropOnRope.defaultState(), BushBlockBehavior.class);
            if (bush != null && bush.maxHeight > 0) {
                resolved = bush.maxHeight;
            }
        }
        resolvedMaxStackHeight = resolved;
        return resolved;
    }

    /** Original TomatoBlock.climbRopeAbove pattern: walk strictly downward, counting blocks
     *  that share the *current* block's id (not "any vine block"). Includes self. The hanging
     *  variant therefore only counts the chain of hangings below it, stopping at the ground tomato —
     *  so a ground tomato + N hangings on a rope is allowed (N = effectiveMaxStackHeight). */
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
