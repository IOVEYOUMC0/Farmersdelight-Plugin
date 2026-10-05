package com.huidu.farmersdelight.api.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic layout reader: how a drawn character grid becomes a cell-type grid, and what the read-only view
 * answers for the cells the grid does not draw.
 *
 *
 * Everything here is plain config and plain arrays, so it runs without a server: the reader never builds an
 * item and never resolves CraftEngine.
 */
class GuiLayoutsTest {

    private static final Map<Character, String> LEGEND = Map.of(
            'I', "ingredient",
            'B', "bait",
            'X', GuiLayout.BACKGROUND);

    @Test
    void aDrawnGridKeepsItsTypes() {
        String[] cells = GuiLayouts.cellTypes(3, List.of(
                "IIIIIIIII",
                "BBBBBBBBB",
                "XXXXXXXXX"), LEGEND);

        assertEquals(27, cells.length);
        assertEquals("ingredient", cells[0]);
        assertEquals("ingredient", cells[8]);
        assertEquals("bait", cells[9]);
        assertEquals("bait", cells[17]);
        assertEquals(GuiLayout.BACKGROUND, cells[18]);
    }

    @Test
    void aShortRowAndAMissingRowAreBackground() {
        String[] cells = GuiLayouts.cellTypes(3, List.of("III"), LEGEND);

        assertEquals("ingredient", cells[0]);
        assertEquals("ingredient", cells[2]);
        for (int slot = 3; slot < 27; slot++) {
            assertEquals(GuiLayout.BACKGROUND, cells[slot], "slot " + slot);
        }
    }

    @Test
    void anUnknownCharacterAndAWhitespaceCellAreBackground() {
        String[] cells = GuiLayouts.cellTypes(2, List.of("I?I      ", "         "), LEGEND);

        assertEquals(18, cells.length);
        assertEquals("ingredient", cells[0]);
        assertEquals(GuiLayout.BACKGROUND, cells[1], "a character the legend does not define");
        assertEquals("ingredient", cells[2]);
        assertEquals(GuiLayout.BACKGROUND, cells[3], "a drawn whitespace cell");
    }

    @Test
    void aMissingLegendOrLayoutLeavesEveryCellAtBackground() {
        String[] withoutLegend = GuiLayouts.cellTypes(1, List.of("III"), null);
        String[] withoutLayout = GuiLayouts.cellTypes(1, null, LEGEND);

        for (String cell : withoutLegend) {
            assertEquals(GuiLayout.BACKGROUND, cell);
        }
        for (String cell : withoutLayout) {
            assertEquals(GuiLayout.BACKGROUND, cell);
        }
        assertEquals(0, GuiLayouts.cellTypes(0, List.of("III"), LEGEND).length);
        assertEquals(0, GuiLayouts.cellTypes(-1, List.of("III"), LEGEND).length);
    }

    @Test
    void rowsBeyondTheGridAndColumnsBeyondTheNinthsAreIgnored() {
        String[] cells = GuiLayouts.cellTypes(1, List.of("IIIIIIIIIII", "BBBBBBBBB"), LEGEND);

        assertEquals(9, cells.length);
        assertEquals("ingredient", cells[8], "the tenth character of row one is not a cell");
    }

    @Test
    void parseReadsRowsLayoutAndLegend() throws Exception {
        GuiLayout layout = GuiLayouts.parse(section("""
                crab-trap-gui:
                  rows: 3
                  layout:
                  - "BBBBBBBBB"
                  - "XXXXXXXXX"
                  - "XXXXXXXXX"
                  legend:
                    B: bait
                    X: background
                """, "crab-trap-gui"));

        assertNotNull(layout);
        assertEquals(3, layout.rows());
        assertEquals(27, layout.size());
        assertTrue(layout.contains(0));
        assertTrue(layout.contains(26));
        assertFalse(layout.contains(-1));
        assertFalse(layout.contains(27));
        assertTrue(layout.isFunctional(0));
        assertFalse(layout.isFunctional(9));
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, layout.functionalSlots());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, layout.slotsOf("bait"));
        assertEquals(0, layout.slotsOf("lure").length, "an unknown type is empty, not null");
        assertEquals(0, layout.slotsOf(null).length);
        assertEquals(0, layout.firstSlotOf("bait"));
        assertEquals(-1, layout.firstSlotOf("lure"));
    }

    @Test
    void aMissingSectionParsesToNothing() {
        assertNull(GuiLayouts.parse(null));
    }

    @Test
    void parseFallsBackToOneRowForARowCountOfZero() throws Exception {
        GuiLayout layout = GuiLayouts.parse(section("""
                empty:
                  rows: 0
                  layout:
                  - "BBBBBBBBB"
                  legend:
                    B: bait
                """, "empty"));

        assertNotNull(layout);
        assertEquals(1, layout.rows());
        assertEquals(9, layout.size());
    }

    @Test
    void theViewsArraysAreCopies() throws Exception {
        GuiLayout layout = GuiLayouts.parse(section("""
                crab-trap-gui:
                  rows: 2
                  layout:
                  - "BBBBBBBBB"
                  - "XXXXXXXXX"
                  legend:
                    B: bait
                    X: background
                """, "crab-trap-gui"));
        assertNotNull(layout);

        int[] functional = layout.functionalSlots();
        int[] bait = layout.slotsOf("bait");
        functional[0] = 99;
        bait[0] = 99;

        assertEquals(0, layout.functionalSlots()[0], "a caller cannot change the layout through the array");
        assertEquals(0, layout.slotsOf("bait")[0]);
    }

    @Test
    void warningsCountTheThreeGridProblems() {
        List<LogRecord> records = new ArrayList<>();

        int warnings = GuiLayouts.warnUnknownCharacters(logger(records), "crab-trap-gui", 4,
                List.of("BBBBBBBBB", "XX", "XXXX?XXXX"),
                Map.of('B', "bait", 'X', GuiLayout.BACKGROUND));

        assertEquals(3, warnings, "row count, row width, unknown character");
        assertEquals(3, records.size());
        assertTrue(records.get(0).getMessage().contains("crab-trap-gui"));
    }

    @Test
    void aCleanGridAndNoLoggerWarnAboutNothing() {
        assertEquals(0, GuiLayouts.warnUnknownCharacters(logger(new ArrayList<>()), "clean", 1,
                List.of("BBBBBBBBB"), Map.of('B', "bait")));
        assertEquals(0, GuiLayouts.warnUnknownCharacters(null, "clean", 1,
                List.of("BBBBBBBBB"), Map.of('B', "bait")));
    }

    private static ConfigurationSection section(String yaml, String key) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        ConfigurationSection section = configuration.getConfigurationSection(key);
        assertNotNull(section, "fixture section " + key);
        return section;
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
