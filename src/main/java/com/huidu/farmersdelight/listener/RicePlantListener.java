package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.WorldGuardCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("unchecked")
public class RicePlantListener implements Listener {

    private static final String RICE_BLOCK_ID = Constants.BLOCK_RICE;
    private static final String RICE_ITEM_ID = Constants.ITEM_RICE;
    private static final String RICE_PANICLE_ID = Constants.ITEM_RICE_PANICLE;
    private static final String WILD_RICE_BLOCK_ID = Constants.BLOCK_WILD_RICE;
    private static final String WILD_RICE_ITEM_ID = Constants.ITEM_WILD_RICE;
    private static final Key RICE_BLOCK_KEY = Key.of(RICE_BLOCK_ID);

    private final FarmersDelightPlugin plugin;
    private final Set<String> pendingRiceStabilizations = new HashSet<>();

    public RicePlantListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRicePhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (isWildRiceBlock(state)) {
            if (canWildRiceStay(block, state)) {
                return;
            }

            scheduleWildRiceValidation(block);
            return;
        }

        if (!isRiceBlock(state)) {
            return;
        }

        if (canRiceStay(block, state)) {
            syncSupportingState(block, state);
            return;
        }

        scheduleRiceValidation(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiceBreak(CustomBlockBreakEvent event) {
        Block brokenBlock = event.bukkitBlock();
        ImmutableBlockState brokenState = event.blockState();

        if (isRiceBlock(brokenState)) {
            Object half = getPropertyValue(brokenState, "half");
            if (isUpperHalfValue(half)) {
                Block lowerBlock = brokenBlock.getWorld().getBlockAt(
                        brokenBlock.getX(), brokenBlock.getY() - 1, brokenBlock.getZ());
                plugin.scheduler().runAt(lowerBlock.getLocation(), () -> resetLowerAfterUpperBreak(lowerBlock));
            } else {
                plugin.scheduler().runAt(brokenBlock.getLocation(), () -> restoreRiceCarrierBlock(brokenBlock, half));
            }
            return;
        }

        if (isWildRiceBlock(brokenState)) {
            String half = getPropertyString(brokenState, "half");
            plugin.scheduler().runAt(brokenBlock.getLocation(), () -> restoreWildRiceCarrierBlock(brokenBlock, half));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlantRice(PlayerInteractEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, hand);
        if (!isRiceSeed(item)) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        if (isBlockingInteractable(clickedBlock, player)) {
            return;
        }

        Location plantLocation = findPlantLocation(clickedBlock);
        if (plantLocation == null) {
            sendInvalidPlacementMessage(player, event, clickedBlock);
            event.setCancelled(true);
            return;
        }
        if (!WorldGuardCompat.canBuild(player, plantLocation)) {
            event.setCancelled(true);
            return;
        }

        if (!RiceCropRules.canPlantRiceAt(plantLocation.getBlock(), RICE_BLOCK_KEY)) {
            sendInvalidPlacementMessage(player, event, clickedBlock);
            event.setCancelled(true);
            return;
        }

        if (!placeRice(plantLocation)) {
            event.setCancelled(true);
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeHeldItem(player, hand, item);
        }

        playPlacementFeedback(player, hand, plantLocation);

        AdvancementManager advancementManager = plugin.getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "plant_rice");
            advancementManager.awardCriteria(player, "plant_all_crops", "rice");
        }

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlantWildRice(PlayerInteractEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = getHeldItem(player, hand);
        if (!isWildRiceItem(item)) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || isBlockingInteractable(clickedBlock, player)) {
            return;
        }

        Location plantLocation = findPlantLocation(clickedBlock);
        if (plantLocation != null && canPlantWildRiceAt(plantLocation.getBlock())) {
            if (!WorldGuardCompat.canBuild(player, plantLocation)) {
                event.setCancelled(true);
                return;
            }
            if (!placeWildRice(plantLocation)) {
                event.setCancelled(true);
                return;
            }

            if (player.getGameMode() != GameMode.CREATIVE) {
                consumeHeldItem(player, hand, item);
            }

            playPlacementFeedback(player, hand, plantLocation);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
            return;
        }

        sendInvalidPlacementMessage(player, event, clickedBlock);
        event.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    private boolean isBlockingInteractable(Block clickedBlock, Player player) {
        return clickedBlock.getType() != Material.WATER
                && !player.isSneaking()
                && clickedBlock.getType().isInteractable();
    }

    private boolean isRiceSeed(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        return RICE_ITEM_ID.equals(customItemId) || RICE_PANICLE_ID.equals(customItemId);
    }

    private boolean isWildRiceItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        return WILD_RICE_ITEM_ID.equals(ItemUtils.getCustomItemId(item));
    }

