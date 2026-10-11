package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.gui.editor.RecipeEditorView;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Entry-level atomic writes on FD's bounded queue; reload and completion run on the recipe-state thread. */
public final class RecipeEditorFiles {
    private RecipeEditorFiles() { }

    /** Synchronous compatibility entry point; GUI callers should use saveAsync. */
    public static boolean save(File file, List<String> path, Map<String, Object> body,
                               Set<String> preservedFields, Runnable reload) {
        return RecipeEditorView.saveAddonFileSync(file, path, body, preservedFields, reload);
    }

    public static boolean saveAsync(File file, List<String> path, Map<String, Object> body,
                                    Set<String> preservedFields, Runnable reload, Consumer<Boolean> completion) {
        return RecipeEditorView.saveAddonFile(file, List.copyOf(path), body == null ? null : Map.copyOf(body),
                Set.copyOf(preservedFields), reload, completion);
    }
}
