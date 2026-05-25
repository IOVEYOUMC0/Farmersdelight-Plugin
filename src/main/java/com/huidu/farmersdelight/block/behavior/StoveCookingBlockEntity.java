package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.*;

/**
 * Legacy stove state model kept for recipe cache helpers and old saved-state compatibility.
 * Runtime stove logic is owned by com.huidu.farmersdelight.manager.StoveManager.
 */
@Deprecated(forRemoval = false)
public class StoveCookingBlockEntity {

    private static final int MAX_CACHE_SIZE = 100;
    private static final LinkedHashMap<Material, CookingRecipe<?>> recipeCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Material, CookingRecipe<?>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final int NO_DISPLAY = -1;

    private final BlockPosKey posKey;
    private final CookingSlot[] slots = new CookingSlot[StoveCookingBlockBehavior.SLOT_COUNT];
    private final int[] displayEntityIds = new int[StoveCookingBlockBehavior.SLOT_COUNT];

    private static final float[][] SLOT_OFFSETS = {
            {0.3f, 0.2f},
            {0.0f, 0.2f},
            {-0.3f, 0.2f},
            {0.3f, -0.2f},
            {0.0f, -0.2f},
            {-0.3f, -0.2f}
    };

    public StoveCookingBlockEntity(BlockPos pos) {
        this(new BlockPosKey(pos));
    }

