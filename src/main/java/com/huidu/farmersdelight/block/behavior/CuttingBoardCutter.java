package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.sound.ToolSoundTable;
import com.huidu.farmersdelight.config.CuttingBoardSounds;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

// Encapsulates the cutting outcome: recipe matching against the stored item, per-unit output rolling
// (with Fortune bonus), result spawning, durable consumption and the player/dispenser cut paths. Kept
// separate from the block behavior so the interaction flow stays lean.
final class CuttingBoardCutter {

    // Increase each output unit's keep chance by this amount per Fortune level.
    private static final double FORTUNE_BONUS_PER_LEVEL = 0.1d;

    private final CuttingBoardToolMatcher toolMatcher;

    CuttingBoardCutter(CuttingBoardToolMatcher toolMatcher) {
        this.toolMatcher = toolMatcher;
    }

    ItemStack findMatchingTool(CuttingBoardBlockEntity blockEntity, ItemStack mainHand, ItemStack offHand,
                               boolean allowOffhandInteractions) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }

        if (matchesAnyRecipe(storedItem, mainHand)) {
            return mainHand;
        }
        if (allowOffhandInteractions && matchesAnyRecipe(storedItem, offHand)) {
            return offHand;
        }
        return null;
    }

    private boolean matchesAnyRecipe(ItemStack storedItem, ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        ItemStack singleItem = storedItem.clone();
        singleItem.setAmount(1);
        return FarmersDelightPlugin.getInstance().getCuttingBoardRecipes().matchRecipe(singleItem, tool) != null;
    }

    boolean processCutting(CuttingBoardBlockEntity blockEntity, ItemStack tool, Player player,
                           BlockFace facing, World world, BlockPosKey posKey, boolean toolIsOffhand) {
        // Wrap the whole cut in the entity monitor so two concurrent tool-right-clicks (different
        // regions) can't each grab a clone of the same stored item and both drop the recipe's result.
        // The api experience event is built inside the monitor (so it snapshots the same state it
        // always did) but handed back here to be dispatched after the monitor is released, so
        // third-party listener code never runs while this board is locked.
        ProfessionCookingExperienceEvent[] pendingExperienceEvent = new ProfessionCookingExperienceEvent[1];
        boolean cut;
        synchronized (blockEntity) {
            cut = processCuttingLocked(blockEntity, tool, player, facing, world, posKey, toolIsOffhand,
                    pendingExperienceEvent);
        }
        if (pendingExperienceEvent[0] != null) {
            Bukkit.getPluginManager().callEvent(pendingExperienceEvent[0]);
        }
        return cut;
    }

    private boolean processCuttingLocked(CuttingBoardBlockEntity blockEntity, ItemStack tool, Player player,
                                         BlockFace facing, World world, BlockPosKey posKey, boolean toolIsOffhand,
                                         ProfessionCookingExperienceEvent[] pendingExperienceEvent) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null) {
            return false;
        }

        ItemStack recipeInput = storedItem.clone();
        recipeInput.setAmount(1);
        CuttingBoardRecipe recipe = FarmersDelightPlugin.getInstance().getCuttingBoardRecipes()
                .matchRecipe(recipeInput, tool);
        if (recipe == null) {
            return false;
        }

        Location location = player.getLocation();
        int fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);
        // Fortune raises the chance of keeping each unit without exceeding the configured result count.
        double fortuneBonus = FORTUNE_BONUS_PER_LEVEL * fortuneLevel;

        ItemStack firstResult = null;
        boolean hasPossibleResult = false;
        for (CuttingBoardRecipe.ResultEntry resultEntry : recipe.getResults()) {
            ItemStack configuredResult = resultEntry.item();
            if (configuredResult == null || configuredResult.getType().isAir() || configuredResult.getAmount() <= 0) {
                continue;
            }
            hasPossibleResult = true;

            // Roll separately for each output unit. Per-entry rolls would allow only all-or-nothing results
            // and prevent Fortune from increasing the retained portion of a stack.
            int outputAmount = configuredResult.getAmount();
            for (int roll = 0; roll < configuredResult.getAmount(); roll++) {
                if (ThreadLocalRandom.current().nextDouble() > resultEntry.chance() + fortuneBonus) {
                    outputAmount--;
                }
            }
            if (outputAmount <= 0) {
                continue;
            }

            ItemStack result = configuredResult.clone();
            result.setAmount(outputAmount);
            if (firstResult == null) {
                firstResult = result.clone();
            }
            spawnItemEntity(world, posKey, result, facing);
        }

        if (!hasPossibleResult) {
            debug("recipe=" + recipe.getId()
                    + " matched input=" + formatItem(storedItem)
                    + " tool=" + formatItem(tool)
                    + " but produced no output");
            player.sendActionBar(I18n.getComponent("messages.cutting_board.no_output", player));
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            return true;
        }

        // Constructed here, dispatched by processCutting once the monitor is released. The event
        // clones its result on construction, so it still carries the pre-decrement stored item — the
        // same snapshot the immediate call took.
        pendingExperienceEvent[0] = new ProfessionCookingExperienceEvent(
                player.getUniqueId(),
                player.getName(),
                "cutting_board",
                firstResult != null ? firstResult : storedItem,
                0.0f,
                posKey.toLocation(world)
        );

        playCuttingFeedback(world, posKey, storedItem, recipe, tool);
        if (toolIsOffhand) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            ToolAttackListener.consumeDurability(tool, player.getLocation());
        }

        if (storedItem.getAmount() > 1) {
            storedItem.setAmount(storedItem.getAmount() - 1);
            blockEntity.setStoredItem(storedItem, world, posKey, facing);
            CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
        } else {
            blockEntity.clearItem();
            CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        var advancementManager = plugin.getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "use_cutting_board");
        }

        return true;
    }

    boolean tryDispenserCut(World world, BlockPosKey posKey, BlockFace facing, ItemStack tool) {
        if (world == null || posKey == null || tool == null || tool.getType().isAir()) {
            return false;
        }
        CuttingBoardBlockEntity blockEntity = CuttingBoardBlockBehavior.getBlockEntity(world, posKey);
        if (blockEntity == null) {
            return false;
        }
        // Same monitor the player cut takes, so a dispenser and a player can't both grab a clone of the same
        // stored item and each drop the recipe result.
        synchronized (blockEntity) {
            ItemStack storedItem = blockEntity.getStoredItem();
            if (storedItem == null || storedItem.getType().isAir()) {
                return false;
            }
            ItemStack recipeInput = storedItem.clone();
            recipeInput.setAmount(1);
            CuttingBoardRecipe recipe = FarmersDelightPlugin.getInstance().getCuttingBoardRecipes()
                    .matchRecipe(recipeInput, tool);
            if (recipe == null) {
                return false;
            }

            // Output rolling mirrors processCuttingLocked (one roll per output unit; Fortune raises the keep
            // chance). Kept as its own path so the dispenser cut carries none of the player-side effects
            // (action bar, swing, advancement, profession experience) that the manual cut adds.
            double fortuneBonus = FORTUNE_BONUS_PER_LEVEL
                    * tool.getEnchantmentLevel(Enchantment.FORTUNE);
            boolean hasPossibleResult = false;
            for (CuttingBoardRecipe.ResultEntry resultEntry : recipe.getResults()) {
                ItemStack configuredResult = resultEntry.item();
                if (configuredResult == null || configuredResult.getType().isAir() || configuredResult.getAmount() <= 0) {
                    continue;
                }
                hasPossibleResult = true;
                int outputAmount = configuredResult.getAmount();
                for (int roll = 0; roll < configuredResult.getAmount(); roll++) {
                    if (ThreadLocalRandom.current().nextDouble() > resultEntry.chance() + fortuneBonus) {
                        outputAmount--;
                    }
                }
                if (outputAmount <= 0) {
                    continue;
                }
                ItemStack result = configuredResult.clone();
                result.setAmount(outputAmount);
                spawnItemEntity(world, posKey, result, facing);
            }

            Location effectLocation = posKey.toLocation(world).add(0.5, 0.5, 0.5);
            if (!hasPossibleResult) {
                world.playSound(effectLocation, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return true;
            }

            playCuttingFeedback(world, posKey, storedItem, recipe, tool);

            // A dispenser has no creative exemption, so its tool always takes durability, exactly like a
            // survival player's. A broken tool is emptied; the caller then clears the dispenser slot.
            ToolAttackListener.consumeDurability(tool, effectLocation);

            if (storedItem.getAmount() > 1) {
                storedItem.setAmount(storedItem.getAmount() - 1);
                blockEntity.setStoredItem(storedItem, world, posKey, facing);
                CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
            } else {
                blockEntity.clearItem();
                CuttingBoardBlockBehavior.saveBlockEntityData(world, posKey);
            }
            return true;
        }
    }

    private void playCuttingFeedback(World world, BlockPosKey posKey, ItemStack storedItem,
                                     CuttingBoardRecipe recipe, ItemStack tool) {
        if (world == null || posKey == null) {
            return;
        }

        Location effectLocation = posKey.toLocation(world).add(0.5, 0.1, 0.5);
        // Priority: the recipe's own sound, then the tool table (shears shear, knives cut), then the
        // configured fallback. A recipe that names a sound always wins, so this only fills the gap.
        String recipeSound = recipe.getSound();
        if (recipeSound != null && !recipeSound.isBlank()) {
            SoundUtils.play(world, effectLocation, recipeSound, Sound.BLOCK_WOOD_BREAK, 1.0f, 1.0f);
        } else {
            CuttingBoardSounds sounds = FarmersDelightPlugin.getInstance().getCuttingBoardSounds();
            ToolSoundTable.Entry entry = sounds.resolve(tool);
            SoundUtils.play(world, effectLocation, entry.soundKey(), Sound.BLOCK_WOOD_BREAK,
                    entry.volume(), entry.pitch());
        }
        world.spawnParticle(Particle.ITEM, effectLocation, 5, 0.1, 0.1, 0.1, 0.0, storedItem);
    }

    private void spawnItemEntity(World world, BlockPosKey posKey, ItemStack item, BlockFace facing) {
        if (world == null) {
            return;
        }

        BlockFace ejectFace = getCounterClockWise(facing);
        double offsetX = ejectFace.getModX() * 0.2;
        double offsetZ = ejectFace.getModZ() * 0.2;

        Location location = new Location(world,
                posKey.x() + 0.5 + offsetX,
                posKey.y() + 0.2,
                posKey.z() + 0.5 + offsetZ);

        int remaining = item.getAmount();
        int maxStackSize = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            ItemStack droppedStack = item.clone();
            droppedStack.setAmount(Math.min(remaining, maxStackSize));
            remaining -= droppedStack.getAmount();

            Item droppedItem = world.dropItem(location, droppedStack);
            droppedItem.setVelocity(new Vector(
                    ejectFace.getModX() * 0.2,
                    0.0,
                    ejectFace.getModZ() * 0.2
            ));
        }
    }

    private BlockFace getCounterClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private void debug(String message) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.isDebugEnabled("interact")) {
            plugin.getLogger().info(I18n.formatConsole("debug.cutting_board", "message", message));
        }
    }

    private String formatItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "air";
        }
        String customId = ItemUtils.getCustomItemId(item);
        return customId != null ? customId + " x" + item.getAmount() : item.getType().name() + " x" + item.getAmount();
    }
}
