package com.huidu.farmersdelight.api.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The generic half of a gui.yml layout reader: the character grid to a normalised cell-type grid, plus the
 * grid diagnostics every reader repeats.
 *
 *
 * The rich station readers stay where they are — the cooking-pot reader also resolves titles, progress items
 * and per-station slot roles, and the addon readers add their own decorations — but they no longer each need
 * their own copy of the "character at (row, column) maps to which type" rule or of the three warnings it can
 * produce.
 *
 *
 * Nothing here touches CraftEngine or the server: {@link #cellTypes} and {@link #warnUnknownCharacters} are
 * plain data transforms, and {@link #parse} reads only rows/layout/legend.
 */
public final class GuiLayouts {

    private GuiLayouts() {
    }

    /**
     * Reads the generic layout of one gui.yml section: rows (default 3, at least 1), layout and legend.
     *
     *
     * The returned view answers {@link GuiLayout} only; titles, items and station roles stay with the caller's
     * own config class. Returns null when the section is null.
     */
    @Nullable
    public static GuiLayout parse(@Nullable ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        int rows = Math.max(1, section.getInt("rows", 3));
        Map<Character, String> legend = new HashMap<>();
        ConfigurationSection legendSection = section.getConfigurationSection("legend");
        if (legendSection != null) {
            for (String key : legendSection.getKeys(false)) {
                if (key.length() == 1) {
                    legend.put(key.charAt(0), legendSection.getString(key));
                }
            }
        }
        return new ParsedLayout(rows, cellTypes(rows, section.getStringList("layout"), legend));
    }

    /**
     * The normalised cell-type grid: {@code rows * 9} entries in slot order, never null.
     *
     *
     * Every cell that is not inside a drawn row or column is {@link GuiLayout#BACKGROUND}: a missing row, a
     * row shorter than nine cells, a whitespace character, a character the legend does not define and a null
     * legend type all become the background. Rows beyond {@code rows} and characters beyond column nine are
     * ignored. A row count below 1 yields an empty array.
     *
     *
     * Note for callers that also read the same grid through a station config: this is the normalising reader,
     * where an undrawn cell is background. The cooking-pot reader's own per-slot lookup answers null for those
     * cells instead, and keeps doing so. The two agree on every drawn cell whose legend entry is a non-blank
     * type; they differ where the legend maps a drawn character to null or to a blank string, because this
     * reader normalises those to background while a literal lookup returns what the legend holds.
     */
    public static String[] cellTypes(int rows, @Nullable List<String> layout, @Nullable Map<Character, String> legend) {
        int columns = 9;
        int size = Math.max(0, rows) * columns;
        String[] cells = new String[size];
        Arrays.fill(cells, GuiLayout.BACKGROUND);
        for (int row = 0; row < rows; row++) {
            if (layout == null || row >= layout.size()) {
                continue;
            }
            String line = layout.get(row);
            if (line == null) {
                continue;
            }
            for (int col = 0; col < columns && col < line.length(); col++) {
                char character = line.charAt(col);
                if (Character.isWhitespace(character)) {
                    continue;
                }
                String type = legend == null ? null : legend.get(character);
                if (type != null && !type.isBlank()) {
                    cells[row * columns + col] = type;
                }
            }
        }
        return cells;
    }

    /**
     * Logs the three grid problems — a row count that disagrees with {@code rows}, a row that is not nine
     * cells wide, and a drawn character the legend does not define — and returns how many were logged.
     *
     *
     * Self-contained by design: it takes the logger instead of reaching for the plugin, so an addon can report
     * through its own logger and no static lookup or language key is involved. A null logger or layout logs
     * nothing; a whitespace character is not a problem.
     */
    public static int warnUnknownCharacters(@Nullable Logger logger, @Nullable String configPath, int rows,
                                            @Nullable List<String> layout,
                                            @Nullable Map<Character, String> legend) {
        if (logger == null) {
            return 0;
        }
        String path = configPath == null || configPath.isBlank() ? "gui.yml" : configPath;
        List<String> lines = layout == null ? List.of() : layout;
        Map<Character, String> types = legend == null ? Map.of() : legend;
        int warnings = 0;
        if (lines.size() != rows) {
            logger.warning(path + ": layout has " + lines.size() + " rows but rows is " + rows);
            warnings++;
        }
        for (int row = 0; row < lines.size(); row++) {
            String line = lines.get(row);
            if (line == null) {
                continue;
            }
            if (line.length() != 9) {
                logger.warning(path + ": row " + (row + 1) + " is " + line.length()
                        + " characters wide, expected 9");
                warnings++;
            }
            for (int col = 0; col < line.length(); col++) {
                char character = line.charAt(col);
                if (!Character.isWhitespace(character) && !types.containsKey(character)) {
                    logger.warning(path + ": unknown layout character '" + character
                            + "' at row " + (row + 1) + " column " + (col + 1));
                    warnings++;
                }
            }
        }
        return warnings;
    }

    /** The parsed view: the row count plus the normalised grid, with copies handed out of every array. */
    private static final class ParsedLayout implements GuiLayout {

        private final int rows;
        private final String[] cells;

        private ParsedLayout(int rows, String[] cells) {
            this.rows = rows;
            this.cells = cells;
        }

        @Override
        public int rows() {
            return rows;
        }

        @Override
        public boolean isFunctional(int slot) {
            return contains(slot) && !GuiLayout.BACKGROUND.equals(cells[slot]);
        }

        @Override
        public int[] slotsOf(String type) {
            if (type == null) {
                return new int[0];
            }
            int[] found = new int[cells.length];
            int count = 0;
            for (int slot = 0; slot < cells.length; slot++) {
                if (type.equals(cells[slot])) {
                    found[count++] = slot;
                }
            }
            return Arrays.copyOf(found, count);
        }
    }
}
