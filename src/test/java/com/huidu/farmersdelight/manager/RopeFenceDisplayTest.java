package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
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
 * Locks the rope family onto the states of the carriers it borrows, and every borrowed state onto exactly one
 * block.
 *
 *
 * The rope fence claims all 32 crimson fence states and declares transparent, because CraftEngine then writes
 * the empty variant model for them and the rope look comes from item display elements. A claimed state that
 * declared a model instead would fight those elements, and a state that stayed unclaimed would keep the empty
 * variant out of the generated block state file and make the carrier render as a missing model.
 *
 *
 * The rope fence gate does not claim states by hand at all: it uses CraftEngine's own
 * default:block_state/fence_gate template, which claims the 16 crimson fence gate states the template's
 * powered half frees and renders the gate with a model generated from the vanilla gate template plus the rope
 * texture. So it must carry no appearances, no transparent flag and no display elements.
 *
 *
 * The crimson cover blocks borrow mangrove fence and gate states the other way round: they declare neither
 * transparent nor a model, so CraftEngine never writes an override for those states and a genuinely vanilla
 * mangrove fence keeps its own model and renders as itself. Their own display elements draw the crimson
 * fence, and because that layer sits exactly on the vanilla model of the same shape, its geometry has to be
 * widened past the vanilla box (fences) or scaled up (gates) or the two layers z-fight.
 *
 *
 * The YAML is read with SnakeYAML rather than YamlConfiguration: the variant keys contain commas
 * ("north=true,east=false,..."), and Bukkit's dotted-path parser splits those into separate keys.
 */
class RopeFenceDisplayTest {

    private static final Path FARMERS_DELIGHT = Path.of("src/main/resources/craftengine/farmersdelight");
    private static final Path MODELS = FARMERS_DELIGHT.resolve("resourcepack/assets/farmersdelight/models");
    private static final Path WORKSPACE = Path.of("..");

    private static final String ROPE_FENCE = "farmersdelight:rope_fence";
    private static final String ROPE_GATE = "farmersdelight:rope_fence_gate";
    private static final String COVER_FENCE = "farmersdelight:crimson_fence_cover";
    private static final String COVER_GATE = "farmersdelight:crimson_fence_gate_cover";
    private static final String CRIMSON_FENCE_ITEM = "minecraft:crimson_fence";
    private static final String CRIMSON_GATE_ITEM = "minecraft:crimson_fence_gate";
    private static final String MANGROVE_FENCE_ITEM = "minecraft:mangrove_fence";
    private static final String MANGROVE_GATE_ITEM = "minecraft:mangrove_fence_gate";
    private static final String BEHAVIOR_TYPE = "farmersdelight:substituting_block_item";

    private static final Pattern CARRIER =
            Pattern.compile("state:\\s*(minecraft:)?(crimson|mangrove)_fence(_gate)?\\[");

    @Test
    void theRopeFenceClaimsEveryStateOfItsCrimsonCarrier() throws IOException {
        Map<String, Object> root = rope();

        assertEquals(crimsonFenceStates(), carrierStates(root, ROPE_FENCE));
        assertEquals(32, carrierStates(root, ROPE_FENCE).size(), "16 connection shapes, wet and dry");
    }

    @Test
    void theRopeGateWritesTheTemplateStructureOverItsOwnModels() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> block = asMap(asMap(root.get("blocks")).get(ROPE_GATE));
        Map<String, Object> states = section(block, "states");

        assertFalse(states.containsKey("template"),
                "the gate writes its states out instead of handing them to the shared template");
        assertFalse(states.containsKey("arguments"), "and it passes no template arguments");

        Map<String, Object> appearances = section(states, "appearances");
        assertEquals(16, appearances.size(), "four facings times in-wall times open");
        for (Map.Entry<String, Object> entry : appearances.entrySet()) {
            Map<String, Object> appearance = asMap(entry.getValue());
            assertEquals("crimson_fence_gate[" + entry.getKey() + ",powered=true]", appearance.get("state"),
                    entry.getKey() + " claims the freed powered half of the crimson gate");
            Map<String, Object> model = section(appearance, "model");
            assertFalse(model.containsKey("generation"),
                    entry.getKey() + " has to use our own model, never a generated one");
            String path = String.valueOf(model.get("path"));
            assertTrue(Files.exists(MODELS.resolve(path.substring(path.indexOf(':') + 1) + ".json")),
                    path + " has to ship in the pack");
            assertEquals(Boolean.TRUE, model.get("uvlock"), entry.getKey() + " rotates its uvs with the gate");
        }

