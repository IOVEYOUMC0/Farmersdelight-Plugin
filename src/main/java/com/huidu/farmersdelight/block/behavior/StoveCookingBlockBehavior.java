package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CookingDebugLog;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.PermissionChecker;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class StoveCookingBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    public static final int SLOT_COUNT = 6;
    public static final String FIRE_PROPERTY = "fire";
    // Vanilla GRILLING_AREA in the mod is Block.box(3,0,3,13,1,13). Block.box takes sixteenths, so that
    // is a plate one pixel thick on the stove's top face, inset to the central 10x10: only an entity
    // standing within that plate burns, the rim is safe, and anything laid on top of the stove (a carpet,
    // a slab) lifts the entity clear of the plate.
    private static final double GRILL_MIN = 3.0D / 16.0D;
    private static final double GRILL_MAX = 13.0D / 16.0D;
    private static final double GRILL_THICKNESS = 1.0D / 16.0D;
    // Resolved once at construction from the block definition this behavior belongs to, so the handle can
    // never go stale: a /ce reload rebuilds the definition and its Property instances together with this
    // behavior. Final, so it is safely published to the region tick threads that read it.
    private final Property<Boolean> fireProperty;
    private final String crackleSound;
    private final boolean burnEnabled;
    private final double burnDamage;
    // Flint & steel / fire charge light the stove and a shovel / water bucket puts it out, the two halves of
    // the mod's AbstractStoveBlock#useItemOn that every stove block inherits there. Both are switchable so an
    // addon can keep a stove that only cooks, and the four sounds plus the tool wear are configurable because
    // they used to live in the pack's right_click events.
    private final boolean igniteEnabled;
    private final boolean extinguishEnabled;
    private final String igniteSound;
    private final String fireChargeSound;
    private final String extinguishSound;
    private final String waterExtinguishSound;
    private final int stateChangeToolDamage;
    // The custom farmersdelight:stove_burn damage type from FD's datapack (correct death message + mob
    // panic), or HOT_FLOOR when the datapack is not loaded so the burn still works either way. Resolved
    // lazily on the first step; a /ce reload replaces this behavior together with the block definition.
    private volatile DamageType burnDamageType;

    // Captured from the factory so the controller it creates does not have to look the plugin up.
    private final FarmersDelightPlugin plugin;

    private StoveCookingBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, Property<Boolean> fireProperty,
                                      String crackleSound, boolean burnEnabled, double burnDamage, boolean igniteEnabled,
                                      boolean extinguishEnabled, String igniteSound, String fireChargeSound,
                                      String extinguishSound, String waterExtinguishSound,
                                      int stateChangeToolDamage) {
        super(block);
        this.plugin = plugin;
        this.fireProperty = fireProperty;
        this.crackleSound = crackleSound;
        this.burnEnabled = burnEnabled;
        this.burnDamage = burnDamage;
        this.igniteEnabled = igniteEnabled;
        this.extinguishEnabled = extinguishEnabled;
        this.igniteSound = igniteSound;
        this.fireChargeSound = fireChargeSound;
        this.extinguishSound = extinguishSound;
        this.waterExtinguishSound = waterExtinguishSound;
        this.stateChangeToolDamage = stateChangeToolDamage;
    }

    public static final BlockBehaviorFactory<StoveCookingBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public StoveCookingBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String path = section != null ? section.path() : Constants.BEHAVIOR_STOVE;
            // The lit state is required, not optional, and the property name is fixed to 'fire' the same way
            // craftengine:crop_block requires 'age': every ignite/extinguish interaction below writes this
            // property, and a block that declares the behavior without a boolean 'fire' property would load as
            // a stove that can never be lit or put out. getProperty aborts the block's own load here, naming
            // the config node and the property, instead of failing later at the first right-click.
            Property<Boolean> fireProperty = BlockBehaviorFactory.getProperty(path, block, FIRE_PROPERTY, Boolean.class);
            String crackleSound = BehaviorArgParser.getArgumentString(arguments, "crackle-sound", Constants.SOUND_STOVE_CRACKLE);
            // Grouped options read either as "burn: {enabled, damage}" or as the legacy flat "burn-enabled" /
            // "burn-damage" (same for ignite/extinguish and the handle-toggle-sound options elsewhere).
            boolean burnEnabled = BehaviorArgParser.getBoolean(arguments, "burn.enabled", true);
            double burnDamage = Math.max(0D, (double) BehaviorArgParser.getFloat(arguments, "burn.damage", 1.0F));
            boolean igniteEnabled = BehaviorArgParser.getBoolean(arguments, "ignite.enabled", true);
            boolean extinguishEnabled = BehaviorArgParser.getBoolean(arguments, "extinguish.enabled", true);
            // Sound ids and the tool wear are validated here, so a typo fails the block's load with its config
            // path instead of silently falling back to the vanilla sound or damaging by the wrong amount.
            String igniteSound = requireSoundId(path, "ignite.sound",
                    BehaviorArgParser.getArgumentString(arguments, "ignite.sound", Constants.SOUND_STOVE_IGNITE));
            String fireChargeSound = requireSoundId(path, "ignite.fire-charge-sound",
                    BehaviorArgParser.getStringStrict(arguments, "ignite.fire-charge-sound", "fire-charge-sound",
                            Constants.SOUND_STOVE_IGNITE_FIRE_CHARGE).trim());
            String extinguishSound = requireSoundId(path, "extinguish.sound",
                    BehaviorArgParser.getArgumentString(arguments, "extinguish.sound", Constants.SOUND_STOVE_EXTINGUISH));
            String waterExtinguishSound = requireSoundId(path, "extinguish.water-sound",
                    BehaviorArgParser.getStringStrict(arguments, "extinguish.water-sound", "water-extinguish-sound",
                            Constants.SOUND_STOVE_EXTINGUISH_WATER).trim());
            int stateChangeToolDamage = Math.max(0, BehaviorArgParser.getInt(arguments, "tool-damage", 1));
            return new StoveCookingBlockBehavior(FarmersDelightPlugin.getInstance(), block, fireProperty, crackleSound,
                    burnEnabled, burnDamage, igniteEnabled, extinguishEnabled, igniteSound, fireChargeSound,
                    extinguishSound, waterExtinguishSound, stateChangeToolDamage);
        }
    };

    // Vanilla-style namespaced id: namespace:path, lowercase letters/digits and '_', '-', '.', '/' only.
    private static final Pattern SOUND_ID_PATTERN =
            Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    // Validates one configured sound id. CraftEngine's Key.of does not check anything (it only splits on ':'),
    // so the pattern above is what makes a typo fail the block's own load with its config path.
    static String requireSoundId(String path, String argument, String raw) {
        if (raw == null || !SOUND_ID_PATTERN.matcher(raw).matches()) {
            throw new KnownResourceException(ConfigConstants.PARSE_IDENTIFIER_FAILED,
                    path + "." + argument, String.valueOf(raw));
        }
        return raw;
    }

    public boolean isLit(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }
        Boolean lit = state.getNullable(fireProperty);
        return lit != null && lit;
    }

    public String getCrackleSound() {
        return crackleSound;
    }

    @Override
    public void stepOn(Object thisBlock, Object[] args) {
        // Vanilla calls this every tick for the block an entity is standing on (Block.stepOn(level, pos,
        // state, entity)), which is exactly where the original mod burns from: no polling, no per-player
        // entity scan, and nothing depends on where any player is. The damage rate is bounded by vanilla's
        // hurt invulnerability window (0.5s), and the burn runs on the thread that owns the entity.
        if (!burnEnabled || burnDamage <= 0D || args == null || args.length < 4) {
            return;
        }
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
        if (!isLit(state)) {
            return;
        }
        LivingEntity entity = adaptLivingEntity(args[3]);
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return;
        }
        // Vanilla's isSteppingCarefully() is only ever true for a sneaking player.
        if (entity instanceof Player player && player.isSneaking()) {
            return;
        }
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (pos == null) {
            return;
        }
        // GRILLING_AREA moved one block up from the stepped-on position: a one-pixel plate resting on the
        // stove's top face (the stove's own collision is a full cube, so a bare stove puts the entity's feet
        // exactly on the plate floor, while a carpet or slab on the stove lifts them off it).
        double grillBottom = pos.y() + 1.0D;
        double grillTop = grillBottom + GRILL_THICKNESS;
        BoundingBox bb = entity.getBoundingBox();
        if (bb.getMaxY() <= grillBottom || bb.getMinY() >= grillTop
                || bb.getMaxX() <= pos.x() + GRILL_MIN || bb.getMinX() >= pos.x() + GRILL_MAX
                || bb.getMaxZ() <= pos.z() + GRILL_MIN || bb.getMinZ() >= pos.z() + GRILL_MAX) {
            return;
        }
        entity.damage(burnDamage, DamageSource.builder(burnDamageType()).build());
    }

    // NMS entity -> Bukkit entity. Non-living entities (items, arrows, ...) are not burned, matching the
    // mod's LivingEntity check.
    private static LivingEntity adaptLivingEntity(Object minecraftEntity) {
        try {
            return EntityUtils.adaptNMS(minecraftEntity).platformEntity() instanceof LivingEntity living
                    ? living : null;
        } catch (RuntimeException | LinkageError ignored) {
            // No adaptor for this entity class -> nothing to burn.
            return null;
        }
    }

    // RegistryKey.DAMAGE_TYPE is the stable lookup. A try/catch with a HOT_FLOOR fallback supports server
    // variants without the registry accessor.
    @SuppressWarnings("UnstableApiUsage")
    private DamageType burnDamageType() {
        DamageType type = this.burnDamageType;
        if (type == null) {
            DamageType custom = null;
            NamespacedKey key = NamespacedKey.fromString("farmersdelight:stove_burn");
            if (key != null) {
                try {
                    custom = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(key);
                } catch (Throwable ignored) {
                    // Registry unavailable on this server flavour -> fall back below.
                }
            }
            type = custom != null ? custom : DamageType.HOT_FLOOR;
            this.burnDamageType = type;
        }
        return type;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new StoveBlockEntityController(plugin, blockEntity);
    }

    @Override
    public void initControllerId(int id) {
    }

    public static StoveCookingBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        return CustomBlockUtils.getBehavior(state, StoveCookingBlockBehavior.class);
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

        World world = player.getWorld();
        BlockPos pos = context.getClickedPos();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ItemStack heldItem = ItemUtils.getItemInHand(player, context.getHand());
        StoveManager manager = getManager();
        if (plugin == null || manager == null) {
            return InteractionResult.PASS;
        }

        if (plugin.isDebugEnabled("stove")) {
            logDebug(player, block, heldItem, manager.findRecipeId(heldItem));
        }
        if (!PermissionChecker.check(player, "farmersdelight.use.stove")) {
            return InteractionResult.PASS;
        }
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.STOVE)
                || !ProtectionCompat.canBuild(player, block, ProtectionCompat.Feature.STOVE)) {
            return InteractionResult.PASS;
        }

        if (isStateChangeItem(heldItem)) {
            return handleStateChangeItem(context, state, player, block, heldItem);
        }

        if (isEquippable(heldItem)) {
            // Passing through lets vanilla equip the held armor after CraftEngine has processed the stove
            // interaction. Cancel this before the sneak bypass too, and for either hand, so a second
            // right-click cannot consume the replaced piece.
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (player.isSneaking()) {
            return InteractionResult.PASS;
        }

        if (heldItem == null || heldItem.getType().isAir()) {
            return InteractionResult.PASS;
        }

        if (!manager.canCook(heldItem)) {
            return InteractionResult.PASS;
        }

        if (manager.handleInteract(player, block, heldItem)) {
            ItemUtils.swingHand(player, context.getHand());
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        // Managed by StoveManager.
    }

    /**
     * The mod's AbstractStoveBlock#useItemOn, split off into ignite/extinguish: a lit stove goes out when hit
     * with a shovel or doused with a water bucket, an unlit one lights up from flint & steel or a fire charge.
     * Every stove block inherits this there, so handling it in the behavior is what makes an addon stove (an
     * addon's own 'fire' property block reusing farmersdelight:stove) behave like the mod's without the pack
     * having to repeat four right_click handlers. Returns PASS when the held item is not one of the four, so
     * the interaction falls through unchanged.
     */
    private InteractionResult handleStateChangeItem(UseOnContext context, ImmutableBlockState state,
                                                    Player player, Block block, ItemStack heldItem) {
        Material type = heldItem.getType();
        StateChange action = stateChangeAction(isLit(state), type, igniteEnabled, extinguishEnabled);
        if (action == StateChange.EXTINGUISH) {
            if (type.name().endsWith("_SHOVEL")) {
                setLit(block, state, false);
                SoundUtils.play(block.getWorld(), center(block), extinguishSound,
                        Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.0F, 1.0F);
                FarmersDelightItems.damage(heldItem, stateChangeToolDamage, center(block));
                ItemUtils.swingHand(player, context.getHand());
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            if (type == Material.WATER_BUCKET) {
                setLit(block, state, false);
                SoundUtils.play(block.getWorld(), center(block), waterExtinguishSound,
                        Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                // The mod leaves the crafting remainder in the hand (an empty bucket) unless the player is in
                // creative, where the bucket is kept.
                if (player.getGameMode() != GameMode.CREATIVE) {
                    heldItem.setType(Material.BUCKET);
                }
                ItemUtils.swingHand(player, context.getHand());
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.PASS;
        }
        if (action == StateChange.IGNITE) {
            if (type == Material.FLINT_AND_STEEL) {
                setLit(block, state, true);
                SoundUtils.play(block.getWorld(), center(block), igniteSound,
                        Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                FarmersDelightItems.damage(heldItem, stateChangeToolDamage, center(block));
                ItemUtils.swingHand(player, context.getHand());
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            if (type == Material.FIRE_CHARGE) {
                setLit(block, state, true);
                SoundUtils.play(block.getWorld(), center(block), fireChargeSound,
                        Sound.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 1.0F, 1.0F);
                if (player.getGameMode() != GameMode.CREATIVE) {
                    heldItem.setAmount(heldItem.getAmount() - 1);
                }
                ItemUtils.swingHand(player, context.getHand());
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }
        return InteractionResult.PASS;
    }

    /** Which state change a right-click performs: a stove is only ever lit or put out, never both. */
    enum StateChange {
        NONE,
        IGNITE,
        EXTINGUISH
    }

    /**
     * The mod's ignite/extinguish split (AbstractStoveBlock#tryToIgnite only runs while unlit and
     * #tryToExtinguish only while lit), plus the two behavior switches. Pure so the rule is unit-tested
     * without a server; the caller maps the action onto the item that produced it.
     */
    static StateChange stateChangeAction(boolean lit, Material held, boolean igniteEnabled, boolean extinguishEnabled) {
        if (held == null || held == Material.AIR) {
            return StateChange.NONE;
        }
        if (lit) {
            return extinguishEnabled && isExtinguishItem(held) ? StateChange.EXTINGUISH : StateChange.NONE;
        }
        return igniteEnabled && isIgniteItem(held) ? StateChange.IGNITE : StateChange.NONE;
    }

    private static boolean isExtinguishItem(Material type) {
        return type == Material.WATER_BUCKET || type.name().endsWith("_SHOVEL");
    }

    private static boolean isIgniteItem(Material type) {
        return type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE;
    }

    // Writes the block's own fire property. place(..., false) keeps the state swap silent; the interaction
    // plays its own sound above.
    private void setLit(Block block, ImmutableBlockState state, boolean lit) {
        CraftEngineBlocks.place(block.getLocation(), state.with(fireProperty, lit), false);
    }

    private static Location center(Block block) {
        return block.getLocation().add(0.5D, 0.5D, 0.5D);
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleStateRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleStateRemoval(args);
    }

    private static void handleStateRemoval(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        Object worldObj = args[1];
        Object posObj = args[2];
        if (!(worldObj instanceof net.momirealms.craftengine.core.world.World ceWorld) || !(posObj instanceof BlockPos pos)) {
            return;
        }
        World world = Bukkit.getWorld(ceWorld.uuid());
        StoveManager manager = getManagerStatic();
        if (world == null || manager == null) {
            return;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        // breakStove persists and dirties only the region-owned location being removed.
        manager.breakStove(location, location.clone().add(0.5, 0.5, 0.5), false);
    }

    public static void cleanupAll() {
        StoveManager manager = getManagerStatic();
        if (manager != null) {
            manager.cleanup();
        }
    }

    public static void saveAllData() {
        StoveManager manager = getManagerStatic();
        if (manager != null) {
            manager.saveAllData();
        }
    }

    public static void cleanupWorld(UUID worldId) {
        StoveManager manager = getManagerStatic();
        if (manager != null) {
            manager.cleanupWorld(worldId);
        }
    }

    private StoveManager getManager() {
        return managerOf(plugin);
    }

    // Static callers (cleanup / save / state removal) have no behaviour instance to take the plugin from.
    private static StoveManager getManagerStatic() {
        return managerOf(FarmersDelightPlugin.getInstance());
    }

    private static StoveManager managerOf(FarmersDelightPlugin plugin) {
        if (plugin == null) {
            return null;
        }
        return plugin.getStoveManager();
    }

    @SuppressWarnings("UnstableApiUsage")
    public static boolean isEquippable(ItemStack item) {
        if (item == null) return false;
        return isEquippable(item.getType(), item.hasData(DataComponentTypes.EQUIPPABLE));
    }

    static boolean isEquippable(Material type, boolean hasEquippableComponent) {
        return type == Material.SHIELD || hasEquippableComponent;
    }

    public static boolean isStateChangeItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        Material type = itemStack.getType();
        return isIgniteItem(type) || isExtinguishItem(type);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void logDebug(Player player, Block clickedBlock, ItemStack item, String recipeId) {
        String resolvedItemId = CookingDebugLog.resolveItemId(item);
        Material material = Material.AIR;
        if (item != null) {
            material = item.getType();
        }
        Bukkit.getLogger().info(I18n.formatConsole("debug.ce_header"));
        CookingDebugLog.logField("debug.label_behavior", "farmersdelight:stove");
        CookingDebugLog.logField("debug.label_player", player.getName());
        CookingDebugLog.logField("debug.label_clicked_block", clickedBlock.getType());
        CookingDebugLog.logField("debug.label_item", material);
        CookingDebugLog.logField("debug.label_item_id", resolvedItemId);
        CookingDebugLog.logField("debug.label_recipe_found", recipeId);
    }
}
