package com.huidu.farmersdelight.debug;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import com.huidu.farmersdelight.api.util.DebugToolExtension;
import com.huidu.farmersdelight.api.util.DebugToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugToolsCommandTest {
    @Test
    void recipeGridStaysHorizontalAcrossRows() throws Exception {
        var grid = DebugToolsCommand.class.getDeclaredMethod("recipeSetupLocation", Location.class,
                int.class, int.class);
        grid.setAccessible(true);
        Location origin = new Location(null, -20, 64, 30);
        for (int slot = 0; slot < 9; slot++) {
            Location placed = (Location) grid.invoke(null, origin, 3, slot);
            assertEquals(66, placed.getBlockY());
            assertEquals(-18 + slot % 3 * 3, placed.getBlockX());
            assertEquals(32 + slot / 3 * 3, placed.getBlockZ());
        }
        assertEquals(64, origin.getBlockY());
    }

    @Test
    void onlyCanonicalCommandsAndRecipeSubcommandsComplete() {
        DebugToolsCommand command = new DebugToolsCommand(null);
        assertEquals(List.of("place", "random", "validate"),
                command.tabComplete(null, new String[]{"debugtools", "recipe", ""}));
        assertEquals(List.of("cooking_pot", "cutting_board", "all"),
                command.tabComplete(null, new String[]{"debugtools", "recipe", "place", ""}));
        assertEquals(List.of("1", "4", "16", "64"),
                command.tabComplete(null, new String[]{"debugtools", "recipe", "random", "all", ""}));
        assertFalse(command.tabComplete(null, new String[]{"debugtools", "place", ""}).contains("recipe"));
        for (String alias : List.of("placeRecipe", "placeRecipeRandom", "place_recipe", "place-recipe",
                "look", "hand", "held", "recipes", "lang")) {
            assertTrue(command.tabComplete(null, new String[]{"debugtools", alias, ""}).isEmpty(), alias);
        }
        assertTrue(command.tabComplete(null, new String[]{"debugtools", "place", "recipe", ""}).isEmpty());
    }

    @Test
    void loadedAddonCapabilitiesDecideEachAllScope() {
        DebugToolExtension active = new DebugToolExtension() {
            public String name() { return "test_station"; }
            public boolean supportsPlacement() { return true; }
            public boolean supportsActivation() { return true; }
            public List<String> recipeIds() { return List.of("test:recipe"); }
        };
        DebugToolExtension passive = new DebugToolExtension() {
            public String name() { return "test_passive"; }
            public boolean supportsPlacement() { return true; }
        };
        DebugToolExtension status = () -> "test_status";
        for (DebugToolExtension extension : List.of(active, passive, status)) DebugToolRegistry.register(extension);
        try {
            assertTrue(DebugToolsCommand.placementTargets().containsAll(List.of("cutting_board", "basket", "test_station", "test_passive")));
            assertFalse(DebugToolsCommand.placementTargets().contains("test_status"));
            assertTrue(DebugToolsCommand.activationTargets().containsAll(List.of("basket", "test_station")));
            assertFalse(DebugToolsCommand.activationTargets().contains("test_passive"));
            assertTrue(DebugToolsCommand.recipeTargets().contains("test_station"));
            assertFalse(DebugToolsCommand.recipeTargets().contains("test_passive"));
            DebugToolsCommand command = new DebugToolsCommand(null);
            assertEquals(List.of("test:recipe"), command.tabComplete(null,
                    new String[]{"debugtools", "recipe", "place", "test_station", ""}));
        } finally {
            for (DebugToolExtension extension : List.of(active, passive, status)) DebugToolRegistry.unregister(extension.name());
        }
        assertFalse(DebugToolsCommand.placementTargets().contains("test_station"));
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
            assertTrue(rendered.startsWith("/fd debugtools recipe place <target|all>"), rendered);
            assertTrue(language.isString("command.debug_usage_stop"));
            assertTrue(language.isString("command.debug_usage_undo"));
        }
    }
}
