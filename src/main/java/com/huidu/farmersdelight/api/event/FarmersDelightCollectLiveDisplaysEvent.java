package com.huidu.farmersdelight.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;

@ApiStatus.NonExtendable
public class FarmersDelightCollectLiveDisplaysEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<Integer> liveIds;

    public FarmersDelightCollectLiveDisplaysEvent(Set<Integer> liveIds) {
        this.liveIds = liveIds;
    }

    public void addLiveId(int entityId) {
        liveIds.add(entityId);
    }

    public void addLiveIds(Collection<Integer> entityIds) {
        liveIds.addAll(entityIds);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
