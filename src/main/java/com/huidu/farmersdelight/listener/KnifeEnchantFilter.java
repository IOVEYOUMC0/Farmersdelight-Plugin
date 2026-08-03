package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;

import java.util.Random;

/**
 * 小刀附魔台注入器：以原版附魔权重概率为 CE 小刀添加时运附魔选项。
 * <p>
 * 由于 CE 自定义物品的 ID 在原版数据包加载时尚未注册，无法通过原版标签链
 * 让小刀获得时运。此监听器在附魔台生成选项后，以时运的原版权重（2，约 14%）
 * 概率将末尾选项替换为时运，使用附魔台位置种子确保同一次附魔结果一致。
 */
public final class KnifeEnchantFilter implements Listener {

    private static final Key KNIFE_TAG = Key.of("farmersdelight", "knives");
    private static final int FORTUNE_MAX_LEVEL = 3;
    /** 时运权重 2，剑类附魔总权重约 40 → 单槽概率 ~5%，三槽累计约 14% */
    private static final int FORTUNE_WEIGHT = 2;
    private static final int TOTAL_WEIGHT = 40;

    public KnifeEnchantFilter(FarmersDelightPlugin plugin) {
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (!ItemUtils.hasCustomItemTag(event.getItem(), KNIFE_TAG)) return;

        var offers = event.getOffers();
        int silkTouchIdx = -1;
        boolean hasFortune = false;

        for (int i = 0; i < offers.length; i++) {
            if (offers[i] == null) continue;
            Enchantment ench = offers[i].getEnchantment();
            if (ench == Enchantment.SILK_TOUCH) {
                silkTouchIdx = i;
            } else if (ench == Enchantment.FORTUNE) {
                hasFortune = true;
            }
        }

        if (hasFortune) {
            if (silkTouchIdx >= 0) {
                offers[silkTouchIdx] = null;
            }
            return;
        }

        // 按时运权重概率决定是否注入，种子 = 附魔台位置 ^ 物品，同一次附魔台结果一致
        long seed = event.getEnchantBlock().hashCode() ^ event.getItem().hashCode();
        if (new Random(seed).nextInt(TOTAL_WEIGHT) >= FORTUNE_WEIGHT) return;

        int targetSlot = silkTouchIdx >= 0 ? silkTouchIdx : offers.length - 1;
        int level = Math.min(event.getEnchantmentBonus() / 6 + 1, FORTUNE_MAX_LEVEL);
        if (level < 1) level = 1;
        int cost = 15 + level * 9;
        offers[targetSlot] = new EnchantmentOffer(Enchantment.FORTUNE, level, cost);
    }
}
