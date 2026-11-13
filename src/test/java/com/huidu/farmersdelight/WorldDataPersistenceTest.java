package com.huidu.farmersdelight;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence contract for Folia. The shutdown path may only mark CraftEngine data dirty (the write happens
 * on the owning region), and the world-unload path may only touch a block entity when the current thread owns
 * its region.
 */
class WorldDataPersistenceTest {

    @Test
    void paperKeepsWritingBlockDataInline() {
        assertTrue(WorldDataPersistence.mayWriteInline(false, null, null, null));
    }

    @Test
    void foliaNeverWritesInlineWithoutRegionOwnership() {
        assertFalse(WorldDataPersistence.mayWriteInline(true, null, null, null));
    }

    @Test
    void foliaShutdownMarksInsteadOfWriting() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/WorldDataPersistence.java");
        int folia = source.indexOf("plugin.scheduler().isFolia()");
        assertTrue(folia > 0);
        int markPot = source.indexOf("markAllBlockEntitiesDirty", folia);
        int savePot = source.indexOf("CookingPotBlockBehavior::saveAllData", folia);
        assertTrue(markPot > folia, "the Folia shutdown branch has to mark the pot data dirty");
        assertTrue(savePot > markPot, "the Paper shutdown branch writes the pot data after the Folia branch");
    }

    @Test
    void worldUnloadGatesEveryBlockEntityWriteOnRegionOwnership() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/WorldDataPersistence.java");
        int unload = source.indexOf("static void saveWorldBlockData(");
        int gate = source.indexOf("mayWriteInline(folia, plugin, world, posKey)", unload);
        int potSave = source.indexOf("CookingPotBlockBehavior.saveBlockEntityData(world, posKey)", unload);
        int boardSave = source.indexOf("CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey)", unload);
        assertTrue(gate > unload);
        assertTrue(potSave > gate, "the pot save has to sit behind the ownership gate");
        assertTrue(boardSave > gate, "the board save has to sit behind the ownership gate");
    }

    @Test
    void theStoveAndSkilletWorldUnloadPassesGateOnRegionOwnership() throws IOException {
        for (String file : new String[]{
                "src/main/java/com/huidu/farmersdelight/manager/StoveManager.java",
                "src/main/java/com/huidu/farmersdelight/manager/SkilletManager.java"}) {
            String source = read(file);
            int unload = source.indexOf("public void saveWorldData(World world)");
            int gate = source.indexOf("isOwnedByCurrentRegion(location)", unload);
            int passivate = source.indexOf("passivate", unload);
            assertTrue(unload > 0, file);
            assertTrue(gate > unload, file + " has to gate world-unload saves on region ownership");
            assertTrue(passivate == -1 || passivate > gate,
                    file + " has to gate the controller write behind the ownership check");
        }
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
