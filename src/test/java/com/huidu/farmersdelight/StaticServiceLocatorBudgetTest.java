package com.huidu.farmersdelight;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezes the plugin's static service-locator surface so it can only shrink.
 *
 *
 * FarmersDelightPlugin.getInstance() makes the whole plugin reachable from anywhere, which is why a
 * component can be impossible to test without booting a server. The migration away from it is incremental, so
 * this test does not demand zero: it pins the current count and fails when a change adds a site. When a batch
 * is migrated, lower MAX_STATIC_LOOKUPS to the new count — the number is a ratchet, not a target.
 */
class StaticServiceLocatorBudgetTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Pattern LOOKUP =
            Pattern.compile("FarmersDelightPlugin\\s*\\.\\s*getInstance\\s*\\(\\s*\\)");

    /**
     * Measured sites under src/main/java. Lower this whenever a batch is removed; never raise it to
     * make a new lookup pass. Sources that legitimately cannot receive the plugin (static storage helpers and
     * static behavior factories, which run before any instance exists) account for most of what remains; the
     * api package resolves through api.PluginAccess instead.
     */
    private static final int MAX_STATIC_LOOKUPS = 54;

    @Test
    void staticServiceLookupsDoNotGrow() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (LOOKUP.matcher(lines.get(i)).find()) {
                        sites.add(file + ":" + (i + 1));
                    }
                }
            }
        }

        assertTrue(sites.size() <= MAX_STATIC_LOOKUPS,
                "FarmersDelightPlugin.getInstance() sites grew from " + MAX_STATIC_LOOKUPS + " to "
                        + sites.size() + ". Pass the plugin in (constructor or field) instead of looking it up;"
                        + " a static storage helper can take it as a parameter. Current sites:\n  "
                        + String.join("\n  ", sites));
    }

    /**
     * The api package resolves the plugin only through api.PluginAccess, so the availability rule
     * ("present and enabled") stays single-sourced and the facades carry no static lookup of their own.
     */
    @Test
    void apiPackageResolvesThePluginOnlyThroughPluginAccess() throws IOException {
        Path api = SOURCE_ROOT.resolve(Path.of("com", "huidu", "farmersdelight", "api"));
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(api)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("PluginAccess.java"))
                    .sorted()
                    .toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (LOOKUP.matcher(lines.get(i)).find()) {
                        offenders.add(file + ":" + (i + 1));
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "api facades must resolve the plugin through PluginAccess (or take it as a parameter),"
                        + " not by looking it up directly. Offending sites:\n  "
                        + String.join("\n  ", offenders));
    }
}
