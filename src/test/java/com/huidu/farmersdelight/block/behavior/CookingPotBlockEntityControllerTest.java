package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotBlockEntityControllerTest {

    @Test
    void nullSlotsAreTreatedAsEmptyDuringSynchronization() {
        assertTrue(CookingPotBlockEntityController.isEmptyBukkitSlot(null));
    }

    @Test
    void setItemRefreshesTheNonCeWriteBackTemplate() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/block/behavior/CookingPotBlockEntityController.java");
        int setItem = source.indexOf("public void setItem(int slot, Item item)");
        assertTrue(setItem > 0);
        int refresh = source.indexOf("refreshNonCeOriginal(slot);", setItem);
        int helper = source.indexOf("private void refreshNonCeOriginal(int slot)", refresh);
        assertTrue(refresh > setItem, "setItem must refresh the preserved non-CE stack");
        assertTrue(helper > refresh, "the refresh has to be the shared helper");
        assertTrue(source.indexOf("this.nonCeOriginal[slot] = null;", helper) > helper,
                "an empty slot must clear the preserved stack");
        assertTrue(source.indexOf("this.nonCeOriginal[slot] = bukkit == null || ItemUtils.isCustomItem(bukkit) ? null : bukkit.clone();", helper) > helper,
                "a non-empty slot must preserve the current Bukkit stack, not the previous one");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
