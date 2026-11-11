package com.huidu.farmersdelight.shutdown;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shutdown order is behaviour: a cleanup that runs before its dependencies leaves dangling state. */
class TeardownOrderTest {

    private static final List<String> EXPECTED = List.of(
            "plugin.disable_step_cleanup_cooking_pot_block_entities",
            "plugin.disable_step_cleanup_cutting_board_block_entities",
            "plugin.disable_step_cleanup_stove_block_entities",
            "plugin.disable_step_cleanup_skillet_block_entities",
            "plugin.disable_step_cleanup_tall_crops",
            "plugin.disable_step_cleanup_mushroom_colonies",
            "plugin.disable_step_cleanup_wild_rice_blocks");

    @Test
    void behaviorCleanupsKeepTheDocumentedOrder() {
        assertEquals(EXPECTED, BehaviorTeardown.ids(),
                "the ordered cleanup list changed; check the disable path before reordering");
    }

    @Test
    void stepsMatchTheIdsAndStayUnique() {
        List<TeardownStep> steps = BehaviorTeardown.steps(true);
        assertEquals(EXPECTED.size(), steps.size());
        assertEquals(EXPECTED, steps.stream().map(TeardownStep::id).toList());
        assertEquals(steps.size(), new HashSet<>(EXPECTED).size(), "duplicate cleanup id");
    }

    @Test
    void everyStepIsRunnableAndCarriesAnId() {
        for (TeardownStep step : BehaviorTeardown.steps(false)) {
            assertTrue(step.id().startsWith("plugin.disable_step_"), step.id());
            assertTrue(step instanceof Runnable);
        }
    }
}
