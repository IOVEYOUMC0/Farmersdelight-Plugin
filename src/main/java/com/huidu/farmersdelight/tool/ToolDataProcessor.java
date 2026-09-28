package com.huidu.farmersdelight.tool;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;

import java.util.Map;

public final class ToolDataProcessor implements ItemProcessor {

    private final int maxDurability;
    private final int enchantability;

    public ToolDataProcessor(int maxDurability, int enchantability) {
        this.maxDurability = Math.max(1, maxDurability);
        this.enchantability = Math.max(0, enchantability);
    }

    // Both spellings are kept and neither carries @Override: CraftEngine changed ItemProcessor.apply
    // from (Item, ItemBuildContext) returning Item to (ItemBuildContext) returning void, so exactly one
    // of them implements the interface depending on which jar is present, and the annotation would be a
    // compile error against the other.
    public Item apply(Item item, ItemBuildContext context) {
        // Settings processors run before CraftEngine finishes merging the item's data section, so
        // maxStackSize() can still expose the base material's default of 64 even when YAML specifies 1.
        // Enforce the damageable-item invariant without treating that intermediate value as a bad config.
        item.maxStackSize(1);
        item.maxDamage(maxDurability);
        item.damage(0);
        if (enchantability > 0) {
            try {
                // 1.21.5+: enchantable changed from int to {"value": int}
                item.setJavaComponent(DataComponentKeys.ENCHANTABLE, Map.of("value", enchantability));
            } catch (RuntimeException e) {
                // 1.21.4 and earlier: enchantable is a plain int
                item.setJavaComponent(DataComponentKeys.ENCHANTABLE, enchantability);
            }
        }
        return item;
    }

    public void apply(ItemBuildContext context) {
        apply(context.item(), context);
    }
}
