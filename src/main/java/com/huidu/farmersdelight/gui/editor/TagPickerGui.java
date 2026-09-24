package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class TagPickerGui extends AbstractInventoryGui implements EditorGui {

    private enum Mode { SELECT, EXCLUDE }

    private final RecipeViewGuiConfig.BaseConfig config;
    private final ItemStack sourceItem;
    private final List<String> tagIds;
    private final Consumer<RecipeIngredient> onConfirm;
    private final Runnable onCancel;
    private final List<Integer> entrySlots;

    private Mode mode = Mode.SELECT;
    private int page = 0;
    private Key chosenTag;
    private final Key openTag;
    private List<ItemStack> members = List.of();
    private List<String> availableTags = List.of();
    private final Set<String> excludedItemIds = new LinkedHashSet<>();
    private final Set<String> excludedTagIds = new LinkedHashSet<>();

    private boolean acted = false;

    public TagPickerGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                        ItemStack sourceItem, List<String> tagIds,
                        Consumer<RecipeIngredient> onConfirm, Runnable onCancel) {
        this(plugin, player, config, sourceItem, tagIds, null, onConfirm, onCancel);
    }

    public TagPickerGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                        ItemStack sourceItem, List<String> tagIds, @Nullable Key openTag,
                        Consumer<RecipeIngredient> onConfirm, Runnable onCancel) {
        super(plugin, player);
        this.config = config;
        this.sourceItem = sourceItem.clone();
        this.tagIds = List.copyOf(tagIds);
        this.openTag = openTag;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.entrySlots = config.getSlotsByType("entry");
        this.inventory = plugin.getServer().createInventory(this, config.getSize(), EditorGui.coloredComponent(config.getTitle()));
    }

    public void open() {
        // Editing an existing tag opens straight into its exclusion list.
        if (openTag != null) {
            enterExcludeMode(openTag);
        }
        doOpen(this::render);
    }

    @Override
    protected AbstractInventoryGui findExistingGui(UUID playerId) {
        return null;
    }

    @Override
    protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) {
    }

    @Override
    protected void removeFromActiveGuis(UUID playerId) {
    }

    @Override
    protected void ensureListenerRegistered() {
        RecipeEditorListener.ensureRegistered(plugin);
    }

    private int pageCount(int total) {
        int per = Math.max(1, entrySlots.size());
        return Math.max(1, (int) Math.ceil(total / (double) per));
    }

    private void render() {
        int total = mode == Mode.SELECT ? tagIds.size() : members.size() + availableTags.size();
        int per = Math.max(1, entrySlots.size());
        int start = page * per;

        for (int i = 0; i < config.getSize(); i++) {
            String type = config.getSlotType(i);
            if (type == null) {
                inventory.setItem(i, configItem("background"));
                continue;
            }
            switch (type) {
                case "entry" -> {
                    int idx = entrySlots.indexOf(i);
                    int dataIndex = start + idx;
                    inventory.setItem(i, dataIndex < total ? renderEntry(dataIndex) : configItem("background"));
                }
                case "prev" -> inventory.setItem(i, configItem("prev-page"));
                case "next" -> inventory.setItem(i, configItem("next-page"));
                case "cancel" -> inventory.setItem(i, configItem("cancel"));
                case "back" -> inventory.setItem(i, mode == Mode.EXCLUDE ? configItem("back") : configItem("background"));
                case "confirm" -> inventory.setItem(i, mode == Mode.EXCLUDE ? configItem("confirm") : configItem("background"));
                case "manual" -> inventory.setItem(i, mode == Mode.SELECT ? configItem("manual") : configItem("background"));
                case "info" -> inventory.setItem(i, infoItem());
                default -> inventory.setItem(i, configItem("background"));
            }
        }
    }

    private ItemStack renderEntry(int dataIndex) {
        if (mode == Mode.SELECT) {
            String tagId = tagIds.get(dataIndex);
            // Tags are multi-item matches, so they share the name-tag icon across the list; the lore shows
            // what the tag actually contains.
            ItemStack display = new ItemStack(Material.NAME_TAG);
            named(display, "&e#" + tagId);
            lore(display, tagLore(tagId));
            return display;
        }
        // EXCLUDE mode: items first, then tags
        if (dataIndex < members.size()) {
            ItemStack member = members.get(dataIndex).clone();
            member.setAmount(1);
            boolean excluded = excludedItemIds.contains(ItemUtils.resolveItemId(member));
            if (excluded) {
                glow(member);
                lore(member, tr("gui.editor.tag.member_excluded"));
            } else {
                lore(member, tr("gui.editor.tag.member_included"));
            }
            return member;
        }
        // Tag entry
        int tagIdx = dataIndex - members.size();
        if (tagIdx < availableTags.size()) {
            String tagId = availableTags.get(tagIdx);
            ItemStack display = new ItemStack(Material.NAME_TAG);
            named(display, "&b#" + tagId);
            boolean excluded = excludedTagIds.contains(tagId);
            if (excluded) {
                glow(display);
                lore(display, tr("gui.editor.tag.tag_excluded"));
            } else {
                lore(display, tr("gui.editor.tag.tag_included"));
            }
            return display;
        }
        return configItem("background");
    }

    // Max member names shown in a tag entry's lore before collapsing into "...N more".
    private static final int TAG_PREVIEW_LIMIT = 5;

    // Show what the tag actually contains instead of stacking the id on a copied icon.
    private List<String> tagLore(String tagId) {
        List<ItemStack> members = resolveMembers(Key.of(tagId));
        List<String> lines = new ArrayList<>();
        lines.add(tr("gui.editor.tag.select_hint"));
        if (members.isEmpty()) {
            lines.add("<gray>(<dark_gray>empty tag<gray>)");
            return lines;
        }
        lines.add("");
        for (int i = 0; i < Math.min(members.size(), TAG_PREVIEW_LIMIT); i++) {
            lines.add("<gray>• <white>" + memberName(members.get(i)));
        }
        if (members.size() > TAG_PREVIEW_LIMIT) {
            lines.add("<gray>... " + (members.size() - TAG_PREVIEW_LIMIT) + " <dark_gray>more");
        }
        return lines;
    }

    private String memberName(ItemStack stack) {
        String name = ItemUtils.getDisplayName(stack, player);
        return name == null || name.isBlank() ? stack.getType().name().toLowerCase(Locale.ROOT) : name;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (closed) {
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < config.getSize();
        if (!top) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                // Picked-up copies never leave the inventory, so dropping the cursor cancels the pickup
                // and lets the player put the item back without touching the GUI.
                player.setItemOnCursor(null);
            } else {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) {
                    player.setItemOnCursor(cleanCopy(clicked));
                }
            }
            return;
        }
        handleTopClick(raw, event.getClick());
    }

    private void handleTopClick(int slot, ClickType click) {
        String type = config.getSlotType(slot);
        if (type == null) {
            return;
        }
        int total = mode == Mode.SELECT ? tagIds.size() : members.size() + availableTags.size();
        switch (type) {
            case "entry" -> {
                int idx = entrySlots.indexOf(slot);
                int dataIndex = page * Math.max(1, entrySlots.size()) + idx;
                if (dataIndex < 0 || dataIndex >= total) {
                    return;
                }
                if (mode == Mode.SELECT) {
                    Key tag = Key.of(tagIds.get(dataIndex));
                    if (click.isRightClick()) {
                        enterExcludeMode(tag);
                    } else {
                        finish(new RecipeIngredient.Tag(tag));
                    }
                } else if (dataIndex < members.size()) {
                    // Item entry
                    String id = ItemUtils.resolveItemId(members.get(dataIndex));
                    if (id != null) {
                        if (!excludedItemIds.remove(id)) {
                            excludedItemIds.add(id);
                        }
                        render();
                    }
                } else {
                    // Tag entry
                    int tagIdx = dataIndex - members.size();
                    if (tagIdx < availableTags.size()) {
                        String tagId = availableTags.get(tagIdx);
                        if (!excludedTagIds.remove(tagId)) {
                            excludedTagIds.add(tagId);
                        }
                        render();
                    }
                }
            }
            case "prev" -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case "next" -> {
                if (page < pageCount(total) - 1) {
                    page++;
                    render();
                }
            }
            case "back" -> {
                if (mode == Mode.EXCLUDE) {
                    mode = Mode.SELECT;
                    page = 0;
                    render();
                }
            }
            case "confirm" -> {
                if (mode == Mode.EXCLUDE && chosenTag != null) {
                    Set<Key> excluded = new LinkedHashSet<>();
                    for (String id : excludedItemIds) {
                        excluded.add(Key.of(id));
                    }
                    finish(new RecipeIngredient.Tag(chosenTag, Set.copyOf(excluded), excludedTags()));
                }
            }
            case "manual" -> {
                if (mode == Mode.SELECT) {
                    startManualInput();
                }
            }
            case "cancel" -> {
                acted = true;
                super.close();
                clearCursor();
                onCancel.run();
            }
            default -> {
            }
        }
    }

    private void enterExcludeMode(Key tag) {
        chosenTag = tag;
        excludedItemIds.clear();
        excludedTagIds.clear();
        members = resolveMembers(tag);
        availableTags = resolveExcludableTags(tag);
        mode = Mode.EXCLUDE;
        page = 0;
        render();
    }

    // A tag id is "namespace:path" or a bare id; path chars match vanilla/custom tags (e.g. forge:vegetables/onion).
    private static final Pattern TAG_ID_PATTERN = Pattern.compile("^[a-z0-9_.\\-/]+(?::[a-z0-9_.\\-/]+)?$");

    // Close the picker and ask for a tag id in chat; the ingredient is confirmed on a valid input.
    private void startManualInput() {
        acted = true;
        super.close();
        // super.close() only unregisters the tick callback; the chest stays open on the client and would
        // capture keyboard focus, so the player could not type the tag id the prompt asks for.
        player.closeInventory();
        clearCursor();
        RecipeEditorListener.promptChat(player, raw -> {
            String input = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (input.equals("cancel")) {
                player.sendMessage(tr("gui.editor.tag.manual_cancelled"));
                reopenPicker();
                return;
            }
            if (!TAG_ID_PATTERN.matcher(input).matches()) {
                player.sendMessage(tr("gui.editor.tag.manual_invalid"));
                reopenPicker();
                return;
            }
            finish(new RecipeIngredient.Tag(Key.of(input)));
        });
    }

    private void reopenPicker() {
        new TagPickerGui(plugin, player, config, sourceItem, tagIds, onConfirm, onCancel).open();
    }

    private List<ItemStack> resolveMembers(Key tag) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        if (plugin.getCraftEngine() != null) {
            for (UniqueKey uniqueKey : plugin.getCraftEngine().itemManager().itemIdsByTag(tag)) {
                ItemStack stack = ItemUtils.createItem(uniqueKey.key().toString());
                if (stack != null && !stack.getType().isAir() && stack.getType() != Material.BARRIER) {
                    unique.putIfAbsent(uniqueKey.key().toString(), stack);
                }
            }
        }
        for (ItemStack stack : ItemUtils.createVanillaTagDisplayItems(tag, Set.of(), Set.of())) {
            String id = ItemUtils.resolveItemId(stack);
            if (id != null) {
                unique.putIfAbsent(id, stack);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<String> resolveExcludableTags(Key chosen) {
        Set<String> tags = new LinkedHashSet<>();
        String chosenStr = chosen.toString();
        for (ItemStack member : members) {
            for (String tagId : ItemUtils.getAllItemTagIds(member)) {
                if (!tagId.equals(chosenStr)) {
                    tags.add(tagId);
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private Set<Key> excludedTags() {
        Set<Key> tags = new LinkedHashSet<>();
        for (String id : excludedTagIds) {
            tags.add(Key.of(id));
        }
        return tags;
    }

    private void finish(RecipeIngredient ingredient) {
        acted = true;
        super.close();
        clearCursor();
        onConfirm.accept(ingredient);
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        super.close();
        clearCursor();
        if (!acted) {
            acted = true;
            plugin.scheduler().runLaterForEntity(player, onCancel, 1L);
        }
    }

    private ItemStack infoItem() {
        GuiConfig.GuiItem item = config.getItem("info");
        if (item == null) {
            return configItem("background");
        }
        return item.createItem(Map.of(
                "tag", chosenTag == null ? "-" : chosenTag.toString(),
                "excluded", String.valueOf(excludedItemIds.size()),
                "excluded_tags", String.valueOf(excludedTagIds.size())));
    }

    private ItemStack configItem(String key) {
        GuiConfig.GuiItem item = config.getItem(key);
        if (item == null) {
            item = config.getItem("background");
        }
        return item == null ? new ItemStack(Material.AIR) : item.createItem(Map.of());
    }

    private void clearCursor() {
        player.setItemOnCursor(null);
    }

    private String tr(String key) {
        return I18n.get(key, player);
    }

    private static void lore(ItemStack stack, String line) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.lore(List.of(Text.lore(line)));
            stack.setItemMeta(meta);
        }
    }

    private static void lore(ItemStack stack, List<String> lines) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.lore(Text.loreLines(lines));
            stack.setItemMeta(meta);
        }
    }

    private static void named(ItemStack stack, String name) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.name(name));
            stack.setItemMeta(meta);
        }
    }

    private static void glow(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.LOOTING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
    }

    private static ItemStack cleanCopy(ItemStack source) {
        ItemStack copy = source.clone();
        copy.setAmount(1);
        return copy;
    }
}
