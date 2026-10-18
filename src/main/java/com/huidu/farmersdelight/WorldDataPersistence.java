package com.huidu.farmersdelight;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
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
        for (BlockPosKey posKey : CookingPotBlockBehavior.getAllBlockEntities(world).keySet()) {
            CookingPotBlockBehavior.saveBlockEntityData(world, posKey);
        }
        for (BlockPosKey posKey : CuttingBoardBlockBehavior.getAllBlockEntities(world).keySet()) {
            CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
        }
        if (plugin.getSkilletManager() != null) {
            plugin.getSkilletManager().saveWorldData(world);
        }
        if (plugin.getStoveManager() != null) {
            plugin.getStoveManager().saveWorldData(world);
        }
    }

    private static void flushCraftEngineWorldData(FarmersDelightPlugin plugin) {
        BukkitWorldManager worldManager = BukkitWorldManager.instance();
        if (worldManager == null) {
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
