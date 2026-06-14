package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.DisplayTransformUtils;
import com.huidu.farmersdelight.visual.ItemDisplayManager;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 遗留的煎锅状态模型，保留它是为了配方缓存辅助方法以及与旧存档状态的兼容性。
 * 运行时的煎锅逻辑由 com.huidu.farmersdelight.manager.SkilletManager 负责。
 */
@Deprecated(forRemoval = false)
public class SkilletBlockEntity {

    private static final int NO_DISPLAY = -1;
    
    private final BlockPosKey posKey;
    private ItemStack skilletStack;
    private ItemStack storedItem;
    private ItemStack cookedItem;
    private final AtomicInteger cookingProgress = new AtomicInteger(0);
    private final AtomicInteger cookingDuration = new AtomicInteger(getConfiguredDefaultCookingTime());
    private volatile CookingRecipe<?> currentRecipe;
    private volatile int fireAspectLevel = 0;
    private final Object cookingLock = new Object();
    private int displayEntityId = NO_DISPLAY;

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

    // 已删除遗留方法 setStoredItem(ItemStack)：本类从不被实例化（src 内无 new SkilletBlockEntity(...)
    // 调用），该方法是不可达死代码，且是已删除的 findCampfireRecipe 的唯一调用方。
    // 运行时煎锅逻辑由 SkilletManager 负责。

    public static int getSkilletCookingTime(int originalCookingTime, int fireAspectLevel) {
        int cookingTime = originalCookingTime > 0 ? originalCookingTime : getConfiguredDefaultCookingTime();
        int cookingSeconds = cookingTime / 20;
        double cookingTimeReduction = getConfiguredCookingTimeMultiplier();
        if (fireAspectLevel > 0) {
            cookingTimeReduction -= fireAspectLevel * getConfiguredFireAspectBonus();
        }
        cookingTimeReduction = Math.max(0.0D, cookingTimeReduction);
        
        int result = (int) (cookingSeconds * cookingTimeReduction) * 20;
        return Math.min(cookingTime, Math.max(getConfiguredMinimumCookingTime(), result));
    }

    private static int getConfiguredDefaultCookingTime() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? Constants.DEFAULT_COOKING_TIME_SKILLET
                : Math.max(1, plugin.getConfigInt(Constants.DEFAULT_COOKING_TIME_SKILLET,
                "skillet.cooking.default-cook-time",
                "skillet.default-cook-time"));
    }

    private static int getConfiguredMinimumCookingTime() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null
                ? SkilletBlockBehavior.MINIMUM_COOKING_TIME
                : Math.max(1, plugin.getConfigInt(SkilletBlockBehavior.MINIMUM_COOKING_TIME,
                "skillet.cooking.min-cook-time",
                "skillet.min-cook-time"));
    }

    private static double getConfiguredCookingTimeMultiplier() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        double value = plugin == null
                ? Constants.SKILLET_COOKING_TIME_REDUCTION
                : plugin.getConfigDouble(Constants.SKILLET_COOKING_TIME_REDUCTION,
                "skillet.cooking.cook-time-multiplier",
                "skillet.cooking-time-reduction");
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double getConfiguredFireAspectBonus() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        double value = plugin == null
                ? Constants.SKILLET_FIRE_ASPECT_BONUS
                : plugin.getConfigDouble(Constants.SKILLET_FIRE_ASPECT_BONUS,
                "skillet.cooking.fire-aspect-bonus",
                "skillet.fire-aspect-bonus");
        return Math.max(0.0D, Math.min(1.0D, value));
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

    // 已删除遗留方法 findCampfireRecipe(ItemStack) 及其 per-Material LinkedHashMap 缓存：
    // 运行时由 SkilletManager.findCampfireRecipe（委托给 CampfireRecipeCache）负责查找，
    // 本类的版本已无任何活调用方。

    /**
     * 保留此空实现是为了兼容重载/禁用流程（FarmersDelightPlugin 仍会调用它）。
     * 由于配方缓存已迁移到 SkilletManager，这里不再需要清理任何状态。
     */
    public static void clearRecipeCache() {
        // 无操作：缓存已迁移至 SkilletManager 的 CampfireRecipeCache。
    }

    public void updateDisplayEntity(org.bukkit.World world, BlockPosKey posKey, BlockFace facing) {
        removeDisplayEntity();
        if (storedItem == null || storedItem.getType().isAir()) return;

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
        if (visualManager == null || !visualManager.isAvailable()) return;

        Location displayLoc = new Location(world, posKey.x() + 0.5, posKey.y() + 0.1, posKey.z() + 0.5);
        ItemStack visualItem = storedItem.clone();
        visualItem.setAmount(1);

        float yaw = DisplayTransformUtils.skilletYaw(facing);

        org.joml.Quaternionf leftRotation = new org.joml.Quaternionf();
        leftRotation.rotationYXZ(
                (float) Math.toRadians(yaw),
                (float) Math.toRadians(90.0f),
                0.0f
        );

        displayEntityId = visualManager.createDisplay(new ItemDisplayManager.DisplaySpec(
                displayLoc,
                visualItem,
                ItemDisplay.ItemDisplayTransform.FIXED,
                new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(),
                        leftRotation,
                        new org.joml.Vector3f(0.5f, 0.5f, 0.5f),
                        new org.joml.Quaternionf()
                )
        ));
    }
    
    public void removeDisplayEntity() {
        if (displayEntityId == NO_DISPLAY) return;
        int entityId = displayEntityId;
        displayEntityId = NO_DISPLAY;

        ItemDisplayManager visualManager = FarmersDelightPlugin.getInstance().getItemDisplayManager();
        if (visualManager != null) {
            visualManager.destroyDisplay(entityId);
        }
    }

    private ItemStack cloneOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }
}
