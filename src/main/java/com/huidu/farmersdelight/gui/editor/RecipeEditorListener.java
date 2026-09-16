package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class RecipeEditorListener implements Listener {

    private static volatile boolean registered = false;
    private static volatile FarmersDelightPlugin registeredPlugin;
    // One-shot chat prompts (e.g. typing a tag id): uuid -> consumer running on the main thread.
    private static final Map<UUID, Consumer<String>> CHAT_PROMPTS = new ConcurrentHashMap<>();

    private RecipeEditorListener() {
    }

    public static void ensureRegistered(FarmersDelightPlugin plugin) {
        if (registered) {
            return;
        }
        synchronized (RecipeEditorListener.class) {
            if (registered) {
                return;
            }
            Bukkit.getPluginManager().registerEvents(new RecipeEditorListener(), plugin);
            registered = true;
            registeredPlugin = plugin;
        }
    }

    public static void reset() {
        registered = false;
        registeredPlugin = null;
        CHAT_PROMPTS.clear();
    }

    /** Queue a one-shot chat input for the player; the next chat message cancels itself and runs on main. */
    public static void promptChat(Player player, Consumer<String> onInput) {
        CHAT_PROMPTS.put(player.getUniqueId(), onInput);
        player.sendMessage(com.huidu.farmersdelight.i18n.I18n.get("gui.editor.tag.manual_prompt", player));
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
}
