package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        for (String relative : new String[]{
                "src/main/java/com/huidu/farmersdelight/manager/CookingPotEffectManager.java",
                "src/main/java/com/huidu/farmersdelight/manager/SkilletEffectManager.java",
                "src/main/java/com/huidu/farmersdelight/manager/StoveManager.java"}) {
            String source = read(relative);
            assertTrue(source.contains("ManagerSupport.spawnParticleFor(\n                    plugin.scheduler()")
                            || source.contains("ManagerSupport.spawnParticleFor(plugin.scheduler()"), relative);
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
