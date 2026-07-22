package com.huidu.farmersdelight.api.recipe;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;

/**
 * Describes how a RecipeType's recipes are edited in the generic editor GUI, and how edits are
 * persisted. The addon owns the actual storage (its yml files); the editor only drives the GUI and
 * hands back an EditableRecipe to save/delete.
 *
 * Lives in the name-stable api package; uses only api / java types.
 */
@ApiStatus.OverrideOnly
public interface RecipeEditor {

    /** Labels for the editable item slots, in order (e.g. ingredient slots + a fluid slot). */
    List<String> itemSlotLabels();

    /** Editable numeric fields (e.g. ferment time, experience). May be empty. */
    List<NumericField> numericFields();

    /** Loads an existing recipe into a draft, or a blank draft when id is null/blank. */
    EditableRecipe load(String id);

    /** Persists the draft (write to the addon's storage + reload). Returns true on success. */
    boolean save(EditableRecipe draft);

    /** Removes the recipe with the given id. Returns true on success. */
    boolean delete(String id);
}
