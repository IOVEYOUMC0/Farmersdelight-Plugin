package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.WorldGuardCompat;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
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
import java.util.concurrent.Callable;

public class SkilletBlockBehavior extends BlockBehavior implements EntityBlock {

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

    public static final int DEFAULT_COOKING_TIME = 600;
    public static final int MINIMUM_COOKING_TIME = 60;

    private final String addFoodSound;
    private final String sizzleSound;

    public static final BlockBehaviorFactory<SkilletBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public SkilletBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String addFoodSound = getArgumentString(arguments, "add-food-sound", Constants.SOUND_SKILLET_ADD_FOOD);
            String sizzleSound = getArgumentString(arguments, "sizzle-sound", Constants.SOUND_SKILLET_SIZZLE);
            return new SkilletBlockBehavior(block, addFoodSound, sizzleSound);
        }
    };

    private SkilletBlockBehavior(BlockDefinition block, String addFoodSound, String sizzleSound) {
        super(block);
        this.addFoodSound = addFoodSound;
        this.sizzleSound = sizzleSound;
    }

    public String getAddFoodSound() {
        return addFoodSound;
    }

    public String getSizzleSound() {
        return sizzleSound;
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new SkilletBlockEntityController(blockEntity);
    }

    @Override
    public void initControllerId(int id) {
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

    private static String getArgumentString(Map<String, Object> arguments, String key, String defaultValue) {
        if (arguments == null) {
            return defaultValue;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text;
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
        if (!WorldGuardCompat.canUse(player, block) || !WorldGuardCompat.canBuild(player, block)) {
            return InteractionResult.PASS;
        }

        if (player.isSneaking() && isSkilletItem(mainHand)) {
            return InteractionResult.PASS;
        }

        if (!player.hasPermission("farmersdelight.use.skillet")) {
            player.sendActionBar(I18n.getComponent("general.no_permission", player));
            return InteractionResult.FAIL;
        }

        if (manager.handleInteract(player, block, mainHand, EquipmentSlot.HAND)) {
            player.updateInventory();
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
        manager.saveWorldData(world);
        manager.breakSkillet(location, location.clone().add(0.5, 0.5, 0.5), false);
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

    private boolean isSkilletItem(ItemStack itemStack) {
        return itemStack != null && Constants.ITEM_SKILLET.equals(ItemUtils.getCustomItemId(itemStack));
    }

    private void logDebug(Player player, Block clickedBlock, ItemStack item, String recipeId) {
        String resolvedItemId = resolveItemId(item);
        Material material = Material.AIR;
        if (item != null) {
            material = item.getType();
        }
        Bukkit.getLogger().info(I18n.formatConsole("debug.ce_header"));
        logDebugField("debug.label_behavior", "farmersdelight:skillet");
        logDebugField("debug.label_player", player.getName());
        logDebugField("debug.label_clicked_block", clickedBlock.getType());
        logDebugField("debug.label_item", material);
        logDebugField("debug.label_item_id", resolvedItemId);
        logDebugField("debug.label_recipe_found", recipeId);
    }

    private void logDebugField(String labelKey, Object value) {
        Bukkit.getLogger().info(I18n.formatConsole("debug.field",
                "label", I18n.formatConsole(labelKey),
                "value", value));
    }

    private String resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "minecraft:air";
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null) {
            return customItemId;
        }
        return "minecraft:" + item.getType().name().toLowerCase();
    }
}

