package com.huidu.farmersdelight.manager;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacketProxy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;


/**
 * Opens the handheld slot display for a feature outside this package, without exposing
 * HandheldCookingDisplay itself: an addon-visible API is not wanted here, only a sibling package
 * needs it, and tools/check_api_boundary.py keeps that boundary honest.
 *
 *
 * The handle rewrites the player's own held slot on the way out (client-side copy only, real durability is
 * never touched): Handle#update(int) shows progress as a client-only damage bar, and
 * Handle#close() drops the handler and resends the real item so the bar disappears with it.
 */
@ApiStatus.Internal
public final class HandheldDisplays {

    private HandheldDisplays() {
    }

    /** A live held-slot display. Closing it restores the real item for that viewer. */
    public interface Handle extends AutoCloseable {

        /**
         * Shows the given progress on the held item as a client-only durability bar. progress runs
         * from 0 (empty) to the duration passed to open; the copy carries
         * max_damage = duration and damage = duration - progress, so the bar grows with the
         * cooking instead of shrinking like real wear.
         */
        void update(int progress);

        /** Removes the rewrite handler and resends the real held item; safe to call twice. */
        @Override
        void close();
    }

    /**
     * Starts rewriting this player's held slot. Returns null when the player has no channel or the stacks
     * cannot be adapted, and never throws: a cosmetic bar must not break the gameplay that opened it.
     */
    @ApiStatus.Internal
    @Nullable
    public static Handle open(Player player, int slot, @Nullable ItemStack original, int duration) {
        if (player == null || original == null || original.getType().isAir()) {
            return null;
        }
        try {
            var channel = BukkitNetworkManager.instance().getChannel(player);
            if (channel == null || !channel.isOpen()) {
                return null;
            }
            HandheldCookingDisplay display =
                    new HandheldCookingDisplay(channel, slot, BukkitAdaptor.adapt(original.clone()).minecraftItem());
            return new HandleImpl(player, slot, original.clone(), duration, display);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static final class HandleImpl implements Handle {

        private final Player player;
        private final int slot;
        private final ItemStack original;
        private final int duration;
        private final HandheldCookingDisplay display;
        private boolean closed;

        private HandleImpl(Player player, int slot, ItemStack original, int duration,
                           HandheldCookingDisplay display) {
            this.player = player;
            this.slot = slot;
            this.original = original;
            this.duration = Math.max(1, duration);
            this.display = display;
        }

        @Override
        public void update(int progress) {
            if (this.closed) {
                return;
            }
            try {
                ItemStack copy = this.original.clone();
                var wrapped = BukkitAdaptor.adapt(copy);
                wrapped.setJavaComponent(DataComponentKeys.MAX_DAMAGE, this.duration);
                int damage = Math.max(0, Math.min(this.duration, this.duration - progress));
                wrapped.setJavaComponent(DataComponentKeys.DAMAGE, damage);
                Object item = wrapped.minecraftItem();
                this.display.update(item,
                        ClientboundSetPlayerInventoryPacketProxy.INSTANCE.newInstance(this.slot, item));
            } catch (RuntimeException | LinkageError ignored) {
                // A failed cosmetic update only costs the bar for this tick.
            }
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.display.close();
            } catch (RuntimeException | LinkageError ignored) {
                // The handler is gone either way; the resend below is what the client actually needs.
            }
            sendRealSlot();
        }

        private void sendRealSlot() {
            try {
                var user = BukkitAdaptor.adapt(this.player);
                if (user == null) {
                    return;
                }
                ItemStack item = this.original.getType() == Material.AIR ? new ItemStack(Material.AIR) : this.original;
                user.sendPacket(ClientboundSetPlayerInventoryPacketProxy.INSTANCE.newInstance(
                        this.slot, BukkitAdaptor.adapt(item).minecraftItem()), false);
            } catch (RuntimeException | LinkageError ignored) {
                // The next normal inventory sync restores the real item anyway.
            }
        }
    }
}
