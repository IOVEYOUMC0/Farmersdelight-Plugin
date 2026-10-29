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

    /** Records one read. Meaningless outside begin()/end(), where it simply does nothing. */
    public static void record(String path) {
        Set<String> recorded = ACTIVE.get();
        if (recorded == null || path == null || path.isEmpty()) {
            return;
        }
        recorded.add(topLevel(path));
        if (path.indexOf('.') >= 0) {
            recorded.add(topLevel(path.replace('.', '-')));
        }
    }

    /** The section a dotted path lives in, or the key itself when it has no dots. */
    static String topLevel(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }
}
