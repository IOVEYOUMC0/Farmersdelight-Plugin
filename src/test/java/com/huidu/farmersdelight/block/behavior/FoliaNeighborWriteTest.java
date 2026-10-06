package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neighbour writes only run on a region the current thread owns.
 *
 *
 * Residency is not ownership: on a regionised server a neighbour whose chunk is loaded can still belong to
 * another region, and both the CraftEngine state lookup and the programmatic place that follows it would then
 * touch that block from the wrong thread. Every pass that rewrites a neighbour across a chunk border has to
 * check region ownership first and skip an unowned neighbour, never hand the write to the owning region.
 *
 * These passes read blocks and call Bukkit statics, so the check is read from the source: the region gate has
 * to come before the neighbour state lookup, which has to come before the place. Dropping the gate turns this
 * red.
 */
class FoliaNeighborWriteTest {

    // The Gradle test working directory is the module root, so the sources resolve from there.
    private static final Path SOURCES = Path.of("src", "main", "java", "com", "huidu", "farmersdelight",
            "block", "behavior");

    @Test
    void theRopeNeighbourPassChecksOwnershipBeforeItReadsOrWrites() throws IOException {
        assertGatedNeighbourWrite("RopeBlockBehavior.java", "public static void refreshAdjacentRopes(");
    }

    @Test
    void theTatamiNeighbourPassChecksOwnershipBeforeItReadsOrWrites() throws IOException {
        assertGatedNeighbourWrite("TatamiPairingBehavior.java", "public static void resetFacingNeighbors(");
    }

    @Test
    void bothPassesSkipTheUnownedNeighbourInsteadOfDispatchingItElsewhere() throws IOException {
        for (String file : new String[]{"RopeBlockBehavior.java", "TatamiPairingBehavior.java"}) {
            String body = file.startsWith("Rope")
                    ? body(file, "public static void refreshAdjacentRopes(")
                    : body(file, "public static void resetFacingNeighbors(");
            assertTrue(body.contains("if (!Bukkit.isOwnedByCurrentRegion(neighbor)) { continue; }"),
                    file + " has to skip the neighbour it does not own, not schedule the write for another region");
            assertTrue(body.indexOf("runAt(") < 0,
                    file + " must not hand a neighbour write to another region: the next neighbour update retries");
        }
    }

    @Test
    void theConnectedRugNeighbourPassChecksOwnershipBeforeItReadsOrWrites() throws IOException {
        String body = body("ConnectedRugBlockBehavior.java", "private void refreshNeighbors(World world, Block center)");
        int gate = body.indexOf("isOwnedByCurrentRegion(neighbor)");
        int read = body.indexOf("getStateIfResident(neighbor)");
        int write = body.indexOf("writeVariant(world, neighbor");
        assertTrue(gate >= 0, "refreshing a neighbour has to check that this thread owns its chunk first");
        assertTrue(read > gate, "a loaded neighbour can belong to another region, so ownership is checked before"
                + " the state lookup");
        assertTrue(write > read, "the variant rewrite writes the neighbour, so ownership is checked before it");
    }

    @Test
    void theDoubleBlockPlacementChecksOwnershipBeforeItWritesThePartner() throws IOException {
        String body = body("DoubleBlockRugBlockBehavior.java",
                "private void placeDoubleBlock(World world, Block self, ImmutableBlockState state)");
        int gate = body.indexOf("isOwnedByCurrentRegion(partner)");
        int write = body.indexOf("CraftEngineBlocks.place(partner");
        assertTrue(gate >= 0, "placing the foot half has to check that this thread owns the partner cell");
        assertTrue(write > gate, "the foot cell can belong to another region, so ownership is checked before the"
                + " place");
    }

    @Test
    void theDoubleBlockRemovalChecksOwnershipBeforeItLoadsAndClearsThePartner() throws IOException {
        String body = body("DoubleBlockRugBlockBehavior.java", "private void clearPartner(Block partner, Player breaker)");
        int gate = body.indexOf("isOwnedByCurrentRegion(partner)");
        int load = body.indexOf("CraftEngineBlocks.getCustomBlockState(partner)");
        int clear = body.indexOf("partner.setType(Material.AIR");
        assertTrue(gate >= 0, "clearing the partner has to check that this thread owns its cell first");
        assertTrue(load > gate, "the lookup loads the partner chunk, so ownership is checked before it");
        assertTrue(clear > load, "the clear writes the partner, so ownership is checked before it");
    }

    @Test
    void theRugPassesSkipTheUnownedCellInsteadOfDispatchingItElsewhere() throws IOException {
        assertSkippedNotDispatched("ConnectedRugBlockBehavior.java",
                "private void refreshNeighbors(World world, Block center)",
                "if (!Bukkit.isOwnedByCurrentRegion(neighbor)) { continue; }");
        assertSkippedNotDispatched("DoubleBlockRugBlockBehavior.java",
                "private void placeDoubleBlock(World world, Block self, ImmutableBlockState state)",
                "if (!Bukkit.isOwnedByCurrentRegion(partner)) { return; }");
        assertSkippedNotDispatched("DoubleBlockRugBlockBehavior.java",
                "private void clearPartner(Block partner, Player breaker)",
                "if (!Bukkit.isOwnedByCurrentRegion(partner)) { return; }");
    }

