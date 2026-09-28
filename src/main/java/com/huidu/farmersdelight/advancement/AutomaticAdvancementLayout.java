package com.huidu.farmersdelight.advancement;

/**
 * Reports whether the installed UltimateAdvancementAPI carries the vanilla tidy-tree layout.
 *
 * <p>Every tab is registered through AdvancementTab.registerAdvancements(RootAdvancement, Set,
 * boolean), which only the patched UltimateAdvancementAPI build declares; upstream builds expose the
 * two-argument overload alone. Reaching that call on an unpatched build throws NoSuchMethodError
 * while the tab is being built, which discards the tree and leaves every advancement missing, so the
 * check runs before the advancement system starts.
 *
 * <p>The check is the presence of util.AdvancementLayout, the class the patched build added
 * together with that overload. Looking the overload up instead would be less reliable: resolving a
 * method forces the JVM to resolve every other public method signature too, so an unrelated optional
 * dependency of UltimateAdvancementAPI being absent would look like a missing overload.
 *
 * <p>The answer is resolved once: the dependency is declared load: BEFORE in paper-plugin.yml, so
 * the classpath is final by the time any caller asks.
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
