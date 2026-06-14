package com.huidu.farmersdelight.gui.editor;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * 所有配方编辑器 GUI（以及它们的选择器子 GUI）的共享契约。GUI 实例本身就是它自己的
 * InventoryHolder，因此 RecipeEditorListener 可以通过检查顶部物品栏的 holder
 * 将原始物品栏事件路由到它。
 *
 * <p>编辑器 GUI 从不移动真实物品：每一个 InventoryClickEvent 都会被取消，并且槽位
 * 内容始终只会以编程方式设置为副本，因此玩家的物品栏永远不会被消耗或丢失（即“点击时复制”模型）。
 */
public interface EditorGui extends InventoryHolder {

    void handleClick(InventoryClickEvent event);

    default void handleDrag(InventoryDragEvent event) {
        event.setCancelled(true);
    }

    default void handleClose(InventoryCloseEvent event) {
    }
}