    @Test
    void theRugPlacementPathReadsThePlacementArgumentLayout() throws IOException {
        String placement = body("ConnectedRugBlockBehavior.java",
                "public void placeMultiState(Object thisBlock, Object[] args)");
        assertTrue(placement.contains("recomputeSelf(args[0], args[1])"),
                "a placement carries the level first and the block position second");
        assertFalse(placement.contains("handleNeighborUpdate("),
                "the neighbour-change argument layout is not the placement one");

        String recompute = body("ConnectedRugBlockBehavior.java",
                "private void recomputeSelf(Object levelArg, Object posArg)");
        assertTrue(recompute.contains("toWorld(levelArg)") && recompute.contains("toBlockPos(posArg)"),
                "the shared recompute reads the level and the position it was handed");

        String neighbour = body("ConnectedRugBlockBehavior.java",
                "public void neighborChanged(Object thisBlock, Object[] args)");
        assertTrue(neighbour.contains("recomputeSelf(args[1], args[2])"),
                "a neighbour change carries the previous state first, so the level is its second argument");
    }

    @Test
    void theDoubleBlockRemovalChecksResidencyBeforeItLoadsThePartner() throws IOException {
        String body = body("DoubleBlockRugBlockBehavior.java", "private void clearPartner(Block partner, Player breaker)");
        int residency = body.indexOf("isChunkLoaded(partner");
        int gate = body.indexOf("isOwnedByCurrentRegion(partner)");
        int load = body.indexOf("CraftEngineBlocks.getCustomBlockState(partner)");
        assertTrue(residency >= 0, "clearing the partner has to skip a cell whose chunk is not resident");
        assertTrue(load > residency, "the lookup would load the partner chunk, so residency is checked first");
        assertTrue(gate > residency, "the region gate follows the residency check on the same cell");
        assertTrue(load > gate, "ownership is checked before the partner state is read");
    }

    @Test
    void theRugVariantMaskSkipsUnownedNeighbours() throws IOException {
        String mask = body("ConnectedRugBlockBehavior.java",
                "private void writeVariant(World world, Block self, ImmutableBlockState state)");
        int gate = mask.indexOf("isOwnedByCurrentRegion(neighbor)");
        int read = mask.indexOf("getStateIfResident(neighbor)");
        assertTrue(gate >= 0, "computing the frayed-edge mask reads four neighbours, so each one needs the gate");
        assertTrue(read > gate, "an unowned neighbour has to count as unconnected instead of being read");
    }

    @Test
    void theRemoveHookClearsThePartnerWithoutStashingThePlayer() throws IOException {
        String file = source("DoubleBlockRugBlockBehavior.java");
        assertFalse(file.contains("PendingBreak") || file.contains("pendingBreak"),
                "the break hook carries the player itself, so no stashed copy may exist");

        String destroy = body("DoubleBlockRugBlockBehavior.java",
                "public Object playerWillDestroy(Object thisBlock, Object[] args)");
        assertTrue(destroy.contains("clearPartner(partnerOf(broken, state), player)"),
                "the teardown has to hand the player the hook received straight to the clear");
        assertTrue(destroy.contains("return args[2]"), "the destruction state must be returned unchanged");
    }

    @Test
    void thePartnerClearChecksBuildPermissionWithTheHookPlayer() throws IOException {
        String clear = body("DoubleBlockRugBlockBehavior.java",
                "private void clearPartner(Block partner, Player breaker)");
        int permission = clear.indexOf("!ProtectionCompat.canPlace(breaker, partner,");
        int write = clear.indexOf("partner.setType(Material.AIR");
        assertTrue(permission >= 0, "the teardown is a block state change, so it needs a build check");
        assertTrue(clear.contains("if (breaker != null && !ProtectionCompat.canPlace(breaker, partner,"
                        + " (ProtectionCompat.Feature) null)) { return; }"),
                "a rejected check has to leave the half standing instead of clearing it");
        assertTrue(write > permission, "the permission check has to come before the partner is cleared");
    }

    @Test
    void theRemovalCallbacksKeepThePlainTeardown() throws IOException {
        String removal = body("DoubleBlockRugBlockBehavior.java", "private void handleRemoval(Object[] args)");
        assertTrue(removal.contains("clearPartner(partner, null)"),
                "the removal callbacks have no player, so they clear the partner without a permission subject");
    }

    private static void assertSkippedNotDispatched(String file, String marker, String skip) throws IOException {
        String body = body(file, marker);
        assertTrue(body.contains(skip), file + " has to skip the cell it does not own instead of writing it");
        assertTrue(body.indexOf("runAt(") < 0,
                file + " must not hand a neighbour write to another region: the next neighbour update retries");
    }

    private static void assertGatedNeighbourWrite(String file, String marker) throws IOException {
        String body = body(file, marker);
        int gate = body.indexOf("isOwnedByCurrentRegion(neighbor)");
        int read = body.indexOf("getStateIfResident(neighbor)");
        int write = body.indexOf("CraftEngineBlocks.place(neighbor");
        assertTrue(gate >= 0, file + " has to gate its neighbour on region ownership: a loaded chunk can belong to"
                + " another region, so the lookup and the place would run on the wrong thread");
        assertTrue(read > gate, file + " has to check ownership before it reads the neighbour state");
        assertTrue(write > read, file + " has to check ownership before it writes the neighbour");
    }

    private static String source(String file) throws IOException {
        Path path = SOURCES.resolve(file);
        assertTrue(Files.isRegularFile(path), file + " has to exist under " + SOURCES.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static String body(String file, String marker) throws IOException {
        String text = source(file);
        int start = text.indexOf(marker);
        assertTrue(start >= 0, file + " has to declare " + marker);
        int end = text.indexOf("\n    }", start);
        assertTrue(end > start, file + ": the body of " + marker + " has no closing brace");
        return text.substring(start, end).replaceAll("\\s+", " ");
    }
}
