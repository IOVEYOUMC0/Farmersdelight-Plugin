package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 进度（advancement）展示对象，其标题/描述使用资源包的翻译键（translation key），而非在
 * 服务端固化的字符串，因此每个客户端都会以自己的语言渲染它们。这里只重写了（非 NMS 的）
 * Component 取值方法；打过补丁的 UltimateAdvancementAPI 会读取它们用于 GUI、toast 和聊天栏。
 */
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

    // 没有 @Override：打过补丁的 UAA 才新增了这个方法，但我们是针对官方原版 API jar 编译的。
    // 在运行时，打过补丁的 UAA 会调用它以启用 Component（按客户端区分）的渲染方式。
    public boolean usesComponentDisplay() {
        return true;
    }

    @Override
    @NotNull
    public BaseComponent[] getChatTitle() {
        return new BaseComponent[]{colored(titleKey)};
    }

    @Override
    @NotNull
    public BaseComponent[] getChatDescription() {
        return new BaseComponent[]{colored(descriptionKey)};
    }

    // 与官方原版 UAA 保持一致：它会用 frame.getColor() 给标题/描述上色；不带颜色的 Component 会渲染成白色。
    private TranslatableComponent colored(String key) {
        TranslatableComponent component = new TranslatableComponent(key);
        component.setColor(frame.getColor());
        return component;
    }
}
