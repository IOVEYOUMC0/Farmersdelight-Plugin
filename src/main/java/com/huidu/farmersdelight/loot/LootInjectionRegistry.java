package com.huidu.farmersdelight.loot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Runtime registry for addon-contributed chest loot pools (via FarmersDelightLootInjections). Pools
// are keyed by the normalized table path ("simple_dungeon.json") so the installer merges them in the
// same pass as the bundled FD append files, including the deleted-CE-item filter.
public final class LootInjectionRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<JsonNode>> poolsByTable = new ConcurrentHashMap<>();

    // Normalizes an addon-provided table name to the resource path the installer uses: accepts
    // "simple_dungeon", "chests/simple_dungeon", "minecraft:chests/simple_dungeon" or with .json.
    public static String normalizeTable(String table) {
        String t = table.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("minecraft:")) {
            t = t.substring("minecraft:".length());
        }
        if (t.startsWith("chests/")) {
            t = t.substring("chests/".length());
        }
        if (t.endsWith(".json")) {
            t = t.substring(0, t.length() - ".json".length());
        }
        return t + ".json";
    }

    // poolsJson may be a JSON pool array or a {"pools": [...]} object, matching the bundled append
    // file shape. Returns false for malformed input so an addon can detect its failed registration.
    public boolean register(String chestTable, String poolsJson) {
        if (poolsJson == null || poolsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(poolsJson);
            JsonNode pools = node.isArray() ? node : node.path("pools");
            if (!pools.isArray()) {
                return false;
            }
            String key = normalizeTable(chestTable);
            List<JsonNode> list = poolsByTable.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            for (JsonNode pool : pools) {
                list.add(pool.deepCopy());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean unregister(String chestTable) {
        return poolsByTable.remove(normalizeTable(chestTable)) != null;
    }

    public List<String> tables() {
        return new ArrayList<>(poolsByTable.keySet());
    }

    // JSON text of every registered pool for a normalized table, shaped like the append files
    // ({"pools": [...]}); null when the table has no injections.
    public String poolsJson(String normalizedTable) {
        List<JsonNode> list = poolsByTable.get(normalizedTable);
        if (list == null || list.isEmpty()) {
            return null;
        }
        ArrayNode pools = MAPPER.createArrayNode();
        for (JsonNode pool : list) {
            pools.add(pool);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("pools", pools);
        return root.toString();
    }
}
