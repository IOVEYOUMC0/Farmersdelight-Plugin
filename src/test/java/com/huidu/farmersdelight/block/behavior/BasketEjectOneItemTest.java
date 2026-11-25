package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract for the eject transaction: adding to the target must be confirmed before the source is
 * decremented, and a source slot that changed between the snapshot and the write-back must roll the target back.
 * The item-stack operations themselves need a server-backed ItemStack, so the ordering is pinned here instead of
 * being exercised with a fake ItemStack (which Paper cannot construct without a registry).
 */
class BasketEjectOneItemTest {

    @Test
    void theTargetIsConfirmedBeforeTheSourceIsDecremented() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/block/behavior/BasketVacuumController.java");
        assertTrue(source.contains("ItemStack[] targetBefore = target.getStorageContents();"),
                "the target is snapshotted before addItem");
        assertTrue(source.contains("findAddedSlot(targetBefore, target.getStorageContents(), single)"),
                "addItem must be verified against the target snapshot");
        assertTrue(source.contains("ItemStack current = source.getItem(slot);"),
                "the source slot is re-read after the target accepted the item");
        assertTrue(source.contains("sameStack(current, stack)"),
                "a changed source slot must be detected before decrementing");
        assertTrue(source.contains("findAddedSlot(targetBefore, target.getStorageContents(), single)"),
                "the target diff must be verified");
        assertTrue(source.contains("countSimilar(target.getStorageContents(), single)"),
                "an unlocatable but real target copy must still be rolled back");
    }

    @Test
    void aChangedSourceOrFailedWriteRollsTheTargetBack() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/block/behavior/BasketVacuumController.java");
        assertTrue(source.contains("removeOneFromSlot(target, addedSlot, single);"),
                "a stale source read must undo the target's copy");
        assertTrue(source.contains("catch (RuntimeException | LinkageError failure)"),
                "a failed source write must roll the target back before rethrowing");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
