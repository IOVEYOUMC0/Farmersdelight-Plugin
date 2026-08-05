package com.huidu.farmersdelight.tool;

import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;

/**
 * CE ItemProcessor 注入 max_damage/damage/enchantable 组件。
 * 实际最大耐久值在运行时从 ToolRegistry 读取。
 */
public final class ToolDataProcessor implements ItemProcessor {

    private final int maxDurability;
    private final int enchantability;

    public ToolDataProcessor(int maxDurability, int enchantability) {
        this.maxDurability = Math.max(1, maxDurability);
        this.enchantability = Math.max(0, enchantability);
    }

    @Override
    public Item apply(Item item, ItemBuildContext context) {
        int configuredMaxStack = item.maxStackSize();
        if (configuredMaxStack > 1) {
            I18n.logWarning("plugin.tool.stack_size_forced", "size", String.valueOf(configuredMaxStack));
        }
        item.maxStackSize(1);
        item.maxDamage(maxDurability);
        item.damage(0);
        if (enchantability > 0) {
            try {
                // 1.21.5+: enchantable changed from int to {"value": int}
                item.setJavaComponent(DataComponentKeys.ENCHANTABLE, java.util.Map.of("value", enchantability));
            } catch (RuntimeException e) {
                // 1.21.4 and earlier: enchantable is a plain int
                item.setJavaComponent(DataComponentKeys.ENCHANTABLE, enchantability);
            }
        }
        return item;
    }
}
