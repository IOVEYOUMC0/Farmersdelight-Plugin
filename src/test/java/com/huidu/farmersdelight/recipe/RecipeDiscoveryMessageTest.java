package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unlock message names the recipe by substituting a component into the {recipe} placeholder, because
 * a CraftEngine item name is a client-side translation key and cannot be flattened to a string server
 * side. Dropping the placeholder from a translation would silently cost the message its recipe name, and
 * nothing at runtime would complain, so the shipped translations are checked here.
 */
class RecipeDiscoveryMessageTest {

    private static final String PLACEHOLDER = "{recipe}";

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void shippedTranslationKeepsTheRecipePlaceholder(String locale) {
        String template = unlockedTemplate(locale);
        assertTrue(template.contains(PLACEHOLDER),
                locale + " recipe-discovery.unlocked lost the " + PLACEHOLDER + " placeholder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void substitutionPutsTheNameInAndTakesThePlaceholderOut(String locale) {
        Component name = Component.text("Beef Stew");
        Component message = Text.deserialize(unlockedTemplate(locale))
                .replaceText(builder -> builder.matchLiteral(PLACEHOLDER).replacement(name));
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        assertTrue(plain.contains("Beef Stew"), locale + " message dropped the substituted name: " + plain);
        assertFalse(plain.contains(PLACEHOLDER), locale + " message still shows the raw placeholder: " + plain);
    }

    @Test
    void multiUnlockMessageCountsInsteadOfNaming() {
        String template = section("en_us").getString("recipe-discovery.unlocked-multi");
        assertTrue(Objects.requireNonNull(template).contains("{count}"),
                "the batched message is the one that reports a number, so it needs {count}");
    }

    private static String unlockedTemplate(String locale) {
        return Objects.requireNonNull(section(locale).getString("recipe-discovery.unlocked"),
                "missing recipe-discovery.unlocked in " + locale);
    }

    private static YamlConfiguration section(String locale) {
        try (InputStream in = RecipeDiscoveryMessageTest.class.getClassLoader()
                .getResourceAsStream("lang/" + locale + ".yml")) {
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(Objects.requireNonNull(in, "missing lang/" + locale + ".yml"),
                            StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
