package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.block.FarmersDelightStation;
import com.huidu.farmersdelight.api.block.SkilletSnapshot;
import com.huidu.farmersdelight.api.event.FarmersDelightStationBrokenEvent;
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
import com.huidu.farmersdelight.storage.PackedCarrierCodec;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.StationTypes;
import com.huidu.farmersdelight.util.Text;
import com.huidu.farmersdelight.util.compat.ProtectionChecks;
import com.huidu.farmersdelight.util.scheduler.RegionTasks;
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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
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

    // Handed in by the registrar instead of looked up: every handler below runs while the plugin is enabled.
    private final FarmersDelightPlugin plugin;

    public BlockBreakListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

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
        // Deliberate: an operator breaking a FarmersDelight workstation is allowed through even when another
        // plugin cancelled the event, so a mis-configured region flag or claim cannot lock operators out of
        // their own blocks. ProtectionChecks.canBreak answers true for operators by design.
        if (event.isCancelled() && event.getPlayer().isOp()
                && isProtectedBlock(state)
                && ProtectionChecks.canBreak(event.getPlayer(), block, featureFor(state))) {
            event.setCancelled(false);
        }
        if (event.isCancelled()) {
            return;
        }
        // Block.getLocation() already returns a fresh Location object — the extra clone() before
        // mutating add() was double-allocating per break event.
        plugin.getStoveManager()
                .invalidateBlockedAboveCache(block.getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(block);
        if (isProtectedBlock(state)
                && !ProtectionChecks.canBreak(event.getPlayer(), block, featureFor(state))) {
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
                && ProtectionChecks.canBreak(event.getPlayer(), event.bukkitBlock(), featureFor(state))) {
            event.setCancelled(false);
        }
        if (event.isCancelled()) {
            return;
        }
        plugin.getStoveManager()
                .invalidateBlockedAboveCache(event.bukkitBlock().getLocation().add(0, -1, 0));
        syncTraysAroundSupportChange(event.bukkitBlock());
        // Resolve the block state once and reuse it across the managed-type checks and cleanup, avoiding
        // the repeated lookups the per-call event.blockState() would otherwise perform.
        if (isProtectedBlock(state)
                && !ProtectionChecks.canBreak(event.getPlayer(), event.bukkitBlock(), featureFor(state))) {
            event.setCancelled(true);
            return;
        }
        if (!isManagedInteractiveBlock(state)) {
            return;
        }
        FarmersDelightStation station = StationTypes.of(state);
        Player breaker = event.getPlayer();
        if (breaker != null && !plugin.scheduler().isOwnedByCurrentRegion(breaker)) {
            handleCrossRegionBreak(event, state, station, breaker);
            return;
        }
        if (station != null) {
            ItemStack tool = breaker == null ? null : breaker.getInventory().getItemInMainHand();
            Bukkit.getPluginManager().callEvent(new FarmersDelightStationBrokenEvent(
                    breaker, station, event.bukkitBlock().getLocation(), tool));
        }
        boolean creative = breaker != null && breaker.getGameMode() == GameMode.CREATIVE;
        cleanupManagedBreak(event, state, creative);
    }

    private void cleanupManagedBreak(CustomBlockBreakEvent event, ImmutableBlockState state, boolean creative) {
        boolean platformDrops = event.dropItems();
        if (isCookingPotBlock(state)) {
            boolean shouldDropItems = platformDrops && !creative;
            event.setDropItems(false);
            boolean preserveContents = plugin.isCookingPotPackContentsOnBreak();
            cleanupBlockAt(event.bukkitBlock(), state, preserveContents, shouldDropItems);
            return;
        }
        if (!isSkilletBlock(state)) {
            cleanupBlockAt(event.bukkitBlock(), state, false, platformDrops);
            return;
        }
        boolean shouldDropItems = platformDrops && !creative;
        event.setDropItems(false);
        cleanupBlockAt(event.bukkitBlock(), state, false, shouldDropItems);
    }

    // Folia only: the breaker's inventory and game mode belong to the player's scheduler, so the station event
    // and the creative-gated drops settle one hop later; CraftEngine's removal callbacks have already dropped
    // the plugin-side station data by then, so the cleanup itself stays synchronous.
    private void handleCrossRegionBreak(CustomBlockBreakEvent event, ImmutableBlockState state,
                                        FarmersDelightStation station, Player breaker) {
        Block block = event.bukkitBlock();
        World world = block.getWorld();
        Location blockLocation = block.getLocation();
        boolean platformDrops = event.dropItems();
        CapturedBreakDrops creativeGatedDrops = platformDrops ? new CapturedBreakDrops() : null;
        if (isCookingPotBlock(state)) {
            event.setDropItems(false);
            boolean preserveContents = plugin.isCookingPotPackContentsOnBreak();
            cleanupBlockAt(block, state, preserveContents, creativeGatedDrops, platformDrops, false);
        } else if (isSkilletBlock(state)) {
            event.setDropItems(false);
            cleanupBlockAt(block, state, false, creativeGatedDrops, platformDrops, false);
        } else {
            cleanupBlockAt(block, state, false, platformDrops, false);
        }
        settleBrokenStationLater(world, blockLocation, station, breaker, creativeGatedDrops);
    }

    private void settleBrokenStationLater(World world, Location blockLocation, FarmersDelightStation station,
                                          Player breaker, CapturedBreakDrops creativeGatedDrops) {
        plugin.scheduler().runForEntity(breaker, () -> {
            ItemStack tool = breaker.getInventory().getItemInMainHand();
            boolean creative = breaker.getGameMode() == GameMode.CREATIVE;
            // On a Folia region border this event arrives one player-scheduler hop late.
            RegionTasks.runAtLoadedChunk(plugin.scheduler(), world,
                    blockLocation.getBlockX() >> 4, blockLocation.getBlockZ() >> 4, () -> {
                        if (!plugin.isEnabled() || !plugin.scheduler().isOwnedByCurrentRegion(blockLocation)) {
                            return;
                        }
                        if (station != null) {
                            Bukkit.getPluginManager().callEvent(new FarmersDelightStationBrokenEvent(
                                    breaker, station, blockLocation, tool));
                        }
                        if (!creative && creativeGatedDrops != null) {
                            creativeGatedDrops.flush(world, blockLocation.clone().add(0.5, 0.5, 0.5));
                        }
                    });
        });
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

    private static ProtectionChecks.Feature featureFor(ImmutableBlockState state) {
        if (CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class)) {
            return ProtectionChecks.Feature.COOKING_POT;
        }
        if (CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)) {
            return ProtectionChecks.Feature.SKILLET;
        }
        if (CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)) {
            return ProtectionChecks.Feature.STOVE;
        }
        if (CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)) {
            return ProtectionChecks.Feature.CUTTING_BOARD;
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
        if (plugin.getTrayManager() == null) {
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

    private void cleanupBlockAt(Block block, ImmutableBlockState state, boolean preserveCookingPotContents,
                                boolean shouldDropItems, boolean explosion) {
        cleanupBlockAt(block, state, preserveCookingPotContents,
                shouldDropItems ? ImmediateBreakDrops.INSTANCE : null, shouldDropItems, explosion);
    }

    /**
     * @param creativeGatedDrops sink for the cooking pot and skillet drops, which the breaker's game mode gates;
     *                           null drops nothing. Cutting board and stove drops are not creative-gated and keep
     *                           using {@code shouldDropItems}.
     */
    private void cleanupBlockAt(Block block, ImmutableBlockState state, boolean preserveCookingPotContents,
                                BreakDrops creativeGatedDrops, boolean shouldDropItems, boolean explosion) {
        World world = block.getWorld();
        Location blockLocation = block.getLocation();
        Location dropLocation = blockLocation.clone().add(0.5, 0.5, 0.5);
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        if (isCookingPotBlock(state)) {
            cleanupCookingPot(pos, world, dropLocation, state, preserveCookingPotContents, creativeGatedDrops, explosion);
        } else if (CookingPotBlockBehavior.getBlockEntity(world, pos) != null) {
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
        }
        cleanupSkillet(blockLocation, dropLocation, creativeGatedDrops, explosion);
        cleanupCuttingBoard(pos, world, dropLocation, shouldDropItems);
        cleanupStove(blockLocation, dropLocation, shouldDropItems);
    }

    private void cleanupCookingPot(BlockPos pos, World world, Location dropLocation, ImmutableBlockState state,
                                   boolean preserveContents, BreakDrops drops, boolean explosion) {
        // Force-close any open viewers of this pot BEFORE dropping/removing. This runs inside the
        // CustomBlockBreakEvent (and explosion) path, which removes the block entity below; the removal-lifecycle
        // close (CookingPotBlockBehavior.handleStateRemoval) would then early-return on the now-null entity and
        // never fire, leaving a cross-player viewer with a live GUI over the dropped meal (dupe).
        CookingPotGui.closeOpenGuisAt(world, pos.x(), pos.y(), pos.z());
        FarmersDelightPlugin fdPlugin = plugin;
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
            if (drops != null && !explosion) {
                dropCookingPotBaseItem(world, dropLocation, state, null, drops);
            }
            CookingPotBlockBehavior.removeBlockEntity(world, pos);
            return;
        }

        // Settle before packing so a recovered pot cannot award the same recipes again.
        entity.dropUsedRecipeExperience(dropLocation);
        if (drops != null) {
            if (explosion) {
                // The loot table drops the pot item on explosion (with its survives_explosion roll, like the
                // mod); dropping it manually too would duplicate it, and suppressing that loot to build the item
                // here would drop it deterministically instead. The loot cannot carry the pending meal — it is
                // plugin-side block entity data — so it is parked for the spawned item (see onItemSpawn) while
                // the rest of the contents, which the loot table never holds, is spilled.
                try {
                    parkExplodedMeal(world, pos, dropLocation, state, entity);
                } catch (Exception | LinkageError failure) {
                    reportPotPackFailure(world, dropLocation, failure);
                }
                dropCookingPotContents(world, dropLocation, entity, drops);
            } else if (preserveContents) {
                if (!dropCookingPotBaseItem(world, dropLocation, state, entity, drops)) {
                    // Packing failed: the helper dropped the plain pot item, salvage the contents too before
                    // the block entity is removed.
                    dropCookingPotContents(world, dropLocation, entity, drops);
                }
            } else {
                // Contents are scattered, but the pending meal is not: it was already paid for with the
                // ingredients and has not consumed its serving container, so it is packed into the pot item
                // instead of being deleted or spilled without its serving container.
                dropCookingPotBaseItem(world, dropLocation, state, entity, false, drops);
                dropCookingPotContents(world, dropLocation, entity, drops);
            }
        }
        CookingPotBlockBehavior.removeBlockEntity(world, pos);
    }

    private boolean dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state,
                                           CookingPotBlockEntity entity, BreakDrops drops) {
        return dropCookingPotBaseItem(world, dropLocation, state, entity, true, drops);
    }

    /**
     * @param packAll true packs the whole pot (contents, progress, container); false packs only the pending
     *                meal plus the container it still owes, for the path that scatters the other slots.
     * @return true when the item was dropped with its packed data; false when packing failed and only the
     *         plain pot item was dropped, so the caller must salvage the entity contents.
     */
    private boolean dropCookingPotBaseItem(World world, Location dropLocation, ImmutableBlockState state,
                                           CookingPotBlockEntity entity, boolean packAll, BreakDrops drops) {
        String itemId = CustomBlockUtils.getId(state);
        ItemStack potItem = ItemUtils.createItem(itemId != null ? itemId : Constants.BLOCK_COOKING_POT);
        if (potItem == null || potItem.getType().isAir()) {
            return false;
        }
        potItem.setAmount(1);
        if (entity == null || !entity.hasStoredContents()) {
            drops.drop(world, dropLocation, potItem);
            return true;
        }

        CookingPotBlockBehavior behavior = state != null
                ? CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class)
                : CookingPotBlockBehavior.getBlockBehavior(dropLocation);
        if (behavior == null) {
            drops.drop(world, dropLocation, potItem);
            return true;
        }

        // Display the stored meal's name, serving count and a fill bar scaled to the count. Server-side can only
        // drive vanilla's green->red durability bar, so the colour is the vanilla gradient.
        // The line describes what the item carries: the full pack falls back to the output slot when no meal is
        // waiting, while the partial pack reads the pending meal slot (index 6) only.
        ItemStack meal = packAll ? entity.getPackedMealDisplayItem() : entity.getPendingOutputItem();

        Item wrapped;
        try {
            wrapped = packMealInto(potItem, behavior, packAll
                    ? CookingPotBlockEntityController.saveData(entity)
                    : CookingPotBlockEntityController.savePendingMealData(entity), meal);
        } catch (Exception | LinkageError failure) {
            // Do not let a serialization failure abort the break cleanup: drop the plain pot first, then let
            // the caller spill the entity contents instead of deleting them with the block entity.
            reportPotPackFailure(world, dropLocation, failure);
            drops.drop(world, dropLocation, potItem);
            return false;
        }
        drops.dropCeItem(world, dropLocation, wrapped);
        return true;
    }

    private void reportPotPackFailure(World world, Location dropLocation, Throwable failure) {
        plugin.getLogger().warning("Could not pack a cooking pot at " + world.getName() + " "
                + dropLocation.getBlockX() + "," + dropLocation.getBlockY() + "," + dropLocation.getBlockZ()
                + "; dropping its contents instead: " + failure.getMessage());
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
        PackedCarrierCodec.write(wrapped, DataComponentKeys.CUSTOM_DATA, behavior.getCustomDataKey(), packedData);
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
     * Parks an exploded pot meal until its item drop spawns at the pot position in the same tick.
     * onItemSpawn consumes the entry; unmatched entries expire after one tick.
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
        if (plugin.scheduler() != null) {
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
        List<Component> originalLore = meta.lore();
        List<Component> lore = originalLore == null ? new ArrayList<>() : new ArrayList<>(originalLore);
        // Display the serving count, meal icon and name with a readable server fallback.
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

    private void dropCookingPotContents(World world, Location dropLocation, CookingPotBlockEntity entity,
                                        BreakDrops drops) {
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
                drops.drop(world, dropLocation, item);
            }
        }
    }

    private void cleanupSkillet(Location blockLocation, Location dropLocation, BreakDrops drops, boolean explosion) {
        if (drops == null) {
            plugin.getSkilletManager().breakSkillet(blockLocation, dropLocation, false, explosion);
            return;
        }
        if (drops.capturing()) {
            // Mirror breakSkillet's drops — the stored food and the skillet item itself — before the manager
            // removes its tracked data; the captured sink settles them on the block's region later.
            SkilletSnapshot snapshot = plugin.getSkilletManager().snapshot(blockLocation);
            if (snapshot != null) {
                ItemStack storedItem = snapshot.storedItem();
                if (storedItem != null && !storedItem.getType().isAir()) {
                    drops.drop(blockLocation.getWorld(), dropLocation, storedItem);
                }
                ItemStack skilletItem = snapshot.skilletItem();
                ItemStack skilletDrop = skilletItem != null && !skilletItem.getType().isAir()
                        ? skilletItem
                        : ItemUtils.createItem(Constants.ITEM_SKILLET);
                if (skilletDrop != null && !skilletDrop.getType().isAir()) {
                    skilletDrop.setAmount(1);
                    drops.drop(blockLocation.getWorld(), dropLocation, skilletDrop);
                }
            }
            plugin.getSkilletManager().breakSkillet(blockLocation, dropLocation, false, explosion);
            return;
        }
        plugin.getSkilletManager().breakSkillet(blockLocation, dropLocation, true, explosion);
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
        plugin.getStoveManager().breakStove(blockLocation, dropLocation, shouldDropItems);
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

    /** Where a station break puts its drops; the captured variant defers them to the block's region. */
    private interface BreakDrops {
        void drop(World world, Location dropLocation, ItemStack item);

        void dropCeItem(World world, Location dropLocation, Item item);

        default boolean capturing() {
            return false;
        }
    }

    private static final class ImmediateBreakDrops implements BreakDrops {
        private static final ImmediateBreakDrops INSTANCE = new ImmediateBreakDrops();

        @Override
        public void drop(World world, Location dropLocation, ItemStack item) {
            world.dropItemNaturally(dropLocation, item);
        }

        @Override
        public void dropCeItem(World world, Location dropLocation, Item item) {
            net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
            ceWorld.dropItemNaturally(
                    new WorldPosition(ceWorld, dropLocation.getX(), dropLocation.getY(), dropLocation.getZ()), item);
        }
    }

    private final class CapturedBreakDrops implements BreakDrops {
        private final List<ItemStack> items = new ArrayList<>();
        private final List<Item> ceItems = new ArrayList<>();

        @Override
        public boolean capturing() {
            return true;
        }

        @Override
        public void drop(World world, Location dropLocation, ItemStack item) {
            items.add(item);
        }

        @Override
        public void dropCeItem(World world, Location dropLocation, Item item) {
            ceItems.add(item);
        }

        void flush(World world, Location dropLocation) {
            if (!plugin.isEnabled() || world == null || dropLocation == null) {
                return;
            }
            for (ItemStack item : items) {
                world.dropItemNaturally(dropLocation, item);
            }
            if (ceItems.isEmpty()) {
                return;
            }
            net.momirealms.craftengine.core.world.World ceWorld = BukkitAdaptor.adapt(world);
            for (Item item : ceItems) {
                ceWorld.dropItemNaturally(
                        new WorldPosition(ceWorld, dropLocation.getX(), dropLocation.getY(), dropLocation.getZ()), item);
            }
        }
    }
}