    private ItemStack getHeldItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private void consumeHeldItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (player == null || hand == null || item == null || item.getType().isAir()) {
            return;
        }

        int remaining = item.getAmount() - 1;
        if (remaining > 0) {
            item.setAmount(remaining);
            return;
        }

        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private Location findPlantLocation(Block clickedBlock) {
        if (isRiceBlock(clickedBlock) || isWildRiceBlock(clickedBlock)) {
            return null;
        }

        if (RiceCropRules.isSourceWater(clickedBlock)) {
            if (hasExistingRicePlant(clickedBlock)) {
                return null;
            }
            return clickedBlock.getLocation();
        }

        if (RiceCropRules.isValidSoil(clickedBlock)) {
            Block above = clickedBlock.getRelative(BlockFace.UP);
            if (isRiceBlock(above) || isWildRiceBlock(above) || hasExistingRicePlant(above)) {
                return null;
            }
            if (RiceCropRules.isSourceWater(above)) {
                return above.getLocation();
            }
        }

        return null;
    }

    private boolean hasExistingRicePlant(Block block) {
        if (block == null) {
            return false;
        }

        if (isRiceBlock(block) || isWildRiceBlock(block)) {
            return true;
        }

        Block upper = block.getRelative(BlockFace.UP);
        return isRiceBlock(upper) || isWildRiceBlock(upper);
    }

    private boolean canRiceStay(Block block, ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }

        if (isUpperRiceHalf(state)) {
            Block lowerBlock = block.getRelative(BlockFace.DOWN);
            ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
            return isRiceBlock(lowerState);
        }

