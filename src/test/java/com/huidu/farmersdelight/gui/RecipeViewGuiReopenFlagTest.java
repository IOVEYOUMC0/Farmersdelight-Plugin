package com.huidu.farmersdelight.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reopening the view must not leave ignoreNextClose set when openInventory throws. */
class RecipeViewGuiReopenFlagTest {

    @Test
    void aFailedReopenClearsTheIgnoreCloseFlag() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/gui/RecipeViewGui.java");
        int method = source.indexOf("private void reopenInventory(Player player)");
        assertTrue(method >= 0, "reopenInventory must exist");
        String body = source.substring(method, Math.min(source.length(), method + 900));
        assertTrue(body.contains("try {"), "the openInventory call must be guarded");
        assertTrue(body.contains("ignoreNextClose = false"), "a failed open must clear the ignore flag");
        assertTrue(body.contains("throw failure"), "the failure must still reach the caller");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
