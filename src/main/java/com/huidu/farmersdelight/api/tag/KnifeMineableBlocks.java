package com.huidu.farmersdelight.api.tag;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.setting.BlockSettings;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reads the "mineable with a knife" declaration of a CraftEngine block definition.
 *
 * <p><b>The tag is data; the behaviour is CraftEngine's.</b> A pack declares
 * {@code farmersdelight:mineable/knife} (or the common {@code c:mineable/knife}) in a block's
 * {@code settings.tags}; CraftEngine writes those tags onto the block's holder, and the drop behaviour
 * itself comes from the pack's {@code match_item} rules. This query only answers what a definition
 * declares — wiring a drop to it in Java would double the drops on every block whose pack already has such
 * a rule.
 *
 * <p><b>It does not change mining speed.</b> Vanilla break speed comes from {@code minecraft:mineable/*}
 * plus the carrier item's digger rules, and CraftEngine's own tool check uses
 * {@code settings.requireCorrectTool()} / {@code requiredBreakPower()} / {@code settings.isCorrectTool(..)}
 * — none of them read {@code settings.tags()} (see {@code BlockStateUtils} in CraftEngine). Use
 * {@code require_correct_tool} in the pack if a tool requirement has to gate drops.
 *
 * <p><b>Version path.</b> The lookup goes {@code BlockDefinition.defaultState().settings().tags()}. That
 * path exists in CraftEngine 26.8.2, 26.9.1 and 26.9.2 alike, while {@code BlockDefinition#settings()} does
 * not exist in any of them: the settings live on the block state. This plugin is compiled against the
 * newest CraftEngine artifact published to Maven (26.9.1 at the time of writing — 26.9.2 and 26.10 are
 * source/snapshot only), and the supported runtime floor is 26.8.2.
 *
 * <p><b>Failures are classified</b>, so a server on an unsupported CraftEngine is told once and then left
 * alone:
 * <ul>
 *   <li>a {@link LinkageError} ({@code NoSuchMethodError}, {@code NoClassDefFoundError}, ...) means the API
 *       is structurally unavailable: this query warns once and disables itself for the rest of the run —
 *       later calls answer false without touching CraftEngine again and without logging again;</li>
 *   <li>a {@link RuntimeException} (not ready yet, definition reload in progress) answers false for that
 *       call only and is retried on the next one, with at most one log line per distinct reason.</li>
 * </ul>
 *
 * <p><b>Threading:</b> this is a pure definition lookup — it reads CraftEngine's in-memory registries and
 * never touches a world, chunk, block entity or block state, so it may be called from any thread and any
 * region. That is also why the entry point takes a block id rather than a {@code Block}: resolving a
 * {@code Block} to its CraftEngine id needs the world's custom state, which is a region-bound read.
 */
@ApiStatus.NonExtendable
public final class KnifeMineableBlocks {

    /** Looks a block definition's declared tags up through CraftEngine. Internal so it can be stubbed. */
    @ApiStatus.Internal
    public interface TagSource {

        /** The tags the definition declares, or an empty set when the id has no definition. */
        Set<Key> declaredTags(@Nullable Key blockId);
    }

    private static final Set<Key> KNIFE_TAGS = Set.of(
            Key.of(FarmersDelightTags.BLOCK_MINEABLE_WITH_KNIFE),
            Key.of(FarmersDelightTags.COMMON_MINEABLE_WITH_KNIFE));
    private static final Consumer<String> CONSOLE_WARNER = message -> Bukkit.getLogger().warning(message);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static volatile TagSource source = new CraftEngineTagSource();
    private static volatile Consumer<String> warner = CONSOLE_WARNER;
    private static volatile boolean unsupportedApi;

    private KnifeMineableBlocks() {
    }

    /**
     * Whether the block with this CraftEngine id declares that it is mineable with a knife. Null ids,
     * unknown ids, ids without a definition and a structurally unsupported CraftEngine all answer false.
     */
    public static boolean isKnifeMineable(@Nullable Key blockId) {
        if (blockId == null) {
            return false;
        }
        if (unsupportedApi) {
            // Already classified as unsupported: answer without touching CraftEngine again.
            return false;
        }
        Set<Key> tags;
        try {
            tags = source.declaredTags(blockId);
        } catch (LinkageError unavailable) {
            disableUnsupportedApi(unavailable);
            return false;
        } catch (RuntimeException temporary) {
            // CraftEngine not up yet or a definition that is being reloaded: the safe answer, retried later.
            reportOnce("temporary:" + temporary.getClass().getName(),
                    "FarmersDelight: reading CraftEngine block tags failed temporarily ("
                            + temporary.getClass().getSimpleName() + "); the query will be retried.");
            return false;
        }
        return matchesKnifeTag(tags);
    }

    /** True once a structural API failure was seen; this query is inert from then on. */
    @ApiStatus.Internal
    public static boolean isDisabledForUnsupportedApi() {
        return unsupportedApi;
    }

    /** The predicate on its own; testable without CraftEngine. */
    @ApiStatus.Internal
    public static boolean matchesKnifeTag(@Nullable Set<Key> declaredTags) {
        if (declaredTags == null || declaredTags.isEmpty()) {
            return false;
        }
        for (Key tag : declaredTags) {
            if (tag != null && KNIFE_TAGS.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    @ApiStatus.Internal
    public static void setSource(@Nullable TagSource replacement) {
        source = replacement == null ? new CraftEngineTagSource() : replacement;
    }

    /** Test seam for the warning channel; null restores the console logger. */
    @ApiStatus.Internal
    public static void setWarner(@Nullable Consumer<String> replacement) {
        warner = replacement == null ? CONSOLE_WARNER : replacement;
        if (replacement == null) {
            REPORTED.clear();
        }
    }

    /** Clears the disabled flag and the once-per-reason log memory; used by tests and a full reload. */
    @ApiStatus.Internal
    public static void resetState() {
        unsupportedApi = false;
        REPORTED.clear();
    }

    private static void disableUnsupportedApi(LinkageError failure) {
        unsupportedApi = true;
        reportOnce("unsupported",
                "FarmersDelight: this CraftEngine build does not provide the block-tag API this plugin"
                        + " expects (" + failure.getClass().getSimpleName() + "). This plugin is compiled"
                        + " against the newest CraftEngine published to Maven (26.9.1 at the time of writing;"
                        + " 26.9.2 and 26.10 exist as source/snapshot only) and the supported runtime floor is"
                        + " 26.8.2, so the running CraftEngine is older or different than expected: the"
                        + " knife-tag query is disabled for the rest of this server run.");
    }

    private static void reportOnce(String reason, String message) {
        if (REPORTED.add(reason)) {
            warner.accept(message);
        }
    }

    /** The production lookup: definition only, no world access. */
    private static final class CraftEngineTagSource implements TagSource {

        @Override
        public Set<Key> declaredTags(@Nullable Key blockId) {
            if (blockId == null) {
                return Set.of();
            }
            Optional<BlockDefinition> definition = BukkitCraftEngine.instance().blockManager().blockById(blockId);
            if (definition.isEmpty()) {
                return Set.of();
            }
            // 26.9.1 exposes the settings on the block state, not on the definition itself.
            BlockSettings settings = definition.get().defaultState().settings();
            if (settings == null) {
                return Set.of();
            }
            Set<Key> tags = settings.tags();
            return tags == null ? Set.of() : tags;
        }
    }
}
