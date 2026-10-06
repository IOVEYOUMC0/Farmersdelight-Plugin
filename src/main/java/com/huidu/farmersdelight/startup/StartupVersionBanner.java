package com.huidu.farmersdelight.startup;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Builds the version fields of the enable-time summary line and decides whether the running CraftEngine version is
 * supported.
 *
 * The version check exists because a server has been found with more than one CraftEngine jar in the plugins
 * folder at the same time: a replaced jar that never took effect makes "it suddenly works" and "it broke for no
 * reason" indistinguishable. The one summary line a startup prints therefore begins with the four versions, and
 * everything else about startup stays under the startup debug category:
 *
 * FarmersDelight 1.0.3 | CraftEngine 26.9.2 | server Paper 1.21.8 | api 1.21.5
 *
 * The plugin is verified against CraftEngine 26.8.2 and newer. A version below that, or one that cannot be parsed
 * at all, warns exactly once and never again; a newer version is not a warning. Nothing here reads files or the
 * network, and it only runs while the plugin enables.
 */
public final class StartupVersionBanner {

    /** The oldest CraftEngine release this project is verified against. */
    public static final String MINIMUM_CRAFT_ENGINE = "26.8.2";

    /** Written in place of a version that could not be determined. */
    public static final String UNKNOWN = "unknown";

    private final Consumer<String> warn;
    private boolean warned;

    public StartupVersionBanner(Consumer<String> warn) {
        this.warn = warn;
    }

    /**
     * The four version fields as one line, led by the plugin name. Missing inputs become unknown rather than
     * being dropped.
     */
    public static String format(@Nullable String pluginVersion, @Nullable String ceVersion,
                                @Nullable String serverVersion, @Nullable String apiVersion) {
        return "FarmersDelight " + orUnknown(pluginVersion) + fields(ceVersion, serverVersion, apiVersion);
    }

    private static String fields(@Nullable String ceVersion, @Nullable String serverVersion,
                                 @Nullable String apiVersion) {
        return " | CraftEngine " + orUnknown(ceVersion)
                + " | server " + orUnknown(serverVersion)
                + " | api " + orUnknown(apiVersion);
    }

    /**
     * Whether this CraftEngine version is one we verify. A null or unparsable version is not supported: the line
     * would say unknown, which is exactly the situation worth warning about.
     */
    public static boolean isVerified(@Nullable String ceVersion) {
        int[] running = parse(ceVersion);
        int[] minimum = parse(MINIMUM_CRAFT_ENGINE);
        if (running == null || minimum == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (running[i] != minimum[i]) {
                return running[i] > minimum[i];
            }
        }
        return true;
    }

    /**
     * Warns once about an unsupported or unreadable CraftEngine version.
     *
     * @return true when this call emitted the warning
     */
    public boolean warnIfUnsupported(@Nullable String ceVersion) {
        if (warned || isVerified(ceVersion)) {
            return false;
        }
        warned = true;
        warn.accept("This server runs CraftEngine " + orUnknown(ceVersion) + ", but FarmersDelight is"
                + " verified against CraftEngine " + MINIMUM_CRAFT_ENGINE + " and newer. Check the plugins"
                + " folder for an older or duplicated craft-engine jar; behaviour on this version is not verified.");
        return true;
    }

    /** Whether the warning was already emitted. */
    public boolean hasWarned() {
        return warned;
    }

    /** Parses 26.9.2, 26.10-SNAPSHOT or 26.9; null when no version can be read. */
    @Nullable
    static int[] parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\.");
        int[] numbers = new int[3];
        int filled = 0;
        for (String part : parts) {
            if (filled == 3) {
                break;
            }
            String digits = leadingDigits(part);
            if (digits.isEmpty()) {
                break;
            }
            try {
                numbers[filled++] = Integer.parseInt(digits);
            } catch (NumberFormatException tooBig) {
                return null;
            }
        }
        // A bare "26" or "26.9" is still a version, just with the missing components treated as zero.
        return filled == 0 ? null : numbers;
    }

    private static String leadingDigits(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return part.substring(0, end);
    }

    private static String orUnknown(@Nullable String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? UNKNOWN : trimmed;
    }
}
