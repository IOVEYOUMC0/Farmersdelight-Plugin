package com.huidu.farmersdelight.storage;

import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packed custom-data carrier is shared with CraftEngine item identity and other plugins, so writing one
 * packed key must merge into the component instead of replacing it. These are the pure rules behind that merge.
 */
class PackedCarrierCodecMergeTest {

    @Test
    void mergingKeepsEveryKeyTheWriterDidNotTouch() {
        CompoundTag existing = new CompoundTag();
        existing.putString("craftengine:id", "brewinandchewin:keg");
        existing.putString("other-plugin", "keep-me");
        CompoundTag overlay = new CompoundTag();
        overlay.putInt("items", 3);

        CompoundTag merged = PackedCarrierCodec.merge(existing, overlay);

        assertEquals("brewinandchewin:keg", merged.getString("craftengine:id"));
        assertEquals("keep-me", merged.getString("other-plugin"));
        assertEquals(3, merged.getInt("items", 0));
    }

    @Test
    void theNewerValueWins() {
        CompoundTag existing = new CompoundTag();
        existing.putInt("progress", 5);
        CompoundTag overlay = new CompoundTag();
        overlay.putInt("progress", 9);

        assertEquals(9, PackedCarrierCodec.merge(existing, overlay).getInt("progress", 0));
    }

    @Test
    void nestedCompoundsMergeRecursivelyInsteadOfBeingReplaced() {
        CompoundTag existingTank = new CompoundTag();
        existingTank.putString("fluid", "minecraft:water");
        existingTank.putInt("amount", 1000);
        CompoundTag existing = new CompoundTag();
        existing.put("tank", existingTank);

        CompoundTag overlayTank = new CompoundTag();
        overlayTank.putInt("amount", 500);
        CompoundTag overlay = new CompoundTag();
        overlay.put("tank", overlayTank);

        CompoundTag mergedTank = PackedCarrierCodec.merge(existing, overlay).getCompound("tank");
        assertEquals("minecraft:water", mergedTank.getString("fluid"));
        assertEquals(500, mergedTank.getInt("amount", 0));
    }

    @Test
    void mergingDoesNotMutateEitherInput() {
        CompoundTag existing = new CompoundTag();
        existing.putString("keep", "value");
        CompoundTag overlay = new CompoundTag();
        overlay.putInt("added", 1);

        PackedCarrierCodec.merge(existing, overlay);

        assertEquals(1, existing.keySet().size());
        assertEquals(1, overlay.keySet().size());
        assertNotSame(existing, PackedCarrierCodec.merge(existing, overlay));
    }

    @Test
    void emptySidesAreTolerated() {
        CompoundTag only = new CompoundTag();
        only.putString("key", "value");

        assertTrue(PackedCarrierCodec.merge(null, new CompoundTag()).isEmpty());
        assertEquals("value", PackedCarrierCodec.merge(null, only).getString("key"));
        assertEquals("value", PackedCarrierCodec.merge(only, null).getString("key"));
    }

    @Test
    void typeChangeReplacesTheOldValue() {
        CompoundTag existing = new CompoundTag();
        existing.putInt("slot", 1);
        CompoundTag overlay = new CompoundTag();
        overlay.putString("slot", "text");

        Tag merged = PackedCarrierCodec.merge(existing, overlay).get("slot");
        assertEquals("text", merged.getAsString());
    }
}
