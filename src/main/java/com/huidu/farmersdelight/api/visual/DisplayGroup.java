package com.huidu.farmersdelight.api.visual;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A keyed set of packet displays owned by one plugin, covering both item and text displays.
 *
 * <p>The raw api hands back a bare int handle and leaves the rest to the caller: storing the handle,
 * replacing it when what is shown changes, destroying it on teardown, and declaring it live so the
 * /fd cleanup orphan sweep does not remove it. Every addon that shows something wrote that same
 * bookkeeping again, and the liveness half is easy to get subtly wrong -- a display nothing declares is
 * swept on every cleanup and rebuilt right after, so it appears to flicker back.
 *
 * <p>A group does that once. Handles are declared live for as long as the group holds them, so an addon
 * using a group needs no FarmersDelightCollectLiveDisplaysEvent listener of its own. Item and text
 * displays share one handle space and one key space here, because they share one manager underneath.
 *
 * <p>Threading is unchanged: every call must run on the region that owns the location, exactly as with
 * the raw api. Use FarmersDelightApi.get().runAtLocation(location, runnable) when off-region.
 */
public final class DisplayGroup {

    private static final Map<String, DisplayGroup> GROUPS = new ConcurrentHashMap<>();

    // A text display can only have its text updated in place; its location and transform are fixed at
    // creation. Remembering the location a key was placed at is what lets showText notice a move and
    // recreate instead of silently leaving the text behind at the old spot.
    private record Entry(int handle, boolean text, Location location) {
    }

    private final String owner;
    private final Map<Object, Entry> entries = new ConcurrentHashMap<>();

    private DisplayGroup(String owner) {
        this.owner = owner;
    }

    /** The group for this plugin, created on first use. One group per plugin is the intended shape. */
    public static DisplayGroup of(Plugin owner) {
        return GROUPS.computeIfAbsent(owner.getName(), DisplayGroup::new);
    }

    /**
     * Shows an item at a location under the given key, replacing whatever that key showed before.
     * Returns the handle, or -1 when FarmersDelight is unavailable or the display could not be created.
     *
     * <p>The key is the caller's own anchor -- a block position, a slot index, a record. Anything with
     * sane equals and hashCode works.
     */
    public int showItem(Object key, Location location, ItemStack item,
                        ItemDisplay.ItemDisplayTransform itemTransform, Transformation transformation) {
        return showItem(key, location, item, itemTransform, transformation, 0);
    }

    /** As showItem, but interpolates the transform change over the given number of ticks (0 snaps). */
    public int showItem(Object key, Location location, ItemStack item,
                        ItemDisplay.ItemDisplayTransform itemTransform, Transformation transformation,
                        int interpolationDurationTicks) {
        if (key == null || location == null || item == null) {
            return -1;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        if (!api.isAvailable()) {
            return -1;
        }
        Entry existing = this.entries.get(key);
        if (existing != null && !existing.text()
                && api.updateItemDisplay(existing.handle(), location, item, itemTransform,
                        transformation, interpolationDurationTicks)) {
            this.entries.put(key, new Entry(existing.handle(), false, location.clone()));
            return existing.handle();
        }
        drop(key, existing);
        int handle = api.createItemDisplay(location, item, itemTransform, transformation);
        if (handle == -1) {
            return -1;
        }
        this.entries.put(key, new Entry(handle, false, location.clone()));
        return handle;
    }

    /**
     * Shows text at a location under the given key, replacing whatever that key showed before. A text
     * display cannot be moved in place, so changing the location recreates it; changing only the text
     * updates it.
     */
    public int showText(Object key, Location location, Component text, Transformation transformation,
                        Color backgroundColor, boolean shadowed, boolean seeThrough) {
        if (key == null || location == null || text == null) {
            return -1;
        }
        FarmersDelightApi api = FarmersDelightApi.get();
        if (!api.isAvailable()) {
            return -1;
        }
        Entry existing = this.entries.get(key);
        if (existing != null && existing.text() && sameBlock(existing.location(), location)
                && api.updateTextDisplay(existing.handle(), text)) {
            return existing.handle();
        }
        drop(key, existing);
        int handle = api.createTextDisplay(location, text, transformation, backgroundColor, shadowed, seeThrough);
        if (handle == -1) {
            return -1;
        }
        this.entries.put(key, new Entry(handle, true, location.clone()));
        return handle;
    }

    /** Removes the display shown under this key, if any. Returns true when something was removed. */
    public boolean hide(Object key) {
        Entry entry = key == null ? null : this.entries.remove(key);
        if (entry == null) {
            return false;
        }
        destroy(entry);
        return true;
    }

    /** Removes every display in the group. */
    public void hideAll() {
        for (Object key : Set.copyOf(this.entries.keySet())) {
            Entry entry = this.entries.remove(key);
            if (entry != null) {
                destroy(entry);
            }
        }
    }

    /** The handle currently shown under this key, or -1. */
    public int handle(Object key) {
        Entry entry = key == null ? null : this.entries.get(key);
        return entry == null ? -1 : entry.handle();
    }

    /** How many displays the group is holding. */
    public int size() {
        return this.entries.size();
    }

    /** Removes every display and drops the group. Call this from the owning plugin's onDisable. */
    public void close() {
        hideAll();
        GROUPS.remove(this.owner, this);
    }

    // The stored handle no longer refers to a live display (a world unload or a manager reload drops it),
    // or the key is changing kind or position. Either way the old one goes before a new one is made.
    private void drop(Object key, Entry existing) {
        if (existing == null) {
            return;
        }
        this.entries.remove(key, existing);
        destroy(existing);
    }

    private void destroy(Entry entry) {
        FarmersDelightApi api = FarmersDelightApi.get();
        if (!api.isAvailable()) {
            return;
        }
        if (entry.text()) {
            api.removeTextDisplay(entry.handle());
        } else {
            api.removeItemDisplay(entry.handle());
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    /** Feeds every group's handles into the /fd cleanup orphan sweep so they are never swept. */
    @ApiStatus.Internal
    public static void collectLiveHandles(Set<Integer> liveIds) {
        for (DisplayGroup group : GROUPS.values()) {
            for (Entry entry : group.entries.values()) {
                liveIds.add(entry.handle());
            }
        }
    }
}
