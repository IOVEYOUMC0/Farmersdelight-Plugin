package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.PresentationUtils;
import com.huidu.farmersdelight.util.Text;
import com.huidu.farmersdelight.api.util.TooltipUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BlockBreakListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        // Block.getLocation() already returns a fresh Location object — the extra clone() before
        // mutating add() was double-allocating per break event.
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(block.getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(block);
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        if (isManagedInteractiveBlock(state)) {
            return;
        }
        cleanupBlockAt(block, state, false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(event.bukkitBlock().getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(event.bukkitBlock());
        // Resolve the block state once and reuse it across the managed-type checks and cleanup, avoiding
        // the repeated lookups the per-call event.blockState() would otherwise perform.
        ImmutableBlockState state = event.blockState();
        if (!isManagedInteractiveBlock(state)) {
            return;
        }
        if (isCookingPotBlock(state)) {
            boolean shouldDropItems = event.dropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
            event.setDropItems(false);
            boolean preserveContents = FarmersDelightPlugin.getInstance().isCookingPotPackContentsOnBreak();
            cleanupBlockAt(event.bukkitBlock(), state, preserveContents, shouldDropItems);
            return;
        }
        if (!isSkilletBlock(state)) {
            cleanupBlockAt(event.bukkitBlock(), state, false, event.dropItems());
            return;
        }
        boolean shouldDropItems = event.dropItems() && event.getPlayer().getGameMode() != GameMode.CREATIVE;
        event.setDropItems(false);
        cleanupBlockAt(event.bukkitBlock(), state, false, shouldDropItems);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (var block : event.blockList()) {
            syncTraysAroundSupportChange(block);
            cleanupExplodedBlockAt(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (var block : event.blockList()) {
            syncTraysAroundSupportChange(block);
            cleanupExplodedBlockAt(block);
        }
    }

    private void syncTraysAroundSupportChange(org.bukkit.block.Block block) {
        if (block == null) {
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().syncAroundSupportChange(block.getLocation());
    }

    private void cleanupExplodedBlockAt(org.bukkit.block.Block block) {
        cleanupBlockAt(block, CustomBlockUtils.getState(block), false, true, true);
    }

    private void cleanupBlockAt(org.bukkit.block.Block block, ImmutableBlockState state, boolean preserveCookingPotContents, boolean shouldDropItems) {
        cleanupBlockAt(block, state, preserveCookingPotContents, shouldDropItems, false);
    }

    private void cleanupBlockAt(org.bukkit.block.Block block, ImmutableBlockState state, boolean preserveCookingPotContents, boolean shouldDropItems, boolean explosion) {
        World world = block.getWorld();
        Location blockLocation = block.getLocation();
        Location dropLocation = blockLocation.clone().add(0.5, 0.5, 0.5);
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        if (isCookingPotBlock(state)) {
            cleanupCookingPot(pos, world, dropLocation, state, preserveCookingPotContents, shouldDropItems, explosion);
        } else if (CookingPotBlockBehavior.getBlockEntity(world, pos) != null) {
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
        }
        cleanupSkillet(blockLocation, dropLocation, shouldDropItems, explosion);
        cleanupCuttingBoard(pos, world, dropLocation, shouldDropItems);
        cleanupStove(blockLocation, dropLocation, shouldDropItems);
    }

    private void cleanupCookingPot(BlockPos pos, World world, Location dropLocation, ImmutableBlockState state, boolean preserveContents, boolean shouldDropItems, boolean explosion) {
        // Force-close any open viewers of this pot BEFORE dropping/removing. This runs inside the
        // CustomBlockBreakEvent (and explosion) path, which removes the block entity below; the removal-lifecycle
        // close (CookingPotBlockBehavior.handleStateRemoval) would then early-return on the now-null entity and
        // never fire, leaving a cross-player viewer with a live GUI over the dropped meal (dupe).
        com.huidu.farmersdelight.gui.CookingPotGui.closeOpenGuisAt(world, pos.x(), pos.y(), pos.z());
        FarmersDelightPlugin fdPlugin = FarmersDelightPlugin.getInstance();
        if (fdPlugin != null && fdPlugin.getHandleManager() != null) {
            fdPlugin.getHandleManager().removeHandle(world, pos);
        }
        // Re-hydrate the plugin entity from parked controller data before reading it: a pot whose entity was
        // dropped on a chunk-cache unload keeps its contents only in the controller's pendingSaveData, so
        // without this the drop below reads a null entity and the contents are silently lost.
        CookingPotBlockBehavior.flushPendingControllerData(world, new BlockPosKey(pos));
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, pos);
        if (entity == null) {
            // On explosion the block's own loot table drops the pot item (like vanilla / the keg); only the
            // player-break path (where that loot is suppressed via setDropItems(false)) drops it manually.
            if (shouldDropItems && !explosion) {
                dropCookingPotBaseItem(world, dropLocation, state, null);
            }
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
            return;
        }

        if (shouldDropItems) {
            if (explosion) {
                // The loot table drops the pot item on explosion (probabilistically, matching vanilla and the
                // keg); dropping it manually too would duplicate it. Only the contents — which are not in the
                // loot table — are spilled here. Packing contents into the item is not possible without
                // suppressing that loot, so an exploded pot drops its contents loose.
                dropCookingPotContents(world, dropLocation, entity);
            } else if (preserveContents) {
                dropCookingPotBaseItem(world, dropLocation, state, entity);
            } else {
                dropCookingPotBaseItem(world, dropLocation);
                dropCookingPotContents(world, dropLocation, entity);
            }
        }
        CookingPotBlockBehavior.removeBlockEntity(world, pos);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation) {
        dropCookingPotBaseItem(world, dropLocation, null, null);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state, CookingPotBlockEntity entity) {
        String itemId = CustomBlockUtils.getId(state);
        ItemStack potItem = ItemUtils.createItem(itemId != null ? itemId : Constants.BLOCK_COOKING_POT);
        if (potItem == null || potItem.getType().isAir()) {
            return;
        }
        potItem.setAmount(1);
        if (entity == null || !entity.hasStoredContents()) {
            world.dropItemNaturally(dropLocation, potItem);
            return;
        }

        CookingPotBlockBehavior behavior = state != null
                ? CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class)
                : CookingPotBlockBehavior.getBlockBehavior(dropLocation);
        if (behavior == null) {
            world.dropItemNaturally(dropLocation, potItem);
            return;
        }

        // Display the stored meal's name + serving count as tooltip, and a fill bar scaled to the count.
        // Server-side can only drive vanilla's green->red durability bar, so the level matches but the
        // colour is the vanilla gradient (a true blue bar would require a client mod).
        ItemStack meal = entity.getPackedMealDisplayItem();
        boolean hasMeal = meal != null && !meal.getType().isAir();
        if (hasMeal) {
            applyMealLore(potItem, meal);
        }

        Item wrapped = BukkitItemManager.instance().wrap(potItem);
        CompoundTag packedData = CookingPotBlockEntityController.saveData(entity);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        if (customData == null) {
            customData = new CompoundTag();
        }
        customData.put(behavior.getCustomDataKey(), packedData);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
        if (hasMeal) {
            // max_damage = 64, damage = 64 - servings (clamped >=1 so a full meal still shows a near-full bar;
            // vanilla hides the bar at damage 0). Bar width then scales with the meal count.
            int servings = Math.max(1, Math.min(64, meal.getAmount()));
            wrapped.maxDamage(64);
            wrapped.damage(Math.max(1, 64 - servings));
            TooltipUtils.hideDurabilityLine(wrapped);
        }
        net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
        ceWorld.dropItemNaturally(new WorldPosition(ceWorld, dropLocation.getX(), dropLocation.getY(), dropLocation.getZ()), wrapped);
    }

    private void applyMealLore(ItemStack item, ItemStack meal) {
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        int servings = meal.getAmount();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        // Name line: the meal name (white), prefixed with the meal's inline icon glyph when one is registered
        // (configuration/meal_icons.yml). Built from an empty root so the name keeps the normal font instead of
        // inheriting the glyph font (farmersdelight:custom), which would render the letters as boxes.
        Component name = ItemUtils.getTranslatableDisplayComponentNoAnvil(meal).colorIfAbsent(NamedTextColor.WHITE);
        Component nameLine = name;
        String customId = ItemUtils.getCustomItemId(meal);
        if (customId != null) {
            String glyph = PresentationUtils.imageGlyph("farmersdelight:icon_"
                    + customId.substring(customId.indexOf(':') + 1));
            if (!glyph.isEmpty()) {
                nameLine = Component.empty()
                        .append(Text.deserialize(glyph).color(NamedTextColor.WHITE))
                        .append(Component.space())
                        .append(name);
            }
        }
        lore.add(Component.empty());
        lore.add(nameLine.decoration(TextDecoration.ITALIC, false));
        // Translatable so each viewer's client renders in its own locale; carries a server-resolved
        // .fallback so packs without the lang entry still show readable text. Gray applied here because
        // the lang value is plain text without color codes.
        Component servingsLine = com.huidu.farmersdelight.api.text.FarmersDelightText.translatable(
                "farmersdelight.tooltip.cooking_pot.servings",
                servings
        ).color(NamedTextColor.GRAY);
        lore.add(servingsLine.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private void dropCookingPotContents(World world, Location dropLocation, CookingPotBlockEntity entity) {
        var layout = entity.getLayout();
        ItemStack[] inventory = entity.getInventory();
        for (int slot = 0; slot < inventory.length; slot++) {
            // The pending-output slot holds a finished meal whose container has NOT been paid for yet — a
            // preview of what a supplied container would extract, not a real item (upstream FarmersDelight
            // excludes this slot from break drops). Dropping it loose, and dropping the meal-container marker
            // as a real item, would hand out meals and containers that were never consumed (dupe).
            if (layout.isPendingOutputSlot(slot)) {
                continue;
            }
            ItemStack item = inventory[slot];
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(dropLocation, item);
            }
        }
    }

    private void cleanupSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems, boolean explosion) {
        FarmersDelightPlugin.getInstance().getSkilletManager().breakSkillet(blockLocation, dropLocation, shouldDropItems, explosion);
    }

    private void cleanupCuttingBoard(BlockPos pos, World world, Location dropLocation, boolean shouldDropItems) {
        // Re-hydrate the plugin entity from parked controller data before reading it: a board whose entity
        // was dropped on a chunk-cache unload keeps its item only in the controller's pendingSaveData, so
        // without this the drop below reads a null entity and the stored item is silently lost.
        CuttingBoardBlockBehavior.flushPendingControllerData(world, new BlockPosKey(pos));
        CuttingBoardBlockEntity entity = CuttingBoardBlockBehavior.getBlockEntity(world, pos);
        if (entity == null) return;

        ItemStack storedItem = entity.getStoredItem();
        if (shouldDropItems && storedItem != null && !storedItem.getType().isAir()) {
            world.dropItemNaturally(dropLocation, storedItem);
        }
        entity.removeDisplayEntity();
        CuttingBoardBlockBehavior.removeBlockEntity(world, pos);
    }

    private void cleanupStove(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        FarmersDelightPlugin.getInstance().getStoveManager().breakStove(blockLocation, dropLocation, shouldDropItems);
    }

    private boolean isManagedInteractiveBlock(ImmutableBlockState state) {
        return isCookingPotBlock(state)
                || isSkilletBlock(state)
                || CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)
                || CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class);
    }

    private boolean isSkilletBlock(ImmutableBlockState state) {
        return CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class);
    }

    private boolean isCookingPotBlock(ImmutableBlockState state) {
        return CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class);
    }
}

