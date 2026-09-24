package com.huidu.farmersdelight.visual;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.UUID;
import java.util.Set;

public interface ItemDisplayManager {

    boolean isAvailable();

    int createDisplay(DisplaySpec spec);

    boolean updateDisplay(int entityId, DisplaySpec spec);

    /**
     * Whether the given handle still refers to a managed display. A display is removed without the
     * owner's knowledge by the chunk-unload sweep, world unload, or the /fd cleanup wipe, so owners
     * that cache handles (e.g. station slot displays) should probe this periodically and recreate.
     */
    boolean isActive(int entityId);

    int createTextDisplay(TextDisplaySpec spec);

    boolean updateText(int entityId, Component text);

    void destroyDisplay(int entityId);

    void cleanupWorld(UUID worldId);

    void cleanup();

    int cleanupOrphans(Set<Integer> liveIds);

    record DisplaySpec(
            Location location,
            ItemStack itemStack,
            ItemDisplay.ItemDisplayTransform itemTransform,
            Transformation transformation,
            int interpolationDurationTicks,
            int interpolationDelayTicks
    ) {
        // Backward-compatible: displays with no interpolation (transform changes apply instantly / snap).
        public DisplaySpec(Location location, ItemStack itemStack,
                           ItemDisplay.ItemDisplayTransform itemTransform, Transformation transformation) {
            this(location, itemStack, itemTransform, transformation, 0, 0);
        }
    }

    record TextDisplaySpec(
            Location location,
            Component text,
            Transformation transformation,
            Color backgroundColor,
            boolean shadowed,
            boolean seeThrough
    ) {
    }
}
