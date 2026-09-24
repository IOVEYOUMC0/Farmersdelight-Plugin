package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CookingDebugLog;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.PermissionChecker;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
    // The custom farmersdelight:stove_burn damage type from FD's datapack (correct death message + mob
    // panic), or HOT_FLOOR when the datapack is not loaded so the burn still works either way. Resolved
    // lazily on the first step; a /ce reload replaces this behavior together with the block definition.
    private volatile DamageType burnDamageType;

    private StoveCookingBlockBehavior(BlockDefinition block, Property<Boolean> fireProperty, String crackleSound,
                                       boolean burnEnabled, double burnDamage) {
        super(block);
        this.fireProperty = fireProperty;
        this.crackleSound = crackleSound;
        this.burnEnabled = burnEnabled;
        this.burnDamage = burnDamage;
    }

    public static final BlockBehaviorFactory<StoveCookingBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public StoveCookingBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            // The lit state is not optional: without it the stove has no way to be off, so a block that
            // declares this behavior without a boolean 'fire' property aborts its own load here with the
            // config node and property name in the message, instead of behaving as permanently lit.
            String path = section != null ? section.path() : Constants.BEHAVIOR_STOVE;
            Property<Boolean> fireProperty = BlockBehaviorFactory.getProperty(path, block, FIRE_PROPERTY, Boolean.class);
            String crackleSound = BehaviorArgParser.getArgumentString(arguments, "crackle-sound", Constants.SOUND_STOVE_CRACKLE);
            boolean burnEnabled = BehaviorArgParser.getBoolean(arguments, "burn-enabled", true);
            double burnDamage = Math.max(0D, (double) BehaviorArgParser.getFloat(arguments, "burn-damage", 1.0F));
            return new StoveCookingBlockBehavior(block, fireProperty, crackleSound, burnEnabled, burnDamage);
        }
    };

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
        return new StoveBlockEntityController(blockEntity);
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
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
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
            return InteractionResult.PASS;
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
        StoveManager manager = getManager();
        if (world == null || manager == null) {
            return;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        // breakStove persists and dirties only the region-owned location being removed.
        manager.breakStove(location, location.clone().add(0.5, 0.5, 0.5), false);
    }

    public static void cleanupAll() {
        StoveManager manager = getManager();
        if (manager != null) {
            manager.cleanup();
        }
    }

    public static void saveAllData() {
        StoveManager manager = getManager();
        if (manager != null) {
            manager.saveAllData();
        }
    }

    public static void cleanupWorld(UUID worldId) {
        StoveManager manager = getManager();
        if (manager != null) {
            manager.cleanupWorld(worldId);
        }
    }

    private static StoveManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
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
        return type == Material.FLINT_AND_STEEL
                || type == Material.FIRE_CHARGE
                || type == Material.WATER_BUCKET
                || type.name().endsWith("_SHOVEL");
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
