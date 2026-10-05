package com.huidu.farmersdelight;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the plugin's reflection from growing, and keeps it pointed at what it is for.
 *
 *
 * Reflection here is legitimate for exactly three reasons: a class that is not on the compile classpath
 * (NMS, or a CraftEngine type only some builds ship), an optional plugin, and a class the release build
 * leaves out. Anything that is on the compile classpath can be referenced directly, so looking it up
 * is a mistake: it costs a class-loader query and a method scan, and where it sits on a tick, an interaction
 * or a placement that cost lands on every one of them.
 *
 *
 * The counts below cover every call site. They are a ratchet, not a target: lower a
 * number when a site starts resolving once and caching, never raise one to let a new lookup through. A file
 * that is not listed at all may not reflect.
 *
 *
 * The ratchet covers both Java source roots: src/main/java and the debug source set
 * src/debugTools/java, which is compiled into the plugin only with -PdebugTools=true but is
 * still shipped reflection when it is. Each root keeps its own budget map, so a debug-only file can never
 * hide inside a main-source file's number.
 */
class ReflectionBudgetTest {

    private static final Pattern REFLECTIVE = Pattern.compile(
            "Class\\.forName|getDeclaredMethod|getDeclaredField|getMethod\\s*\\(|getField\\s*\\(|MethodHandles|VarHandle|ClassValue");

    /** A lookup of a class that is always on the compile classpath, which is never necessary. */
    private static final Pattern COMPILE_TIME_LOOKUP =
            Pattern.compile("Class\\.forName\\s*\\(\\s*\"(?:org\\.bukkit\\.|java\\.|javax\\.)");

    /**
     * One source root: the directory walked and the per-file budgets measured against it. The debug
     * source set is compiled only with -PdebugTools=true, so its files are deliberately kept apart
     * from the main counts rather than merged into one map.
     */
    private record SourceSet(Path root, Map<String, Integer> perFileBudget) {
    }

    /**
     * The files that legitimately reflect, each with its current number of matches.
     * Paths are relative to their source root and use forward slashes.
     */
    private static final Map<String, Integer> MAIN_BUDGET = Map.ofEntries(
            // Optional plugins and the WorldEdit/WorldGuard API, none of which is on the compile classpath.
            Map.entry("com/huidu/farmersdelight/util/compat/WorldGuardCompat.java", 21),
            Map.entry("com/huidu/farmersdelight/compat/AuraSkillsHook.java", 14),
            Map.entry("com/huidu/farmersdelight/util/compat/MMOItemsCompat.java", 11),
            // NMS and the CraftEngine proxy: present at runtime, absent from the compile classpath.
            Map.entry("com/huidu/farmersdelight/api/util/DatapackSupport.java", 5),
            Map.entry("com/huidu/farmersdelight/recipe/RecipeItemCodec.java", 5),
            Map.entry("com/huidu/farmersdelight/util/compat/CraftEngineModelMappings.java", 2),
            // A class the release build omits unless the debug-tools flag is on.
            Map.entry("com/huidu/farmersdelight/command/DebugToolsSubCommand.java", 3),
            // Folia and Paper API that the 1.21.5 compile target does not carry, plus CraftEngine's own player
            // type, whose getBukkitEntity is not reachable through a callable type.
            Map.entry("com/huidu/farmersdelight/util/scheduler/SchedulerAdapter.java", 2),
            Map.entry("com/huidu/farmersdelight/block/behavior/TatamiPairingBehavior.java", 1),
            Map.entry("com/huidu/farmersdelight/advancement/AutomaticAdvancementLayout.java", 1),
            // Paper API newer than the compile target (item model, attribute registry).
            Map.entry("com/huidu/farmersdelight/api/util/CompatAttributes.java", 1),
            Map.entry("com/huidu/farmersdelight/api/util/CompatItemMeta.java", 1));

    /**
     * The debug source set, which reaches into the plugin's own classes by reflection for fields the main
     * sources keep private (the stove's item arrays, the skillet's state, the recipe manager's visual manager).
     * Those lookups are legitimate for a debug-only build, but they are still a cost and still a ratchet:
     * measured from the sources at the point this budget was extended to cover the debug set.
     */
    private static final Map<String, Integer> DEBUG_TOOLS_BUDGET = Map.ofEntries(
            Map.entry("com/huidu/farmersdelight/debug/DebugToolsCommand.java", 10));

    private static final List<SourceSet> SOURCE_SETS = List.of(
            new SourceSet(Path.of("src", "main", "java"), MAIN_BUDGET),
            new SourceSet(Path.of("src", "debugTools", "java"), DEBUG_TOOLS_BUDGET));

    @Test
    void reflectionStaysInsideTheAuditedFilesAndCounts() throws IOException {
        List<String> grown = new ArrayList<>();
        List<String> newFiles = new ArrayList<>();
        for (SourceSet sourceSet : SOURCE_SETS) {
            String rootLabel = sourceSet.root().toString().replace('\\', '/');
            for (Path file : javaSources(sourceSet.root())) {
                String relative = sourceSet.root().relativize(file).toString().replace('\\', '/');
                String key = rootLabel + ":" + relative;
                int found = countMatches(file, REFLECTIVE);
                Integer budget = sourceSet.perFileBudget().get(relative);
                if (budget == null) {
                    if (found > 0) {
                        newFiles.add(key + " (" + found + ")");
                    }
                } else if (found > budget) {
                    grown.add(key + ": " + budget + " -> " + found);
                }
            }
        }

        assertTrue(newFiles.isEmpty(),
                "Reflection appeared in files the audit cleared. If the lookup is genuinely needed (a class that"
                        + " is not on the compile classpath, an optional plugin, or a class the release build"
                        + " omits), resolve it once into a static final or a volatile holder and add the file"
                        + " here with its count. Files:\n  " + String.join("\n  ", newFiles));
        assertTrue(grown.isEmpty(),
                "Reflection grew in audited files. Resolve the lookup once and cache it (see"
                        + " SchedulerAdapter.FoliaReflect, CraftEngineModelMappings or MMOItemsCompat for the"
                        + " shape), then lower this file's number. Growth:\n  " + String.join("\n  ", grown));
    }

    /**
     * Bukkit, Java and javax classes are always on the compile classpath, so a Class.forName for one
     * only hides a direct reference behind a string and a class-loader query. There are none in the
     * plugin; this keeps it that way.
     */
    @Test
    void compileTimeClassesAreNotLookedUpByName() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (SourceSet sourceSet : SOURCE_SETS) {
            for (Path file : javaSources(sourceSet.root())) {
                Matcher matcher = COMPILE_TIME_LOOKUP.matcher(read(file));
                while (matcher.find()) {
                    offenders.add(sourceSet.root().relativize(file) + ": " + matcher.group());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These classes are on the compile classpath, so reference them directly instead of by name:"
                        + "\n  " + String.join("\n  ", offenders));
    }

    private static List<Path> javaSources(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("src/main/java/com/huidu/farmersdelight/debug"))
                    .sorted()
                    .toList();
        }
    }

    private static int countMatches(Path file, Pattern pattern) throws IOException {
        int count = 0;
        Matcher matcher = pattern.matcher(read(file));
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }
}
