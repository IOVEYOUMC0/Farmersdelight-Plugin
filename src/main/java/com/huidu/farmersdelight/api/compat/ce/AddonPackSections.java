package com.huidu.farmersdelight.api.compat.ce;

import net.momirealms.craftengine.core.pack.PackManager;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CraftEngine-pinned facade for claiming addon-owned YAML root sections during onLoad. This is the
 * advanced counterpart of the neutral content API; it exposes CE pack types and follows the deployed
 * CraftEngine version.
 */
@ApiStatus.Experimental
public final class AddonPackSections {

    public record Section(String sectionId, String source, String namespace, YamlConfiguration config) {
    }

    public record Entry(String id, ConfigurationSection section, String source) {
    }

    private final com.huidu.farmersdelight.api.pack.AddonPackSections delegate;

    private AddonPackSections(com.huidu.farmersdelight.api.pack.AddonPackSections delegate) {
        this.delegate = delegate;
    }

    public static List<Entry> entries(AddonPackSections claim, String sectionId, String rootKey,
                                      File overrideFile) {
        List<Entry> converted = new ArrayList<>();
        for (com.huidu.farmersdelight.api.pack.AddonPackSections.Entry entry
                : com.huidu.farmersdelight.api.pack.AddonPackSections.entries(
                claim == null ? null : claim.delegate, sectionId, rootKey, overrideFile)) {
            converted.add(new Entry(entry.id(), entry.section(), entry.source()));
        }
        return List.copyOf(converted);
    }

    public static List<Entry> entriesFromResource(JavaPlugin plugin, String resourcePath, String rootKey) {
        List<Entry> converted = new ArrayList<>();
        for (com.huidu.farmersdelight.api.pack.AddonPackSections.Entry entry
                : com.huidu.farmersdelight.api.pack.AddonPackSections.entriesFromResource(
                plugin, resourcePath, rootKey)) {
            converted.add(new Entry(entry.id(), entry.section(), entry.source()));
        }
        return List.copyOf(converted);
    }

    public static void warnIfEmpty(JavaPlugin plugin, AddonPackSections claim, String sectionId,
                                   String rootKey, List<String> sources, List<Entry> entries) {
        com.huidu.farmersdelight.api.pack.AddonPackSections.warnIfEmpty(plugin,
                claim == null ? null : claim.delegate, sectionId, rootKey, sources, legacyEntries(entries));
    }

    public static void warnIfEmpty(Consumer<String> warn, AddonPackSections claim, String sectionId,
                                   String rootKey, List<String> sources, List<Entry> entries) {
        com.huidu.farmersdelight.api.pack.AddonPackSections.warnIfEmpty(warn,
                claim == null ? null : claim.delegate, sectionId, rootKey, sources, legacyEntries(entries));
    }

    public static AddonPackSections claim(JavaPlugin plugin, String typeId, String stageName,
                                          Map<String, String> roots) {
        return wrap(com.huidu.farmersdelight.api.pack.AddonPackSections.claim(
                plugin, typeId, stageName, roots));
    }

    public static AddonPackSections claim(PackManager packManager, String typeId, String stageName,
                                          Map<String, String> roots) {
        return wrap(com.huidu.farmersdelight.api.pack.AddonPackSections.claim(
                packManager, typeId, stageName, roots));
    }

    public static AddonPackSections createForTesting(Map<String, String> roots) {
        return wrap(com.huidu.farmersdelight.api.pack.AddonPackSections.createForTesting(roots));
    }

    public String claimId() {
        return this.delegate.claimId();
    }

    public boolean registered() {
        return this.delegate.registered();
    }

    public boolean hasPublished() {
        return this.delegate.hasPublished();
    }

    public List<String> claimedIds() {
        return this.delegate.claimedIds();
    }

    public List<Section> sections(String sectionId) {
        List<Section> converted = new ArrayList<>();
        for (com.huidu.farmersdelight.api.pack.AddonPackSections.Section section
                : this.delegate.sections(sectionId)) {
            converted.add(new Section(section.sectionId(), section.source(), section.namespace(),
                    section.config()));
        }
        return List.copyOf(converted);
    }

    public Key type() {
        return this.delegate.type();
    }

    public String[] sectionId() {
        return this.delegate.sectionId();
    }

    public LoadingStage loadingStage() {
        return this.delegate.loadingStage();
    }

    public List<LoadingStage> dependencies() {
        return this.delegate.dependencies();
    }

    public void loadAll() {
        this.delegate.loadAll();
    }

    private static AddonPackSections wrap(com.huidu.farmersdelight.api.pack.AddonPackSections delegate) {
        return new AddonPackSections(delegate);
    }

    private static List<com.huidu.farmersdelight.api.pack.AddonPackSections.Entry> legacyEntries(
            List<Entry> entries) {
        List<com.huidu.farmersdelight.api.pack.AddonPackSections.Entry> converted = new ArrayList<>();
        if (entries != null) {
            for (Entry entry : entries) {
                converted.add(new com.huidu.farmersdelight.api.pack.AddonPackSections.Entry(
                        entry.id(), entry.section(), entry.source()));
            }
        }
        return converted;
    }
}
