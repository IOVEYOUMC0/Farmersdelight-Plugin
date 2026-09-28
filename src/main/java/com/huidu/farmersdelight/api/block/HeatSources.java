package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.api.PluginAccess;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers per-plugin heat sources and conductors, replaying them when the heat table reloads.
 * Vanilla blocks match by ID. CE blocks may also carry state filters so an unlit state does not
 * provide heat. Conductors pass heat from below; custom tags match groups of CE blocks.
 * Query the effective table through FarmersDelightApi.isHeatSource and isConductor.
 *
 * <p>A state filter only narrows the strict question ("is this block hot right now") that the pot, the placed
 * skillet, the tray and the stove ask. The portable (handheld) skillet asks whether a heat source is nearby at
 * all, so a registered block still starts handheld cooking while its filtered state is off — the same split the
 * mod has between HeatableBlockEntity#isHeated and SkilletItem#isPlayerNearHeatSource.
 */
public final class HeatSources {

    private static final Map<String, HeatSources> GROUPS = new ConcurrentHashMap<>();

    private final Set<String> vanillaBlocks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> conductors = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> customBlocks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> customTags = Collections.synchronizedSet(new LinkedHashSet<>());

    private final String owner;

    private HeatSources(String owner) {
        this.owner = owner;
    }

    /** The heat sources declared by this plugin, created on first use. */
    public static HeatSources of(Plugin owner) {
        return GROUPS.computeIfAbsent(owner.getName(), HeatSources::new);
    }

    /** Declares a vanilla block id, "minecraft:campfire", to be a heat source. */
    public void addVanillaBlock(String vanillaBlockId) {
        if (blank(vanillaBlockId)) {
            return;
        }
        this.vanillaBlocks.add(vanillaBlockId);
        HeatSourceConfig config = config();
        if (config != null) {
            config.addVanillaBlock(vanillaBlockId);
        }
    }

    /** Declares a vanilla block id to pass heat up from whatever is under it. */
    public void addConductor(String vanillaBlockId) {
        if (blank(vanillaBlockId)) {
            return;
        }
        this.conductors.add(vanillaBlockId);
        HeatSourceConfig config = config();
        if (config != null) {
            config.addVanillaConductor(vanillaBlockId);
        }
    }

    /**
     * Declares a CraftEngine block, optionally narrowed to one state, to be a heat source. Returns false
     * when the id could not be parsed or FarmersDelight is unavailable.
     */
    public boolean addCustomBlock(String blockIdWithOptionalState) {
        if (blank(blockIdWithOptionalState)) {
            return false;
        }
        HeatSourceConfig config = config();
        if (config == null || !config.addCustomBlockState(blockIdWithOptionalState)) {
            return false;
        }
        this.customBlocks.add(blockIdWithOptionalState);
        return true;
    }

    /** Declares every block carrying a CraftEngine block tag to be a heat source. */
    public void addCustomTag(String tagId) {
        if (blank(tagId)) {
            return;
        }
        this.customTags.add(tagId);
        HeatSourceConfig config = config();
        if (config != null) {
            config.addCustomBlockTag(Key.of(tagId));
        }
    }

    /**
     * Drops a previous declaration of any kind, so it stops being replayed across reloads. The current
     * table keeps it until the next reload, because the table is rebuilt rather than edited.
     */
    public boolean remove(String id) {
        if (id == null) {
            return false;
        }
        return this.vanillaBlocks.remove(id) | this.conductors.remove(id)
                | this.customBlocks.remove(id) | this.customTags.remove(id);
    }

    /** Drops every declaration by this plugin. Call from onDisable. */
    public void clear() {
        this.vanillaBlocks.clear();
        this.conductors.clear();
        this.customBlocks.clear();
        this.customTags.clear();
        GROUPS.remove(this.owner, this);
    }

    /** How many declarations this plugin holds, across all four kinds. */
    public int size() {
        return this.vanillaBlocks.size() + this.conductors.size()
                + this.customBlocks.size() + this.customTags.size();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static HeatSourceConfig config() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin == null ? null : plugin.getHeatSourceConfig();
    }

    /**
     * Replays every plugin's declarations into a freshly built table. FarmersDelight calls this right
     * after it reloads its own heat-source config; addons never call it.
     */
    @ApiStatus.Internal
    public static void replayAll(HeatSourceConfig config) {
        if (config == null) {
            return;
        }
        for (HeatSources group : GROUPS.values()) {
            for (String id : Set.copyOf(group.vanillaBlocks)) {
                config.addVanillaBlock(id);
            }
            for (String id : Set.copyOf(group.conductors)) {
                config.addVanillaConductor(id);
            }
            for (String id : Set.copyOf(group.customBlocks)) {
                config.addCustomBlockState(id);
            }
            for (String id : Set.copyOf(group.customTags)) {
                config.addCustomBlockTag(Key.of(id));
            }
        }
    }

    /** The group the deprecated FarmersDelightApi heat-source methods write into. */
    @ApiStatus.Internal
    public static HeatSources legacy() {
        return GROUPS.computeIfAbsent("(api)", HeatSources::new);
    }
}
