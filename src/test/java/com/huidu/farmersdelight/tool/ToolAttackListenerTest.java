package com.huidu.farmersdelight.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolAttackListenerTest {

    @Test
    void toolBreaksAtItsLastDurabilityPoint() {
        assertEquals(-1, ToolAttackListener.damageAfterUse(249, 250));
    }

    @Test
    void toolDamageAdvancesBeforeTheBreakingPoint() {
        assertEquals(11, ToolAttackListener.damageAfterUse(10, 250));
    }

}
