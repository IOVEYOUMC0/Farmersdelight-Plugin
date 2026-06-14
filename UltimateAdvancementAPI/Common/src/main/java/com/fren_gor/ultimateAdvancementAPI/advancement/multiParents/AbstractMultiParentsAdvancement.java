package com.fren_gor.ultimateAdvancementAPI.advancement.multiParents;

import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import com.google.common.base.Preconditions;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.Set;
import java.util.UUID;

import static com.fren_gor.ultimateAdvancementAPI.util.AdvancementUtils.progressionFromUUID;
import static com.fren_gor.ultimateAdvancementAPI.util.AdvancementUtils.uuidFromPlayer;

/**
 * The AbstractMultiParentsAdvancement class abstracts the implementation of any multi-parent advancement,
 * providing a standard supported by the API.
 * <p>A multi-parent advancement is an advancement that has more than one parent.
 * <p>An implementation of AbstractMultiParentsAdvancement is MultiParentsAdvancement.
 */
public abstract class AbstractMultiParentsAdvancement extends BaseAdvancement {

    /**
     * Creates a new AbstractMultiParentsAdvancement with a maximum progression of 1.
     *
     * @param key The unique key of the advancement. It must be unique among the other advancements of the tab.
     * @param display The display information of this advancement.
     * @param aParent One of the parents of this advancement.
     */
    public AbstractMultiParentsAdvancement(@NotNull String key, @NotNull AdvancementDisplay display, @NotNull BaseAdvancement aParent) {
        super(key, display, aParent);
    }

    /**
     * Creates a new AbstractMultiParentsAdvancement.
     *
     * @param key The unique key of the advancement. It must be unique among the other advancements of the tab.
     * @param display The display information of this advancement.
     * @param aParent One of the parents of this advancement.
     * @param maxProgression The maximum progression of the task.
     */
    public AbstractMultiParentsAdvancement(@NotNull String key, @NotNull AdvancementDisplay display, @NotNull BaseAdvancement aParent, @Range(from = 1, to = Integer.MAX_VALUE) int maxProgression) {
        super(key, display, aParent, maxProgression);
    }

    /**
     * Get a Set of the parents.
     *
     * @return A Set of the parents.
     */
    @NotNull
    public abstract Set<@NotNull BaseAdvancement> getParents();

    /**
     * Returns whether every parent advancement is granted for the provided player's team.
     *
     * @param player The player.
     * @return Whether every parent advancement is granted for the provided player's team.
     */
    public boolean isEveryParentGranted(@NotNull Player player) {
        return isEveryParentGranted(uuidFromPlayer(player));
    }

    /**
     * Returns whether every parent advancement is granted for the provided player's team.
     *
     * @param uuid The UUID of the player.
     * @return Whether every parent advancement is granted for the provided player's team.
     */
    public boolean isEveryParentGranted(@NotNull UUID uuid) {
        return isEveryParentGranted(progressionFromUUID(uuid, this));
    }

    /**
     * Returns whether every parent advancement is granted for the provided team.
     *
     * @param progression The TeamProgression of the team.
     * @return Whether every parent advancement is granted for the provided team.
     */
    public abstract boolean isEveryParentGranted(@NotNull TeamProgression progression);

    /**
     * Returns whether any parent advancement is granted for the provided player's team.
     *
     * @param player The player.
     * @return Whether any parent advancement is granted for the provided player's team.
     */
    public boolean isAnyParentGranted(@NotNull Player player) {
        return isAnyParentGranted(uuidFromPlayer(player));
    }

    /**
     * Returns whether any parent advancement is granted for the provided player's team.
     *
     * @param uuid The UUID of the player.
     * @return Whether any parent advancement is granted for the provided player's team.
     */
    public boolean isAnyParentGranted(@NotNull UUID uuid) {
        return isAnyParentGranted(progressionFromUUID(uuid, this));
    }

    /**
     * Returns whether any parent advancement is granted for the provided team.
     *
     * @param progression The TeamProgression of the team.
     * @return Whether any parent advancement is granted for the provided team.
     */
    public abstract boolean isAnyParentGranted(@NotNull TeamProgression progression);

    /**
     * Returns whether every parent and every grandparent advancements are granted for the provided player's team.
     *
     * @param player The player.
     * @return Whether either any parent or any grandparent advancement is granted for the provided player's team.
     */
    public boolean isEveryGrandparentGranted(@NotNull Player player) {
        return isEveryGrandparentGranted(uuidFromPlayer(player));
    }

    /**
     * Returns whether every parent and every grandparent advancements are granted for the provided player's team.
     *
     * @param uuid The UUID of the player.
     * @return Whether either any parent or any grandparent advancement is granted for the provided player's team.
     */
    public boolean isEveryGrandparentGranted(@NotNull UUID uuid) {
        return isEveryGrandparentGranted(progressionFromUUID(uuid, this));
    }

    /**
     * Returns whether every parent and every grandparent advancements are granted for the provided team.
     *
     * @param progression The TeamProgression of the team.
     * @return Whether either any parent or any grandparent advancement is granted for the provided team.
     */
    public abstract boolean isEveryGrandparentGranted(@NotNull TeamProgression progression);

    /**
     * Returns whether either any parent or any grandparent advancement is granted for the provided player's team.
     *
     * @param player The player.
     * @return Whether either any parent or any grandparent advancement is granted for the provided player's team.
     */
    public boolean isAnyGrandparentGranted(@NotNull Player player) {
        return isAnyGrandparentGranted(uuidFromPlayer(player));
    }

    /**
     * Returns whether either any parent or any grandparent advancement is granted for the provided player's team.
     *
     * @param uuid The UUID of the player.
     * @return Whether either any parent or any grandparent advancement is granted for the provided player's team.
     */
    public boolean isAnyGrandparentGranted(@NotNull UUID uuid) {
        return isAnyGrandparentGranted(progressionFromUUID(uuid, this));
    }

    /**
     * Returns whether either any parent or any grandparent advancement is granted for the provided team.
     *
     * @param progression The TeamProgression of the team.
     * @return Whether either any parent or any grandparent advancement is granted for the provided team.
     */
    public abstract boolean isAnyGrandparentGranted(@NotNull TeamProgression progression);

    /**
     * Returns the first element of the provided Set of BaseAdvancements. This method is intended to
     * be used to safely get an advancement from the Set of parent advancements passed as parameter in the subclass constructor.
     * The obtained parent advancement should be passed to AbstractMultiParentsAdvancement(String, AdvancementDisplay, BaseAdvancement, int).
     * <p>If the returned element is null or it doesn't exist (the Set is empty), an IllegalArgumentException is thrown.
     *
     * @param advancements The advancements of the Set.
     * @param <E> The type of the elements in the Set.
     * @return The first element of the Set.
     * @throws IllegalArgumentException If the Set is null, empty, or the first element is null.
     */
    @NotNull
    public static <E extends BaseAdvancement> E validateAndGetFirst(@NotNull Set<E> advancements) {
        Preconditions.checkNotNull(advancements, "Parent advancements are null.");
        Preconditions.checkArgument(advancements.size() > 0, "There must be at least 1 parent.");
        E e = advancements.iterator().next();
        Preconditions.checkNotNull(e, "A parent advancement is null.");
        return e;
    }
}
