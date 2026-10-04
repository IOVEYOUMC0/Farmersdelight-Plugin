package com.huidu.farmersdelight.recipe;

import com.google.gson.JsonElement;
import com.huidu.farmersdelight.api.util.CeItemInterop;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.GsonHelper;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialization support so recipe results and containers keep the full NBT of a real item (custom
 * components, MMOItems data) instead of being flattened to an id + count.
 *
 * A recipe item declared as a map accepts two shapes:
 *   item: "minecraft:diamond"
 *   count: 1
 *   nbt: "<base64 of the whole item, written by the in-game editor>"
 * and the CraftEngine style used by CE's own items.yml:
 *   components:
 *     minecraft:custom_name: "(json) {\"text\":\"Diamond of Power\"}"
 *     minecraft:custom_data: "(snbt) {MMOITEMS_ITEM_ID: 'DIAMOND'}"
 *
 * nbt wins over components when both are present; components is meant for hand-written recipes.
 */
public final class RecipeItemCodec {

    // CE 26.8.2 exposes core.util.TagParser and its proxy artifact. Resolve the parser lazily so the
    // plugin does not link parser classes during config bootstrap.
    private static volatile Method snbtParseMethod;
    private static volatile Object snbtParseTarget;

    private RecipeItemCodec() {
    }

    /** Full-item base64 snapshot. */
    public static String itemToBase64(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        byte[] bytes = ItemStackUtils.toBytes(item);
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    // Decoded snapshots, keyed by the encoded string the recipe owns. The ingredient matcher calls this
    // on every comparison with the same constant input, and each miss is a Base64 decode plus a full
    // ItemStack deserialisation. Bounded by the number of NBT-carrying ingredients that were loaded.
    private static final Map<String, ItemStack> DECODED = new ConcurrentHashMap<>();
    private static final ItemStack UNDECODABLE = new ItemStack(Material.AIR);

    public static ItemStack itemFromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        ItemStack cached = DECODED.computeIfAbsent(encoded, RecipeItemCodec::decodeFromBase64);
        // The cache holds a sentinel for input that does not decode, so a bad string is not retried and
        // does not have to be representable as null in the map.
        return cached == UNDECODABLE ? null : cached.clone();
    }

    /** Drops the decode cache; called when the recipe set is rebuilt. */
    public static void clearDecodeCache() {
        DECODED.clear();
    }

