package com.huidu.farmersdelight.api.registry;

import com.huidu.farmersdelight.util.compat.CustomItemPresence;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The early-registration guard: an addon registering content during onLoad must not reach CraftEngine
 * while it is still starting (26.9.x NPEs there), and the registration must not be lost while it waits.
 */
class ContentRegistrationEarlyCallTest {


    @BeforeEach
    void freshState() {
        ContentRegistration.resetForTests();
        CustomItemPresence.installProbe(null);
    }

    @AfterEach
    void restore() {
        ContentRegistration.resetForTests();
        CustomItemPresence.installProbe(null);
    }

    @Test
    void theProductionBridgeNeverDefersRegistration() throws Exception {
        // The 26.9.2 log showed addon types ("unknown block behaviour brewinandchewin:keg", "unknown function
        // type brewinandchewin:booze") right after registration was deferred. CraftEngine parses the pack
        // during the plugin's onLoad, so these two calls must stay immediate; only the "is content loaded" question is
        // guarded, and only because that one dereferences a manager that is still null in 26.9.x.
        String source = read("api/registry/ContentRegistration.java");
        assertEquals(0, source.split("if \\(!CustomItemPresence.anyLoaded\\(\\)\\)", -1).length - 1,
                "register/isRegistered must not be short-circuited");
        assertFalse(CustomItemPresence.anyLoaded(),
                "the NPE guard itself still answers 'not ready' instead of throwing");
        assertDoesNotThrow(() -> ContentRegistration.apply(ContentRegistration.Kind.BLOCK_BEHAVIOR),
                "and the remembered registrations stay queryable");
    }

    @Test
    void aDeferredRegistrationIsRememberedAndAppliedLater() {
        RecordingBridge bridge = new RecordingBridge();
        ContentRegistration.installBridge(bridge);

        ContentRegistration.registerBlockBehavior(Key.of("testplugin", "deferred_behavior"),
                (block, section) -> null);

        assertEquals(1, bridge.pushes, "the registration is remembered (and pushed once) while not ready");
        assertEquals(1, ContentRegistration.apply(ContentRegistration.Kind.BLOCK_BEHAVIOR),
                "apply() re-applies a remembered entry the engine does not report as registered");
        assertEquals(2, bridge.pushes, "so a registration made early is never lost");
    }


    private static String read(String relative) throws Exception {
        java.nio.file.Path cursor = java.nio.file.Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                java.nio.file.Path candidate = cursor.resolve(prefix + relative);
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    return java.nio.file.Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("source not reachable: " + relative);
    }

    private static final class RecordingBridge implements ContentRegistration.Bridge {

        private int pushes;

        @Override
        public boolean isRegistered(ContentRegistration.Kind kind, Key id) {
            return false;
        }

        @Override
        public void register(ContentRegistration.Kind kind, Key id, Object factory) {
            this.pushes++;
        }

        @Override
        public boolean contentLoaded() {
            return false;
        }
    }
}
