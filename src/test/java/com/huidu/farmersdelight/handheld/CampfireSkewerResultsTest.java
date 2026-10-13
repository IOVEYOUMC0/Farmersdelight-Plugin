package com.huidu.farmersdelight.handheld;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CampfireSkewerResultsTest {
    @Test
    void untaggedCampfireInputsNeverQueryRecipesOrOverrides() {
        AtomicInteger queries = new AtomicInteger();
        var results = new CampfireSkewerResults(() -> {
            queries.incrementAndGet();
            return new SkewerResultTable(Map.of("minecraft:beef", "minecraft:cooked_beef"));
        }, () -> {
            queries.incrementAndGet();
            throw new AssertionError("Untagged inputs must not reach the recipe cache");
        }, held -> false);

        for (Material material : new Material[]{Material.BEEF, Material.CHICKEN, Material.POTATO}) {
            assertNull(results.resultId(new PlainStack(material)));
        }
        assertEquals(0, queries.get());
    }

    @Test
    void emptyInputsNeverAskForTags() {
        var results = new CampfireSkewerResults(() -> null, () -> null, held -> {
            throw new AssertionError("Empty inputs must not query tags");
        });
        assertNull(results.resultId(null));
        assertNull(results.resultId(new PlainStack(Material.AIR)));
    }

    private static final class PlainStack extends ItemStack {
        private final Material material;

        private PlainStack(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public boolean isEmpty() {
            return material == Material.AIR;
        }
    }
}
