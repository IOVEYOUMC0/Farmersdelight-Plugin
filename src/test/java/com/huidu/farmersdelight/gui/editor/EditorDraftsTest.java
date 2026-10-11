package com.huidu.farmersdelight.gui.editor;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EditorDraftsTest {
    @AfterEach void cleanup() { EditorDrafts.clear(); }

    @Test void anOldSaveCompletionCannotDiscardANewerDraft() {
        UUID player = UUID.randomUUID();
        InventoryHolder old = new Holder(), current = new Holder();
        EditorDrafts.remember(player, "pot:a", old);
        EditorDrafts.remember(player, "pot:a", current);
        EditorDrafts.discard(player, "pot:a", old);
        assertSame(current, EditorDrafts.get(player, "pot:a"));
        EditorDrafts.discard(player, "pot:a", current);
        assertNull(EditorDrafts.get(player, "pot:a"));
    }

    @Test void evictionAndQuitOnlyAffectTheOwningPlayer() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        InventoryHolder other = new Holder();
        EditorDrafts.remember(second, "pot:0", other);
        for (int i = 0; i < 9; i++) EditorDrafts.remember(first, "pot:" + i, new Holder());
        assertNull(EditorDrafts.get(first, "pot:0"));
        assertNotNull(EditorDrafts.get(first, "pot:1"));
        EditorDrafts.clear(first);
        assertNull(EditorDrafts.get(first, "pot:8"));
        assertSame(other, EditorDrafts.get(second, "pot:0"));
    }

    private static final class Holder implements InventoryHolder {
        public Inventory getInventory() { return null; }
    }
}
