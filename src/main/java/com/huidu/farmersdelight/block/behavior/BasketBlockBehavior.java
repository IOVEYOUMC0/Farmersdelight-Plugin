package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.util.Map;

/**
 * The item-vacuum half of Farmer's Delight's basket. It is composed alongside CraftEngine's
 * simple_storage_block behavior in the block definition: the storage behavior owns the container (GUI,
 * comparator, hopper I/O, drop-on-break) and the six-way facing state, while this behavior contributes a
 * second block-entity controller whose ticker pulls dropped items from the faced cell into that
 * container. Keeping the two behaviors separate leaves the storage behavior untouched and lets the
 * composite dispatch container calls to it exactly as before.
 */
public class BasketBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock {

    // The reference BasketBlockEntity waits eight ticks after each successful pickup (setCooldown(8)).
    public static final int DEFAULT_TRANSFER_COOLDOWN = 8;

    private final int transferCooldown;
    private final boolean eject;

    private BasketBlockBehavior(BlockDefinition block, int transferCooldown, boolean eject) {
        super(block);
        this.transferCooldown = transferCooldown;
        this.eject = eject;
    }

    public static final BlockBehaviorFactory<BasketBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        int cooldown = Math.max(1, BehaviorArgParser.getInt(arguments, "transfer-cooldown", DEFAULT_TRANSFER_COOLDOWN));
        // When on, the basket pushes its contents into a container it faces; when off it only collects
        // dropped items. Collection is always on. Defaults to on so an existing basket gains the behavior.
        boolean eject = BehaviorArgParser.getBoolean(arguments, "eject", true);
        return new BasketBlockBehavior(block, cooldown, eject);
    };

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new BasketVacuumController(blockEntity, transferCooldown, eject);
    }

    @Override
    public void initControllerId(int id) {
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

}
