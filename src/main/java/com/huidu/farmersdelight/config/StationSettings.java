package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.block.CuttingBoardInteractionMode;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.util.Constants;

/** Immutable station settings published as one unit after a config reload. */
public record StationSettings(
        boolean progressDisplayEnabled,
        boolean showRecipeName,
        double progressYOffset,
        float progressScale,
        double progressVisibilityDistanceSquared,
        double progressLookDotThreshold,
        int progressUpdateIntervalTicks,
        int progressDisableAboveActivePots,
        CuttingBoardInteractionMode interactionMode,
        float failVolume,
        float failPitch,
        boolean hopperEnabled,
        boolean cookingPotHopperEnabled,
        boolean cuttingBoardHopperEnabled,
        boolean skilletHopperEnabled,
        boolean cookingPotPackContentsOnBreak,
        boolean skilletConductorsAllowed,
        float skilletDisplayScale,
        double skilletDisplayYOffset,
        double skilletDisplaySpread,
        float stoveDisplayScale) {

    public static StationSettings load(FarmersDelightPlugin plugin,
                                       CuttingBoardDisplayConfig skilletDisplay,
                                       CuttingBoardDisplayConfig stoveDisplay) {
        double distance = Math.max(1.0D, plugin.getConfigDouble(10.0D,
                "cooking-pot.progress-display.visibility-distance",
                "cooking-pot-progress-display.visibility-distance"));
        return new StationSettings(
                plugin.getConfigBoolean(true, "cooking-pot.progress-display.enabled",
                        "cooking-pot-progress-display.enabled"),
                plugin.getConfigBoolean(false, "cooking-pot.progress-display.show-recipe-name",
                        "cooking-pot-progress-display.show-recipe-name"),
                plugin.getConfigDouble(1.2D, "cooking-pot.progress-display.y-offset",
                        "cooking-pot-progress-display.y-offset"),
                Math.max(0.01F, (float) plugin.getConfigDouble(0.5D,
                        "cooking-pot.progress-display.scale", "cooking-pot-progress-display.scale")),
                distance * distance,
                Math.max(-1.0D, Math.min(1.0D, plugin.getConfigDouble(0.95D,
                        "cooking-pot.progress-display.look-dot-threshold",
                        "cooking-pot-progress-display.look-dot-threshold"))),
                Math.max(1, plugin.getConfigInt(8, "cooking-pot.progress-display.update-interval-ticks",
                        "cooking-pot-progress-display.update-interval-ticks")),
                Math.max(0, plugin.getConfigInt(512, "cooking-pot.progress-display.disable-above-active-pots",
                        "cooking-pot-progress-display.disable-above-active-pots")),
                CuttingBoardInteractionMode.parse(plugin.getConfig().getString(
                        "cutting-board.interaction-mode", "stacking")),
                (float) Math.max(0.0D, plugin.getConfigDouble(Constants.CUTTING_BOARD_FAIL_VOLUME,
                        "cutting-board.sounds.retrieve-volume")),
                (float) Math.max(0.0D, plugin.getConfigDouble(Constants.CUTTING_BOARD_FAIL_PITCH,
                        "cutting-board.sounds.retrieve-pitch")),
                plugin.getConfig().getBoolean("hopper-interactions.enabled", true),
                plugin.getConfigBoolean(true, "cooking-pot.hopper-interactions", "hopper-interactions.cooking-pot"),
                plugin.getConfigBoolean(true, "cutting-board.hopper-interactions", "hopper-interactions.cutting-board"),
                plugin.getConfigBoolean(true, "skillet.hopper-interactions", "hopper-interactions.skillet"),
                plugin.getConfig().getBoolean("cooking-pot.pack-contents-on-break", true),
                plugin.getConfigBoolean(true, "skillet.heat.allow-conductors", "heat-sources.skillet.allow-conductors"),
                skilletDisplay.getDefaultUniformScale(0.5F),
                skilletDisplay.getDefaultOffset().y(),
                skilletDisplay.getItemSpread(),
                stoveDisplay.getDefaultUniformScale(0.375F));
    }

    public boolean cookingPotHopperAllowed() {
        return hopperEnabled && cookingPotHopperEnabled;
    }

    public boolean cuttingBoardHopperAllowed() {
        return hopperEnabled && cuttingBoardHopperEnabled;
    }

    public boolean skilletHopperAllowed() {
        return hopperEnabled && skilletHopperEnabled;
    }
}
