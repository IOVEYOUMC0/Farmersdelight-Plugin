package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The basket's simple-storage GUI and its region-thread vacuum tick share one inventory. While a viewer has
 * either side open, the tick must not move items: a high-frequency redstone clock could otherwise interleave
 * the GUI click with the eject and duplicate the stack that is still shown to the player.
 */
class BasketVacuumControllerViewerGuardTest {

    @Test
    void anOpenViewerDefersTheTransfer() {
        assertTrue(BasketVacuumController.hasOpenViewer(inventory(List.of(new Object()))));
        assertFalse(BasketVacuumController.hasOpenViewer(inventory(List.of())));
        assertFalse(BasketVacuumController.hasOpenViewer(null));
    }

    @Test
    void theTickGuardsBothTheBasketAndTheFacedContainer() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/block/behavior/BasketVacuumController.java");
        assertTrue(source.contains("hasOpenViewer(inventory)"), "the basket storage must be guarded");
        assertTrue(source.contains("hasOpenViewer(target)"), "a container with an open viewer must not be written");
    }

    private static org.bukkit.inventory.Inventory inventory(List<Object> viewers) {
        return (org.bukkit.inventory.Inventory) Proxy.newProxyInstance(
                org.bukkit.inventory.Inventory.class.getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getViewers" -> viewers;
                    default -> null;
                });
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