        Map<String, Object> variants = section(states, "variants");
        assertEquals(32, variants.size(), "both halves of our own powered property resolve");
        for (Map.Entry<String, Object> entry : variants.entrySet()) {
            assertTrue(appearances.containsKey(asMap(entry.getValue()).get("appearance")),
                    entry.getKey() + " points at one of the gate appearances");
        }
        String raw = block.toString();
        assertFalse(raw.contains("transparent"), "a modelled gate must not empty its carrier");
        assertFalse(raw.contains("entity_renderer"), "a modelled gate must not carry display elements");
    }

    @Test
    void theCoverBlocksBorrowEveryMangroveStateWithoutTouchingItsModel() throws IOException {
        Map<String, Object> root = rope();

        assertEquals(mangroveFenceStates(), carrierStates(root, COVER_FENCE));
        assertEquals(mangroveGateStates(), carrierStates(root, COVER_GATE));

        for (String blockId : new String[]{COVER_FENCE, COVER_GATE}) {
            for (Map.Entry<String, Object> entry : section(blockStates(root, blockId), "appearances").entrySet()) {
                Map<String, Object> appearance = asMap(entry.getValue());
                String variant = blockId + " " + entry.getKey();
                assertFalse(appearance.containsKey("transparent"),
                        variant + " must not empty the vanilla mangrove model");
                assertFalse(appearance.containsKey("model"),
                        variant + " must not bind a model to a mangrove state");
                assertNotNull(appearance.get("entity_renderer"),
                        variant + " must draw the crimson cover with element displays");
            }
        }
    }

    @Test
    void theClaimedCarrierSetsAreDisjointAndComplete() throws IOException {
        Map<String, Object> root = rope();
        Set<String> claimed = new LinkedHashSet<>();

        for (String blockId : new String[]{ROPE_FENCE, ROPE_GATE, COVER_FENCE, COVER_GATE}) {
            for (String state : carrierStates(root, blockId)) {
                assertTrue(claimed.add(state), "carrier state claimed by two blocks: " + state);
            }
        }

        assertEquals(112, claimed.size(), "32 crimson fence + 16 freed crimson gate + 32 + 32 mangrove, none shared");
    }

    @Test
    void everyAppearanceDrawsElementsAndEmptiesOnlyTheRopeCarrier() throws IOException {
        Map<String, Object> root = rope();

        for (String blockId : new String[]{ROPE_FENCE, COVER_FENCE, COVER_GATE}) {
            Map<String, Object> states = blockStates(root, blockId);
            Map<String, Object> appearances = section(states, "appearances");
            Map<String, Object> variants = section(states, "variants");
            assertEquals(32, appearances.size(), blockId + " claims 32 carrier states");
            assertEquals(appearances.keySet(), variants.keySet(), blockId + " needs one variant per appearance");
            boolean rope = blockId.equals(ROPE_FENCE);

            for (Map.Entry<String, Object> entry : appearances.entrySet()) {
                Map<String, Object> appearance = asMap(entry.getValue());
                String variant = blockId + " " + entry.getKey();
                assertEquals(rope, Boolean.TRUE.equals(appearance.get("transparent")),
                        variant + (rope
                                ? " claims a crimson state and has to empty its carrier model"
                                : " borrows a mangrove state and has to leave its model alone"));
                assertNotNull(appearance.get("entity_renderer"),
                        variant + " must be drawn by element displays");
                assertFalse(appearance.containsKey("model"), variant + " must not declare a model");
                assertEquals(entry.getKey(), asMap(variants.get(entry.getKey())).get("appearance"),
                        variant + " has to be the variant's appearance");
            }
        }
    }

    @Test
    void theRopeFenceKeepsItsWaterloggedPropertyWithADryDefault() throws IOException {
        Map<String, Object> properties = section(blockStates(rope(), ROPE_FENCE), "properties");
        Map<String, Object> waterlogged = asMap(properties.get("waterlogged"));

        assertEquals("boolean", waterlogged.get("type"), "the rope fence carries the real property");
        assertEquals(Boolean.FALSE, waterlogged.get("default"), "a placed rope fence has to start dry");
    }

    @Test
    void neitherGateDeclaresWaterlogged() throws IOException {
        Map<String, Object> root = rope();

        Map<String, Object> coverProperties = section(blockStates(root, COVER_GATE), "properties");
        assertEquals(Set.of("facing", "in_wall", "open", "powered"), coverProperties.keySet(),
                COVER_GATE + ": vanilla fence gates are not waterloggable, so neither is its block");

        Map<String, Object> ropeProperties = section(blockStates(root, ROPE_GATE), "properties");
        assertEquals(Set.of("facing", "in_wall", "open", "powered"), ropeProperties.keySet(),
                ROPE_GATE + ": a fence gate is not waterloggable, so it declares no waterlogged");
    }

    @Test
    void theFenceElementsFollowTheCraftEngineRotationTable() throws IOException {
        Map<String, Object> root = rope();

        assertEquals(Map.of("north", 180, "east", 270, "south", 180, "west", 270),
                fenceSideRotations(root, ROPE_FENCE));
        assertEquals(Map.of("north", 180, "east", 270, "south", 0, "west", 90),
                fenceSideRotations(root, COVER_FENCE));

        for (String blockId : new String[]{ROPE_FENCE, COVER_FENCE}) {
            Map<String, Object> post = singleFaceAppearance(root, blockId, "north").get(0);
            assertEquals(180, post.get("rotation"), blockId + ": the post follows the template rotation");
            assertEquals("1.05,1.01,1.05", post.get("scale"), blockId + ": the post keeps the template scale");
            assertEquals("0,0.0001,0", post.get("translation"), blockId + ": the post keeps the template nudge");
        }
    }

    @Test
    void theCoverGateElementsFollowTheSameRotationTable() throws IOException {
        assertEquals(Map.of("north", 0, "east", 90, "south", 180, "west", 270),
                gateRotations(rope(), COVER_GATE));
    }

    @Test
    void theCoverElementsAreWiderThanTheVanillaGeometryTheySitOn() throws IOException {
        Map<String, Object> root = rope();

        for (String name : new String[]{"crimson_fence_post", "crimson_fence_side"}) {
            Path file = MODELS.resolve("block/" + name + ".json");
            assertTrue(Files.exists(file), name + " has to be an authored element model");
            Map<String, Object> model = parse(file);
            assertFalse(model.containsKey("parent"),
                    name + " must not be a bare wrapper around the vanilla model: the two layers would"
                            + " occupy the same space and z-fight");
            List<?> elements = (List<?>) model.get("elements");
            assertNotNull(elements, name + " needs elements of its own");
            List<Double> corners = new ArrayList<>();
            for (Object element : elements) {
                for (String corner : new String[]{"from", "to"}) {
                    for (Object value : (List<?>) asMap(element).get(corner)) {
                        corners.add(((Number) value).doubleValue());
                    }
                }
            }
            assertTrue(corners.stream().anyMatch(value -> value < 0 || value > 16 || value % 1 != 0),
                    name + " has to reach past the vanilla box by a hair: " + corners);
        }

        for (Object value : section(blockStates(root, COVER_GATE), "appearances").values()) {
            Map<String, Object> element = elements(asMap(value)).get(0);
            assertNotNull(element.get("scale"),
                    COVER_GATE + " draws a vanilla-shaped gate model on top of the real one and needs a scale");
        }
    }

    @Test
    void theCrimsonItemsRedirectToTheCoversAndTheElementsShip() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> items = asMap(root.get("items"));
        Map<String, Object> blocks = asMap(root.get("blocks"));

        assertRedirect(items, blocks, CRIMSON_FENCE_ITEM, COVER_FENCE);
        assertRedirect(items, blocks, CRIMSON_GATE_ITEM, COVER_GATE);
        assertEquals(CRIMSON_FENCE_ITEM, asMap(asMap(asMap(blocks.get(COVER_FENCE)).get("settings"))
                .get("overrides")).get("item"), "the cover drops the crimson fence item");
        assertEquals(CRIMSON_GATE_ITEM, asMap(asMap(asMap(blocks.get(COVER_GATE)).get("settings"))
                .get("overrides")).get("item"), "the cover gate drops the crimson gate item");

        for (String vanilla : new String[]{MANGROVE_FENCE_ITEM, MANGROVE_GATE_ITEM}) {
            assertFalse(items.containsKey(vanilla),
                    vanilla + " has to place the vanilla block again instead of a substituted one");
        }

        Set<String> referenced = new LinkedHashSet<>();
        for (String blockId : new String[]{ROPE_FENCE, COVER_FENCE, COVER_GATE}) {
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
            Path file = MODELS.resolve(model.substring(model.indexOf(':') + 1) + ".json");
            assertTrue(Files.exists(file), model + " must ship at " + file);
        }
    }

    @Test
    void theRetiredGateElementsAreGoneAndTheGateModelsStay() throws IOException {
        Map<String, Object> root = rope();
        Map<String, Object> items = asMap(root.get("items"));
        Map<String, Object> blocks = asMap(root.get("blocks"));

        for (String itemId : new String[]{"farmersdelight:rope_fence_gate_elements",
                "farmersdelight:rope_fence_gate_open_elements",
                "farmersdelight:rope_fence_gate_wall_elements",
                "farmersdelight:rope_fence_gate_wall_open_elements"}) {
            assertFalse(items.containsKey(itemId), itemId + " retired with the gate display elements");
        }
        for (String model : new String[]{"rope_fence_gate", "rope_fence_gate_open",
                "rope_fence_gate_wall", "rope_fence_gate_wall_open"}) {
            assertTrue(Files.exists(MODELS.resolve("block/" + model + ".json")),
                    model + " is the model the gate appearances point at and has to ship");
        }
        for (String blockId : new String[]{"farmersdelight:mangrove_fence",
                "farmersdelight:mangrove_fence_gate"}) {
            assertFalse(blocks.containsKey(blockId),
                    blockId + " used to empty the mangrove carrier states and must be gone");
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
                "another pack claims a crimson or mangrove fence state; our carriers must be free");
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

    /** The element displays one fence uses for the appearance whose only connection is that face. */
    private static List<Map<String, Object>> singleFaceAppearance(Map<String, Object> root, String blockId,
                                                                  String face) {
        for (Object value : section(blockStates(root, blockId), "appearances").values()) {
            Map<String, Object> appearance = asMap(value);
            if (facesOf(String.valueOf(appearance.get("state"))).equals(Set.of(face))) {
                return elements(appearance);
            }
        }
        throw new AssertionError(blockId + " has no appearance connected only to " + face);
    }

    @Test
    void theRopeFenceStopsBeingAWoodenFenceSoVanillaFencesDoNotConnect() throws IOException {
        Map<String, Object> blocks = asMap(rope().get("blocks"));

        Map<String, Object> fence = asMap(blocks.get(ROPE_FENCE));
        List<?> tags = (List<?>) section(fence, "settings").get("tags");
        assertTrue(tags.contains("minecraft:fences"),
                "staying in minecraft:fences is what keeps rope fences joining each other");
        assertFalse(tags.contains("minecraft:wooden_fences"),
                "a wooden fence tag makes vanilla fences connect to the rope fence and cull their own sides");
        assertTrue(tags.contains("farmersdelight:rope_fences"), "the rope fence joins its own group");
        assertEquals("farmersdelight:rope_fences", asMap(fence.get("behavior")).get("connectable_block_tag"),
                "the rope fence only connects to its own group, exactly like the mod's CrossCollisionBlock");

        List<?> gateTags = (List<?>) section(asMap(blocks.get(ROPE_GATE)), "settings").get("tags");
        assertTrue(gateTags.contains("farmersdelight:rope_fences"), "the rope gate joins the rope fence group");

        List<?> coverTags = (List<?>) section(asMap(blocks.get(COVER_FENCE)), "settings").get("tags");
        assertTrue(coverTags.contains("minecraft:wooden_fences"),
                "the cover stands for a placed vanilla crimson fence and keeps vanilla fence semantics");
        assertEquals("minecraft:wooden_fences",
                asMap(asMap(blocks.get(COVER_FENCE)).get("behavior")).get("connectable_block_tag"),
                "the cover has to connect like a vanilla fence does");
        List<?> coverGateTags = (List<?>) section(asMap(blocks.get(COVER_GATE)), "settings").get("tags");
        assertTrue(coverGateTags.contains("minecraft:fence_gates"),
                "the cover gate stands for a placed vanilla crimson gate and keeps vanilla gate semantics");
    }

    private static Map<String, Integer> fenceSideRotations(Map<String, Object> root, String blockId) {
        Map<String, Integer> rotations = new LinkedHashMap<>();
        for (String face : List.of("north", "east", "south", "west")) {
            List<Map<String, Object>> elements = singleFaceAppearance(root, blockId, face);
            Map<String, Object> side = elements.stream()
                    .filter(element -> !String.valueOf(element.get("item")).contains("post"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(blockId + " " + face + " has no side element"));
            rotations.put(face, (Integer) side.get("rotation"));
        }
        return rotations;
    }

    private static Map<String, Integer> gateRotations(Map<String, Object> root, String blockId) {
        Map<String, Integer> rotations = new LinkedHashMap<>();
        for (Object value : section(blockStates(root, blockId), "appearances").values()) {
            Map<String, Object> appearance = asMap(value);
            String facing = facingOf(String.valueOf(appearance.get("state")));
            List<Map<String, Object>> elements = elements(appearance);
            assertEquals(1, elements.size(), blockId + " draws its gate with one element");
            Integer rotation = (Integer) elements.get(0).get("rotation");
            Integer previous = rotations.put(facing, rotation);
            if (previous != null) {
                assertEquals(previous, rotation, blockId + " turns every " + facing + " appearance the same way");
            }
        }
        return rotations;
    }

    private static void collectElementItems(Object renderer, Set<String> out) {
        for (Map<String, Object> element : elements(Map.of("entity_renderer", renderer))) {
            assertEquals("item_display", element.get("type"), "only element displays draw these blocks");
            out.add(String.valueOf(element.get("item")));
        }
    }

    private static List<Map<String, Object>> elements(Map<String, Object> appearance) {
        Object renderer = appearance.get("entity_renderer");
        return renderer instanceof List<?> list
                ? list.stream().map(RopeFenceDisplayTest::asMap).toList()
                : List.of(asMap(renderer));
    }

    private static Set<String> facesOf(String state) {
        Set<String> connected = new LinkedHashSet<>();
        for (Map.Entry<String, String> part : propertiesOf(state).entrySet()) {
            if (List.of("north", "east", "south", "west").contains(part.getKey())
                    && "true".equals(part.getValue())) {
                connected.add(part.getKey());
            }
        }
        return connected;
    }

    private static String facingOf(String state) {
        return propertiesOf(state).get("facing");
    }

    private static Map<String, String> propertiesOf(String state) {
        String inner = state.substring(state.indexOf('[') + 1, state.lastIndexOf(']'));
        Map<String, String> properties = new LinkedHashMap<>();
        for (String pair : inner.split(",")) {
            String[] parts = pair.split("=");
            properties.put(parts[0], parts[1]);
        }
        return properties;
    }

    private static Set<String> carrierStates(Map<String, Object> root, String blockId) {
        Set<String> states = new LinkedHashSet<>();
        for (Object value : section(blockStates(root, blockId), "appearances").values()) {
            String state = String.valueOf(asMap(value).get("state"));
            states.add(state.startsWith("minecraft:") ? state : "minecraft:" + state);
        }
        return states;
    }

    private static Set<String> crimsonFenceStates() {
        return fenceStates("crimson");
    }

    private static Set<String> mangroveFenceStates() {
        return fenceStates("mangrove");
    }

    private static Set<String> mangroveGateStates() {
        return gateStates("mangrove");
    }

    private static Set<String> fenceStates(String wood) {
        Set<String> states = new LinkedHashSet<>();
        for (boolean waterlogged : new boolean[]{false, true}) {
            for (boolean north : new boolean[]{false, true}) {
                for (boolean east : new boolean[]{false, true}) {
                    for (boolean south : new boolean[]{false, true}) {
                        for (boolean west : new boolean[]{false, true}) {
                            states.add("minecraft:" + wood + "_fence[east=" + east + ",north=" + north
                                    + ",south=" + south + ",waterlogged=" + waterlogged + ",west=" + west + "]");
                        }
                    }
                }
            }
        }
        return states;
    }

    private static Set<String> gateStates(String wood) {
        Set<String> states = new LinkedHashSet<>();
        for (String facing : new String[]{"north", "east", "south", "west"}) {
            for (boolean inWall : new boolean[]{false, true}) {
                for (boolean open : new boolean[]{false, true}) {
                    for (boolean powered : new boolean[]{false, true}) {
                        states.add("minecraft:" + wood + "_fence_gate[facing=" + facing + ",in_wall=" + inWall
                                + ",open=" + open + ",powered=" + powered + "]");
                    }
                }
            }
        }
        return states;
    }

    private static Map<String, Object> rope() throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(FARMERS_DELIGHT.resolve("configuration/rope.yml"))) {
            root = new Yaml().load(reader);
        }
        assertNotNull(root, "configuration/rope.yml did not parse");
        return root;
    }

    private static Map<String, Object> parse(Path file) throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file)) {
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
