package com.huidu.farmersdelight.advancement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream advancement tree has 21 nodes: organic compost is one placement node under harvest_straw and the
 * rich soil goal hangs off it. The two extra middle nodes this port used to declare are gone, so the tree and the
 * award call sites have to stay in line.
 */
class AdvancementTreeUpstreamTest {

    @Test
    void extraMiddleNodesAreGoneAndTheRemainingBranchMatchesUpstream() throws IOException {
        String tree = read("src/main/java/com/huidu/farmersdelight/advancement/AdvancementManager.java");
        assertFalse(tree.contains("node(\"get_organic_compost\""), "upstream has no get_organic_compost node");
        assertFalse(tree.contains("node(\"hoe_rich_soil\""), "upstream has no hoe_rich_soil node");
        assertTrue(tree.contains("node(\"place_organic_compost\", \"harvest_straw\""),
                "place_organic_compost hangs off harvest_straw upstream");
        assertTrue(tree.contains("node(\"get_rich_soil\", \"place_organic_compost\""),
                "get_rich_soil hangs off place_organic_compost upstream");
    }

    @Test
    void awardCallSitesForTheRemovedNodesAreGone() throws IOException {
        String achievements = read("src/main/java/com/huidu/farmersdelight/listener/AchievementListener.java");
        assertFalse(achievements.contains("award(player, \"get_organic_compost\")"),
                "the pickup and craft paths must not award a node that no longer exists");
        String hoe = read("src/main/java/com/huidu/farmersdelight/listener/RichSoilHoeListener.java");
        assertFalse(hoe.contains("hoe_rich_soil"), "the tilling path has no challenge node any more");
    }

    @Test
    void remainingNodesKeepTheirAwardCallSites() throws IOException {
        String achievements = read("src/main/java/com/huidu/farmersdelight/listener/AchievementListener.java");
        assertTrue(achievements.contains("award(player, \"get_rich_soil\")"),
                "get_rich_soil is still the goal awarded by rich soil pickup");
        String blockPlace = read("src/main/java/com/huidu/farmersdelight/listener/BlockPlaceListener.java");
        assertTrue(blockPlace.contains("award(player, \"place_organic_compost\")"),
                "place_organic_compost is still awarded on placement");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
