package com.huidu.farmersdelight.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiPagingTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void pageCountAlwaysHasAtLeastOnePage() {
        assertEquals(1, GuiPaging.pageCount(0, 10));
        assertEquals(1, GuiPaging.pageCount(10, 10));
        assertEquals(2, GuiPaging.pageCount(11, 10));
        assertEquals(1, GuiPaging.pageCount(6, 0));
    }

    @Test
    void clampKeepsThePageInsideTheList() {
        assertEquals(0, GuiPaging.clampPage(-5, 3));
        assertEquals(2, GuiPaging.clampPage(9, 3));
        assertEquals(1, GuiPaging.clampPage(1, 3));
        assertEquals(0, GuiPaging.clampPage(1, 0));
    }

    @Test
    void startIndexUsesTheClampedPageSize() {
        assertEquals(20, GuiPaging.startIndex(2, 10));
        assertEquals(0, GuiPaging.startIndex(-1, 10));
        assertEquals(1, GuiPaging.startIndex(1, 0));
    }

    @Test
    void titlePlaceholdersAreSubstitutedAndMissingOnesStay() {
        assertEquals("Page 2 of 5", plain(GuiPaging.withPageInfo(Component.text("Page {page} of {total}"), 2, 5)));
        assertEquals("Plain", plain(GuiPaging.withPageInfo(Component.text("Plain"), 2, 5)));
    }
}
