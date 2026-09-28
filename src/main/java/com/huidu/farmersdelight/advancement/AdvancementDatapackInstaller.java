package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.i18n.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// Removes the legacy advancements data pack. FD advancements are served entirely through
// UltimateAdvancementAPI tabs; nothing writes this pack any more, so only the uninstall half remains.
public final class AdvancementDatapackInstaller {

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

}
