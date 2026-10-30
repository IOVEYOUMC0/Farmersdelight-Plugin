package com.huidu.farmersdelight.api.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Records which argument paths a behavior factory asks for during one create call, so a wrapper can report the
 * options the pack declared but nobody read. An unread option is usually a typo: the reader falls back to its
 * compiled-in default and the operator sees no symptom at all.
 *
 * The recording is thread-confined because CraftEngine creates behaviors on its loading thread while the server
 * thread can create more during a reload. Nested paths record both their section and the flat spelling they can
 * fall back to (burn.enabled records burn and burn-enabled), so a pack written in either style counts as read.
 */
public final class BehaviorArgRecorder {

    private static final ThreadLocal<Set<String>> ACTIVE = new ThreadLocal<>();

    private BehaviorArgRecorder() {
    }

    /** Starts recording on this thread, discarding anything an unbalanced earlier begin left behind. */
    public static void begin() {
        ACTIVE.set(new LinkedHashSet<>());
    }

    /** Stops recording and returns the top-level keys read since begin(); empty when nothing was recording. */
    public static Set<String> end() {
        Set<String> recorded = ACTIVE.get();
        ACTIVE.remove();
        return recorded == null ? Set.of() : Collections.unmodifiableSet(recorded);
    }

    /**
     * Records one read in every spelling the reader accepts, so a pack written in the other spelling does not
     * look unread. Meaningless outside begin()/end(), where it simply does nothing.
     */
    public static void record(String path) {
        Set<String> recorded = ACTIVE.get();
        if (recorded == null || path == null || path.isEmpty()) {
            return;
        }
        recorded.add(path);
        recorded.add(alternate(path));
        // The flat spelling of a nested option is a real key form of its own (burn.enabled -> burn-enabled).
        recorded.add(path.replace('.', '-'));
        recorded.add(alternate(path.replace('.', '-')));
    }

    /** The same key in the other spelling, mirroring the lookup's own alias rule. */
    static String alternate(String key) {
        StringBuilder result = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            result.append(c == '-' ? '_' : c == '_' ? '-' : c);
        }
        return result.toString();
    }

    /**
     * Records that a factory asked for the whole section at this path. Its children are then parsed by that
     * factory, so a child the recorder never saw named does not mean the pack's child is ignored.
     */
    public static void recordSection(String path) {
        Set<String> recorded = ACTIVE.get();
        if (recorded == null || path == null || path.isEmpty()) {
            return;
        }
        recorded.add(sectionMarker(path));
        recorded.add(alternate(path));
        recorded.add(alternate(path) + ".*");
    }

    /** The marker a section read leaves behind for the diagnostics. */
    public static String sectionMarker(String path) {
        return path + ".*";
    }
}
