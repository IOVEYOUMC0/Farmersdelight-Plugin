package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CraftEngine hands these blocks' containers to vanilla hopper logic on whatever thread ticks the hopper, and
 * nothing in CraftEngine or in this plugin checked the region before. Each container hook must refuse a caller
 * that does not own the block's region (the same rule the crab trap and worm bin already apply).
 *
 * These are source-contract checks: the hooks take CraftEngine's reflection-free Object[] arguments
 * and cannot be driven offline.
 */
class ContainerRegionContractTest {

    private static final List<String> BEHAVIORS = List.of(
            "src/main/java/com/huidu/farmersdelight/block/behavior/CookingPotBlockBehavior.java",
            "src/main/java/com/huidu/farmersdelight/block/behavior/CuttingBoardBlockBehavior.java",
            "src/main/java/com/huidu/farmersdelight/block/behavior/SkilletBlockBehavior.java");

    @Test
    void everyContainerHookRefusesAForeignRegionBeforeItReadsTheBlockEntity() throws IOException {
        for (String behavior : BEHAVIORS) {
            String source = read(behavior);
            int hook = source.indexOf("public Object getContainer(Object thisBlock, Object[] args)");
            assertTrue(hook > 0, behavior + " no longer overrides getContainer");
            int end = source.indexOf("return blockEntity.controller.let(", hook);
            assertTrue(end > hook, behavior + ": the hook still returns the controller's container");

            String body = source.substring(hook, end);
            int guard = body.indexOf("CustomBlockUtils.ownsBlockRegion(plugin, world, pos)");
            int lookup = body.indexOf("getBlockEntityAtIfLoaded(pos)");
            assertTrue(guard > 0, behavior + ": the hopper container hook has no region check");
            assertTrue(lookup > guard, behavior + ": the region check must run before the block entity is read");
            assertTrue(body.contains("return null;"), behavior + ": a foreign region must get no container");
        }
    }

    @Test
    void theRegionCheckUsesTheSchedulerRatherThanAReimplementation() throws IOException {
        String utils = read("src/main/java/com/huidu/farmersdelight/util/CustomBlockUtils.java");
        assertTrue(utils.contains("public static boolean ownsBlockRegion(FarmersDelightPlugin plugin, World world, BlockPos pos)"));
        assertTrue(utils.contains("plugin.scheduler().isOwnedByCurrentRegion("),
                "Folia detection belongs to the scheduler adapter, not to each block behavior");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
