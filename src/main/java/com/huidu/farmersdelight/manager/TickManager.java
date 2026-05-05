package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ManagerSupport;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

public class TickManager {

    private final FarmersDelightPlugin plugin;
    private BukkitTask tickTask;
    private BukkitTask cleanupTask;
    private volatile boolean running = false;
    
    private final Set<ActiveBlock> activeBlocks = ConcurrentHashMap.newKeySet();
    private final ReentrantLock pendingLock = new ReentrantLock();
    private final Set<ActiveBlock> pendingAdditions = new HashSet<>();
    private final Set<ActiveBlock> pendingRemovals = new HashSet<>();

    private static final int TICK_INTERVAL = 4;
    private static final int MAX_CACHE_SIZE = 1000;
    private static final int CLEANUP_INTERVAL = 6000;
    public TickManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) return;
        running = true;
        
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_INTERVAL);
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::performCleanup, CLEANUP_INTERVAL, CLEANUP_INTERVAL);
        plugin.getLogger().info("TickManager started with interval " + TICK_INTERVAL + " ticks");
    }

    public void stop() {
        running = false;
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        
        pendingLock.lock();
        try {
            activeBlocks.clear();
            pendingAdditions.clear();
            pendingRemovals.clear();
        } finally {
            pendingLock.unlock();
        }
        
        plugin.getLogger().info("TickManager stopped");
    }
    
    private void performCleanup() {
        int cleanedCount = 0;
        
        cleanedCount += cleanupInvalidBlockEntities(
            CookingPotBlockBehavior::getAllBlockEntities,
            CookingPotBlockBehavior::removeBlockEntity,
            "cooking_pot"
        );
        
        if (cleanedCount > 0) {
            plugin.getLogger().info("Cleanup completed: removed " + cleanedCount + " invalid block entities");
        }
    }
    
    private interface BlockEntityGetter<T> {
        Map<BlockPosKey, T> getAll(World world);
    }
    
    private interface BlockEntityRemover {
        void remove(World world, BlockPosKey posKey);
    }
    
    private <T> int cleanupInvalidBlockEntities(BlockEntityGetter<T> getter, BlockEntityRemover remover, String blockIdContains) {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPosKey, T> entities = getter.getAll(world);
            for (BlockPosKey posKey : entities.keySet()) {
                Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
                ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                
                if (state == null || state.isEmpty()) {
                    remover.remove(world, posKey);
                    count++;
                    continue;
                }
                
                String blockId = CustomBlockUtils.getId(state);
                if (blockId == null || !blockId.contains(blockIdContains)) {
                    remover.remove(world, posKey);
                    count++;
                }
            }
        }
        return count;
    }
    
    public void registerActiveBlock(World world, BlockPosKey posKey, BlockType type) {
        ActiveBlock block = new ActiveBlock(world.getUID(), posKey, type);
        pendingLock.lock();
        try {
            pendingAdditions.add(block);
            pendingRemovals.remove(block);
        } finally {
            pendingLock.unlock();
        }
    }
    
    public void unregisterActiveBlock(World world, BlockPosKey posKey, BlockType type) {
        ActiveBlock block = new ActiveBlock(world.getUID(), posKey, type);
        pendingLock.lock();
        try {
            pendingRemovals.add(block);
            pendingAdditions.remove(block);
        } finally {
            pendingLock.unlock();
        }
    }
    
    public void markActive(World world, BlockPosKey posKey, BlockType type) {
        registerActiveBlock(world, posKey, type);
    }

    private void tick() {
        if (!running) return;
        
        pendingLock.lock();
        try {
            if (!pendingAdditions.isEmpty()) {
                activeBlocks.addAll(pendingAdditions);
                pendingAdditions.clear();
            }
            
            if (!pendingRemovals.isEmpty()) {
                activeBlocks.removeAll(pendingRemovals);
                pendingRemovals.clear();
            }
        } finally {
            pendingLock.unlock();
        }
        
        if (activeBlocks.isEmpty()) return;
        
        if (activeBlocks.size() > MAX_CACHE_SIZE) {
            plugin.getLogger().warning("Active blocks count exceeds " + MAX_CACHE_SIZE + ", consider optimizing");
        }
        
        for (ActiveBlock activeBlock : activeBlocks) {
            World world = Bukkit.getWorld(activeBlock.worldId);
            if (world == null) continue;
            
            try {
                switch (activeBlock.type) {
                    case COOKING_POT -> tickCookingPot(world, activeBlock.posKey);
                    default -> throw new IllegalArgumentException("Unexpected value: " + activeBlock.type);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error ticking " + activeBlock.type + " at " + activeBlock.posKey + ": " + e.getMessage());
            }
        }
    }

    private void tickCookingPot(World world, BlockPosKey posKey) {
        Block block = world.getBlockAt(posKey.x(), posKey.y(), posKey.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        
        if (state == null || state.isEmpty()) {
            unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
            CookingPotBlockBehavior.removeBlockEntity(world, posKey);
            return;
        }
        
        String blockId = CustomBlockUtils.getId(state);
        if (blockId == null || !blockId.contains("cooking_pot")) {
            unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
            return;
        }
        
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(world, posKey);
        if (entity == null) {
            unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
            return;
        }

        // Keep buffer -> output progression in the pot tick, matching the old plugin behavior
        // instead of relying only on GUI refreshes or manual output pickup.
        entity.tryMovePendingToOutput();

        if (!entity.hasInput() && !entity.hasPendingOutput()) {
            unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
            return;
        }

        Block blockBelow = world.getBlockAt(posKey.x(), posKey.y() - 1, posKey.z());
        boolean hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockBelow);
        
        if (!hasHeat) {
            Block blockTwoBelow = world.getBlockAt(posKey.x(), posKey.y() - 2, posKey.z());
            if (plugin.getHeatSourceConfig().isConductor(blockBelow)) {
                hasHeat = plugin.getHeatSourceConfig().isHeatSource(blockTwoBelow);
            }
        }

        entity.setHasHeatSource(hasHeat);
        emitCookingPotEffects(world, posKey, entity, hasHeat);

        if (hasHeat && entity.canCook()) {
            CookingPotRecipe recipe = entity.getCurrentRecipe();
            if (recipe != null) {
                entity.setCookingDuration(recipe.getCookTime());
            }
            
            for (int i = 0; i < TICK_INTERVAL; i++) {
                entity.incrementCookingProgress();
                
                if (entity.getCookingProgress() >= entity.getCookingDuration()) {
                    Location blockLoc = ManagerSupport.toLocation(world, posKey);
                    if (blockLoc == null) {
                        unregisterActiveBlock(world, posKey, BlockType.COOKING_POT);
                        return;
                    }
                    if (entity.finishCooking(world, blockLoc)) {
                        // Keep the pot active after a successful cook so remaining
                        // ingredients can immediately start the next batch, matching
                        // the old plugin behavior.
                        break;
                    }
                    break;
                }
            }
        } else if (entity.getCookingProgress() > 0) {
            for (int i = 0; i < TICK_INTERVAL * 2; i++) {
                entity.decrementCookingProgress();
            }
        }

        if (entity.getCookingProgress() > 0 && entity.getCurrentRecipe() != null) {
            CookingPotRecipe recipe = entity.getCurrentRecipe();
            ItemStack result = recipe.getResult();
            CookingPotBlockBehavior.setCookingRecipeItem(posKey, result);
            CookingPotBlockBehavior.updateProgressDisplay(world, posKey, entity.getProgressPercent());
        } else {
            CookingPotBlockBehavior.removeProgressDisplay(world, posKey);
        }
    }

    private void emitCookingPotEffects(World world, BlockPosKey posKey, CookingPotBlockEntity entity, boolean hasHeat) {
        if (!hasHeat) {
            return;
        }

        boolean hasActivity = entity.hasInput()
                || entity.hasPendingOutput()
                || entity.getCookingProgress() > 0
                || entity.getCurrentRecipe() != null;
        if (!hasActivity) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        CookingPotBlockBehavior behavior = CookingPotBlockBehavior.getBlockBehavior(posKey.toLocation(world));
        Location center = ManagerSupport.toLocation(world, posKey);
        if (center == null) {
            return;
        }
        center.add(0.5, 0.9, 0.5);

        ConfigurationSection effectSection = plugin.getConfig().getConfigurationSection("cooking-pot-effects");

        ConfigurationSection bubbleSection = effectSection != null
                ? effectSection.getConfigurationSection("bubble")
                : null;
        if (isEffectEnabled(bubbleSection, true) && random.nextFloat() < getChance(bubbleSection, 0.20f)) {
            double x = center.getX() + (random.nextDouble() * 0.6D - 0.3D);
            double y = center.getY() + getDouble(bubbleSection, "y-offset", 0.0D);
            double z = center.getZ() + (random.nextDouble() * 0.6D - 0.3D);
            Particle particle = resolveParticle(bubbleSection, Particle.BUBBLE_POP);
            world.spawnParticle(
                    particle,
                    x, y, z,
                    Math.max(1, getCount(bubbleSection, 1)),
                    getDouble(bubbleSection, "offset-x", 0.0D),
                    getDouble(bubbleSection, "offset-y", 0.0D),
                    getDouble(bubbleSection, "offset-z", 0.0D),
                    getDouble(bubbleSection, "speed", 0.01D)
            );
        }

        ConfigurationSection steamSection = effectSection != null
                ? effectSection.getConfigurationSection("steam")
                : null;
        if (isEffectEnabled(steamSection, true) && random.nextFloat() < getChance(steamSection, 0.05f)) {
            double x = center.getX() + (random.nextDouble() * 0.4D - 0.2D);
            double y = center.getY() + getDouble(steamSection, "y-offset", 0.08D);
            double z = center.getZ() + (random.nextDouble() * 0.4D - 0.2D);
            Particle particle = resolveParticle(steamSection, Particle.CLOUD);
            int steamCount = Math.max(1, getCount(steamSection, 1));
            double motionX = getDouble(steamSection, "offset-x", 0.0D);
            double motionY = getDouble(steamSection, "offset-y", 0.03D);
            double motionZ = getDouble(steamSection, "offset-z", 0.0D);
            double speed = Math.max(0.001D, getDouble(steamSection, "speed", 0.02D));
            for (int i = 0; i < steamCount; i++) {
                world.spawnParticle(
                        particle,
                        x, y, z,
                        0,
                        motionX,
                        motionY + (random.nextDouble() * 0.01D),
                        motionZ,
                        speed
                );
            }

            ConfigurationSection secondarySection = steamSection != null
                    ? steamSection.getConfigurationSection("secondary")
                    : null;
            if (secondarySection != null && isEffectEnabled(secondarySection, false)) {
                Particle secondaryParticle = resolveParticle(secondarySection, Particle.SMOKE);
                int secondaryCount = Math.max(1, getCount(secondarySection, 1));
                double secondaryMotionX = getDouble(secondarySection, "offset-x", 0.0D);
                double secondaryMotionY = getDouble(secondarySection, "offset-y", 0.025D);
                double secondaryMotionZ = getDouble(secondarySection, "offset-z", 0.0D);
                double secondarySpeed = Math.max(0.001D, getDouble(secondarySection, "speed", 0.02D));
                for (int i = 0; i < secondaryCount; i++) {
                    world.spawnParticle(
                            secondaryParticle,
                            x,
                            y + getDouble(secondarySection, "y-offset", 0.05D),
                            z,
                            0,
                            secondaryMotionX,
                            secondaryMotionY + (random.nextDouble() * 0.01D),
                            secondaryMotionZ,
                            secondarySpeed
                    );
                }
            }
        }

        float soundChance = 0.10f;
        if (behavior != null && behavior.getSoundChance() != null) {
            soundChance = behavior.getSoundChance().floatValue();
        }
        if (random.nextFloat() < soundChance) {
            boolean soupReady = entity.hasPendingOutput() || entity.getMealDisplayItem() != null;
            String configuredSound;
            if (soupReady) {
                String soupBoilSound = null;
                if (behavior != null) {
                    soupBoilSound = behavior.getSoupBoilSound();
                }
                configuredSound = firstNonBlank(soupBoilSound, Constants.SOUND_COOKING_POT_BOIL_SOUP);
            } else {
                String boilSound = null;
                if (behavior != null) {
                    boilSound = behavior.getBoilSound();
                }
                configuredSound = firstNonBlank(boilSound, Constants.SOUND_COOKING_POT_BOIL);
            }
            ResolvedSound boilSound = resolveSound(
                    configuredSound,
                    soupReady ? Sound.BLOCK_BREWING_STAND_BREW : Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP
            );
            float volume = 0.5f;
            if (behavior != null && behavior.getSoundVolume() != null) {
                volume = behavior.getSoundVolume().floatValue();
            }
            float pitchMin = 0.9f;
            if (behavior != null && behavior.getSoundPitchMin() != null) {
                pitchMin = behavior.getSoundPitchMin().floatValue();
            }
            float pitchMax = 1.1f;
            if (behavior != null && behavior.getSoundPitchMax() != null) {
                pitchMax = behavior.getSoundPitchMax().floatValue();
            }
            float pitch = pitchMin;
            if (pitchMin < pitchMax) {
                pitch = pitchMin + random.nextFloat() * (pitchMax - pitchMin);
            }
            playConfiguredSound(world, center, boilSound, volume, pitch);
        }
    }

    private boolean isEffectEnabled(ConfigurationSection section, boolean defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getBoolean("enabled", defaultValue);
    }

    private float getChance(ConfigurationSection section, float defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return (float) section.getDouble("chance", defaultValue);
    }

    private int getCount(ConfigurationSection section, int defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getInt("count", defaultValue);
    }

    private double getDouble(ConfigurationSection section, String key, double defaultValue) {
        if (section == null) {
            return defaultValue;
        }
        return section.getDouble(key, defaultValue);
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private Particle resolveParticle(ConfigurationSection section, Particle defaultParticle) {
        if (section == null) {
            return defaultParticle;
        }

        String configured = section.getString("type");
        if (configured == null || configured.isBlank()) {
            return defaultParticle;
        }

        String normalized = configured.trim();
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        try {
            return Particle.valueOf(normalized.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return defaultParticle;
        }
    }

    private ResolvedSound resolveSound(String configured, Sound defaultSound) {
        if (configured == null || configured.isBlank()) {
            return ResolvedSound.fromBukkit(defaultSound);
        }

        String trimmed = configured.trim();
        String registryKey;
        if (trimmed.contains(":")) {
            registryKey = trimmed.toLowerCase();
        } else {
            registryKey = "minecraft:" + trimmed.toLowerCase().replace('_', '.');
        }
        Sound registrySound = Registry.SOUNDS.get(NamespacedKey.fromString(registryKey));
        if (registrySound != null) {
            return ResolvedSound.fromBukkit(registrySound);
        }

        NamespacedKey customKey;
        if (trimmed.contains(":")) {
            customKey = NamespacedKey.fromString(trimmed);
        } else {
            customKey = NamespacedKey.fromString(registryKey);
        }
        if (customKey != null) {
            return ResolvedSound.fromKey(customKey.toString());
        }

        return ResolvedSound.fromBukkit(defaultSound);
    }

    private void playConfiguredSound(World world, Location location, ResolvedSound sound, float volume, float pitch) {
        if (sound.bukkitSound() != null) {
            world.playSound(location, sound.bukkitSound(), volume, pitch);
            return;
        }
        if (sound.soundKey() != null && !sound.soundKey().isBlank()) {
            world.playSound(location, sound.soundKey(), SoundCategory.BLOCKS, volume, pitch);
        }
    }

    private record ResolvedSound(Sound bukkitSound, String soundKey) {
        private static ResolvedSound fromBukkit(Sound sound) {
            return new ResolvedSound(sound, null);
        }

        private static ResolvedSound fromKey(String key) {
            return new ResolvedSound(null, key);
        }
    }

    public enum BlockType {
        SKILLET,
        STOVE,
        COOKING_POT
    }
    
    private record ActiveBlock(UUID worldId, BlockPosKey posKey, BlockType type) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActiveBlock that = (ActiveBlock) o;
            return worldId.equals(that.worldId) && posKey.equals(that.posKey) && type == that.type;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * worldId.hashCode() + posKey.hashCode()) + type.hashCode();
        }
    }
}
