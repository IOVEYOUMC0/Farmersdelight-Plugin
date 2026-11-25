package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.effect.EffectManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-side equivalent of the upstream Nourishment always-eat mixin. Upstream returns true from
 * Player#canEat while the player has Nourishment; Paper exposes no canEat hook, so a right-click with
 * food at full hunger briefly lowers the food level by one (enough for vanilla to start the consume) and the
 * normal restore runs on the next tick. The food's nutrition and on-consume effects still resolve through the
 * vanilla path. Players that quit inside that one-tick window are put back to their original level on quit so the
 * temporary drop never reaches the player data. Config: buff.nourishment-always-eat.
 */
public final class NourishmentEatListener implements Listener {

    private static final String CONFIG_PATH = "buff.nourishment-always-eat";
    private static final long RESTORE_DELAY_TICKS = 1L;

    private final FarmersDelightPlugin plugin;
    // Only an entry that outlives its restore task (player quit before the next tick) has to be repaired later.
    private final Map<UUID, Integer> pendingRestore = new ConcurrentHashMap<>();

    public NourishmentEatListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        // PlayerInteractEvent#isCancelled is deprecated in favour of the two result slots; a DENY on the item
        // slot is the one that stops vanilla from starting the consume.
        boolean itemUseDenied = event.useItemInHand() == Event.Result.DENY;
        if (!isFoodUse(event.getAction()) || itemUseDenied) {
            return;
        }
        Player player = event.getPlayer();
        boolean enabled = plugin.getConfigBoolean(true, CONFIG_PATH);
        boolean hasNourishment = EffectManager.hasNourishment(player);
        // Cheap gates run before any item component/meta work: this handler sees every right click, while
        // Nourishment + full hunger is the rare case that needs the item inspection below.
        if (!enabled || !hasNourishment || player.getFoodLevel() < NourishmentEatRules.FULL_FOOD_LEVEL) {
            return;
        }
        // Creative is invulnerable, so Player.canEat already returns true there; only the two survival modes
        // need the temporary drop. Spectator cannot interact at all.
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }
        UUID playerId = player.getUniqueId();
        // A second interaction in the same tick (main hand then off hand) already sees the lowered level.
        if (pendingRestore.containsKey(playerId)) {
            return;
        }
        ItemStack item = usedItem(player, event);
        if (item == null) {
            return;
        }
        FoodProperties food = foodProperties(item);
        boolean edible = food != null || item.getType().isEdible();
        // Vanilla already lets always-edible foods be eaten at full hunger; lowering the bar would only churn it.
        boolean canAlwaysEat = food != null ? food.canAlwaysEat() : metaCanAlwaysEat(item);
        if (!NourishmentEatRules.shouldLower(
                enabled, itemUseDenied, hasNourishment, player.getFoodLevel(), edible, canAlwaysEat)) {
            return;
        }

        int original = player.getFoodLevel();
        player.setFoodLevel(NourishmentEatRules.loweredFoodLevel(original));
        if (player.getFoodLevel() >= original) {
            // Another plugin cancelled the food-level change; nothing to restore.
            return;
        }
        pendingRestore.put(playerId, original);
        plugin.scheduler().runLaterForEntity(player, () -> restore(playerId, player), RESTORE_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Integer original = pendingRestore.remove(event.getPlayer().getUniqueId());
        if (original != null) {
            // The next-tick task was retired with the player; repair the temporary drop before the save.
            playerFoodLevel(event.getPlayer(), original);
        }
    }

    private void restore(UUID playerId, Player player) {
        Integer original = pendingRestore.remove(playerId);
        if (original == null) {
            return;
        }
        playerFoodLevel(player, original);
    }

    private static void playerFoodLevel(Player player, int original) {
        if (player.isOnline()) {
            player.setFoodLevel(NourishmentEatRules.restoredFoodLevel(player.getFoodLevel(), original));
        }
    }

    private static boolean isFoodUse(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private static ItemStack usedItem(Player player, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null) {
            return item;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return null;
        }
        return switch (hand) {
            case HAND -> player.getInventory().getItemInMainHand();
            case OFF_HAND -> player.getInventory().getItemInOffHand();
            default -> null;
        };
    }

    private static FoodProperties foodProperties(ItemStack item) {
        try {
            return item.getData(DataComponentTypes.FOOD);
        } catch (RuntimeException unsupported) {
            // A server older than the component API answers through the item meta path below instead.
            return null;
        }
    }

    private static boolean metaCanAlwaysEat(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasFood()) {
                return false;
            }
            FoodComponent food = meta.getFood();
            return food != null && food.canAlwaysEat();
        } catch (RuntimeException unsupported) {
            return false;
        }
    }
}
