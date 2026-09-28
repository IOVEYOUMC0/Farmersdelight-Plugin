package com.huidu.farmersdelight.pack;

import com.huidu.farmersdelight.api.pack.AddonPackSections;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.PackManager;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FarmersDelight's own view of the CraftEngine pack sections it claims: the cooking-pot, cutting-board,
 * special-recipe and addon-advancement sections named by PackSection.
 *
 * <p>Claiming and bridging live in AddonPackSections (the api class addons use for their own
 * sections); this class only maps this plugin's fixed section set onto it and reports the outcome with this
 * plugin's own console keys. Registration has to happen during FarmersDelight's onLoad: CraftEngine
 * dispatches sections while it loads packs in its own onEnable, and this plugin loads after it.
 */
public final class PackSections {

    /** One section handed over by CraftEngine, already bridged to the configuration shape readers expect. */
    public record Section(PackSection section, String source, String namespace, YamlConfiguration yaml) {
    }

    private final AddonPackSections claimed;

    private PackSections(AddonPackSections claimed) {
        this.claimed = claimed;
    }

    /**
     * Registers the parser with CraftEngine. Returns null when CraftEngine has no pack manager yet or when
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
        return new PackSections(claimed);
    }

    /** The claimed ids mapped to the root key each reader looks up. */
    private static Map<String, String> roots() {
        Map<String, String> roots = new LinkedHashMap<>();
        for (PackSection section : PackSection.values()) {
            roots.put(section.sectionId(), section.rootKey());
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
}
