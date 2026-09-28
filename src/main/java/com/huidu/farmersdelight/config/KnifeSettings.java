package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.Constants;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record KnifeSettings(Set<String> itemIds, Set<String> tagIds) {
    public static KnifeSettings load(ConfigurationSection config) {
        Set<String> items = values(config, "knife-items.items");
        Set<String> tags = values(config, "knife-items.tags").stream()
                .map(s -> s.startsWith("#") ? s.substring(1) : s)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (tags.isEmpty()) tags = Set.of(Constants.TAG_KNIVES.toLowerCase(Locale.ROOT));
        return new KnifeSettings(items, tags);
    }

    private static Set<String> values(ConfigurationSection config, String path) {
        return config.getStringList(path).stream().filter(Objects::nonNull).map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
