package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CookingPotBlockEntityControllerTest {

    @Test
    void nullSlotsAreTreatedAsEmptyDuringSynchronization() {
        assertTrue(CookingPotBlockEntityController.isEmptyBukkitSlot(null));
    }
}
