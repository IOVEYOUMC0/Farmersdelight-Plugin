package com.huidu.farmersdelight.api.rule;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the recipe-condition wiring in the real cooking-pot and cutting-board paths. */
class RecipeConditionIntegrationContractTest {

    private static final List<String> SOURCES = List.of(
            "block/behavior/CookingPotBlockEntity.java",
            "block/behavior/CuttingBoardCutter.java");

    @Test
    void recipePathsConsultTheConditionRegistry() throws Exception {
        String cooking = read(SOURCES.get(0));
        assertTrue(cooking.contains("RecipeConditions.hasConditions()"),
                "cooking pot must skip context allocation when no condition is registered");
        assertTrue(cooking.contains("RecipeConditions.allMatch("),
                "cooking pot must evaluate registered conditions");
        assertTrue(cooking.contains("\"cooking_pot\"") && cooking.contains("recipe.getId()"),
                "cooking pot context must carry the matched recipe identity");

        String cutting = read(SOURCES.get(1));
        assertTrue(cutting.contains("RecipeConditions.hasConditions()"),
                "cutting board must skip context allocation when no condition is registered");
        assertTrue(cutting.contains("RecipeConditions.allMatch("),
                "cutting board must evaluate registered conditions");
        assertTrue(cutting.contains("\"cutting_board\"") && cutting.contains("recipe.getId()"),
                "cutting board context must carry the matched recipe identity");
    }

    private static String read(String relative) throws Exception {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && cursor != null; depth++) {
            for (String prefix : new String[]{"src/main/java/com/huidu/farmersdelight/",
                    "FarmersDelight/src/main/java/com/huidu/farmersdelight/"}) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("source not reachable: " + relative);
    }
}
