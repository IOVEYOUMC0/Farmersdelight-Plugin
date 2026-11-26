package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;
import java.util.Set;

/**
 * Registry for addon working-station rules.
 *
 * <p>Registration is additive and duplicate ids are rejected; call {@link #unregister(ContentId)}
 * before replacing a rule. Rules registered after content is loaded are visible immediately, and
 * a reload does not clear them.</p>
 */
@ApiStatus.Experimental
public final class StationRules {

    private static final RuleStore<StationRule> STORE = new RuleStore<>();

    private StationRules() {
    }

    /** Registers a rule; returns false for null rules, null ids or a duplicate id. */
    public static boolean register(StationRule rule) {
        return rule != null && STORE.register(rule.id(), rule);
    }

    public static boolean unregister(ContentId id) {
        return STORE.unregister(id);
    }

    public static Optional<StationRule> find(ContentId id) {
        return STORE.find(id);
    }

    public static Set<ContentId> ids() {
        return STORE.ids();
    }

    /** First registered rule that matches the block, or null. Broken rules are skipped. */
    public static StationRule matching(Block block) {
        if (block == null) {
            return null;
        }
        return STORE.firstMatching(rule -> rule.matches(block));
    }

    /** Test hook used by the API contract tests; not part of the addon-facing contract. */
    static void clearForTests() {
        STORE.clearForTests();
    }

    /** Clears every registered rule; called during FarmersDelight's own shutdown. */
    @ApiStatus.Internal
    public static void clearAll() {
        STORE.clearForTests();
    }

    static int size() {
        return STORE.size();
    }
}
