package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.gui.GuiListenerRegistrar;
import com.huidu.farmersdelight.i18n.I18n;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class RecipeEditorListener implements Listener {

    private static volatile FarmersDelightPlugin registeredPlugin;
    // One-shot chat prompts (e.g. typing a tag id): uuid -> consumer running on the main thread.
    private static final Map<UUID, Consumer<String>> CHAT_PROMPTS = new ConcurrentHashMap<>();

    private RecipeEditorListener() {
    }

    public static void ensureRegistered(FarmersDelightPlugin plugin) {
        GuiListenerRegistrar.ensureRegistered(RecipeEditorListener.class, RecipeEditorListener::new, plugin);
        if (plugin != null) {
            registeredPlugin = plugin;
        }
    }

    public static void reset() {
        GuiListenerRegistrar.reset(RecipeEditorListener.class);
        registeredPlugin = null;
        CHAT_PROMPTS.clear();
    }

    /** Queue a one-shot chat input for the player; the next chat message cancels itself and runs on main. */
    public static void promptChat(Player player, Consumer<String> onInput) {
        CHAT_PROMPTS.put(player.getUniqueId(), onInput);
        player.sendMessage(I18n.get("gui.editor.tag.manual_prompt", player));
    }

    public static void cancelPrompt(UUID playerId) {
        CHAT_PROMPTS.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleDrag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorGui gui) {
            gui.handleClose(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Consumer<String> prompt = CHAT_PROMPTS.remove(id);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        FarmersDelightPlugin plugin = registeredPlugin;
        if (plugin != null) {
            // Folia has no global main thread; run on the player's region (the prompt reopens their GUI).
            plugin.scheduler().runForEntity(event.getPlayer(), () -> prompt.accept(message));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CHAT_PROMPTS.remove(event.getPlayer().getUniqueId());
    }

    // The addon recipe editor view used to be handled by the core's recipe-book listener; it moved here with
    // the view itself, so that listener no longer knows about either type.

    @EventHandler(priority = EventPriority.HIGH)
    public void onEditorViewClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecipeEditorView editor)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (event.getClickedInventory() != event.getInventory()) {
            // Clicking the player's own inventory: copy item to cursor, or discard a held template.
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                player.setItemOnCursor(null);
            } else {
                ItemStack current = event.getCurrentItem();
                if (current != null && !current.getType().isAir()) {
                    ItemStack copy = current.clone();
                    copy.setAmount(1);
                    player.setItemOnCursor(copy);
                }
            }
            return;
        }
        if (editor.isEditableSlot(raw)) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType().isAir()) {
                event.getInventory().setItem(raw, null);
            } else {
                ItemStack template = cursor.clone();
                template.setAmount(1);
                event.getInventory().setItem(raw, template);
            }
        } else {
            editor.handleButton(player, raw, event.isRightClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEditorViewDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RecipeEditorView) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEditorViewDrop(PlayerDropItemEvent event) {
        // The view keeps a fabricated template copy on the cursor: pressing Q drops that copy without going
        // through InventoryClickEvent, so without this guard the template becomes a real item.
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof RecipeEditorView) {
            event.setCancelled(true);
            event.getPlayer().setItemOnCursor(null);
        }
    }

    @EventHandler
    public void onEditorViewClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof RecipeEditorView
                && event.getPlayer() instanceof Player player) {
            // Discard any template copy left on the cursor (it was never a real item).
            player.setItemOnCursor(null);
        }
    }
}
