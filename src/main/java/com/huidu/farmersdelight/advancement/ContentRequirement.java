package com.huidu.farmersdelight.advancement;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;

import java.util.Arrays;
import java.util.List;

/**
 * The CraftEngine content one advancement (or one criterion of a multi-task advancement) needs in order to be
 * obtainable at all. Derived from what actually awards the advancement — the trigger site — not from its icon.
 *
 * ALWAYS covers everything a server owner cannot delete from the CraftEngine configuration: vanilla-driven,
 * item/block-tag-driven, plugin-config-driven, and behavior-keyed triggers (a behavior only ever runs because
 * some block declares it, so the trigger disappears together with its own configuration and never leaves a
 * dangling requirement on one id).
 *
 * ANY_ITEM / ANY_BLOCK / ANY_ITEM_OR_BLOCK are satisfied while at least one of the listed ids is still loaded,
 * which expresses both a hard single-id dependency (one id) and an "any of these" trigger (several ids).
 *
 * Unknown or unresolvable ids count as present: over-reporting presence only restores the ungated behaviour,
 * while under-reporting it would hide content that still works.
 */
public record ContentRequirement(Kind kind, List<String> ids) {

    public enum Kind {
        /** Not tied to any deletable CraftEngine id. */
        ALWAYS,
        /** Satisfied while any listed id is a loaded CraftEngine item. */
        ANY_ITEM,
        /** Satisfied while any listed id is a loaded CraftEngine block. */
        ANY_BLOCK,
        /** Satisfied while any listed id is a loaded CraftEngine item or block. */
        ANY_ITEM_OR_BLOCK
    }

    public static final ContentRequirement ALWAYS = new ContentRequirement(Kind.ALWAYS, List.of());

    public ContentRequirement {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }

    public static ContentRequirement anyItem(String... ids) {
        return new ContentRequirement(Kind.ANY_ITEM, Arrays.asList(ids));
    }

    public static ContentRequirement anyItem(List<String> ids) {
        return new ContentRequirement(Kind.ANY_ITEM, ids);
    }

    public static ContentRequirement anyBlock(String... ids) {
        return new ContentRequirement(Kind.ANY_BLOCK, Arrays.asList(ids));
    }

    /** Requirement for ids whose kind is not known (the addon-facing form): item or block both count. */
    public static ContentRequirement anyItemOrBlock(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return ALWAYS;
        }
        return new ContentRequirement(Kind.ANY_ITEM_OR_BLOCK, ids);
    }

    /** True when the content this requirement guards is still loaded. */
    public boolean isSatisfied() {
        if (kind == Kind.ALWAYS || ids.isEmpty()) {
            return true;
        }
        for (String id : ids) {
            if (exists(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean exists(String id) {
        if (id == null || id.isEmpty()) {
            return true;
        }
        // Key.of does not reject a bare id, it silently assumes the minecraft namespace. Probing such an id
        // would always miss and hide the advancement forever, so an id without an explicit namespace counts
        // as present instead. Requirement ids must be written as namespace:path.
        if (id.indexOf(':') < 0) {
            return true;
        }
        try {
            Key key = Key.of(id);
            return switch (kind) {
                case ANY_ITEM -> CraftEngineItems.byId(key) != null;
                case ANY_BLOCK -> CraftEngineBlocks.byId(key) != null;
                case ANY_ITEM_OR_BLOCK -> CraftEngineItems.byId(key) != null || CraftEngineBlocks.byId(key) != null;
                case ALWAYS -> true;
            };
        } catch (Exception e) {
            // A malformed id or a registry that cannot answer must not hide working content.
            return true;
        }
    }
}
