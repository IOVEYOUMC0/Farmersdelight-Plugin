package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cutting board controller cannot be built without a running CraftEngine platform: its constructor asks
 * the platform for a container, so no unit test can drive its container callbacks. The write path is therefore
 * asserted on the source, and what has to hold there is the order: the parked passivation snapshot is applied
 * before anything creates an entity, because the blank entity a create-first write leaves behind makes the
 * later apply discard the snapshot, and that snapshot is then the only copy of the board's item.
 */
class CuttingBoardBlockEntityControllerSourceTest {

    @Test
    void theWritePathAppliesParkedDataBeforeResolvingTheEntity() throws IOException {
        String body = methodBody(read("block/behavior/CuttingBoardBlockEntityController.java"),
                "private void writeToEntity()");

        assertTrue(body.contains("getOrCreateEntity()"),
                "the write has to resolve the entity through the helper that applies parked data first");
        assertFalse(body.contains("new CuttingBoardBlockEntity("),
                "the write must never create an entity of its own, or it can create a blank one");
        assertTrue(body.indexOf("getOrCreateEntity()") < body.indexOf("CeItemInterop.asBukkitStack(this.item)"),
                "parked data has to be applied before the shadow this write publishes is read");
    }

    @Test
    void theEntityHelperAppliesParkedDataBeforeCreating() throws IOException {
        String body = methodBody(read("block/behavior/CuttingBoardBlockEntityController.java"),
                "private CuttingBoardBlockEntity getOrCreateEntity()");
        int apply = body.indexOf("loadPendingDataIfReady()");
        int create = body.indexOf("new CuttingBoardBlockEntity(");

        assertTrue(apply > 0, "the helper applies the parked data");
        assertTrue(create > apply, "and applies it before it creates anything");
    }

    @Test
    void theLoadKeepsParkedDataWhenTheLiveEntityIsEmpty() throws IOException {
        String body = methodBody(read("block/behavior/CuttingBoardBlockEntityController.java"),
                "private boolean loadData(CompoundTag data)");
        int create = body.indexOf("new CuttingBoardBlockEntity(");

        assertTrue(create > 0, "the load still creates the entity when the board has none");
        assertTrue(body.substring(0, create).contains("hasItem()"),
                "only a live entity that already holds an item may consume the parked snapshot");
        assertTrue(body.substring(create).contains("entity.setItem("),
                "a live empty entity is filled in place instead of being left empty");
    }

    /** The body of the method with the given signature, so a test can assert its shape. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start > 0, "the method has to exist: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "the method has to have a body: " + signature);
        return source.substring(open + 1, matchingBrace(source, open));
    }

    /** The index of the brace that closes the one at the given index, ignoring line comments. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = source.indexOf('\n', index);
                if (index < 0) {
                    break;
                }
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new AssertionError("unbalanced braces from index " + open);
    }

    /** Finds the source from the test working directory the same way the other source assertions do. */
    private static String read(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        assertNotNull(null, "the source file has to be reachable from the test working directory: " + relative);
        return "";
    }
}
