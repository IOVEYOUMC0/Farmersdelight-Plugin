package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.advancement.AdvancementDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The experience declaration is what turns the original mod's {@code rewards.experience} into an actual grant, so the
 * builder has to carry it into the definitions the tab is built from.
 */
class AdvancementTreeExperienceTest {

    @Test
    void experienceOverridesReachTheMatchingDefinitionsOnly() {
        AdvancementTree tree = FarmersDelightAdvancements.tree("test");
        tree.root("root", null, "root", "root.desc", null)
                .advancement("cook", "root", null, "cook", "cook.desc", "goal", 1, 2)
                .multiTask("feast", "root", null, "feast", "feast.desc", "challenge", 3, 4, List.of("a", "b"))
                .experience("cook", 50)
                .experience("feast", 200);

        assertEquals(50, experienceOf(tree, "cook"));
        assertEquals(200, experienceOf(tree, "feast"));
        assertEquals(0, experienceOf(tree, "root"), "an advancement without a declaration rewards nothing");
    }

    @Test
    void aNonPositiveDeclarationClearsTheReward() {
        AdvancementTree tree = FarmersDelightAdvancements.tree("test");
        tree.root("root", null, "root", "root.desc", null)
                .experience("root", 100)
                .experience("root", 0);

        assertEquals(0, experienceOf(tree, "root"));
    }

    @Test
    void theDeclarationAloneStillProducesDefinitions() {
        AdvancementTree tree = FarmersDelightAdvancements.tree("test");
        tree.root("root", null, "root", "root.desc", null).experience("root", 100);

        List<AdvancementDef> defs = tree.definitions();

        assertEquals(1, defs.size());
        assertEquals("root", defs.get(0).id());
        assertEquals(100, defs.get(0).experience());
    }

    private static int experienceOf(AdvancementTree tree, String id) {
        return tree.definitions().stream()
                .filter(def -> def.id().equals(id))
                .findFirst()
                .map(AdvancementDef::experience)
                .orElse(-1);
    }
}
