package com.huidu.farmersdelight.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DatapackWorldWhitelist {

    public static final String PRIMARY_WORLD_TOKEN = "$primary";
    public static final String ALL_WORLDS_TOKEN = "*";

    private final boolean allWorlds;
    private final Set<String> worldNames;

    private DatapackWorldWhitelist(boolean allWorlds, Set<String> worldNames) {
        this.allWorlds = allWorlds;
        this.worldNames = Set.copyOf(worldNames);
    }

    public static DatapackWorldWhitelist from(List<String> configuredWorlds, String primaryWorldName) {
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
            return new DatapackWorldWhitelist(false, Set.of());
        }

        LinkedHashSet<String> worldNames = new LinkedHashSet<>();
        boolean allWorlds = false;
        for (String configuredWorld : configuredWorlds) {
            String normalized = normalize(configuredWorld);
            if (normalized == null) {
                continue;
            }
            if (ALL_WORLDS_TOKEN.equals(normalized)) {
                allWorlds = true;
                continue;
            }
            if (PRIMARY_WORLD_TOKEN.equals(normalized)) {
                String primary = normalize(primaryWorldName);
                if (primary != null) {
                    worldNames.add(primary);
                }
                continue;
            }
            worldNames.add(normalized);
        }
        return new DatapackWorldWhitelist(allWorlds, worldNames);
    }

    public boolean allows(String worldName) {
        if (this.allWorlds) {
            return true;
        }
        String normalized = normalize(worldName);
        return normalized != null && this.worldNames.contains(normalized);
    }

    public boolean allowsAllWorlds() {
        return this.allWorlds;
    }

    public Set<String> worldNames() {
        return this.worldNames;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
