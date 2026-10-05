package com.huidu.farmersdelight.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The api documentation is the only place the addon-facing contract is written down, so it is the only thing an
 * addon author reads before compiling against this plugin. Nothing stops it from drifting away from the code —
 * and it had: the capability table listed 12 of 17 feature ids and the documented apiVersion was a release
 * behind. This test fails the build the moment they disagree again.
 *
 *
 * The pages live in a separate documentation repository, checked out beside this repository (CI does it under wiki/). A copy under api-docs/ is used too, for acheckout that still keeps one. None of them being present is a failure, not a skip: this build treats a
 * skipped test as a broken report, and a drift check that quietly stops checking is worse than a red build.
 */
class ApiDocsDriftTest {

    private static final List<Path> DOC_ROOTS = List.of(
            Path.of("api-docs"),
            Path.of("wiki", "api-docs"),
            Path.of("..", "..", "FarmersdelightPluginWiKi", "api-docs"));

    private static final Pattern FEATURE_ROW = Pattern.compile("^\\|\\s*`([a-z0-9-]+)`\\s*\\|");
    private static final Pattern CURRENT_VERSION = Pattern.compile("\\*\\*(\\d+)\\*\\*");

    /** The documentation page for one locale, from whichever checkout has it. */
    private static Path docsFor(String locale) {
        for (Path root : DOC_ROOTS) {
            Path doc = root.resolve(locale).resolve("farmersdelight-api.md");
            if (Files.isRegularFile(doc)) {
                return doc;
            }
        }
        return fail("No api documentation checkout found; looked for farmersdelight-api.md under " + DOC_ROOTS
                + ". Check the FarmersdelightPluginWiKi repository out beside this one (CI does it under wiki/).");
    }

    @Test
    void featureTableListsEveryFeatureId() throws IOException {
        Set<String> code = FarmersDelightApi.get().features();
        for (String locale : new String[]{"en", "zh-cn"}) {
            Path doc = docsFor(locale);
            Set<String> documented = new LinkedHashSet<>();
            for (String line : Files.readAllLines(doc)) {
                Matcher matcher = FEATURE_ROW.matcher(line);
                if (matcher.find() && code.contains(matcher.group(1))) {
                    documented.add(matcher.group(1));
                }
            }
            Set<String> missing = new LinkedHashSet<>(code);
            missing.removeAll(documented);
            assertTrue(missing.isEmpty(),
                    doc + " does not document these feature ids: " + missing
                            + ". Every id in FarmersDelightApi.FEATURES needs a table row.");
        }
    }

    @Test
    void documentedApiVersionMatchesTheCode() throws IOException {
        int code = FarmersDelightApi.get().apiVersion();
        for (String locale : new String[]{"en", "zh-cn"}) {
            Path doc = docsFor(locale);
            // Take the first bold number inside the apiVersion() section rather than matching a
            // sentence: the prose is written twice (en / zh-cn) and gets reworded, the heading does not.
            Integer documented = null;
            boolean inSection = false;
            for (String line : Files.readAllLines(doc)) {
                if (line.startsWith("### ")) {
                    inSection = line.contains("apiVersion()");
                    continue;
                }
                if (!inSection) {
                    continue;
                }
                Matcher matcher = CURRENT_VERSION.matcher(line);
                if (matcher.find()) {
                    documented = Integer.parseInt(matcher.group(1));
                    break;
                }
            }
            assertEquals(Integer.valueOf(code), documented,
                    doc + " states a different apiVersion than the code returns."
                            + " Bump the sentence that names the current revision.");
        }
    }
}
