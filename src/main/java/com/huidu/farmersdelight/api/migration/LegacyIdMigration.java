package com.huidu.farmersdelight.api.migration;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets an addon declare item ids that used to exist and now have a replacement, so stacks that are already
 * in the world are rewritten the next time FarmersDelight touches them.
 *
 * <p>CraftEngine has no alias mechanism, so a renamed id would otherwise leave every existing stack as an
 * unknown item. Addons call {@link #registerItem(Key, ItemStack)} (or the {@link Key} overload) from their
 * {@code onEnable}, after FarmersDelight has loaded; the plugin's automatic hooks then rewrite those stacks
 * on the thread that owns them. No addon code has to run on its own.
 *
 * <p><b>Precondition:</b> the legacy id must still have an item definition in the pack that produced it,
 * otherwise CraftEngine turns the old stack into an unknown item before anything here can see it, and
 * {@link #isLegacy(ItemStack)} will never match. Keep the old definition in the pack (even pointing at the
 * same model) for as long as old stacks may exist.
 *
 * <p>Contract of {@link #migrate(ItemStack)}:
 * <ul>
 *   <li>An id that is not registered is returned unchanged, as is an empty stack or {@code null}.</li>
 *   <li>A registered id becomes the replacement item; the stack <em>amount</em> is kept.</li>
 *   <li>Persistent data is <em>merged</em>: entries the replacement already carries win (this includes the
 *       CraftEngine id key, so the result really is the new item), and entries only the old stack had are
 *       carried over. Anything else on the old stack — custom name, lore, enchantments, damage — is not
 *       copied; an addon that needs those should pass a fully prepared replacement stack to
 *       {@link #registerItem(Key, ItemStack)}, whose own meta is used as the template.</li>
 *   <li>Calling it again on a migrated stack changes nothing (the new id is not registered as legacy).</li>
 *   <li>Threading: it only reads the stack it is given and performs no world access, so any thread or region
 *       may call it. The automatic hooks always call it on the owner of the inventory or entity.</li>
 * </ul>
 *
 * <p>Registering the same legacy id twice keeps the first mapping and reports the second, so two addons
 * cannot silently fight over one id.
 */
@ApiStatus.NonExtendable
public final class LegacyIdMigration {

    private static final int MAX_CHAIN = 8;
    private static final Map<String, String> MAPPINGS = new ConcurrentHashMap<>();
    private static final Map<String, ItemStack> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<String, String> CONFLICTS = new ConcurrentHashMap<>();
    private static volatile Bridge bridge = new ItemStackBridge();
    private static volatile Consumer<String> conflictReporter = message -> {
    };

    /**
     * The CraftEngine/Bukkit calls behind this facade. Internal on purpose: it exists so the mapping rules
     * and the replacement contract can be exercised without a running server, which is also how the plugin
     * tests the fast path and the conflict handling.
     */
    @ApiStatus.Internal
    public interface Bridge {

        /** The CraftEngine id of a stack, or null when it has none. */
        @Nullable
        String idOf(@Nullable ItemStack stack);

        /** Whether the id has a usable item definition. */
        boolean hasDefinition(String id);

        /** A stack of the given id, or null when the id has no definition. */
        @Nullable
        ItemStack create(String id);

        void setAmount(ItemStack stack, int amount);

        /** Carries the legacy stack's persistent data over; entries the replacement already has win. */
        void mergePersistentData(@Nullable ItemStack legacy, ItemStack replacement);
    }

    private LegacyIdMigration() {
    }

    /**
     * Declares that {@code legacyId} should become the item in {@code replacement}, which is also the meta
     * template for the migrated stack (amount is taken from the old stack).
     */
    public static void registerItem(Key legacyId, ItemStack replacement) {
        if (legacyId == null || replacement == null || replacement.getType().isAir()) {
            return;
        }
        // The template is stored under the id the replacement resolves to, so chained mappings share it.
        String currentId = ItemUtils.resolveItemId(replacement);
        if (currentId == null) {
            return;
        }
        TEMPLATES.putIfAbsent(currentId, replacement.clone());
        register(legacyId.toString(), currentId);
    }

    /** Declares that {@code legacyId} should become {@code currentId}, using that item's own definition. */
    public static void registerItem(Key legacyId, Key currentId) {
        if (legacyId == null || currentId == null) {
            return;
        }
        register(legacyId.toString(), currentId.toString());
    }

    /** Whether this stack carries an id that has been registered as legacy. */
    public static boolean isLegacy(@Nullable ItemStack stack) {
        if (stack == null || MAPPINGS.isEmpty()) {
            return false;
        }
        String id = bridge.idOf(stack);
        return id != null && MAPPINGS.containsKey(id);
    }

    /**
     * Returns the replacement for a legacy stack, or the same stack when it is not legacy. See the class
     * notes for what is preserved.
     */
    public static ItemStack migrate(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        // Fast path: with an empty table this is the only work done, so the hooks cost nothing until an
        // addon registers something.
        if (MAPPINGS.isEmpty()) {
            return stack;
        }
        Bridge active = bridge;
        String currentId = resolve(active.idOf(stack));
        if (currentId == null) {
            return stack;
        }
        return replace(currentId, stack, stack.getAmount(), active);
    }

    /** The id {@code legacyId} ends up as, or null when it is not registered. Testable without a server. */
    @Nullable
    public static String resolveId(@Nullable String legacyId) {
        return resolve(legacyId);
    }

    /** Whether anything is registered; the hooks skip their work entirely while this is false. */
    public static boolean isEmpty() {
        return MAPPINGS.isEmpty();
    }

    /** How many legacy ids are registered. */
    public static int size() {
        return MAPPINGS.size();
    }

    /** How many registrations were rejected because the id was already mapped elsewhere. */
    public static int conflictCount() {
        return CONFLICTS.size();
    }

    /** Forgets every registration; used by tests and by a full plugin reload of the table. */
    @ApiStatus.Internal
    public static void clear() {
        MAPPINGS.clear();
        TEMPLATES.clear();
        CONFLICTS.clear();
    }

    @ApiStatus.Internal
    public static void setBridge(@Nullable Bridge replacement) {
        // null puts the production bridge back, so a test that injects a stub can restore the real one
        // instead of leaving its stub installed for whatever runs next.
        bridge = replacement == null ? new ItemStackBridge() : replacement;
    }

    /** Whether the production bridge is installed; lets an offline test prove a reset actually happened. */
    @ApiStatus.Internal
    public static boolean usingProductionBridge() {
        return bridge instanceof ItemStackBridge;
    }

    /** Where duplicate registrations are reported; the plugin points this at its logger. */
    @ApiStatus.Internal
    public static void setConflictReporter(@Nullable Consumer<String> reporter) {
        conflictReporter = reporter == null ? message -> {
        } : reporter;
    }

    /**
     * Builds the replacement from the registered template (or the current item's own definition), then
     * applies the amount and merges persistent data. Split out so the contract is testable with a stub
     * bridge: an offline test cannot construct an ItemStack at all.
     */
    @ApiStatus.Internal
    static ItemStack replace(String currentId, @Nullable ItemStack legacy, int amount, Bridge active) {
        ItemStack template = TEMPLATES.get(currentId);
        if (template == null && !active.hasDefinition(currentId)) {
            // The replacement has no definition: leaving the stack alone keeps the old item usable instead
            // of deleting it.
            return legacy;
        }
        ItemStack replacement = template == null ? active.create(currentId) : template.clone();
        if (replacement == null) {
            // CraftEngine can drop an item definition between the check above and this call (its reload
            // window), so a null here is expected: keep the old stack instead of failing inside an event.
            return legacy;
        }
        if (amount > 0) {
            active.setAmount(replacement, amount);
        }
        active.mergePersistentData(legacy, replacement);
        return replacement;
    }

    private static void register(String legacyId, String currentId) {
        String key = normalize(legacyId);
        if (key == null) {
            return;
        }
        if (key.equals(currentId)) {
            // A self-mapping would rebuild the stack from its definition on every touch, dropping the meta
            // this facility does not carry over. There is nothing to migrate, so the id stays untouched.
            return;
        }
        String previous = MAPPINGS.putIfAbsent(key, currentId);
        if (previous == null) {
            return;
        }
        if (previous.equals(currentId)) {
            return;
        }
        // Keep the first mapping; report the loser once per legacy id so a registration war is visible.
        if (CONFLICTS.putIfAbsent(key, currentId) == null) {
            conflictReporter.accept("Legacy id " + key + " is already mapped to " + previous
                    + "; ignoring the later mapping to " + currentId);
        }
    }

    @Nullable
    private static String resolve(@Nullable String legacyId) {
        String current = normalize(legacyId);
        if (current == null) {
            return null;
        }
        String first = MAPPINGS.get(current);
        if (first == null) {
            return null;
        }
        // Follow a chain (A -> B -> C) so one call is enough, with a bound that also breaks a cycle.
        String resolved = first;
        for (int step = 0; step < MAX_CHAIN; step++) {
            String next = MAPPINGS.get(resolved);
            if (next == null || next.equals(resolved)) {
                return resolved;
            }
            resolved = next;
        }
        return resolved;
    }

    @Nullable
    private static String normalize(@Nullable String id) {
        if (id == null) {
            return null;
        }
        // Only trimmed: ids are case sensitive (MMOItems ids carry an upper-case type segment).
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The production bridge: CraftEngine ids and ItemStack metadata. */
    private static final class ItemStackBridge implements Bridge {

        @Override
        public String idOf(ItemStack stack) {
            return ItemUtils.resolveItemId(stack);
        }

        @Override
        public boolean hasDefinition(String id) {
            return ItemUtils.createItem(id) != null;
        }

        @Override
        public ItemStack create(String id) {
            return ItemUtils.createItem(id);
        }

        @Override
        public void setAmount(ItemStack stack, int amount) {
            stack.setAmount(amount);
        }

        @Override
        public void mergePersistentData(@Nullable ItemStack legacy, ItemStack replacement) {
            if (legacy == null) {
                return;
            }
            ItemMeta legacyMeta = legacy.getItemMeta();
            if (legacyMeta == null) {
                return;
            }
            PersistentDataContainer source = legacyMeta.getPersistentDataContainer();
            if (source.isEmpty()) {
                return;
            }
            ItemMeta target = replacement.getItemMeta();
            if (target == null) {
                return;
            }
            // replace=false: the replacement's own entries (including CraftEngine's id key) win.
            source.copyTo(target.getPersistentDataContainer(), false);
            replacement.setItemMeta(target);
        }
    }
}
