package com.huidu.farmersdelight.manager;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandheldCookingDisplayTest {
    @Test
    void inventorySlotsNeverAliasTheCursorOrAnotherOpenContainer() {
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(slot + 36, HandheldCookingDisplay.containerSlot(0, slot));
            assertEquals(slot, HandheldCookingDisplay.containerSlot(-2, slot));
            assertEquals(-1, HandheldCookingDisplay.containerSlot(-1, slot));
            assertEquals(-1, HandheldCookingDisplay.containerSlot(1, slot));
        }
        assertEquals(45, HandheldCookingDisplay.containerSlot(0, 40));
        assertEquals(40, HandheldCookingDisplay.containerSlot(-2, 40));
        assertEquals(-1, HandheldCookingDisplay.containerSlot(5, 40));
    }

    @Test
    void stoppingDiscardsQueuedProgressAndRemovesTheNetworkHandler() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            var display = new HandheldCookingDisplay(channel, 2, new Object());
            channel.runPendingTasks();
            assertNotNull(channel.pipeline().context(display));
            Object untouched = new Object();
            assertTrue(channel.writeOutbound(untouched));
            assertSame(untouched, channel.readOutbound());
            display.update(new Object(), new Object());
            display.close();
            display.close();
            channel.runPendingTasks();
            assertNull(channel.readOutbound(), "A queued progress packet must not restore a dropped pan visually");
            assertNull(channel.pipeline().context(display));
            assertTrue(channel.writeOutbound(untouched));
            assertSame(untouched, channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
