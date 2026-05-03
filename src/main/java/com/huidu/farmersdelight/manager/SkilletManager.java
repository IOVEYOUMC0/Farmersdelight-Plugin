package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.*;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SkilletManager {

    private static final String BLOCK_TYPE = "skillet";
    private static final int DEFAULT_COOK_TIME = 600;
    private static final int MIN_COOK_TIME = 60;
    private static final int HEARTBEAT_LOG_INTERVAL = 20;

    private final FarmersDelightPlugin plugin;
    private final Map<Location, SkilletData> skillets = new ConcurrentHashMap<>();
    private final CampfireRecipeCache campfireRecipes = new CampfireRecipeCache("skillet", this::debug);
    private BukkitTask tickTask;
    private int heartbeatTicks;

    public static class SkilletData {
        final Location location;
        ItemStack storedItem;
        ItemStack skilletStack;
        int cookingProgress = 0;
        int cookingDuration = DEFAULT_COOK_TIME;
        CookingRecipe<?> currentRecipe;
        int fireAspectLevel = 0;
        final List<Integer> displayEntityIds = new ArrayList<>();

        SkilletData(Location location) {
            this.location = location;
        }

        boolean hasItem() {
            return storedItem != null && !storedItem.getType().isAir();
        }
    }

    public SkilletManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        campfireRecipes.rebuild();
    }

    private void ensureTaskRunning() {
        if (tickTask != null) {
            return;
        }
        debug("tick task: starting skillet tick task");
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 4L);
    }

    private void stopTaskIfIdle() {
        if (tickTask != null && skillets.isEmpty()) {
            debug("tick task: stopping skillet tick task because no skillets remain");
            tickTask.cancel();
            tickTask = null;
            heartbeatTicks = 0;
        }
    }

    public SkilletData getOrCreateSkillet(Location location) {
        ensureTaskRunning();
        Location normalized = ManagerSupport.normalize(location);
        return skillets.computeIfAbsent(normalized, SkilletData::new);
    }

    public void recordPlacedSkillet(Location location, ItemStack skilletItem) {
        if (location == null || skilletItem == null || skilletItem.getType().isAir()) return;

        SkilletData skillet = getOrCreateSkillet(location);
        skillet.skilletStack = skilletItem.clone();
        skillet.skilletStack.setAmount(1);
        skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        saveSkillet(location, skillet);

        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.checkAndPlaceTray(location);
        }
    }

    public boolean handleInteract(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        Location location = ManagerSupport.normalize(block.getLocation());
        SkilletData skillet = getOrLoadSkillet(location);
        ensurePlacedSkilletState(skillet);
        ItemStack heldItem = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        if (heldItem == null || heldItem.getType().isAir()) {
            if (!skillet.hasItem()) {
                return false;
            }

            ItemStack toReturn = skillet.storedItem.clone();
            debug("retrieve: returning=" + formatItem(toReturn) + ", hand=" + hand + ", location=" + formatLocation(location));
            skillet.storedItem = null;
            skillet.currentRecipe = null;
            skillet.cookingProgress = 0;
            cleanupVisual(skillet);
            saveSkillet(location, skillet);

            if (hand == EquipmentSlot.OFF_HAND && player.getInventory().getItemInOffHand().getType().isAir()) {
                player.getInventory().setItemInOffHand(toReturn);
            } else if (hand != EquipmentSlot.OFF_HAND && player.getInventory().getItemInMainHand().getType().isAir()) {
                player.getInventory().setItemInMainHand(toReturn);
            } else {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(toReturn);
                for (ItemStack leftover : leftovers.values()) {
                    block.getWorld().dropItemNaturally(location.clone().add(0.5, 1.0, 0.5), leftover);
                }
            }
            block.getWorld().playSound(location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            return true;
        }

        if (skillet.hasItem()) {
            CookingRecipe<?> recipe = findCampfireRecipe(heldItem);
            if (recipe == null || skillet.currentRecipe == null) {
                debug("recipe match: stack add failed, recipe missing. input=" + formatItem(heldItem)
                        + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location));
                return false;
            }

            if (!canStackWithStored(skillet.storedItem, heldItem, skillet.currentRecipe, recipe)) {
                debug("recipe match: stack add failed, recipe/item mismatch. inputRecipe=" + recipe.getKey()
                        + ", storedRecipe=" + skillet.currentRecipe.getKey() + ", input=" + formatItem(heldItem)
                        + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location));
                return false;
            }

            int maxStack = skillet.storedItem.getMaxStackSize();
            int freeSpace = Math.max(0, maxStack - skillet.storedItem.getAmount());
            if (freeSpace <= 0) {
                return false;
            }

            int requestedAmount = heldItem.getAmount();
            int toMove = Math.min(freeSpace, requestedAmount);
            if (toMove <= 0) {
                return false;
            }
            debug("recipe match: stacking onto skillet, recipe=" + recipe.getKey() + ", move=" + toMove
                    + ", storedBefore=" + formatItem(skillet.storedItem) + ", input=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
            skillet.storedItem.setAmount(skillet.storedItem.getAmount() + toMove);
            debug("create state: stacked item now=" + formatItem(skillet.storedItem)
                    + ", recipe=" + skillet.currentRecipe.getKey() + ", location=" + formatLocation(location));
            createVisual(location, skillet);
            saveSkillet(location, skillet);

            if (player.getGameMode() != GameMode.CREATIVE) {
                debug("consume: stacked move=" + toMove + ", before=" + heldItem.getAmount()
                        + ", after=" + (heldItem.getAmount() - toMove) + ", item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
                heldItem.setAmount(heldItem.getAmount() - toMove);
            } else {
                debug("consume: skipped for creative mode while stacking, item=" + formatItem(heldItem)
                        + ", location=" + formatLocation(location));
            }

            awardUseSkillet(player);
            SoundUtils.play(block.getWorld(), location, getAddFoodSound(location), Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.0f);
            return true;
        }

        if (isTool(heldItem) || isSkilletItem(heldItem)) {
            return false;
        }

        CookingRecipe<?> recipe = findCampfireRecipe(heldItem);
        if (recipe == null) {
            debug("recipe match: no campfire recipe for input=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
            return false;
        }
        debug("recipe match: recipe=" + recipe.getKey() + ", input=" + formatItem(heldItem)
                + ", location=" + formatLocation(location));

        ItemStack toPlace = heldItem.clone();
        int placeAmount = heldItem.getAmount();
        toPlace.setAmount(placeAmount);
        skillet.storedItem = toPlace;
        skillet.currentRecipe = recipe;
        skillet.cookingDuration = getAdjustedCookingTime(recipe.getCookingTime(), skillet.fireAspectLevel);
        skillet.cookingProgress = 0;
        debug("create state: stored=" + formatItem(toPlace) + ", recipe=" + recipe.getKey()
                + ", duration=" + skillet.cookingDuration + ", fireAspect=" + skillet.fireAspectLevel
                + ", location=" + formatLocation(location));

        createVisual(location, skillet);
        saveSkillet(location, skillet);

        if (player.getGameMode() != GameMode.CREATIVE) {
            debug("consume: before=" + heldItem.getAmount() + ", after=" + (heldItem.getAmount() - placeAmount)
                    + ", moved=" + placeAmount + ", item=" + formatItem(heldItem) + ", location=" + formatLocation(location));
            heldItem.setAmount(heldItem.getAmount() - placeAmount);
        } else {
            debug("consume: skipped for creative mode, moved=" + placeAmount + ", item=" + formatItem(heldItem)
                    + ", location=" + formatLocation(location));
        }

        awardUseSkillet(player);
        SoundUtils.play(block.getWorld(), location, getAddFoodSound(location), Sound.BLOCK_LANTERN_PLACE, 0.7f, 1.0f);
        return true;
    }

    public boolean canCook(ItemStack item) {
        return findCampfireRecipe(item) != null;
    }

    public String findRecipeId(ItemStack item) {
        CookingRecipe<?> recipe = findCampfireRecipe(item);
        if (recipe != null) {
            return recipe.getKey().toString();
        }
        return "null";
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String materialName = item.getType().name();
        if (materialName.endsWith("_SWORD")
                || materialName.endsWith("_AXE")
                || materialName.endsWith("_HOE")
                || materialName.endsWith("_SHOVEL")
                || materialName.endsWith("_PICKAXE")
                || materialName.equals("SHEARS")
                || materialName.equals("FLINT_AND_STEEL")) {
            return true;
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId == null) {
            return false;
        }

        if (customItemId.contains("knife")) {
            return true;
        }

        return plugin.getConfig().getStringList("knife-config.items").contains(customItemId);
    }

    private boolean isSkilletItem(ItemStack item) {
        String customItemId = ItemUtils.getCustomItemId(item);
        return Constants.ITEM_SKILLET.equals(customItemId);
    }

    private boolean isSkilletBlock(Location location) {
        return CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation) {
        breakSkillet(blockLocation, dropLocation, true);
    }

    public void breakSkillet(Location blockLocation, Location dropLocation, boolean shouldDropItems) {
        Location normalized = ManagerSupport.normalize(blockLocation);
        SkilletData skillet = skillets.remove(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
            if (shouldDropItems && skillet.storedItem != null && !skillet.storedItem.getType().isAir()) {
                normalized.getWorld().dropItemNaturally(dropLocation, skillet.storedItem.clone());
            }
            if (shouldDropItems) {
                ItemStack skilletDrop = skillet.skilletStack != null && !skillet.skilletStack.getType().isAir()
                        ? skillet.skilletStack.clone()
                        : ItemUtils.createItem(Constants.ITEM_SKILLET);
                if (skilletDrop != null && !skilletDrop.getType().isAir()) {
                    skilletDrop.setAmount(1);
                    normalized.getWorld().dropItemNaturally(dropLocation, skilletDrop);
                }
            }
        }
        removeStoredData(normalized);
        TrayManager trayManager = plugin.getTrayManager();
        if (trayManager != null) {
            trayManager.removeTrayIfAutoPlaced(normalized);
        }
    }

    public void saveAllData() {
        ManagerSupport.saveAllData(skillets, this::saveSkillet);
    }

    public void saveWorldData(World world) {
        ManagerSupport.saveWorldData(skillets, world, this::saveSkillet);
    }

    public void cleanupWorld(UUID worldId) {
        ManagerSupport.cleanupWorld(skillets, worldId, this::cleanupVisual);
    }

    public void saveAndUnloadChunk(World world, int minX, int maxX, int minZ, int maxZ) {
        ManagerSupport.saveAndUnloadChunk(skillets, world, minX, maxX, minZ, maxZ,
                this::saveSkillet, location -> removeSkillet(location, false));
    }

    public void loadSkillet(World world, net.momirealms.craftengine.core.world.BlockPos pos, Map<String, Object> data) {
        loadSkillet(world, new BlockPosKey(pos), data);
    }

    public void loadSkillet(World world, BlockPosKey posKey, Map<String, Object> data) {
        if (world == null || posKey == null || data == null) return;

        Location location = ManagerSupport.toLocation(world, posKey);
        if (location == null) return;
        if (!isSkilletBlock(location)) {
            removeStoredData(location);
            removeSkillet(location, false);
            return;
        }

        SkilletData skillet = new SkilletData(location);

        if (data.get("storedItem") instanceof ItemStack storedItem) {
            skillet.storedItem = storedItem.clone();
        }
        if (data.get("skilletStack") instanceof ItemStack skilletStack) {
            skillet.skilletStack = skilletStack.clone();
            skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        }
        if (data.get("cookingProgress") instanceof Number progress) {
            skillet.cookingProgress = progress.intValue();
        }
        if (data.get("cookingDuration") instanceof Number duration) {
            skillet.cookingDuration = duration.intValue();
        }
        if (skillet.storedItem != null && !skillet.storedItem.getType().isAir()) {
            skillet.currentRecipe = findCampfireRecipe(skillet.storedItem);
            createVisual(location, skillet);
        }
        if (shouldPersistSkillet(skillet)) {
            skillets.put(location, skillet);
            ensureTaskRunning();
        } else {
            removeStoredData(location);
        }
    }

    private SkilletData getOrLoadSkillet(Location location) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = skillets.get(normalized);
        if (skillet != null) {
            ensureTaskRunning();
            return skillet;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage != null) {
            Map<String, Object> data = storage.loadBlockData(normalized, BLOCK_TYPE);
            if (data != null) {
                loadSkillet(normalized.getWorld(), new BlockPosKey(normalized), data);
                skillet = skillets.get(normalized);
                if (skillet != null) {
                    return skillet;
                }
            }
        }

        return getOrCreateSkillet(normalized);
    }

    public void cleanup() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (SkilletData skillet : skillets.values()) {
            cleanupVisual(skillet);
        }
        skillets.clear();
    }

    public Collection<Location> getTrackedLocations(World world) {
        if (world == null) {
            return List.of();
        }

        return skillets.keySet().stream()
                .filter(location -> world.equals(location.getWorld()))
                .map(Location::clone)
                .collect(Collectors.toUnmodifiableList());
    }

    public void reloadRecipeCache() {
        campfireRecipes.rebuild();
    }

    private void removeSkillet(Location location, boolean removeStoredData) {
        Location normalized = ManagerSupport.normalize(location);
        SkilletData skillet = skillets.remove(normalized);
        if (skillet != null) {
            cleanupVisual(skillet);
        }
        stopTaskIfIdle();
        if (removeStoredData) {
            removeStoredData(normalized);
        }
    }

    private int getAdjustedCookingTime(int baseTime, int fireAspectLevel) {
        int cookingTime = baseTime > 0 ? baseTime : DEFAULT_COOK_TIME;
        int cookingSeconds = cookingTime / 20;
        float cookingTimeReduction = 0.2F;

        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * 0.05F;
        }

        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        return Math.max(MIN_COOK_TIME, Math.min(result, cookingTime));
    }

    private void tick() {
        if (skillets.isEmpty()) {
            stopTaskIfIdle();
            return;
        }
        if (++heartbeatTicks >= HEARTBEAT_LOG_INTERVAL) {
            heartbeatTicks = 0;
            debug(() -> "tick heartbeat: activeSkillets=" + skillets.size());
        }
        Iterator<Map.Entry<Location, SkilletData>> it = skillets.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<Location, SkilletData> entry = it.next();
            Location location = entry.getKey();
            SkilletData skillet = entry.getValue();

            World world = location.getWorld();
            if (world == null) continue;
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
            if (!skillet.hasItem()) {
                if (!hasPlacedSkillet(skillet)) {
                    debug(() -> "tick remove: skillet has no base item snapshot and no stored food at " + formatLocation(location));
                    removeStoredData(location);
                    it.remove();
                }
                continue;
            }

            ensureVisualsExist(location, skillet);

            boolean hasHeat = hasHeatSource(location);
            debug(() -> "tick state: hasHeat=" + hasHeat + ", progress=" + skillet.cookingProgress
                    + "/" + skillet.cookingDuration + ", recipe="
                    + (skillet.currentRecipe != null ? skillet.currentRecipe.getKey() : "null")
                    + ", stored=" + formatItem(skillet.storedItem) + ", location=" + formatLocation(location)
                    + ", fireAspectLevel=" + skillet.fireAspectLevel);
            if (hasHeat && skillet.currentRecipe != null) {
                skillet.cookingProgress++;

                if (Math.random() < 0.1) {
                    spawnCookingParticles(location);
                }
                if (Math.random() < 0.03) {
                    SoundUtils.play(world, location, getSizzleSound(location), Sound.BLOCK_CAMPFIRE_CRACKLE, 0.5f, 1.0f);
                }
                if (skillet.cookingProgress >= skillet.cookingDuration) {
                    debug(() -> "tick finish: progress reached duration for " + formatItem(skillet.storedItem)
                            + " at " + formatLocation(location));
                    finishCooking(location, skillet);
                }
            } else {
                skillet.cookingProgress = Math.max(0, skillet.cookingProgress - 2);
            }
        }
        stopTaskIfIdle();
    }

    private boolean hasHeatSource(Location location) {
        Block blockBelow = location.clone().subtract(0, 1, 0).getBlock();

        if (plugin.getHeatSourceConfig().isHeatSource(blockBelow)) {
            return true;
        }

        if (plugin.getHeatSourceConfig().isConductor(blockBelow)) {
            Block blockTwoBelow = location.clone().subtract(0, 2, 0).getBlock();
            return plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
        }

        return false;
    }

    private void finishCooking(Location location, SkilletData skillet) {
        if (skillet.currentRecipe == null || skillet.storedItem == null) return;
        if (!isSkilletBlock(location)) {
            debug("finish cooking: skipped because block is no longer a skillet at " + formatLocation(location));
            cleanupVisual(skillet);
            ManagerSupport.removeStoredData(plugin, location);
            skillets.remove(location);
            stopTaskIfIdle();
            return;
        }

        ItemStack result = skillet.currentRecipe.getResult();
        debug("finish cooking: result=" + formatItem(result) + ", storedBefore=" + formatItem(skillet.storedItem)
                + ", location=" + formatLocation(location));
        if (result != null) {
            Block block = location.getBlock();
            BlockFace facing = CustomBlockUtils.getFacing(block);
            BlockFace clockwise = getClockWise(facing);

            Location dropLoc = location.clone().add(0.5, 0.3, 0.5);
            var droppedItem = location.getWorld().dropItem(dropLoc, result.clone());
            droppedItem.setVelocity(new org.bukkit.util.Vector(
                    clockwise.getModX() * 0.08,
                    0.25,
                    clockwise.getModZ() * 0.08
            ));
        }

        skillet.storedItem.setAmount(skillet.storedItem.getAmount() - 1);
        if (skillet.storedItem.getAmount() <= 0) {
            skillet.storedItem = null;
            skillet.currentRecipe = null;
            cleanupVisual(skillet);
        } else {
            createVisual(location, skillet);
        }

        skillet.cookingProgress = 0;
        saveSkillet(location, skillet);
        location.getWorld().playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
    }

    private void ensurePlacedSkilletState(SkilletData skillet) {
        if (skillet == null || hasPlacedSkillet(skillet)) {
            return;
        }

        ItemStack skilletItem = ItemUtils.createItem(Constants.ITEM_SKILLET);
        if (skilletItem == null || skilletItem.getType().isAir()) {
            return;
        }

        skillet.skilletStack = skilletItem;
        skillet.skilletStack.setAmount(1);
        skillet.fireAspectLevel = skillet.skilletStack.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        debug("create state: synthesized missing skillet base item snapshot");
    }

    private String getAddFoodSound(Location location) {
        SkilletBlockBehavior behavior = SkilletBlockBehavior.getBlockBehavior(location);
        if (behavior != null) {
            return behavior.getAddFoodSound();
        }
        return Constants.SOUND_SKILLET_ADD_FOOD;
    }

    private String getSizzleSound(Location location) {
        SkilletBlockBehavior behavior = SkilletBlockBehavior.getBlockBehavior(location);
        if (behavior != null) {
            return behavior.getSizzleSound();
        }
        return Constants.SOUND_SKILLET_SIZZLE;
    }

    private BlockFace getClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private void spawnCookingParticles(Location location) {
        Location particleLocation = location.clone().add(0.5, 0.2, 0.5);
        location.getWorld().spawnParticle(Particle.SMOKE, particleLocation, 2, 0.1, 0.1, 0.1, 0.02);
    }

    private void createVisual(Location location, SkilletData skillet) {
        cleanupVisual(skillet);
        if (skillet.storedItem == null || skillet.storedItem.getType().isAir()) {
            debug("spawn display: skipped empty stored item at " + formatLocation(location));
            return;
        }

        int displayCount = getModelCount(skillet.storedItem);
        Random random = new Random(getVisualSeed(skillet.storedItem));
        BlockFace facing = CustomBlockUtils.getFacing(location.getBlock());
        float yRotation = CustomBlockUtils.getYRotation(facing);
        debug("spawn display: preparing " + displayCount + " displays for " + formatItem(skillet.storedItem)
                + " at " + formatLocation(location));

        for (int i = 0; i < displayCount; i++) {
            double offsetX = (random.nextDouble() - 0.5D) * 0.3D;
            double offsetZ = (random.nextDouble() - 0.5D) * 0.3D;
            double offsetY = 0.1D + ((i + 1) * 0.03D);
            float randomSpin = (float) ((random.nextDouble() - 0.5D) * 35.0D);

            ItemStack visualItem = skillet.storedItem.clone();
            visualItem.setAmount(1);

            Quaternionf leftRotation = new Quaternionf();
            leftRotation.rotationYXZ(
                    (float) Math.toRadians(yRotation),
                    (float) Math.toRadians(90.0f),
                    (float) Math.toRadians(randomSpin)
            );

            Transformation transformation = new Transformation(
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    leftRotation,
                    new Vector3f(plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale(), plugin.getSkilletDisplayScale()),
                    new Quaternionf()
            );

            ItemDisplayManager visualManager = plugin.getFakeItemDisplayManager();
            if (visualManager == null || !visualManager.isAvailable()) {
                debug("spawn display: visual manager unavailable at " + formatLocation(location));
                continue;
            }

            Location displayLoc = location.clone().add(0.5 + offsetX, offsetY, 0.5 + offsetZ);
            int displayId = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                    displayLoc,
                    visualItem,
                    org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED,
                    transformation
            ));
            if (displayId >= 0) {
                skillet.displayEntityIds.add(displayId);
                debug("spawn display: entityId=" + displayId + ", index=" + i + ", item=" + formatItem(visualItem)
                        + ", location=" + formatLocation(location));
            }
        }
    }

    private int getModelCount(ItemStack stack) {
        int amount = stack.getAmount();
        if (amount > 48) {
            return 5;
        }
        if (amount > 32) {
            return 4;
        }
        if (amount > 16) {
            return 3;
        }
        if (amount > 1) {
            return 2;
        }
        return 1;
    }

    private long getVisualSeed(ItemStack stack) {
        long seed = stack.getType().ordinal();
        String customItemId = ItemUtils.getCustomItemId(stack);
        if (customItemId != null) {
            seed = 31L * seed + customItemId.hashCode();
        }
        if (stack.hasItemMeta() && stack.getItemMeta().hasCustomModelData()) {
            seed = 31L * seed + stack.getItemMeta().getCustomModelData();
        }
        return seed;
    }

    private boolean canStackWithStored(ItemStack stored, ItemStack incoming, CookingRecipe<?> storedRecipe, CookingRecipe<?> incomingRecipe) {
        if (stored == null || incoming == null || storedRecipe == null || incomingRecipe == null) {
            return false;
        }

        if (!storedRecipe.getKey().equals(incomingRecipe.getKey())) {
            return false;
        }

        ItemStack storedSingle = stored.clone();
        storedSingle.setAmount(1);
        ItemStack incomingSingle = incoming.clone();
        incomingSingle.setAmount(1);
        return storedSingle.isSimilar(incomingSingle);
    }

    private void cleanupVisual(SkilletData skillet) {
        if (skillet.displayEntityIds.isEmpty()) {
            return;
        }

        ItemDisplayManager visualManager = plugin.getFakeItemDisplayManager();
        if (visualManager == null) {
            skillet.displayEntityIds.clear();
            return;
        }

        for (Integer entityId : new ArrayList<>(skillet.displayEntityIds)) {
            visualManager.destroyDisplay(entityId);
        }
        skillet.displayEntityIds.clear();
    }

    private void ensureVisualsExist(Location location, SkilletData skillet) {
        if (!skillet.hasItem()) {
            return;
        }

        int expectedCount = getModelCount(skillet.storedItem);
        if (skillet.displayEntityIds.size() != expectedCount) {
            createVisual(location, skillet);
        }
    }

    private CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        return campfireRecipes.find(item);
    }

    private boolean hasPlacedSkillet(SkilletData skillet) {
        return skillet != null && skillet.skilletStack != null && !skillet.skilletStack.getType().isAir();
    }

    private boolean shouldPersistSkillet(SkilletData skillet) {
        return skillet != null && (skillet.hasItem() || hasPlacedSkillet(skillet));
    }

    private void saveSkillet(Location location, SkilletData skillet) {
        Location normalized = ManagerSupport.normalize(location);
        ensurePlacedSkilletState(skillet);
        if (!shouldPersistSkillet(skillet)) {
            debug("save state: removing persisted skillet state at " + formatLocation(normalized));
            ManagerSupport.removeStoredData(plugin, normalized);
            return;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) {
            debug("save state: skipped because storage manager is null at " + formatLocation(normalized));
            return;
        }

        Map<String, Object> data = new HashMap<>();
        if (skillet.hasItem()) {
            data.put("storedItem", skillet.storedItem.clone());
            data.put("cookingProgress", skillet.cookingProgress);
            data.put("cookingDuration", skillet.cookingDuration);
        }
        if (skillet.skilletStack != null && !skillet.skilletStack.getType().isAir()) {
            data.put("skilletStack", skillet.skilletStack.clone());
        }
        storage.saveBlockData(normalized, BLOCK_TYPE, data);
        debug("save state: hasItem=" + skillet.hasItem() + ", stored=" + formatItem(skillet.storedItem)
                + ", duration=" + skillet.cookingDuration + ", progress=" + skillet.cookingProgress
                + ", hasPlacedSkillet=" + hasPlacedSkillet(skillet) + ", location=" + formatLocation(normalized));
    }

    private void removeStoredData(Location location) {
        ManagerSupport.removeStoredData(plugin, location);
    }

    private void awardUseSkillet(Player player) {
        if (player == null) {
            return;
        }

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "use_skillet");
        }
    }

    private void debug(String message) {
        if (plugin.isDebugEnabled("skillet")) {
            plugin.getLogger().info("[SkilletDebug] " + message);
        }
    }

    private void debug(Supplier<String> messageSupplier) {
        if (plugin.isDebugEnabled("skillet")) {
            plugin.getLogger().info("[SkilletDebug] " + messageSupplier.get());
        }
    }

    private String formatItem(ItemStack item) {
        return ManagerSupport.formatItem(item);
    }

    private String formatLocation(Location location) {
        return ManagerSupport.formatLocation(location);
    }
}
