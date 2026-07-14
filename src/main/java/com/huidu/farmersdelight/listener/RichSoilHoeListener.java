package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.block.behavior.RichSoilBlockBehavior;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public final class RichSoilHoeListener implements Listener {

    private static final Key RICH_SOIL_FARMLAND_KEY = Key.of(Constants.BLOCK_RICH_SOIL_FARMLAND);

    private final FarmersDelightPlugin plugin;

    public RichSoilHoeListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK) return;
        if (event.hand() != InteractionHand.MAIN_HAND) return;

        ItemStack item = event.item();
        if (item == null || !Tag.ITEMS_HOES.isTagged(item.getType())) return;

        if (!CustomBlockUtils.hasBehavior(event.blockState(), RichSoilBlockBehavior.class)) return;

        Block target = event.bukkitBlock();
        Block above = target.getRelative(0, 1, 0);
        if (!above.getType().isAir()) return;

        // Respect WorldGuard build protection (mirrors SkilletPlaceListener). Without this a player
        // with no build permission could till rich_soil into rich_soil_farmland inside a protected
        // region — this listener runs at HIGH and would otherwise place the block unconditionally.
        if (!ProtectionCompat.canBuild(event.player(), target, ProtectionCompat.Feature.RICH_SOIL)) return;

        BlockDefinition farmland = CraftEngineBlocks.byId(RICH_SOIL_FARMLAND_KEY);
        if (farmland == null) return;

        event.setCancelled(true);
        Location loc = target.getLocation().add(0.5, 0, 0.5);
        boolean placed = CraftEngineBlocks.place(loc, farmland.defaultState(), true);
        if (!placed) return;

        Player player = event.player();
        target.getWorld().playSound(target.getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.Sound.ITEM_HOE_TILL, 1.0F, 1.0F);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && item.getType() != Material.AIR) {
            item.damage(1, player);
        }
        AdvancementManager am = plugin.getAdvancementManager();
        if (am != null) {
            am.award(player, "hoe_rich_soil");
        }
    }
}
