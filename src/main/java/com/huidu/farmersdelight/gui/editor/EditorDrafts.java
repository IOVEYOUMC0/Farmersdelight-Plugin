package com.huidu.farmersdelight.gui.editor;

import org.bukkit.inventory.InventoryHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class EditorDrafts {
    private static final Map<UUID, LinkedHashMap<String, InventoryHolder>> DRAFTS = new ConcurrentHashMap<>();
    private EditorDrafts() { }

    static void remember(UUID player, String key, InventoryHolder draft) {
        var drafts = DRAFTS.computeIfAbsent(player, ignored -> new LinkedHashMap<>());
        drafts.remove(key);
        drafts.put(key, draft);
        while (drafts.size() > 8) drafts.remove(drafts.keySet().iterator().next());
    }

    static InventoryHolder get(UUID player, String key) {
        var drafts = DRAFTS.get(player);
        return drafts == null ? null : drafts.get(key);
    }

    static void discard(UUID player, String key, InventoryHolder expected) {
        var drafts = DRAFTS.get(player);
        if (drafts != null) drafts.remove(key, expected);
    }

    static void clear(UUID player) { DRAFTS.remove(player); }
    static void clear() { DRAFTS.clear(); }
}
