package com.huidu.farmersdelight.manager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.BundlePacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundBundlePacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundContainerSetContentPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.item.ItemStackProxy;

import java.util.ArrayList;
import java.util.List;

/** Owns only packet copies. Inventory and Bukkit access stay on the player's entity thread. */
final class HandheldCookingDisplay extends ChannelOutboundHandlerAdapter {
    private final Channel channel;
    private final int slot;
    private final Object original;
    private Object display;
    private volatile boolean closed;

    HandheldCookingDisplay(Channel channel, int slot, Object original) {
        this.channel = channel;
        this.slot = slot;
        this.original = original;
        // Outbound traversal starts at the tail, before CE processes item components. Bundle
        // packets are rewritten as copies too; never mutate packets shared with other viewers.
        channel.eventLoop().execute(() -> {
            if (!closed && channel.isOpen()) channel.pipeline().addLast(this);
        });
    }

    void update(Object item, Object packet) {
        channel.eventLoop().execute(() -> {
            if (closed || !channel.isOpen()) return;
            display = item;
            channel.writeAndFlush(packet);
        });
    }

    void close() {
        closed = true;
        if (!channel.isOpen()) return;
        channel.eventLoop().execute(() -> {
            display = null;
            if (channel.pipeline().context(this) != null) channel.pipeline().remove(this);
        });
    }

    @Override
    public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
        super.write(context, rewrites(closed, display != null) ? rewrite(packet) : packet, promise);
    }

    private Object rewrite(Object packet) {
        if (packet.getClass() == ClientboundSetPlayerInventoryPacketProxy.CLASS) {
            var proxy = ClientboundSetPlayerInventoryPacketProxy.INSTANCE;
            if (proxy.getSlot(packet) == slot && matches(proxy.getContents(packet))) {
                return proxy.newInstance(slot, copyDisplay());
            }
        } else if (packet.getClass() == ClientboundContainerSetSlotPacketProxy.CLASS) {
            var proxy = ClientboundContainerSetSlotPacketProxy.INSTANCE;
            int container = proxy.getContainerId(packet);
            int index = containerSlot(container, slot);
            if (index >= 0 && proxy.getSlot(packet) == index && matches(proxy.getItemStack(packet))) {
                return proxy.newInstance(container, proxy.getStateId(packet), proxy.getSlot(packet), copyDisplay());
            }
        } else if (packet.getClass() == ClientboundContainerSetContentPacketProxy.CLASS) {
            var proxy = ClientboundContainerSetContentPacketProxy.INSTANCE;
            int index = containerSlot(proxy.getContainerId(packet), slot);
            List<Object> items = proxy.getItems(packet);
            if (index >= 0 && index < items.size() && matches(items.get(index))) {
                List<Object> copy = new ArrayList<>(items);
                copy.set(index, copyDisplay());
                return proxy.newInstance(proxy.getContainerId(packet), proxy.getStateId(packet), copy, proxy.getCarriedItem(packet));
            }
        } else if (packet.getClass() == ClientboundBundlePacketProxy.CLASS) {
            // Copy the children into a list first: an Iterable may hand out a single-use iterator, and the
            // prefix rebuild below used to ask for a second one mid-iteration.
            List<Object> children = new ArrayList<>();
            for (Object child : BundlePacketProxy.INSTANCE.getPackets(packet)) {
                children.add(child);
            }
            List<Object> copy = null;
            for (int index = 0; index < children.size(); index++) {
                Object child = children.get(index);
                Object replacement = rewrite(child);
                if (copy == null && replacement != child) {
                    copy = new ArrayList<>(children.subList(0, index));
                }
                if (copy != null) copy.add(replacement);
            }
            if (copy != null) return ClientboundBundlePacketProxy.INSTANCE.newInstance(copy);
        }
        return packet;
    }

    private boolean matches(Object item) {
        return ItemStackProxy.INSTANCE.getCount(item) == 1
                && ItemStackProxy.INSTANCE.isSameItemSameComponents(original, item);
    }

    private Object copyDisplay() {
        return ItemStackProxy.INSTANCE.copy(display);
    }

    /**
     * The slot inside the given container the rewrite may touch, or -1 for "not ours".
     *
     * Two ids both mean "the player's own inventory": -2 is the player-inventory packet, and 0
     * is the player's inventory menu (hotbar 36..44, offhand 45). Both have to be rewritten. An earlier
     * version only handled -2, on the theory that container 0 was "somebody's GUI", and the client then
     * received the held skillet from the server's inventory sync alongside the cooking copy — the held model
     * flipped and the durability bar flickered while cooking. The cursor (-1) is never a hand.
     */
    static int containerSlot(int containerId, int inventorySlot) {
        if (containerId == -2) {
            return inventorySlot;
        }
        if (containerId != 0) {
            return -1;
        }
        return inventorySlot == 40 ? 45 : inventorySlot + 36;
    }

static boolean rewrites(boolean closed, boolean hasDisplay) {
        return !closed && hasDisplay;
    }
}
