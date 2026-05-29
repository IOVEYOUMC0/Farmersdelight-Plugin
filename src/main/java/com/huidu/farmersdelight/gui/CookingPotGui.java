package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotLayout;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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

        if (world != null && blockBehavior != null) {
            blockEntity.setHasHeatSource(blockBehavior.checkHeatSource(blockEntity.getPos(), world));
        }

        blockEntity.tryMovePendingToOutput();
        updateDisplayItems();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void refreshInventory() {
        resetDisplayCache();
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
        for (int slot : bufferSlots) inventory.setItem(slot, null);
        for (int slot : outputSlots) inventory.setItem(slot, null);

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
            refreshInputSlotsFromBlockEntity();
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

    private void updateMappedDisplaySlot(int guiSlot, boolean appendContainerHint, ItemStack container) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return;
        }
        ItemStack item = blockEntity.getInventorySlot(entitySlot);
        ItemStack cached = cachedDisplayItems.get(guiSlot);
        if (!appendContainerHint && sameItemState(item, cached)) {
            return;
        }
        ItemStack display = cloneOrNull(item);
        if (appendContainerHint && display != null) {
            appendContainerHint(display, container);
        }
        inventory.setItem(guiSlot, display);
        cachedDisplayItems.put(guiSlot, cloneOrNull(item));
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
        Component containerName = ItemUtils.getDisplayComponent(container, viewer);
        Component hintPrefix = I18n.getComponent("gui.cooking_pot.pending_container_hint_prefix", viewer);
        String rawHint = viewer != null
                ? I18n.get("gui.cooking_pot.pending_container_hint", viewer)
                : I18n.get("gui.cooking_pot.pending_container_hint");
        Component hint = rawHint.contains("{container}")
                ? componentWithInsertedItemName(rawHint, "{container}", containerName)
                : hintPrefix.append(containerName);

        lore.add(Component.empty());
        lore.add(hint.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private Component componentWithInsertedItemName(String template, String marker, Component itemName) {
        int markerIndex = template.indexOf(marker);
        if (markerIndex < 0) {
            return LEGACY.deserialize(template).append(itemName);
        }
        String before = template.substring(0, markerIndex);
        String after = template.substring(markerIndex + marker.length());
        return LEGACY.deserialize(before)
                .append(itemName)
                .append(LEGACY.deserialize(after));
    }

    private void syncToBlockEntity() {
        for (Map.Entry<Integer, Integer> entry : writableSlotMapping.entrySet()) {
            int guiSlot = entry.getKey();
            int entitySlot = entry.getValue();
            ItemStack item = inventory.getItem(guiSlot);
            blockEntity.setInventorySlot(entitySlot, cloneOrNull(item));
        }
        blockEntity.tryMovePendingToOutput();
        if (world != null && blockEntity.getPosKey() != null) {
            TickManager tickManager = plugin.getTickManager();
            if (tickManager != null) {
                if (blockEntity.hasStoredContents()) {
                    tickManager.markActive(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                } else {
                    tickManager.unregisterActiveBlock(world, blockEntity.getPosKey(), TickManager.BlockType.COOKING_POT);
                }
            }
            if (blockBehavior != null) {
                blockEntity.setHasHeatSource(blockBehavior.checkHeatSource(blockEntity.getPos(), world));
            }
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

            ItemStack meal = takeOutputFromSlot(rawSlot, requestedAmount);
            if (meal != null) {
                deliverOutputToPlayer(event, player, meal);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);

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
                smartMoveFromPlayerInventory(current);
                event.setCurrentItem(current.getAmount() > 0 ? current : null);
                syncToBlockEntity();
                updateDisplayItems();
            }
            return;
        }

        scheduleGuiSync();
    }

    void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() != this) return;

        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < config.getSize()) {
                event.setCancelled(true);
                return;
            }
        }

        scheduleGuiSync();
    }

    private void handleTopInventoryInteraction(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= config.getSize() || !isPlayerInputSlot(rawSlot)) {
            return;
        }

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
                inventory.setItem(rawSlot, slotItem.getAmount() > 0 ? slotItem : null);
                player.setItemOnCursor(taken);
            } else {
                inventory.setItem(rawSlot, null);
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
            inventory.setItem(rawSlot, placed);
            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        if (slotItem.isSimilar(cursor)) {
            int maxStack = Math.min(slotItem.getMaxStackSize(), inventory.getMaxStackSize());
            int space = maxStack - slotItem.getAmount();
            if (space <= 0) {
                inventory.setItem(rawSlot, cursor.clone());
                player.setItemOnCursor(slotItem.clone());
                syncToBlockEntity();
                updateDisplayItems();
                return;
            }

            int moved = Math.min(space, rightClick ? 1 : cursor.getAmount());
            slotItem.setAmount(slotItem.getAmount() + moved);
            cursor.setAmount(cursor.getAmount() - moved);
            inventory.setItem(rawSlot, slotItem);
            player.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            syncToBlockEntity();
            updateDisplayItems();
            return;
        }

        inventory.setItem(rawSlot, cursor.clone());
        player.setItemOnCursor(slotItem.clone());
        syncToBlockEntity();
        updateDisplayItems();
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
            inventory.setItem(rawSlot, null);
            return;
        }

        ItemStack leftover = leftovers.values().iterator().next();
        inventory.setItem(rawSlot, leftover.clone());
    }

    private void scheduleGuiSync() {
        if (closed || syncQueued) {
            return;
        }

        syncQueued = true;
        plugin.scheduler().runAt(cookingPotLocation, () -> {
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
        });
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
                player.closeInventory();
            }
        }
    }

    private void ensureListenerRegistered() {
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
            // PlayerQuit needs to iterate activeGuis directly, not holder
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
                inventory.setItem(slot, placed);
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
            inventory.setItem(slot, target);
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
            inventory.setItem(slot, target);
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
            inventory.setItem(slot, placed);
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

    private ItemStack takeOutputFromSlot(int guiSlot, int requestedAmount) {
        Integer entitySlot = slotMapping.get(guiSlot);
        if (entitySlot == null) {
            return null;
        }
        return blockEntity.takeOutputSlotPortionForDelivery(world, entitySlot, requestedAmount);
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
        String materialKey = "minecraft:" + item.getType().name().toLowerCase();
        return plugin.getCookingPotRecipes().getValidContainerKeys().contains(materialKey);
    }

}

