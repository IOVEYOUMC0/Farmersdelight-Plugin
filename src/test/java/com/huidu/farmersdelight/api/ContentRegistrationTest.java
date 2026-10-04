package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.api.registry.ContentRegistration;
import com.huidu.farmersdelight.api.registry.ContentRegistration.Kind;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural coverage for the public content registration facade. The CraftEngine calls are swapped for a
 * recording double, so these run without a server or a loaded CraftEngine; the real registry wiring is
 * exercised by the plugin's own registration pass.
 */
class ContentRegistrationTest {

    private static final Key VALID = Key.of("testplugin:my_type");

    private static final BlockBehaviorFactory<BlockBehavior> BLOCK = (definition, section) -> null;
    private static final ItemBehaviorFactory<ItemBehavior> ITEM = (pack, path, id, section) -> null;
    private static final FunctionFactory<Context, Function<Context>> FUNCTION = section -> null;
    private static final ConditionFactory<Context, Condition<Context>> CONDITION = section -> null;
    private static final LootFunctionFactory<LootFunction> LOOT = section -> null;

    private final RecordingBridge bridge = new RecordingBridge();

    @AfterEach
    void restoreRealBridge() {
        ContentRegistration.resetForTests();
    }

    private void install() {
        ContentRegistration.installBridge(bridge);
    }

    @Test
    void rejectsNullId() {
        install();
        assertThrows(NullPointerException.class,
                () -> ContentRegistration.registerBlockBehavior(null, BLOCK));
    }

