package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Installs / removes FarmersDelight's advancement datapack files under a world's
 * datapacks/advancements folder. Extracted from the plugin main class so the datapack file IO
 * (copy-if-changed, prune obsolete, atomic writes) lives in one focused place. All operations are
 * idempotent and return whether they changed anything on disk (so the caller can decide to reload packs).
 */
public final class AdvancementDatapackInstaller {

    private static final List<String> ADVANCEMENT_RESOURCES = List.of(
            "advancements/pack.mcmeta",
            "advancements/data/farmersdelight/advancement/main/root.json",
            "advancements/data/farmersdelight/advancement/main/craft_knife.json",
            "advancements/data/farmersdelight/advancement/main/place_campfire.json",
            "advancements/data/farmersdelight/advancement/main/use_skillet.json",
            "advancements/data/farmersdelight/advancement/main/get_fd_seed.json",
            "advancements/data/farmersdelight/advancement/main/obtain_netherite_knife.json",
            "advancements/data/farmersdelight/advancement/main/hit_raider_with_rotten_tomato.json",
            "advancements/data/farmersdelight/advancement/main/harvest_straw.json",
            "advancements/data/farmersdelight/advancement/main/place_cooking_pot.json",
            "advancements/data/farmersdelight/advancement/main/place_skillet.json",
            "advancements/data/farmersdelight/advancement/main/place_feast.json",
            "advancements/data/farmersdelight/advancement/main/use_cutting_board.json",
            "advancements/data/farmersdelight/advancement/main/plant_rice.json",
            "advancements/data/farmersdelight/advancement/main/plant_all_crops.json",
            "advancements/data/farmersdelight/advancement/main/get_ham.json",
            "advancements/data/farmersdelight/advancement/main/master_chef.json"
    );

    private final FarmersDelightPlugin plugin;

    public AdvancementDatapackInstaller(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Syncs the bundled advancement files into datapackRoot, pruning obsolete ones. Returns
     *  true if anything on disk changed. */
    public boolean sync(Path datapackRoot, String worldName) {
        if (datapackRoot == null || worldName == null || worldName.isBlank()) {
            return false;
        }

        boolean updated = pruneObsoleteAdvancementFiles(datapackRoot);

        for (String resourcePath : ADVANCEMENT_RESOURCES) {
            Path target = mapAdvancementResourceTarget(datapackRoot, resourcePath);
            try {
                updated |= copyResourceIfChanged(resourcePath, target);
            } catch (IOException e) {
                I18n.logWarning("plugin.advancement_resource_sync_failed",
                        "resource", resourcePath,
                        "world", worldName,
                        "error", e.getMessage());
            }
        }

        return updated;
    }

    /** Removes the advancement datapack from datapackRoot. Returns true if anything was removed. */
    public boolean remove(Path datapackRoot) {
        if (datapackRoot == null || !Files.exists(datapackRoot)) {
            return false;
        }

        boolean removed = false;
        removed |= deleteIfExists(datapackRoot.resolve("pack.mcmeta"));
        removed |= deleteTree(datapackRoot.resolve(Path.of("data", "farmersdelight")));

        pruneEmptyDirectories(datapackRoot.resolve("data"), datapackRoot);
        deleteEmptyDirectory(datapackRoot);
        return removed;
    }

    private boolean deleteIfExists(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            I18n.logWarning("plugin.advancement_file_delete_failed", "path", path, "error", e.getMessage());
            return false;
        }
    }

    private boolean deleteTree(Path root) {
        if (!Files.exists(root)) {
            return false;
        }
        boolean removed = false;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                removed |= deleteIfExists(path);
            }
        } catch (IOException e) {
            I18n.logWarning("plugin.advancement_directory_delete_failed", "path", root, "error", e.getMessage());
        }
        return removed;
    }

    private void pruneEmptyDirectories(Path start, Path boundary) {
        Path current = start;
        while (current != null && !current.equals(boundary)) {
            if (!deleteEmptyDirectory(current)) {
                return;
            }
            current = current.getParent();
        }
    }

    private boolean deleteEmptyDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                return false;
            }
            Files.deleteIfExists(directory);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean pruneObsoleteAdvancementFiles(Path datapackRoot) {
        Set<Path> expectedFiles = ADVANCEMENT_RESOURCES.stream()
                .filter(path -> path.startsWith("advancements/data/farmersdelight/advancement/main/"))
                .map(path -> mapAdvancementResourceTarget(datapackRoot, path).normalize())
                .collect(Collectors.toSet());

        boolean updated = false;
        for (Path mainDir : List.of(
                datapackRoot.resolve(Path.of("data", "farmersdelight", "advancements", "main")),
                datapackRoot.resolve(Path.of("data", "farmersdelight", "advancement", "main"))
        )) {
            if (!Files.isDirectory(mainDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(mainDir)) {
                for (Path file : stream.toList()) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    Path normalized = file.normalize();
                    if (!expectedFiles.contains(normalized)) {
                        Files.deleteIfExists(normalized);
                        updated = true;
                    }
                }
            } catch (IOException e) {
                I18n.logWarning("plugin.advancement_prune_failed", "path", mainDir, "error", e.getMessage());
            }
        }
        return updated;
    }

    private Path mapAdvancementResourceTarget(Path datapackRoot, String resourcePath) {
        String relativePath = resourcePath.substring("advancements/".length());
        return datapackRoot.resolve(relativePath);
    }

    private boolean copyResourceIfChanged(String resourcePath, Path target) throws IOException {
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                I18n.logWarning("plugin.advancement_resource_missing", "resource", resourcePath);
                return false;
            }

            byte[] newBytes = input.readAllBytes();
            if (Files.exists(target)) {
                byte[] existingBytes = Files.readAllBytes(target);
                if (java.util.Arrays.equals(existingBytes, newBytes)) {
                    return false;
                }
            }

            Files.createDirectories(Objects.requireNonNull(target.getParent()));
            Path tempFile = Files.createTempFile(target.getParent(), "fd-adv", ".tmp");
            try {
                Files.write(tempFile, newBytes);
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
            return true;
        }
    }
}
