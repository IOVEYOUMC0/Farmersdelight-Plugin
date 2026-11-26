package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;
import java.util.Set;

/**
 * Registry for addon crop rules. FarmersDelight consults these after its built-in crop behaviours;
 * they never replace CE growth, bonemeal or block behaviour.
 */
@ApiStatus.Experimental
public final class CropRules {

    private static final RuleStore<CropRule> STORE = new RuleStore<>();

    private CropRules() {
    }

    /** Registers a rule; returns false for null rules, null ids or a duplicate id. */
    public static boolean register(CropRule rule) {
        return rule != null && STORE.register(rule.id(), rule);
    }

    public static boolean unregister(ContentId id) {
        return STORE.unregister(id);
    }

    public static Optional<CropRule> find(ContentId id) {
        return STORE.find(id);
    }

    public static Set<ContentId> ids() {
        return STORE.ids();
    }

    /** First registered rule that matches the block, or null. Broken rules are skipped. */
    public static CropRule matching(Block block) {
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
