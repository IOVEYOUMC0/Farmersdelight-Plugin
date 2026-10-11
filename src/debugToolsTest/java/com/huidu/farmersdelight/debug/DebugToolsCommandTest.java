package com.huidu.farmersdelight.debug;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    void serializationProfilingCompletesTheOperationAndBoundedDuration() {
        DebugToolsCommand command = new DebugToolsCommand(null);
        assertTrue(command.tabComplete(null, new String[]{"debugtools", ""}).contains("profile"));
        assertEquals(List.of("serialization"),
                command.tabComplete(null, new String[]{"debugtools", "profile", ""}));
        assertEquals(List.of("200", "600", "1200"),
                command.tabComplete(null, new String[]{"debugtools", "profile", "serialization", ""}));
    }

    @Test
    void lookingUpHistoryDuringUndoDoesNotShiftPendingIndices() throws Exception {
        DebugToolsCommand command = new DebugToolsCommand(null);
        UUID owner = UUID.randomUUID();
        Class<?> batchType = Class.forName(DebugToolsCommand.class.getName() + "$PlacementBatch");
        var constructor = batchType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object batch = constructor.newInstance(owner);
        var entriesField = batchType.getDeclaredField("entries");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) entriesField.get(batch);
        Class<?> entryType = Class.forName(DebugToolsCommand.class.getName() + "$UndoEntry");
        var entryConstructor = entryType.getDeclaredConstructors()[0];
        entryConstructor.setAccessible(true);
        Object entry = entryConstructor.newInstance(null, null, null, null);
        for (int i = 0; i < 6874; i++) entries.add(entry);
        var remember = DebugToolsCommand.class.getDeclaredMethod("rememberBatch", batchType);
        remember.setAccessible(true);
        remember.invoke(command, batch);
        var prune = DebugToolsCommand.class.getDeclaredMethod("removeEmptyBatches", UUID.class);
        prune.setAccessible(true);

        for (int cursor = 0; cursor < 6874; cursor++) {
            if (cursor % 16 == 0) prune.invoke(command, owner);
            assertEquals(6874, entries.size(), "history lookup must retain the active undo's indices");
            int index = 6873 - cursor;
            assertSame(entry, entries.get(index));
            entries.set(index, null);
        }
        prune.invoke(command, owner);
        var history = DebugToolsCommand.class.getDeclaredField("undoHistory");
        history.setAccessible(true);
        assertTrue(((Map<?, ?>) history.get(command)).isEmpty());
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
