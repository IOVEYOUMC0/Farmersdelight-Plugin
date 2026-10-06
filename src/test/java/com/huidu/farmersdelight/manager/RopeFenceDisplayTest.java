package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Locks the rope fence and rope fence gate onto the mangrove fence and fence gate carriers.
 *
 *
 * Both rope blocks and the two vanilla-looking blocks that replace the mangrove fence and gate items claim
 * every state of the two carriers between them, and each of those appearances declares transparent so the
 * carrier model is emptied and drawn by element displays instead. A state left unclaimed would keep the
 * empty variant out of the generated block state file and the carrier block would render as a missing
 * model, so the coverage assertion below is the guard for that failure mode: it must stay exact.
 *
 *
 * The YAML is read with SnakeYAML rather than YamlConfiguration: the variant keys contain commas
 * ("north=true,east=false,..."), and Bukkit's dotted-path parser splits those into separate keys.
 */
class RopeFenceDisplayTest {

    private static final Path FARMERS_DELIGHT = Path.of("src/main/resources/craftengine/farmersdelight");
    private static final Path WORKSPACE = Path.of("..");
    private static final Path CARRIER_SOURCE = Path.of("src/main/java/com/huidu/farmersdelight/manager/CarrierRestorer.java");
    private static final Path REGISTRAR_SOURCE = Path.of("src/main/java/com/huidu/farmersdelight/registry/BehaviorRegistrar.java");
    private static final Path CONSTANTS_SOURCE = Path.of("src/main/java/com/huidu/farmersdelight/util/Constants.java");

    private static final String ROPE_FENCE = "farmersdelight:rope_fence";
    private static final String VANILLA_FENCE = "farmersdelight:mangrove_fence";
    private static final String ROPE_GATE = "farmersdelight:rope_fence_gate";
    private static final String VANILLA_GATE = "farmersdelight:mangrove_fence_gate";

    private static final String BEHAVIOR_TYPE = "farmersdelight:substituting_block_item";
    private static final String VANILLA_FENCE_ITEM = "minecraft:mangrove_fence";
    private static final String VANILLA_GATE_ITEM = "minecraft:mangrove_fence_gate";

    private static final Pattern CARRIER = Pattern.compile("state:\\s*(minecraft:)?(mangrove)_(fence|fence_gate)\\[");

    @Test
    void everyCarrierStateIsClaimedEmptiedAndDrawn() throws IOException {
        Map<String, Object> root = rope();
        Set<String> claimed = new LinkedHashSet<>();

        for (String blockId : blocks()) {
            Map<String, Object> appearances = section(blockStates(root, blockId), "appearances");
            assertFalse(appearances.isEmpty(), blockId + " has no appearance");
            for (Map.Entry<String, Object> entry : appearances.entrySet()) {
                String variant = entry.getKey();
                Map<String, Object> appearance = asMap(entry.getValue());
                assertTrue(Boolean.TRUE.equals(appearance.get("transparent")),
                        blockId + " " + variant + " must empty its carrier model with transparent");
                assertNotNull(appearance.get("entity_renderer"),
                        blockId + " " + variant + " must be drawn by an element display");
                assertFalse(appearance.containsKey("model"),
                        blockId + " " + variant + " must not declare a model");
                claimed.add(normalize(String.valueOf(appearance.get("state"))));
            }
        }

        assertEquals(expectedCarriers(), claimed,
                "every mangrove fence and gate state must be claimed by exactly one appearance");
        assertEquals(64, claimed.size(), "32 fence states plus 32 gate states");
    }

    @Test
    void eachBlockKeepsItsOwnHalfOfTheCarrierStates() throws IOException {
        Map<String, Object> root = rope();

        assertCarrierHalf(root, ROPE_FENCE, "mangrove_fence[", "waterlogged=true");
        assertCarrierHalf(root, VANILLA_FENCE, "mangrove_fence[", "waterlogged=false");
        assertCarrierHalf(root, ROPE_GATE, "mangrove_fence_gate[", "powered=true");
        assertCarrierHalf(root, VANILLA_GATE, "mangrove_fence_gate[", "powered=false");

        Map<String, Object> variants = section(blockStates(root, ROPE_FENCE), "variants");
        assertEquals(16, variants.size(), "a fence has 16 connection states");
        assertEquals(variants.keySet(), section(blockStates(root, ROPE_FENCE), "appearances").keySet(),
                "every state needs its own appearance");
    }