        return RiceCropRules.canLowerRiceStay(block, RICE_BLOCK_KEY);
    }

    private boolean canWildRiceStay(Block block, ImmutableBlockState state) {
        WildRiceBlockBehavior behavior = WildRiceBlockBehavior.getBehavior(state);
        if (behavior == null) {
            return false;
        }
        return behavior.canStay(block, state);
    }

    private void syncSupportingState(Block block, ImmutableBlockState state) {
        if (isUpperRiceHalf(state)) {
            return;
        }

        try {
            Property<Boolean> supportingProperty = (Property<Boolean>) state.owner().value().getProperty("supporting");
            if (supportingProperty == null) {
                return;
            }

            boolean shouldSupport = isRiceBlock(block.getRelative(BlockFace.UP));
            Boolean currentValue = state.get(supportingProperty);
            if (currentValue != null && currentValue == shouldSupport) {
                return;
            }

            CraftEngineBlocks.place(block.getLocation(), state.with(supportingProperty, shouldSupport), false);
        } catch (Exception ignored) {
        }
    }

    private Object getPropertyValue(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        try {
            Property<?> property = state.owner().value().getProperty(propertyName);
            if (property == null) {
                return null;
            }

            return state.get(property);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isUpperRiceHalf(ImmutableBlockState state) {
        Object halfValue = getPropertyValue(state, "half");
        if (halfValue == null) {
            return false;
        }

        if (halfValue instanceof Integer intValue) {
            return intValue == 1;
        }

        if (halfValue instanceof Number numberValue) {
            return numberValue.intValue() == 1;
        }

        String textValue = String.valueOf(halfValue).trim().toLowerCase();
        return "upper".equals(textValue) || "1".equals(textValue);
    }

    private Object inferRiceHalfValue(BlockDefinition block, String target) {
        if (block == null) {
            return target.toLowerCase();
        }

        try {
            Property<?> halfProperty = block.getProperty("half");
            if (halfProperty == null) {
                return target.toLowerCase();
            }

            for (Object candidate : halfProperty.possibleValues()) {
                if (matchesRiceHalfValue(candidate, target)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }

        return target.toLowerCase();
    }

    private boolean matchesRiceHalfValue(Object actual, Object expected) {
        if (actual == expected) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        String actualText = normalizeRiceHalfValue(actual);
        String expectedText = normalizeRiceHalfValue(expected);
        return !actualText.isEmpty() && actualText.equals(expectedText);
    }

    private String normalizeRiceHalfValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toLowerCase();
    }

    private Property<Integer> getAgeProperty(BlockDefinition block) {
        if (block == null) {
            return null;
        }

        Property<Integer> cropStageProperty = (Property<Integer>) block.getProperty("crop_stage");
        if (cropStageProperty != null) {
            return cropStageProperty;
        }

        return (Property<Integer>) block.getProperty("age");
    }

    private Property<Integer> getAgeProperty(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        BlockDefinition block = state.owner().value();
        return getAgeProperty(block);
    }

    private String getPropertyString(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        try {
            Property<?> property = state.owner().value().getProperty(propertyName);
            if (property == null) {
                return null;
            }

            Object value = state.get(property);
            if (value == null) {
                return null;
            }
            return value.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isRiceBlock(ImmutableBlockState state) {
        return RICE_BLOCK_ID.equals(CustomBlockUtils.getId(state));
    }

    private boolean isRiceBlock(Block block) {
        return isRiceBlock(CustomBlockUtils.getState(block));
    }

    private boolean isWildRiceBlock(ImmutableBlockState state) {
        return WILD_RICE_BLOCK_ID.equals(CustomBlockUtils.getId(state));
    }

    private boolean isWildRiceBlock(Block block) {
        return isWildRiceBlock(CustomBlockUtils.getState(block));
    }

    private void scheduleRiceValidation(Block block) {
        Location location = block.getLocation();
        plugin.scheduler().runAt(location, () -> validateRiceAfterPhysics(location));
    }

    private void scheduleWildRiceValidation(Block block) {
        Location location = block.getLocation();
        plugin.scheduler().runAt(location, () -> validateWildRiceAfterPhysics(location));
    }

    private void validateRiceAfterPhysics(Location location) {
        Block block = location.getBlock();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (!isRiceBlock(state)) {
            return;
        }

        if (canRiceStay(block, state)) {
            syncSupportingState(block, state);
            return;
        }

        removeRicePlant(block, state);
    }

    private void validateWildRiceAfterPhysics(Location location) {
        Block block = location.getBlock();
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (!isWildRiceBlock(state)) {
            return;
        }

        if (canWildRiceStay(block, state)) {
            return;
        }

        removeWildRicePlant(block, state);
    }

    private void removeRicePlant(Block block, ImmutableBlockState state) {
        if (isUpperRiceHalf(state)) {
            block.setType(Material.AIR, false);
            return;
        }

        Block upperBlock = block.getRelative(BlockFace.UP);
        if (isRiceBlock(upperBlock)) {
            upperBlock.setType(Material.AIR, false);
        }
        if (RiceCropRules.isValidSoil(block.getRelative(BlockFace.DOWN))) {
            block.setType(Material.WATER, false);
        } else {
            block.setType(Material.AIR, false);
        }
    }

    private void restoreRiceCarrierBlock(Block brokenBlock, Object half) {
        if (brokenBlock == null) {
            return;
        }

        if (isUpperHalfValue(half)) {
            resetLowerAfterUpperBreak(brokenBlock.getRelative(BlockFace.DOWN));
            return;
        }

        Block upperBlock = brokenBlock.getRelative(BlockFace.UP);
        clearRiceUpperCarrier(upperBlock);

        if (RiceCropRules.isValidSoil(brokenBlock.getRelative(BlockFace.DOWN))) {
            brokenBlock.setType(Material.WATER, false);
        }
    }

    private boolean isUpperHalfValue(Object halfValue) {
        if (halfValue == null) {
            return false;
        }

        if (halfValue instanceof Integer intValue) {
            return intValue == 1;
        }

        if (halfValue instanceof Number numberValue) {
            return numberValue.intValue() == 1;
        }

        String textValue = String.valueOf(halfValue).trim().toLowerCase();
        return "upper".equals(textValue) || "1".equals(textValue);
    }

    private void restoreWildRiceCarrierBlock(Block brokenBlock, String half) {
        if (brokenBlock == null) {
            return;
        }

        if ("upper".equalsIgnoreCase(half)) {
            restoreWildRiceLowerAfterUpperBreak(brokenBlock.getRelative(BlockFace.DOWN));
            return;
        }

        Block upperBlock = brokenBlock.getRelative(BlockFace.UP);
        if (isWildRiceBlock(upperBlock)) {
            upperBlock.setType(Material.AIR, false);
        }

        if (getWildRiceBehavior().isValidSoil(brokenBlock.getRelative(BlockFace.DOWN))) {
            brokenBlock.setType(Material.WATER, false);
        }
    }

    private void restoreWildRiceLowerAfterUpperBreak(Block lowerBlock) {
        if (lowerBlock == null) {
            return;
        }

        if (getWildRiceBehavior().isValidSoil(lowerBlock.getRelative(BlockFace.DOWN))) {
            lowerBlock.setType(Material.WATER, false);
        } else {
            lowerBlock.setType(Material.AIR, false);
        }
    }

    private boolean canPlantWildRiceAt(Block waterBlock) {
        return getWildRiceBehavior().canPlantAt(waterBlock);
    }

    private void resetLowerAfterUpperBreak(Block lowerBlock) {
        if (lowerBlock == null) {
            return;
        }

        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
        if (!isRiceBlock(lowerState)) {
            return;
        }

        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(lowerState);
        int lowerResetAge = behavior != null ? Math.max(0, behavior.getMaxAgeLower() - 1) : 3;

        ImmutableBlockState resetState = lowerState;
        try {
            Property<Integer> ageProperty = getAgeProperty(lowerState);
            if (ageProperty != null) {
                resetState = resetState.with(ageProperty, lowerResetAge);
            }

            Property<?> half = lowerState.owner().value().getProperty("half");
            if (half != null) {
                resetState = withRawProperty(resetState, half, inferRiceHalfValue(lowerState.owner().value(), "lower"));
            }

            Property<Boolean> supporting = (Property<Boolean>) lowerState.owner().value().getProperty("supporting");
            if (supporting != null) {
                resetState = resetState.with(supporting, false);
            }

            CraftEngineBlocks.place(lowerBlock.getLocation(), resetState, false);
        } catch (Exception ignored) {
        }
    }

    private void clearRiceUpperCarrier(Block upperBlock) {
        if (upperBlock == null) {
            return;
        }

        if (isRiceBlock(upperBlock) || upperBlock.getType() == Material.TRIPWIRE) {
            upperBlock.setType(Material.AIR, false);
        }
    }

    private void removeWildRicePlant(Block block, ImmutableBlockState state) {
        String half = getPropertyString(state, "half");
        if ("upper".equalsIgnoreCase(half)) {
            block.setType(Material.AIR, false);
            return;
        }

        Block upperBlock = block.getRelative(BlockFace.UP);
        if (isWildRiceBlock(upperBlock)) {
            upperBlock.setType(Material.AIR, false);
        }

        if (getWildRiceBehavior().isValidSoil(block.getRelative(BlockFace.DOWN))) {
            block.setType(Material.WATER, false);
        } else {
            block.setType(Material.AIR, false);
        }
    }

    private boolean placeRice(Location location) {
        return placeRice(location, true);
    }

    private boolean placeWildRice(Location location) {
        BlockDefinition wildRiceBlock = CraftEngineBlocks.byId(Key.of(WILD_RICE_BLOCK_ID));
        if (wildRiceBlock == null) {
            return false;
        }

        Block lowerBlock = location.getBlock();
        Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
        if (!upperBlock.getType().isAir()) {
            return false;
        }

        ImmutableBlockState lowerState = wildRiceBlock.defaultState();
        Property<?> halfProperty = wildRiceBlock.getProperty("half");
        if (halfProperty != null) {
            lowerState = withRawProperty(lowerState, halfProperty, inferRiceHalfValue(wildRiceBlock, "lower"));
        }

        boolean lowerPlaced = CraftEngineBlocks.place(location, lowerState, false);
        if (!lowerPlaced) {
            return false;
        }

        ImmutableBlockState upperState = wildRiceBlock.defaultState();
        if (halfProperty != null) {
            upperState = withRawProperty(upperState, halfProperty, inferRiceHalfValue(wildRiceBlock, "upper"));
        }

        boolean upperPlaced = CraftEngineBlocks.place(upperBlock.getLocation(), upperState, false);
        if (!upperPlaced) {
            lowerBlock.setType(Material.WATER, false);
            return false;
        }

        scheduleWildRiceValidation(lowerBlock);
        scheduleWildRiceValidation(upperBlock);
        return true;
    }

    private boolean placeRice(Location location, boolean scheduleStabilization) {
        BlockDefinition riceBlock = CraftEngineBlocks.byId(Key.of(RICE_BLOCK_ID));
        if (riceBlock == null) {
            return false;
        }

        ImmutableBlockState state = riceBlock.defaultState();

        Property<Integer> ageProperty = getAgeProperty(riceBlock);
        if (ageProperty != null) {
            state = state.with(ageProperty, 0);
        }

        Property<?> half = riceBlock.getProperty("half");
        if (half != null) {
            state = withRawProperty(state, half, inferRiceHalfValue(riceBlock, "lower"));
        }

        Property<Boolean> supporting = (Property<Boolean>) riceBlock.getProperty("supporting");
        if (supporting != null) {
            state = state.with(supporting, false);
        }

        boolean placementSucceeded = CraftEngineBlocks.place(location, state, false);
        if (!placementSucceeded) {
            return false;
        }

        ImmutableBlockState placedState = CraftEngineBlocks.getCustomBlockState(location.getBlock());
        boolean placedNow = isRiceBlock(placedState);
        if (scheduleStabilization) {
            scheduleRiceStabilization(location.clone(), 3);
        }
        // CE may not expose the custom state on the same tick even though the
        // placement itself succeeded. Treat a successful place call as success
        // and let stabilization reconcile the carrier block over the next ticks.
        return placementSucceeded || placedNow;
    }

    private void scheduleRiceStabilization(Location location, int attemptsRemaining) {
        if (location == null || location.getWorld() == null || attemptsRemaining <= 0) {
            return;
        }

        String key = location.getWorld().getUID() + ":" + new BlockPosKey(location);
        if (!pendingRiceStabilizations.add(key)) {
            return;
        }

        // CE may briefly rewrite the carrier block right after placement.
        // Keep only one stabilization chain per position and retry once if the
        // custom state is still settling on the next tick.
        plugin.scheduler().runLaterAt(location, () -> {
            pendingRiceStabilizations.remove(key);
            ensureRiceStable(location, attemptsRemaining);
        }, 1L);
    }

    @SuppressWarnings({"rawtypes"})
    private ImmutableBlockState withRawProperty(ImmutableBlockState state, Property<?> property, Object value) {
        if (state == null || property == null || value == null) {
            return state;
        }
        return state.with((Property) property, (Comparable) value);
    }

    private void ensureRiceStable(Location location, int attemptsRemaining) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        Block block = location.getBlock();
        if (isRiceBlock(block)) {
            ImmutableBlockState state = CustomBlockUtils.getState(block);
            if (isRiceBlock(state) && canRiceStay(block, state)) {
                syncSupportingState(block, state);
            }
            return;
        }

        boolean placed = placeRice(location, false);
        if (attemptsRemaining > 1 && placed && !isRiceBlock(CustomBlockUtils.getState(block))) {
            scheduleRiceStabilization(location.clone(), attemptsRemaining - 1);
        }
    }

    private void sendInvalidPlacementMessage(Player player, PlayerInteractEvent event, Block clickedBlock) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getBlockFace() != BlockFace.UP) {
            return;
        }

        if (!RiceCropRules.isValidSoil(clickedBlock)) {
            return;
        }

        player.sendActionBar(I18n.getComponent("crop.need_water", player));
    }

    private WildRiceBlockBehavior getWildRiceBehavior() {
        WildRiceBlockBehavior behavior = WildRiceBlockBehavior.getBehavior(Key.of(WILD_RICE_BLOCK_ID));
        if (behavior == null) {
            throw new IllegalStateException("Wild rice behavior not registered for " + WILD_RICE_BLOCK_ID);
        }
        return behavior;
    }

    private void playPlacementFeedback(Player player, EquipmentSlot hand, Location plantLocation) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }

        // Reuse the planted block's own sound group so rice placement feels like a
        // natural block placement instead of a hard-coded custom sound.
        SoundGroup soundGroup = plantLocation.getBlock().getBlockData().getSoundGroup();
        if (soundGroup != null) {
            player.playSound(
                    plantLocation.clone().add(0.5, 0.5, 0.5),
                    soundGroup.getPlaceSound(),
                    soundGroup.getVolume(),
                    soundGroup.getPitch()
            );
        } else {
            player.playSound(plantLocation.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f);
        }
    }
}

