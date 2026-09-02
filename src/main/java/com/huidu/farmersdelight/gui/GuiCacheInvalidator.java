package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;

/** Keeps the coupled recipe GUI caches and open views in sync after a reload. */
public final class GuiCacheInvalidator {

    private GuiCacheInvalidator() {
    }

    public static void clearConfigCaches() {
        RecipeViewGui.clearConfigCache();
        RecipeBookGui.clearConfigCache();
    }

    public static void clearConfigCachesAndCloseOpenGuis() {
        clearConfigCaches();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
    }
}
