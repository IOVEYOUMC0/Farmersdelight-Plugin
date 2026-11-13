package com.huidu.farmersdelight;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.World;

/**
 * Persists the plugin's per-world block state at shutdown and on world unload. The shutdown path, the
 * world-unload path and CraftEngine's own world flush all need the same three steps.
 */
final class WorldDataPersistence {

    private WorldDataPersistence() {
    }

    /**
     * Marks (Folia, where the write must happen on the owning region) or writes (Paper) every block entity's
     * data, then flushes CraftEngine's own per-world data.
     */
    static void saveAllBlockData(FarmersDelightPlugin plugin) {
        if (plugin.scheduler() != null && plugin.scheduler().isFolia()) {
            plugin.runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::markAllBlockEntitiesDirty);
            plugin.runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::markAllBlockEntitiesDirty);
        } else {
            plugin.runDisableStep("plugin.disable_step_save_cooking_pot_data", CookingPotBlockBehavior::saveAllData);
            plugin.runDisableStep("plugin.disable_step_save_cutting_board_data", CuttingBoardBlockBehavior::saveAllData);
        }
        plugin.runDisableStep("plugin.disable_step_save_skillet_data", SkilletBlockBehavior::saveAllData);
        plugin.runDisableStep("plugin.disable_step_save_stove_data", StoveCookingBlockBehavior::saveAllData);

        flushCraftEngineWorldData(plugin);
    }

    /** Writes one world's block-entity data; called when that world unloads, not at plugin shutdown. */
    static void saveWorldBlockData(FarmersDelightPlugin plugin, World world) {
        if (world == null) {
            return;
        }
        boolean folia = plugin.scheduler().isFolia();
        for (BlockPosKey posKey : CookingPotBlockBehavior.getAllBlockEntities(world).keySet()) {
            if (mayWriteInline(folia, plugin, world, posKey)) {
                CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
            }
        }
        for (BlockPosKey posKey : CuttingBoardBlockBehavior.getAllBlockEntities(world).keySet()) {
            if (mayWriteInline(folia, plugin, world, posKey)) {
                CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
            }
        }
        if (plugin.getSkilletManager() != null) {
            plugin.getSkilletManager().saveWorldData(world);
        }
        if (plugin.getStoveManager() != null) {
            plugin.getStoveManager().saveWorldData(world);
        }
    }

    /**
     * A block's CraftEngine data may only be read or marked from the thread that owns its region. Folia has no
     * runtime world unloading (CraftServer.createWorld/unloadWorld throw), so this only guards a forked or
     * future server that delivers WorldUnloadEvent from a different region thread.
     */
    static boolean mayWriteInline(boolean folia, FarmersDelightPlugin plugin, World world, BlockPosKey posKey) {
        if (!folia) {
            return true;
        }
        if (plugin == null || world == null || posKey == null) {
            return false;
        }
        return CustomBlockUtils.ownsBlockRegion(plugin, world, posKey.toBlockPos());
    }

    private static void flushCraftEngineWorldData(FarmersDelightPlugin plugin) {
        // CraftEngine loads after this plugin, so at shutdown it is disabled first and its classloader is
        // closed by the time this runs. It has already saved its own world data on the way down; touching
        // its classes here would throw NoClassDefFoundError. Flush only when it is still enabled.
        if (!plugin.getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            plugin.runDisableStep("plugin.disable_step_flush_craftengine_world", () -> {
                CEWorld ceWorld = CustomBlockUtils.getCEWorld(world);
                if (ceWorld != null) {
                    ceWorld.saveChunks();
                    ceWorld.saveSettings();
                }
            }, "world", world.getName());
        }
    }
}
