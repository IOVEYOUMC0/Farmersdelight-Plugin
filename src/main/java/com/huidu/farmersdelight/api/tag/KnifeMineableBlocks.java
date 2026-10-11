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
 * Reads knife-mining tags from CE block definitions; it does not change speed or produce drops.
 * Uses defaultState().settings().tags(), supported since CE 26.8.2.
 * Definition lookups do not access a world and may run on any thread.
 * A linkage failure disables the lookup; runtime failures are retried. Both warn at most once per reason.
 */
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
    private static final Consumer<String> CONSOLE_WARNER = message -> {
        try {
            Bukkit.getServer().getLogger().warning(message);
        } catch (Throwable noServer) {
            // Offline (unit tests, tooling) there is no server logger; the warning is then simply not shown.
        }
    };
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

    /** Whether the console warner is installed; lets an offline test prove that a reset actually happened. */
    @ApiStatus.Internal
    public static boolean usingConsoleWarner() {
        return warner == CONSOLE_WARNER;
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
