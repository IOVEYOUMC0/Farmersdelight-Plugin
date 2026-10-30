package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.PluginAccess;
import com.huidu.farmersdelight.api.config.BehaviorArgRecorder;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reports behaviour options a pack declared but the behaviour never read. Such an option is normally a typo:
 * the reader falls back to its default and the operator sees a feature that silently ignores their value.
 *
 * A nearby known key is reported as a warning with a suggestion, because that is the case worth acting on. An
 * option that resembles nothing is logged at detail level only, so a pack that keeps its own keys in the same
 * section does not produce noise. Each option is reported once per server run.
 */
public final class BehaviorArgDiagnostics {

    private static final int SUGGESTION_DISTANCE = 2;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Consumer<String> CONSOLE_WARNER = message -> {
        try {
            FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
            if (plugin != null && plugin.isEnabled()) {
                plugin.getLogger().warning(message);
            }
        } catch (Throwable noServer) {
            // Offline (unit tests, tooling) there is no server logger; the warning is then simply not shown.
        }
    };
    private static volatile Consumer<String> warner = CONSOLE_WARNER;
    private static volatile Consumer<String> detailer = BehaviorArgDiagnostics::logDetail;

    private BehaviorArgDiagnostics() {
    }

    /**
     * Compares the options the pack declared with the ones the factory read and reports the difference.
     * A null section or an empty one has nothing to compare and is skipped.
     */
    public static void reportUnread(@Nullable BlockDefinition block, @Nullable Key behaviorId,
                                    @Nullable ConfigSection section, Set<String> read) {
        if (section == null) {
            return;
        }
        Map<String, Object> declared = section.values();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        List<String> unread = new ArrayList<>(2);
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            String option = entry.getKey();
            if (option == null || option.isEmpty() || "type".equals(option)) {
                continue;
            }
            if (read.contains(BehaviorArgRecorder.sectionMarker(option))) {
                // The factory asked for this section as a whole, so it parses the children itself and a child it
                // did not name may still be read by that parser: nothing can be judged here.
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                for (Object child : nested.keySet()) {
                    if (!(child instanceof String name) || name.isEmpty()) {
                        continue;
                    }
                    String childPath = option + "." + name;
                    if (read.contains(childPath)) {
                        continue;
                    }
                    unread.add(childPath);
                }
                continue;
            }
            if (!read.contains(option)) {
                unread.add(option);
            }
        }
        for (String option : unread) {
            report(block, behaviorId, option, read);
        }
    }

    private static void report(@Nullable BlockDefinition block, @Nullable Key behaviorId, String option,
                               Set<String> read) {
        String blockId = block == null ? "?" : String.valueOf(block.id());
        String reason = behaviorId + "|" + blockId + "|" + option;
        if (!REPORTED.add(reason)) {
            return;
        }
        String subject = "behaviour option '" + option + "' of " + (behaviorId == null ? "a block" : behaviorId)
                + " on " + blockId;
        String suggestion = closest(option, read);
        if (suggestion != null) {
            warner.accept(subject + " is not read; did you mean '" + suggestion + "'?");
        } else {
            detailer.accept(subject + " is not read by this version and was ignored");
        }
    }

    /** The read option closest to this one, or null when nothing is within the suggestion distance. */
    @Nullable
    static String closest(String option, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            int distance = distance(option, candidate);
            if (distance <= SUGGESTION_DISTANCE && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** Levenshtein distance, two rows: option names are short, so the plain algorithm is enough. */
    static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static void logDetail(String message) {
        I18n.logDetailMessage("startup", message);
    }

    /** Test seam for the warning channel; null restores the console logger. */
    @ApiStatus.Internal
    public static void setWarner(@Nullable Consumer<String> replacement) {
        warner = replacement == null ? CONSOLE_WARNER : replacement;
    }

    /** Test seam for the detail channel; null restores the detail-level logger. */
    @ApiStatus.Internal
    public static void setDetailer(@Nullable Consumer<String> replacement) {
        detailer = replacement == null ? BehaviorArgDiagnostics::logDetail : replacement;
    }

    /** Forgets which options were already reported, so a reload reports them again. */
    @ApiStatus.Internal
    public static void resetReported() {
        REPORTED.clear();
    }
}
