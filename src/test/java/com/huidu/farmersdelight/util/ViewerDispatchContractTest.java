package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents viewer-side effect helpers from running Player APIs on a block region thread. */
class ViewerDispatchContractTest {

    @Test
    void managerSupportDispatchesParticlesThroughViewerSchedulers() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/util/ManagerSupport.java");
        assertTrue(source.contains("SchedulerAdapter scheduler"));
        assertTrue(source.contains("scheduler.runForEntity(viewer"));
    }

    @Test
    void soundUtilsDispatchesViewerSoundsThroughViewerSchedulers() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/util/SoundUtils.java");
        assertTrue(source.contains("play(SchedulerAdapter scheduler, List<Player> viewers"));
        assertTrue(source.contains("scheduler.runForEntity(viewer"));
    }

    @Test
    void effectManagersPassTheirSchedulerToViewerEffects() throws IOException {
        // The call may sit at any nesting depth, so the indentation between the opening parenthesis and the
        // scheduler argument is matched as whitespace rather than as a fixed number of spaces.
        java.util.regex.Pattern dispatched = java.util.regex.Pattern.compile(
                "ManagerSupport\\.(spawnParticleFor|runForViewerWithin)\\(\\s*plugin\\.scheduler\\(\\)");
        for (String relative : new String[]{
                "src/main/java/com/huidu/farmersdelight/manager/CookingPotEffectManager.java",
                "src/main/java/com/huidu/farmersdelight/manager/SkilletEffectManager.java",
                "src/main/java/com/huidu/farmersdelight/manager/StoveManager.java"}) {
            String source = read(relative);
            assertTrue(dispatched.matcher(source).find(),
                    relative + " must route its viewer effects through the viewer scheduler");
        }
    }

    @Test
    void cookingPotSoundPathRunsOnTheViewerSchedulers() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/manager/CookingPotEffectManager.java");
        assertTrue(source.contains("SoundUtils.playWithin(plugin.scheduler()"));
        assertFalse(source.contains("viewer.playSound("), "the cooking pot must not send sound packets on its region thread");
        assertFalse(source.contains("p.playSound("));
    }

    @Test
    void cookingPotViewerSelectionIsChunkTrackingOnly() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/manager/CookingPotEffectManager.java");
        assertTrue(source.contains("getPlayersSeeingChunk()"));
        // The distance gate moved into the viewer's own scheduler task; the pot region must not read
        // another region's player coordinates while selecting viewers.
        assertFalse(source.contains(".getX() -"), "viewer coordinates must not be compared on the pot region thread");
        assertFalse(source.contains("p.getWorld()"));
        assertFalse(source.contains("viewer.getWorld()"));
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
