package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.plugin.compatibility.CompatibilityManager;
import net.momirealms.craftengine.core.plugin.compatibility.ItemSource;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

public final class CraftEngineItemSources {
    private final Supplier<CompatibilityManager> compatibility;
    private final Function<ItemStack, Item> wrap;
    private final Function<Item, ItemStack> unwrap;
    private final Map<String, ItemSource> bound = new ConcurrentHashMap<>();
    private volatile List<String> configured = List.of("mmoitems");
    private volatile List<ItemSource> sources = List.of();

    public CraftEngineItemSources(Supplier<CompatibilityManager> compatibility,
                                 Function<ItemStack, Item> wrap, Function<Item, ItemStack> unwrap) {
        this.compatibility = compatibility;
        this.wrap = wrap;
        this.unwrap = unwrap;
    }

    public synchronized void configure(List<String> plugins) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("mmoitems");
        for (String plugin : plugins) {
            if (plugin != null && !plugin.isBlank()) names.add(plugin.trim().toLowerCase(Locale.ROOT));
        }
        configured = List.copyOf(names);
        clear();
    }

    public synchronized void clear() {
        bound.clear();
        sources = List.of();
    }

    private synchronized ItemSource bind(String plugin) {
        ItemSource cached = bound.get(plugin);
        if (cached != null) return cached;
        CompatibilityManager manager = compatibility.get();
        ItemSource source = manager == null ? null : manager.getItemSource(plugin);
        // CE registers integrations during delayed enable; never remember an absent provider.
        if (source != null) {
            bound.put(plugin, source);
            List<ItemSource> ordered = new ArrayList<>(bound.values());
            ordered.sort(Comparator.comparing(ItemSource::plugin));
            sources = List.copyOf(ordered);
        }
        return source;
    }

    public ItemStack build(String itemId) {
        int separator = itemId.indexOf(':');
        if (separator <= 0 || separator == itemId.length() - 1) return null;
        String plugin = itemId.substring(0, separator).toLowerCase(Locale.ROOT);
        try {
            ItemSource source = bind(plugin);
            if (source == null) return null;
            Item item = source.build(itemId.substring(separator + 1), ItemBuildContext.empty());
            if (item == null || item.isEmpty()) return null;
            ItemStack built = unwrap.apply(item);
            return built == null || built.getType().isAir() ? null : built.clone();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    public static boolean matchesId(String identity, String requested) {
        int separator = requested.indexOf(':');
        return separator > 0 && identity.equals(requested.substring(0, separator).toLowerCase(Locale.ROOT)
                + requested.substring(separator));
    }

    public static boolean isValidId(String itemId) {
        return itemId != null && itemId.matches("^[a-z0-9_]+:[A-Za-z0-9_./:-]+$");
    }

    public String id(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        try {
            for (String plugin : configured) bind(plugin);
            if (sources.isEmpty()) return null;
            Item item = wrap.apply(stack);
            if (item == null) return null;
            for (ItemSource source : sources) {
                try {
                    if (source.plugin().equals("mmoitems")) {
                        String legacyId = MMOItemsCompat.getItemId(item);
                        if (legacyId != null) return legacyId;
                        continue;
                    }
                    String id = source.id(item);
                    if (id != null && !id.isBlank()) return source.plugin() + ":" + id;
                } catch (RuntimeException | LinkageError unavailable) {
                    // One unavailable integration must not prevent the remaining sources from identifying an item.
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
        return null;
    }
}
