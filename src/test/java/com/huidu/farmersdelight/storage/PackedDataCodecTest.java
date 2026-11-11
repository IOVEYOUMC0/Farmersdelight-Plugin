package com.huidu.farmersdelight.storage;

import com.huidu.farmersdelight.api.storage.PackedData;
import com.huidu.farmersdelight.api.storage.PackedStorageApi;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The api tree survives a custom-data round trip: each value keeps its shape instead of degrading to a string or
 * a different number type. NBT has no boolean, so booleans travel as a byte and are read back as booleans.
 */
class PackedDataCodecTest {

    @Test
    void everyValueShapeSurvivesATagRoundTrip() {
        PackedData source = PackedStorageApi.create()
                .putString("recipe", "brewinandchewin:beer")
                .putInt("progress", 42)
                .putLong("seed", 9_000_000_000L)
                .putDouble("experience", 2.5D)
                .putBool("fermenting", true)
                .putBool("paused", false);

        PackedData read = PackedDataCodec.fromTag(PackedDataCodec.toTag(source));

        assertEquals("brewinandchewin:beer", read.string("recipe", null));
        assertEquals(42, read.integer("progress", -1));
        assertEquals(9_000_000_000L, read.longValue("seed", -1L));
        assertEquals(2.5D, read.decimal("experience", 0.0D));
        assertTrue(read.bool("fermenting", false));
        assertFalse(read.bool("paused", true));
    }

    @Test
    void integersStayIntegersAndBooleansStayBooleans() {
        CompoundTag tag = PackedDataCodec.toTag(PackedStorageApi.create()
                .putInt("count", 7)
                .putBool("flag", true));

        assertEquals(7, tag.getInt("count", 0));
        assertTrue(tag.getBoolean("flag"), "a boolean is stored as a byte, never as a number");
    }

    @Test
    void nestedTreesSurviveTheRoundTrip() {
        PackedData source = PackedStorageApi.create()
                .putCompound("tank", PackedStorageApi.create()
                        .putString("fluid", "brewinandchewin:beer")
                        .putInt("amount", 750)
                        .putBool("full", false));

        PackedData tank = PackedDataCodec.fromTag(PackedDataCodec.toTag(source)).compound("tank");

        assertEquals("brewinandchewin:beer", tank.string("fluid", null));
        assertEquals(750, tank.integer("amount", 0));
        assertFalse(tank.bool("full", true));
    }

    @Test
    void anEmptyTreeBecomesAnEmptyCompound() {
        assertTrue(PackedDataCodec.toTag(PackedStorageApi.create()).isEmpty());
        assertTrue(PackedDataCodec.fromTag(new CompoundTag()).isEmpty());
    }

    @Test
    void valuesWithoutABooleanOrNumberShapeAreKeptAsText() {
        PackedData read = PackedDataCodec.fromTag(PackedDataCodec.toTag(PackedStorageApi.create()
                .putString("note", "true")));

        assertEquals("true", read.string("note", null));
        assertFalse(read.bool("note", false), "a string that spells true is still a string");
    }
}
