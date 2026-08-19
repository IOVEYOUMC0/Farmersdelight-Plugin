package com.huidu.farmersdelight.loot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// Merges a vanilla chest loot table with the FarmersDelight append pools. The append resource only
// carries the pools that inject custom items; the vanilla table stays untouched. The generated
// table keeps every vanilla pool (music discs and anything new server versions add) and appends
// the FD pools, so the datapack never has to be maintained by hand for version updates.
public final class LootTableMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LootTableMerger() {
    }

    // vanillaJson: current vanilla loot table, read from the running server jar.
    // appendJson: {"pools": [fd pools...]} bundled in the plugin resources.
    // Returns the merged table as pretty-printed JSON text.
    public static String merge(String vanillaJson, String appendJson) {
        try {
            JsonNode vanilla = MAPPER.readTree(vanillaJson);
            JsonNode append = MAPPER.readTree(appendJson);
            ObjectNode merged = vanilla.deepCopy();
            ArrayNode pools = merged.withArray("pools");
            JsonNode fdPools = append.path("pools");
            if (!fdPools.isArray()) {
                throw new IllegalArgumentException("Append loot file must contain a pools array");
            }
            for (JsonNode pool : fdPools) {
                pools.add(pool.deepCopy());
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to merge loot tables", e);
        }
    }

    // Removes craftengine:item entries whose custom id no longer exists in the CraftEngine registry.
    // itemPresent must return true when the registry cannot be trusted yet (CE not finished loading),
    // otherwise an early pass would drop every FD entry. The returned json keeps the append file shape
    // so it can be fed straight into merge(); removedItemIds lists the dropped ids for logging.
    public static FilterResult filterMissingCeItems(String appendJson, Predicate<String> itemPresent) {
        try {
            JsonNode append = MAPPER.readTree(appendJson);
            ObjectNode filtered = append.deepCopy();
            ArrayNode pools = filtered.withArray("pools");
            List<String> removed = new ArrayList<>();
            for (JsonNode pool : pools) {
                JsonNode entries = pool.path("entries");
                if (!entries.isArray()) {
                    continue;
                }
                ArrayNode kept = MAPPER.createArrayNode();
                for (JsonNode entry : entries) {
                    boolean drop = false;
                    if ("craftengine:item".equals(entry.path("type").asText())) {
                        String id = entry.path("name").asText(null);
                        if (id != null && !id.isEmpty() && !itemPresent.test(id)) {
                            removed.add(id);
                            drop = true;
                        }
                    }
                    if (!drop) {
                        kept.add(entry);
                    }
                }
                ((ObjectNode) pool).set("entries", kept);
            }
            if (removed.isEmpty()) {
                return new FilterResult(appendJson, List.of());
            }
            return new FilterResult(
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(filtered), removed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to filter CE items from loot table", e);
        }
    }

    public record FilterResult(String json, List<String> removedItemIds) {
    }
}
