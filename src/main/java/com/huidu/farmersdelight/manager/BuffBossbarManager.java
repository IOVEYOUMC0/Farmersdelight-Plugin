package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BuffBossbarManager implements Listener {

    public enum LayoutMode {
        STACKED, ROTATING;

        static LayoutMode parse(String s) {
            if (s == null) return STACKED;
            String norm = s.trim().toLowerCase(Locale.ROOT);
            return switch (norm) {
                case "rotating", "rotate", "cycle" -> ROTATING;
                default -> STACKED;
            };
        }
    }

    public enum Channel {
        BOSSBAR, ACTIONBAR, TAB_FOOTER;

        static Channel parse(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "bossbar", "boss_bar", "boss", "bar" -> BOSSBAR;
                case "actionbar", "action_bar", "action" -> ACTIONBAR;
                case "tab_footer", "tabfooter", "tab_list", "tablist", "footer", "tab" -> TAB_FOOTER;
                default -> null;
            };
        }
    }

    // Quantization step count for the bar's progress value. Feeders compute progress as
    // remaining/initial, so the raw float changes on every feed tick and the snapshot compare below
    // never absorbs it — each change becomes one boss-event packet, most of them moving the bar by
    // less than a pixel. The vanilla boss bar texture is ~182px wide, so rounding to 1/128 is below
    // the visible resolution while collapsing the majority of those packets into no-ops. Applied here
    // rather than in the feeds: this is the single choke point every addon's bar passes through.
    private static final int PROGRESS_STEPS = 128;

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, PlayerBars> players = new ConcurrentHashMap<>();
    // Display switch (buff.display.enabled): silences every channel while the effects themselves keep
    // running and ticking.
    private volatile boolean enabled = true;
    // The volatile buff.enabled snapshot controls display updates across reload, tick and addon threads.
    private volatile boolean systemEnabled = true;
    private volatile LayoutMode layoutMode = LayoutMode.STACKED;
    private volatile long rotationIntervalTicks = 80L;
    // Action bar messages fade after ~3s, so the rotation tick re-sends them this often when the
    // ACTIONBAR channel is active. Boss bar / tab footer persist and need no refresh.
    private volatile long actionbarRefreshTicks = 30L;
    // Enabled render channels. EnumSet, replaced wholesale on reload (never mutated in place) so readers
    // see a consistent snapshot. Defaults to BOSSBAR to preserve pre-channels behaviour.
    private volatile Set<Channel> channels = EnumSet.of(Channel.BOSSBAR);
    // Joins the buff titles on the action bar line. Configurable so admins can widen/narrow the gap.
    private volatile Component actionbarSeparator = Component.text("   ");
    private volatile PluginTask tickTask;
    private volatile long currentTick;
    private volatile boolean started;

    // Active manager for the BuffBossbar API facade: a single volatile read instead of the
    // getInstance() -> getter chain, and (unlike the plugin's non-volatile field) a safely published
    // reference for addon threads. Set in start(), identity-cleared in stop().
    private static volatile BuffBossbarManager active;

    public static BuffBossbarManager active() {
        return active;
    }

    public BuffBossbarManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyConfig(ConfigurationSection section, boolean systemEnabled) {
        boolean wasRendering = renderingEnabled();
        Set<Channel> oldChannels = this.channels;
        this.systemEnabled = systemEnabled;
        if (section == null) {
            this.enabled = true;
            this.layoutMode = LayoutMode.STACKED;
            this.rotationIntervalTicks = 80L;
            this.actionbarRefreshTicks = 30L;
            this.channels = EnumSet.of(Channel.BOSSBAR);
            this.actionbarSeparator = Component.text("   ");
        } else {
            this.enabled = ConfigSectionReader.optionalBoolean(section, "enabled", true);
            this.layoutMode = LayoutMode.parse(ConfigSectionReader.optionalString(section, "layout-mode", "stacked"));
            this.rotationIntervalTicks = Math.max(20L,
                    ConfigSectionReader.optionalLong(section, "rotation-interval-ticks", 80L));
            this.actionbarRefreshTicks = Math.max(1L,
                    ConfigSectionReader.optionalLong(section, "actionbar-refresh-ticks", 30L));
            this.channels = parseChannels(ConfigSectionReader.optionalStringList(section, "channels"));
            this.actionbarSeparator = Component.text(ConfigSectionReader.optionalString(section, "actionbar-separator", "   "));
        }
        if (wasRendering && !renderingEnabled()) {
            hideAndClearAll();
            ensureTickTask();
            return;
        }
        Set<Channel> newChannels = this.channels;
        for (Map.Entry<UUID, PlayerBars> entry : players.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) {
                PlayerBars state = entry.getValue();
                dispatchToPlayer(p, () -> {
                    if (!p.isOnline()) return;
                    // Every other render caller holds the per-player state monitor; this reload path must too,
                    // else a concurrent per-tick update() (state.bars.put) races the LinkedHashMap iteration -> CME.
                    synchronized (state) {
                        clearDroppedChannels(p, state, oldChannels, newChannels);
                        render(p, state);
                    }
                });
            }
        }
        ensureTickTask();
    }

    private static Set<Channel> parseChannels(List<String> raw) {
        EnumSet<Channel> parsed = EnumSet.noneOf(Channel.class);
        if (raw != null) {
            for (String s : raw) {
                Channel c = Channel.parse(s);
                if (c != null) parsed.add(c);
            }
        }
        // Empty / missing / all-unknown -> boss bar, preserving the pre-channels default.
        return parsed.isEmpty() ? EnumSet.of(Channel.BOSSBAR) : parsed;
    }

    private void clearDroppedChannels(Player player, PlayerBars state,
                                      Set<Channel> oldCh, Set<Channel> newCh) {
        if (oldCh.contains(Channel.BOSSBAR) && !newCh.contains(Channel.BOSSBAR)) {
            hideBossbars(player, state);
        }
        if (oldCh.contains(Channel.ACTIONBAR) && !newCh.contains(Channel.ACTIONBAR)) {
            player.sendActionBar(Component.empty());
        }
        if (oldCh.contains(Channel.TAB_FOOTER) && !newCh.contains(Channel.TAB_FOOTER)) {
            player.sendPlayerListFooter(Component.empty());
        }
    }

    public void start() {
        started = true;
        active = this;
        ensureTickTask();
    }

    public void stop() {
        started = false;
        if (active == this) {
            active = null;
        }
        ensureTickTask();
        hideAndClearAll();
    }

    private synchronized void ensureTickTask() {
        // The tick drives boss bar rotation AND the action bar's periodic re-send. Stacked boss bar and
        // the tab footer both persist without a tick, so neither keeps the task alive on its own.
        // No player has any tracked bar: nothing to rotate or re-send, so the task must not run either —
        // update() re-arms it the moment the first bar appears.
        boolean want = started && renderingEnabled()
                && !players.isEmpty()
                && ((channels.contains(Channel.BOSSBAR) && layoutMode == LayoutMode.ROTATING)
                    || channels.contains(Channel.ACTIONBAR));
        if (want && tickTask == null) {
            tickTask = plugin.scheduler().runRepeating(this::tick, 1L, 1L);
        } else if (!want && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private boolean renderingEnabled() {
        return systemEnabled && enabled;
    }

    public boolean isEnabled() {
        return renderingEnabled();
    }

    public void update(Plugin owner, Player player, NamespacedKey key,
                       Component title, float progress,
                       BossBar.Color color, BossBar.Overlay overlay) {
        if (!renderingEnabled() || player == null || key == null) return;
        dispatchToPlayer(player, () -> updateOnPlayer(owner, player, key, title, progress, color, overlay));
    }

    private void updateOnPlayer(Plugin owner, Player player, NamespacedKey key,
                                Component title, float progress,
                                BossBar.Color color, BossBar.Overlay overlay) {
        if (!renderingEnabled() || !player.isOnline()) return;
        float clamped = quantize(clamp(progress));
        BossBar.Color c = color == null ? BossBar.Color.WHITE : color;
        BossBar.Overlay o = overlay == null ? BossBar.Overlay.PROGRESS : overlay;
        Component t = title == null ? Component.empty() : title;

        UUID id = player.getUniqueId();
        boolean wasEmpty = players.isEmpty();
        // Synchronize on the per-player state so two addons updating the same player don't race on
        // bars.put / showBossBar ordering. Cross-player updates remain parallel.
        PlayerBars state = players.computeIfAbsent(id, k -> new PlayerBars());
        synchronized (state) {
            BossBar bar = state.bars.get(key);
            if (bar == null) {
                bar = BossBar.bossBar(t, clamped, c, o);
                state.bars.put(key, bar);
                state.snapshots.put(key, new BarSnapshot(t, clamped, c, o));
                // A new buff appeared: render every enabled channel. For an EXISTING bar the boss bar
                // auto-propagates its Adventure mutation without a re-show packet (see the else branch),
                // but the action bar / tab footer are push-only and must be rebuilt on every change.
                render(player, state);
            } else {
                BarSnapshot last = state.snapshots.get(key);
                if (last != null && last.matches(t, clamped, c, o)) {
                    return;
                }
                bar.name(t);
                bar.progress(clamped);
                bar.color(c);
                bar.overlay(o);
                state.snapshots.put(key, new BarSnapshot(t, clamped, c, o));
                // Boss bar already mutated in place above; refresh the push-only channels with the new text.
                renderAuxiliary(player, state);
            }
        }
        // First bar of the session: the tick task may have self-cancelled while nobody had a bar;
        // re-arm it now that there is something to rotate / re-send. Idempotent, so a racing second
        // update() just re-checks the same state.
        if (wasEmpty) {
            ensureTickTask();
        }
    }

    public void hide(Plugin owner, Player player, NamespacedKey key) {
        if (player == null || key == null) return;
        PlayerBars state = players.get(player.getUniqueId());
        if (state == null) return;
        dispatchToPlayer(player, () -> hideOnPlayer(player, key, state));
    }

    private void hideOnPlayer(Player player, NamespacedKey key, PlayerBars state) {
        BossBar removed;
        synchronized (state) {
            removed = state.bars.remove(key);
            if (removed == null) {
                // Hot path for the BAC bossbar feed: every tick it calls hide() for inactive buffs,
                // most of which were never shown for this player. Early-return before resyncing
                // anything — nothing changed, no point re-issuing packets.
                return;
            }
            state.snapshots.remove(key);
            if (key.equals(state.currentVisible)) {
                state.currentVisible = null;
            }
        }
        if (player.isOnline()) {
            player.hideBossBar(removed);
            // Re-render: the boss bar re-shows the survivors, and the push-only channels rebuild from
            // the remaining buffs (or clear themselves when this was the last active one).
            synchronized (state) {
                render(player, state);
            }
        }
        // NOTE: do NOT players.remove(id, state) when emptied — earlier version did, but races with a
        // concurrent update() on the same player. After this hide() exits its sync block, a concurrent
        // update() can computeIfAbsent the SAME state object and add a bar; the identity-based remove
        // then drops the (now non-empty) state, orphaning the new bar (shown to client but lost from
        // manager → never hidden). The empty PlayerBars is ~64 bytes and cleaned on PlayerQuit, so the
        // memory cost of leaving it is trivial; the rotation tick early-skips state.bars.size() <= 1.
    }

    public void hideAll(Plugin owner, Player player) {
        if (player == null) return;
        PlayerBars state = players.remove(player.getUniqueId());
        if (state == null) return;
        dispatchToPlayer(player, () -> hideAllOnPlayer(player, state));
    }

    private void hideAllOnPlayer(Player player, PlayerBars state) {
        if (!player.isOnline()) return;
        synchronized (state) {
            for (BossBar bar : state.bars.values()) {
                player.hideBossBar(bar);
            }
        }
        clearAuxiliary(player);
    }

    private void clearAuxiliary(Player player) {
        Set<Channel> ch = channels;
        if (ch.contains(Channel.ACTIONBAR)) player.sendActionBar(Component.empty());
        if (ch.contains(Channel.TAB_FOOTER)) player.sendPlayerListFooter(Component.empty());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlayerBars state = players.remove(event.getPlayer().getUniqueId());
        if (state == null) return;
        Player player = event.getPlayer();
        synchronized (state) {
            for (BossBar bar : state.bars.values()) {
                player.hideBossBar(bar);
            }
        }
    }

    private void hideAndClearAll() {
        for (Iterator<Map.Entry<UUID, PlayerBars>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerBars> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayerBars state = entry.getValue();
            if (player != null) {
                dispatchToPlayer(player, () -> hideAllOnPlayer(player, state));
            }
            it.remove();
        }
    }

    private void render(Player player, PlayerBars state) {
        if (!renderingEnabled()) return;
        Set<Channel> ch = channels;
        if (ch.contains(Channel.BOSSBAR)) {
            syncBossbars(player, state);
        }
        renderAuxiliary(player, state, ch);
    }

    private void renderAuxiliary(Player player, PlayerBars state) {
        if (!renderingEnabled()) return;
        renderAuxiliary(player, state, channels);
    }

    private void renderAuxiliary(Player player, PlayerBars state, Set<Channel> ch) {
        if (ch.contains(Channel.ACTIONBAR)) {
            player.sendActionBar(joinTitles(state, actionbarSeparator));
        }
        if (ch.contains(Channel.TAB_FOOTER)) {
            player.sendPlayerListFooter(joinTitles(state, Component.newline()));
        }
    }

    private static Component joinTitles(PlayerBars state, Component separator) {
        Component out = Component.empty();
        boolean first = true;
        for (BarSnapshot snap : state.snapshots.values()) {
            if (!first) out = out.append(separator);
            out = out.append(snap.title());
            first = false;
        }
        return out;
    }

    private void hideBossbars(Player player, PlayerBars state) {
        for (BossBar bar : state.bars.values()) {
            player.hideBossBar(bar);
        }
        state.currentVisible = null;
    }

    private void syncBossbars(Player player, PlayerBars state) {
        if (state.bars.isEmpty()) {
            state.currentVisible = null;
            return;
        }
        if (layoutMode == LayoutMode.STACKED) {
            for (BossBar bar : state.bars.values()) {
                player.showBossBar(bar);
            }
            state.currentVisible = null;
            return;
        }
        // ROTATING: show exactly one — the current pick (or the first if none yet / invalidated).
        if (state.currentVisible == null || !state.bars.containsKey(state.currentVisible)) {
            state.currentVisible = state.bars.keySet().iterator().next();
            state.lastRotationTick = currentTick;
        }
        for (Map.Entry<NamespacedKey, BossBar> entry : state.bars.entrySet()) {
            if (entry.getKey().equals(state.currentVisible)) {
                player.showBossBar(entry.getValue());
            } else {
                player.hideBossBar(entry.getValue());
            }
        }
    }

    private void tick() {
        currentTick++;
        if (!renderingEnabled()) {
            return;
        }
        if (players.isEmpty()) {
            // Self-cancel when the last tracked bar is gone; the locked recheck pairs with update()'s
            // computeIfAbsent + ensureTickTask so a fresh bar can never be stranded without a running pass.
            ensureTickTask();
            return;
        }
        Set<Channel> ch = channels;
        boolean rotate = ch.contains(Channel.BOSSBAR) && layoutMode == LayoutMode.ROTATING;
        boolean refreshActionBar = ch.contains(Channel.ACTIONBAR)
                && currentTick % actionbarRefreshTicks == 0;
        if (!rotate && !refreshActionBar) {
            return;
        }
        for (Map.Entry<UUID, PlayerBars> entry : players.entrySet()) {
            PlayerBars state = entry.getValue();
            boolean canRotate;
            boolean canRefresh;
            synchronized (state) {
                canRotate = rotate && state.bars.size() > 1
                        && currentTick - state.lastRotationTick >= rotationIntervalTicks;
                canRefresh = refreshActionBar && !state.snapshots.isEmpty();
            }
            if (!canRotate && !canRefresh) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            dispatchToPlayer(player, () -> {
                if (!player.isOnline()) return;
                synchronized (state) {
                    if (rotate && state.bars.size() > 1
                            && currentTick - state.lastRotationTick >= rotationIntervalTicks) {
                        // Advance currentVisible to the next key in insertion order; wrap.
                        NamespacedKey next = findNext(state);
                        if (next != null) {
                            BossBar oldBar = state.currentVisible == null ? null : state.bars.get(state.currentVisible);
                            if (oldBar != null) player.hideBossBar(oldBar);
                            state.currentVisible = next;
                            state.lastRotationTick = currentTick;
                            player.showBossBar(state.bars.get(next));
                        }
                    }
                    // Re-send the action bar so it doesn't fade; it always shows all active buffs.
                    if (refreshActionBar && !state.snapshots.isEmpty()) {
                        player.sendActionBar(joinTitles(state, actionbarSeparator));
                    }
                }
            });
        }
    }

    private void dispatchToPlayer(Player player, Runnable action) {
        if (plugin.scheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(player)) {
            plugin.scheduler().runForEntity(player, action);
        } else {
            action.run();
        }
    }

    private static NamespacedKey findNext(PlayerBars state) {
        boolean returnNext = state.currentVisible == null;
        NamespacedKey first = null;
        for (NamespacedKey key : state.bars.keySet()) {
            if (first == null) first = key;
            if (returnNext) return key;
            if (key.equals(state.currentVisible)) returnNext = true;
        }
        // Wrapped past the end: return the first (i.e. wrap to start).
        return first;
    }

    private static float clamp(float progress) {
        if (Float.isNaN(progress) || progress < 0F) return 0F;
        if (progress > 1F) return 1F;
        return progress;
    }

    private static float quantize(float clamped) {
        return Math.round(clamped * PROGRESS_STEPS) / (float) PROGRESS_STEPS;
    }

    private static final class PlayerBars {
        // LinkedHashMap to preserve insertion order — rotation walks bars in the order addons created them,
        // which matches what a player would expect when reading the bossbar stack.
        final Map<NamespacedKey, BossBar> bars = new LinkedHashMap<>();
        // Snapshot of the last (title, progress, color, overlay) tuple actually pushed via the
        // BossBar setters. Lets update() return without touching Adventure when the incoming state
        // is identical — Adventure's setters compare on equals() too, but a Component.equals() walk
        // is not free and BAC's per-tick feeders re-build the same title every tick.
        final Map<NamespacedKey, BarSnapshot> snapshots = new LinkedHashMap<>();
        NamespacedKey currentVisible;
        long lastRotationTick;
    }

    private record BarSnapshot(Component title, float progress, BossBar.Color color, BossBar.Overlay overlay) {
        boolean matches(Component t, float p, BossBar.Color c, BossBar.Overlay o) {
            return Float.compare(progress, p) == 0
                    && color == c
                    && overlay == o
                    && Objects.equals(title, t);
        }
    }
}
