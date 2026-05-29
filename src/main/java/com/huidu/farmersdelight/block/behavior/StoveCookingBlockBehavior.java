package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.StoveManager;
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
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.Callable;

public class StoveCookingBlockBehavior extends BlockBehavior implements EntityBlock {

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

    public static final int SLOT_COUNT = 6;
    private final String crackleSound;

    private StoveCookingBlockBehavior(BlockDefinition block, String crackleSound) {
        super(block);
        this.crackleSound = crackleSound;
    }

    public static final BlockBehaviorFactory<StoveCookingBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public StoveCookingBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String crackleSound = getArgumentString(arguments, "crackle-sound", Constants.SOUND_STOVE_CRACKLE);
            return new StoveCookingBlockBehavior(block, crackleSound);
        }
    };

    public String getCrackleSound() {
        return crackleSound;
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
        if (!WorldGuardCompat.canUse(player, block) || !WorldGuardCompat.canBuild(player, block)) {
            return InteractionResult.PASS;
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
        manager.saveWorldData(world);
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

    @SuppressWarnings("deprecation")
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

    public static boolean isStateChangeItem(ItemStack itemStack) {
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
        Bukkit.getLogger().info(I18n.formatConsole("debug.ce_header"));
        logDebugField("debug.label_behavior", "farmersdelight:stove");
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

