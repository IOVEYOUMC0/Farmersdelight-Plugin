package com.huidu.farmersdelight.util;

import java.util.List;

/**
 * Bounds the length of a report whose line count follows the loaded content, so one command cannot fill the
 * chat or spend a whole tick sending it.
 *
 * A limit of zero or less means no bound at all: the report is handed back untouched and nothing counts as
 * hidden. The methods are pure, keep no state and never copy the list they are given.
 */
public final class OutputCap {

    private OutputCap() {
    }

    /**
     * The entries of lines that fit under max, in order. A list that already fits, or a limit that is not
     * positive, comes back as it is; a longer list comes back as a view of its first max entries, so capping a
     * large report costs no second copy of it. The view reads the list it was taken from, so it is meant to be
     * consumed right away.
     */
    public static <T> List<T> cap(List<T> lines, int max) {
        if (max <= 0 || lines.size() <= max) {
            return lines;
        }
        return lines.subList(0, max);
    }

    /** How many of total lines a limit of max hides; zero when the whole list fits or the limit is not positive. */
    public static int overflow(int total, int max) {
        if (max <= 0 || total <= max) {
            return 0;
        }
        return total - max;
    }
}