    @Test
    void noAppearanceKeepsACrimsonOrWarpedCarrier() throws IOException {
        String yaml = Files.readString(FARMERS_DELIGHT.resolve("configuration/rope.yml"));
        assertFalse(yaml.contains("crimson_fence["), "the crimson fence carrier is gone");
        assertFalse(yaml.contains("warped_fence_gate["), "the warped fence gate carrier is gone");
    }

    @Test
    void carrierRestorerMatchesTheClaimedCarriers() throws IOException, ReflectiveOperationException {
        Set<String> claimed = new LinkedHashSet<>();
        Map<String, Object> root = rope();
        for (String blockId : blocks()) {
            for (Object appearance : section(blockStates(root, blockId), "appearances").values()) {
                claimed.add(normalize(String.valueOf(asMap(appearance).get("state"))));
            }
        }

        assertEquals(64, claimed.size(), "four appearances families of 16");
        assertEquals(claimed, hijackedStates(),
                "the restorer must recognise exactly the carrier states the pack claims");
    }

    @Test
    void carrierRestorerNoLongerKnowsCrimsonOrWarped() throws IOException {
        String source = Files.readString(CARRIER_SOURCE);
        assertTrue(source.contains("Material.MANGROVE_FENCE"), "the fence carrier material check");
        assertTrue(source.contains("Material.MANGROVE_FENCE_GATE"), "the gate carrier material check");
        assertFalse(source.contains("CRIMSON"), "no crimson material may stay behind");
        assertFalse(source.contains("WARPED"), "no warped material may stay behind");
        assertFalse(Files.readString(FARMERS_DELIGHT.resolve("configuration/rope.yml")).contains("RealDisplayCuller"),
                "the carrier displays belong to CraftEngine renderers; our own client culler is not wired into the pack");
    }

    @Test
    void vanillaFenceItemsAreRedirectedThroughOurOwnBehavior() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> items = asMap(root.get("items"));
        Map<String, Object> blocks = asMap(root.get("blocks"));

        assertRedirect(items, blocks, VANILLA_FENCE_ITEM, VANILLA_FENCE);
        assertRedirect(items, blocks, VANILLA_GATE_ITEM, VANILLA_GATE);


    @Test
    void everyElementItemPointsAtAModelThatShips() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> items = asMap(root.get("items"));
        Path models = FARMERS_DELIGHT.resolve("resourcepack/assets/farmersdelight/models");

        Set<String> referenced = new LinkedHashSet<>();
        for (String blockId : blocks()) {
            for (Object appearance : section(blockStates(root, blockId), "appearances").values()) {
                collectElementItems(asMap(appearance).get("entity_renderer"), referenced);
            }
        }
        assertFalse(referenced.isEmpty(), "an appearance without an element display draws nothing");

