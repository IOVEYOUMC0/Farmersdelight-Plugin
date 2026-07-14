package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotLayout;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.CookingPotPlaceholder;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineImages;
import net.momirealms.craftengine.core.font.Image;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

public class CookingPotGui implements InventoryHolder {

    static final Map<UUID, CookingPotGui> activeGuis = new ConcurrentHashMap<>();
    private static volatile boolean listenerRegistered = false;
    private static final Pattern SHIFT_TAG_PATTERN = Pattern.compile("<shift:([+-]?\\d+)>");
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("<image:([a-z0-9_./-]+:[a-z0-9_./-]+)>");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final FarmersDelightPlugin plugin;
    private final CookingPotBlockEntity blockEntity;
    private final CookingPotBlockBehavior blockBehavior;
    private final GuiConfig config;
    private final Inventory inventory;

    private final int[] ingredientSlots;
    private final int[] containerSlots;
    private final int[] bufferSlots;
    private final int[] outputSlots;
    private final int heatSlot;
    private final int progressSlot;
    private final int recipeSlot;
    private final Map<Integer, Integer> slotMapping;
    private final Map<Integer, Integer> writableSlotMapping;

    private final World world;
    private final Location cookingPotLocation;
    private volatile boolean closed = false;
    private final Map<String, String> reusablePlaceholders = new HashMap<>();
    private final Consumer<Void> tickCallback;
    private Boolean cachedHeatState;
    private int cachedProgressPercent = -1;
    private int cachedRemainingSeconds = -1;
    private final Map<Integer, ItemStack> cachedDisplayItems = new HashMap<>();
    private ItemStack cachedPendingContainer;
    private boolean syncQueued;
    // Inventory version seen at last input-slot rescan; skip rescan if unchanged.
    private long lastSeenInventoryVersion = Long.MIN_VALUE;
    // Tracks which writable GUI slots have been mutated by a click/drag handler since the last
    // syncToBlockEntity. Sync only writes back these slots — so an unchanged GUI slot can't clobber
    // a concurrent cook tick that mutated the corresponding entity slot in the same window
    // (multi-viewer dup vector).
    // Slots are added from the viewer's click region; syncToBlockEntity iterates and clears them on the
    // pot's region during a break-triggered close. A concurrent set keeps that cross-region access from
    // corrupting the backing table or throwing under iteration.
    private final Set<Integer> dirtyWritableSlots = ConcurrentHashMap.newKeySet();

    public CookingPotGui(FarmersDelightPlugin plugin, CookingPotBlockEntity blockEntity,
                         CookingPotBlockBehavior blockBehavior, World world) {
        this(plugin, blockEntity, blockBehavior, world, null);
    }

    public CookingPotGui(FarmersDelightPlugin plugin, CookingPotBlockEntity blockEntity,
                         CookingPotBlockBehavior blockBehavior, World world, Location cookingPotLocation) {
        this.plugin = plugin;
        this.blockEntity = blockEntity;
        this.blockBehavior = blockBehavior;
        this.world = world;
        this.cookingPotLocation = cookingPotLocation;
        String customId = blockBehavior != null ? blockBehavior.getCustomRecipeGroupId() : blockEntity.getRecipeGroupId();
        this.config = plugin.getCookingPotGuiConfig(customId);

        this.ingredientSlots = config.getIngredientSlots();
        this.containerSlots = config.getContainerSlots();
        this.bufferSlots = config.getBufferSlots();
        this.outputSlots = config.getOutputSlots();
        this.heatSlot = config.getHeatSlot();
        this.progressSlot = config.getProgressSlot();
        this.recipeSlot = config.getRecipeSlot();
        this.slotMapping = new HashMap<>();
        this.writableSlotMapping = new HashMap<>();
        CookingPotLayout layout = blockEntity.getLayout();
        mapSlots(ingredientSlots, layout.inputSlots(), true);
        mapSlots(containerSlots, layout.containerSlots(), true);
        mapSlots(bufferSlots, layout.pendingOutputSlots(), false);
        mapSlots(outputSlots, layout.outputSlots(), false);

        this.inventory = Bukkit.createInventory(this, config.getSize(), resolveTitleComponent());
        this.tickCallback = ignored -> {
            if (!closed) {
                tick();
            }
        };
    }

    private void mapSlots(int[] guiSlots, int[] entitySlots, boolean writable) {
        int count = Math.min(guiSlots.length, entitySlots.length);
        for (int i = 0; i < count; i++) {
            slotMapping.put(guiSlots[i], entitySlots[i]);
            if (writable) {
                writableSlotMapping.put(guiSlots[i], entitySlots[i]);
            }
        }
    }

    public void open(Player player) {
        closed = false;

        CookingPotGui existingGui = activeGuis.get(player.getUniqueId());
        if (existingGui != null && !existingGui.closed) {
            existingGui.close();
        }

        ensureListenerRegistered();
        activeGuis.put(player.getUniqueId(), this);

        refreshInventory();
        player.openInventory(inventory);

        GuiTickManager.getInstance(plugin).registerCallback(player, tickCallback);
    }

    private void tick() {
        if (closed) return;

        // The tick callback runs on the VIEWER's region/entity thread (GuiTickManager uses
        // runForEntity). Reading the pot block here would be cross-region access on Folia,
        // so dispatch the heat-source block read to the pot's own region; the result is stored
        // on the thread-safe block entity and consumed by updateDisplayItems below or the next tick.
        refreshHeatStateOnRegion();

        blockEntity.tryMovePendingToOutput();
        updateDisplayItems();
    }

