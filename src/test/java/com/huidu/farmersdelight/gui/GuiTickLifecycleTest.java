package com.huidu.farmersdelight.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the no-op editor tick registrations and the quit cleanup from regressing. */
class GuiTickLifecycleTest {

    @Test
    void onlyGuisWithTickWorkRegisterCallbacks() throws IOException {
        String base = read("src/main/java/com/huidu/farmersdelight/gui/AbstractInventoryGui.java");
        assertTrue(base.contains("protected boolean needsTick()"));
        assertTrue(base.contains("if (needsTick())"), "the callback registration has to be conditional");

        for (String editor : List.of("ChoiceBuilderGui", "ConfirmGui", "CookingPotEditorGui",
                "CuttingBoardEditorGui", "TagPickerGui")) {
            String source = read("src/main/java/com/huidu/farmersdelight/gui/editor/" + editor + ".java");
            assertTrue(source.contains("protected boolean needsTick()"), editor + " should opt out");
            assertTrue(source.contains("return false;"), editor + " has no tick work");
        }
        for (String ticking : List.of("CookingPotGui", "RecipeViewGui")) {
            String source = read("src/main/java/com/huidu/farmersdelight/gui/" + ticking + ".java");
            assertFalse(source.contains("boolean needsTick()"), ticking + " overrides onTick and must keep ticking");
        }
    }

    @Test
    void editorQuitRemovesTheTickCallbackAndGuiTickManagerSupportsIt() throws IOException {
        String manager = read("src/main/java/com/huidu/farmersdelight/gui/GuiTickManager.java");
        assertTrue(manager.contains("public synchronized void unregisterPlayer(Player player)"));
        assertTrue(manager.contains("playerTickCallbacks.entrySet().removeIf"),
                "the quit cleanup must remove every callback of that player");

        String listener = read("src/main/java/com/huidu/farmersdelight/gui/editor/RecipeEditorListener.java");
        int quit = listener.indexOf("public void onQuit(PlayerQuitEvent event)");
        assertTrue(quit > 0);
        assertTrue(listener.indexOf("unregisterPlayer(event.getPlayer())", quit) > quit,
                "a quit without a close event must still release the editor tick callback");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