        for (String itemId : referenced) {
            Map<String, Object> item = asMap(items.get(itemId));
            assertNotNull(item, itemId + " is not declared as an item");
            assertEquals(Boolean.TRUE, asMap(item.get("data")).get("hidden"),
                    itemId + " is an element carrier and must stay hidden");
            String model = String.valueOf(item.get("model"));
            Path file = models.resolve(model.substring(model.indexOf(':') + 1) + ".json");
            assertTrue(Files.exists(file), model + " must ship at " + file);
        }
    }

    @Test
    void connectionsStayInTheWoodenFenceFamily() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> blocks = asMap(root.get("blocks"));

        for (String blockId : new String[]{ROPE_FENCE, VANILLA_FENCE}) {
            Map<String, Object> block = asMap(blocks.get(blockId));
            Map<String, Object> behavior = asMap(block.get("behavior"));
            assertEquals("fence_block", behavior.get("type"), blockId + " is a fence");
            assertEquals("minecraft:wooden_fences", behavior.get("connectable_block_tag"),
                    blockId + " connects to the wooden fence family, which is also the family it belongs to");
            List<?> tags = (List<?>) asMap(block.get("settings")).get("tags");
            assertTrue(tags.contains("minecraft:fences"), blockId + " must stay in minecraft:fences");
            assertTrue(tags.contains("minecraft:wooden_fences"), blockId + " must stay in minecraft:wooden_fences");
        }

        for (String blockId : new String[]{ROPE_GATE, VANILLA_GATE}) {
            assertEquals("fence_gate_block", asMap(asMap(blocks.get(blockId)).get("behavior")).get("type"),
                    blockId + " is a fence gate");
        }
    }

    @Test
    void theCarrierStatesAreNotClaimedByAnyOtherPackWeShip() throws IOException {
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
                "another pack claims a mangrove fence or gate state; our carriers must be free");
    }

    @Test
    void carrierPositionsNeverCollideAcrossChunks() {
        // The restorer keys displays by packed position; a fence at y = 64 and one at y = 320 in the same
        // column must not share a key, and neither may two positions in different chunks.
        Set<Long> keys = new LinkedHashSet<>();
        for (int y : new int[]{-64, 0, 64, 320}) {
            assertTrue(keys.add(CarrierRestorer.key(8, y, 8)), "y=" + y);
        }
        assertTrue(CarrierRestorer.key(0, 64, 0) != CarrierRestorer.key(16, 64, 0));
        assertTrue(CarrierRestorer.chunkKey(-1, -1) != CarrierRestorer.chunkKey(0, 0));
    }

    private static void assertCarrierHalf(Map<String, Object> root, String blockId, String carrier,
                                          String marker) throws IOException {
        Map<String, Object> appearances = section(blockStates(root, blockId), "appearances");
        assertEquals(16, appearances.size(), blockId + " claims 16 carrier states");
        for (Map.Entry<String, Object> entry : appearances.entrySet()) {
            String state = String.valueOf(asMap(entry.getValue()).get("state"));
            assertTrue(state.startsWith(carrier), blockId + " " + entry.getKey() + " carrier family: " + state);
            assertTrue(state.contains(marker), blockId + " " + entry.getKey() + " carrier marker: " + state);
        }
    }

    private static void assertRedirect(Map<String, Object> items, Map<String, Object> blocks,
                                       String itemId, String blockId) {
        Map<String, Object> item = asMap(items.get(itemId));
        Map<String, Object> behavior = asMap(item.get("behavior"));
        assertEquals(BEHAVIOR_TYPE, behavior.get("type"),
                itemId + " must use our behavior type, not a built-in block item");
        assertEquals(blockId, String.valueOf(behavior.get("block")), itemId + " places the matching block");
        assertNotNull(blocks.get(blockId), blockId + " must be a real block");
    }

    private static void collectElementItems(Object renderer, Set<String> out) {
        if (renderer instanceof List<?> list) {
            for (Object element : list) {
                collectElementItems(element, out);
            }
            return;
        }
        Map<String, Object> map = asMap(renderer);
        assertEquals("item_display", map.get("type"), "only element displays draw these blocks");
        out.add(String.valueOf(map.get("item")));
    }

    private static Set<String> expectedCarriers() {
        Set<String> states = new LinkedHashSet<>();
        for (boolean waterlogged : new boolean[]{false, true}) {
            for (boolean north : new boolean[]{false, true}) {
                for (boolean east : new boolean[]{false, true}) {
                    for (boolean south : new boolean[]{false, true}) {
                        for (boolean west : new boolean[]{false, true}) {
                            states.add("minecraft:mangrove_fence[east=" + east + ",north=" + north
                                    + ",south=" + south + ",waterlogged=" + waterlogged + ",west=" + west + "]");
                        }
                    }
                }
            }
        }
        for (boolean powered : new boolean[]{false, true}) {
            for (String facing : new String[]{"north", "east", "south", "west"}) {
                for (boolean inWall : new boolean[]{false, true}) {
                    for (boolean open : new boolean[]{false, true}) {
                        states.add("minecraft:mangrove_fence_gate[facing=" + facing + ",in_wall=" + inWall
                                + ",open=" + open + ",powered=" + powered + "]");
                    }
                }
            }
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> hijackedStates() throws ReflectiveOperationException {
        Field field = CarrierRestorer.class.getDeclaredField("HIJACKED");
        field.setAccessible(true);
        return new LinkedHashSet<>((Set<String>) field.get(null));
    }

    private static String[] blocks() {
        return new String[]{ROPE_FENCE, VANILLA_FENCE, ROPE_GATE, VANILLA_GATE};
    }

    private static String normalize(String state) {
        return state.startsWith("minecraft:") ? state : "minecraft:" + state;
    }

    private static Map<String, Object> rope() throws IOException {
        return rope("configuration/rope.yml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rope(String file) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(FARMERS_DELIGHT.resolve(file))) {
            root = new Yaml().load(reader);
        }
        assertNotNull(root, file + " did not parse");
        return root;
    }

    private static Map<String, Object> blockStates(Map<String, Object> root, String blockId) {
        Map<String, Object> blocks = asMap(root.get("blocks"));
        Map<String, Object> block = asMap(blocks.get(blockId));
        assertNotNull(block, blockId + " is not declared");
        return section(block, "states");
    }

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
}
