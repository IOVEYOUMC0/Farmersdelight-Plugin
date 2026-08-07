package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    String name();

    int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo);

    @FunctionalInterface
    interface UndoSink {
        void capture(Location loc);
    }

    default int activate(Player player) {
        return 0;
    }

    default List<String> status(Player player) {
        return List.of();
    }

    default void cleanupBeforeUndo(Location location) {
    }
}
