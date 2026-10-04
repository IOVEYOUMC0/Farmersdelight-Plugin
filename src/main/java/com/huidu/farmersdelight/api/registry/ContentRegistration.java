package com.huidu.farmersdelight.api.registry;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.loot.function.LootFunctions;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonFunctions;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registry;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets another plugin register its own CraftEngine content types — block behaviors, item behaviors,
 * common functions, common conditions and loot functions — under its own namespace, so its content pack
 * can reference them from YAML the same way FarmersDelight's own pack does.
 *
 * <p>Registrations are remembered here and applied through the same CraftEngine registries
 * FarmersDelight's own content uses ({@code BehaviorRegistrar}); they are re-applied on every
 * registration pass, so a CraftEngine reload that rebuilds its registries does not lose them.
 *
 * <p>An id must be namespaced ({@code myplugin:my_function}) and must <em>not</em> use the
 * {@code minecraft} or {@code farmersdelight} namespaces: those are reserved, and a content pack that
 * overrides FarmersDelight's own type ids would change behaviour for every other pack on the server.
 * Registering an id that is already taken — by FarmersDelight itself or by another plugin — is
 * rejected rather than silently replacing it.
 *
 * <p>Registering after CraftEngine has finished loading its content still succeeds, but content that
 * already exists only picks the type up after CraftEngine re-reads it; that case is reported through
 * the {@code plugin.content_registration_late} console key so the operator can reload CraftEngine
 * instead of guessing why the type appears to be ignored.
 *
 * <p>CraftEngine has no API to remove a registered type. {@link #unregister(Key)} therefore stops this
 * registry from re-applying the entry and forgets it, but the type stays registered inside CraftEngine
 * for the rest of the JVM run: content that already uses the id keeps working, and the id cannot be
 * registered again (a replacement factory would never be consulted). Use a new id instead.
 */
@ApiStatus.NonExtendable
public final class ContentRegistration {

    /** The CraftEngine registry a registration belongs to. */
    public enum Kind {
        BLOCK_BEHAVIOR("block behavior"),
        ITEM_BEHAVIOR("item behavior"),
        FUNCTION("function"),
        CONDITION("condition"),
        LOOT_FUNCTION("loot function");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private record Entry(Kind kind, Key id, Object factory) {
    }

    /**
     * The CraftEngine registry calls behind this facade. Internal on purpose: it exists so the
     * registry bookkeeping and its validation can be tested without a running CraftEngine, which is
     * also how the plugin checks that a reload re-applies external registrations.
     */
    @ApiStatus.Internal
    public interface Bridge {

        boolean isRegistered(Kind kind, Key id);

        void register(Kind kind, Key id, Object factory);

        boolean contentLoaded();
    }

    private static final Set<String> RESERVED_NAMESPACES = Set.of(Key.MINECRAFT_NAMESPACE, "farmersdelight");
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    // Ids withdrawn through unregister(). CraftEngine keeps the type, so a later registration of the same
    // id has to be refused with that reason instead of the generic "already registered" one.
    private static final Set<String> WITHDRAWN = ConcurrentHashMap.newKeySet();
    private static volatile Bridge bridge = new CraftEngineBridge();

    private ContentRegistration() {
    }

    /** Registers a block behavior type other packs can use as {@code myplugin:my_behavior}. */
    public static void registerBlockBehavior(Key id, BlockBehaviorFactory<?> factory) {
        add(Kind.BLOCK_BEHAVIOR, id, factory);
    }

    /** Registers an item behavior type. */
    public static void registerItemBehavior(Key id, ItemBehaviorFactory<?> factory) {
        add(Kind.ITEM_BEHAVIOR, id, factory);
    }

    /** Registers a common function type usable wherever CraftEngine accepts a function. */
    public static <T extends Function<Context>> void registerFunction(Key id, FunctionFactory<Context, T> factory) {
        add(Kind.FUNCTION, id, factory);
    }

    /** Registers a common condition type usable wherever CraftEngine accepts a condition. */
    public static <T extends Condition<Context>> void registerCondition(Key id, ConditionFactory<Context, T> factory) {
        add(Kind.CONDITION, id, factory);
    }

    /** Registers a loot function type for loot tables and drop rules. */
    public static void registerLootFunction(Key id, LootFunctionFactory<?> factory) {
        add(Kind.LOOT_FUNCTION, id, factory);
    }

    /**
     * Forgets every kind registered under this id and stops re-applying it. Returns whether anything was
     * removed. CraftEngine itself keeps the type for the rest of the run — see the class notes — so the
     * id cannot be registered again afterwards.
     */
    public static boolean unregister(Key id) {
        if (id == null) {
            return false;
        }
        String key = keyOf(id);
        boolean removed = false;
        for (Kind kind : Kind.values()) {
            if (ENTRIES.remove(entryKey(kind, key)) != null) {
                removed = true;
            }
        }
        if (removed) {
            WITHDRAWN.add(key);
        }
        return removed;
    }

    /** Whether any kind is currently registered under this id. */
    public static boolean isRegistered(Key id) {
        if (id == null) {
            return false;
        }
        String key = keyOf(id);
        for (Kind kind : Kind.values()) {
            if (ENTRIES.containsKey(entryKey(kind, key))) {
                return true;
            }
        }
        return false;
    }

    /** Every id currently registered through this registry, without duplicates. Order is unspecified. */
    public static Set<Key> registeredIds() {
        Set<Key> ids = new LinkedHashSet<>();
        for (Kind kind : Kind.values()) {
            for (Entry entry : ENTRIES.values()) {
                if (entry.kind() == kind) {
                    ids.add(entry.id());
                }
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Re-applies every remembered registration of one kind that CraftEngine no longer reports. Called by
     * the plugin's own registration pass. The CraftEngine versions this plugin targets keep their built-in
     * type registries for the whole class-loader lifetime, so this is defence in depth rather than a step a
     * reload depends on. Returns how many were (re-)applied.
     */
    @ApiStatus.Internal
    public static int apply(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        int applied = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.kind() != kind || bridge.isRegistered(kind, entry.id())) {
                continue;
            }
            bridge.register(kind, entry.id(), entry.factory());
            applied++;
        }
        return applied;
    }

    /** Swaps the CraftEngine calls for a test double. Internal: production code never calls this. */
    @ApiStatus.Internal
    public static void installBridge(Bridge replacement) {
        bridge = Objects.requireNonNull(replacement, "replacement");
    }

    /** Drops every remembered registration and restores the CraftEngine bridge. Internal, test-only. */
    @ApiStatus.Internal
    public static void resetForTests() {
        ENTRIES.clear();
        WITHDRAWN.clear();
        bridge = new CraftEngineBridge();
    }

    private static void add(Kind kind, Key id, Object factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        if (id.namespace() == null || id.namespace().isBlank() || id.value() == null || id.value().isBlank()) {
            throw new IllegalArgumentException("id must be namespaced, for example 'myplugin:my_function'");
        }
        if (RESERVED_NAMESPACES.contains(id.namespace())) {
            throw new IllegalArgumentException("namespace '" + id.namespace() + "' is reserved by FarmersDelight"
                    + " and Minecraft; register under your own plugin namespace");
        }
        String key = keyOf(id);
        if (ENTRIES.containsKey(entryKey(kind, key))) {
            throw new IllegalStateException("id " + key + " is already registered by an external plugin as a "
                    + kind.label());
        }
        if (bridge.isRegistered(kind, id)) {
            if (WITHDRAWN.contains(key)) {
                throw new IllegalStateException("id " + key + " was unregistered earlier and CraftEngine keeps a"
                        + " registered type for the rest of the run, so it cannot be registered again;"
                        + " use a different id");
            }
            throw new IllegalStateException("id " + key + " is already registered with CraftEngine as a "
                    + kind.label() + " (FarmersDelight's own content or another plugin)");
        }
        Entry entry = new Entry(kind, id, factory);
        if (ENTRIES.putIfAbsent(entryKey(kind, key), entry) != null) {
            throw new IllegalStateException("id " + key + " is already registered by an external plugin as a "
                    + kind.label());
        }
        WITHDRAWN.remove(key);
        bridge.register(kind, id, factory);
        if (bridge.contentLoaded()) {
            I18n.logWarning("plugin.content_registration_late", "id", key, "kind", kind.label());
        }
    }

    private static String keyOf(Key id) {
        return id.namespace() + ":" + id.value();
    }

    // Per kind on purpose: CraftEngine keeps one registry per kind, so the same id may name a function
    // and a condition at once, and only a duplicate inside one registry is a real conflict.
    private static String entryKey(Kind kind, String id) {
        return kind.name() + "|" + id;
    }

    private static final class CraftEngineBridge implements Bridge {

        @Override
        public boolean isRegistered(Kind kind, Key id) {
            return registryOf(kind).getValue(id) != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void register(Kind kind, Key id, Object factory) {
            switch (kind) {
                case BLOCK_BEHAVIOR -> BlockBehaviors.register(id, (BlockBehaviorFactory<?>) factory);
                case ITEM_BEHAVIOR -> ItemBehaviors.register(id, (ItemBehaviorFactory<?>) factory);
                case FUNCTION -> CommonFunctions.register(id, (FunctionFactory<Context, ?>) factory);
                case CONDITION -> CommonConditions.register(id, (ConditionFactory<Context, ?>) factory);
                case LOOT_FUNCTION -> LootFunctions.register(id, (LootFunctionFactory<?>) factory);
            }
        }

        @Override
        public boolean contentLoaded() {
            return FarmersDelightApi.get().isContentLoaded();
        }

        private static Registry<?> registryOf(Kind kind) {
            return switch (kind) {
                case BLOCK_BEHAVIOR -> BuiltInRegistries.BLOCK_BEHAVIOR_TYPE;
                case ITEM_BEHAVIOR -> BuiltInRegistries.ITEM_BEHAVIOR_TYPE;
                case FUNCTION -> BuiltInRegistries.COMMON_FUNCTION_TYPE;
                case CONDITION -> BuiltInRegistries.COMMON_CONDITION_TYPE;
                case LOOT_FUNCTION -> BuiltInRegistries.LOOT_FUNCTION_TYPE;
            };
        }
    }
}
