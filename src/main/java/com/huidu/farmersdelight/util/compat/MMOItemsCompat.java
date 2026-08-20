package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Soft dependency on MMOItems. Recipe ids use the "mmoitems:<TYPE>:<ID>" format: tryCreate builds
// the item through MMOItems' API via reflection, getItemId reads the identity MMOItems stores in the
// item's minecraft:custom_data component. All MMOItems classes are reached reflectively so FD
// compiles and runs without MMOItems installed; every call is a no-op when the plugin is absent.
public final class MMOItemsCompat {

    private static final String PLUGIN_NAME = "MMOItems";
    private static final String PREFIX = "mmoitems:";
    // Identity keys MMOItems writes into the minecraft:custom_data component.
    private static final String KEY_ITEM_TYPE = "MMOITEMS_ITEM_TYPE";
    private static final String KEY_ITEM_ID = "MMOITEMS_ITEM_ID";

    // Reflection handles resolved once on first use; null when MMOItems is absent.
    private static volatile Boolean available;
    private static volatile Field pluginField;
    private static volatile Method getTypesMethod;
    private static volatile Method getTypeMethod;
    private static volatile Method getMMOItemMethod;
    private static volatile Method newBuilderMethod;
    private static volatile Method buildMethod;

    private MMOItemsCompat() {
    }

    public static boolean isMmoItemsId(String itemId) {
        return itemId != null && itemId.startsWith(PREFIX);
    }

    // Returns the "mmoitems:<TYPE>:<ID>" identity of an item, or null when the item carries none.
    // CraftEngine custom items are skipped: their data lives in the item definition, not custom_data.
    public static String getItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || CraftEngineItems.getCustomItemId(item) != null) {
            return null;
        }
        try {
            BukkitItem wrapped = BukkitItemManager.instance().wrap(item);
            Tag tag = wrapped.getSparrowTag(DataComponentKeys.CUSTOM_DATA);
            if (tag instanceof CompoundTag compound) {
                String type = compound.getString(KEY_ITEM_TYPE, "");
                String id = compound.getString(KEY_ITEM_ID, "");
                if (!type.isEmpty() && !id.isEmpty()) {
                    return PREFIX + type + ":" + id;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Builds the MMOItems item for a "mmoitems:<TYPE>:<ID>" id; null when MMOItems is absent, the id
    // is malformed, or the type/item does not exist.
    public static ItemStack tryCreate(String itemId) {
        if (!isMmoItemsId(itemId) || !isAvailable() || !resolveHandles()) {
            return null;
        }
        String rest = itemId.substring(PREFIX.length());
        int separator = rest.indexOf(':');
        if (separator <= 0 || separator == rest.length() - 1) {
            return null;
        }
        String typeId = rest.substring(0, separator);
        String itemName = rest.substring(separator + 1);
        try {
            Object plugin = pluginField.get(null);
            if (plugin == null) {
                return null;
            }
            Object typeManager = getTypesMethod.invoke(plugin);
            Object type = getTypeMethod.invoke(typeManager, typeId);
            if (type == null) {
                return null;
            }
            Object mmoItem = getMMOItemMethod.invoke(plugin, type, itemName);
            if (mmoItem == null) {
                return null;
            }
            Object builder = newBuilderMethod.invoke(mmoItem);
            return (ItemStack) buildMethod.invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            cached = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null
                    && Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
            available = cached;
        }
        return cached;
    }

    private static synchronized boolean resolveHandles() {
        if (pluginField != null) {
            return true;
        }
        try {
            Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
            pluginField = mmoItemsClass.getField("plugin");
            getTypesMethod = mmoItemsClass.getMethod("getTypes");
            getMMOItemMethod = mmoItemsClass.getMethod("getMMOItem",
                    Class.forName("net.Indyuce.mmoitems.api.Type"), String.class);
            Class<?> typeManagerClass = Class.forName("net.Indyuce.mmoitems.manager.TypeManager");
            getTypeMethod = typeManagerClass.getMethod("get", String.class);
            Class<?> mmoItemClass = Class.forName("net.Indyuce.mmoitems.api.item.mmoitem.MMOItem");
            newBuilderMethod = mmoItemClass.getMethod("newBuilder");
            Class<?> builderClass = Class.forName("net.Indyuce.mmoitems.api.item.build.ItemStackBuilder");
            buildMethod = builderClass.getMethod("build");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            available = false;
            return false;
        }
    }
}
