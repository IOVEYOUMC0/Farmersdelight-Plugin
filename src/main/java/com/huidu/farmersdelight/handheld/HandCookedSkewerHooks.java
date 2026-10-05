package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Location;
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

    private final FarmersDelightPlugin plugin;
    /** Which hand started the session, so finishing consumes the skewer that was used. */
    private final Map<UUID, EquipmentSlot> hands = new ConcurrentHashMap<>();
    private volatile HandCookedSkewerService service;
    private volatile SkewerResultTable results = new SkewerResultTable(null);
    private volatile boolean requireSneak;
    private volatile PluginTask tickTask = PluginTask.NOOP;
    private volatile TaskArm taskArm = this::armSchedulerTask;

    public HandCookedSkewerHooks(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
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
        HandCookedSkewerService previous = service;
        if (!enabled || table.isEmpty()) {
            if (previous != null) {
                previous.setEnabled(false);
            }
            service = null;
            hands.clear();
            cancelTask();
            return false;
        }
        if (previous != null) {
            // A reload replaces the service; the old sessions must not survive it.
            previous.setEnabled(false);
        }
        service = new HandCookedSkewerService(true, () -> Math.max(1, cookingTicks),
                onCooked == null ? this::convert : onCooked);
        // No tick loop is started here: it is armed by the session that needs it (see ensureTask), so a
        // reload with no session running does not leave an idle loop behind.
        cancelTask();
        return true;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
        if (!hasHeat(player)) {
            // The heat check happens once, when the use starts (upstream parity). Not near a heat source means
            // no session at all, so nothing is consumed and no tick runs.
            return;
        }
        beginSession(player.getUniqueId(), hand);
    }

    /**
     * Records a session and makes sure the tick loop is running. The loop cancels itself once the last session
     * ends (see {@link #tick()}), so every new session has to arm it again — a loop that was armed only when
     * the config was read would stop after the first skewer and never advance another one. Arming is
     * idempotent: a running loop is left alone, so a player cannot end up with two of them.
     */
    @ApiStatus.Internal
    boolean beginSession(UUID player, EquipmentSlot hand) {
        HandCookedSkewerService active = service;
        if (active == null || player == null || !active.tryStart(player)) {
            return false;
        }
        hands.put(player, hand);
        ensureTask();
        return true;
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
        HandCookedSkewerService active = service;
        FarmersDelightPlugin owner = plugin;
        if (active == null || owner == null) {
            cancelTask();
            return;
        }
        if (hands.isEmpty()) {
            cancelTask();
            return;
        }
        for (Map.Entry<UUID, EquipmentSlot> entry : hands.entrySet()) {
            Player player = owner.getServer().getPlayer(entry.getKey());
            if (player == null) {
                hands.remove(entry.getKey());
                active.cancel(entry.getKey());
                continue;
            }
            UUID id = entry.getKey();
            owner.scheduler().runForEntity(player, () -> active.tick(id));
        }
    }

    /**
     * Finishes one skewer. Runs on the player's region (dispatched by {@link #tick()}), consumes one raw
     * skewer in the hand that started the session and hands over the cooked one; a full inventory drops it.
     * No stack is cloned and no durability is written: the held stack is only shrunk by one.
     */
    private void convert(UUID id) {
        FarmersDelightPlugin owner = plugin;
        EquipmentSlot hand = hands.remove(id);
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
        HandCookedSkewerService active = service;
        if (active == null || player == null) {
            return;
        }
        hands.remove(player.getUniqueId());
        active.cancel(player.getUniqueId());
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
     * config time, and never twice: a task that is not cancelled is left as it is.
     */
    private void ensureTask() {
        if (!tickTask.isCancelled()) {
            return;
        }
        tickTask = taskArm.arm(this::tick);
    }

    private void cancelTask() {
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
