package com.huidu.farmersdelight.debug;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugToolsCommandTest {
    @Test
    void nestedRecipePlacementRetainsAllRecipeArguments() {
        String[] entered = {"debugtools", "place", "recipe", "cooking_pot", "farmersdelight:beef_stew", "4"};
        assertArrayEquals(new String[]{"debugtools", "place_recipe", "cooking_pot", "farmersdelight:beef_stew", "4"},
                DebugToolsCommand.normalizePlacementArguments(entered));
        assertEquals("recipe", entered[2], "completion must not modify the caller's arguments");
    }

    @Test
    void randomPlacementAndIncompleteInputReachTheExistingHandlers() {
        assertArrayEquals(new String[]{"debugtools", "place_random_recipe", "all", "16"},
                DebugToolsCommand.normalizePlacementArguments(
                        new String[]{"debugtools", "PLACE", "RANDOM", "all", "16"}));
        assertArrayEquals(new String[]{"debugtools", "place_recipe"},
                DebugToolsCommand.normalizePlacementArguments(new String[]{"debugtools", "place", "recipe"}));
    }

    @Test
    void existingPlacementAndLegacyCommandsArePreserved() {
        for (String[] entered : List.of(
                new String[]{"debugtools", "place", "cooking_pot", "64", "2", "3"},
                new String[]{"debugtools", "placeRecipe", "cutting_board", "test"},
                new String[]{"debugtools", "placeRecipeRandom", "all", "4"},
                new String[]{"debugtools", "place"})) {
            assertSame(entered, DebugToolsCommand.normalizePlacementArguments(entered));
        }
    }

    @Test
    void completionsLeadWithLowercaseNestedCommands() {
        DebugToolsCommand command = new DebugToolsCommand(null);
        List<String> actions = command.tabComplete(null, new String[]{"debugtools", ""});
        assertTrue(actions.contains("place"));
        assertFalse(actions.contains("placeRecipe"));
        assertTrue(command.tabComplete(null, new String[]{"debugtools", "place", ""})
                .containsAll(List.of("recipe", "random")));
        assertEquals(List.of("cooking_pot", "cutting_board"),
                command.tabComplete(null, new String[]{"debugtools", "place", "recipe", ""}));
        assertEquals(List.of("1", "4", "16", "64"),
                command.tabComplete(null, new String[]{"debugtools", "place", "random", "all", ""}));
    }

    @Test
    void bilingualHelpKeepsCommandPlaceholdersVisible() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            YamlConfiguration language = new YamlConfiguration();
            language.loadFromString(Files.readString(Path.of("src/main/resources/lang/" + locale + ".yml")));
            String rendered = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage()
                    .deserialize(language.getString("command.debug_usage_place_recipe")));
            assertTrue(rendered.startsWith("/fd debugtools place recipe <cooking_pot|cutting_board|all>"), rendered);
            assertTrue(language.isString("command.debug_usage_stop"));
            assertTrue(language.isString("command.debug_usage_undo"));
        }
    }
}
