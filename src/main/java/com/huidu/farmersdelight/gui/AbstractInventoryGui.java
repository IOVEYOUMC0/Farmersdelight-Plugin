package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 所有插件的 Inventory GUI 的共享基类。
 * 提取了 open/close 生命周期、GuiTickManager 的 tick 回调注册/注销、以及 onClose/onDrag 事件处理等公共逻辑。
 */
public abstract class AbstractInventoryGui implements InventoryHolder {

    protected final FarmersDelightPlugin plugin;
    protected UUID playerId;
    protected Player player;
    protected Inventory inventory;
    protected volatile boolean closed = false;
    /** 子类通过 onTick() 实现每 tick 的刷新逻辑 */
    protected final Consumer<Void> tickCallback;

    protected AbstractInventoryGui(FarmersDelightPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.playerId = player != null ? player.getUniqueId() : null;
        this.tickCallback = v -> onTick();
    }

    @Override
    @Nonnull
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * 每 tick 的回调，子类重写以实现动画/状态刷新等逻辑。
     * GuiTickManager 在玩家所在区域线程上调用。
     */
    protected void onTick() {
    }

    /**
     * 打开 GUI 给玩家。处理已有 GUI 的关闭、事件监听器注册、活跃 GUI 追踪、以及 tick 回调注册。
     *
     * @param afterRefresh 在创建库存并填充后、注册 tick 回调之前的钩子，传 null 表示无额外操作
     */
    protected final void doOpen(Runnable afterRefresh) {
        closed = false;

        AbstractInventoryGui existingGui = findExistingGui(playerId);
        if (existingGui != null && !existingGui.closed) {
            existingGui.close();
        }

        ensureListenerRegistered();
        putActiveGui(playerId, this);

        if (afterRefresh != null) {
            afterRefresh.run();
        }
        player.openInventory(inventory);

        GuiTickManager.getInstance(plugin).registerCallback(player, tickCallback);
    }

    // ---- 子类须实现 ----

    /** 查找该玩家当前打开的同类 GUI */
    protected abstract AbstractInventoryGui findExistingGui(UUID playerId);

    /** 将自身注册到活跃 GUI 表中 */
    protected abstract void putActiveGui(UUID playerId, AbstractInventoryGui gui);

    /** 从活跃 GUI 表中移除该玩家 */
    protected abstract void removeFromActiveGuis(UUID playerId);

    /** 确保当前 GUI 类型的事件监听器已注册 */
    protected abstract void ensureListenerRegistered();

    // ---- 公共生命周期 ----

    public void close() {
        if (closed) return;
        closed = true;
        GuiTickManager.getInstance(plugin).unregisterCallback(tickCallback);
    }

    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) return;
        close();
        removeFromActiveGuis(event.getPlayer().getUniqueId());
    }

    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        // 默认：取消所有拖拽到顶部栏的操作（子类可覆写以支持特定拖拽行为）
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

}
