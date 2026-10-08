package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class CookingPotGui extends AbstractInventoryGui {

    static final Map<UUID, CookingPotGui> activeGuis = new ConcurrentHashMap<>();
    private static final Pattern SHIFT_TAG_PATTERN = Pattern.compile("<shift:([+-]?\\d+)>");
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("<image:([a-z0-9_./-]+:[a-z0-9_./-]+)>");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final CookingPotBlockEntity blockEntity;
    // Injectable region seam for the pot's store writes; built from the plugin on first use.
    private volatile PotRegionDispatch potRegion;

    PotRegionDispatch potRegion() {
        PotRegionDispatch current = potRegion;
        if (current == null) {
            current = PotRegionDispatch.production(plugin);
            potRegion = current;
        }
        return current;
    }

    /** Test seam: replaces the region dispatch used by the store writes. */
    void setPotRegion(PotRegionDispatch dispatch) {
        this.potRegion = dispatch;
    }
    private final CookingPotBlockBehavior blockBehavior;
    private final GuiConfig config;
    private final CookingPotItemDistributor itemDistributor;
    private final CookingPotOutputTaker outputTaker;

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
    private final Map<String, String> reusablePlaceholders = new HashMap<>();
    private Boolean cachedHeatState;
    private int cachedProgressPercent = -1;
    private int cachedRemainingSeconds = -1;
    private final Map<Integer, ItemStack> cachedDisplayItems = new HashMap<>();
    private ItemStack cachedPendingContainer;
    // Inventory version seen at last input-slot rescan; skip rescan if unchanged.
    private long lastSeenInventoryVersion = Long.MIN_VALUE;
    // Tracks which writable GUI slots have been mutated by a click/drag handler since the last
    // syncToBlockEntity. Sync only writes back these slots — so an unchanged GUI slot can't clobber
    // a concurrent cook tick that mutated the corresponding entity slot in the same window
    // (multi-viewer dup vector).
    private final Set<Integer> dirtyWritableSlots = ConcurrentHashMap.newKeySet();

    public CookingPotGui(FarmersDelightPlugin plugin, CookingPotBlockEntity blockEntity,
                         CookingPotBlockBehavior blockBehavior, World world) {
        this(plugin, blockEntity, blockBehavior, world, null);
    }

    public CookingPotGui(FarmersDelightPlugin plugin, CookingPotBlockEntity blockEntity,
                         CookingPotBlockBehavior blockBehavior, World world, Location cookingPotLocation) {
        super(plugin, null);
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
        this.itemDistributor = new CookingPotItemDistributor(blockEntity, plugin, inventory,
                writableSlotMapping, ingredientSlots, containerSlots, this::writeWritableSlot);
        this.outputTaker = new CookingPotOutputTaker(blockEntity, slotMapping);
    }

    @Override
    protected void onTick() {
        if (!closed) tick();
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
        this.player = player;
        this.playerId = player.getUniqueId();
        if (!potRegion().isOwned(potLocation())) return;
        doOpen(this::refreshInventory);
    }

    private void tick() {
        if (closed || !ensureOwner()) return;

        // The player must still share the pot's owner before world or inventory access.
        refreshHeatStateOnRegion();

        blockEntity.tryMovePendingToOutput();
        updateDisplayItems();
    }

    private void refreshHeatStateOnRegion() {
        if (world == null || blockBehavior == null) {
            return;
        }
        potRegion().runAt(potLocation(), () -> {
            if (!closed && world != null && blockBehavior != null) {
                blockEntity.setHasHeatSource(blockBehavior.checkHeatSource(blockEntity.getPos(), world));
            }
        });
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
        return replaceShiftTags(text, plugin.getCraftEngine().fontManager()::createMiniMessageOffsets);
    }

    // Package-private so the offline test can drive the real loop with a stub offset renderer. A tag whose
    // digits do not fit an int keeps its place in the output while its neighbours are still replaced.
    static String replaceShiftTags(String text, IntFunction<String> offsetRenderer) {
        Matcher matcher = SHIFT_TAG_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            Integer offset = parseShiftOffset(matcher.group(1));
            if (offset == null) {
                // Digits that do not fit an int: leave the matched token untouched. Skipping the match
                // without calling appendReplacement is what keeps it in the output — the matcher only
                // copies text up to the position the last replacement consumed.
                continue;
            }
            String replacement = offsetRenderer.apply(offset);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    // The pixel offset a <shift:N> tag asks for, or null when the digits do not fit an int.
    // A null result leaves the tag verbatim in the title instead of aborting it: the pattern accepts any
    // number of digits, this runs while a GUI title is built (including from the constructor), and an
    // oversized value in gui.yml must not make the pot GUI fail to open. Every value that does fit keeps
    // its sign and magnitude untouched, so legal tags behave exactly as before.
    static Integer parseShiftOffset(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException outOfIntRange) {
            return null;
        }
    }

    private String replaceImageTags(String text) {
        Matcher matcher = IMAGE_TAG_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
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

    private void updateDisplayItems() {
        long version = blockEntity.getInventoryVersion();
        if (version != lastSeenInventoryVersion) {
            refreshInputSlotsFromBlockEntity();
            lastSeenInventoryVersion = version;
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
        // Hint visibility depends on the pending container; rebuilding depends on either item or container changes.
        boolean hasContainerHint = container != null && !container.getType().isAir();
        ItemStack item = blockEntity.getInventorySlot(entitySlot);
        ItemStack cached = cachedDisplayItems.get(guiSlot);
        if (!containerChanged && sameItemState(item, cached)) {
            return;
        }
        ItemStack display = cloneOrNull(item);
        if (display != null) {
            raiseDisplayStackLimit(display);
        }
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

    private void raiseDisplayStackLimit(ItemStack display) {
        int amount = display.getAmount();
        if (amount <= display.getMaxStackSize()) {
            return;
        }
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setMaxStackSize(Math.min(99, amount));
        display.setItemMeta(meta);
    }

    private ItemStack placeholderItem() {
        GuiConfig.GuiItem background = config.getItem("background");
        if (background == null) {
            return null;
        }
        return CookingPotPlaceholder.mark(background.createItem());
    }

    // Writable slots only. The buffer and output cells are display-only: they are never written back by
    // syncToBlockEntity and never read as authority by the click handlers, so updateMappedDisplaySlot is
    // their sole owner. Refreshing them from the raw entity item here would overwrite that owner's work
    // with a plain clone — stripping the pending-container hint lore and the invisible background
    // placeholder — and, because cachedDisplayItems would still match the entity item, nothing would
    // rebuild them until the underlying item changed again.
    private void refreshInputSlotsFromBlockEntity() {
        for (Map.Entry<Integer, Integer> entry : writableSlotMapping.entrySet()) {
            int guiSlot = entry.getKey();
            int entitySlot = entry.getValue();

            // A slot still waiting for write-back is authoritative on the GUI mirror side: syncToBlockEntity
            // has not published the value the player just put there, so the entity still holds the older one
            // and must not overwrite it. The slot stays in the dirty set until that write succeeds, which is
            // also what keeps a failed write retryable instead of silently dropping the mirror's value.
            if (dirtyWritableSlots.contains(guiSlot)) {
                continue;
            }

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

    private boolean sameItemState(@Nullable ItemStack first, @Nullable ItemStack second) {
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

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        List<Component> lore = meta.lore();
        if (lore == null) {
            lore = new ArrayList<>();
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
        List<Component> containerLore = container.getItemMeta() == null ? null : container.getItemMeta().lore();
        if (containerLore != null && !containerLore.isEmpty()) {
            lore.addAll(containerLore);
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }


    private void writeWritableSlot(int rawSlot, ItemStack item) {
        inventory.setItem(rawSlot, item);
        if (writableSlotMapping.containsKey(rawSlot)) {
            dirtyWritableSlots.add(rawSlot);
        }
    }

    static boolean isDoubleClickPlacement(InventoryAction action, ClickType click,
                                          boolean clickedTop, boolean cursorHasItem) {
        return action == InventoryAction.NOTHING && click == ClickType.DOUBLE_CLICK
                && clickedTop && cursorHasItem;
    }

    private boolean ensureOwner() {
        if (potRegion().isOwned(potLocation())) return true;
        close();
        closeViewerInventory(player, inventory);
        return false;
    }

    private void syncToBlockEntity() {
        if (!potRegion().isOwned(potLocation())) {
            throw new IllegalStateException("Cooking pot edits require the container owner");
        }
        syncToBlockEntityNow();
    }

    /**
     * The store half of the sync: the slots the player mutated, written under the entity's inventory lock, the
     * pending meal flushed, and the ticker bookkeeping. Runs on the region that owns the pot.
     */
    private void syncToBlockEntityNow() {
        // Only write the slots the player actively mutated since the last sync. Skipping clean slots
        // is what prevents the GUI's pre-modification snapshot from clobbering cook-tick mutations
        // (e.g. ingredients consumed / result deposited) that happened during the click handler.
        if (!dirtyWritableSlots.isEmpty()) {
            // Mutual exclusion boundary between the GUI mirror and the cook tick. The mirror belongs to the
            // viewer's region while the tick runs on the pot's region, so the read of the mirror and the write
            // to the entity are taken under the entity's inventory lock, and the mirror is re-read inside it:
            // a slot the tick consumes before the lock is granted can then no longer be handed back through a
            // value read before it. setInventorySlot acquires the same reentrant monitor, so nesting is safe.
            // tryMovePendingToOutput and the tick-manager bookkeeping below stay outside the lock.
            blockEntity.withInventoryLock(() -> {
                for (int guiSlot : dirtyWritableSlots) {
                    Integer entitySlot = writableSlotMapping.get(guiSlot);
                    if (entitySlot == null) continue;
                    ItemStack item = inventory.getItem(guiSlot);
                    blockEntity.setInventorySlot(entitySlot, cloneOrNull(item));
                }
                dirtyWritableSlots.clear();
            });
        }
        blockEntity.tryMovePendingToOutput();
        if (world != null && blockEntity.getPosKey() != null) {
            TickManager tickManager = plugin.getTickManager();
            if (tickManager != null) {
                // Matching TickManager's tick: a pot with progress still to decay stays active, so closing the
                // GUI after stripping the ingredients does not freeze the bar where it stopped.
                if (blockEntity.hasStoredContents() || blockEntity.getCookingProgress() > 0) {
                    tickManager.markActive(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                } else {
                    tickManager.markInactive(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                }
            }
            // Heat-source detection reads the pot block, so it is dispatched to the pot's region via
            // refreshHeatStateOnRegion() (called from the sync/tick path) rather than read here,
            // because this may run on the viewer's thread.
        }
    }

    /** The pot's location, or null when its world or position can no longer be resolved. */
    private Location potLocation() {
        if (world == null || blockEntity.getPosKey() == null) {
            return null;
        }
        var position = blockEntity.getPos();
        return new Location(world, position.x(), position.y(), position.z());
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            super.close();
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("gui_runtime.unregister_tick_failed", "error", e.getMessage()));
        }
    }

    void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed || !ensureOwner()) {
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
        if (isDoubleClickPlacement(action, click, clickedTop,
                event.getCursor() != null && !event.getCursor().getType().isAir())
                && isPlayerInputSlot(rawSlot)) {
            event.setCancelled(true);
            handleTopInventoryInteraction(event);
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
            player.getScheduler().run(plugin, task -> {
                if (closed || player.getOpenInventory().getTopInventory() != inventory || !ensureOwner()) return;
                close();
                player.closeInventory();
                new RecipeViewGui(plugin, player, true, cookingPotLocation).open(player);
            }, null);
            return;
        }

        if (config.isOutputSlot(rawSlot)) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            int requestedAmount = outputTaker.resolveOutputTakeAmount(event, rawSlot);
            if (requestedAmount <= 0) {
                return;
            }

            ItemStack outputItem = outputTaker.takeOutputFromSlot(player, rawSlot, requestedAmount);
            if (outputItem != null && !outputItem.getType().isAir()) {
                outputTaker.deliverOutputToPlayer(event, player, outputItem);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                Bukkit.getPluginManager().callEvent(new FarmersDelightProduceEvent(
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
                if (ItemUtils.isContainerNestingHazard(current)) {
                    return;
                }
                // Adopt authoritative state first so the deposit stacks onto the real slot contents, not a stale
                // phantom from another viewer. Hold the block entity's inventory lock across the whole
                // read-modify-write so a concurrent cook tick (which consumes ingredients under the same lock)
                // cannot land between the refresh and the write-back and be clobbered by the pre-cook GUI value
                // — a Folia cross-region ingredient dupe.
                blockEntity.withInventoryLock(() -> {
                    refreshInputSlotsFromBlockEntity();
                    itemDistributor.smartMoveFromPlayerInventory(current);
                    event.setCurrentItem(current.getAmount() > 0 ? current : null);
                    syncToBlockEntity();
                });
                updateDisplayItems();
            }
            return;
        }

    }

    @Override
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed || !ensureOwner()) {
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
        if (ItemUtils.isContainerNestingHazard(oldCursor)) {
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

        // A cancelled InventoryDragEvent has its cursor restored to the pre-drag stack by the server AFTER
        // this handler returns (unlike a cancelled click, which keeps the handler's cursor), so the shares
        // cannot be deducted from the cursor here. Both halves — committing the shares to the pot and
        // deducting them from the cursor — are therefore deferred to the next tick and performed together
        // against the cursor as it exists then. Committing now and correcting the cursor later would mint
        // items whenever the player drops or stashes the restored stack inside that window.
        Map<Integer, Integer> requested = new LinkedHashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            if (rawSlot < 0 || rawSlot >= config.getSize() || !isPlayerInputSlot(rawSlot)) {
                continue; // only handle writable top input slots
            }
            ItemStack newItem = entry.getValue();
            if (newItem == null || newItem.getType().isAir() || !newItem.isSimilar(oldCursor)) {
                continue;
            }
            requested.put(rawSlot, newItem.getAmount());
        }
        if (requested.isEmpty()) {
            return;
        }
        ItemStack expectedCursor = oldCursor.clone();
        player.getScheduler().run(plugin,
                t -> commitDragShares(player, expectedCursor, requested, maxStack), null);
    }

    // Runs one tick after the cancelled drag, on the player's region. Places at most what the cursor still
    // holds, then removes exactly that much from it, so the pot and the cursor can never disagree.
    private void commitDragShares(Player player, ItemStack expectedCursor,
                                  Map<Integer, Integer> requested, int maxStack) {
        if (closed || player.getOpenInventory().getTopInventory() != inventory || !ensureOwner()) return;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir() || !cursor.isSimilar(expectedCursor)) {
            return; // the restored stack is gone or changed: commit nothing
        }
        int budget = cursor.getAmount();
        int[] placedTotal = {0};
        blockEntity.withInventoryLock(() -> {
            refreshInputSlotsFromBlockEntity();
            for (Map.Entry<Integer, Integer> entry : requested.entrySet()) {
                int rawSlot = entry.getKey();
                ItemStack existing = inventory.getItem(rawSlot);
                int existingAmount = (existing == null || existing.getType().isAir()) ? 0 : existing.getAmount();
                // The slot may now hold a different item (another viewer / the cook tick); only stack onto a match.
                if (existingAmount > 0 && !expectedCursor.isSimilar(existing)) {
                    continue;
                }
                int share = Math.min(entry.getValue(), maxStack) - existingAmount;
                if (share <= 0) {
                    continue;
                }
                int actual = Math.min(share, budget - placedTotal[0]);
                if (actual <= 0) {
                    break;
                }
                ItemStack placed = expectedCursor.clone();
                placed.setAmount(existingAmount + actual);
                writeWritableSlot(rawSlot, placed);
                placedTotal[0] += actual;
            }
            if (placedTotal[0] > 0) {
                syncToBlockEntity();
            }
        });

        if (placedTotal[0] <= 0) {
            return;
        }
        int remaining = budget - placedTotal[0];
        if (remaining > 0) {
            ItemStack leftover = cursor.clone();
            leftover.setAmount(remaining);
            player.setItemOnCursor(leftover);
        } else {
            player.setItemOnCursor(null);
        }
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

        // A cooking pot (or any packed container) placed as an ingredient would nest its saved NBT inside this
        // pot and let a player grow it into an NBT bomb; refuse it and leave the item on the cursor.
        if (ItemUtils.isContainerNestingHazard(event.getCursor())) {
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

        int remaining = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        ItemStack leftover = current.clone();
        leftover.setAmount(remaining);
        writeWritableSlot(rawSlot, leftover);
    }

    public static void cleanupAll() {
        for (CookingPotGui gui : activeGuis.values()) {
            if (!gui.closed) {
                gui.close();
            }
        }
        activeGuis.clear();
        GuiTickManager.cleanup();
        // Calling HandlerList.unregisterAll(plugin) on disable removes the EventDispatcher, but the
        // registrar entry must also be reset so a fresh dispatcher is re-registered on soft restart;
        // otherwise GUI clicks would no longer be cancelled (dupe/loss).
        GuiListenerRegistrar.reset(EventDispatcher.class);
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
                closeViewerInventory(player, gui.inventory);
            }
        }
    }

    public static void closeOpenGuisAt(World world, int x, int y, int z) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
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
                closeViewerInventory(player, gui.inventory);
            }
        }
    }


    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return activeGuis.get(playerId);
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
        activeGuis.put(playerId, (CookingPotGui) gui);
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
        activeGuis.remove(playerId);
    }

    @Override
    protected void ensureListenerRegistered() {
        warm(plugin);
    }

    public static void warm(FarmersDelightPlugin plugin) {
        GuiListenerRegistrar.ensureRegistered(EventDispatcher.class, EventDispatcher::new, plugin);
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

}

/** Dispatches store-only work; unavailable owners never fall back to the calling thread. */
final class PotRegionDispatch {

    private final Logger logger;
    private final Predicate<Location> ownedByCurrentRegion;
    private final BiConsumer<Location, Runnable> dispatch;

    /** Production seam: the plugin supplies the ownership query, the dispatch and the logger. */
    static PotRegionDispatch production(FarmersDelightPlugin plugin) {
        return new PotRegionDispatch(plugin.getLogger(),
                location -> {
                    World world = worldOrNull(location);
                    return world != null && plugin.scheduler().isOwnedByCurrentRegion(location);
                },
                (location, task) -> plugin.scheduler().runAt(location, task));
    }

    PotRegionDispatch(Logger logger, Predicate<Location> ownedByCurrentRegion,
                      BiConsumer<Location, Runnable> dispatch) {
        this.logger = logger;
        this.ownedByCurrentRegion = ownedByCurrentRegion;
        this.dispatch = dispatch;
    }

    /**
     * True when the location has a usable world and that world's region is the current one. A location whose
     * world is missing or already unloaded is NOT owned: claiming otherwise would write the pot's store from
     * whichever region happened to run the code.
     */
    boolean isOwned(Location location) {
        return worldOrNull(location) != null && ownedByCurrentRegion.test(location);
    }

    void runAt(Location location, Runnable task) {
        if (worldOrNull(location) == null) return;
        if (ownedByCurrentRegion.test(location)) {
            task.run();
            return;
        }
        try {
            dispatch.accept(location, task);
        } catch (RuntimeException failure) {
            log(Level.WARNING, "pot region dispatch rejected", failure);
        }
    }

    /** The location's world, or null when it is missing or has already been unloaded. */
    private static World worldOrNull(Location location) {
        if (location == null) {
            return null;
        }
        try {
            return location.getWorld();
        } catch (RuntimeException unloaded) {
            // Bukkit throws "World unloaded" once the location's weakly held world has been collected.
            return null;
        }
    }

    /** Logging must never be the reason a store write is skipped, hence the contained failure. */
    private void log(Level level, String message, Throwable failure) {
        if (logger == null) {
            return;
        }
        try {
            if (failure == null) {
                logger.log(level, message);
            } else {
                logger.log(level, message, failure);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No logger available; the fallback itself has already happened.
        }
    }
}
