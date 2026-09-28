package com.huidu.farmersdelight.manager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.proxy.minecraft.core.NonNullListProxy;
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
        super.write(context, closed || display == null ? packet : rewrite(packet), promise);
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
                @SuppressWarnings("unchecked")
                List<Object> copy = VersionHelper.isOrAbove1_21_5 ? new ArrayList<>(items)
                        : (List<Object>) NonNullListProxy.INSTANCE.createWithCapacity(items.size());
                if (!VersionHelper.isOrAbove1_21_5) copy.addAll(items);
                copy.set(index, copyDisplay());
                return VersionHelper.isOrAbove1_21_5
                        ? proxy.newInstance(proxy.getContainerId(packet), proxy.getStateId(packet), copy, proxy.getCarriedItem(packet))
                        : proxy.newInstance$legacy(proxy.getContainerId(packet), proxy.getStateId(packet), copy, proxy.getCarriedItem(packet));
            }
        } else if (packet.getClass() == ClientboundBundlePacketProxy.CLASS) {
            Iterable<Object> children = BundlePacketProxy.INSTANCE.getPackets(packet);
            List<Object> copy = null;
            int index = 0;
            for (Object child : children) {
                Object replacement = rewrite(child);
                if (copy == null && replacement != child) {
                    copy = new ArrayList<>();
                    var prefix = children.iterator();
                    for (int i = 0; i < index; i++) copy.add(prefix.next());
                }
                if (copy != null) copy.add(replacement);
                index++;
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

    static int containerSlot(int containerId, int inventorySlot) {
        // Open-container sessions are stopped by InventoryOpenEvent. Cursor (-1) is never a hand.
        if (containerId == -2) return inventorySlot;
        if (containerId != 0) return -1;
        return inventorySlot == 40 ? 45 : inventorySlot + 36;
    }
}
