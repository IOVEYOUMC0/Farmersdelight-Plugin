package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.lang.reflect.Field;
import java.util.function.Supplier;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CookingPotExperienceSettlementTest {
    private Field blockType;
    private Object originalBlockType;

    @BeforeEach
    void installMaterialFixture() throws Exception {
        blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        originalBlockType = blockType.get(Material.STONE);
        blockType.set(Material.STONE, (Supplier<Object>) () -> null);
    }

    @AfterEach
    void restoreMaterialFixture() throws Exception {
        blockType.set(Material.STONE, originalBlockType);
    }

    @Test
    void failedTakeDoesNotDrainTheLedgerAndSuccessfulTakesCreditItOnce() throws Exception {
        RewardPot pot = new RewardPot();
        ledger(pot).put("rice", 4);
        assertNull(pot.takeOutputSlotPortionForDelivery(null, 8, 1));
        assertNull(pot.takeOutputSlotPortionForDelivery(null, -1, 1));
        assertEquals(4, ledger(pot).get("rice"));
        pot.setInventorySlot(8, new CookingPotConcurrencyTest.StubStack(2));
        assertNotNull(pot.takeOutputSlotPortionForDelivery(null, 8, 1));
        assertEquals(2.0, pot.experience);
        assertNotNull(pot.takeOutputSlotPortionForDelivery(null, 8, 1));
        assertEquals(2.0, pot.experience);
        assertEquals(2, pot.rewardCalls);
        assertTrue(ledger(pot).isEmpty());
    }

    @Test
    void automationDoesNotConsumeThePlayersAccumulatedReward() throws Exception {
        RewardPot pot = new RewardPot();
        ledger(pot).put("rice", 4);
        pot.setInventorySlot(8, new CookingPotConcurrencyTest.StubStack(2));
        assertNotNull(pot.takeOutputSlotPortionForDelivery(8, 1));
        assertEquals(4, ledger(pot).get("rice"));
        assertNotNull(pot.takeMealWithExperience(null));
        assertEquals(2.0, pot.experience);
    }

    @Test
    void concurrentClaimsCannotDuplicateExperience() throws Exception {
        RewardPot pot = new RewardPot();
        ledger(pot).put("rice", 64);
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<Double> claim = () -> pot.collectUsedRecipeExperience(id -> 0.25);
            double total = 0;
            for (var result : executor.invokeAll(List.of(claim, claim, claim, claim))) total += result.get();
            assertEquals(16.0, total);
        }
        assertEquals(0.0, pot.collectUsedRecipeExperience(id -> 0.25));
    }

    @Test
    void invalidRecipeExperienceCannotPoisonTheValidReward() throws Exception {
        RewardPot pot = new RewardPot();
        ledger(pot).putAll(Map.of("rice", 3, "invalid", 4, "negative", 5));
        assertEquals(1.5, pot.collectUsedRecipeExperience(id -> switch (id) {
            case "rice" -> 0.5;
            case "negative" -> -2;
            default -> Double.NaN;
        }));
        assertTrue(ledger(pot).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> ledger(CookingPotBlockEntity pot) throws Exception {
        Field field = CookingPotBlockEntity.class.getDeclaredField("usedRecipeTracker");
        field.setAccessible(true);
        return (Map<String, Integer>) field.get(pot);
    }

    private static final class RewardPot extends CookingPotBlockEntity {
        double experience;
        int rewardCalls;
        RewardPot() { super(null, new BlockPosKey(0, 0, 0), null, CookingPotLayout.DEFAULT, null); }
        @Override public void awardUsedRecipes(Player player, ItemStack result) {
            assertNotNull(result);
            rewardCalls++;
            experience += collectUsedRecipeExperience(id -> 0.5);
        }
    }
}
