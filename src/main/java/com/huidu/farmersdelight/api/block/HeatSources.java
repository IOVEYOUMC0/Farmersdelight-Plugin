package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one plugin has declared to be a heat source, so a cooking pot, skillet or addon station standing
 * on it will cook.
 *
 * <p>The four kinds are separate because they are matched differently. A vanilla block is matched by id.
 * A conductor is a block that passes heat up from the block below it. A custom block is a CraftEngine
 * block and may carry a state filter, "endsdelight:end_stove[fire:true]", which is the form to
 * use for anything that can be switched off: CraftEngine copies block-level settings onto every state, so
 * a tag on the block alone would make the unlit state a heat source too. A custom tag covers a whole
 * CraftEngine block tag at once.
 *
 * <p>Registrations are remembered per plugin and replayed automatically whenever FarmersDelight rebuilds
 * its heat-source table, which happens on every /fd reload. Registering through the raw api and forgetting
 * that is how an addon's heat source used to disappear on the first reload with no log line.
 *
 * <p>Query the result with FarmersDelightApi.get().isHeatSource(block) and
 * isConductor(block); those stay on the api because stations call them every tick.
 */
public final class HeatSources {

    private static final Map<String, HeatSources> GROUPS = new ConcurrentHashMap<>();

    private final Set<String> vanillaBlocks = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> conductors = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> customBlocks = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> customTags = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

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
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
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
