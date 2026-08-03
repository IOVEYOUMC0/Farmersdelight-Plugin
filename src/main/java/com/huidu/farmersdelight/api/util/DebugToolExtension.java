package com.huidu.farmersdelight.api.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Extension point for /fd debugtools. An addon (e.g. Brewin' And Chewin') implements this
 * interface to plug a new <em>target</em> (like keg) into the existing command. FarmersDelight
 * itself only ships when built with -PdebugTools=true; the registry is otherwise dormant and
 * the extension's #place/#activate/#status are simply never invoked.
 *
 * Register an instance once at plugin enable via
 * DebugToolRegistry#register(DebugToolExtension). The #name() becomes the target
 * keyword in /fd debugtools place <name> ....
 */
@ApiStatus.OverrideOnly
public interface DebugToolExtension {

    /** Lower-case target keyword (e.g. "keg"). Used both for command parsing and tab-complete. */
    String name();

    /**
     * Mass-place this extension's block in a grid starting at origin. The exact placement
     * pattern is the extension's choice; FD's built-in implementations use grid = ceil(sqrt(count))
     * with spacing between cells and layers stacked vertically.
     *
     * Call undo.capture(loc) BEFORE mutating each target block so the placement can be
     * reverted by /fd debugtools undo. Captures that don't end up changing state are silently
     * no-op'd on undo.
     *
     * how many blocks actually went into the world (≤ count × layers).
     */
    int place(Player player, Location origin, int count, int spacing, int layers, UndoSink undo);

    /** Callback the extension uses to record pre-state into FD's debug-tool undo batch. */
    @FunctionalInterface
    interface UndoSink {
        /** Snapshot the block state at loc so a later /fd debugtools undo can restore it. */
        void capture(Location loc);
    }

    /**
     * Optional: fill placed blocks with sample state so they actually start ticking / fermenting / etc.
     * Default: no-op. Return the number of blocks transitioned to an "active" state.
     */
    default int activate(Player player) {
        return 0;
    }

    /**
     * Optional: status lines shown after the built-in TickManager snapshot in
     * /fd debugtools status. Return an empty list to omit. Each entry is one line.
     */
    default List<String> status(Player player) {
        return List.of();
    }

    /**
     * Optional: called by FD during /fd debugtools undo on every restored location, BEFORE the
     * block data is reverted. Use this to release in-memory tracking and remove block-entity NBT
     * belonging to this extension so an undone placement doesn't leak ghost state. Implementations
     * should no-op cheaply when the location isn't one of their blocks.
     */
    default void cleanupBeforeUndo(Location location) {
    }
}
