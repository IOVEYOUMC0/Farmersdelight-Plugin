package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed gui.yml config for the recipe editor GUI. Reuses RecipeViewGuiConfig.BaseConfig
 * to parse layout/legend/items, following the per-custom-pot override pattern of
 * recipe-detail-cooking-pot-guis.
 */
public final class RecipeEditorGuiConfig {

    private static final String COOKING_POT_KEY = "recipe-editor-gui";
    private static final String COOKING_POT_CUSTOM_KEY = "recipe-editor-cooking-pot-guis";
    private static final String CUTTING_BOARD_KEY = "recipe-cutting-board-editor-gui";
    private static final String CONFIRM_DELETE_KEY = "recipe-editor-confirm-delete-gui";
    private static final String CHOICE_BUILDER_KEY = "recipe-choice-builder-gui";
    private static final String TAG_PICKER_KEY = "recipe-tag-picker-gui";

    @Nullable
    private final RecipeViewGuiConfig.BaseConfig cookingPotDefault;
    private final Map<String, RecipeViewGuiConfig.BaseConfig> cookingPotCustom;
    @Nullable
    private final RecipeViewGuiConfig.BaseConfig cuttingBoard;
    @Nullable
    private final RecipeViewGuiConfig.BaseConfig confirmDelete;
    @Nullable
    private final RecipeViewGuiConfig.BaseConfig choiceBuilder;
    @Nullable
    private final RecipeViewGuiConfig.BaseConfig tagPicker;
    private final Set<String> warnedMissingCustom = ConcurrentHashMap.newKeySet();

    private RecipeEditorGuiConfig(@Nullable RecipeViewGuiConfig.BaseConfig cookingPotDefault,
                                  Map<String, RecipeViewGuiConfig.BaseConfig> cookingPotCustom,
                                  @Nullable RecipeViewGuiConfig.BaseConfig cuttingBoard,
                                  @Nullable RecipeViewGuiConfig.BaseConfig confirmDelete,
                                  @Nullable RecipeViewGuiConfig.BaseConfig choiceBuilder,
                                  @Nullable RecipeViewGuiConfig.BaseConfig tagPicker) {
        this.cookingPotDefault = cookingPotDefault;
        this.cookingPotCustom = cookingPotCustom;
        this.cuttingBoard = cuttingBoard;
        this.confirmDelete = confirmDelete;
        this.choiceBuilder = choiceBuilder;
        this.tagPicker = tagPicker;
    }

    public static RecipeEditorGuiConfig fromConfig(@Nullable ConfigurationSection guiRoot) {
        if (guiRoot == null) {
            return new RecipeEditorGuiConfig(null, Map.of(), null, null, null, null);
        }

        RecipeViewGuiConfig.BaseConfig potDefault =
                RecipeViewGuiConfig.BaseConfig.parseConfig(guiRoot.getConfigurationSection(COOKING_POT_KEY));

        Map<String, RecipeViewGuiConfig.BaseConfig> custom = new HashMap<>();
        ConfigurationSection customSection = guiRoot.getConfigurationSection(COOKING_POT_CUSTOM_KEY);
        if (customSection != null) {
            for (String id : customSection.getKeys(false)) {
                RecipeViewGuiConfig.BaseConfig parsed =
                        RecipeViewGuiConfig.BaseConfig.parseConfig(customSection.getConfigurationSection(id));
                if (parsed != null) {
                    custom.put(id, parsed);
                }
            }
        }

        RecipeViewGuiConfig.BaseConfig board =
                RecipeViewGuiConfig.BaseConfig.parseConfig(guiRoot.getConfigurationSection(CUTTING_BOARD_KEY));
        RecipeViewGuiConfig.BaseConfig confirm =
                RecipeViewGuiConfig.BaseConfig.parseConfig(guiRoot.getConfigurationSection(CONFIRM_DELETE_KEY));
        RecipeViewGuiConfig.BaseConfig choice =
                RecipeViewGuiConfig.BaseConfig.parseConfig(guiRoot.getConfigurationSection(CHOICE_BUILDER_KEY));
        RecipeViewGuiConfig.BaseConfig tagPickerConfig =
                RecipeViewGuiConfig.BaseConfig.parseConfig(guiRoot.getConfigurationSection(TAG_PICKER_KEY));

        return new RecipeEditorGuiConfig(potDefault, custom, board, confirm, choice, tagPickerConfig);
    }

    /**
     * the editor layout for the given custom pot group (returns the default layout when the group is
     *         null/blank or has no dedicated config section); returns null when no editor config section is configured at all.
     */
    @Nullable
    public RecipeViewGuiConfig.BaseConfig getCookingPotConfig(@Nullable String customGroupId) {
        if (customGroupId == null || customGroupId.isBlank()) {
            return cookingPotDefault;
        }
        RecipeViewGuiConfig.BaseConfig custom = cookingPotCustom.get(customGroupId);
        if (custom != null) {
            return custom;
        }
        if (warnedMissingCustom.add(customGroupId)) {
            I18n.logWarning("recipe.editor_missing_custom_pot_gui", "id", customGroupId);
        }
        return cookingPotDefault;
    }

    @Nullable
    public RecipeViewGuiConfig.BaseConfig getCuttingBoardConfig() {
        return cuttingBoard;
    }

    @Nullable
    public RecipeViewGuiConfig.BaseConfig getConfirmDeleteConfig() {
        return confirmDelete;
    }

    @Nullable
    public RecipeViewGuiConfig.BaseConfig getChoiceBuilderConfig() {
        return choiceBuilder;
    }

    @Nullable
    public RecipeViewGuiConfig.BaseConfig getTagPickerConfig() {
        return tagPicker;
    }
}
