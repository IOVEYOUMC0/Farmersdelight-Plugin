package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.block.behavior.OrganicCompostBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RichSoilBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Right-clicking a vanilla brown/red mushroom onto a rich_soil or organic_compost block places the
 * corresponding CraftEngine mushroom colony at age 0 — a young colony that then grows to maturity via its
 * own random tick (mirroring the mod, where planting a mushroom on rich soil yields a small colony that
 * grows, not a fully grown one). A real vanilla mushroom cannot be used as the intermediate: the client
 * sees the soil's CraftEngine deceive-material and won't place it, and even placed server-side it fails
 * vanilla's canSurvive (CraftEngine settings.tags don't satisfy the vanilla mushroom_grow_block check), so
 * it would immediately pop off. Placing the CE colony directly keeps it alive.
 *
 * <p>RichSoilBlockBehavior#randomTick still converts a real mushroom sitting on rich soil (worldedit
 * / setblock / structure spawns) to an age-0 colony as a fallback. Colony block ids come from the soil
 * block's behavior config so renaming the colonies only needs a YAML edit.
 */
public final class MushroomOnRichSoilListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        if (event.getBlockFace() != BlockFace.UP) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getAmount() <= 0) return;
        boolean brown = item.getType() == Material.BROWN_MUSHROOM;
        boolean red = item.getType() == Material.RED_MUSHROOM;
        if (!brown && !red) return;

        Key colonyId = resolveColonyId(clicked, brown);
        if (colonyId == null) return;

        Block target = clicked.getRelative(BlockFace.UP);
        if (!target.getType().isAir()) return;

        // Respect WorldGuard build protection (mirrors SkilletPlaceListener). Without this a player with no
        // build permission could place a colony inside a protected region, since the vanilla placement that
        // WorldGuard would deny never fires for this custom-interaction path.
        if (!ProtectionCompat.canBuild(event.getPlayer(), target, ProtectionCompat.Feature.RICH_SOIL)) return;

        BlockDefinition colony = CraftEngineBlocks.byId(colonyId);
        if (colony == null) return;

        // Stop vanilla's placement attempt (which would fail at the client's canPlaceOn anyway) and suppress
        // its failed-interaction feedback so the block's own place sound is all the player hears.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Location loc = target.getLocation().add(0.5, 0, 0.5);
        if (!CraftEngineBlocks.place(loc, colonyAgeZero(colony), true)) return;

        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        event.getPlayer().swingHand(event.getHand());
    }

    /** The colony's age-0 state (young, grows to maturity) — its default state is age 3 for direct item placement. */
    @SuppressWarnings("unchecked")
    private static ImmutableBlockState colonyAgeZero(BlockDefinition colony) {
        ImmutableBlockState state = colony.defaultState();
        Property<Integer> age = (Property<Integer>) colony.getProperty("age");
        return age != null ? state.with(age, 0) : state;
    }

    /** Asks the clicked soil block's behavior which colony id corresponds to the held mushroom. */
    @Nullable
    private Key resolveColonyId(Block clicked, boolean brown) {
        RichSoilBlockBehavior richSoil = CustomBlockUtils.getBehavior(clicked, RichSoilBlockBehavior.class);
        if (richSoil != null) {
            return brown ? richSoil.getBrownMushroomColonyId() : richSoil.getRedMushroomColonyId();
        }
        OrganicCompostBlockBehavior compost = CustomBlockUtils.getBehavior(clicked, OrganicCompostBlockBehavior.class);
        if (compost != null) {
            return brown ? compost.getBrownMushroomColonyId() : compost.getRedMushroomColonyId();
        }
        return null;
    }
}
