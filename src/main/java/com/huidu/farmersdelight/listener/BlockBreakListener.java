package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.text.FarmersDelightText;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntityController;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.gui.CookingPotGui;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.PresentationUtils;
import com.huidu.farmersdelight.util.Text;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
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
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlockBreakListener implements Listener {

    // Pending meals of exploded pots, keyed by the pot's block position. CraftEngine's loot table drops those
    // pots' items, and that drop cannot carry plugin-side block entity data, so the meal waits here for the
    // item the same explosion spawns at that position (see parkExplodedMeal / onItemSpawn).
    private static final Map<ExplodedPotKey, PendingExplodedMeal> pendingExplodedMeals = new ConcurrentHashMap<>();

    private record ExplodedPotKey(UUID worldId, int x, int y, int z) {
    }

    private record PendingExplodedMeal(String itemId, CookingPotBlockBehavior behavior, CompoundTag data, ItemStack meal) {
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        ImmutableBlockState state = CustomBlockUtils.getState(block);
        if (event.isCancelled() && event.getPlayer().isOp()
                && isProtectedBlock(state)
                && ProtectionCompat.canBreak(event.getPlayer(), block, featureFor(state))) {
            event.setCancelled(false);
        }
        if (event.isCancelled()) {
            return;
        }
        // Block.getLocation() already returns a fresh Location object — the extra clone() before
        // mutating add() was double-allocating per break event.
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(block.getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(block);
        if (isProtectedBlock(state)
                && !ProtectionCompat.canBreak(event.getPlayer(), block, featureFor(state))) {
            event.setCancelled(true);
            return;
        }
        if (isManagedInteractiveBlock(state)) {
            return;
        }
        cleanupBlockAt(block, state, false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        ImmutableBlockState state = event.blockState();
        if (event.isCancelled() && event.getPlayer().isOp()
                && isProtectedBlock(state)
                && ProtectionCompat.canBreak(event.getPlayer(), event.bukkitBlock(), featureFor(state))) {
            event.setCancelled(false);
        }
        if (event.isCancelled()) {
            return;
        }
        FarmersDelightPlugin.getInstance().getStoveManager()
                .invalidateBlockedAboveCache(event.bukkitBlock().getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(event.bukkitBlock());
        // Resolve the block state once and reuse it across the managed-type checks and cleanup, avoiding
        // the repeated lookups the per-call event.blockState() would otherwise perform.
        if (isProtectedBlock(state)
                && !ProtectionCompat.canBreak(event.getPlayer(), event.bukkitBlock(), featureFor(state))) {
            event.setCancelled(true);
            return;
        }
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

    private static boolean isProtectedBlock(ImmutableBlockState state) {
        return isProtectedBlockId(CustomBlockUtils.getId(state));
    }

    static boolean isProtectedBlockId(String id) {
        if (id == null) {
            return false;
        }
        int separator = id.indexOf(':');
        if (separator < 1) {
            return false;
        }
        String namespace = id.substring(0, separator);
        return "farmersdelight".equals(namespace)
                || FarmersDelightApi.get().isAddonBlockNamespace(namespace);
    }

    private static ProtectionCompat.Feature featureFor(ImmutableBlockState state) {
        if (CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class)) {
            return ProtectionCompat.Feature.COOKING_POT;
        }
        if (CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)) {
            return ProtectionCompat.Feature.SKILLET;
        }
        if (CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)) {
            return ProtectionCompat.Feature.STOVE;
        }
        if (CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)) {
            return ProtectionCompat.Feature.CUTTING_BOARD;
        }
        return null;
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

    private void syncTraysAroundSupportChange(Block block) {
        if (block == null) {
            return;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || plugin.getTrayManager() == null) {
            return;
        }
        plugin.getTrayManager().syncAroundSupportChange(block.getLocation());
    }

    private void cleanupExplodedBlockAt(Block block) {
        cleanupBlockAt(block, CustomBlockUtils.getState(block), false, true, true);
    }

    private void cleanupBlockAt(Block block, ImmutableBlockState state, boolean preserveCookingPotContents, boolean shouldDropItems) {
        cleanupBlockAt(block, state, preserveCookingPotContents, shouldDropItems, false);
    }

    private void cleanupBlockAt(Block block, ImmutableBlockState state, boolean preserveCookingPotContents, boolean shouldDropItems, boolean explosion) {
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
        CookingPotGui.closeOpenGuisAt(world, pos.x(), pos.y(), pos.z());
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
                // The loot table drops the pot item on explosion (with its survives_explosion roll, like the
                // mod); dropping it manually too would duplicate it, and suppressing that loot to build the item
                // here would drop it deterministically instead. The loot cannot carry the pending meal — it is
                // plugin-side block entity data — so it is parked for the spawned item (see onItemSpawn) while
                // the rest of the contents, which the loot table never holds, is spilled.
                parkExplodedMeal(world, pos, dropLocation, state, entity);
                dropCookingPotContents(world, dropLocation, entity);
            } else if (preserveContents) {
                dropCookingPotBaseItem(world, dropLocation, state, entity);
            } else {
                // Contents are scattered, but the pending meal is not: it was already paid for with the
                // ingredients and has not consumed its serving container, so it is packed into the pot item
                // (like the mod does in every path) instead of being deleted or spilled without its bowl.
                dropCookingPotBaseItem(world, dropLocation, state, entity, false);
                dropCookingPotContents(world, dropLocation, entity);
            }
        }
        CookingPotBlockBehavior.removeBlockEntity(world, pos);
    }

    private void dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state, CookingPotBlockEntity entity) {
        dropCookingPotBaseItem(world, dropLocation, state, entity, true);
    }

    /**
     * @param packAll true packs the whole pot (contents, progress, container); false packs only the pending
     *                meal plus the container it still owes, for the path that scatters the other slots.
     */
    private void dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state,
                                        CookingPotBlockEntity entity, boolean packAll) {
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
        // The line has to describe what the item actually carries: the full pack holds every slot, so it can
        // fall back to the output slot when no meal is waiting, while the partial pack holds the pending meal
        // only. The mod's tooltip reads its meal-display slot (index 6 — this plugin's pending slot) and never
        // the output slot.
        ItemStack meal = packAll ? entity.getPackedMealDisplayItem() : entity.getPendingOutputItem();

        Item wrapped = packMealInto(potItem, behavior, packAll
                ? CookingPotBlockEntityController.saveData(entity)
                : CookingPotBlockEntityController.savePendingMealData(entity), meal);
        net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
        ceWorld.dropItemNaturally(new WorldPosition(ceWorld, dropLocation.getX(), dropLocation.getY(), dropLocation.getZ()), wrapped);
    }

    /**
     * Writes the packed meal data, tooltip lore and fill bar into a pot item, returning the CraftEngine view
     * that owns them (the same view the drop paths hand to CraftEngine).
     */
    private Item packMealInto(ItemStack potItem, CookingPotBlockBehavior behavior, CompoundTag packedData, ItemStack meal) {
        boolean hasMeal = meal != null && !meal.getType().isAir();
        if (hasMeal) {
            applyMealLore(potItem, meal);
        }
        Item wrapped = BukkitItemManager.instance().wrap(potItem);
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
        return wrapped;
    }

    /**
     * Parks the pending meal of an exploded pot so the item CraftEngine's loot table spawns for it can pick it
     * up. The drop appears during this same tick at the pot's own position, so the entry is consumed by
     * onItemSpawn and expires one tick later if no matching item appeared — an explosion that consumed
     * the pot item loses the meal, exactly as in the mod.
     */
    private void parkExplodedMeal(World world, BlockPos pos, Location dropLocation, ImmutableBlockState state,
                                  CookingPotBlockEntity entity) {
        ItemStack meal = entity.getPendingOutputItem();
        if (meal == null || meal.getType().isAir()) {
            return;
        }
        CookingPotBlockBehavior behavior = state != null
                ? CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class)
                : CookingPotBlockBehavior.getBlockBehavior(dropLocation);
        if (behavior == null) {
            return;
        }
        ExplodedPotKey key = new ExplodedPotKey(world.getUID(), pos.x(), pos.y(), pos.z());
        pendingExplodedMeals.put(key, new PendingExplodedMeal(CustomBlockUtils.getId(state), behavior,
                CookingPotBlockEntityController.savePendingMealData(entity), meal));
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null && plugin.scheduler() != null) {
            plugin.scheduler().runLater(() -> pendingExplodedMeals.remove(key), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (pendingExplodedMeals.isEmpty()) {
            return;
        }
        Block spawnBlock = event.getLocation().getBlock();
        ExplodedPotKey key = new ExplodedPotKey(
                spawnBlock.getWorld().getUID(), spawnBlock.getX(), spawnBlock.getY(), spawnBlock.getZ());
        // Peek first: the pot's spilled contents spawn at the same position and may come first, and only the
        // pot's own item consumes the entry (which the scheduled expiry removes if nothing matched).
        PendingExplodedMeal pending = pendingExplodedMeals.get(key);
        if (pending == null) {
            return;
        }
        ItemStack spawned = event.getEntity().getItemStack();
        if (!isSamePotItem(spawned, pending.itemId())) {
            return;
        }
        pendingExplodedMeals.remove(key);
        Item updated = packMealInto(spawned, pending.behavior(), pending.data(), pending.meal());
        event.getEntity().setItemStack(updated.minecraftItem() instanceof ItemStack stack ? stack : spawned);
    }

    private static boolean isSamePotItem(ItemStack stack, String itemId) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        String customId = ItemUtils.getCustomItemId(stack);
        return itemId != null ? itemId.equals(customId) : customId == null;
    }

    private void applyMealLore(ItemStack item, ItemStack meal) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        int servings = meal.getAmount();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        // Line order and wording follow the mod's CookingPotTooltip: the servings header first, then the meal
        // itself as its item icon followed by its name. Translatable so each viewer's client renders in its own
        // locale; carries a server-resolved .fallback so packs without the lang entry still show readable text.
        // Gray applied here because the lang value is plain text without color codes.
        Component servingsLine = FarmersDelightText.translatable(
                servings == 1
                        ? "farmersdelight.tooltip.cooking_pot.single_serving"
                        : "farmersdelight.tooltip.cooking_pot.many_servings",
                servings
        ).color(NamedTextColor.GRAY);
        lore.add(servingsLine.decoration(TextDecoration.ITALIC, false));

        // Name line: the meal name (white), prefixed with the meal's inline icon glyph when its pack registers
        // one (configuration/meal_icons.yml). Built from an empty root so the name keeps the normal font instead
        // of inheriting the glyph font, which would render the letters as boxes.
        Component name = ItemUtils.getTranslatableDisplayComponentNoAnvil(meal).colorIfAbsent(NamedTextColor.WHITE);
        String glyphId = mealGlyphId(ItemUtils.getCustomItemId(meal), ItemUtils.getVanillaMaterialItemId(meal));
        if (glyphId == null) {
            lore.add(name.decoration(TextDecoration.ITALIC, false));
        } else {
            // The <image:...> tag stays in the lore instead of being resolved to the glyph character here:
            // characters are allocated while the pack is generated and move when an icon set changes, whereas
            // CraftEngine replaces this tag inside the item-lore packet with the current allocation. A pot filled
            // before an icon update therefore keeps rendering the icon its own pack declares.
            lore.add(Component.empty()
                    .append(Text.deserialize("<image:" + glyphId + ">").color(NamedTextColor.WHITE))
                    .append(Component.space())
                    .append(name)
                    .decoration(TextDecoration.ITALIC, false));
            // The icon is a 16px glyph inside a 9px tooltip line: it is drawn from 8px above the baseline down
            // to 8px below it, so the empty line after it is what keeps its lower half (and the tooltip box)
            // inside the tooltip. Dropping it clips the icon against the line below.
            lore.add(Component.empty());
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /**
     * The inline icon a meal's own pack registers for it: <namespace>:meal_<item>, with the namespace
     * taken from the meal's CraftEngine id, so addon packs ship icons for their own meals and this plugin only
     * resolves them. Vanilla meals have no CraftEngine id and cannot own a pack, so they use
     * farmersdelight:meal_<material> — this plugin owns the pot tooltip and ships those icons itself
     * (their textures are referenced from the client's own vanilla assets, not redistributed). Returns null when
     * the meal has no usable id, which the glyph lookup treats as "no icon".
     */
    static String mealGlyphId(String customItemId, String vanillaItemId) {
        if (customItemId != null && !customItemId.isEmpty()) {
            int separator = customItemId.indexOf(':');
            // Exactly one colon: CraftEngine ids are namespace:value. MMOItems ids are mmoitems:<TYPE>:<ID> and
            // have no glyph, and a namespace-less id would build an invalid key.
            if (separator <= 0 || separator != customItemId.lastIndexOf(':')
                    || separator == customItemId.length() - 1) {
                return null;
            }
            return customItemId.substring(0, separator) + ":meal_" + customItemId.substring(separator + 1);
        }
        if (vanillaItemId == null || !vanillaItemId.startsWith("minecraft:")) {
            return null;
        }
        return "farmersdelight:meal_" + vanillaItemId.substring("minecraft:".length());
    }

    private void dropCookingPotContents(World world, Location dropLocation, CookingPotBlockEntity entity) {
        var layout = entity.getLayout();
        ItemStack[] inventory = entity.getInventory();
        for (int slot = 0; slot < inventory.length; slot++) {
            // Pending meals have not consumed their serving containers. Do not drop the pending preview
            // or the container marker as items; doing so would create unpaid meals or containers.
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
