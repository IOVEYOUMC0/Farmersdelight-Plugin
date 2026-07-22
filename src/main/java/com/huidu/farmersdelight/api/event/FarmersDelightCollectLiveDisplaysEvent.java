package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Set;

/**
 * Fired by /fd cleanup BEFORE FarmersDelight removes orphan proxy item-displays, so addons that own
 * their own packet item-displays (e.g. the items shown on a coaster) can mark theirs as live and keep them
 * from being swept as orphans. Listeners add their display entity ids via addLiveId(int) /
 * addLiveIds(Collection); FarmersDelight then treats every id in the set as in-use.
 *
 * <p>Mirrors FarmersDelightCleanupEvent, but runs first and is protective (it prevents removals)
 * rather than reporting removals. Listeners must only ADD ids — the set already holds FarmersDelight's own
 * live displays.
 */
@ApiStatus.NonExtendable
public class FarmersDelightCollectLiveDisplaysEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<Integer> liveIds;

    public FarmersDelightCollectLiveDisplaysEvent(Set<Integer> liveIds) {
        this.liveIds = liveIds;
    }

    /** Mark a display entity id as live so /fd cleanup keeps it. */
    public void addLiveId(int entityId) {
        liveIds.add(entityId);
    }

    /** Mark several display entity ids as live. */
    public void addLiveIds(Collection<Integer> entityIds) {
        liveIds.addAll(entityIds);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
