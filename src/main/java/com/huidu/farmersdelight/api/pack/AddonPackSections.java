package com.huidu.farmersdelight.api.pack;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackManager;
import net.momirealms.craftengine.core.plugin.config.AbstractConfigParser;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStages;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Claims CraftEngine pack sections for one addon, so its own content can ship inside its pack instead of a
 * file in the plugin data folder.
 *
 * <p>CraftEngine reads every <pack>/configuration/**.yml (and the same path inside
 * subpacks/&lt;name&gt;/), splits a file by root key and hands each root whose value is a mapping to the
 * parser registered for that key. Claim the keys your addon owns:
 *
 * <pre>
 * // onLoad: must run before CraftEngine loads packs, which happens in its own onEnable
 * kegSections = AddonPackSections.claim(this, "brewinandchewin:keg",
 *         Map.of("keg_recipes", "keg_recipes", "keg_pouring_recipes", "keg_fluids"));
 *
 * // later, from the loader that used to read recipes/keg_recipes.yml
 * for (AddonPackSections.Section section : kegSections.sections("keg_recipes")) {
 *     ConfigurationSection root = section.config().getConfigurationSection("keg_recipes");
 * }
 * </pre>
 *
 * <p>The claimed id must be the file's root key, and it must not collide with a section CraftEngine or
 * another plugin already owns; a collision is reported once and leaves this claim empty (the addon should
 * then fall back to whatever else it reads). Each claim gets its own LoadingStage: CraftEngine's
 * loading pyramid keys its tasks by stage, so sharing one would replace its owner's task.
 *
 * <p>Sections are published as immutable snapshots, and clearConfigs() - which CraftEngine calls at
 * the end of every load pass - only drops the raw storage. Parsing runs on CraftEngine's loading thread, so
 * this class never touches CraftEngine or Bukkit registries; resolving item ids belongs in the reader, on the
 * main thread.
 */
public final class AddonPackSections extends AbstractConfigParser {

    /** One section handed over by CraftEngine, bridged to the configuration shape Bukkit readers expect. */
    public record Section(String sectionId, String source, String namespace, YamlConfiguration config) {
    }

    /**
     * One entry to read: its id, the section body, and where it came from (for diagnostics).
     *
     * @param id      entry key inside the root, used verbatim as the recipe id
     * @param section entry body
     * @param source  pack file or plugin file the entry came from
     */
    public record Entry(String id, org.bukkit.configuration.ConfigurationSection section, String source) {
    }

