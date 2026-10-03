package com.huidu.farmersdelight.pack;

import com.huidu.farmersdelight.api.pack.AddonPackSections;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.AdvancedTagGroups;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.PackManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FarmersDelight's own view of the CraftEngine pack sections it claims: the cooking-pot, cutting-board,
 * special-recipe and addon-advancement sections named by PackSection.
 *
 *
 * Claiming and bridging live in AddonPackSections (the api class addons use for their own
 * sections); this class only maps this plugin's fixed section set onto it and reports the outcome with this
 * plugin's own console keys. Registration has to happen during FarmersDelight's onLoad: CraftEngine
 * dispatches sections while it loads packs in its own onEnable, and this plugin loads after it.
 */
public final class PackSections {

    /** One section handed over by CraftEngine, already bridged to the configuration shape readers expect. */
    public record Section(PackSection section, String source, String namespace, YamlConfiguration yaml) {
    }

    private final AddonPackSections claimed;
    private final AddonPackSections tagGroups;

    private PackSections(AddonPackSections claimed, AddonPackSections tagGroups) {
        this.claimed = claimed;
        this.tagGroups = tagGroups;
    }

    /**
     * Registers the parsers with CraftEngine. Returns null when CraftEngine has no pack manager yet or when
     * another plugin already claimed one of the section ids; both cases are reported to the console.
     */
    public static PackSections register() {
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        PackManager packManager = craftEngine == null ? null : craftEngine.packManager();
        if (packManager == null) {
            I18n.logWarning("plugin.pack_sections_unavailable");
            return null;
        }
        Map<String, String> roots = roots();
        AddonPackSections claimed = AddonPackSections.claim(packManager, "farmersdelight:pack_sections",
                "farmersdelight pack sections", roots);
        if (!claimed.registered()) {
            I18n.logWarning("plugin.pack_sections_conflict", "ids", String.join(", ", roots.keySet()));
            return null;
        }
        I18n.logDetail("startup", "plugin.pack_sections_registered", "ids", String.join(", ", roots.keySet()));

        // The advanced tag groups are claimed separately: one claim's ids are registered as a unit, so putting
        // this one in the batch above would let a pack or plugin that already owns the id take the recipes down
        // with it. A failed claim here costs tag groups and nothing else.
        AddonPackSections tagGroups = AddonPackSections.claim(packManager, "farmersdelight:advanced_tags",
                "farmersdelight advanced tag groups", separateRoots());
        if (!tagGroups.registered()) {
            I18n.logWarning("plugin.pack_sections_conflict", "ids", String.join(", ", separateRoots().keySet()));
        } else {
            I18n.logDetail("startup", "plugin.pack_sections_registered",
                    "ids", String.join(", ", separateRoots().keySet()));
        }
        return new PackSections(claimed, tagGroups);
    }

    /** The claimed ids mapped to the root key each reader looks up. */
    private static Map<String, String> roots() {
        Map<String, String> roots = new LinkedHashMap<>();
        for (PackSection section : PackSection.values()) {
            if (!section.separateClaim()) {
                roots.put(section.sectionId(), section.rootKey());
            }
        }
        return roots;
    }

    /** The ids claimed in their own registration, so one conflict cannot disable the sections above. */
    private static Map<String, String> separateRoots() {
        Map<String, String> roots = new LinkedHashMap<>();
        for (PackSection section : PackSection.values()) {
            if (section.separateClaim()) {
                roots.put(section.sectionId(), section.rootKey());
            }
        }
        return roots;
    }

    /** Snapshots of one section, empty when the parser was never registered or the packs declare none. */
    public List<Section> sectionsOf(PackSection section) {
        if (this.claimed == null) {
            return List.of();
        }
        List<AddonPackSections.Section> found = this.claimed.sections(section.sectionId());
        if (found.isEmpty()) {
            return List.of();
        }
        List<Section> out = new ArrayList<>(found.size());
        for (AddonPackSections.Section entry : found) {
            out.add(new Section(section, entry.source(), entry.namespace(), entry.config()));
        }
        return List.copyOf(out);
    }

    /**
     * The advanced tag groups the packs declare, resolved into flat member lists.
     *
     *
     * Compiled on each call rather than cached: this runs during a reload, next to the recipe readers, and a
     * cached snapshot would have to be invalidated by every one of them.
     */
    public AdvancedTagGroups advancedTagGroups() {
        if (this.tagGroups == null) {
            return AdvancedTagGroups.EMPTY;
        }
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (AddonPackSections.Section entry : this.tagGroups.sections(PackSection.ADVANCED_TAGS.sectionId())) {
            collectGroups(entry.config(), PackSection.ADVANCED_TAGS.rootKey(), declared);
        }
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared);
        if (!groups.dropped().isEmpty()) {
            List<String> ids = new ArrayList<>(groups.dropped().size());
            for (var id : groups.dropped()) {
                ids.add(id.toString());
            }
            I18n.logWarning("plugin.advanced_tag_groups_dropped", "ids", String.join(", ", ids));
        }
        return groups;
    }

    /**
     * Reads the group id to member list map out of one pack section.
     *
     *
     * A member is an item id or an advtag: reference; both stay as written, because flattening them
     * is AdvancedTagGroups' job and a value that is neither a list nor a scalar is reported rather than
     * guessed at. The first declaration of a group id wins, so a pack that sorts earlier cannot be overridden
     * by one that sorts later.
     */
    static void collectGroups(YamlConfiguration yaml, String rootKey, Map<String, List<String>> out) {
        if (yaml == null) {
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection(rootKey);
        if (root == null) {
            return;
        }
        for (String groupId : root.getKeys(false)) {
            Object value = root.get(groupId);
            List<String> members = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object member : list) {
                    if (member != null) {
                        members.add(String.valueOf(member));
                    }
                }
            } else if (value instanceof String single) {
                members.add(single);
            } else if (value != null) {
                I18n.logWarning("plugin.advanced_tag_group_invalid", "id", groupId);
                continue;
            }
            out.putIfAbsent(groupId, members);
        }
    }
}