    private void refreshHeatStateOnRegion() {
        if (world == null || blockBehavior == null) {
            return;
        }
        plugin.scheduler().runAt(cookingPotLocation, () -> {
            if (!closed && world != null && blockBehavior != null) {
                blockEntity.setHasHeatSource(blockBehavior.checkHeatSource(blockEntity.getPos(), world));
            }
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void refreshInventory() {
        resetDisplayCache();
        // Force the next updateDisplayItems to rescan, ensuring input-slot state is correct after open/redraw.
        lastSeenInventoryVersion = Long.MIN_VALUE;
        inventory.clear();

        for (int slot = 0; slot < config.getSize(); slot++) {
            String type = config.getSlotType(slot);
            GuiConfig.GuiItem guiItem = getGuiItemForType(type);
            if (guiItem != null && !isPlayerInputSlot(slot)) {
                inventory.setItem(slot, guiItem.createItem());
            }
        }

        if (recipeSlot >= 0) {
            GuiConfig.GuiItem recipeItem = config.getItem("recipe");
            if (recipeItem != null) {
                inventory.setItem(recipeSlot, recipeItem.createItem());
            }
        }

        for (int ingredientSlot : ingredientSlots) {
            inventory.setItem(ingredientSlot, null);
        }
        for (int slot : containerSlots) inventory.setItem(slot, null);
        // Buffer + output start empty visually; paint the invisible background placeholder so the painted GUI
        // background shows through these read-only slots instead of a bare slot (matches the keg's pattern).
        for (int slot : bufferSlots) inventory.setItem(slot, placeholderItem());
        for (int slot : outputSlots) inventory.setItem(slot, placeholderItem());

        for (Map.Entry<Integer, Integer> entry : slotMapping.entrySet()) {
            int guiSlot = entry.getKey();
            int entitySlot = entry.getValue();
            ItemStack item = blockEntity.getInventorySlot(entitySlot);
            if (item != null && !item.getType().isAir()) {
                inventory.setItem(guiSlot, item.clone());
            }
        }

        updateDisplayItems();
    }

    private Component resolveTitleComponent() {
        String title = blockBehavior != null && blockBehavior.getTitleOverride() != null
                ? blockBehavior.getTitleOverride()
                : config.getTitle();
        return MINI_MESSAGE.deserialize(resolveTitleLayout(title));
    }

    private String resolveTitleLayout(String rawTitle) {
        String title = "Cooking Pot";
        if (rawTitle != null) {
            title = rawTitle;
        }
        title = title.replace("<offset>", resolveLayoutToken(config.getTitleLayoutOffset()));
        title = title.replace("<icon>", resolveLayoutToken(config.getTitleLayoutIcon()));
        return resolveLayoutToken(title);
    }

    private String resolveLayoutToken(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String resolved = replaceShiftTags(value);
        return replaceImageTags(resolved);
    }

    private String replaceShiftTags(String text) {
        Matcher matcher = SHIFT_TAG_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int offset = Integer.parseInt(matcher.group(1));
            String replacement = plugin.getCraftEngine().fontManager().createMiniMessageOffsets(offset);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceImageTags(String text) {
        Matcher matcher = IMAGE_TAG_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Image image = CraftEngineImages.byId(Key.of(matcher.group(1)));
            String replacement = matcher.group(0);
            if (image != null) {
                replacement = image.miniMessageAt(0, 0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean isPlayerInputSlot(int slot) {
        return writableSlotMapping.containsKey(slot);
    }

    private GuiConfig.GuiItem getGuiItemForType(String type) {
        if (type == null) {
            return null;
        }
        if (!config.isFillersEnabled() && ("background".equals(type) || "decoration".equals(type))) {
            return null;
        }
        return switch (type) {
            case "background" -> config.getItem("background");
            case "decoration" -> config.getItem("decoration");
            default -> null;
        };
    }

    @SuppressWarnings({ "null" })
    private void updateDisplayItems() {
        if (!syncQueued) {
            // Only do a full input-slot rescan when the inventory version changed; skip when pot contents are unchanged.
            long version = blockEntity.getInventoryVersion();
            if (version != lastSeenInventoryVersion) {
                refreshInputSlotsFromBlockEntity();
                lastSeenInventoryVersion = version;
            }
        }

        if (heatSlot >= 0) {
            boolean hasHeat = blockEntity.hasHeatSource();
            if (cachedHeatState == null || cachedHeatState != hasHeat) {
        String heatKey = "heat-inactive";
        if (hasHeat) {
            heatKey = "heat-active";
        }
                GuiConfig.GuiItem heatItem = config.getItem(heatKey);
                if (heatItem != null) {
                    inventory.setItem(heatSlot, heatItem.createItem());
                }
                cachedHeatState = hasHeat;
            }
        }

        if (progressSlot >= 0) {
            int progressPercent = blockEntity.getProgressPercent();
            int remainingSeconds = blockEntity.getRemainingTime() / 20;
            if (progressPercent != cachedProgressPercent || remainingSeconds != cachedRemainingSeconds) {
                GuiConfig.GuiItem progressItem = config.getProgressItem(progressPercent);
                if (progressItem != null) {
                    reusablePlaceholders.clear();
                    reusablePlaceholders.put("progress", String.valueOf(progressPercent));
                    reusablePlaceholders.put("time", String.valueOf(remainingSeconds));
                    inventory.setItem(progressSlot, progressItem.createItem(reusablePlaceholders));
                }
                cachedProgressPercent = progressPercent;
                cachedRemainingSeconds = remainingSeconds;
            }
        }

        ItemStack container = blockEntity.getMealContainer();
        boolean pendingContainerChanged = !sameItemState(container, cachedPendingContainer);
        for (int slot : bufferSlots) {
            updateMappedDisplaySlot(slot, pendingContainerChanged, container);
        }
        for (int slot : outputSlots) {
            updateMappedDisplaySlot(slot, false, null);
        }
        cachedPendingContainer = cloneOrNull(container);
    }

    private void updateMappedDisplaySlot(int guiSlot, boolean containerChanged, ItemStack container) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return;
        }
        // Whether the container hint exists depends only on whether a pending container exists now; whether a rebuild
        // is needed depends only on whether the item or container changed. Previously one flag served both, so when
        // "buffer item amount changed but container unchanged" the display was rebuilt but the hint was dropped.
        boolean hasContainerHint = container != null && !container.getType().isAir();
        ItemStack item = blockEntity.getInventorySlot(entitySlot);
        ItemStack cached = cachedDisplayItems.get(guiSlot);
        if (!containerChanged && sameItemState(item, cached)) {
            return;
        }
        ItemStack display = cloneOrNull(item);
        if (hasContainerHint && display != null) {
            appendContainerHint(display, container);
        }
        // Empty buffer / output slot → paint the invisible background placeholder so the painted background shows
        // through (matches the keg). The placeholder is PDC-tagged and never persisted (these slots aren't writable
        // and the output-take / takeOutputFromSlot path reads the block entity, not the GUI inventory).
        if (display == null && (config.isBufferSlot(guiSlot) || config.isOutputSlot(guiSlot))) {
            display = placeholderItem();
        }
        inventory.setItem(guiSlot, display);
        cachedDisplayItems.put(guiSlot, cloneOrNull(item));
    }

    /** Invisible PDC-tagged copy of the GUI's configured background filler, used to fill empty buffer / output
     * cells so the painted background shows through. Returns null if no background filler is configured. */
    private ItemStack placeholderItem() {
        GuiConfig.GuiItem background = config.getItem("background");
        if (background == null) {
            return null;
        }
        return CookingPotPlaceholder.mark(background.createItem());
    }

    @SuppressWarnings("null")
    private void refreshInputSlotsFromBlockEntity() {
        for (Map.Entry<Integer, Integer> entry : slotMapping.entrySet()) {
            int guiSlot = entry.getKey();
            int entitySlot = entry.getValue();

            ItemStack entityItem = blockEntity.getInventorySlot(entitySlot);
            ItemStack guiItem = inventory.getItem(guiSlot);

            if (!sameItemState(entityItem, guiItem)) {
            inventory.setItem(guiSlot, cloneOrNull(entityItem));
            }
        }
    }

    private void resetDisplayCache() {
        cachedHeatState = null;
        cachedProgressPercent = -1;
        cachedRemainingSeconds = -1;
        cachedDisplayItems.clear();
        cachedPendingContainer = null;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item.clone();
    }

    private boolean sameItemState(@Nonnull ItemStack first, @Nonnull ItemStack second) {
        boolean firstEmpty = first == null || first.getType().isAir();
        boolean secondEmpty = second == null || second.getType().isAir();
        if (firstEmpty || secondEmpty) {
            return firstEmpty == secondEmpty;
        }

        return first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    private void appendContainerHint(ItemStack item, ItemStack container) {
        if (item == null || container == null || container.getType().isAir()) {
            return;
        }

        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        java.util.List<Component> lore = meta.lore();
        if (lore == null) {
            lore = new java.util.ArrayList<>();
        }

        Player viewer = null;
        if (!inventory.getViewers().isEmpty() && inventory.getViewers().getFirst() instanceof Player player) {
            viewer = player;
        }
        // White container name on a gray label so the item stands out from the rest of the hint.
        Component containerName = ItemUtils.getDisplayComponent(container, viewer)
                .colorIfAbsent(NamedTextColor.WHITE);
        Component hint = Component.translatable("gui.cooking_pot.pending_container_hint", containerName)
                .color(NamedTextColor.GRAY);

        lore.add(Component.empty());
        lore.add(hint.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** Writes item to rawSlot AND marks the slot dirty for the next sync. Use this
     *  from every click/drag handler that mutates a writable GUI slot; never use it for periodic
     *  display refresh (those don't represent player intent and would force a false write). */
    private void writeWritableSlot(int rawSlot, ItemStack item) {
        inventory.setItem(rawSlot, item);
        if (writableSlotMapping.containsKey(rawSlot)) {
            dirtyWritableSlots.add(rawSlot);
        }
    }

    private void syncToBlockEntity() {
        // Only write the slots the player actively mutated since the last sync. Skipping clean slots
        // is what prevents the GUI's pre-modification snapshot from clobbering cook-tick mutations
        // (e.g. ingredients consumed / result deposited) that happened during the click handler.
        if (!dirtyWritableSlots.isEmpty()) {
            for (int guiSlot : dirtyWritableSlots) {
                Integer entitySlot = writableSlotMapping.get(guiSlot);
                if (entitySlot == null) continue;
                ItemStack item = inventory.getItem(guiSlot);
                blockEntity.setInventorySlot(entitySlot, cloneOrNull(item));
            }
            dirtyWritableSlots.clear();
        }
        blockEntity.tryMovePendingToOutput();
        if (world != null && blockEntity.getPosKey() != null) {
            TickManager tickManager = plugin.getTickManager();
            if (tickManager != null) {
                if (blockEntity.hasStoredContents()) {
                    tickManager.markActive(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                } else {
                    tickManager.markInactive(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                }
            }
            // Heat-source detection reads the pot block, so it is dispatched to the pot's region via
            // refreshHeatStateOnRegion() (called from the sync/tick path) rather than read here,
            // because here we may be on the viewer's thread.
        }
    }

    private void close() {
        if (closed) return;
        closed = true;
        syncQueued = false;
        try {
            GuiTickManager.getInstance(plugin).unregisterCallback(tickCallback);
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("gui_runtime.unregister_tick_failed", "error", e.getMessage()));
        }
        try {
            syncToBlockEntity();
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("gui_runtime.sync_inventory_failed", "error", e.getMessage()));
        }
    }

    void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        Inventory clickedInventory = event.getClickedInventory();
        boolean clickedTop = clickedInventory != null && clickedInventory.equals(inventory);
        boolean clickedBottom = clickedInventory != null && clickedInventory.getType() == InventoryType.PLAYER;

        // Double-click "collect to cursor" gathers matching item stacks from both inventories.
        // Vanilla's default would pull from read-only top display/output/buffer slots mapped to the
        // block entity — syncToBlockEntity only writes back writable slots, so a collect would pull
        // items out of display slots without removing them from the entity -> item duplication.
        // Implement a safe collect that only pulls from writable input slots (under the inventory
        // lock, mirroring the click handlers) and the player's own inventory.
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        if (action == InventoryAction.UNKNOWN) {
            event.setCancelled(true);
            return;
        }
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleCollectToCursor(event, player);
            return;
        }
        // On a double-click of an empty slot with nothing to merge, Paper fires NOTHING + DOUBLE_CLICK.
        // If the clicked slot is a placeable input slot, let vanilla handle it normally (drop the cursor
        // item into the slot) by not cancelling the event, so the double-click is not interrupted.
        if (action == InventoryAction.NOTHING && click == ClickType.DOUBLE_CLICK
                && clickedTop && isPlayerInputSlot(rawSlot)) {
            scheduleGuiSync(event.getWhoClicked() instanceof Player p ? p : null);
            return;
        }
        // Hotbar number-key / offhand swap targeting top (GUI) slots does not fit this GUI's
        // slot model (they fall into the cursor-pickup branch). Reject them on the top inventory;
        // players can still freely arrange their own inventory.
        if (clickedTop && (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND)) {
            event.setCancelled(true);
            return;
        }
        if (rawSlot == heatSlot || rawSlot == progressSlot || config.isBufferSlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot == recipeSlot) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            syncToBlockEntity();
            close();
            activeGuis.remove(player.getUniqueId());
            player.closeInventory();
            RecipeViewGui recipeGui = new RecipeViewGui(plugin, player, true, cookingPotLocation);
            recipeGui.open(player);
            return;
        }

        if (config.isOutputSlot(rawSlot)) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            int requestedAmount = resolveOutputTakeAmount(event, rawSlot);
            if (requestedAmount <= 0) {
                return;
            }

            CookingPotBlockEntity.TakenMeal meal = takeOutputFromSlot(rawSlot, requestedAmount);
            ItemStack outputItem = meal == null ? null : meal.item();
            if (outputItem != null && !outputItem.getType().isAir()) {
                deliverOutputToPlayer(event, player, outputItem);
                applyOutputExperienceReward(player, outputItem, meal.experience());
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                Bukkit.getPluginManager().callEvent(new com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent(
                        player.getUniqueId(), "cooking_pot", outputItem, cookingPotLocation));

                updateDisplayItems();
            }
            return;
        }

        if (clickedTop && !isPlayerInputSlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        if (clickedTop) {
            event.setCancelled(true);
            handleTopInventoryInteraction(event);
            return;
        }

        if (clickedBottom && event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (current != null && !current.getType().isAir()) {
                // Adopt authoritative state first so the deposit stacks onto the real slot contents, not a stale
                // phantom from another viewer. Hold the block entity's inventory lock across the whole
                // read-modify-write so a concurrent cook tick (which consumes ingredients under the same lock)
                // cannot land between the refresh and the write-back and be clobbered by the pre-cook GUI value
                // — a Folia cross-region ingredient dupe.
                blockEntity.withInventoryLock(() -> {
                    refreshInputSlotsFromBlockEntity();
                    smartMoveFromPlayerInventory(current);
                    event.setCurrentItem(current.getAmount() > 0 ? current : null);
                    syncToBlockEntity();
                });
                updateDisplayItems();
            }
            return;
        }

        scheduleGuiSync(event.getWhoClicked() instanceof Player p ? p : null);
    }

    void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) {
            event.setCancelled(true);
            return;
        }

        boolean touchesTop = false;
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < config.getSize()) {
                touchesTop = true;
                break;
            }
        }
        if (!touchesTop) {
            // Drag entirely within the player inventory; leave to vanilla handling.
            scheduleGuiSync(event.getWhoClicked() instanceof Player p ? p : null);
            return;
        }

        // Touches the top: always cancel (vanilla applies the drag to all touched slots, including read-only
        // display/output/buffer slots -> item duping). Instead manually apply vanilla's computed distribution
        // only to writable input slots, leaving the rest on the cursor, preventing duping/loss.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack oldCursor = event.getOldCursor();
        if (oldCursor == null || oldCursor.getType().isAir()) {
            return;
        }

        int maxStack = Math.min(oldCursor.getMaxStackSize(), inventory.getMaxStackSize());
        // Lightweight precheck: is any input slot touched by the drag and holding a matching item type? Avoids
        // acquiring the inventory lock needlessly.
        boolean hasApplicable = false;
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            if (rawSlot < 0 || rawSlot >= config.getSize() || !isPlayerInputSlot(rawSlot)) {
                continue;
            }
            ItemStack newItem = entry.getValue();
            if (newItem == null || newItem.getType().isAir() || !newItem.isSimilar(oldCursor)) {
                continue;
            }
            hasApplicable = true;
            break;
        }
        if (!hasApplicable) {
            return; // no applicable input slots; cursor unchanged (event already cancelled)
        }

        // Under the inventory lock, compute and apply each share based on the freshly refreshed state, skipping the
        // intermediate LinkedHashMap allocation. Computing share against the latest existingAmount is more accurate
        // (computing it from stale pre-lock state could disagree with the refreshed state).
        int[] placedTotal = {0};
        blockEntity.withInventoryLock(() -> {
            refreshInputSlotsFromBlockEntity();
            for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
                int rawSlot = entry.getKey();
                if (rawSlot < 0 || rawSlot >= config.getSize() || !isPlayerInputSlot(rawSlot)) {
                    continue; // only handle writable top input slots
                }
                ItemStack newItem = entry.getValue();
                if (newItem == null || newItem.getType().isAir() || !newItem.isSimilar(oldCursor)) {
                    continue;
                }
                ItemStack existing = inventory.getItem(rawSlot);
                int existingAmount = (existing == null || existing.getType().isAir()) ? 0 : existing.getAmount();
                // The slot may now hold a different item (another viewer / the cook tick); only stack onto a match.
                if (existingAmount > 0 && !oldCursor.isSimilar(existing)) {
                    continue;
                }
                int share = Math.min(newItem.getAmount(), maxStack) - existingAmount;
                if (share <= 0) {
                    continue;
                }
                int place = Math.min(existingAmount + share, maxStack);
                int actual = place - existingAmount;
                if (actual <= 0) {
                    continue;
                }
                ItemStack placed = oldCursor.clone();
                placed.setAmount(place);
                writeWritableSlot(rawSlot, placed);
                placedTotal[0] += actual;
            }
            syncToBlockEntity();
        });

        // Cursor remainder = original cursor amount - total actually placed; shares not applied to read-only slots/inventory stay on the cursor.
        int remaining = oldCursor.getAmount() - placedTotal[0];
        final ItemStack cursorAfter;
        if (remaining > 0) {
            ItemStack leftover = oldCursor.clone();
            leftover.setAmount(remaining);
            cursorAfter = leftover;
        } else {
            cursorAfter = null;
        }
        // A cancelled InventoryDragEvent has its cursor restored to the pre-drag stack by the server AFTER
        // this handler returns (unlike a cancelled click, which keeps the handler's cursor). Setting it here
        // would be clobbered, leaving the whole stack in hand while the shares are already committed to the
        // pot — a duplication. Apply the reduced cursor next tick, after that restore, on the player's region.
        player.getScheduler().run(plugin, t -> player.setItemOnCursor(cursorAfter), null);

        updateDisplayItems();
    }

