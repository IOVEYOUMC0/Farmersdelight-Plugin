package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handheld skillet refuses a click while a skewer session owns the player, the mirror of the skewer path's
 * own check against this skillet. Without it both paths could eat the same right-click with the skillet's
 * ingredient being the very stack the skewer session is cooking.
 *
 * The gate itself runs without a server: the mayStartHandheld decision is the whole decision.
 * The wiring is asserted on the source, because the registry owns both handlers and neither class can be built
 * in a unit test.
 */
class SkilletHandheldExclusionTest {

    @Test
    void aCookingSkewerBlocksTheHandheldSkillet() {
        assertFalse(SkilletHandheldCooking.mayStartHandheld(true, false, true),
                "a skewer session owns the click, so the skillet must not start");
        assertTrue(SkilletHandheldCooking.mayStartHandheld(true, false, false),
                "the same click is accepted once no skewer is cooking");
    }

    /** The two conditions that already existed keep their exact meaning. */
    @Test
    void theOldConditionsStillRefuse() {
        assertFalse(SkilletHandheldCooking.mayStartHandheld(false, false, false),
                "a disabled path never starts");
        assertFalse(SkilletHandheldCooking.mayStartHandheld(true, true, false),
                "a native use of the other hand takes the click");
        assertTrue(SkilletHandheldCooking.mayStartHandheld(true, false, false));
    }

    /** The skillet consultation is real code, not a parameter that is passed around and ignored. */
    @Test
    void theSkilletConsultsTheSkewerSession() throws IOException {
        String skillet = read("manager/SkilletHandheldCooking.java");
        assertTrue(skillet.contains("Predicate<Player> skewerCooking = player -> false"),
                "the check has to default to never-cooking");
        assertTrue(skillet.contains("skewerCooking.test(player)"),
                "and the click path has to ask it");
        assertTrue(skillet.contains("mayStartHandheld(handheldCookingEnabled, otherHandUsing, skewerCookingNow)"),
                "the answer has to reach the admission gate");
        assertTrue(skillet.contains("void setSkewerCookingCheck(Predicate<Player> check)"),
                "and the wiring has to be settable");
    }

    /** The skewer side exposes only the read-only question, and the registry wires the two together. */
    @Test
    void theSkewerExposesItsStateAndTheRegistryWiresIt() throws IOException {
        String skewer = read("handheld/HandCookedSkewerHooks.java");
        assertTrue(skewer.contains("public boolean isCooking(@Nullable UUID player)"),
                "the skewer path has to expose its session state read-only");

        String registry = read("ListenerRegistry.java");
        assertTrue(registry.contains("setSkewerCookingCheck"),
                "the registry has to hand the check to the skillet");
        assertTrue(registry.contains("handCookedSkewerHooks.isCooking(player.getUniqueId())"),
                "and the check has to be the skewer session, read per call");
        assertTrue(registry.contains("wireSkilletSkewerExclusion()"),
                "wired during handler registration");
    }

    /** Both stop cases stay in the gate: the old one for a native use, the new one for a cooking skewer. */
    @Test
    void theGateStillStopsTheSessionForBothStopCases() throws IOException {
        String skillet = read("manager/SkilletHandheldCooking.java");
        int gate = skillet.indexOf("if (!mayStartHandheld(");
        int stop = skillet.indexOf("stopHandheldUse(player, null);", gate);
        assertTrue(gate > 0 && stop > gate,
                "the refused click has to stop the session, as it did for the other-hand use before");
        assertTrue(skillet.substring(gate, stop).contains("otherHandUsing || skewerCookingNow"),
                "and both stop cases have to reach it");
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
