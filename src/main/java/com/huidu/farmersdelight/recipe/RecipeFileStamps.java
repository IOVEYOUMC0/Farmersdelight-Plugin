package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers which recipe file was parsed at which (exists, modified, size) stamp.
 *
 *
 * Every reload used to read and parse all recipe files again even when not one byte had changed, which is the
 * main-thread cost behind the reported TPS dip. A stamp is cheap (two stat fields, no content hash) and is taken
 * after any bundled-entry reconciliation wrote the file, so the cache never claims a stale parse.
 */
public final class RecipeFileStamps {

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger PARSES = new AtomicInteger();

    private RecipeFileStamps() {
    }

    /** File identity cheap enough to read on the server thread: existence, length, modified time. */
    public record Stamp(boolean exists, long modified, long size) {

        public static Stamp of(@Nullable File file) {
            if (file == null || !file.exists()) {
                return new Stamp(false, 0L, 0L);
            }
            return new Stamp(true, file.lastModified(), file.length());
        }
    }

    /** The parse from a previous pass when the file is byte-for-byte at the same stamp, else null. */
    @Nullable
    public static YamlConfiguration cached(String relativePath, Stamp stamp) {
        if (relativePath == null || stamp == null || !stamp.exists()) {
            return null;
        }
        Entry entry = CACHE.get(relativePath);
        return entry != null && entry.stamp().equals(stamp) ? entry.yaml() : null;
    }

    /** Stores a successful parse under the stamp it was read at. */
    public static void remember(String relativePath, Stamp stamp, @Nullable YamlConfiguration yaml) {
        if (relativePath == null) {
            return;
        }
        if (yaml == null) {
            CACHE.remove(relativePath);
            return;
        }
        CACHE.put(relativePath, new Entry(stamp, yaml));
    }

    /** Counts files actually parsed; the reload report and its regression test read the delta per pass. */
    public static void noteParse() {
        PARSES.incrementAndGet();
    }

    public static int parsedCount() {
        return PARSES.get();
    }

    /** Forgets every stamp and the counter. Used by tests and by a full plugin re-enable. */
    public static void clear() {
        CACHE.clear();
        PARSES.set(0);
    }

    private record Entry(Stamp stamp, YamlConfiguration yaml) {
    }
}
