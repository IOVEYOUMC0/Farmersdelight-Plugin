package com.huidu.farmersdelight.visual;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.UUID;

/**
 * Packet-only display manager. Despite the historical name, it handles both ItemDisplay and
 * TextDisplay proxies through the same lifecycle (createDisplay / updateDisplay / destroyDisplay
 * for items, createTextDisplay / updateText / destroyDisplay for text). Returned entity IDs share
 * the same numeric space so destroyDisplay accepts either kind.
 */
public interface ItemDisplayManager {

    boolean isAvailable();

    int createDisplay(DisplaySpec spec);

    boolean updateDisplay(int entityId, DisplaySpec spec);

    int createTextDisplay(TextDisplaySpec spec);

    boolean updateText(int entityId, Component text);

    void destroyDisplay(int entityId);

    void cleanupWorld(UUID worldId);

    int cleanup();

    /**
     * Removes only orphaned displays — those whose entity id is NOT in liveIds (the set still
     * referenced by a live block owner). Legitimate, in-use displays are left untouched. Returns the
     * number removed. Unlike #cleanup() (a full wipe used on disable), this is the /fd
     * cleanup} command's path so it never removes a display a block still owns.
     */
    int cleanupOrphans(java.util.Set<Integer> liveIds);

    record DisplaySpec(
            Location location,
            ItemStack itemStack,
            ItemDisplay.ItemDisplayTransform itemTransform,
            Transformation transformation
    ) {
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
