package com.huidu.farmersdelight.advancement;

/**
 * Checks for the patched advancement-layout class before tabs are registered.
 * The three-argument registration overload requires that build; probing its companion class
 * avoids resolving unrelated optional types in public method signatures.
 * The dependency loads before this plugin, so the result is cached once.
 */
public final class AutomaticAdvancementLayout {

    private static final String LAYOUT_CLASS =
            "com.fren_gor.ultimateAdvancementAPI.util.AdvancementLayout";
    private static final boolean SUPPORTED = detect();

    private AutomaticAdvancementLayout() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    private static boolean detect() {
        try {
            Class.forName(LAYOUT_CLASS, false, AutomaticAdvancementLayout.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
