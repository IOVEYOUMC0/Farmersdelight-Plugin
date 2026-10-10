package com.huidu.farmersdelight;

import com.huidu.farmersdelight.recipe.RecipeRegistrationDriver;
import com.huidu.farmersdelight.recipe.RecipeRegistrationRound;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CraftEngineResourceLeaseTest {
    private static final Logger LOGGER = Logger.getLogger(CraftEngineResourceLeaseTest.class.getName());

    @Test
    void oldEnginesRemainUsableWithoutTheNewApi() throws Exception {
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Object(), LOGGER);
        leases.tryAcquire().close();
        assertNotNull(leases.tryAcquire());
    }

    @Test
    void capabilityLookupRunsOnceAndLeaseExcludesConcurrentWork() throws Exception {
        Engine engine = new Engine(new Coordinator());
        CraftEngineResourceLease leases = new CraftEngineResourceLease(engine, LOGGER);
        AutoCloseable first = leases.tryAcquire();
        assertNotNull(first);
        assertNull(leases.tryAcquire(), "CE must refuse a competing reload while addon readers hold the lease");
        first.close();
        leases.tryAcquire().close();
        assertEquals(1, engine.lookups);
    }

    @Test
    void failedNotificationReleasesTheResource() throws Exception {
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Engine(new Coordinator()), LOGGER);
        assertThrows(IllegalArgumentException.class, () -> {
            try (AutoCloseable ignored = leases.tryAcquire()) {
                throw new IllegalArgumentException("addon failure");
            }
        });
        try (AutoCloseable next = leases.tryAcquire()) {
            assertNotNull(next, "the next CE operation must not remain blocked");
        }
    }

    @Test
    void unknownAcquisitionFailuresAreReportedInsteadOfTreatedAsBusy() {
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Engine(new BrokenCoordinator()), LOGGER);
        IllegalStateException failure = assertThrows(IllegalStateException.class, leases::tryAcquire);
        assertEquals("broken", failure.getCause().getMessage());
    }

    @Test
    void actualCompileTargetCoordinatorSupportsTheOptionalLease() throws Exception {
        Object coordinator = Class.forName("net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator")
                .getConstructor().newInstance();
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Engine(coordinator), LOGGER);
        try (AutoCloseable first = leases.tryAcquire()) {
            assertNotNull(first);
            assertNull(leases.tryAcquire());
        }
        leases.tryAcquire().close();
    }

    @Test
    void shardedPublicationKeepsCeExcludedThroughTheLastSlice() throws Exception {
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Engine(new Coordinator()), LOGGER);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(work -> {
            tick.set(work);
            return PluginTask.NOOP;
        });
        AutoCloseable held = leases.tryAcquire();
        var watch = driver.beginPublicationWatch();
        watch.whenPublished(outcome -> close(held));
        driver.start(this, List.of(new RecipeRegistrationRound.Segment(List.of("a", "b", "c"),
                id -> assertNull(leases.tryAcquire(), "CE reload must not clear items inside a slice"), null)),
                1, null);
        driver.endPublicationWatch();
        assertNull(leases.tryAcquire());
        tick.get().run();
        assertNull(leases.tryAcquire(), "the second slice has not published yet");
        tick.get().run();
        try (AutoCloseable next = leases.tryAcquire()) {
            assertNotNull(next, "publication must release the CE resource");
        }
    }

    @Test
    void cancellingRecipeRegistrationReleasesTheCeResource() throws Exception {
        CraftEngineResourceLease leases = new CraftEngineResourceLease(new Engine(new Coordinator()), LOGGER);
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(work -> PluginTask.NOOP);
        AutoCloseable held = leases.tryAcquire();
        var watch = driver.beginPublicationWatch();
        watch.whenPublished(outcome -> close(held));
        driver.start(this, List.of(new RecipeRegistrationRound.Segment(List.of("a", "b"), id -> { }, null)),
                1, null);
        driver.endPublicationWatch();
        assertNull(leases.tryAcquire());
        driver.cancel();
        try (AutoCloseable next = leases.tryAcquire()) {
            assertNotNull(next, "disable must not leave CE permanently busy");
        }
    }

    private static void close(AutoCloseable lease) {
        try {
            lease.close();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    public static final class Engine {
        private final Object coordinator;
        private int lookups;

        public Engine(Object coordinator) { this.coordinator = coordinator; }
        public Object resourceOperations() { lookups++; return coordinator; }
    }

    public static final class Coordinator {
        private final AtomicBoolean active = new AtomicBoolean();

        public AutoCloseable acquire() {
            if (!active.compareAndSet(false, true)) throw new BusyException();
            return () -> active.set(false);
        }

        public static final class BusyException extends IllegalStateException { }
    }

    public static final class BrokenCoordinator {
        public AutoCloseable acquire() { throw new IllegalArgumentException("broken"); }
    }
}
