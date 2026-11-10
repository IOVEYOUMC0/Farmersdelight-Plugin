package com.huidu.farmersdelight.api.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packed tree is what addons get to build their own payload; these rules (typed getters with fallbacks,
 * nested trees, mutation) are what an addon compiles against.
 */
class PackedDataTreeTest {

    @Test
    void valuesRoundTripThroughTheTree() {
        PackedData data = PackedStorageApi.create()
                .putString("recipe", "brewinandchewin:beer")
                .putInt("progress", 120)
                .putLong("seed", 42L)
                .putDouble("experience", 3.5)
                .putBool("fermenting", true);

        assertFalse(data.isEmpty());
        assertTrue(data.keys().containsAll(List.of("recipe", "progress", "seed", "experience", "fermenting")));
        assertEquals("brewinandchewin:beer", data.string("recipe", "missing"));
        assertEquals(120, data.integer("progress", -1));
        assertEquals(42L, data.longValue("seed", -1L));
        assertEquals(3.5, data.decimal("experience", 0.0));
        assertTrue(data.bool("fermenting", false));
    }

    @Test
    void missingKeysAnswerTheFallback() {
        PackedData data = PackedStorageApi.create();
        assertTrue(data.isEmpty());
        assertEquals("fallback", data.string("absent", "fallback"));
        assertEquals(7, data.integer("absent", 7));
        assertEquals(7L, data.longValue("absent", 7L));
        assertEquals(0.5, data.decimal("absent", 0.5));
        assertFalse(data.bool("absent", false));
        assertNull(data.compound("absent"));
        assertNull(data.item("absent"));
    }

    @Test
    void nestedTreesAreIndependentAndRemovable() {
        PackedData data = PackedStorageApi.create();
        data.putInt("progress", 10);
        data.putCompound("tank", PackedStorageApi.create()
                .putString("fluid", "brewinandchewin:beer")
                .putInt("amount", 750));

        PackedData tank = data.compound("tank");
        assertEquals("brewinandchewin:beer", tank.string("fluid", null));
        assertEquals(750, tank.integer("amount", 0));

        data.remove("tank");
        assertNull(data.compound("tank"));
        assertEquals(10, data.integer("progress", 0));
    }

    @Test
    void keysIsASnapshotNotTheLiveView() {
        PackedData data = PackedStorageApi.create().putInt("first", 1);
        var keys = data.keys();
        data.putInt("second", 2);
        assertFalse(keys.contains("second"));
        assertTrue(data.keys().contains("second"));
    }

    @Test
    void theApiSurfaceStaysFreeOfCraftEngineTypes() throws IOException {
        for (String name : new String[]{"PackedData.java", "PackedStorageApi.java"}) {
            Path path = apiSource(name);
            String source = Files.readString(path);
            assertFalse(source.contains("momirealms"), name + " must not mention CraftEngine packages");
            assertFalse(source.contains("CompoundTag"), name + " must not expose CraftEngine NBT");
            assertFalse(source.contains("BlockEntityController"), name + " must not expose CraftEngine controllers");
        }
    }

    private static Path apiSource(String name) throws IOException {
        for (String root : new String[]{"src/main/java/com/huidu/farmersdelight/api/storage/",
                "FarmersDelight/src/main/java/com/huidu/farmersdelight/api/storage/"}) {
            Path path = Path.of(root + name);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IOException("api storage source not found: " + name);
    }
}
