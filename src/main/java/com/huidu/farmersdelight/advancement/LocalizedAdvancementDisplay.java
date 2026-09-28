package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class LocalizedAdvancementDisplay extends AdvancementDisplay {

    private final String titleKey;
    private final String descriptionKey;
    private final AdvancementFrameType frame;

    public LocalizedAdvancementDisplay(@NotNull ItemStack icon, @NotNull String titleKey, @NotNull String descriptionKey,
                                       @NotNull AdvancementFrameType frame, boolean showToast, boolean announceChat,
                                       float x, float y) {
        super(icon, titleKey, frame, showToast, announceChat, x, y, descriptionKey);
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.frame = frame;
    }

    // Enables per-client component rendering when the installed UAA supports this extension.
    public boolean usesComponentDisplay() {
        return true;
    }

    @Override
    @NotNull
    @SuppressWarnings("deprecation")
    public BaseComponent[] getChatTitle() {
        return new BaseComponent[]{colored(titleKey)};
    }

    @Override
    @NotNull
    @SuppressWarnings("deprecation")
    public BaseComponent[] getChatDescription() {
        return new BaseComponent[]{colored(descriptionKey)};
    }

    // Apply the frame color explicitly; an uncolored component would render white.
    @SuppressWarnings("deprecation")
    private TranslatableComponent colored(String key) {
        TranslatableComponent component = new TranslatableComponent(key);
        component.setColor(frame.getColor());
        return component;
    }
}