    public StoveCookingBlockEntity(BlockPosKey posKey) {
        this.posKey = posKey;
        Arrays.fill(displayEntityIds, NO_DISPLAY);
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new CookingSlot();
        }
    }

    public BlockPosKey getPosKey() {
        return posKey;
    }

    public static class CookingSlot {
        private ItemStack item;
        private int cookTime;
        private int cookTimeTotal;
        private CookingRecipe<?> recipe;

        public ItemStack getItem() {
            return item;
        }

        public void setItem(ItemStack item) {
            this.item = item;
        }

        public int getCookTime() {
            return cookTime;
        }

        public void setCookTime(int cookTime) {
            this.cookTime = cookTime;
        }

        public int getCookTimeTotal() {
            return cookTimeTotal;
        }

        public void setCookTimeTotal(int cookTimeTotal) {
            this.cookTimeTotal = cookTimeTotal;
        }

        public CookingRecipe<?> getRecipe() {
            return recipe;
        }

        public void setRecipe(CookingRecipe<?> recipe) {
            this.recipe = recipe;
        }

        public void clear() {
            this.item = null;
            this.cookTime = 0;
            this.cookTimeTotal = 0;
            this.recipe = null;
        }
        
        public int getProgress() {
            return cookTime;
        }
        
        public void setProgress(int progress) {
            this.cookTime = progress;
        }
        
        public void decrementProgress() {
            this.cookTime = Math.max(0, this.cookTime - 1);
        }
        
        public int getCookingDuration() {
            return cookTimeTotal;
        }
        
        public void reset() {
            clear();
        }
    }

    public CookingSlot getSlot(int index) {
        if (index < 0 || index >= slots.length) return null;
        return slots[index];
    }

    public int getSlotCount() {
        return slots.length;
    }
    
    public CookingSlot[] getSlots() {
        return slots;
    }

    public boolean canAddItem() {
        for (CookingSlot slot : slots) {
            if (slot.getItem() == null) {
                return true;
            }
        }
        return false;
    }

    public int addItems(ItemStack item, int maxCount) {
        int added = 0;
        for (CookingSlot slot : slots) {
            if (slot.getItem() == null && added < maxCount) {
                slot.setItem(item.clone());
                slot.getItem().setAmount(1);
                added++;
            }
        }
        return added;
    }

    public void tick(World world, Location blockLoc) {
        boolean isLit = true;
        
        for (int i = 0; i < slots.length; i++) {
            CookingSlot slot = slots[i];
            if (slot.getItem() == null) continue;

            if (slot.getRecipe() == null) {
                CookingRecipe<?> recipe = findCampfireRecipe(slot.getItem());
                if (recipe != null) {
                    slot.setRecipe(recipe);
                    slot.setCookTimeTotal(recipe.getCookingTime());
                    slot.setCookTime(0);
                } else {
                    continue;
                }
            }

            if (isLit) {
                slot.setCookTime(slot.getCookTime() + 1);
                
                if (slot.getCookTime() >= slot.getCookTimeTotal()) {
                    finishCooking(slot, world, blockLoc, i);
                }
            }
        }
    }

    private void finishCooking(CookingSlot slot, World world, Location blockLoc, int slotIndex) {
        CookingRecipe<?> recipe = slot.getRecipe();
        if (recipe == null) return;

        ItemStack result = recipe.getResult();
        if (result != null) {
            Location dropLoc = blockLoc.clone().add(0.5, 1.0, 0.5);
            world.dropItemNaturally(dropLoc, result.clone());
        }

        slot.clear();
        removeDisplayEntity(slotIndex);
    }

    public static CookingRecipe<?> findCampfireRecipe(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        
        Material material = item.getType();
        
        synchronized (recipeCache) {
            CookingRecipe<?> cached = recipeCache.get(material);
            if (cached != null) {
                return cached;
            }
        }
        
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof CookingRecipe<?> cookingRecipe) {
                var ingredient = cookingRecipe.getInputChoice();
                if (ingredient != null && ingredient.test(item)) {
                    synchronized (recipeCache) {
                        recipeCache.put(material, cookingRecipe);
                    }
                    return cookingRecipe;
                }
            }
        }
        
        return null;
    }

    public static void clearRecipeCache() {
        synchronized (recipeCache) {
            recipeCache.clear();
        }
    }

    public void createDisplayEntity(int slotIndex, World world, Location blockLoc) {
        if (slotIndex < 0 || slotIndex >= displayEntityIds.length) return;

        CookingSlot slot = slots[slotIndex];
        if (slot == null || slot.getItem() == null) return;

        removeDisplayEntity(slotIndex);

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) return;

        float[] offset = SLOT_OFFSETS[slotIndex];
        Location displayLoc = blockLoc.clone().add(0.5 + offset[0], 0.375, 0.5 + offset[1]);
        ItemStack visualItem = slot.getItem().clone();
        visualItem.setAmount(1);

        displayEntityIds[slotIndex] = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                displayLoc,
                visualItem,
                ItemDisplay.ItemDisplayTransform.FIXED,
                new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(),
                        new org.joml.Quaternionf(),
                        new org.joml.Vector3f(0.6f, 0.6f, 0.6f),
                        new org.joml.Quaternionf()
                )
        ));
    }
    
    public void updateDisplayEntity(World world, BlockPosKey posKey, int slotIndex, BlockFace facing) {
        if (slotIndex < 0 || slotIndex >= displayEntityIds.length) return;
        
        CookingSlot slot = slots[slotIndex];
        if (slot == null || slot.getItem() == null) {
            removeDisplayEntity(slotIndex);
            return;
        }
        
        Location blockLoc = new Location(world, posKey.x(), posKey.y(), posKey.z());
        createDisplayEntity(slotIndex, world, blockLoc);
    }

    public void removeDisplayEntity(int slotIndex) {
        int entityId = displayEntityIds[slotIndex];
        if (entityId == NO_DISPLAY) return;
        displayEntityIds[slotIndex] = NO_DISPLAY;

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    public void removeAllDisplayEntities() {
        for (int i = 0; i < displayEntityIds.length; i++) {
            removeDisplayEntity(i);
        }
    }

    public Map<String, Object> serialize() {
        Map<String, Object> data = new HashMap<>();
        data.put("posKey", posKey.toString());
        
        for (int i = 0; i < slots.length; i++) {
            CookingSlot slot = slots[i];
            if (slot.getItem() != null) {
                Map<String, Object> slotData = new HashMap<>();
                slotData.put("item", slot.getItem());
                slotData.put("cookTime", slot.getCookTime());
                slotData.put("cookTimeTotal", slot.getCookTimeTotal());
                data.put("slot_" + i, slotData);
            }
        }
        
        return data;
    }

    public static StoveCookingBlockEntity deserialize(Map<String, Object> data, World world) {
        if (data == null) return null;
        
        String posKeyStr = (String) data.get("posKey");
        if (posKeyStr == null) return null;
        
        BlockPosKey posKey = BlockPosKey.fromString(posKeyStr);
        if (posKey == null) return null;
        
        StoveCookingBlockEntity entity = new StoveCookingBlockEntity(posKey);
        
        for (int i = 0; i < entity.slots.length; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> slotData = (Map<String, Object>) data.get("slot_" + i);
            if (slotData != null) {
                ItemStack item = (ItemStack) slotData.get("item");
                int cookTime = slotData.get("cookTime") instanceof Number n ? n.intValue() : 0;
                int cookTimeTotal = slotData.get("cookTimeTotal") instanceof Number n ? n.intValue() : 0;
                
                entity.slots[i].setItem(item);
                entity.slots[i].setCookTime(cookTime);
                entity.slots[i].setCookTimeTotal(cookTimeTotal);
            }
        }
        
        return entity;
    }
}
