package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Builds an advancement display the installed UltimateAdvancementAPI can render.
 *
 * The component constructor (per-client localized titles) is part of the build that ships with this project. With
 * any other build the display falls back to English text read from the resource pack, so the tab still shows
 * readable names instead of translation keys, and the announcement falls back to that build's own English message.
 * The installed API is probed once: the dependency loads before this plugin, and its classes do not change later.
 */
final class AdvancementDisplaySupport {

    private static final boolean COMPONENT_DISPLAY = detectComponentDisplay();
    private static final AdvancementEnglishNames ENGLISH = new AdvancementEnglishNames();

    private AdvancementDisplaySupport() {
    }

    /** Whether the installed API renders client translation keys (the bundled build does). */
    static boolean isComponentDisplaySupported() {
        return COMPONENT_DISPLAY;
    }

    static AdvancementDisplay create(Plugin plugin, String namespace, ItemStack icon, String titleKey,
                                     String descriptionKey, AdvancementFrameType frame, boolean showToast,
                                     boolean announceChat, float x, float y) {
        if (COMPONENT_DISPLAY) {
            return componentDisplay(icon, titleKey, descriptionKey, frame, showToast, announceChat, x, y);
        }
        return new AdvancementDisplay(icon, ENGLISH.lookup(plugin, namespace, titleKey), frame, showToast,
                announceChat, x, y, ENGLISH.lookup(plugin, namespace, descriptionKey));
    }

    @SuppressWarnings("deprecation")
    private static AdvancementDisplay componentDisplay(ItemStack icon, String titleKey, String descriptionKey,
                                                       AdvancementFrameType frame, boolean showToast,
                                                       boolean announceChat, float x, float y) {
        return new AdvancementDisplay(icon, new TranslatableComponent(titleKey),
                new TranslatableComponent(descriptionKey), frame, showToast, announceChat, x, y);
    }

    private static boolean detectComponentDisplay() {
        try {
            return hasComponentConstructor(AdvancementDisplay.class);
        } catch (LinkageError missingApi) {
            // No UltimateAdvancementAPI at all: the advancement module does not run, but detection must not throw.
            return false;
        }
    }

    /** Whether a display class carries the component constructor (only the bundled build does). */
    @SuppressWarnings("deprecation")
    static boolean hasComponentConstructor(Class<?> displayClass) {
        try {
            displayClass.getConstructor(ItemStack.class, BaseComponent.class, BaseComponent.class,
                    AdvancementFrameType.class, boolean.class, boolean.class, float.class, float.class);
            return true;
        } catch (NoSuchMethodException | LinkageError unsupported) {
            return false;
        }
    }
}
