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
        assertEquals(Set.of("eject", "transfer-cooldown", "transfer_cooldown"), read);
        assertFalse(read.contains("ignite"), "a read that never happened must not appear");
        assertEquals(Set.of(), BehaviorArgRecorder.end(), "nothing is recording after end()");
    }

    @Test
    void aNestedPathCountsItsLeafAndTheFlatSpellings() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(java.util.Map.of(), "burn.enabled");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains("burn.enabled"), read.toString());
        assertTrue(read.contains("burn-enabled"), read.toString());
        assertTrue(read.contains("burn_enabled"), read.toString());
    }

    @Test
    void aWholeSectionReadIsMarkedSeparatelyFromItsChildren() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.section(java.util.Map.of(), "burn");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains(BehaviorArgRecorder.sectionMarker("burn")), read.toString());
        assertFalse(read.contains("burn.enabled"), "a section read does not claim any child");
    }

    @Test
    void theLegacyFlatKeyOfAGroupedOptionCountsAsRead() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.rawWithLegacy(java.util.Map.of("bone_meal_age_bonus", 1), "bone-meal.age-bonus",
                "bone_meal_age_bonus");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains("bone_meal_age_bonus"), read.toString());
        assertTrue(read.contains("bone-meal.age-bonus"), read.toString());
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
        assertEquals(Set.of("worker-option", "worker_option"), otherThread.get());
        List<String> mainRead = new ArrayList<>(BehaviorArgRecorder.end());
        assertEquals(Set.of("main-thread-option", "main_thread_option"), Set.copyOf(mainRead));
    }
}
