package com.fren_gor.ultimateAdvancementAPI.visibilities;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import org.jetbrains.annotations.NotNull;

/**
 * The core interface of the Advancement Visibility System.
 * <p>A sub-interface of IVisibility is suitable for the Advancement Visibility System if it provides a
 * default implementation to the isVisible(Advancement, TeamProgression) method.
 * A suitable sub-interface can be implemented by any Advancement sub-class to change the visibility of that
 * advancement.
 * <p>When Advancement#isVisible(TeamProgression) is called, it calls the default method of the first
 * suitable interface found or returns true if no suitable interface can be found.
 * <p>See Advancement#isVisible(TeamProgression) for a more complete explanation of the search algorithm.
 * <p>Note that classes that overrides that method and does not call the Advancement one disables the
 * Advancement Visibility System. Thus, isVisible(Advancement, TeamProgression) will not be called in that case.
 */
public interface IVisibility {

    /**
     * Whether the provided advancement is visible for the specified team.
     *
     * @param advancement The advancement.
     * @param progression The team TeamProgression.
     * @return Whether the advancement is visible for the specified team.
     */
    boolean isVisible(@NotNull Advancement advancement, @NotNull TeamProgression progression);
}