    private void handleTopInventoryInteraction(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= config.getSize() || !isPlayerInputSlot(rawSlot)) {
            return;
        }
        // Hold the block entity's inventory lock across the whole read-modify-write (refresh + mutate + sync) so
        // a concurrent cook tick (which consumes ingredients under the same lock) cannot interleave between the
        // authoritative-state refresh and the write-back and get clobbered — a Folia cross-region dupe.
        blockEntity.withInventoryLock(() -> handleTopInventoryInteractionLocked(event, player, rawSlot));
    }

    private void handleTopInventoryInteractionLocked(InventoryClickEvent event, Player player, int rawSlot) {
        // Adopt the authoritative entity state for the mapped slots before acting, so a second viewer can't
        // take a phantom item that another viewer (or the cook tick) already removed in the ~1-tick window
        // before the periodic refresh would have corrected this GUI.
        refreshInputSlotsFromBlockEntity();

        if (event.isShiftClick()) {
            handleTopShiftClick(player, rawSlot);
            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        ItemStack slotItem = inventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        boolean rightClick = event.isRightClick();

        if (cursor == null || cursor.getType().isAir()) {
            if (slotItem == null || slotItem.getType().isAir()) {
                return;
            }

            if (rightClick && slotItem.getAmount() > 1) {
                int takeAmount = (slotItem.getAmount() + 1) / 2;
                ItemStack taken = slotItem.clone();
                taken.setAmount(takeAmount);
                slotItem.setAmount(slotItem.getAmount() - takeAmount);
                writeWritableSlot(rawSlot, slotItem.getAmount() > 0 ? slotItem : null);
                player.setItemOnCursor(taken);
            } else {
                writeWritableSlot(rawSlot, null);
                player.setItemOnCursor(slotItem.clone());
            }

            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        if (slotItem == null || slotItem.getType().isAir()) {
            ItemStack placed = cursor.clone();
            if (rightClick) {
                placed.setAmount(1);
                cursor.setAmount(cursor.getAmount() - 1);
                player.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            } else {
                player.setItemOnCursor(null);
            }
            writeWritableSlot(rawSlot, placed);
            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        if (slotItem.isSimilar(cursor)) {
            int maxStack = Math.min(slotItem.getMaxStackSize(), inventory.getMaxStackSize());
            int space = maxStack - slotItem.getAmount();
            if (space <= 0) {
                writeWritableSlot(rawSlot, cursor.clone());
                player.setItemOnCursor(slotItem.clone());
                syncToBlockEntity();
                updateDisplayItems();
                return;
            }

            int moved = Math.min(space, rightClick ? 1 : cursor.getAmount());
            slotItem.setAmount(slotItem.getAmount() + moved);
            cursor.setAmount(cursor.getAmount() - moved);
            writeWritableSlot(rawSlot, slotItem);
            player.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        writeWritableSlot(rawSlot, cursor.clone());
        player.setItemOnCursor(slotItem.clone());
        syncToBlockEntity();
        updateDisplayItems();
    }

    /**
     * Safe double-click collect: gather matching items to the cursor only from writable input slots
     * (ingredient + container) and the player's inventory, skipping read-only slots
     * (output/buffer/heat/progress/recipe). Input-slot mutations go through the inventory lock,
     * consistent with the cook tick, to avoid a Folia cross-region dupe.
     */
    private void handleCollectToCursor(InventoryClickEvent event, Player player) {
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        int maxStack = Math.min(cursor.getMaxStackSize(), inventory.getMaxStackSize());
        int available = maxStack - cursor.getAmount();
        if (available <= 0) {
            return;
        }

        final ItemStack target = cursor;
        final int[] collected = {0};
        final int clickedRawSlot = event.getRawSlot();

        // Collect from input slots (under the inventory lock to stay consistent with the cook tick).
        blockEntity.withInventoryLock(() -> {
            refreshInputSlotsFromBlockEntity();
            for (int slot : ingredientSlots) {
                if (slot == clickedRawSlot) continue;
                if (collected[0] >= available) break;
                collected[0] += collectMatchingFromGuiSlot(slot, target, available - collected[0]);
            }
            for (int slot : containerSlots) {
                if (slot == clickedRawSlot) continue;
                if (collected[0] >= available) break;
                collected[0] += collectMatchingFromGuiSlot(slot, target, available - collected[0]);
            }
            syncToBlockEntity();
        });

        // Then collect from the player's inventory (vanilla behavior: main storage + hotbar, excluding armor and offhand).
        if (collected[0] < available) {
            PlayerInventory playerInventory = player.getInventory();
            int topSize = config.getSize();
            for (int i = 0; i < 36 && collected[0] < available; i++) {
                if (topSize + i == clickedRawSlot) continue;
                ItemStack item = playerInventory.getItem(i);
                if (item == null || !item.isSimilar(target)) continue;
                int take = Math.min(available - collected[0], item.getAmount());
                collected[0] += take;
                if (take >= item.getAmount()) {
                    playerInventory.setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - take);
                }
            }
        }

        if (collected[0] > 0) {
            cursor.setAmount(cursor.getAmount() + collected[0]);
            player.setItemOnCursor(cursor);
            updateDisplayItems();
        }
    }

    private int collectMatchingFromGuiSlot(int guiSlot, ItemStack target, int maxTake) {
        ItemStack slotItem = inventory.getItem(guiSlot);
        if (slotItem == null || slotItem.getType().isAir() || !slotItem.isSimilar(target)) {
            return 0;
        }
        int take = Math.min(maxTake, slotItem.getAmount());
        if (take >= slotItem.getAmount()) {
            writeWritableSlot(guiSlot, null);
        } else {
            ItemStack remaining = slotItem.clone();
            remaining.setAmount(slotItem.getAmount() - take);
            writeWritableSlot(guiSlot, remaining);
        }
        return take;
    }

    private void handleTopShiftClick(Player player, int rawSlot) {
        ItemStack current = inventory.getItem(rawSlot);
        if (current == null || current.getType().isAir()) {
            return;
        }

        PlayerInventory playerInventory = player.getInventory();
        ItemStack toMove = current.clone();
        Map<Integer, ItemStack> leftovers = playerInventory.addItem(toMove);
        if (leftovers.isEmpty()) {
            writeWritableSlot(rawSlot, null);
            return;
        }

        ItemStack leftover = leftovers.values().iterator().next();
        writeWritableSlot(rawSlot, leftover.clone());
    }

    private void scheduleGuiSync(Player viewer) {
        if (closed || syncQueued) {
            return;
        }

        syncQueued = true;
        Runnable syncTask = () -> {
            if (closed) {
                syncQueued = false;
                return;
            }

            try {
                syncToBlockEntity();
            } finally {
                syncQueued = false;
            }
            updateDisplayItems();
            refreshHeatStateOnRegion();
        };
        // The GUI inventory is owned by the viewer's region/entity thread, so deferred reads/writes
        // must run there (not on the pot's region) to avoid cross-thread access to the Bukkit
        // inventory on Folia. Block-related work is dispatched to the pot's region from inside syncTask.
        if (viewer != null) {
            // With a retired callback: if the player is retired, syncTask's finally block never runs, so syncQueued
            // would stay true forever, causing every later scheduleGuiSync call to early-return and breaking GUI sync.
            plugin.scheduler().runForEntity(viewer, syncTask, () -> syncQueued = false);
        } else {
            plugin.scheduler().runAt(cookingPotLocation, syncTask);
        }
    }

    void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) return;

        close();
        activeGuis.remove(event.getPlayer().getUniqueId());
    }



    public static void cleanupAll() {
        for (CookingPotGui gui : activeGuis.values()) {
            if (!gui.closed) {
                gui.close();
            }
        }
        activeGuis.clear();
        GuiTickManager.cleanup();
        // Calling HandlerList.unregisterAll(plugin) on disable removes the EventDispatcher, but this
        // static flag must also be reset so a fresh dispatcher is re-registered on soft restart;
        // otherwise GUI clicks would no longer be cancelled (dupe/loss).
        listenerRegistered = false;
    }

    public static void closeAllOpenGuis() {
        for (Map.Entry<UUID, CookingPotGui> entry : new ArrayList<>(activeGuis.entrySet())) {
            CookingPotGui gui = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (gui != null && !gui.closed) {
                gui.close();
            }
            activeGuis.remove(entry.getKey());
            if (player != null && player.isOnline()) {
                closeViewerInventory(player);
            }
        }
    }

    /** Force-closes every open cooking-pot GUI viewing the block at world/pos. Call this BEFORE
     * tearing down the block entity on a player/explosion break: otherwise a viewer keeps a live Bukkit
     * Inventory whose items the cook tick is no longer guarding, and clicking them out dupes (same shape as
     * the keg break-while-open dupe). */
    public static void closeOpenGuisAt(World world, int x, int y, int z) {
        if (world == null) {
            return;
        }
        java.util.UUID worldId = world.getUID();
        for (Map.Entry<UUID, CookingPotGui> entry : new ArrayList<>(activeGuis.entrySet())) {
            CookingPotGui gui = entry.getValue();
            if (gui == null) {
                continue;
            }
            Location loc = gui.cookingPotLocation;
            if (loc == null || loc.getWorld() == null
                    || !worldId.equals(loc.getWorld().getUID())
                    || loc.getBlockX() != x || loc.getBlockY() != y || loc.getBlockZ() != z) {
                continue;
            }
            if (!gui.closed) {
                gui.close();
            }
            activeGuis.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                closeViewerInventory(player);
            }
        }
    }

    /** Closes a viewer's inventory on the viewer's own region. On Folia a Player is region-owned, so a bare
     *  closeInventory() called from the broken pot's region throws a cross-region access error and aborts the
     *  caller before it drops the pot's stored contents. The EntityScheduler runs synchronously on Paper and
     *  routes to the entity's region on Folia; the try/catch keeps break cleanup going even if dispatch fails. */
    private static void closeViewerInventory(Player player) {
        try {
            player.getScheduler().run(FarmersDelightPlugin.getInstance(), t -> player.closeInventory(), null);
        } catch (Throwable t) {
            try {
                player.closeInventory();
            } catch (Throwable ignored) {
            }
        }
    }

    private void ensureListenerRegistered() {
        warm(plugin);
    }

    /** Registers the shared inventory listener up-front so the first cooking-pot open does not pay the
     *  one-time InvUI/event-dispatch class-load + registerEvents on the interaction path. Idempotent. */
    public static void warm(FarmersDelightPlugin plugin) {
        if (listenerRegistered) return;
        synchronized (CookingPotGui.class) {
            if (listenerRegistered) return;
            Bukkit.getPluginManager().registerEvents(new EventDispatcher(), plugin);
            listenerRegistered = true;
        }
    }

    public static class EventDispatcher implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            if (event.getInventory().getHolder() instanceof CookingPotGui gui) {
                gui.onClick(event);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof CookingPotGui gui) {
                gui.onDrag(event);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            if (event.getInventory().getHolder() instanceof CookingPotGui gui) {
                gui.onClose(event);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            // PlayerQuit must iterate activeGuis directly rather than the holder
            UUID uuid = event.getPlayer().getUniqueId();
            CookingPotGui gui = activeGuis.remove(uuid);
            if (gui != null && !gui.closed) {
                gui.close();
            }
        }
    }

    private void smartMoveFromPlayerInventory(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        if (shouldPrioritizeContainer(item)) {
            moveToContainerSlot(item);
            moveToIngredientSlots(item);
        } else {
            moveToIngredientSlots(item);
            moveToContainerSlot(item);
        }
    }

    private int[] writableGuiSlots(int[] slots) {
        if (slots == null || slots.length == 0) {
            return new int[0];
        }
        List<Integer> writableSlots = new ArrayList<>();
        for (int slot : slots) {
            if (writableSlotMapping.containsKey(slot)) {
                writableSlots.add(slot);
            }
        }
        return writableSlots.stream().mapToInt(Integer::intValue).toArray();
    }

    private boolean shouldPrioritizeContainer(ItemStack item) {
        int[] writableContainerSlots = writableGuiSlots(containerSlots);
        if (writableContainerSlots.length == 0) {
            return false;
        }

        for (int slot : writableContainerSlots) {
            ItemStack containerItem = inventory.getItem(slot);
            if (containerItem != null && !containerItem.getType().isAir()) {
                return isContainerCandidate(item) && containerItem.isSimilar(item);
            }
        }

        return blockEntity.doesMealHaveContainer() && isContainerCandidate(item);
    }

    private void moveToIngredientSlots(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        int[] writableIngredientSlots = writableGuiSlots(ingredientSlots);
        Set<Integer> orderedSlots = new LinkedHashSet<>();
        for (int slot : writableIngredientSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir() && target.isSimilar(item)) {
                orderedSlots.add(slot);
            }
        }
        for (int slot : writableIngredientSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir()) {
                orderedSlots.add(slot);
            }
        }

        for (int slot : orderedSlots) {
            if (item.getAmount() <= 0) {
                return;
            }

            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir()) {
                ItemStack placed = item.clone();
                writeWritableSlot(slot, placed);
                item.setAmount(0);
                return;
            }

            if (!target.isSimilar(item)) {
                continue;
            }

            int space = target.getMaxStackSize() - target.getAmount();
            if (space <= 0) {
                continue;
            }

            int toMove = Math.min(space, item.getAmount());
            target.setAmount(target.getAmount() + toMove);
            item.setAmount(item.getAmount() - toMove);
            writeWritableSlot(slot, target);
        }
    }

    private void moveToContainerSlot(ItemStack item) {
        int[] writableContainerSlots = writableGuiSlots(containerSlots);
        if (writableContainerSlots.length == 0 || item == null || item.getType().isAir()) {
            return;
        }

        if (!isContainerCandidate(item)) {
            return;
        }

        for (int slot : writableContainerSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target == null || target.getType().isAir() || !target.isSimilar(item)) {
                continue;
            }

            int space = target.getMaxStackSize() - target.getAmount();
            if (space <= 0) {
                continue;
            }

            int toMove = Math.min(space, item.getAmount());
            target.setAmount(target.getAmount() + toMove);
            item.setAmount(item.getAmount() - toMove);
            writeWritableSlot(slot, target);
            if (item.getAmount() <= 0) {
                return;
            }
        }

        for (int slot : writableContainerSlots) {
            ItemStack target = inventory.getItem(slot);
            if (target != null && !target.getType().isAir()) {
                continue;
            }
            ItemStack placed = item.clone();
            writeWritableSlot(slot, placed);
            item.setAmount(0);
            return;
        }
    }

    private int resolveOutputTakeAmount(InventoryClickEvent event, int guiSlot) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return 0;
        }
        ItemStack currentOutput = blockEntity.getInventorySlot(entitySlot);
        if (currentOutput == null || currentOutput.getType().isAir()) {
            return 0;
        }

        @SuppressWarnings("null")
        @Nonnull
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean rightClick = event.isRightClick();
        boolean shiftClick = event.isShiftClick();

        if (shiftClick) {
            return currentOutput.getAmount();
        }

        if (cursorEmpty) {
            return rightClick ? 1 : currentOutput.getAmount();
        }

        if (!cursor.isSimilar(currentOutput)) {
            return 0;
        }

        int availableCursorSpace = cursor.getMaxStackSize() - cursor.getAmount();
        if (availableCursorSpace <= 0) {
            return 0;
        }

        return Math.min(rightClick ? 1 : currentOutput.getAmount(), availableCursorSpace);
    }

    private CookingPotBlockEntity.TakenMeal takeOutputFromSlot(int guiSlot, int requestedAmount) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return null;
        }
        return blockEntity.takeOutputSlotPortionForDelivery(entitySlot, requestedAmount);
    }

    private void applyOutputExperienceReward(Player player, ItemStack result, double experience) {
        if (experience <= 0.0D) {
            plugin.callCookingPotExperienceEvent(player, result, experience);
            return;
        }
        if (plugin.shouldDropCookingPotVanillaExperience()) {
            // The output-take click event runs on the player's region thread, but the experience orb must spawn at the pot;
            // calling world.spawn cross-region on Folia throws, so dispatch to the pot's own region (same as heat-source reads).
            plugin.scheduler().runAt(cookingPotLocation, () -> blockEntity.dropExperience(world, experience));
        }
        plugin.awardCookingPotAuraSkillsExperience(player, experience);
        plugin.callCookingPotExperienceEvent(player, result, experience);
    }

    private void deliverOutputToPlayer(InventoryClickEvent event, Player player, ItemStack meal) {
        if (event.isShiftClick()) {
            var leftover = player.getInventory().addItem(meal);
            for (var entry : leftover.entrySet()) {
                player.getWorld().dropItemNaturally(player.getLocation(), entry.getValue());
            }
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            player.setItemOnCursor(meal);
            return;
        }

        if (cursor.isSimilar(meal) && cursor.getAmount() + meal.getAmount() <= cursor.getMaxStackSize()) {
            cursor.setAmount(cursor.getAmount() + meal.getAmount());
            player.setItemOnCursor(cursor);
            return;
        }

        var leftover = player.getInventory().addItem(meal);
        for (var entry : leftover.entrySet()) {
            player.getWorld().dropItemNaturally(player.getLocation(), entry.getValue());
        }
    }

    private boolean isContainerCandidate(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (blockEntity.doesMealHaveContainer() && blockEntity.isContainerValid(item)) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null && plugin.getCookingPotRecipes().getValidContainerKeys().contains(customId)) {
            return true;
        }
        String materialKey = "minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT);
        return plugin.getCookingPotRecipes().getValidContainerKeys().contains(materialKey);
    }

}
