package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackManager;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class PackRecipeSource {

    record Loaded(String source, YamlConfiguration config) {
    }

    private PackRecipeSource() {
    }

    static List<Loaded> load(FarmersDelightPlugin plugin) {
        BukkitCraftEngine craftEngine = plugin.getCraftEngine();
        if (craftEngine == null) {
            return List.of();
        }
        PackManager packManager = craftEngine.packManager();
        if (packManager == null) {
            return List.of();
        }

        List<Loaded> result = new ArrayList<>();
        for (Pack pack : packManager.loadedPacks()) {
            // A pack with enable: false keeps its files on disk and stays in loadedPacks, but CraftEngine
            // registers none of its items, so its recipes could only resolve to nothing.
            if (!pack.enabled()) {
                continue;
            }
            Path recipeDir = pack.folder().resolve("farmersdelight");
            if (!Files.isDirectory(recipeDir)) {
                continue;
            }
            collectFrom(pack.folder().getFileName().toString(), recipeDir, result);
        }
        return result;
    }

    private static void collectFrom(String packName, Path recipeDir, List<Loaded> out) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(recipeDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(PackRecipeSource::isYaml)
                    .forEach(files::add);
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_load_failed", "file", packName + "/farmersdelight", "error", e.getMessage());
            return;
        }
        files.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));

        Path packRoot = recipeDir.getParent();
        for (Path file : files) {
            String source = packName + "/" + packRoot.relativize(file).toString().replace('\\', '/');
            // Read explicitly as UTF-8 (matching RecipeFileLoader), so non-ASCII recipe content is not
            // corrupted on servers whose default charset is not UTF-8. Buffer the stream: yaml.load() issues
            // many small reads, and this runs on the main thread on the reload path.
            try (Reader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 8192)) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(reader);
                out.add(new Loaded(source, yaml));
            } catch (Exception e) {
                I18n.logWarning("plugin.recipe_load_failed", "file", source, "error", e.getMessage());
            }
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
