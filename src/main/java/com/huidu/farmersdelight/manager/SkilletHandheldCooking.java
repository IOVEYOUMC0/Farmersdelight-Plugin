package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.api.util.ItemDelivery;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineModelMappings;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacketProxy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Cooking a skillet held in a player's hand: the per-player session, its tick task and the display
 * swap, split out of SkilletManager.
 *
 *
 * This block owns every piece of hand-held state, which is why it could move as one unit: the
 * placed-skillet side of SkilletManager never reads a session, and only three lifecycle hooks
 * (config reload, cleanup, tick-task suspension) reach in — those call the public methods below.
 *
 *
 * The display-model plumbing stays behind HandheldCookingModels and the recipe lookups
 * behind the shared CampfireRecipeCache; this class only sequences them.
 */
final class SkilletHandheldCooking {

    private static final long HANDHELD_INPUT_TIMEOUT_MILLIS = 400L;
    // Read-only migration keys for versions that stored unfinished food and fake damage on the item.
    private static final NamespacedKey HANDHELD_INGREDIENT_KEY =
            NamespacedKey.fromString("farmersdelight:skillet_ingredient");
    private static final NamespacedKey HANDHELD_DURATION_KEY =
            NamespacedKey.fromString("farmersdelight:skillet_duration");
    private static final NamespacedKey HANDHELD_PROGRESS_KEY =
            NamespacedKey.fromString("farmersdelight:skillet_progress");
    private static final NamespacedKey HANDHELD_ORIGINAL_DAMAGE_KEY =
            NamespacedKey.fromString("farmersdelight:skillet_original_damage");
    private static final NamespacedKey HANDHELD_ORIGINAL_MODEL_KEY =
            NamespacedKey.fromString("farmersdelight:skillet_original_model");

    private final FarmersDelightPlugin plugin;
    private final CampfireRecipeCache campfireRecipes;
    private final HandheldCookingModels handheldModels;
    private final Map<UUID, HandheldSession> handheldSessions = new ConcurrentHashMap<>();
    // Awarding the use_skillet advancement stays with SkilletManager: the placed path awards it too, so the
    // advancement bookkeeping has one owner and this class just calls back into it.
    private final Consumer<Player> advancementAward;
    private volatile PluginTask handheldTickTask;
    private volatile boolean handheldCookingEnabled = true;
    private volatile boolean handheldProgressDisplayEnabled = true;
    // One handheld cook per player, both ways: the skewer path refuses to start while this skillet cooks, and
    // this path refuses the other way round. Without the reverse check both sessions could run on the same
    // skewer stack, because the skillet's ingredient can be the very skewer the other session is cooking.
    // Wired by the listener registry at startup; the default answers "no" so a server that never wires it, or
    // a test with no wiring at all, behaves exactly as before.
    private volatile Predicate<Player> skewerCooking = player -> false;
    // Cooking-time settings, pushed from SkilletManager#reloadConfig. The hand-held path scales cook time
    // exactly like the placed path does, so it holds its own copy instead of reaching back into the manager.
    private volatile int defaultCookingTime;
    private volatile int minCookingTime;
    private volatile double cookTimeMultiplier;
    private volatile double fireAspectBonus;

    SkilletHandheldCooking(FarmersDelightPlugin plugin, CampfireRecipeCache campfireRecipes,
                           HandheldCookingModels handheldModels, Consumer<Player> advancementAward,
                           int defaultCookingTime, int minCookingTime, double cookTimeMultiplier,
                           double fireAspectBonus) {
        this.plugin = plugin;
        this.campfireRecipes = campfireRecipes;
        this.handheldModels = handheldModels;
        this.advancementAward = advancementAward;
        this.defaultCookingTime = defaultCookingTime;
        this.minCookingTime = minCookingTime;
        this.cookTimeMultiplier = cookTimeMultiplier;
        this.fireAspectBonus = fireAspectBonus;
    }

    /**
     * Sets the "is this player cooking a skewer" check. Wired by the listener registry, which owns both
     * handheld paths; null restores the never-cooking answer a server without the skewer path would give.
     */
    void setSkewerCookingCheck(Predicate<Player> check) {
        this.skewerCooking = check == null ? player -> false : check;
    }

