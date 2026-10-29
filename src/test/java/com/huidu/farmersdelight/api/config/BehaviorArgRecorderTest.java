package com.huidu.farmersdelight.api.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorArgRecorderTest {

    @Test
    void recordsWhatAFactoryReadsAndStopsWhenDone() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(java.util.Map.of("eject", true), "eject");
        BehaviorArguments.present(java.util.Map.of(), "transfer-cooldown");
        Set<String> read = BehaviorArgRecorder.end();
        assertEquals(Set.of("eject", "transfer-cooldown"), read);
        assertFalse(read.contains("ignite"), "a read that never happened must not appear");
        assertEquals(Set.of(), BehaviorArgRecorder.end(), "nothing is recording after end()");
    }

    @Test
    void aNestedPathCountsBothItsSectionAndTheFlatSpelling() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(java.util.Map.of(), "burn.enabled");
        assertEquals(Set.of("burn", "burn-enabled"), BehaviorArgRecorder.end());
    }

    @Test
    void theLegacyFlatKeyOfAGroupedOptionCountsAsRead() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.rawWithLegacy(java.util.Map.of("bone_meal_age_bonus", 1), "bone-meal.age-bonus",
                "bone_meal_age_bonus");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains("bone_meal_age_bonus"));
        assertTrue(read.contains("bone-meal"));
    }

    @Test
    void recordingIsThreadConfined() throws Exception {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(java.util.Map.of(), "main-thread-option");
        AtomicReference<Set<String>> otherThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            BehaviorArgRecorder.begin();
            BehaviorArguments.raw(java.util.Map.of(), "worker-option");
            otherThread.set(BehaviorArgRecorder.end());
            done.countDown();
        });
        worker.start();
        done.await();
        assertEquals(Set.of("worker-option"), otherThread.get());
        List<String> mainRead = new ArrayList<>(BehaviorArgRecorder.end());
        assertEquals(List.of("main-thread-option"), mainRead);
    }
}