    @Test
    void rejectsBlankIdValue() {
        install();
        Key blank = new Key("testplugin", "");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ContentRegistration.registerFunction(blank, FUNCTION));
        assertTrue(failure.getMessage().contains("namespaced"), failure.getMessage());
    }

    @Test
    void rejectsReservedNamespaces() {
        install();
        IllegalArgumentException minecraft = assertThrows(IllegalArgumentException.class,
                () -> ContentRegistration.registerCondition(Key.of("minecraft:test_condition"), CONDITION));
        assertTrue(minecraft.getMessage().contains("reserved"), minecraft.getMessage());
        IllegalArgumentException own = assertThrows(IllegalArgumentException.class,
                () -> ContentRegistration.registerLootFunction(Key.of("farmersdelight:test_loot"), LOOT));
        assertTrue(own.getMessage().contains("reserved"), own.getMessage());
        assertTrue(bridge.applied().isEmpty());
    }

    @Test
    void rejectsNullFactory() {
        install();
        assertThrows(NullPointerException.class,
                () -> ContentRegistration.registerItemBehavior(VALID, null));
    }

    @Test
    void registersAndReportsTheId() {
        install();
        ContentRegistration.registerBlockBehavior(VALID, BLOCK);
        assertTrue(ContentRegistration.isRegistered(VALID));
        assertEquals(Set.of(VALID), ContentRegistration.registeredIds());
        assertEquals(Set.of("BLOCK_BEHAVIOR|testplugin:my_type"), bridge.applied());
    }

    @Test
    void rejectsDuplicateRegistrationOfTheSameKind() {
        install();
        ContentRegistration.registerFunction(VALID, FUNCTION);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ContentRegistration.registerFunction(VALID, FUNCTION));
        assertTrue(failure.getMessage().contains("already registered"), failure.getMessage());
        assertEquals(1, bridge.applied().size());
    }

    @Test
    void rejectsIdCraftEngineAlreadyKnows() {
        install();
        // Simulates a collision with FarmersDelight's own type or with another plugin's registration.
        bridge.markPresent(Kind.FUNCTION, VALID);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ContentRegistration.registerFunction(VALID, FUNCTION));
        assertTrue(failure.getMessage().contains("already registered with CraftEngine"), failure.getMessage());
        assertTrue(bridge.applied().isEmpty());
        assertFalse(ContentRegistration.isRegistered(VALID));
    }

    @Test
    void allowsTheSameIdInDifferentKinds() {
        install();
        ContentRegistration.registerFunction(VALID, FUNCTION);
        ContentRegistration.registerCondition(VALID, CONDITION);
        assertEquals(Set.of(VALID), ContentRegistration.registeredIds());
        assertEquals(2, bridge.applied().size());
    }

    @Test
    void unregisterRemovesTheEntryAndStopsReplay() {
        install();
        ContentRegistration.registerLootFunction(VALID, LOOT);
        assertTrue(ContentRegistration.unregister(VALID));
        assertFalse(ContentRegistration.isRegistered(VALID));
        assertTrue(ContentRegistration.registeredIds().isEmpty());

        bridge.clearRegistry();
        bridge.forgetApplications();
        assertEquals(0, ContentRegistration.apply(Kind.LOOT_FUNCTION));
        assertTrue(bridge.applied().isEmpty());
        assertFalse(ContentRegistration.unregister(VALID));
    }

    @Test
    void refusesToRegisterAnIdWithdrawnEarlier() {
        install();
        ContentRegistration.registerLootFunction(VALID, LOOT);
        ContentRegistration.unregister(VALID);
        // CraftEngine cannot drop the type it already holds, so a replacement factory would never be used.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ContentRegistration.registerLootFunction(VALID, LOOT));
        assertTrue(failure.getMessage().contains("unregistered earlier"), failure.getMessage());
    }

    @Test
    void replayReappliesRegistrationsCraftEngineLost() {
        install();
        ContentRegistration.registerFunction(VALID, FUNCTION);
        ContentRegistration.registerBlockBehavior(Key.of("testplugin:second"), BLOCK);
        assertEquals(2, bridge.applied().size());

        // A CraftEngine reload that rebuilds its registries drops every type, ours included.
        bridge.clearRegistry();
        bridge.forgetApplications();
        assertEquals(1, ContentRegistration.apply(Kind.FUNCTION));
        assertEquals(1, ContentRegistration.apply(Kind.BLOCK_BEHAVIOR));
        assertEquals(Set.of("FUNCTION|testplugin:my_type", "BLOCK_BEHAVIOR|testplugin:second"),
                bridge.applied());

        // Idempotent: a second pass over an intact registry applies nothing.
        bridge.forgetApplications();
        assertEquals(0, ContentRegistration.apply(Kind.FUNCTION));
        assertEquals(0, ContentRegistration.apply(Kind.BLOCK_BEHAVIOR));
        assertTrue(bridge.applied().isEmpty());
    }

    @Test
    void lateRegistrationStillSucceeds() {
        bridge.markContentLoaded();
        install();
        // CraftEngine already loaded its content: the entry is still registered, and the caller is warned
        // through plugin.content_registration_late (a no-op logger outside a running plugin).
        ContentRegistration.registerCondition(VALID, CONDITION);
        assertTrue(ContentRegistration.isRegistered(VALID));
        assertEquals(1, bridge.applied().size());
    }

    /** Records every application and answers the presence probes the facade makes. */
    private static final class RecordingBridge implements ContentRegistration.Bridge {

        private final Set<String> present = new LinkedHashSet<>();
        private final Set<String> applied = new LinkedHashSet<>();
        private boolean contentLoaded;

        @Override
        public boolean isRegistered(Kind kind, Key id) {
            return present.contains(slot(kind, id));
        }

        @Override
        public void register(Kind kind, Key id, Object factory) {
            applied.add(slot(kind, id));
            present.add(slot(kind, id));
        }

        @Override
        public boolean contentLoaded() {
            return contentLoaded;
        }

        void markPresent(Kind kind, Key id) {
            present.add(slot(kind, id));
        }

        void markContentLoaded() {
            contentLoaded = true;
        }

        /** Simulates a CraftEngine reload that rebuilt its registries and lost every type. */
        void clearRegistry() {
            present.clear();
        }

        void forgetApplications() {
            applied.clear();
        }

        Set<String> applied() {
            return Set.copyOf(applied);
        }

        private static String slot(Kind kind, Key id) {
            return kind.name() + "|" + id.namespace() + ":" + id.value();
        }
    }
}
