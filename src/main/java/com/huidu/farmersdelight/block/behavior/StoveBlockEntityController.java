package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.StoveManager;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.World;

import java.util.Map;

public final class StoveBlockEntityController extends BlockEntityController {
    static final String DATA_KEY = "farmersdelight:stove";
    private volatile CompoundTag pendingLoadData;
    // Snapshot taken when the manager entry is dropped on chunk unload: the manager map is cleared at that
    // point (CE's pull-based serialization at HIGHEST would export nothing), so this is the source of truth
    // until the chunk reloads. Also re-hydrates the manager when CraftEngine's chunk cache serves this same
    // controller back on a quick reload without re-running loadCustomData.
    private volatile CompoundTag pendingSaveData;
    // Guards loadPendingDataIfReady against re-entry from manager entry-creation hooks that flush pending data.
    private volatile boolean applyingPendingLoad;

    public StoveBlockEntityController(BlockEntity blockEntity) {
        super(blockEntity);
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        // A passivation snapshot is authoritative while the manager entry is gone; checked before
        // loadPendingDataIfReady so serializing an unloaded chunk cannot resurrect the manager entry.
        if (this.pendingSaveData != null) {
            tag.put(DATA_KEY, this.pendingSaveData);
            return;
        }
        loadPendingDataIfReady();
        if (this.pendingLoadData != null) {
            tag.put(DATA_KEY, this.pendingLoadData);
            return;
        }
        StoveManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return;
        }
        Map<String, Object> data = manager.exportStoveData(world, new BlockPosKey(this.blockEntity.pos));
        if (data == null || data.isEmpty()) {
            return;
        }
        tag.put(DATA_KEY, SimpleBlockEntityData.save(data));
    }

    /**
     * Snapshots the manager's current state for this stove so it survives the manager entry being
     * dropped on chunk unload (CE serializes by pulling from the manager at HIGHEST, after the entry is
     * gone). Returns false when the manager or world is unavailable, in which case the caller must keep
     * the entry so the pull path can still export it.
     */
    public boolean passivate() {
        StoveManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return false;
        }
        Map<String, Object> data = manager.exportStoveData(world, new BlockPosKey(this.blockEntity.pos));
        this.pendingSaveData = data == null || data.isEmpty() ? null : SimpleBlockEntityData.save(data);
        CustomBlockUtils.markBlockEntityDirty(this.blockEntity);
        return true;
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
        if (this.applyingPendingLoad) {
            return;
        }
        if (this.pendingLoadData == null && this.pendingSaveData != null) {
            // Chunk reload served from CraftEngine's chunk cache: loadCustomData never ran (no
            // deserialization), so the passivation snapshot is the authoritative state to re-hydrate from.
            this.pendingLoadData = this.pendingSaveData;
            this.pendingSaveData = null;
        }
        CompoundTag data = this.pendingLoadData;
        if (data == null) {
            return;
        }
        this.applyingPendingLoad = true;
        try {
            if (loadData(data)) {
                this.pendingLoadData = null;
            }
        } finally {
            this.applyingPendingLoad = false;
        }
    }

    private void queueLoadData(CompoundTag tag) {
        // Freshly deserialized/item-packed data is authoritative; discard any stale passivation snapshot.
        this.pendingSaveData = null;
        this.pendingLoadData = tag;
        loadPendingDataIfReady();
    }

    private boolean loadData(CompoundTag tag) {
        StoveManager manager = getManager();
        World world = getBukkitWorld();
        if (manager == null || world == null) {
            return false;
        }
        return manager.loadStove(world, new BlockPosKey(this.blockEntity.pos), SimpleBlockEntityData.load(tag,
                "slot_0_item", "slot_1_item", "slot_2_item", "slot_3_item", "slot_4_item", "slot_5_item"));
    }

    private World getBukkitWorld() {
        return CustomBlockUtils.getBukkitWorld(this.blockEntity);
    }

    private StoveManager getManager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getStoveManager();
    }
}