    /**
     * Applies the settings this block needs from config. Called from SkilletManager#reloadConfig, which owns
     * reading them.
     */
    void applyConfig(boolean cookingEnabled, boolean progressDisplayEnabled,
                     int defaultCookingTime, int minCookingTime, double cookTimeMultiplier,
                     double fireAspectBonus) {
        this.handheldCookingEnabled = cookingEnabled;
        this.handheldProgressDisplayEnabled = progressDisplayEnabled;
        this.defaultCookingTime = defaultCookingTime;
        this.minCookingTime = minCookingTime;
        this.cookTimeMultiplier = cookTimeMultiplier;
        this.fireAspectBonus = fireAspectBonus;
    }

    /**
     * Scales a campfire recipe's cook time by the configured reduction, minus a bonus per Fire Aspect level.
     * Mirrors the placed-skillet calculation so a held skillet and a placed one cook at the same speed.
     */
    private int getAdjustedCookingTime(int baseTime, int fireAspectLevel) {
        int cookingTime = baseTime > 0 ? baseTime : defaultCookingTime;
        int cookingSeconds = cookingTime / 20;
        double cookingTimeReduction = cookTimeMultiplier;

        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * fireAspectBonus;
        }
        cookingTimeReduction = Math.max(0.0D, cookingTimeReduction);

        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        return Math.min(cookingTime, Math.max(minCookingTime, result));
    }

    boolean cookingEnabled() {
        return handheldCookingEnabled;
    }

    /** Stops every session and the tick task. Called from SkilletManager#cleanup. */
    void shutdown() {
        handheldModels.cleanup();
        if (!plugin.scheduler().isFolia()) {
            for (UUID id : handheldSessions.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) stopHandheldUse(player, null);
            }
        }
        for (HandheldSession session : handheldSessions.values()) session.closeDisplay();
        handheldSessions.clear();
        synchronized (this) {
            if (handheldTickTask != null) {
                handheldTickTask.cancel();
                handheldTickTask = null;
            }
        }
    }

    /** Cancels the tick task only; used when the whole manager suspends its tasks. */
    void suspendTickTask() {
        synchronized (this) {
            if (handheldTickTask != null) {
                handheldTickTask.cancel();
                handheldTickTask = null;
            }
        }
    }

    private CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        return campfireRecipes.find(item);
    }

    /**
     * The first gate of a handheld skillet click, on plain answers so the contract is testable without a
     * server: the path has to be armed, the hand being used must not be busy with a native use of the other
     * hand, and the player must not be cooking a skewer. One handheld cook per player, in both directions:
     * the skewer path refuses while this path cooks, and this path refuses while the skewer path does.
     */
    static boolean mayStartHandheld(boolean enabled, boolean otherHandUsing, boolean skewerCooking) {
        return enabled && !otherHandUsing && !skewerCooking;
    }

    /** Ingredients remain in the other hand until the cooking result is committed. */
    public boolean handleHandheldInteract(Player player, EquipmentSlot skilletHand,
                                          NamespacedKey cookingModel, NamespacedKey overlayModel,
                                          Map<String, NamespacedKey> ingredientModels) {
        if (player == null || skilletHand == null) return false;
        boolean otherHandUsing = isUsingOtherHand(player, skilletHand);
        boolean skewerCookingNow = skewerCooking.test(player);
        if (!mayStartHandheld(handheldCookingEnabled, otherHandUsing, skewerCookingNow)) {
            // Both stop cases keep their old meaning: a native use of the other hand cancels the session, and
            // a running skewer session takes the click away from this path entirely (its ingredient would be
            // the very stack that session is cooking).
            if (handheldCookingEnabled && (otherHandUsing || skewerCookingNow)) {
                stopHandheldUse(player, null);
            }
            return false;
        }
        ItemStack skillet = skilletHand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (skillet == null || skillet.getType().isAir() || skillet.getAmount() != 1) return false;
        HandheldSession current = handheldSessions.get(player.getUniqueId());
        if (current != null) {
            if (current.hand == skilletHand && current.matches(player.getInventory())) {
                current.lastInput = System.currentTimeMillis();
                return true;
            }
            stopHandheldUse(player, null);
        }
        if (hasHandheldIngredient(skillet)) stopHandheldUse(player, skillet);
        if (!hasNearbyHeatSource(player)) return false;
        EquipmentSlot otherHand = skilletHand == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack ingredient = otherHand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        CookingRecipe<?> recipe = findCampfireRecipe(ingredient);
        if (recipe == null) return false;
        if (player.isUnderWater()) return false;

        ItemStack unit = ingredient.clone();
        unit.setAmount(1);
        String ingredientId = ItemUtils.getCustomItemId(ingredient);
        if (ingredientId == null) ingredientId = ItemUtils.getVanillaMaterialItemId(ingredient);
        NamespacedKey model = ingredientModels.get(ingredientId);
        if (model == null) model = handheldModels.resolve(player, cookingModel, overlayModel, unit);
        int hotbarSlot = player.getInventory().getHeldItemSlot();
        HandheldSession session = new HandheldSession(skilletHand,
                skilletHand == EquipmentSlot.HAND ? hotbarSlot : 40,
                skilletHand == EquipmentSlot.HAND ? 40 : hotbarSlot,
                skillet.clone(), unit, recipe,
                getAdjustedCookingTime(recipe.getCookingTime(), skillet.getEnchantmentLevel(Enchantment.FIRE_ASPECT)),
                model);
        handheldSessions.put(player.getUniqueId(), session);
        if (session.model != null || handheldProgressDisplayEnabled || VersionHelper.isOrAbove1_21_11) {
            var channel = BukkitNetworkManager.instance().getChannel(player);
            if (channel != null && channel.isOpen()) {
                session.display = new HandheldCookingDisplay(channel, session.slot,
                        BukkitAdaptor.adapt(session.skillet.clone()).minecraftItem());
                updateHandheldDisplay(player, session);
            }
        }
        ensureHandheldTask();
        player.getWorld().playSound(player.getLocation(), Constants.SOUND_SKILLET_ADD_FOOD, 0.7f, 1.0f);
        return true;
    }

    /** Removing the session first makes overlapping lifecycle events idempotent. */
    public void stopHandheldUse(Player player, ItemStack releasedItem) {
        if (player == null) return;
        HandheldSession session = handheldSessions.remove(player.getUniqueId());
        if (session != null) session.closeDisplay();
        // Migration callers pass a live inventory stack, never an event snapshot.
        if (hasHandheldIngredient(releasedItem)) {
            ItemStack ingredient = getHandheldIngredient(releasedItem);
            clearHandheldState(releasedItem);
            if (ingredient != null) returnHandheldIngredient(player, ingredient);
        }
        if (session != null && player.isOnline()) sendHandheldSlot(player, session.slot,
                player.getInventory().getItem(session.slot));
        stopHandheldTaskIfIdle();
    }

    public boolean isHandheldCooking(Player player) {
        return player != null && handheldSessions.containsKey(player.getUniqueId());
    }

    private void ensureHandheldTask() {
        synchronized (this) {
            if (handheldTickTask == null) {
                handheldTickTask = plugin.scheduler().runRepeating(this::tickHandheld, 1L, 1L);
            }
        }
    }

    private void stopHandheldTaskIfIdle() {
        if (!handheldSessions.isEmpty()) return;
        synchronized (this) {
            if (handheldSessions.isEmpty() && handheldTickTask != null) {
                handheldTickTask.cancel();
                handheldTickTask = null;
            }
        }
    }

    private void tickHandheld() {
        if (handheldSessions.isEmpty()) {
            stopHandheldTaskIfIdle();
            return;
        }
        // ConcurrentHashMap's iterator is weakly consistent, so active players can finish or
        // disconnect without allocating a snapshot list on every tick.
        boolean folia = plugin.scheduler().isFolia();
        for (var entry : handheldSessions.entrySet()) {
            UUID id = entry.getKey();
            HandheldSession session = entry.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                handheldSessions.remove(id, session);
                session.closeDisplay();
                continue;
            }
            if (folia) {
                // Folia must enter the player's entity region; Paper can stay on the repeating task's
                // main thread and avoids allocating one Runnable per active player per tick.
                if (session.scheduled.compareAndSet(false, true)) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            try {
                                if (handheldSessions.get(id) == session) tickHandheldPlayer(player, session);
                            } finally {
                                session.scheduled.set(false);
                            }
                        }, () -> {
                            handheldSessions.remove(id, session);
                            session.closeDisplay();
                        });
                    } catch (RuntimeException e) {
                        session.scheduled.set(false);
                        throw e;
                    }
                }
            } else {
                tickHandheldPlayer(player, session);
            }
        }
        stopHandheldTaskIfIdle();
    }

    private void tickHandheldPlayer(Player player, HandheldSession session) {
        PerformanceMonitor.Timing timing = plugin.getTickManager().featureTiming(PerformanceMonitor.Feature.HANDHELD);
        long started = timing == null ? 0L : System.nanoTime();
        try {
            advanceHandheldCooking(player, session);
        } finally {
            if (timing != null) timing.record(System.nanoTime() - started);
        }
    }

    private void advanceHandheldCooking(Player player, HandheldSession session) {
        UUID playerId = player.getUniqueId();
        if (!handheldCookingEnabled || player.isDead() || !session.matches(player.getInventory())
                || !isHandheldInputActive(player, session)) {
            stopHandheldUse(player, null);
            return;
        }
        if (++session.progress < session.duration) {
            // Progress updates stay at five packets per second. Normal inventory sync uses the
            // same display snapshot, so it cannot alternate real damage with cooking progress.
            if (session.display != null && session.progress % 4 == 0) {
                updateHandheldDisplay(player, session);
            }
            return;
        }
        CookingRecipe<?> recipe = findCampfireRecipe(session.ingredient);
        if (recipe != session.recipe) {
            stopHandheldUse(player, null);
            return;
        }
        ItemStack result = session.recipe.getResult().clone();
        // End before inventory changes and plugin callbacks can re-enter this manager.
        if (!handheldSessions.remove(playerId, session)) return;
        session.closeDisplay();
        ItemStack[] before = player.getInventory().getContents();
        for (int i = 0; i < before.length; i++) if (before[i] != null) before[i] = before[i].clone();
        List<Item> drops = new ArrayList<>();
        try {
            if (!session.consumeIngredient(player.getInventory(), player.getGameMode() == GameMode.CREATIVE)) return;
            for (ItemStack leftover : player.getInventory().addItem(result.clone()).values()) {
                drops.add(player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        } catch (RuntimeException e) {
            for (Item drop : drops) drop.remove();
            player.getInventory().setContents(before);
            throw e;
        } finally {
            sendHandheldSlot(player, session.slot, player.getInventory().getItem(session.slot));
        }
        Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(
                player.getUniqueId(), player.getName(), "skillet", result, session.recipe.getExperience(), player.getLocation()));
        // Award the advancement after a serving completes and its result has been delivered.
        advancementAward.accept(player);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
    }

    private static boolean isUsingOtherHand(Player player, EquipmentSlot cookingHand) {
        return player.isHandRaised() && player.getHandRaised() != cookingHand;
    }

    static boolean isHandheldInputActive(Player player, HandheldSession session) {
        // Native eating/drinking must take precedence over the repeated right-click grace period.
        if (isUsingOtherHand(player, session.hand)) return false;
        return player.isHandRaised()
                || System.currentTimeMillis() - session.lastInput <= HANDHELD_INPUT_TIMEOUT_MILLIS;
    }

    private void sendHandheldSlot(Player player, int slot, ItemStack item) {
        var user = BukkitAdaptor.adapt(player);
        if (user == null) return;
        // Equipment packets do not reliably refresh the local hotbar. This packet uses inventory
        // slots (0-8 for the hotbar, 40 for offhand) and exists throughout the 1.21.5+ baseline.
        ItemStack display = item == null ? new ItemStack(Material.AIR) : item.clone();
        user.sendPacket(ClientboundSetPlayerInventoryPacketProxy.INSTANCE.newInstance(
                slot, BukkitAdaptor.adapt(display).minecraftItem()), false);
    }

    private void updateHandheldDisplay(Player player, HandheldSession session) {
        PerformanceMonitor.Timing timing = plugin.getTickManager().featureTiming(PerformanceMonitor.Feature.HANDHELD_DISPLAY);
        long started = timing == null ? 0L : System.nanoTime();
        try {
            refreshHandheldDisplay(player, session);
        } finally {
            if (timing != null) timing.record(System.nanoTime() - started);
        }
    }

    private void refreshHandheldDisplay(Player player, HandheldSession session) {
        NamespacedKey model = session.model;
        if (model != null) {
            var key = Key.of(model.toString());
            model = NamespacedKey.fromString(CraftEngineModelMappings.get()
                    .getOrDefault(key, key).asString());
        }
        boolean showProgress = handheldProgressDisplayEnabled;
        // Static displays remain installed for normal inventory sync; only a setting or model
        // change requires rebuilding them while progress is hidden.
        if (!session.needsDisplayUpdate(model, showProgress)) return;
        ItemStack display = createHandheldDisplay(player.getInventory().getItem(session.slot),
                model, session.progress, session.duration, showProgress);
        var wrapped = BukkitAdaptor.adapt(display);
        // Note-block interaction swings are predicted by the client before event cancellation.
        // Only the display copy suppresses them; versions before 1.21.11 lack this component.
        if (VersionHelper.isOrAbove1_21_11) {
            if (session.noSwingAnimation == null) {
                wrapped.setJavaComponent(DataComponentKeys.SWING_ANIMATION, Map.of("type", "none"));
                session.noSwingAnimation = wrapped.getExactComponent(DataComponentKeys.SWING_ANIMATION);
            } else {
                wrapped.setExactComponent(DataComponentKeys.SWING_ANIMATION, session.noSwingAnimation);
            }
        }
        Object item = wrapped.minecraftItem();
        session.display.update(item, ClientboundSetPlayerInventoryPacketProxy.INSTANCE.newInstance(session.slot, item));
        session.displayedProgress = showProgress;
        session.displayedModel = model;
    }

    private void returnHandheldIngredient(Player player, ItemStack ingredient) {
        ItemDelivery.giveOrDrop(player, ingredient);
    }

    /**
     * True when the player is on fire or a heat source sits in the 3x3x3 box around the player's block position.
     * The box is centred on the player, not on whatever the player right-clicked, which is what the mod does
     * (SkilletItem#isPlayerNearHeatSource); the clicked block, its face and even right-clicking air are all
     * irrelevant. An extinguished campfire or stove is cold here, exactly as it is for the pot, the placed
     * skillet, the tray and the stove: every heat-source question goes through the same state-aware table.
     */
    private boolean hasNearbyHeatSource(Player player) {
        if (player.getFireTicks() > 0) return true;
        World world = player.getWorld();
        // getLocation() builds a new Location per call, so the three block-coordinate reads share one copy
        // instead of allocating three.
        Location center = player.getLocation();
        int x = center.getBlockX(), y = center.getBlockY(), z = center.getBlockZ();
        // A Folia region check needs a Location argument, and it is the only caller that does: the adapter
        // answers true without inspecting it off Folia. Testing the server flavour first keeps the common
        // Paper path free of the 27 per-offset Location objects this loop would otherwise allocate.
        boolean folia = plugin.scheduler().isFolia();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) continue;
            if (folia && !plugin.scheduler().isOwnedByCurrentRegion(new Location(world, x + dx, y + dy, z + dz))) {
                continue;
            }
            Block block = world.getBlockAt(x + dx, y + dy, z + dz);
            if (plugin.getHeatSourceConfig().isHeatSource(
                    block, CraftEngineBlocks.getCustomBlockState(block))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasHandheldIngredient(ItemStack skillet) {
        if (skillet == null || !skillet.hasItemMeta()) return false;
        byte[] bytes = skillet.getItemMeta().getPersistentDataContainer().get(HANDHELD_INGREDIENT_KEY, PersistentDataType.BYTE_ARRAY);
        return bytes != null && bytes.length > 0;
    }

    public boolean isHandheldCookingEnabled() {
        return handheldCookingEnabled;
    }

    public boolean isHandheldProgressDisplayEnabled() {
        return handheldProgressDisplayEnabled;
    }

    private ItemStack getHandheldIngredient(ItemStack skillet) {
        if (skillet == null || !skillet.hasItemMeta()) return null;
        byte[] bytes = skillet.getItemMeta().getPersistentDataContainer().get(HANDHELD_INGREDIENT_KEY, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) return null;
        try { return ItemStack.deserializeBytes(bytes); } catch (RuntimeException ignored) { return null; }
    }

    private void clearHandheldState(ItemStack skillet) {
        if (skillet == null || !skillet.hasItemMeta()) return;
        ItemMeta meta = skillet.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer originalDamage = pdc.get(HANDHELD_ORIGINAL_DAMAGE_KEY, PersistentDataType.INTEGER);
        if (originalDamage != null && meta instanceof Damageable damageable && damageable.hasMaxDamage()) {
            damageable.setDamage(Math.max(0, Math.min(originalDamage, damageable.getMaxDamage())));
        }
        pdc.remove(HANDHELD_INGREDIENT_KEY);
        pdc.remove(HANDHELD_DURATION_KEY);
        pdc.remove(HANDHELD_PROGRESS_KEY);
        pdc.remove(HANDHELD_ORIGINAL_DAMAGE_KEY);
        restoreHandheldModel(meta);
        skillet.setItemMeta(meta);
    }

    static void restoreHandheldModel(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String original = pdc.get(HANDHELD_ORIGINAL_MODEL_KEY, PersistentDataType.STRING);
        if (original == null) return;
        meta.setItemModel(original.isEmpty() ? null : NamespacedKey.fromString(original));
        pdc.remove(HANDHELD_ORIGINAL_MODEL_KEY);
    }

    static ItemStack createHandheldDisplay(ItemStack skillet, NamespacedKey model, int progress, int duration,
                                           boolean showProgress) {
        ItemStack display = skillet.clone();
        ItemMeta meta = display.getItemMeta();
        if (model != null) meta.setItemModel(model);
        if (showProgress && duration > 0 && meta instanceof Damageable damageable) {
            int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : skillet.getType().getMaxDurability();
            if (maxDamage <= 0) {
                display.setItemMeta(meta);
                return display;
            }
            int boundedProgress = Math.max(0, Math.min(progress, duration));
            int visualDamage = maxDamage - (int) Math.round((double) boundedProgress * maxDamage / duration);
            int clampedDamage = Math.max(0, Math.min(maxDamage - 1, visualDamage));
            if (damageable.getDamage() != clampedDamage) {
                damageable.setDamage(clampedDamage);
            }
        }
        display.setItemMeta(meta);
        return display;
    }

    static final class HandheldSession {
        final EquipmentSlot hand;
        final int slot;
        final int ingredientSlot;
        final ItemStack skillet;
        final ItemStack ingredient;
        final CookingRecipe<?> recipe;
        final int duration;
        final NamespacedKey model;
        final AtomicBoolean scheduled = new AtomicBoolean();
        HandheldCookingDisplay display;
        Boolean displayedProgress;
        NamespacedKey displayedModel;
        Object noSwingAnimation;
        long lastInput = System.currentTimeMillis();
        int progress;
        boolean consumed;

        boolean needsDisplayUpdate(NamespacedKey currentModel, boolean showProgress) {
            return showProgress || !Boolean.FALSE.equals(displayedProgress)
                    || !Objects.equals(currentModel, displayedModel);
        }

        void closeDisplay() {
            if (display != null) display.close();
        }

        HandheldSession(EquipmentSlot hand, int slot, int ingredientSlot, ItemStack skillet,
                        ItemStack ingredient, CookingRecipe<?> recipe, int duration, NamespacedKey model) {
            this.hand = hand;
            this.slot = slot;
            this.ingredientSlot = ingredientSlot;
            this.skillet = skillet;
            this.ingredient = ingredient;
            this.recipe = recipe;
            this.duration = Math.max(1, duration);
            this.model = model;
        }

        boolean matches(PlayerInventory inventory) {
            ItemStack pan = inventory.getItem(slot);
            ItemStack food = inventory.getItem(ingredientSlot);
            return inventory.getHeldItemSlot() == (slot == 40 ? ingredientSlot : slot)
                    && pan != null && pan.getAmount() == 1 && skillet.isSimilar(pan)
                    && food != null && food.getAmount() > 0 && ingredient.isSimilar(food);
        }

        boolean consumeIngredient(PlayerInventory inventory, boolean creative) {
            if (consumed || !matches(inventory)) return false;
            consumed = true;
            if (!creative) {
                ItemStack food = inventory.getItem(ingredientSlot);
                food.setAmount(food.getAmount() - 1);
            }
            return true;
        }
    }
}
