package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the rope fence and rope fence gate onto CraftEngine display entities instead of a model
 * override.
 *
 *
 * Both blocks borrow a vanilla fence or gate state as their carrier, and CraftEngine writes the empty
 * variant model for every visual state a custom block claims. Anything that silently moved one of them
 * back to a model: override would hijack that vanilla block's appearance again, which is what
 * these tests exist to prevent: the rope fence must be drawn by element displays, its carriers must be
 * the crimson/warped family, and none of those carrier states may be claimed by another pack this repository ships.
 *
 *
 * The YAML is read with SnakeYAML rather than YamlConfiguration: the variant keys contain commas
 * ("north=true,east=false,..."), and Bukkit's dotted-path parser splits those into separate keys.
 */
class RopeFenceDisplayTest {

    private static final Path FARMERS_DELIGHT = Path.of("src/main/resources/craftengine/farmersdelight");
    private static final Path WORKSPACE = Path.of("..");

    private static final String FENCE_ID = "farmersdelight:rope_fence";
    private static final String GATE_ID = "farmersdelight:rope_fence_gate";

    private static final Pattern CARRIER = Pattern.compile("state:\\s*(minecraft:)?(crimson|warped)_(fence|fence_gate)\\[");

    @Test
    void everyRopeFenceVariantIsDrawnByElementDisplays() throws IOException {
        Map<String, Object> states = states(FENCE_ID);

        Map<String, Object> variants = section(states, "variants");
        Map<String, Object> appearances = section(states, "appearances");
        assertEquals(16, variants.size(), "a fence has 16 connection states");
        assertEquals(variants.keySet(), appearances.keySet(), "every state needs its own appearance");

        for (Map.Entry<String, Object> entry : appearances.entrySet()) {
            String variant = entry.getKey();
            Map<String, Object> appearance = asMap(entry.getValue());
            Object renderer = appearance.get("entity_renderer");
            assertTrue(renderer instanceof List<?>, "rope fence needs entity_renderer, not a model: " + variant);
            List<?> elements = (List<?>) renderer;
            assertEquals(1 + connections(variant), countItemDisplays(elements),
                    "one display per connected side plus the post: " + variant);
            assertTrue(String.valueOf(appearance.get("state")).startsWith("crimson_fence["),
                    "the carrier must come from the crimson fence family: " + variant);
        }
    }

    @Test
    void everyRopeFenceGateVariantIsDrawnByOneDisplay() throws IOException {
        Map<String, Object> states = states(GATE_ID);

        Map<String, Object> variants = section(states, "variants");
        Map<String, Object> appearances = section(states, "appearances");
        assertEquals(16, variants.size(), "a fence gate has 4 facings x in_wall x open");
        assertEquals(variants.keySet(), appearances.keySet(), "every state needs its own appearance");

        for (Map.Entry<String, Object> entry : appearances.entrySet()) {
            String variant = entry.getKey();
            Map<String, Object> appearance = asMap(entry.getValue());
            assertTrue(appearance.get("entity_renderer") instanceof Map<?, ?>,
                    "the gate is one model per state, so its renderer is a single element: " + variant);
            assertTrue(String.valueOf(appearance.get("state")).startsWith("warped_fence_gate["),
                    "the carrier must come from the warped fence gate family: " + variant);
        }
    }

    @Test
    void theCarrierStatesAreNotClaimedByAnyOtherPackWeShip() throws IOException {
        Set<String> carriers = new LinkedHashSet<>();
        for (String block : new String[]{FENCE_ID, GATE_ID}) {
            for (Object appearance : section(states(block), "appearances").values()) {
                carriers.add(String.valueOf(asMap(appearance).get("state")));
            }
        }
        assertEquals(32, carriers.size(), "16 fence plus 16 gate carriers");
        assertFalse(carriers.contains("null"));

        // Another pack claiming these states would render the rope model on its own blocks.
        List<String> offenders = new ArrayList<>();
        Path ownPack = FARMERS_DELIGHT.toAbsolutePath().normalize();
        try (Stream<Path> packRoots = Files.list(WORKSPACE)) {
            for (Path packRoot : packRoots.filter(Files::isDirectory).toList()) {
                Path configuration = packRoot.resolve("src/main/resources/craftengine").toAbsolutePath().normalize();
                if (!Files.isDirectory(configuration)) {
                    continue;
                }
                try (Stream<Path> yaml = Files.walk(configuration)) {
                    for (Path file : yaml.filter(path -> path.toString().endsWith(".yml")).toList()) {
                        Path absolute = file.toAbsolutePath().normalize();
                        if (absolute.startsWith(ownPack)) {
                            continue;
                        }
                        Matcher matcher = CARRIER.matcher(Files.readString(absolute));
                        while (matcher.find()) {
                            offenders.add(absolute + " -> " + matcher.group());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "another pack claims a crimson/warped fence or gate state; the rope carriers must be free");
    }

    @Test
    void carrierPositionsNeverCollideAcrossChunks() {
        // The restorer keys displays by packed position; a fence at y = 64 and one at y = 320 in the same
        // column must not share a key, and neither may two positions in different chunks.
        Set<Long> keys = new HashSet<>();
        for (int y : new int[]{-64, 0, 64, 320}) {
            assertTrue(keys.add(CarrierRestorer.key(8, y, 8)), "y=" + y);
        }
        assertTrue(CarrierRestorer.key(0, 64, 0) != CarrierRestorer.key(16, 64, 0));
        assertTrue(CarrierRestorer.chunkKey(-1, -1) != CarrierRestorer.chunkKey(0, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> states(String blockId) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(FARMERS_DELIGHT.resolve("configuration/rope.yml"))) {
            root = new Yaml().load(reader);
        }
        assertNotNull(root, "rope.yml did not parse");
        Map<String, Object> blocks = asMap(root.get("blocks"));
        Map<String, Object> block = asMap(blocks.get(blockId));
        assertNotNull(block, blockId);
        return section(block, "states");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertNotNull(value, key);
        return asMap(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertNotNull(value, "expected a mapping");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static int connections(String variant) {
        int count = 0;
        for (String part : variant.split(",")) {
            String[] pair = part.split("=");
            if (pair.length == 2 && Set.of("north", "east", "south", "west").contains(pair[0])
                    && Boolean.parseBoolean(pair[1])) {
                count++;
            }
        }
        return count;
    }

    private static int countItemDisplays(List<?> elements) {
        int count = 0;
        for (Object element : elements) {
            if (element instanceof Map<?, ?> map && "item_display".equals(map.get("type"))) {
                count++;
            }
        }
        return count;
    }
}
