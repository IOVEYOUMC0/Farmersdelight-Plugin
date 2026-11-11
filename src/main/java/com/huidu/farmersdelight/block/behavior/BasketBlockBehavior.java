package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.util.Map;

public class BasketBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock {

    // Wait eight ticks after a successful pickup.
    public static final int DEFAULT_TRANSFER_COOLDOWN = 8;

    // Captured from the factory so the controller it creates does not have to look the plugin up.
    private final FarmersDelightPlugin plugin;
    private final int transferCooldown;
    private final boolean eject;

    private BasketBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, int transferCooldown, boolean eject) {
        super(block);
        this.plugin = plugin;
        this.transferCooldown = transferCooldown;
        this.eject = eject;
    }

    public static final BlockBehaviorFactory<BasketBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        // Runs while CraftEngine parses the pack, which is always after this plugin enabled.
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        int cooldown = Math.max(1, BehaviorArgParser.getInt(arguments, "transfer-cooldown", DEFAULT_TRANSFER_COOLDOWN));
        // Optional extra: push contents into a container the basket faces. The upstream basket only collects,
        // so this stays off unless a server opts in; collection itself is always on.
        boolean eject = BehaviorArgParser.getBoolean(arguments, "eject", false);
        return new BasketBlockBehavior(plugin, block, cooldown, eject);
    };

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new BasketVacuumController(plugin, blockEntity, transferCooldown, eject);
    }

    @Override
    public void initControllerId(int id) {
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

}
