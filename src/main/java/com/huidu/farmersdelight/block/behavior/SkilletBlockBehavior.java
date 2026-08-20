package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CookingDebugLog;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import com.huidu.farmersdelight.util.PermissionChecker;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class SkilletBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock, WorldlyContainerHolder {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    public static final String SUPPORT_PROPERTY = "support";

    private final String addFoodSound;
    private final String sizzleSound;
    private final Property<Boolean> supportProperty;
    private int controllerId;

    public static final BlockBehaviorFactory<SkilletBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        String addFoodSound = BehaviorArgParser.getArgumentString(arguments, "add-food-sound", Constants.SOUND_SKILLET_ADD_FOOD);
        String sizzleSound = BehaviorArgParser.getArgumentString(arguments, "sizzle-sound", Constants.SOUND_SKILLET_SIZZLE);
        Property<Boolean> supportProperty = BlockBehaviorFactory.getOptionalProperty(block, SUPPORT_PROPERTY, Boolean.class);
        if (supportProperty == null) {
            FarmersDelightPlugin.getInstance().getLogger()
                    .warning("[FarmersDelight] Block " + block.id() + " is missing the 'support' property"
                            + " — tray entity_renderer switching is disabled for this block.");
        }
        return new SkilletBlockBehavior(block, addFoodSound, sizzleSound, supportProperty);
    };

    private SkilletBlockBehavior(BlockDefinition block, String addFoodSound, String sizzleSound,
                                  Property<Boolean> supportProperty) {
        super(block);
        this.addFoodSound = addFoodSound;
        this.sizzleSound = sizzleSound;
        this.supportProperty = supportProperty;
    }

    public String getAddFoodSound() {
        return addFoodSound;
    }

    public String getSizzleSound() {
        return sizzleSound;
    }

    public Property<Boolean> getSupportProperty() {
        return supportProperty;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new SkilletBlockEntityController(blockEntity);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    public static SkilletBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        return CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class);
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

        World world = player.getWorld();
        BlockPos pos = context.getClickedPos();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        SkilletManager manager = getManager();
        if (plugin == null || manager == null) {
            return InteractionResult.PASS;
        }

        if (plugin.isDebugEnabled("skillet")) {
            logDebug(player, block, mainHand, manager.findRecipeId(mainHand));
        }
        if (!PermissionChecker.check(player, "farmersdelight.use.skillet")) {
            return InteractionResult.PASS;
        }
        if (!ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.SKILLET)
                || !ProtectionCompat.canBuild(player, block, ProtectionCompat.Feature.SKILLET)) {
            return InteractionResult.PASS;
        }

        if (player.isSneaking() && isSkilletItem(mainHand)) {
            return InteractionResult.PASS;
        }

        if (isEquippable(mainHand)) {
            return InteractionResult.PASS;
        }

        if (manager.handleInteract(player, block, mainHand, EquipmentSlot.HAND)) {
            player.updateInventory();
            player.swingMainHand();
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    @Override
    public void tick(Object thisBlock, Object[] args) {
        // Managed by SkilletManager.
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
        org.bukkit.World world = Bukkit.getWorld(ceWorld.uuid());
        SkilletManager manager = getManager();
        if (world == null || manager == null) {
            return;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        // breakSkillet already persists/dirties exactly the broken location; the previous
        // saveWorldData(world) re-dirtied every skillet in the world (O(N) block-entity lookups) on
        // each single break, and on Folia reached chunks owned by other region threads.
        manager.breakSkillet(location, location.clone().add(0.5, 0.5, 0.5), false);
    }

    @Override
    public Object getContainer(Object thisBlock, Object[] args) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || plugin.isSkilletHopperInteractionsEnabled()) {
            return null;
        }
        if (args == null || args.length < 3) {
            return null;
        }

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return null;
        }

        CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
        if (ceWorld == null) {
            return null;
        }

        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) {
            return null;
        }
        return blockEntity.controller.let(SkilletBlockEntityController.class, this.controllerId, controller -> {
            controller.syncFromManager();
            return controller.container();
        });
    }

    public static void cleanupAll() {
        SkilletManager manager = getManager();
        if (manager != null) {
            manager.cleanup();
        }
    }

    public static void saveAllData() {
        SkilletManager manager = getManager();
        if (manager != null) {
            manager.saveAllData();
        }
    }

    public static void cleanupWorld(java.util.UUID worldId) {
        SkilletManager manager = getManager();
        if (manager != null) {
            manager.cleanupWorld(worldId);
        }
    }

    private static SkilletManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return null;
        }
        return plugin.getSkilletManager();
    }

    public static boolean isEquippable(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        String name = type.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.contains("HORSE_ARMOR") || name.contains("WOLF_ARMOR")
                || type == Material.ELYTRA || type == Material.SHIELD;
    }

    private boolean isSkilletItem(ItemStack itemStack) {
        return itemStack != null && Constants.ITEM_SKILLET.equals(ItemUtils.getCustomItemId(itemStack));
    }

    @SuppressWarnings("UnstableApiUsage")
    private void logDebug(Player player, Block clickedBlock, ItemStack item, String recipeId) {
        String resolvedItemId = CookingDebugLog.resolveItemId(item);
        Material material = Material.AIR;
        if (item != null) {
            material = item.getType();
        }
        Bukkit.getLogger().info(I18n.formatConsole("debug.ce_header"));
        CookingDebugLog.logField("debug.label_behavior", "farmersdelight:skillet");
        CookingDebugLog.logField("debug.label_player", player.getName());
        CookingDebugLog.logField("debug.label_clicked_block", clickedBlock.getType());
        CookingDebugLog.logField("debug.label_item", material);
        CookingDebugLog.logField("debug.label_item_id", resolvedItemId);
        CookingDebugLog.logField("debug.label_recipe_found", recipeId);
    }
}
