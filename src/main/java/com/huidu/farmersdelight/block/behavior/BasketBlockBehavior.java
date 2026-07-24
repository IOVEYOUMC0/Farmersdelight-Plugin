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
public class BasketBlockBehavior extends BlockBehavior implements EntityBlock {

    // The reference BasketBlockEntity waits eight ticks after each successful pickup (setCooldown(8)).
    public static final int DEFAULT_TRANSFER_COOLDOWN = 8;

    private final int transferCooldown;

    private BasketBlockBehavior(BlockDefinition block, int transferCooldown) {
        super(block);
        this.transferCooldown = transferCooldown;
    }

    public static final BlockBehaviorFactory<BasketBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public BasketBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            int cooldown = Math.max(1, BehaviorArgParser.getInt(arguments, "transfer-cooldown", DEFAULT_TRANSFER_COOLDOWN));
            return new BasketBlockBehavior(block, cooldown);
        }
    };

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new BasketVacuumController(blockEntity, transferCooldown);
    }

    @Override
    public void initControllerId(int id) {
    }

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
}
