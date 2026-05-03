package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.entity.projectile.BukkitProjectileManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

public class AchievementListener implements Listener {

    private final Set<String> KNIFE_IDS = new HashSet<>();
    private final Set<String> FD_SEED_IDS = new HashSet<>();

    public AchievementListener() {
        loadConfig();
    }

    public void loadConfig() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) return;

        KNIFE_IDS.clear();
        KNIFE_IDS.addAll(plugin.getConfig().getStringList("knife-config.items"));

        FD_SEED_IDS.clear();
        ConfigurationSection nourishmentSection = plugin.getConfig().getConfigurationSection("nourishment-foods.foods");
        if (nourishmentSection != null) {
            FD_SEED_IDS.addAll(nourishmentSection.getKeys(false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        handleCraftedItem(player, event.getRecipe().getResult());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithItem(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        handleCraftedItem(player, event.getCurrentItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        if (!(event.getEntity() instanceof Raider)) return;
        if (!(projectile.getShooter() instanceof Player player)) return;

        BukkitProjectileManager projectileManager = BukkitProjectileManager.instance();
        if (projectileManager == null) return;

        String customItemId = projectileManager.projectileByEntityId(projectile.getEntityId())
                .map(customProjectile -> customProjectile.item().id().toString())
                .orElse(null);
        if ("farmersdelight:rotten_tomato".equals(customItemId)) {
            AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
            if (am != null) {
                am.award(player, "rotten_tomato_throw");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        checkSeedAdvancement(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am != null) {
            am.award(event.getPlayer(), "root");
        }
        checkSeedAdvancement(event.getPlayer());
    }

    private void handleCraftedItem(Player player, ItemStack result) {
        if (result == null || result.getType().isAir()) return;

        String customItemId = ItemUtils.getCustomItemId(result);
        if (customItemId == null) return;

        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null) return;

        if (KNIFE_IDS.contains(customItemId)) {
            am.award(player, "craft_knife");
        }

        if (customItemId.equals("farmersdelight:netherite_knife")) {
            am.award(player, "netherite_knife");
        }

        if (FD_SEED_IDS.contains(customItemId)) {
            am.award(player, "get_fd_seed");
        }

        if (customItemId.equals("farmersdelight:smoked_ham") ||
                customItemId.equals("farmersdelight:ham")) {
            am.award(player, "get_ham");
        }
    }

    private void checkSeedAdvancement(Player player) {
        AdvancementManager am = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (am == null || am.hasAdvancement(player, "get_fd_seed")) {
            return;
        }

        for (ItemStack item : player.getInventory().getContents()) {
            String customItemId = ItemUtils.getCustomItemId(item);
            if (customItemId != null && FD_SEED_IDS.contains(customItemId)) {
                am.award(player, "get_fd_seed");
                return;
            }
        }
    }
}
