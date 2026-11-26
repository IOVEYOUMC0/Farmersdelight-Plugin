package com.huidu.farmersdelight.api.rule;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The rule contract is the neutral part of the API; CraftEngine belongs in api.compat.ce. */
class NeutralRuleApiTest {

    @Test
    void rulePackageHasNoCraftEngineReferences() throws Exception {
        for (String file : List.of("StationRule.java", "CropRule.java", "RecipeCondition.java",
                "RecipeContext.java", "StationRules.java", "CropRules.java", "RecipeConditions.java")) {
            String source = read("api/rule/" + file);
            assertFalse(source.contains("net.momirealms"), file + " must stay CraftEngine-neutral");
        }
    }

    private static String read(String relative) throws Exception {
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
        throw new AssertionError("source not reachable: " + relative);
    }
}
