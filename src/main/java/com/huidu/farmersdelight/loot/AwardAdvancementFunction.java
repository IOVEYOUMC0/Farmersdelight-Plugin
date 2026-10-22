package com.huidu.farmersdelight.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.loot.LootContext;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.loot.function.LootFunctionFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.entity.Player;

/**
 * Loot function that awards one of this plugin's advancements to the killer and passes the item through
 * unchanged.
 */
public final class AwardAdvancementFunction implements LootFunction {

    public static final LootFunctionFactory<AwardAdvancementFunction> FACTORY = AwardAdvancementFunction::new;

    private final String advancementId;

    private AwardAdvancementFunction(ConfigSection section) {
        this.advancementId = section.getString("advancement");
    }

    @Override
    public Item apply(Item item, LootContext context) {
        String id = advancementId;
        if (id == null || id.isEmpty() || !context.isPlayerPresent()) {
            return item;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        AdvancementManager manager = plugin == null ? null : plugin.getAdvancementManager();
        if (manager == null) {
            return item;
        }
        // platformPlayer() hands back the platform handle as Object; only a Bukkit platform is present here
        // because the plugin runs on one.
        var contextPlayer = context.player();
        Object platformPlayer = contextPlayer == null ? null : contextPlayer.platformPlayer();
        if (platformPlayer instanceof Player player) {
            manager.award(player, id);
        }
        return item;
    }
}