    private static ItemStack decodeFromBase64(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.trim());
            ItemStack item = ItemStackUtils.fromBytes(bytes);
            return (item == null || item.getType().isAir()) ? UNDECODABLE : item;
        } catch (Exception e) {
            return UNDECODABLE;
        }
    }

    /**
     * True when the item differs from a freshly built default of its own id, i.e. it carries extra data
     * worth a snapshot. CE custom items are excluded; their data comes from the item definition, so the
     * id alone round-trips it. MMOItems items are excluded too: their full identity is the
     * mmoitems:<TYPE>:<ID> id and MMOItems rebuilds the item from its own data, so no NBT snapshot is kept.
     */
    public static boolean carriesExtraData(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (ItemUtils.isCustomItem(item)) {
            return false;
        }
        if (MMOItemsCompat.getItemId(item) != null) {
            return false;
        }
        String id = RecipeSerializer.itemIdString(item);
        ItemStack rebuilt = ItemUtils.createItem(id);
        if (rebuilt == null) {
            return false;
        }
        rebuilt.setAmount(item.getAmount());
        byte[] original = ItemStackUtils.toBytes(item);
        byte[] plain = ItemStackUtils.toBytes(rebuilt);
        return original == null || plain == null || !Arrays.equals(original, plain);
    }

    /**
     * Serialize an item for a recipe body when it needs a snapshot: {item, count?, nbt}. Returns null when
     * the plain id string + count is enough, so ordinary recipes keep the short readable form.
     */
    public static Map<String, Object> snapshotIfCustom(ItemStack item) {
        if (!carriesExtraData(item)) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("item", RecipeSerializer.itemIdString(item));
        if (item.getAmount() > 1) {
            map.put("count", item.getAmount());
        }
        map.put("nbt", itemToBase64(item));
        return map;
    }

    /**
     * Build an item from a recipe map: nbt snapshot first, then item id + count + CE-style components.
     * Returns null when nothing usable is present.
     */
    public static ItemStack deserializeItem(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object nbtValue = map.get("nbt");
        if (nbtValue != null) {
            ItemStack fromNbt = itemFromBase64(nbtValue.toString());
            if (fromNbt != null) {
                fromNbt.setAmount(Math.max(1, countOf(map, 1)));
                return fromNbt;
            }
        }

        Object itemValue = map.get("item");
        if (itemValue == null) {
            return null;
        }
        ItemStack item = ItemUtils.createItem(itemValue.toString());
        if (item == null) {
            return null;
        }
        item.setAmount(Math.max(1, countOf(map, 1)));

        Object componentsValue = map.get("components");
        if (componentsValue instanceof Map<?, ?> components) {
            item = applyComponents(item, coerceStringMap(components));
        }
        return item;
    }

    /**
     * Apply a CE-style components map onto a freshly built item. "(snbt) ..." and "(json) ..." prefixes
     * parse the value; plain values go through the platform converter. The parsed value is handed to CE's
     * setComponent, which dispatches on its concrete type.
     */
    public static ItemStack applyComponents(ItemStack base, Map<String, Object> components) {
        if (components == null || components.isEmpty()) {
            return base;
        }
        ItemStack working = base.clone();
        BukkitItem wrapped = BukkitItemManager.instance().wrap(working);
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            Key type = Key.of(entry.getKey());
            Object value = parseComponentValue(entry.getValue());
            if (value == null) {
                continue;
            }
            if (DataComponentKeys.CUSTOM_DATA.equals(type)) {
                // custom_data carries CraftEngine's own item identity on CE items, so merge the declared
                // keys into whatever is already there instead of replacing the whole component.
                setCustomDataMerged(wrapped, value);
            } else {
                wrapped.setComponent(type, value);
            }
        }
        ItemStack result = CeItemInterop.toBukkitStack(wrapped);
        return result != null ? result : base;
    }

    private static void setCustomDataMerged(BukkitItem wrapped, Object value) {
        if (!(value instanceof CompoundTag incoming)) {
            // NMS-form SNBT result: no merging done by FD — replace the whole component.
            wrapped.setComponent(DataComponentKeys.CUSTOM_DATA, value);
            return;
        }
        CompoundTag merged = new CompoundTag();
        Tag existing = wrapped.getSparrowTag(DataComponentKeys.CUSTOM_DATA);
        if (existing instanceof CompoundTag compound) {
            for (Map.Entry<String, Tag> entry : compound.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Tag> entry : incoming.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        wrapped.setComponent(DataComponentKeys.CUSTOM_DATA, merged);
    }

    private static Object parseComponentValue(Object value) {
        try {
            if (value instanceof String str) {
                if (str.startsWith("(snbt) ")) {
                    return parseSnbt(str.substring("(snbt) ".length()));
                }
                if (str.startsWith("(json) ")) {
                    JsonElement element = GsonHelper.get().fromJson(str.substring("(json) ".length()), JsonElement.class);
                    return CraftEngine.instance().platform().jsonToSparrowNBT(element);
                }
            }
            return CraftEngine.instance().platform().javaToSparrowNBT(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object parseSnbt(String snbt) {
        Method method = snbtParseMethod;
        Object target = snbtParseTarget;
        if (method == null) {
            synchronized (RecipeItemCodec.class) {
                method = snbtParseMethod;
                target = snbtParseTarget;
                if (method == null) {
                    resolveSnbtParser();
                    method = snbtParseMethod;
                    target = snbtParseTarget;
                }
            }
        }
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, snbt);
        } catch (Exception e) {
            return null;
        }
    }

    private static void resolveSnbtParser() {
        try {
            Class<?> tagParser = Class.forName("net.momirealms.craftengine.core.util.TagParser");
            snbtParseMethod = tagParser.getMethod("parseTagFully", String.class);
            snbtParseTarget = null;
            return;
        } catch (Exception ignored) {
            // fall through to the proxy fallback below
        }
        try {
            Class<?> proxy = Class.forName("net.momirealms.craftengine.proxy.minecraft.nbt.TagParserProxy");
            snbtParseMethod = proxy.getMethod("parseCompoundFully", String.class);
            snbtParseTarget = proxy.getField("INSTANCE").get(null);
            return;
        } catch (Exception ignored) {
            // no supported parser: silence here would make every "(snbt)" recipe fail with no trace
        }
        snbtParseMethod = null;
        snbtParseTarget = null;
        throw new IllegalStateException(
                "Unsupported CraftEngine build: no SNBT TagParser found (" +
                "net.momirealms.craftengine.core.util.TagParser / " +
                "net.momirealms.craftengine.proxy.minecraft.nbt.TagParserProxy missing). " +
                "Refusing to run with silently broken recipe decoding.");
    }

    private static int countOf(Map<String, Object> map, int fallback) {
        Object count = map.get("count");
        if (count == null) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(count.toString()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static Map<String, Object> coerceStringMap(Map<?, ?> raw) {
        Map<String, Object> typed = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                typed.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return typed;
    }
}
