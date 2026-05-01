package com.huidu.farmersdelight.visual;

import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.UUID;

public interface ItemDisplayManager {

    boolean isAvailable();

    int createDisplay(DisplaySpec spec);

    void destroyDisplay(int entityId);

    void cleanupWorld(UUID worldId);

    void cleanup();

    record DisplaySpec(
            Location location,
            ItemStack itemStack,
            ItemDisplay.ItemDisplayTransform itemTransform,
            Transformation transformation
    ) {
    }
}
