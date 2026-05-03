package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.StoveManager;
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
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.Callable;

public class StoveCookingBlockBehavior extends BlockBehavior {

    public static final int SLOT_COUNT = 6;
    private final String crackleSound;

    private StoveCookingBlockBehavior(CustomBlock block, String crackleSound) {
        super(block);
        this.crackleSound = crackleSound;
    }

    public static final BlockBehaviorFactory<StoveCookingBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public StoveCookingBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            String crackleSound = getArgumentString(arguments, "crackle-sound", Constants.SOUND_STOVE_CRACKLE);
            return new StoveCookingBlockBehavior(block, crackleSound);
        }
    };

    public String getCrackleSound() {
        return crackleSound;
    }

    public static StoveCookingBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        var behavior = state.behavior();
        if (behavior instanceof StoveCookingBlockBehavior stoveBehavior) {
            return stoveBehavior;
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
        StoveManager manager = getManager();
        if (plugin == null || manager == null) {
            return InteractionResult.PASS;
        }

        if (plugin.isDebugEnabled("stove")) {
            logDebug(player, block, mainHand, manager.findRecipeId(mainHand));
        }

        if (player.isSneaking()) {
            return InteractionResult.PASS;
        }

        if (isStateChangeItem(mainHand)) {
            return InteractionResult.PASS;
        }

        if (!player.hasPermission("farmersdelight.use.stove")) {
            player.sendActionBar(I18n.getComponent("general.no_permission", player));
            return InteractionResult.FAIL;
        }

        if (mainHand == null || mainHand.getType().isAir()) {
            if (manager.handleRetrieve(player, block)) {
                player.updateInventory();
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.PASS;
        }

        if (!manager.canCook(mainHand)) {
            return InteractionResult.PASS;
        }

        if (manager.handleInteract(player, block, mainHand)) {
            player.updateInventory();
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    @Override
    public void tick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        // Managed by StoveManager.
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

    public static void clearRecipeCache() {
        StoveCookingBlockEntity.clearRecipeCache();
    }

    private static StoveManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return null;
        }
        return plugin.getStoveManager();
    }

    private boolean isStateChangeItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }

        Material type = itemStack.getType();
        return type == Material.FLINT_AND_STEEL
                || type == Material.WATER_BUCKET
                || type == Material.BUCKET
                || type == Material.POTION
                || type.name().endsWith("_SHOVEL");
    }

    private void logDebug(Player player, Block clickedBlock, ItemStack item, String recipeId) {
        String resolvedItemId = resolveItemId(item);
        Material material = Material.AIR;
        if (item != null) {
            material = item.getType();
        }
        Bukkit.getLogger().info("=== FD DEBUG CE ===");
        Bukkit.getLogger().info("Behavior: farmersdelight:stove");
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
