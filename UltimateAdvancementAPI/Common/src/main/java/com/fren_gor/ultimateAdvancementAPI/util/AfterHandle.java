package com.fren_gor.ultimateAdvancementAPI.util;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Action done after player handle in Advancement#handlePlayer(TeamProgression, Player, int, int, boolean, AfterHandle).
 */
@FunctionalInterface
public interface AfterHandle {

    /**
     * The default AfterHandle used in Advancement#handlePlayer(TeamProgression, Player, int, int, boolean, AfterHandle).
     */
    public static final AfterHandle UPDATE_ADVANCEMENTS_TO_TEAM = (progression, player, adv) -> adv.getAdvancementTab().updateAdvancementsToTeam(progression);

    /**
     * The action to do after player handling.
     *
     * @param progression The player team's TeamProgression.
     * @param player The player.
     * @param advancement The advancement which called this method.
     * @see Advancement#handlePlayer(TeamProgression, Player, int, int, boolean, AfterHandle)
     */
    void apply(@NotNull TeamProgression progression, @Nullable Player player, @NotNull Advancement advancement);

}
