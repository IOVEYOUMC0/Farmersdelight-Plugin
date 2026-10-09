package com.huidu.farmersdelight.api.recipe;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import java.util.function.Consumer;

@ApiStatus.OverrideOnly
public interface RecipeEditor {

    List<String> itemSlotLabels();

    List<NumericField> numericFields();

    EditableRecipe load(String id);

    boolean save(EditableRecipe draft);

    boolean delete(String id);

    /** Returns false only when the operation was not queued; completion may run on any thread. */
    default boolean saveAsync(EditableRecipe draft, Consumer<Boolean> completion) {
        completion.accept(save(draft));
        return true;
    }

    default boolean deleteAsync(String id, Consumer<Boolean> completion) {
        completion.accept(delete(id));
        return true;
    }
}
