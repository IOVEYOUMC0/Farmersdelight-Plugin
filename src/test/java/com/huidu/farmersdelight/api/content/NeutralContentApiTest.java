package com.huidu.farmersdelight.api.content;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Guards the neutral content package: no CraftEngine type may leak into it. */
class NeutralContentApiTest {

    @Test
    void contentPackageHasNoCraftEngineReferences() throws Exception {
        for (String file : List.of("ContentId.java", "BlockStateRef.java", "FarmersDelightContent.java")) {
            String source = read("api/content/" + file);
            assertFalse(source.contains("net.momirealms"), file + " must stay CraftEngine-neutral");
        }
    }

    @Test
    void nullNeutralIdsAreReportedAbsent() {
        assertFalse(FarmersDelightContent.isItemPresent((ContentId) null));
        assertFalse(FarmersDelightContent.isBlockPresent((ContentId) null));
        assertFalse(FarmersDelightContent.isItemOrBlockPresent((ContentId) null));
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
