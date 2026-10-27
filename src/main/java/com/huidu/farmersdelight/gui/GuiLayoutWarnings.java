package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;

/**
 * Layout diagnostics shared by the GUI config parsers. Each parser validates the same character-grid shape and
 * reports through the same language keys; only the fallback section path differs, so that is a parameter. The
 * copies this replaces had already drifted apart in nothing but that default.
 */
public final class GuiLayoutWarnings {

    /** The row range `Bukkit.createInventory` accepts: at least one row, at most six. */
    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 6;

    private GuiLayoutWarnings() {
    }

    /**
     * Clamps a configured row count to what a Bukkit inventory accepts (1-6) and reports the change.
     * `Bukkit.createInventory` throws for any other size, so an out-of-range `rows` value would otherwise
     * break every GUI that reads it at open time instead of producing a config warning.
     */
    public static int clampRows(int rows, String sectionPath, String fallbackPath) {
        if (rows >= MIN_ROWS && rows <= MAX_ROWS) {
            return rows;
        }
        int clamped = clampedRows(rows);
        String path = sectionPath == null || sectionPath.isBlank() ? fallbackPath : sectionPath;
        warnConfig("console.gui.rows_out_of_range",
                "path", path,
                "rows", rows,
                "min", MIN_ROWS,
                "max", MAX_ROWS,
                "clamped", clamped);
        return clamped;
    }

    /** The clamp itself, without reporting; kept separate so the rule is testable without a server logger. */
    static int clampedRows(int rows) {
        return Math.min(MAX_ROWS, Math.max(MIN_ROWS, rows));
    }

    /** Warns about a grid whose row count, row width or characters do not match the legend. */
    public static void warnUnknownLayoutCharacters(String sectionPath, String fallbackPath, int rows,
                                                   List<String> layout, Map<Character, String> legend) {
        String path = sectionPath == null || sectionPath.isBlank() ? fallbackPath : sectionPath;
        if (layout.size() != rows) {
            warnConfig("console.gui.rows_mismatch",
                    "path", path,
                    "rows", rows,
                    "layout_rows", layout.size());
        }
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            if (line.length() != 9) {
                warnConfig("console.gui.row_length_mismatch",
                        "path", path,
                        "row", row + 1,
                        "length", line.length());
            }
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (!Character.isWhitespace(c) && !legend.containsKey(c)) {
                    warnConfig("console.gui.unknown_layout_character",
                            "path", path,
                            "character", c,
                            "row", row + 1,
                            "column", col + 1);
                }
            }
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void warnConfig(String key, Object... placeholders) {
        String message = I18n.formatNamedArgs(key, placeholders);
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning(message);
        } else {
            Bukkit.getLogger().warning(message);
        }
    }
}
