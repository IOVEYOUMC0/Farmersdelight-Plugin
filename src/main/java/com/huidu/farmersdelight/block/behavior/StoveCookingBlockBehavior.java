package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CookingDebugLog;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.PermissionChecker;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class StoveCookingBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    public static final int SLOT_COUNT = 6;
    public static final String FIRE_PROPERTY = "fire";
    // Resolved once at construction from the block definition this behavior belongs to, so the handle can
    // never go stale: a /ce reload rebuilds the definition and its Property instances together with this
    // behavior. Final, so it is safely published to the region tick threads that read it.
    private final Property<Boolean> fireProperty;
    private final String crackleSound;
    private final boolean burnEnabled;
    private final double burnDamage;

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
        public StoveCookingBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
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

    public boolean isBurnEnabled() {
        return burnEnabled;
    }

    public double getBurnDamage() {
        return burnDamage;
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
        // breakStove already persists/dirties exactly the broken location; the previous
        // saveWorldData(world) re-dirtied every stove in the world (O(N) block-entity lookups) on
        // each single break, and on Folia reached chunks owned by other region threads.
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

    public static void cleanupWorld(java.util.UUID worldId) {
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
