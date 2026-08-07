package com.huidu.farmersdelight.api.recipe;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;

@ApiStatus.OverrideOnly
public interface RecipeEditor {

    List<String> itemSlotLabels();

    List<NumericField> numericFields();

    EditableRecipe load(String id);

    boolean save(EditableRecipe draft);

    boolean delete(String id);
}