    /**
     * Every entry of one claimed section, in load order, with an optional on-disk file layered on top: an
     * entry the file also defines replaces the pack's copy (keeping its position) and is reported with the
     * file as its source, so an addon that lets operators or an in-game editor own the file keeps working
     * while the shipped defaults live in the pack.
     *
     * @param claim       the addon's claim, may be null (then only the file contributes)
     * @param sectionId   claimed section id
     * @param rootKey     root key inside each section's configuration
     * @param overrideFile file to read on top of the pack, or null when there is none
     */
    public static List<Entry> entries(AddonPackSections claim, String sectionId, String rootKey,
                                      File overrideFile) {
        Map<String, Entry> merged = new LinkedHashMap<>();
        if (claim != null) {
            for (Section section : claim.sections(sectionId)) {
                org.bukkit.configuration.ConfigurationSection root = section.config().getConfigurationSection(rootKey);
                if (root == null) {
                    continue;
                }
                for (String id : root.getKeys(false)) {
                    org.bukkit.configuration.ConfigurationSection body = root.getConfigurationSection(id);
                    if (body != null) {
                        merged.put(id, new Entry(id, body, section.source()));
                    }
                }
            }
        }
        if (overrideFile != null && overrideFile.isFile()) {
            YamlConfiguration file = YamlConfiguration.loadConfiguration(overrideFile);
            org.bukkit.configuration.ConfigurationSection root = file.getConfigurationSection(rootKey);
            if (root != null) {
                for (String id : root.getKeys(false)) {
                    org.bukkit.configuration.ConfigurationSection body = root.getConfigurationSection(id);
                    if (body != null) {
                        merged.put(id, new Entry(id, body, overrideFile.getName()));
                    }
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private final JavaPlugin plugin;
    private final Key type;
    private final Map<String, String> roots;
    private final String[] ids;
    private final LoadingStage stage;
    // The instance handed to CraftEngine is the instance callers read from: CraftEngine dispatches sections to
    // the registered object, so a copy would collect nothing.
    private volatile boolean registered;

    private AddonPackSections(JavaPlugin plugin, Key type, Map<String, String> roots, LoadingStage stage) {
        this.plugin = plugin;
        this.type = type;
        this.roots = Map.copyOf(roots);
        this.ids = roots.keySet().toArray(new String[0]);
        this.stage = stage;
    }

    private volatile Map<String, List<Section>> sections = Map.of();

    /**
     * Claims the given section ids, each mapped to the root key the reader will look up in
     * Section#config(). Call from onLoad: CraftEngine dispatches sections to registered
     * parsers while it loads packs in its own onEnable.
     *
     * @param plugin    owning plugin, used for diagnostics
     * @param typeId    registry key of this claim (e.g. myaddon:keg); must be unique
     * @param stageName name of this claim's loading stage, shown in CraftEngine's load log
     * @param roots     claimed section id -> root key inside Section#config()
     */
    public static AddonPackSections claim(JavaPlugin plugin, String typeId, String stageName,
                                          Map<String, String> roots) {
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        PackManager packManager = craftEngine == null ? null : craftEngine.packManager();
        if (packManager == null) {
            warn(plugin, "CraftEngine's pack manager is not ready, so the pack sections "
                    + String.join(", ", roots.keySet()) + " could not be claimed; that content will not load.");
            return createForTesting(roots);
        }
        AddonPackSections claim = claim(packManager, typeId, stageName, roots);
        if (!claim.registered()) {
            warn(plugin, "The CraftEngine pack section(s) " + String.join(", ", roots.keySet())
                    + " are claimed by another plugin; this plugin's pack content in them is ignored.");
        } else {
            plugin.getLogger().fine("[CraftEngine] claimed pack sections: " + String.join(", ", roots.keySet()));
        }
        return claim;
    }

    /**
     * The same claim without diagnostics, for callers that report the outcome themselves
     * (FarmersDelight uses its own console keys).
     */
    public static AddonPackSections claim(PackManager packManager, String typeId, String stageName,
                                          Map<String, String> roots) {
        AddonPackSections claim = new AddonPackSections(null, Key.of(typeId), roots, new LoadingStage(stageName));
        if (packManager != null && packManager.registerConfigSectionParser(claim)) {
            claim.registered = true;
        }
        return claim;
    }

    /** Test entry point: an unregistered claim that sections can still be fed into through
     *  addConfig/loadAll. */
    public static AddonPackSections createForTesting(Map<String, String> roots) {
        return new AddonPackSections(null, Key.of("farmersdelight", "pack_sections_test"),
                roots, new LoadingStage("pack sections test"));
    }

    private static void warn(JavaPlugin plugin, String message) {
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }

    /** False when the claim could not be registered; sections are then always empty. */
    public boolean registered() {
        return registered;
    }

    /** The claimed ids, in claim order. */
    public List<String> claimedIds() {
        return List.of(ids);
    }

    /** Snapshots of one claimed section, empty when nothing was claimed or no pack declares it. */
    public List<Section> sections(String sectionId) {
        if (sectionId == null) {
            return List.of();
        }
        List<Section> found = this.sections.get(sectionId.toLowerCase(Locale.ROOT));
        return found == null ? List.of() : found;
    }

    @Override
    public Key type() {
        return type;
    }

    @Override
    public String[] sectionId() {
        return ids.clone();
    }

    @Override
    public LoadingStage loadingStage() {
        return stage;
    }

    @Override
    public List<LoadingStage> dependencies() {
        return List.of(LoadingStages.ITEM, LoadingStages.BLOCK);
    }

    @Override
    public void loadAll() {
        Map<String, List<Section>> built = new LinkedHashMap<>();
        for (int i = 0, size = this.configStorage.size(); i < size; i++) {
            CachedConfigSection cached = this.configStorage.get(i);
            ConfigSection config = cached.config();
            String sectionKey = config.path();
            int hash = sectionKey.indexOf('#');
            String id = (hash == -1 ? sectionKey : sectionKey.substring(0, hash)).toLowerCase(Locale.ROOT);
            String root = this.roots.get(id);
            if (root == null) {
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.createSection(root, config.values());
            String namespace = packNamespace(cached.pack(), hash == -1 ? null : sectionKey.substring(hash + 1));
            built.computeIfAbsent(id, key -> new ArrayList<>(2))
                    .add(new Section(id, describe(cached.pack(), cached.path()), namespace, yaml));
        }

        // Publish a whole map at once: readers run on tick threads while CraftEngine may load packs on its
        // asynchronous loading thread, and a rebuilt map drops sections a pack no longer declares.
        Map<String, List<Section>> published = new LinkedHashMap<>();
        for (String id : this.ids) {
            List<Section> found = built.get(id);
            published.put(id, found == null ? List.of() : List.copyOf(found));
        }
        this.sections = Collections.unmodifiableMap(published);

        if (this.plugin != null) {
            for (Map.Entry<String, List<Section>> entry : published.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    this.plugin.getLogger().fine("[CraftEngine] section " + entry.getKey() + ": "
                            + entry.getValue().size() + " file(s)");
                }
            }
        }
    }

    /**
     * Names the section the way CraftEngine names pack files: pack folder plus the path inside the pack, e.g.
     * myaddon/configuration/recipes/keg_recipes.yml.
     */
    private static String describe(Pack pack, Path path) {
        String name = pack.name();
        Path folder = pack.folder();
        if (folder != null) {
            try {
                return name + "/" + folder.relativize(path).toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {
                // Not below this pack's folder; fall through to the bare file name.
            }
        }
        Path fileName = path.getFileName();
        return name + "/" + (fileName == null ? path : fileName.toString());
    }

    /**
     * The pack's namespace, or the suffix of a section#namespace key when the pack declares one, so a
     * single pack can still publish content for several namespaces.
     */
    private static String packNamespace(Pack pack, String suffix) {
        if (suffix != null) {
            String trimmed = suffix.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return pack.namespace();
    }
}
