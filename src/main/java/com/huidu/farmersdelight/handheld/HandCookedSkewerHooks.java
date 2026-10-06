package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.manager.HandheldDisplays;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The wiring for handheld skewer cooking: a player right-clicks a raw skewer next to a heat source, the skewer
 * cooks for handheld-skewer.cooking-time-ticks ticks on the player's own scheduler, and finishing
 * consumes exactly one skewer and hands over the cooked one.
 *
 * Construction only stores the plugin (the same contract as the migration hooks), so the
 * registration order can be asserted without a server. start() reads the config and arms or disarms
 * the path; handheld-skewer.enabled: false — or a result table with nothing in it — leaves the path
 * inert: no session can start, no tick task is scheduled, and every handler returns immediately. The listener
 * itself stays in the registration list either way, because that list is a fixed order (see
 * ListenerRegistrationOrderTest); "off" means "armed with nothing", not "missing".
 *
 * Lifecycle. The one-per-tick loop exists only while a session does: it is armed when a session
 * starts and cancels itself once the last one ends, so a second skewer arms it again. Every tick is gated on
 * the session still holding the raw skewer it started from and on the right-click still being held, the same
 * per-tick gate the handheld skillet uses; a tick that fails it ends the session and takes the progress bar
 * with it, so a released click cannot cook anything.
 *
 * The vanilla use. The raw skewers are food, so a right-click on one would also start eating it. The click is
 * cancelled only when this path accepts it and owns the session that follows; a click this path refuses (no
 * result, no heat, skillet busy, sneak required) is left alone and the skewer stays edible.
 *
 * Mutual exclusion. A player cooks one handheld item at a time: the skillet's session is checked
 * first (SkilletManager#isHandheldCooking) so the two paths cannot both claim the same right-click,
 * and HandCookedSkewerService#tryStart(UUID) refuses a second session for the same player.
 *
 * Known limits (real-server checklist). There is no client-side arm animation or progress bar (the
 * upstream mod uses a NeoForge client extension this plugin cannot replicate), and when the player stands
 * right on a region boundary a heat source that lives in the neighbour region may be missed, because the
 * dispatched probe read only sets a shared flag and the answer is used immediately — the safe direction (no
 * cooking starts) but a timing hole only a real Folia server can show.
 */
public final class HandCookedSkewerHooks implements Listener {

    /** Test seam: how the one-per-tick task is armed; production uses the plugin scheduler. */
    @ApiStatus.Internal
    interface TaskArm {

        PluginTask arm(Runnable tick);
    }

    /**
     * Test seam: the two things the loop needs from the server — the player behind a session and the way a
     * task is handed to that player's scheduler. Faking it lets a test drive the real tick() loop,
     * including the cancellation that happens when the last session ends.
     */
    @ApiStatus.Internal
    interface LoopSeam {

        @Nullable
        Player player(UUID id);

        void dispatch(Player player, Runnable task);
    }

    private final FarmersDelightPlugin plugin;

    /** The grace a held right-click survives without a new click event, matching the handheld skillet. */
    @ApiStatus.Internal
    static final long INPUT_TIMEOUT_MILLIS = 400L;

    /** A session started without a slot to remember: only the wiring tests start sessions that way. */
    @ApiStatus.Internal
    static final int UNKNOWN_SLOT = -1;

    /**
     * One running cook: the hand and inventory slot it started from, the stack it was started on, its raw id
     * and when a click last confirmed it. The hand, slot and stack identity are what the per-tick gate compares
     * against, so a stack that was dragged away or replaced ends the session instead of being cooked.
     */
    static final class SkewerSession {

        private final EquipmentSlot hand;
        private final int slot;
        @Nullable
        private final String rawId;
        @Nullable
        private final ItemStack startedWith;
        private volatile long lastInputMillis;

        SkewerSession(EquipmentSlot hand, int slot, @Nullable String rawId, @Nullable ItemStack startedWith,
                      long nowMillis) {
            this.hand = hand;
            this.slot = slot;
            this.rawId = rawId;
            this.startedWith = startedWith == null ? null : startedWith.clone();
            this.lastInputMillis = nowMillis;
        }

        EquipmentSlot hand() {
            return this.hand;
        }

        int slot() {
            return this.slot;
        }

        @Nullable
        String rawId() {
            return this.rawId;
        }

        @Nullable
        ItemStack startedWith() {
            return this.startedWith;
        }

        long lastInputMillis() {
            return this.lastInputMillis;
        }

        void refreshInput(long nowMillis) {
            this.lastInputMillis = nowMillis;
        }
    }

    /** The click one session is started from: which hand, which slot, the stack and its raw id. */
    @ApiStatus.Internal
    record StartTarget(EquipmentSlot hand, int slot, @Nullable ItemStack stack, @Nullable String rawId) {

        static StartTarget unknown(EquipmentSlot hand) {
            return new StartTarget(hand, UNKNOWN_SLOT, null, null);
        }
    }

    /** What one accepted click did to the session bookkeeping. */
    @ApiStatus.Internal
    enum SessionStart {

        /** A new session was recorded: the caller is the click that owns it. */
        STARTED,
        /** The click confirmed the running session; nothing restarts. */
        CONTINUED,
        /** No session for this click. */
        REFUSED
    }

    /** Which hand and slot started the session, so finishing consumes the skewer that was used. */
    private final Map<UUID, SkewerSession> hands = new ConcurrentHashMap<>();
    private volatile HandCookedSkewerService service;
    private volatile SkewerResultTable results = new SkewerResultTable(null);
    // Override table first, campfire recipe second (upstream default). Swapped for the production source in
    // start(); tests drive it directly through setResultSource.
    private volatile SkewerResultSource resultSource = this::configuredResult;
    // Handheld progress bar: the held skewer is rewritten for that viewer only (client-side copy). Disabled by
    // handheld-skewer.progress-display.enabled, and swapped for a recording fake by the wiring tests.
    private volatile boolean progressDisplayEnabled = true;
    private volatile int cookingTicks = HandCookedSkewerService.COOKING_TICKS;
    private volatile SkewerProgressDisplay progressDisplay = SkewerProgressDisplay.NONE;
    // One diagnostic line per player and reason, so a silent failure is loud exactly once.
    private final Set<String> reportedBlocks = ConcurrentHashMap.newKeySet();
    // Distinct entry-point signatures already logged. Bounded on purpose: reportedBlocks lives until the next
    // shutdown, while this one must not grow with uptime, so the oldest signature is dropped beyond the cap.
    // Synchronized because one click per player can arrive on several Folia region threads at once.
    private static final int ENTRY_LOG_CAPACITY = 256;
    private final Set<String> reportedEntryPoints = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(ENTRY_LOG_CAPACITY + 1, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > ENTRY_LOG_CAPACITY;
                }
            }));
    // The hand and held item one click is judged on, after the policy's null-hand fallback: the entry line and
    // the start path both read it instead of resolving the item twice.
    private record HandCandidate(EquipmentSlot hand, @Nullable ItemStack stack, @Nullable String rawId,
                                 boolean hasResult) {
    }
    private volatile boolean requireSneak;
    private volatile PluginTask tickTask = PluginTask.NOOP;
    private volatile TaskArm taskArm = this::armSchedulerTask;
    /**
     * The single lock over the loop's lifecycle: arming, cancelling, the tick loop's "is anything running"
     * check and the hand bookkeeping all take it together. Two regions can run a player's session start and a
     * tick at the same time, and splitting this into two locks would let both of them believe the loop is
     * cancelled (two parallel loops, so a skewer cooks in half the time) or cancelled-but-still-needed (no
     * loop at all, so the session never advances).
     */
    private final Object taskLock = new Object();
    /** Test seam: how the loop finds and reaches a player; production uses the server and the entity scheduler. */
    private volatile LoopSeam loopSeam = productionLoop();
    /** The nearby-heat answer; a seam so a test can drive the whole accept path without a server. */
    private volatile Predicate<Player> heatProbe = this::probeHeat;
    /** How a held stack resolves to its raw id and how a result id becomes an item; CraftEngine does both. */
    private volatile Function<ItemStack, String> idResolver = ItemUtils::resolveItemId;
    private volatile Function<String, ItemStack> itemFactory = ItemUtils::createItem;
    /** The clock the input grace is measured against; a seam so a test can age a click without sleeping. */
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    public HandCookedSkewerHooks(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Replaces the loop seam; null restores the production one. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setLoopSeam(@Nullable LoopSeam seam) {
        this.loopSeam = seam == null ? productionLoop() : seam;
    }

    /** Replaces the heat probe; null restores the production probe. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setHeatProbe(@Nullable Predicate<Player> probe) {
        this.heatProbe = probe == null ? this::probeHeat : probe;
    }

    /** Replaces the raw-id resolver; null restores the production one. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setIdResolver(@Nullable Function<ItemStack, String> resolver) {
        this.idResolver = resolver == null ? ItemUtils::resolveItemId : resolver;
    }

    /** Replaces the result-item factory; null restores the production one. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setItemFactory(@Nullable Function<String, ItemStack> factory) {
        this.itemFactory = factory == null ? ItemUtils::createItem : factory;
    }

    /** Replaces the input clock; null restores the wall clock. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setClock(@Nullable java.util.function.LongSupplier source) {
        this.clock = source == null ? System::currentTimeMillis : source;
    }

    private long nowMillis() {
        return this.clock.getAsLong();
    }

    private LoopSeam productionLoop() {
        return new LoopSeam() {

            @Override
            public Player player(UUID id) {
                FarmersDelightPlugin owner = plugin;
                return owner == null ? null : owner.getServer().getPlayer(id);
            }

            @Override
            public void dispatch(Player player, Runnable task) {
                FarmersDelightPlugin owner = plugin;
                if (owner != null) {
                    owner.scheduler().runForEntity(player, task);
                }
            }
        };
    }

    /** Reads the config and arms the path. Called once the listeners are registered, and on every reload. */
    public void start() {
        FarmersDelightPlugin active = plugin;
        if (active == null) {
            return;
        }
        // Literal paths on purpose: the config-path guard only accepts paths it can find in config.yml.
        applyConfig(active.getConfigBoolean(true, "handheld-skewer.enabled"),
                active.getConfigBoolean(false, "handheld-skewer.require-sneak"),
                active.getConfigInt(HandCookedSkewerService.COOKING_TICKS, "handheld-skewer.cooking-time-ticks"),
                readResults(active),
                this::armSchedulerTask,
                null);
        // Upstream result source: the configured override table first, then the campfire recipe, using the
        // very same cache instance the skillet queries (one recipe-table scan per rebuild).
        setResultSource(new CampfireSkewerResults(() -> results,
                () -> active.getSkilletManager() == null ? null : active.getSkilletManager().campfireRecipes()));
        // Literal path again, for the same guard reason as above.
        this.progressDisplayEnabled = active.getConfigBoolean(true, "handheld-skewer.progress-display.enabled");
        this.cookingTicks = Math.max(1, active.getConfigInt(HandCookedSkewerService.COOKING_TICKS,
                "handheld-skewer.cooking-time-ticks"));
        this.progressDisplay = new HeldSkewerProgressDisplay(active);
    }

    /** Test seam: replaces the progress display (null restores the no-op one). */
    @ApiStatus.Internal
    void setProgressDisplay(@Nullable SkewerProgressDisplay display) {
        this.progressDisplay = display == null ? SkewerProgressDisplay.NONE : display;
    }

    /** Test seam for the enabled switch. */
    @ApiStatus.Internal
    void setProgressDisplayEnabled(boolean enabled) {
        this.progressDisplayEnabled = enabled;
    }

    /** Test seam: cooking ticks the bar is scaled against. */
    @ApiStatus.Internal
    void setCookingTicks(int ticks) {
        this.cookingTicks = Math.max(1, ticks);
    }

    /**
     * Replaces the result source; null restores the override-table-only default. Used by start() and
     * by the wiring tests.
     */
    @ApiStatus.Internal
    void setResultSource(@Nullable SkewerResultSource source) {
        this.resultSource = source == null ? this::configuredResult : source;
    }

    /** The override table on its own: handheld-skewer.results looked up by the held item's id. */
    @Nullable
    private String configuredResult(@Nullable ItemStack held) {
        String rawId = resolveRawId(held);
        return rawId == null ? null : results.resolve(rawId);
    }

    /**
     * The cooked id the current source answers with, or null when the held item cannot cook. Exposed so the
     * wiring tests can drive the source order (configured override, then campfire recipe, then nothing).
     */
    @ApiStatus.Internal
    @Nullable
    String resolvedResult(@Nullable ItemStack held) {
        return resultSource.resultId(held);
    }

    /** The service, or null while the path is inert (disabled, or no result is configured). */
    @Nullable
    public HandCookedSkewerService service() {
        return service;
    }

    /**
     * Whether this player is cooking a skewer right now. Read-only state for the sibling handheld path: the
     * skillet refuses a click while a skewer session owns the player, the mirror of isSkilletCooking() here.
     * The bookkeeping behind the service stays private; only this answer leaves the class.
     */
    public boolean isCooking(@Nullable UUID player) {
        HandCookedSkewerService active = service;
        return active != null && active.isCooking(player);
    }

    /**
     * The arming decision on its own, so the wiring tests can drive it without a plugin or a server.
     *
     * Order matters: a disabled path never arms, a raw id this table does not cook never starts, and a
     * player whose skillet is already cooking never reaches HandCookedSkewerService#tryStart(UUID) —
     * the skillet owns that right-click.
     */
    @ApiStatus.Internal
    boolean applyConfig(boolean enabled, boolean requireSneak, int cookingTicks,
                        @Nullable Map<String, String> configuredResults, @Nullable TaskArm arm,
                        @Nullable Consumer<UUID> onCooked) {
        this.requireSneak = requireSneak;
        this.cookingTicks = Math.max(1, cookingTicks);
        SkewerResultTable table = new SkewerResultTable(configuredResults);
        this.results = table;
        this.taskArm = arm == null ? this::armSchedulerTask : arm;
        synchronized (taskLock) {
            HandCookedSkewerService previous = service;
            if (!enabled || table.isEmpty()) {
                if (previous != null) {
                    previous.setEnabled(false);
                }
                service = null;
                for (UUID id : hands.keySet()) {
                    progressDisplay.close(id);
                }
                hands.clear();
                cancelTaskLocked();
                return false;
            }
            if (previous != null) {
                // A reload replaces the service; the old sessions must not survive it.
                previous.setEnabled(false);
            }
            // Reload keeps the hand bookkeeping only as long as a session exists: dropping the service above
            // already dropped every session, so a leftover entry would keep the loop awake forever with
            // nothing to advance. Each dropped session loses its bar with it.
            for (UUID id : hands.keySet()) {
                progressDisplay.close(id);
            }
            hands.clear();
            service = new HandCookedSkewerService(true, () -> Math.max(1, cookingTicks), id -> {
                // The session entry goes first, whoever handles the conversion: the loop decides whether it is
                // still needed from this map, so a session that ended must never leave an entry behind.
                SkewerSession session;
                synchronized (taskLock) {
                    session = hands.remove(id);
                }
                if (onCooked == null) {
                    convert(id, session);
                } else {
                    onCooked.accept(id);
                }
            });
            // No tick loop is started here: it is armed by the session that needs it (see ensureTaskLocked),
            // so a reload with no session running does not leave an idle loop behind.
            cancelTaskLocked();
            return true;
        }
    }

    /** Whether a right-click on this raw id may start a session right now. Testable on its own. */
    @ApiStatus.Internal
    static boolean mayStart(boolean armed, boolean hasResult, boolean skilletCooking, boolean sneaking,
                            boolean requireSneak) {
        if (!armed || !hasResult || skilletCooking) {
            return false;
        }
        return !requireSneak || sneaking;
    }

    /**
     * Right click with a raw skewer in hand. HandCookedItem#use is the trigger and useOn is not overridden, so
     * a click the item can take falls through to it: an air click always does, and a block click does when the
     * block has no interaction of its own (fire, soul fire, lava, magma block, plain terrain) or when the
     * player sneaks. Which click is ours is decided only in SkewerBlockClickPolicy; this method
     * resolves the hand, reports, and starts the session.
     *
     * Runs at MONITOR. The vanilla use is cancelled only once this path accepts the click and has a session
     * for it: the raw skewers are food, so an uncancelled click would also start eating the skewer that is
     * being cooked, and one skewer would become a meal and a cooked skewer at the same time. A click this
     * path refuses is left untouched, so the skewer stays edible everywhere else.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();
        EquipmentSlot reportedHand = event.getHand();
        // Entry line first, so every return below leaves a trace — the "right click next to a heat source does
        // nothing" report had nothing at all. It resolves the held id only under the handheld debug category, so
        // a click that is not ours costs one action compare and one category check with debug off.
        String entryId = logEntryPoint(player, action, reportedHand, event.isCancelled(), clickedBlock);

        boolean rightClickBlock = action == Action.RIGHT_CLICK_BLOCK;
        boolean blockHasInteraction = rightClickBlock && clickedBlock != null
                && clickedBlock.getType().isInteractable();
        SkewerBlockClickPolicy.Decision decision = decideClick(action, blockHasInteraction, player.isSneaking());
        if (!SkewerBlockClickPolicy.accepted(decision)) {
            if (decision != SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK) {
                // A left click or a physical interaction is never ours and needs no second line; the block
                // verdict is what the operator has to see (the block keeps its click).
                // reportBlocked writes its first line per player and reason even with debug off, so it needs the
                // real held id — the entry line above already resolved it whenever the category is on.
                reportBlocked(player, entryId != null ? entryId : rawHeldId(player, reportedHand), decision.name());
            }
            return;
        }

        HandCandidate candidate = resolveCandidate(player, reportedHand);
        boolean armed = service != null;
        boolean skilletCooking = armed && isSkilletCooking(player);
        boolean nearbyHeat = armed && candidate.hasResult() && !skilletCooking && hasHeat(player);
        String blocked = blockedReason(armed, candidate.hasResult(), skilletCooking, player.isSneaking(),
                requireSneak, nearbyHeat, false);
        if (blocked != null) {
            // Never silent: a use that does not start says why, once per player per reason under debug, and
            // once per player in the log either way. Diagnosis beats a shrug.
            reportBlocked(player, candidate.rawId(), blocked);
            return;
        }
        // This click belongs to the skewer path: the slot is fixed here, because the gate and the conversion
        // both have to know which stack this session is about.
        int slot = candidate.hand() == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        StartTarget target = new StartTarget(candidate.hand(), slot, candidate.stack(), candidate.rawId());
        SessionStart start = handleUse(player.getUniqueId(), target, true, false, false, false, true);
        if (start == SessionStart.REFUSED) {
            if (plugin != null && plugin.isDebugEnabled("handheld")) {
                plugin.getLogger().info("[handheld] use was accepted but the session did not start for " + player.getName());
            }
            return;
        }
        // Owned by a session now (started or confirmed): stop the vanilla use, so a hungry player does not eat
        // the skewer this same click is cooking and the repeat clicks keep confirming the session.
        event.setCancelled(true);
        if (event.hasItem()) {
            // Paper reports no item for some air clicks; the use result can only be denied when there is one.
            event.setUseItemInHand(Event.Result.DENY);
        }
        if (start == SessionStart.STARTED) {
            startProgressDisplay(player.getUniqueId(), slot, candidate.rawId());
        }
    }

    /**
     * Observation only, and the reason it exists: the handler above is never called for an event another plugin
     * already cancelled (ignoreCancelled = true), so "the click did nothing" cannot be told apart from
     * "the event never arrived". This one is called for cancelled events as well and writes the same entry line
     * into the same bounded set — it reads, writes that one line, and returns. It never cancels the event, never
     * starts a session and never touches the inventory.
     *
     * At MONITOR on purpose: at the earliest priority no other listener has run yet, so cancelled would
     * read false for exactly the events this is meant to expose; by MONITOR the field carries the outcome of every
     * other plugin, which is the answer the field test needs. Sharing the dedupe with the handler above is what
     * keeps this at one line per action instead of two.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteractObserved(PlayerInteractEvent event) {
        if (plugin == null || !plugin.isDebugEnabled("handheld")) {
            return;
        }
        logEntryPoint(event.getPlayer(), event.getAction(), event.getHand(), event.isCancelled(),
                event.getClickedBlock());
    }

    /**
     * The first gate that stops this use, or null when it may start. Pure so the wiring tests can pin every
     * silent failure (no raw id, no result, skillet busy, sneaking required, no heat). blockClick is the
     * caller's verdict that the clicked block keeps the interaction, not "a block was clicked": a block click the
     * policy accepted is passed as false.
     */
    @ApiStatus.Internal
    @Nullable
    static String blockedReason(boolean armed, boolean hasResult, boolean skilletCooking, boolean sneaking,
                                boolean requireSneak, boolean nearbyHeat, boolean blockClick) {
        if (blockClick) {
            return "block-click (vanilla owns it)";
        }
        if (!armed) {
            return "disabled";
        }
        if (!hasResult) {
            return "no-result (override table miss and no campfire recipe)";
        }
        if (skilletCooking) {
            return "skillet-cooking";
        }
        if (requireSneak && !sneaking) {
            return "sneak-required";
        }
        if (!nearbyHeat) {
            return "no-heat";
        }
        return null;
    }

    /** One diagnostic line per player and reason, plus every reason when the handheld category is on debug. */
    private void reportBlocked(Player player, @Nullable String rawId, String reason) {
        FarmersDelightPlugin owner = plugin;
        if (owner == null) {
            return;
        }
        String key = player.getUniqueId() + "|" + reason;
        boolean first = reportedBlocks.add(key);
        if (first || owner.isDebugEnabled("handheld")) {
            owner.getLogger().info("[handheld] " + player.getName() + " right-click did not start cooking: "
                    + reason + " (held=" + rawId + ")");
        }
    }

    /**
     * The hand and held item this click is judged on, in the order SkewerBlockClickPolicy.handsToCheck
     * gives. A reported hand is the only candidate; an unknown one means main hand then off hand, so a skewer in
     * the off hand still cooks when Paper reports no hand for an air interaction. The first candidate holding an
     * item this source can cook wins; otherwise the first candidate is kept so the diagnostics can still name it.
     */
    private HandCandidate resolveCandidate(Player player, @Nullable EquipmentSlot reportedHand) {
        HandCandidate first = null;
        for (int index : SkewerBlockClickPolicy.handsToCheck(reportedHand != null)) {
            EquipmentSlot hand = handAt(index, reportedHand);
            ItemStack stack = held(player, hand);
            HandCandidate candidate = new HandCandidate(hand, stack, resolveRawId(stack),
                    resultSource.resultId(stack) != null);
            if (first == null) {
                first = candidate;
            }
            if (candidate.hasResult()) {
                return candidate;
            }
        }
        return first == null ? new HandCandidate(EquipmentSlot.HAND, null, null, false) : first;
    }

    /**
     * The hand behind one index of SkewerBlockClickPolicy.handsToCheck(boolean): index 0 is the hand
     * Paper reported — or the main hand when it reported none — and index 1 is the off hand.
     */
    @ApiStatus.Internal
    static EquipmentSlot handAt(int index, @Nullable EquipmentSlot reportedHand) {
        if (index <= 0) {
            return reportedHand != null ? reportedHand : EquipmentSlot.HAND;
        }
        return EquipmentSlot.OFF_HAND;
    }

    /**
     * The verdict for one action. The rule itself lives in the policy; this only maps the event's action onto it,
     * so a left click can never be accepted by accident.
     */
    @ApiStatus.Internal
    static SkewerBlockClickPolicy.Decision decideClick(Action action, boolean blockHasInteraction,
                                                       boolean sneaking) {
        return SkewerBlockClickPolicy.decide(action == Action.RIGHT_CLICK_AIR,
                action == Action.RIGHT_CLICK_BLOCK, blockHasInteraction, sneaking);
    }

    /**
     * One entry line per player and action signature, before any other return, under the handheld debug category.
     * The set is bounded (ENTRY_LOG_CAPACITY), so a long uptime cannot grow it; a changed action, hand,
     * cancellation state, held item or clicked block is logged again.
     *
     * @return the item id the line carried, or null when no line was written
     */
    @Nullable
    private String logEntryPoint(Player player, Action action, @Nullable EquipmentSlot reportedHand, boolean cancelled,
                                 @Nullable Block clickedBlock) {
        FarmersDelightPlugin owner = plugin;
        if (owner == null || !owner.isDebugEnabled("handheld")) {
            return null;
        }
        String itemId = rawHeldId(player, reportedHand);
        String blockType = clickedBlock == null ? "none" : clickedBlock.getType().toString();
        String key = entryPointKey(player.getUniqueId(), action, reportedHand, cancelled, itemId, blockType);
        if (!markEntryLogged(key)) {
            return itemId;
        }
        owner.getLogger().info(entryPointLine(action, reportedHand, cancelled, itemId, blockType));
        return itemId;
    }

    /**
     * Remembers one entry signature; true when it is new. Bounded by ENTRY_LOG_CAPACITY, so the oldest
     * signature is dropped instead of growing the set without limit.
     */
    @ApiStatus.Internal
    boolean markEntryLogged(String key) {
        return reportedEntryPoints.add(key);
    }

    /**
     * The held id the diagnostics name: the hand Paper reported, or the main hand when it reported none (the
     * off-hand fallback of the actual decision is not worth a second lookup just for a log line).
     */
    @Nullable
    private String rawHeldId(Player player, @Nullable EquipmentSlot reportedHand) {
        return resolveRawId(held(player, reportedHand != null ? reportedHand : EquipmentSlot.HAND));
    }

    /** The dedupe signature of one entry line; exposed so the bounded dedupe is testable without a server. */
    @ApiStatus.Internal
    static String entryPointKey(UUID player, Action action, @Nullable EquipmentSlot hand, boolean cancelled,
                                @Nullable String itemId, @Nullable String blockType) {
        return player + "|" + action + "|" + hand + "|" + cancelled + "|" + itemId + "|" + blockType;
    }

    /** The entry line itself, so its fields can be pinned without a server. */
    @ApiStatus.Internal
    static String entryPointLine(Action action, @Nullable EquipmentSlot hand, boolean cancelled,
                                 @Nullable String itemId, @Nullable String blockType) {
        return "[handheld] action=" + action + " hand=" + hand + " cancelled=" + cancelled
                + " item=" + (itemId == null ? "none" : itemId)
                + " block=" + (blockType == null ? "none" : blockType);
    }

    /** How many entry-point signatures are remembered; the bound is what keeps this set finite. */
    @ApiStatus.Internal
    int entryPointLogSize() {
        return reportedEntryPoints.size();
    }

    /** Test seam: the cap of the entry-point dedupe set. */
    @ApiStatus.Internal
    static int entryLogCapacity() {
        return ENTRY_LOG_CAPACITY;
    }

    /**
     * The start decision, on plain inputs so the tests can drive it without a server.
     *
     * Upstream parity: the heat check is the 3x3x3 cube around the player (or the player being on fire) and the
     * use has to be held for the full cooking time; useOn is not overridden, so a block click only starts this
     * when the block has no interaction of its own — SkewerBlockClickPolicy decides that and passes the
     * outcome in as blockClick (true meaning "the block keeps the click").
     */
    @ApiStatus.Internal
    boolean handleUse(UUID player, EquipmentSlot hand, boolean rawSkewer, boolean skilletCooking, boolean sneaking,
                      boolean blockClick, boolean nearbyHeat) {
        return handleUse(player, StartTarget.unknown(hand), rawSkewer, skilletCooking, sneaking, blockClick,
                nearbyHeat) != SessionStart.REFUSED;
    }

    /**
     * The same decision with the slot, stack and raw id the session has to remember: the wiring starts a
     * session with all three, a test that only drives the gate passes a target with an unknown slot.
     */
    @ApiStatus.Internal
    SessionStart handleUse(UUID player, StartTarget target, boolean rawSkewer, boolean skilletCooking,
                           boolean sneaking, boolean blockClick, boolean nearbyHeat) {
        if (blockClick) {
            // The policy left this click to the block (a campfire taking food): never cook behind vanilla's back.
            return SessionStart.REFUSED;
        }
        if (!mayStart(service != null, rawSkewer, skilletCooking, sneaking, requireSneak)) {
            return SessionStart.REFUSED;
        }
        if (!nearbyHeat) {
            // No heat means no session at all, so nothing is consumed and no tick runs.
            return SessionStart.REFUSED;
        }
        return beginSession(player, target);
    }

    /**
     * Records a session and makes sure the tick loop is running, or confirms the session this click continues.
     * The loop cancels itself once the last session ends (see tick()), so every new session has to arm it
     * again — a loop that was armed only when the config was read would stop after the first skewer and never
     * advance another one. Arming is idempotent: a running loop is left alone, so a player cannot end up with
     * two of them.
     *
     * A click on the same hand, slot and stack only refreshes the input clock, which is what keeps the session
     * alive while the client repeats its use packet. A click that no longer matches ends the running cook and
     * starts the new one, so the two never share a stack.
     */
    @ApiStatus.Internal
    SessionStart beginSession(UUID player, StartTarget target) {
        if (player == null || target == null || target.hand() == null) {
            return SessionStart.REFUSED;
        }
        synchronized (taskLock) {
            HandCookedSkewerService active = service;
            if (active == null) {
                return SessionStart.REFUSED;
            }
            SkewerSession running = hands.get(player);
            if (running != null) {
                if (matchesTarget(running, target)) {
                    running.refreshInput(nowMillis());
                    return SessionStart.CONTINUED;
                }
                hands.remove(player);
                active.cancel(player);
                progressDisplay.close(player);
            }
            if (!active.tryStart(player)) {
                return SessionStart.REFUSED;
            }
            hands.put(player, new SkewerSession(target.hand(), target.slot(), target.rawId(), target.stack(),
                    nowMillis()));
            ensureTaskLocked();
            return SessionStart.STARTED;
        }
    }

    private static boolean matchesTarget(SkewerSession running, StartTarget target) {
        return running.hand() == target.hand() && running.slot() == target.slot()
                && sameRawId(running.rawId(), target.rawId());
    }

    /** The two-argument start the wiring tests use: no slot and no stack to match against. */
    @ApiStatus.Internal
    boolean beginSession(UUID player, EquipmentSlot hand) {
        return beginSession(player, StartTarget.unknown(hand)) == SessionStart.STARTED;
    }

    private static boolean sameRawId(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    /** Releasing the use key: zero progress, and the bar goes with it before the next tick. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        cancel(event.getPlayer());
    }

    /** Switching hotbar slots: zero progress. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        cancel(event.getPlayer());
    }

    /** Switching hands: zero progress. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        cancel(event.getPlayer());
    }

    /** Dropping the skewer: zero progress. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancel(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        cancel(event.getEntity());
    }

    /** Being hit: zero progress. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancel(player);
        }
    }

    /**
     * The one-per-tick loop: every session is advanced on the scheduler of the player that owns it, and the bar
     * for that session is updated in the same dispatched task so no player or inventory read happens on the
     * loop's own thread.
     */
    private void tick() {
        synchronized (taskLock) {
            HandCookedSkewerService active = service;
            if (active == null || hands.isEmpty()) {
                // Nothing to advance: cancel under the same lock a session start takes, so a start that
                // arrives right now either ran before this check (and keeps the loop) or runs after it (and
                // arms a fresh one).
                cancelTaskLocked();
                return;
            }
            LoopSeam seam = loopSeam;
            for (Map.Entry<UUID, SkewerSession> entry : hands.entrySet()) {
                Player player = seam.player(entry.getKey());
                if (player == null) {
                    hands.remove(entry.getKey());
                    active.cancel(entry.getKey());
                    continue;
                }
                UUID id = entry.getKey();
                SkewerSession session = entry.getValue();
                // Dispatched while the lock is held on purpose: on Paper the task runs inline, so this calls
                // back into convert() on the same thread. Java monitors are reentrant, so the short
                // hands.remove there is safe, and the alternative — releasing the lock around the dispatch —
                // would let the loop be cancelled between the check above and the work it decided to do.
                //
                // The gate and the bar update belong to the same task: both read that player's hand and use
                // state, and on Folia this loop runs on the global scheduler while the task runs on the
                // player's entity scheduler. A session the gate refuses is ended right here, so the bar goes
                // away in the same tick the click was released. Inside the task the tick is applied first, so
                // the bar still reports progress that really happened.
                seam.dispatch(player, () -> {
                    if (!mayAdvance(player, session) || !sessionStillHolds(player, session)) {
                        endSession(id, session);
                        return;
                    }
                    active.tick(id);
                    updateProgressDisplay(id, active);
                });
            }
        }
    }

    /**
     * Whether the session may advance this tick: the player is alive and still holding right-click. A native
     * use means the client is eating or drinking something; when it is the item this session is cooking, that
     * use owns the click and the session is stale, and when it is the other hand the skillet rule applies and
     * the skewer click is not held either. Holding is recognised by the input clock the accepted clicks refresh,
     * because the client repeats its use packet only about every four ticks.
     */
    private boolean mayAdvance(Player player, SkewerSession session) {
        if (player.isDead() || player.isHandRaised()) {
            return false;
        }
        return nowMillis() - session.lastInputMillis() <= INPUT_TIMEOUT_MILLIS;
    }

    /**
     * Whether the stack the session was started on is still the stack in that slot, mirroring the handheld
     * skillet's session match: the same item with a positive amount. A session started without a slot or a
     * stack (the wiring tests) has no identity to compare and is judged on the input alone.
     */
    private boolean sessionStillHolds(Player player, SkewerSession session) {
        if (session.slot() < 0 || session.startedWith() == null) {
            return true;
        }
        if (session.hand() == EquipmentSlot.HAND && player.getInventory().getHeldItemSlot() != session.slot()) {
            // The player moved the selection off the skewer: the hand no longer points at it.
            return false;
        }
        ItemStack current = player.getInventory().getItem(session.slot());
        return current != null && current.getAmount() > 0 && session.startedWith().isSimilar(current);
    }

    /** Ends one session and its bar; a session that was already replaced by a newer one is left alone. */
    private void endSession(UUID id, SkewerSession session) {
        synchronized (taskLock) {
            if (hands.get(id) != session) {
                return;
            }
            hands.remove(id);
            HandCookedSkewerService active = service;
            if (active != null) {
                active.cancel(id);
            }
            progressDisplay.close(id);
        }
    }

    /**
     * Finishes one skewer. Runs on the player's region (dispatched by tick()), consumes one raw
     * skewer in the hand that started the session and hands over the cooked one; a full inventory drops it.
     * No stack is cloned and no durability is written: the held stack is only shrunk by one. The session entry
     * was already removed by the session wrapper in start().
     *
     * The conversion re-checks that the session still holds its stack, because a lifecycle event can end the
     * session between the tick that reached the end and this call; it must not take an item the player no
     * longer offered. The consumption is atomic: the inventory is snapshotted first and restored when the
     * commit throws, so a half-finished conversion cannot leave the raw skewer in the hand and the cooked one
     * in the inventory at the same time.
     */
    private void convert(UUID id, @Nullable SkewerSession session) {
        // The loop's own way of finding the player: the same lookup in production, and the seam the wiring
        // tests replace, so the conversion can be driven without a server.
        Player player = session == null ? null : loopSeam.player(id);
        if (player == null) {
            progressDisplay.close(id);
            return;
        }
        try {
            if (!sessionStillHolds(player, session)) {
                return;
            }
            ItemStack held = held(player, session.hand());
            // Same source the session was started from: the override table first, then the campfire recipe.
            String cookedId = resultSource.resultId(held);
            if (cookedId == null) {
                // The held item changed while cooking; nothing is produced and nothing is consumed.
                return;
            }
            ItemStack cooked = this.itemFactory.apply(cookedId);
            if (cooked == null) {
                return;
            }
            ItemStack[] before = snapshotContents(player);
            List<Item> drops = new ArrayList<>();
            try {
                int left = held.getAmount() - 1;
                if (left > 0) {
                    held.setAmount(left);
                    setHeld(player, session.hand(), held);
                } else {
                    setHeld(player, session.hand(), null);
                }
                for (ItemStack rest : player.getInventory().addItem(cooked).values()) {
                    drops.add(player.getWorld().dropItem(player.getLocation(), rest));
                }
            } catch (RuntimeException | LinkageError failure) {
                for (Item drop : drops) {
                    drop.remove();
                }
                player.getInventory().setContents(before);
                throw failure;
            }
        } finally {
            // The bar goes away after the stack changed: closing it resends the player's current slot, so the
            // client ends on the real item and never on the cooking copy.
            progressDisplay.close(id);
        }
    }

    private static ItemStack[] snapshotContents(Player player) {
        ItemStack[] before = player.getInventory().getContents();
        for (int index = 0; index < before.length; index++) {
            if (before[index] != null) {
                before[index] = before[index].clone();
            }
        }
        return before;
    }

    private void cancel(Player player) {
        if (player == null) {
            return;
        }
        synchronized (taskLock) {
            HandCookedSkewerService active = service;
            if (active == null) {
                return;
            }
            hands.remove(player.getUniqueId());
            active.cancel(player.getUniqueId());
            progressDisplay.close(player.getUniqueId());
        }
    }

    private boolean hasHeat(Player player) {
        return heatProbe.test(player);
    }

    private boolean probeHeat(Player player) {
        FarmersDelightPlugin owner = plugin;
        if (owner == null) {
            return false;
        }
        Location center = player.getLocation();
        Predicate<Location> heat = location -> FarmersDelightApi.get().isHeatSource(location.getBlock());
        SkewerHeatProbe.RegionAccess access = new SchedulerRegionAccess(owner.scheduler(), player, heat);
        return SkewerHeatProbe.isNearHeatSource(access, center.getBlockX(), center.getBlockY(), center.getBlockZ());
    }

    private boolean isSkilletCooking(Player player) {
        FarmersDelightPlugin owner = plugin;
        return owner != null && owner.getSkilletManager() != null
                && owner.getSkilletManager().isHandheldCooking(player);
    }

    @Nullable
    private String resolveRawId(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : this.idResolver.apply(stack);
    }

    private static ItemStack held(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private static void setHeld(Player player, EquipmentSlot hand, @Nullable ItemStack stack) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(stack);
        } else {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    /**
     * The handheld progress bar, as a seam so the wiring can be driven without a server: a feature outside
     * this package only sees HandheldDisplays.
     */
    @ApiStatus.Internal
    interface SkewerProgressDisplay {

        SkewerProgressDisplay NONE = new SkewerProgressDisplay() {

            @Override
            public void start(UUID player, int slot, @Nullable String expectedId, int duration) {
            }

            @Override
            public void update(UUID player, int progress, int duration) {
            }

            @Override
            public void close(UUID player) {
            }
        };

        void start(UUID player, int slot, @Nullable String expectedId, int duration);

        void update(UUID player, int progress, int duration);

        void close(UUID player);
    }

    /** Production bar: one held-slot rewrite per session, five updates per second. */
    static final class HeldSkewerProgressDisplay implements SkewerProgressDisplay {

        private final FarmersDelightPlugin plugin;
        private final Function<UUID, Player> players;
        private final Opener opener;
        private final Function<ItemStack, String> idResolver;
        private final Map<UUID, BarSession> bars = new ConcurrentHashMap<>();

        HeldSkewerProgressDisplay(FarmersDelightPlugin plugin) {
            this(plugin,
                    id -> plugin == null ? null : plugin.getServer().getPlayer(id),
                    HandheldDisplays::open,
                    ItemUtils::resolveItemId);
        }

        HeldSkewerProgressDisplay(FarmersDelightPlugin plugin, Function<UUID, Player> players, Opener opener,
                                  Function<ItemStack, String> idResolver) {
            this.plugin = plugin;
            this.players = players;
            this.opener = opener;
            this.idResolver = idResolver;
        }

        @Override
        public void start(UUID playerId, int slot, @Nullable String expectedId, int duration) {
            if (playerId == null || expectedId == null) {
                return;
            }
            Player player = this.players.apply(playerId);
            if (player == null) {
                return;
            }
            // A second start for the same player must never leave the previous netty handler installed.
            close(playerId);
            HandheldDisplays.Handle handle = this.opener.open(player, slot,
                    player.getInventory().getItem(slot), duration);
            if (handle == null) {
                return;
            }
            World world = player.getWorld();
            this.bars.put(playerId, new BarSession(slot, expectedId, world == null ? null : world.getUID(), handle));
        }

        @Override
        public void update(UUID playerId, int progress, int duration) {
            BarSession session = this.bars.get(playerId);
            if (session == null) {
                return;
            }
            Player player = this.players.apply(playerId);
            if (player == null || !stillHoldsTheSkewer(player, session)) {
                // The bar belongs to one specific skewer in one specific slot: as soon as that slot holds
                // something else (dragged out, swapped hands) or the player changed world, it is a stale fake
                // bar. Self-heals here, once per tick, off the existing loop — one slot read, no rescan.
                close(playerId);
                return;
            }
            session.handle.update(progress);
        }

        private boolean stillHoldsTheSkewer(Player player, BarSession session) {
            World world = player.getWorld();
            if (world == null || session.worldId() == null || !session.worldId().equals(world.getUID())) {
                return false;
            }
            return session.expectedId().equals(this.idResolver.apply(player.getInventory().getItem(session.slot())));
        }

        @Override
        public void close(UUID playerId) {
            BarSession session = this.bars.remove(playerId);
            if (session != null) {
                session.handle().close();
            }
        }
    }

    /** What one held-slot bar was opened for: its slot, the raw id it belongs to and that session's world. */
    record BarSession(int slot, String expectedId, @Nullable UUID worldId, HandheldDisplays.Handle handle) {
    }

    /** Opens a held-slot handle; a seam so the overwrite and self-heal rules can be driven offline. */
    @ApiStatus.Internal
    interface Opener {

        @Nullable
        HandheldDisplays.Handle open(Player player, int slot, @Nullable ItemStack original, int duration);
    }

    /** Opens the bar for a fresh session: hotbar slot for the main hand, 40 for the off hand. */
    @ApiStatus.Internal
    void startProgressDisplay(UUID playerId, int slot, @Nullable String expectedId) {
        if (!this.progressDisplayEnabled) {
            return;
        }
        this.progressDisplay.start(playerId, slot, expectedId, this.cookingTicks);
    }

    /**
     * Called once per loop tick for every session: the bar follows the same cadence as the handheld skillet,
     * one update every four ticks, and never before the first tick of real progress. Called from inside the
     * task dispatched to the owning player, because the bar reads that player's world and held slot.
     */
    private void updateProgressDisplay(UUID playerId, HandCookedSkewerService active) {
        if (!this.progressDisplayEnabled) {
            return;
        }
        int remaining = active.remainingTicks(playerId);
        if (remaining < 0) {
            return;
        }
        int progress = this.cookingTicks - remaining;
        if (progress > 0 && progress % 4 == 0) {
            this.progressDisplay.update(playerId, progress, this.cookingTicks);
        }
    }

    /**
     * Plugin disable: closes every bar (which also drops its netty handler and resends the real slot) and
     * cancels the loop. Wire this from the plugin disable path next to the other manager shutdowns.
     */
    public void shutdown() {
        synchronized (taskLock) {
            for (UUID id : hands.keySet()) {
                progressDisplay.close(id);
            }
            hands.clear();
            cancelTaskLocked();
        }
        reportedBlocks.clear();
        reportedEntryPoints.clear();
    }

    private PluginTask armSchedulerTask(Runnable tick) {
        FarmersDelightPlugin owner = plugin;
        if (owner == null) {
            return PluginTask.NOOP;
        }
        return owner.scheduler().runRepeating(tick, 0, 1);
    }

    /**
     * Creates the one-per-tick loop if it is not running. Called from the session that needs it, never at
     * config time, and never twice: a task that is not cancelled is left as it is. Must be called with
     * taskLock held.
     */
    private void ensureTaskLocked() {
        if (!tickTask.isCancelled()) {
            return;
        }
        tickTask = taskArm.arm(this::tick);
    }

    /** Must be called with taskLock held. */
    private void cancelTaskLocked() {
        tickTask.cancel();
        tickTask = PluginTask.NOOP;
    }

    private static Map<String, String> readResults(FarmersDelightPlugin plugin) {
        Map<String, String> configured = new LinkedHashMap<>();
        if (!plugin.getConfig().isConfigurationSection("handheld-skewer.results")) {
            return configured;
        }
        for (String key : plugin.getConfig().getConfigurationSection("handheld-skewer.results").getKeys(false)) {
            configured.put(key, plugin.getConfig().getString("handheld-skewer.results." + key));
        }
        return configured;
    }
}
