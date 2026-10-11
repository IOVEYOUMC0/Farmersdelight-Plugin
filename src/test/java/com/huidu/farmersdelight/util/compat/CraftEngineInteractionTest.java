package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockHitResult;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftEngineInteractionTest {

    @Test
    void relativeClickBecomesWorldHitIncludingNegativeCoordinates() {
        BlockHitResult hit = CraftEngineAdapter.interactionHit(click(block(-17, 63, 2),
                new Vector(0.25, 0.75, 0.5)));
        assertEquals(-16.75, hit.location().x());
        assertEquals(63.75, hit.location().y());
        assertEquals(2.5, hit.location().z());
        assertEquals(-17, hit.blockPos().x());
        assertEquals(Direction.NORTH, hit.direction());
    }

    @Test
    void missingHitReservesBlockClickAndAirHasNoBlockInteraction() {
        PlayerInteractEvent blockClick = click(block(0, 64, 0), null);
        assertNull(CraftEngineAdapter.interactionHit(blockClick));
        assertTrue(CraftEngineAdapter.hasBlockInteraction(blockClick));
        PlayerInteractEvent airClick = click(null, null);
        assertNull(CraftEngineAdapter.interactionHit(airClick));
        assertFalse(CraftEngineAdapter.hasBlockInteraction(airClick));
    }

    private static PlayerInteractEvent click(Block block, Vector point) {
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
        return new PlayerInteractEvent(player, block == null ? Action.RIGHT_CLICK_AIR : Action.RIGHT_CLICK_BLOCK,
                null, block, BlockFace.NORTH, EquipmentSlot.HAND, point);
    }

    private static Block block(int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getLocation" -> new Location(null, x, y, z);
                    default -> null;
                });
    }
}
