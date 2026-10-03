package com.huidu.farmersdelight.advancement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the UAA unregister contract.
 *
 *
 * UltimateAdvancementAPI.unregisterAdvancementTab(name) defaults to deleteClient=true,
 * which sends a client-side removal packet and drops the tab from every online player. Rebuilding a tab
 * (reload, addon repack, half-built cleanup) must pass false: UAA marks the client tree as reset on a
 * datapack reload and re-sends the tree of every tab it still considers shown, so the remove packet is what
 * makes the tab vanish until the player re-earns an advancement.
 *
 *
 * Only a real teardown (plugin disable) may delete the client-side tree, and the test pins exactly which
 * call site that is so a new rebuild path cannot silently regress.
 */
class AdvancementUnregisterContractTest {

    private static final Path ADVANCEMENT_SOURCES = Path.of("src", "main", "java", "com", "huidu",
            "farmersdelight", "advancement");

    /**
     * The one call site allowed to use the single-argument form (which deletes the client-side tree), mapped
     * to the method that must contain it. Rebuild paths must never use it.
     */
    private static final Map<String, String> TEARDOWN_CALL_SITES = Map.of(
            "AdvancementManager.java", "dispose");

    private static final Pattern CALL = Pattern.compile("unregisterAdvancementTab\\s*\\(([^;]*)\\)");

    @Test
    void everyUnregisterCallIsExplicitAboutTheClientSideState() throws IOException {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> allowlistHits = new LinkedHashMap<>();

        try (Stream<Path> files = Files.walk(ADVANCEMENT_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String fileName = file.getFileName().toString();
                List<String> lines = source.lines().toList();
                for (int i = 0; i < lines.size(); i++) {
                    Matcher call = CALL.matcher(lines.get(i));
                    if (!call.find()) {
                        continue;
                    }
                    if (call.group(1).contains(",")) {
                        continue;
                    }
                    String enclosingMethod = enclosingMethod(lines, i);
                    if (enclosingMethod.equals(TEARDOWN_CALL_SITES.get(fileName))) {
                        allowlistHits.merge(fileName + ':' + enclosingMethod, 1, Integer::sum);
                        continue;
                    }
                    violations.add(fileName + ':' + (i + 1) + " in " + enclosingMethod + "() -> "
                            + lines.get(i).trim());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "unregisterAdvancementTab(name) deletes the client-side tab; rebuild paths must pass an explicit "
                        + "boolean. Offending calls:\n  " + String.join("\n  ", violations));
        assertEquals(TEARDOWN_CALL_SITES.size(), allowlistHits.size(),
                "exactly the known teardown call sites may use the single-argument form, found " + allowlistHits);
    }

    /** Nearest preceding method declaration, good enough for these two small files. */
    private static String enclosingMethod(List<String> lines, int callLine) {
        Pattern declaration = Pattern.compile(
                "\\b(?:public|private|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
                        + "[\\w<>.\\[\\], ?]+\\s+(\\w+)\\s*\\(");
        for (int i = callLine; i >= 0; i--) {
            Matcher matcher = declaration.matcher(lines.get(i));
            if (matcher.find() && !lines.get(i).trim().startsWith("//")) {
                return matcher.group(1);
            }
        }
        return "<unknown>";
    }
}
