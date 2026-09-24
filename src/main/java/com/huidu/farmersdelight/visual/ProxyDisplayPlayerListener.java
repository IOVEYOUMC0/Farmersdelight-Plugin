package com.huidu.farmersdelight.visual;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Translates the player and chunk events the proxy display manager reacts to. The manager owns the registries
 * and the sync task; keeping the Bukkit annotations here means it does not have to be a Listener
 * itself, and a change to which events are observed no longer edits the manager.
 */
final class ProxyDisplayPlayerListener implements Listener {

    private final ProxyItemDisplayManager manager;

    ProxyDisplayPlayerListener(ProxyItemDisplayManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.onPlayerJoined(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        manager.onPlayerChangedWorld(event.getPlayer());
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        manager.onPlayerTeleported(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        manager.onPlayerRespawned(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.onPlayerQuit(event.getPlayer());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.onChunkUnloaded(event.getWorld(), event.getChunk());
    }
}
