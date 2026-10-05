package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The wiring for handheld skewer cooking: a player right-clicks a raw skewer next to a heat source, the skewer
 * cooks for {@code handheld-skewer.cooking-time-ticks} ticks on the player's own scheduler, and finishing
 * consumes exactly one skewer and hands over the cooked one.
 *
 * <p><b>Construction only stores the plugin</b> (the same contract as the migration hooks), so the
 * registration order can be asserted without a server. {@link #start()} reads the config and arms or disarms
 * the path; {@code handheld-skewer.enabled: false} — or a result table with nothing in it — leaves the path
 * inert: no session can start, no tick task is scheduled, and every handler returns immediately. The listener
 * itself stays in the registration list either way, because that list is a fixed order (see
 * {@code ListenerRegistrationOrderTest}); "off" means "armed with nothing", not "missing".
 *
 * <p><b>Lifecycle.</b> The one-per-tick loop exists only while a session does: it is armed when a session
 * starts and cancels itself once the last one ends, so a second skewer arms it again.
 *
 * <p><b>Mutual exclusion.</b> A player cooks one handheld item at a time: the skillet's session is checked
 * first ({@code SkilletManager#isHandheldCooking}) so the two paths cannot both claim the same right-click,
 * and {@link HandCookedSkewerService#tryStart(UUID)} refuses a second session for the same player.
 *
 * <p><b>Known limits (real-server checklist).</b> There is no client-side arm animation or progress bar (the
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
     * task is handed to that player's scheduler. Faking it lets a test drive the real {@link #tick()} loop,
     * including the cancellation that happens when the last session ends.
     */
    @ApiStatus.Internal
    interface LoopSeam {

        @Nullable
        Player player(UUID id);

        void dispatch(Player player, Runnable task);
    }

    private final FarmersDelightPlugin plugin;
    /** Which hand started the session, so finishing consumes the skewer that was used. */
    private final Map<UUID, EquipmentSlot> hands = new ConcurrentHashMap<>();
    private volatile HandCookedSkewerService service;
    private volatile SkewerResultTable results = new SkewerResultTable(null);
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

    public HandCookedSkewerHooks(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** Replaces the loop seam; null restores the production one. Used by the wiring tests only. */
    @ApiStatus.Internal
    void setLoopSeam(@Nullable LoopSeam seam) {
        this.loopSeam = seam == null ? productionLoop() : seam;
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
    }

    /** The service, or null while the path is inert (disabled, or no result is configured). */
    @Nullable
    public HandCookedSkewerService service() {
        return service;
    }

    /**
     * The arming decision on its own, so the wiring tests can drive it without a plugin or a server.
     *
     * <p>Order matters: a disabled path never arms, a raw id this table does not cook never starts, and a
     * player whose skillet is already cooking never reaches {@link HandCookedSkewerService#tryStart(UUID)} —
     * the skillet owns that right-click.
     */
    @ApiStatus.Internal
    boolean applyConfig(boolean enabled, boolean requireSneak, int cookingTicks,
                        @Nullable Map<String, String> configuredResults, @Nullable TaskArm arm,
                        @Nullable Consumer<UUID> onCooked) {
        this.requireSneak = requireSneak;
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
            // nothing to advance.
            hands.clear();
            service = new HandCookedSkewerService(true, () -> Math.max(1, cookingTicks), id -> {
                // The hand entry goes first, whoever handles the conversion: the loop decides whether it is
                // still needed from this map, so a session that ended must never leave an entry behind.
                EquipmentSlot hand;
                synchronized (taskLock) {
                    hand = hands.remove(id);
                }
                if (onCooked == null) {
                    convert(id, hand);
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
     * Right click with a raw skewer in hand. Runs at HIGH and without {@code ignoreCancelled} on purpose: the
     * campfire recipe path cancels this event to put the skewer into a campfire slot, and a MONITOR handler
     * with {@code ignoreCancelled = true} would never see it (that was the live defect: no session started,
     * and the skewer ended up on the fire). Cancelling here, before that handler, keeps the skewer in the hand.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        HandCookedSkewerService active = service;
        if (active == null) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        String rawId = resolveRawId(held(player, hand));
        boolean hasResult = rawId != null && results.cooks(rawId);
        boolean skilletCooking = isSkilletCooking(player);
        if (!mayStart(true, hasResult, skilletCooking, player.isSneaking(), requireSneak)) {
            return;
        }
        boolean blockClick = action == Action.RIGHT_CLICK_BLOCK;
        boolean clickedHeat = blockClick && isHeatSourceHere(event.getClickedBlock());
        // The cube probe is only worth its dispatches when the clicked block is not itself a heat source.
        boolean nearbyHeat = clickedHeat ? false : hasHeat(player);
        handleUse(player.getUniqueId(), hand, true, false, false, blockClick, clickedHeat, nearbyHeat,
                () -> event.setCancelled(true));
    }

    /**
     * The start decision, on plain inputs so the tests can drive a block click without a server.
     *
     * <p>Heat comes from either the clicked block or the 3x3x3 cube around the player (upstream parity keeps
     * the cube). Only a right click on an actual heat source block cancels the vanilla use — that is the case
     * where the campfire would swallow the skewer — so a plain block or a right click in air keeps its own
     * interaction, and holding something else changes nothing at all.
     */
    @ApiStatus.Internal
    boolean handleUse(UUID player, EquipmentSlot hand, boolean rawSkewer, boolean skilletCooking, boolean sneaking,
                      boolean blockClick, boolean clickedBlockHeat, boolean nearbyHeat, Runnable cancelVanilla) {
        if (!mayStart(service != null, rawSkewer, skilletCooking, sneaking, requireSneak)) {
            return false;
        }
        if (!clickedBlockHeat && !nearbyHeat) {
            // No heat means no session at all, so nothing is consumed and no tick runs.
            return false;
        }
        if (blockClick && clickedBlockHeat) {
            cancelVanilla.run();
        }
        return beginSession(player, hand);
    }

    /**
     * Whether the clicked block is a heat source, read only when this thread owns its region: a foreign block
     * read would throw under Folia's threading, and the click of a boundary block is left to vanilla rather
     * than guessed at.
     */
    private boolean isHeatSourceHere(@Nullable Block block) {
        FarmersDelightPlugin owner = plugin;
        if (owner == null || block == null) {
            return false;
        }
        if (!owner.scheduler().isOwnedByCurrentRegion(block.getLocation())) {
            return false;
        }
        return FarmersDelightApi.get().isHeatSource(block);
    }

    /**
     * Records a session and makes sure the tick loop is running. The loop cancels itself once the last session
     * ends (see {@link #tick()}), so every new session has to arm it again — a loop that was armed only when
     * the config was read would stop after the first skewer and never advance another one. Arming is
     * idempotent: a running loop is left alone, so a player cannot end up with two of them.
     */
    @ApiStatus.Internal
    boolean beginSession(UUID player, EquipmentSlot hand) {
        if (player == null) {
            return false;
        }
        synchronized (taskLock) {
            HandCookedSkewerService active = service;
            if (active == null || !active.tryStart(player)) {
                return false;
            }
            hands.put(player, hand);
            ensureTaskLocked();
            return true;
        }
    }

    /** Releasing the use key: zero progress. */
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

    /** The one-per-tick loop: every session is advanced on the scheduler of the player that owns it. */
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
            for (Map.Entry<UUID, EquipmentSlot> entry : hands.entrySet()) {
                Player player = seam.player(entry.getKey());
                if (player == null) {
                    hands.remove(entry.getKey());
                    active.cancel(entry.getKey());
                    continue;
                }
                UUID id = entry.getKey();
                // Dispatched while the lock is held on purpose: on Paper the task runs inline, so this calls
                // back into convert() on the same thread. Java monitors are reentrant, so the short
                // hands.remove there is safe, and the alternative — releasing the lock around the dispatch —
                // would let the loop be cancelled between the check above and the work it decided to do.
                seam.dispatch(player, () -> active.tick(id));
            }
        }
    }

    /**
     * Finishes one skewer. Runs on the player's region (dispatched by {@link #tick()}), consumes one raw
     * skewer in the hand that started the session and hands over the cooked one; a full inventory drops it.
     * No stack is cloned and no durability is written: the held stack is only shrunk by one. The hand entry
     * was already removed by the session wrapper in {@code start()}.
     */
    private void convert(UUID id, @Nullable EquipmentSlot hand) {
        FarmersDelightPlugin owner = plugin;
        Player player = owner == null || hand == null ? null : owner.getServer().getPlayer(id);
        if (player == null) {
            return;
        }
        ItemStack held = held(player, hand);
        String rawId = resolveRawId(held);
        String cookedId = rawId == null ? null : results.resolve(rawId);
        if (cookedId == null) {
            // The held item changed while cooking; nothing is produced and nothing is consumed.
            return;
        }
        ItemStack cooked = ItemUtils.createItem(cookedId);
        if (cooked == null) {
            return;
        }
        int left = held.getAmount() - 1;
        if (left > 0) {
            held.setAmount(left);
            setHeld(player, hand, held);
        } else {
            setHeld(player, hand, null);
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(cooked);
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItem(player.getLocation(), rest);
        }
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
        }
    }

    private boolean hasHeat(Player player) {
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
    private static String resolveRawId(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : ItemUtils.resolveItemId(stack);
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
     * {@link #taskLock} held.
     */
    private void ensureTaskLocked() {
        if (!tickTask.isCancelled()) {
            return;
        }
        tickTask = taskArm.arm(this::tick);
    }

    /** Must be called with {@link #taskLock} held. */
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
