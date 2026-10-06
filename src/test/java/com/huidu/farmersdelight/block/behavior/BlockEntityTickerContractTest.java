package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block entity controllers stay on CraftEngine's synchronous tick channel.
 *
 *
 * CraftEngine's world layer drives controllers through
 * {@code BlockEntityController#createBlockEntityTicker(CEWorld, ImmutableBlockState)}. The engine also offers an
 * asynchronous variant, but its thread model is not established on a regionised server, so a controller must never
 * declare it: block state writes belong to the owning region.
 *
 * <p>The Gradle test working directory is the module root, so the sources are read relative to it. The failure
 * messages name the directory they resolved, which is what makes a wrong working directory diagnosable instead of
 * mysterious.
 */
class BlockEntityTickerContractTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    /** Controllers this repository hands to CraftEngine's world tick today. */
    private static final String BASKET = "com/huidu/farmersdelight/block/behavior/BasketVacuumController.java";

    @Test
    void theCeDrivenControllerDeclaresASynchronousTicker() throws Exception {
        Path path = SOURCES.resolve(BASKET);
        assertTrue(Files.isRegularFile(path), BASKET + " has to exist under " + sourceDir());
        String source = Files.readString(path);
        assertTrue(source.contains("createBlockEntityTicker("),
                BASKET + " has to declare the synchronous ticker CraftEngine's world tick calls");
        assertTrue(source.contains("createTickerHelper("),
                BASKET + " has to build its ticker through createTickerHelper, the shape CraftEngine's own"
                        + " behaviours use");
    }

    @Test
    void noControllerAnywhereDeclaresTheAsyncTickerChannel() throws Exception {
        Path root = SOURCES;
        assertTrue(Files.isDirectory(root), root + " has to exist under " + sourceDir());
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(path).contains("createAsyncBlockEntityTicker"),
                        root.relativize(path) + " must stay on the synchronous ticker: the async channel's thread"
                                + " model is not established on a regionised server, so block state must not be"
                                + " written there");
            }
        }
    }

    private static String sourceDir() {
        return Path.of("").toAbsolutePath() + " (user.dir=" + System.getProperty("user.dir") + ")";
    }
}
