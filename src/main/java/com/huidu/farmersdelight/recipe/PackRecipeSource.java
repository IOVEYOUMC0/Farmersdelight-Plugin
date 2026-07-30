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

/**
 * Loads Farmer's Delight recipe files that ship inside a CraftEngine resource pack, so an addon can add
 * cooking pot and cutting board recipes with no Java. The addon drops its recipe files under a farmersdelight/
 * folder inside its pack, next to the configuration/ and resourcepack/ folders CraftEngine already reads.
 * CraftEngine only scans configuration/,
 * so this sibling folder is invisible to it and never clashes with its parsing; the files use the same
 * root-section format as the plugin's own recipe files (cooking_pot_recipes / custom_cooking_pot_recipes /
 * cutting_board_recipes).
 *
 * The scan is deliberately decoupled from CraftEngine's own recipe pipeline: that pipeline builds vanilla
 * recipes for the crafting table and furnace, and the cooking pot and cutting board are not vanilla
 * stations. Each pack file is parsed here and fed into the existing recipe managers as one more source
 * alongside the plugin's own files and the runtime API.
 */
final class PackRecipeSource {

    /** A recipe file found in a pack, paired with a readable source label used only in log messages. */
    record Loaded(String source, YamlConfiguration config) {
    }

    private PackRecipeSource() {
    }

    /**
     * Every Farmer's Delight recipe file found across all loaded CraftEngine packs, in a stable order so a
     * duplicate id across pack files resolves to the same file every load. Empty when CraftEngine is
     * unavailable or no pack ships a farmersdelight/ folder.
     */
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
