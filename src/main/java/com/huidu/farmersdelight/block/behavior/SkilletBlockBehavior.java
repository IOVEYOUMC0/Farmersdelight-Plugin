package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
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

public class SkilletBlockBehavior extends BlockBehavior {

    public static final int DEFAULT_COOKING_TIME = 600;
    public static final int MINIMUM_COOKING_TIME = 60;

    private final String addFoodSound;
    private final String sizzleSound;

    public static final BlockBehaviorFactory<SkilletBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public SkilletBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            String addFoodSound = getArgumentString(arguments, "add-food-sound", Constants.SOUND_SKILLET_ADD_FOOD);
            String sizzleSound = getArgumentString(arguments, "sizzle-sound", Constants.SOUND_SKILLET_SIZZLE);
            return new SkilletBlockBehavior(block, addFoodSound, sizzleSound);
        }
    };

    private SkilletBlockBehavior(CustomBlock block, String addFoodSound, String sizzleSound) {
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

    public static SkilletBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        var behavior = state.behavior();
        if (behavior instanceof SkilletBlockBehavior skilletBehavior) {
            return skilletBehavior;
        }
        return null;
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
    public void tick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        // Managed by SkilletManager.
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
        return itemStack != null && "farmersdelight:skillet".equals(ItemUtils.getCustomItemId(itemStack));
    }

    private void logDebug(Player player, Block clickedBlock, ItemStack item, String recipeId) {
        String resolvedItemId = resolveItemId(item);
        Material material = Material.AIR;
        if (item != null) {
            material = item.getType();
        }
        Bukkit.getLogger().info("=== FD DEBUG CE ===");
        Bukkit.getLogger().info("Behavior: farmersdelight:skillet");
        Bukkit.getLogger().info("Player: " + player.getName());
        Bukkit.getLogger().info("Clicked block: " + clickedBlock.getType());
        Bukkit.getLogger().info("Item: " + material);
        Bukkit.getLogger().info("Item id: " + resolvedItemId);
        Bukkit.getLogger().info("Recipe found: " + recipeId);
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
