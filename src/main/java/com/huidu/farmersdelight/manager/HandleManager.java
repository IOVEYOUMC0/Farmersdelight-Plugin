package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class HandleManager {

    private static final String DEFAULT_TOGGLE_SOUND = "minecraft:block.lantern.place";
    private static final float DEFAULT_TOGGLE_SOUND_VOLUME = 0.7F;
    private static final float DEFAULT_TOGGLE_SOUND_PITCH = 1.0F;

    private final FarmersDelightPlugin plugin;
    private String toggleSoundId;
    private float toggleSoundVolume;
    private float toggleSoundPitch;

    public HandleManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getFirstConfigSection("cooking-pot.handle", "handle");
        if (config == null) {
            config = new org.bukkit.configuration.MemoryConfiguration();
        }
        toggleSoundId = config.getString("toggle-sound", DEFAULT_TOGGLE_SOUND);
        toggleSoundVolume = (float) config.getDouble("toggle-sound-volume", DEFAULT_TOGGLE_SOUND_VOLUME);
        toggleSoundPitch = (float) config.getDouble("toggle-sound-pitch", DEFAULT_TOGGLE_SOUND_PITCH);
    }

    public boolean hasHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return false;
        return "handle".equals(getSupportProperty(world, potPos));
    }

    public void toggleHandle(World world, BlockPos potPos, @Nullable Player player) {
        if (world == null || potPos == null) return;
        Block potBlock = world.getBlockAt(potPos.x(), potPos.y(), potPos.z());
        if (!Constants.BLOCK_COOKING_POT.equals(CustomBlockUtils.getId(potBlock))) return;

        boolean had = hasHandle(world, potPos);
        if (had) {
            setSupportProperty(potBlock, "none");
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.checkAndPlaceTray(world, potPos);
        } else {
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.removeTrayIfAutoPlaced(world, potPos);
            setSupportProperty(potBlock, "handle");
        }
        if (player != null && toggleSoundVolume > 0) {
            SoundUtils.play(player, player.getLocation(), toggleSoundId, Sound.BLOCK_LANTERN_PLACE,
                    SoundCategory.BLOCKS, toggleSoundVolume, toggleSoundPitch);
        }
    }

    public void removeHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return;
        Block potBlock = world.getBlockAt(potPos.x(), potPos.y(), potPos.z());
        if (!Constants.BLOCK_COOKING_POT.equals(CustomBlockUtils.getId(potBlock))) return;
        if ("handle".equals(getSupportProperty(world, potPos))) {
            setSupportProperty(potBlock, "none");
        }
    }

    public void reload() {
        loadConfig();
    }

    public void cleanupAll() {}

    public void cleanupWorld(java.util.UUID worldId) {}

    // Internal helpers

    @Nullable
    private String getSupportProperty(World world, BlockPos potPos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(potPos.x(), potPos.y(), potPos.z()));
        if (state == null || state.isEmpty()) return null;
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return null;
        return state.getNullable(behavior.getSupportProperty());
    }

    private void setSupportProperty(Block block, String value) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) return;
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return;
        ImmutableBlockState next = state.with(behavior.getSupportProperty(), value);
        CraftEngineBlocks.place(block.getLocation(), next, false);
    }
}
