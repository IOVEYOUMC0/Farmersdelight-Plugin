package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * 背刺附魔伤害处理器。附魔定义在数据包中（farmersdelight:backstabbing），
 * 此处只处理「攻击者在目标背后」这一数据包无法表达的条件判断。
 */
public final class BackstabListener implements Listener {

    private static final NamespacedKey BACKSTAB_KEY = NamespacedKey.fromString("farmersdelight:backstabbing");

    private final FarmersDelightPlugin plugin;
    private Enchantment backstabEnchantment;
    private volatile boolean enabled;

    public BackstabListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 附魔台选项注入：enchantable/knife 为空标签，原版不生成背刺。
     * 仅对 CE 小刀按权重概率（背刺 weight=5，总权重约 25，≈20%）替换第一选项为背刺。
     * 概率使用附魔台位置种子，同一附魔台每次结果一致。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (!enabled) return;
        if (!ItemUtils.hasCustomItemTag(event.getItem(), Key.of("farmersdelight", "knives"))) return;
        Enchantment backstab = resolveEnchantment();
        if (backstab == null) return;

        // 数据包 backstabbing 的 weight 为 5，估算原版剑类附魔总权重约 25
        long seed = event.getEnchantBlock().hashCode() ^ event.getItem().hashCode();
        if (new Random(seed).nextInt(25) >= 5) return;

        EnchantmentOffer[] offers = event.getOffers();
        // 检查是否已存在（理论上不会，空标签不会生成）
        for (EnchantmentOffer offer : offers) {
            if (offer != null && backstab.equals(offer.getEnchantment())) return;
        }
        // 替换第一个选项
        int level = Math.min(event.getEnchantmentBonus() / 5 + 1, backstab.getMaxLevel());
        if (level < 1) level = 1;
        int cost = 15 + level * 9;
        offers[0] = new EnchantmentOffer(backstab, level, cost);
    }

    /**
     * 附魔结果注入：点击背刺选项时，将背刺加入实际结果。
     *（因为 supported_items 为空，原版不会自动应用。）
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (!enabled) return;
        if (!ItemUtils.hasCustomItemTag(event.getItem(), Key.of("farmersdelight", "knives"))) return;
        // 只有点了第1个选项（背刺被注入的槽位）才追加
        if (event.whichButton() != 0) return;
        Enchantment backstab = resolveEnchantment();
        if (backstab == null) return;

        int level = Math.min(event.whichButton() + 1, backstab.getMaxLevel());
        if (level < 1) level = 1;
        event.getEnchantsToAdd().put(backstab, level);
    }

    /**
     * 铁砧拦截：阻止背刺附魔书/物品与非法目标（非 CE 小刀）组合。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!enabled) return;
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty()) return;
        Enchantment ench = resolveEnchantment();
        if (ench == null) return;
        if (result.getEnchantmentLevel(ench) <= 0) return;
        // CE 小刀持有背刺合法，放行
        if (ItemUtils.hasCustomItemTag(result, Key.of("farmersdelight", "knives"))) return;
        // 非法目标 → 阻止铁砧操作
        event.setResult(null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon.isEmpty()) return;

        // 只对 farmersdelight:knives 标签内的小刀生效（CE 标签系统，不走 PDC）
        if (!ItemUtils.hasCustomItemTag(weapon, Key.of("farmersdelight", "knives"))) return;

        int level = weapon.getEnchantmentLevel(resolveEnchantment());
        if (level <= 0) return;

        if (!isBehindTarget(target, player)) return;

        // 倍率对齐原模组：1级1.4x，2级1.6x，3级1.8x
        double multiplier = 1.2 + level * 0.2;
        event.setDamage(event.getDamage() * multiplier);

        // 原模组背刺命中播放暴击音效 + 暴击粒子
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
    }

    private Enchantment resolveEnchantment() {
        if (backstabEnchantment != null) return backstabEnchantment;
        backstabEnchantment = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(BACKSTAB_KEY);
        return backstabEnchantment;
    }

    /**
     * 判断攻击者是否在目标背后。
     * 用目标朝向与「目标→攻击者连线」的点积判定：
     * 点积 &lt; -0.5 说明夹角大于约 100°，即攻击者在身后。
     */
    private static boolean isBehindTarget(LivingEntity target, Player attacker) {
        Vector targetFacing = target.getLocation().getDirection().setY(0).normalize();
        Vector toAttacker = attacker.getLocation().toVector()
                .subtract(target.getLocation().toVector())
                .setY(0);
        if (toAttacker.lengthSquared() < 0.01) return false;
        toAttacker.normalize();
        return targetFacing.dot(toAttacker) < -0.5;
    }
}
