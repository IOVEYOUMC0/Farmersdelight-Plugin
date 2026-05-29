package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.World;

import java.util.Map;

public final class SkilletBlockEntityController extends BlockEntityController {
    static final String DATA_KEY = "farmersdelight:skillet";
    private CompoundTag pendingLoadData;

    public SkilletBlockEntityController(BlockEntity blockEntity) {
        super(blockEntity);
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        loadPendingDataIfReady();
        if (this.pendingLoadData != null) {
            tag.put(DATA_KEY, this.pendingLoadData);
            return;
        }
        SkilletManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return;
        }
        Map<String, Object> data = manager.exportSkilletData(world, new BlockPosKey(this.blockEntity.pos));
        if (data == null || data.isEmpty()) {
            return;
        }
        tag.put(DATA_KEY, SimpleBlockEntityData.save(data));
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        CompoundTag data = tag.getCompound(DATA_KEY);
        if (data == null) return;
        queueLoadData(data);
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        CompoundTag data = CustomBlockUtils.getNestedComponentCompound(item, DataComponentKeys.BLOCK_ENTITY_DATA, DATA_KEY);
        if (data == null) return;
        queueLoadData(data);
    }

    public void loadPendingDataIfReady() {
        CompoundTag data = this.pendingLoadData;
        if (data == null) {
            return;
        }
        if (loadData(data)) {
            this.pendingLoadData = null;
        }
    }

    private void queueLoadData(CompoundTag tag) {
        this.pendingLoadData = tag;
        loadPendingDataIfReady();
    }

    private boolean loadData(CompoundTag tag) {
        SkilletManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return false;
        }
        manager.loadSkillet(world, new BlockPosKey(this.blockEntity.pos), SimpleBlockEntityData.load(tag,
                "storedItem", "skilletStack"));
        return true;
    }

    private World getBukkitWorld() {
        return CustomBlockUtils.getBukkitWorld(this.blockEntity);
    }

    private SkilletManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getSkilletManager();
    }
}
