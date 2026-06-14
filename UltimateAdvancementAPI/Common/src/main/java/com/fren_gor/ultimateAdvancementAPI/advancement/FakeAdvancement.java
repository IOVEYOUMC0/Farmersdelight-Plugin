package com.fren_gor.ultimateAdvancementAPI.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import com.fren_gor.ultimateAdvancementAPI.nms.wrappers.advancement.AdvancementDisplayWrapper;
import com.fren_gor.ultimateAdvancementAPI.nms.wrappers.advancement.AdvancementWrapper;
import com.fren_gor.ultimateAdvancementAPI.util.AfterHandle;
import com.google.common.base.Preconditions;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The FakeAdvancement class is a non-saved and non-registrable invisible advancement.
 * <p>This means that FakeAdvancements are not saved into the database and that they cannot be registered in any tab.
 * <p>They are also not displayed by clients, but the connection to their parent is still visible.
 * Thus, they can be sent in packets to display a connection.
 * <p>Since FakeAdvancements are not saved, many methods are not supported.
 */
public final class FakeAdvancement extends BaseAdvancement {

    private static final AtomicInteger FAKE_NUMBER = new AtomicInteger(1);

    /**
     * Creates a new FakeAdvancement.
     *
     * @param parent The parent of the advancement.
     * @param x The x coordinate of the advancement.
     * @param y The y coordinate of the advancement.
     */
    public FakeAdvancement(@NotNull Advancement parent, float x, float y) {
        this(parent, new FakeAdvancementDisplay(Material.GRASS_BLOCK, "FakeAdvancement", AdvancementFrameType.TASK, x, y));
    }

    /**
     * Creates a new FakeAdvancement.
     *
     * @param parent The parent of the advancement.
     * @param display The display information of this advancement.
     */
    public FakeAdvancement(@NotNull Advancement parent, @NotNull FakeAdvancementDisplay display) {
        super("fakeadvancement._-.-_." + FAKE_NUMBER.getAndIncrement(), display, parent);
    }

    /**
     * 
     */
    @Override
    @NotNull
    public FakeAdvancementDisplay getDisplay() {
        return (FakeAdvancementDisplay) super.getDisplay();
    }

    /**
     * 
     */
    @Override
    @NotNull
    public AdvancementWrapper getNMSWrapper() {
        return super.getNMSWrapper();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always 0.
     *
     * @return Always 0.
     */
    @Override
    public int getProgression(@NotNull Player player) {
        return 0;
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always 0.
     *
     * @return Always 0.
     */
    @Override
    public int getProgression(@NotNull UUID uuid) {
        return 0;
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always 0.
     *
     * @return Always 0.
     */
    @Override
    public int getProgression(@NotNull TeamProgression progression) {
        return 0;
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always true.
     *
     * @return Always true.
     */
    @Override
    @Contract("_ -> true")
    public boolean isVisible(@NotNull Player player) {
        return true;
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always true.
     *
     * @return Always true.
     */
    @Override
    @Contract("_ -> true")
    public boolean isVisible(@NotNull UUID uuid) {
        return true;
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method returns always true.
     *
     * @return Always true.
     */
    @Override
    @Contract("_ -> true")
    public boolean isVisible(@NotNull TeamProgression progression) {
        return true;
    }

    /**
     * 
     */
    @Override
    public void onUpdate(@NotNull TeamProgression teamProgression, @NotNull Map<AdvancementWrapper, Integer> addedAdvancements) {
        super.onUpdate(teamProgression, addedAdvancements);
    }

    /**
     * The AdvancementDisplay used by FakeAdvancements.
     *
     * @see FakeAdvancement
     */
    public static final class FakeAdvancementDisplay extends AdvancementDisplay {

        /**
         * Creates a new FakeAdvancementDisplay.
         *
         * @param icon The material of the item that will be shown in the GUI.
         * @param title The title of the advancement.
         * @param frame The shape of the advancement.
         * @param x The x coordinate of the advancement.
         * @param y The y coordinate of the advancement.
         */
        public FakeAdvancementDisplay(@NotNull Material icon, @NotNull String title, @NotNull AdvancementFrameType frame, float x, float y) {
            super(icon, title, frame, false, false, x, y, Collections.emptyList());
        }

        /**
         * Creates a new FakeAdvancementDisplay.
         *
         * @param icon The item that will be shown in the GUI.
         * @param title The title of the advancement.
         * @param frame The shape of the advancement.
         * @param x The x coordinate of the advancement.
         * @param y The y coordinate of the advancement.
         */
        public FakeAdvancementDisplay(@NotNull ItemStack icon, @NotNull String title, @NotNull AdvancementFrameType frame, float x, float y) {
            super(icon, title, frame, false, false, x, y, Collections.emptyList());
        }

        /**
         * 
         */
        @Override
        @NotNull
        public AdvancementDisplayWrapper getNMSWrapper(@NotNull Advancement advancement) {
            Preconditions.checkNotNull(advancement, "Advancement is null.");
            try {
                return AdvancementDisplayWrapper.craft(icon, title, compactDescription, frame.getNMSWrapper(), x, y, false, false, true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ============ Overridden methods which throw an UnsupportedOperationException ============

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public boolean isGranted(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public boolean isGranted(@NotNull UUID uuid) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public boolean isGranted(@NotNull TeamProgression progression) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Nullable BaseComponent[] getAnnounceMessage(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull UUID uuid) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull UUID uuid, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull UUID uuid, @Range(from = 0, to = Integer.MAX_VALUE) int increment) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull UUID uuid, @Range(from = 0, to = Integer.MAX_VALUE) int increment, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull Player player, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull Player player, @Range(from = 0, to = Integer.MAX_VALUE) int increment) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull Player player, @Range(from = 0, to = Integer.MAX_VALUE) int increment, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    protected @Range(from = 0, to = Integer.MAX_VALUE) int incrementProgression(@NotNull TeamProgression pro, @Nullable Player player, @Range(from = 0, to = Integer.MAX_VALUE) int increment, boolean giveRewards) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void setProgression(@NotNull UUID uuid, @Range(from = 0, to = Integer.MAX_VALUE) int progression) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void setProgression(@NotNull UUID uuid, @Range(from = 0, to = Integer.MAX_VALUE) int progression, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void setProgression(@NotNull Player player, @Range(from = 0, to = Integer.MAX_VALUE) int progression) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void setProgression(@NotNull Player player, @Range(from = 0, to = Integer.MAX_VALUE) int progression, boolean giveReward) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    protected void setProgression(@NotNull TeamProgression pro, @Nullable Player player, @Range(from = 0, to = Integer.MAX_VALUE) int progression, boolean giveRewards) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    protected void handlePlayer(@NotNull TeamProgression pro, @Nullable Player player, int newProgression, int oldProgression, boolean giveRewards, @Nullable AfterHandle afterHandle) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void displayToastToPlayer(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void onGrant(@NotNull Player player, boolean giveRewards) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void giveReward(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void grant(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void grant(@NotNull Player player, boolean giveRewards) {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     * Since FakeAdvancements are not saved, this method always throws an UnsupportedOperationException.
     *
     * @throws UnsupportedOperationException Always when it's called.
     */
    @Override
    public void revoke(@NotNull Player player) {
        throw new UnsupportedOperationException();
    }
}
