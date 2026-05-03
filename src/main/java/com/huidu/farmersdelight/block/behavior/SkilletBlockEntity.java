package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class SkilletBlockEntity {

    private static final LinkedHashMap<Material, CookingRecipe<?>> recipeCache = new LinkedHashMap<>(16, 0.75f, true);
    
    private final BlockPosKey posKey;
    private ItemStack skilletStack;
    private ItemStack storedItem;
    private ItemStack cookedItem;
    private final AtomicInteger cookingProgress = new AtomicInteger(0);
    private final AtomicInteger cookingDuration = new AtomicInteger(SkilletBlockBehavior.DEFAULT_COOKING_TIME);
    private volatile CookingRecipe<?> currentRecipe;
    private volatile int fireAspectLevel = 0;
    private final Object cookingLock = new Object();
    private UUID displayEntityId;

    public SkilletBlockEntity(BlockPosKey posKey) {
        this.posKey = posKey;
        this.skilletStack = null;
    }

    public BlockPosKey getPosKey() {
        return posKey;
    }

    public BlockPos getPos() {
        return posKey.toBlockPos();
    }

    public ItemStack getSkilletStack() {
        if (skilletStack == null) {
            return null;
        }
        return skilletStack.clone();
    }

    public void setSkilletStack(ItemStack skilletStack) {
        this.skilletStack = cloneOrNull(skilletStack);
        if (this.skilletStack != null) {
            Map<Enchantment, Integer> enchants = this.skilletStack.getEnchantments();
            this.fireAspectLevel = enchants.getOrDefault(Enchantment.FIRE_ASPECT, 0);
        } else {
            this.fireAspectLevel = 0;
        }
    }

    public int getFireAspectLevel() {
        return fireAspectLevel;
    }
    
    public void setRecipe(CookingRecipe<?> recipe) {
        this.currentRecipe = recipe;
        if (recipe != null) {
            int baseTime = recipe.getCookingTime();
            this.cookingDuration.set(getSkilletCookingTime(baseTime, fireAspectLevel));
        }
    }
    
    public void setCookingDuration(int duration) {
        this.cookingDuration.set(duration);
    }

    public ItemStack getStoredItem() {
        if (storedItem == null) {
            return null;
        }
        return storedItem.clone();
    }

    public void setStoredItem(ItemStack storedItem) {
        this.storedItem = cloneOrNull(storedItem);
        this.currentRecipe = findCampfireRecipe(this.storedItem);
        if (this.currentRecipe != null) {
            int baseTime = this.currentRecipe.getCookingTime();
            this.cookingDuration.set(getSkilletCookingTime(baseTime, fireAspectLevel));
        } else {
            this.cookingDuration.set(SkilletBlockBehavior.DEFAULT_COOKING_TIME);
        }
        this.cookingProgress.set(0);
    }

    public static int getSkilletCookingTime(int originalCookingTime, int fireAspectLevel) {
        int cookingTime = originalCookingTime > 0 ? originalCookingTime : 600;
        int cookingSeconds = cookingTime / 20;
        float cookingTimeReduction = 0.2F;
        
        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * 0.05F;
        }
        
        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        
        return Math.max(SkilletBlockBehavior.MINIMUM_COOKING_TIME, Math.min(result, originalCookingTime));
    }

    public ItemStack getCookedItem() {
        if (cookedItem == null) {
            return null;
        }
        return cookedItem.clone();
    }

    public void setCookedItem(ItemStack cookedItem) {
        this.cookedItem = cloneOrNull(cookedItem);
    }

    public int getProgress() {
        return cookingProgress.get();
    }

    public void setProgress(int progress) {
        this.cookingProgress.set(progress);
    }

    public int getCookingDuration() {
        return cookingDuration.get();
    }
    
    public boolean isCookingComplete() {
        return cookingProgress.get() >= cookingDuration.get();
    }
    
    public ItemStack finishCookingAndgetResult() {
        synchronized (cookingLock) {
            if (currentRecipe == null || storedItem == null || storedItem.getType().isAir()) {
                cookingProgress.set(0);
                return null;
            }
            
            ItemStack result = currentRecipe.getResult();
            if (result == null) {
                cookingProgress.set(0);
                return null;
            }
            
            ItemStack output = result.clone();
            
            storedItem.setAmount(storedItem.getAmount() - 1);
            if (storedItem.getAmount() <= 0) {
                storedItem = null;
                currentRecipe = null;
            }
            
            cookingProgress.set(0);
            
            return output;
        }
    }

    public void decrementProgress() {
        cookingProgress.updateAndGet(v -> Math.max(0, v - 1));
    }

    public boolean hasItem() {
        return storedItem != null && !storedItem.getType().isAir() && storedItem.getAmount() > 0;
    }

    public boolean hasRecipe() {
        return currentRecipe != null;
    }

    public boolean isComplete() {
        return cookedItem != null && !cookedItem.getType().isAir();
    }

    public boolean canAddCookedItem(ItemStack result) {
        if (cookedItem == null || cookedItem.getType().isAir()) {
            return true;
        }
        if (!cookedItem.isSimilar(result)) {
            return false;
        }
        return cookedItem.getAmount() + result.getAmount() <= cookedItem.getMaxStackSize();
    }

    public void clear() {
        storedItem = null;
        cookedItem = null;
        cookingProgress.set(0);
        currentRecipe = null;
    }

    public static CookingRecipe<?> findCampfireRecipe(ItemStack input) {
        if (input == null || input.getType().isAir()) return null;

        Material inputType = input.getType();
        
        synchronized (recipeCache) {
            CookingRecipe<?> cached = recipeCache.get(inputType);
            if (cached != null) {
                var ingredient = cached.getInputChoice();
                if (ingredient != null && (ingredient.test(input) || ingredient.test(new ItemStack(inputType)))) {
                    return cached;
                }
            }
        }

        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof CookingRecipe<?> cookingRecipe) {
                var ingredient = cookingRecipe.getInputChoice();
                if (ingredient != null && (ingredient.test(input) || ingredient.test(new ItemStack(inputType)))) {
                    synchronized (recipeCache) {
                        recipeCache.put(inputType, cookingRecipe);
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
    
    public void updateDisplayEntity(org.bukkit.World world, BlockPosKey posKey, BlockFace facing) {
        removeDisplayEntity();
        
        if (storedItem == null || storedItem.getType().isAir()) return;
        
        Location displayLoc = new Location(world, posKey.x() + 0.5, posKey.y() + 0.1, posKey.z() + 0.5);
        
        ItemDisplay display = world.spawn(displayLoc, ItemDisplay.class);
        if (display != null) {
            display.setItemStack(storedItem.clone());
            display.setCustomNameVisible(false);
            
            float yaw = switch (facing) {
                case SOUTH -> 180f;
                case WEST -> 270f;
                case EAST -> 90f;
                default -> 0f;
            };
            display.setRotation(yaw, 0f);
            
            displayEntityId = display.getUniqueId();
        }
    }
    
    public void removeDisplayEntity() {
        if (displayEntityId == null) return;
        UUID entityId = displayEntityId;
        displayEntityId = null;
        
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            try {
                var entity = world.getEntity(entityId);
                if (entity instanceof ItemDisplay display && display.isValid()) {
                    display.remove();
                    return;
                }
            } catch (Exception e) {
                // Entity may already be removed
            }
        }
    }

    private ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }
}
