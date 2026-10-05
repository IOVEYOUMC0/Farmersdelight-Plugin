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
 *
 *
 * Exists because a drop rule that lives in a CraftEngine pack has no other way to reach the
 * advancement system: the pack can grant the item, only the plugin can grant the advancement.
 *
 * functions:
 *   - type: farmersdelight:award_advancement
 *     advancement: get_ham
 *
 *
 * Awarding is skipped when the loot context carries no player, which is the case for every non-player
 * death (an unattended mob farm, another mob's kill, an explosion).
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
        AdvancementManager manager = FarmersDelightPlugin.getInstance() == null
                ? null
                : FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (manager == null) {
            return item;
        }
        // platformPlayer() hands back the platform handle as Object; only a Bukkit platform is present here
        // because the plugin runs on one.
        Object platformPlayer = context.player().platformPlayer();
        if (platformPlayer instanceof Player player) {
            manager.award(player, id);
        }
        return item;
    }
}
