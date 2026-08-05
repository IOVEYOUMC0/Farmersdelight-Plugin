package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.AbstractInventoryGui;
import com.huidu.farmersdelight.gui.GuiConfig;
import com.huidu.farmersdelight.gui.RecipeViewGuiConfig;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A two-page picker for selecting a tag ingredient. The first page lists every tag the source item belongs to; left-click
 * uses that tag directly, right-click opens the second page, which lists the tag's member items for the player to
 * toggle exclusions. Layout and text come from the recipe-tag-picker-gui section of gui.yml.
 */
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
    private List<ItemStack> members = List.of();
    private List<String> availableTags = List.of();
    private final Set<String> excludedItemIds = new LinkedHashSet<>();
    private final Set<String> excludedTagIds = new LinkedHashSet<>();

    private boolean acted = false;

    public TagPickerGui(FarmersDelightPlugin plugin, Player player, RecipeViewGuiConfig.BaseConfig config,
                        ItemStack sourceItem, List<String> tagIds,
                        Consumer<RecipeIngredient> onConfirm, Runnable onCancel) {
        super(plugin, player);
        this.config = config;
        this.sourceItem = sourceItem.clone();
        this.tagIds = List.copyOf(tagIds);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.entrySlots = config.getSlotsByType("entry");
        this.inventory = plugin.getServer().createInventory(this, config.getSize(), EditorGui.coloredComponent(config.getTitle()));
    }

    public void open() {
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
                case "info" -> inventory.setItem(i, infoItem());
                default -> inventory.setItem(i, configItem("background"));
            }
        }
    }

    private ItemStack renderEntry(int dataIndex) {
        if (mode == Mode.SELECT) {
            String tagId = tagIds.get(dataIndex);
            ItemStack display = sourceItem.clone();
            display.setAmount(1);
            named(display, "&e#" + tagId);
            lore(display, tr("gui.editor.tag.select_hint"));
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

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (closed) {
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < config.getSize();
        if (!top) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                player.setItemOnCursor(cleanCopy(clicked));
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

    /**
     * Collects all tags that the member items belong to (excluding the chosen tag),
     * so the user can toggle them as excluded tags.
     */
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
        return com.huidu.farmersdelight.i18n.I18n.get(key, player);
    }

    private static void lore(ItemStack stack, String line) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.lore(List.of(Text.lore(line)));
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
