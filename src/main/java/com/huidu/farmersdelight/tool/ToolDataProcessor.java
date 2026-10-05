package com.huidu.farmersdelight.tool;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;


public final class ToolDataProcessor implements ItemProcessor {

    /**
     * Logs the one-shot "neither enchantable shape was accepted" warning. The processor has no plugin handle
     * (it runs during item building and is constructed from pack settings), so the message goes through the
     * plugin logger when one is installed and to System.err otherwise — either way it is not silent.
     */
    private static final java.util.function.Consumer<String> WARN_ONCE = message ->
            System.err.println("[FarmersDelight] WARN: could not write minecraft:enchantable as an integer or "
                    + "as {value: N} for " + message + " — the item will not be enchantable");

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
            // Integer first (the component's codec is a plain int range), record form as the fallback, and a
            // one-shot WARN naming the item when neither is accepted: a silent zero is what hid this defect.
            ToolEnchantableComponent.write(enchantability,
                    value -> item.setJavaComponent(DataComponentKeys.ENCHANTABLE, value),
                    () -> WARN_ONCE.accept(item.toString()));
        }
        return item;
    }

    public void apply(ItemBuildContext context) {
        apply(context.item(), context);
    }
}
