package com.huidu.farmersdelight.item.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.manager.SkilletManager;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.PermissionChecker;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Portable skillet interaction independent of the placed skillet block behavior. */
public final class SkilletItemBehavior extends ItemBehavior {

    public static final ItemBehaviorFactory<SkilletItemBehavior> FACTORY = new Factory();

    private final String permission;
    private final NamespacedKey cookingModel;
    private final NamespacedKey ingredientOverlayModel;
    private final Map<String, NamespacedKey> ingredientModels;

    private SkilletItemBehavior(String permission, NamespacedKey cookingModel,
                                NamespacedKey ingredientOverlayModel, Map<String, NamespacedKey> ingredientModels) {
        this.permission = permission;
        this.cookingModel = cookingModel;
        this.ingredientOverlayModel = ingredientOverlayModel;
        this.ingredientModels = Map.copyOf(ingredientModels);
    }

    public NamespacedKey cookingModel() {
        return cookingModel;
    }

    public NamespacedKey ingredientOverlayModel() {
        return ingredientOverlayModel;
    }

    @Override
    public InteractionResult use(World world,
                                 net.momirealms.craftengine.core.entity.player.Player contextPlayer,
                                 InteractionHand hand) {
        return interact(contextPlayer, hand);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        Player player = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (player == null) return InteractionResult.PASS;

        if (player.isSneaking()) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        Block clicked = player.getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
        if (SkilletBlockBehavior.getBlockBehavior(clicked.getLocation()) != null) {
            return InteractionResult.PASS;
        }
        return interact(context.getPlayer(), context.getHand());
    }

    private InteractionResult interact(net.momirealms.craftengine.core.entity.player.Player contextPlayer,
                                       InteractionHand hand) {
        Player player = ItemUtils.getBukkitPlayer(contextPlayer);
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        SkilletManager manager = plugin == null ? null : plugin.getSkilletManager();
        if (player == null || manager == null || !PermissionChecker.check(player, permission)) {
            return InteractionResult.PASS;
        }
        EquipmentSlot slot = hand == InteractionHand.OFF_HAND
                ? EquipmentSlot.OFF_HAND
                : EquipmentSlot.HAND;
        if (!manager.handleHandheldInteract(player, slot, cookingModel, ingredientOverlayModel, ingredientModels)) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    private static final class Factory implements ItemBehaviorFactory<SkilletItemBehavior> {
        @Override
        public SkilletItemBehavior create(Pack pack, Path path, Key key,
                                          ConfigSection section) {
            String permission = section == null
                    ? "farmersdelight.use.skillet"
                    : section.getString("permission", "farmersdelight.use.skillet");
            NamespacedKey cookingModel = section == null ? null : section.getValue("cooking-model",
                    value -> NamespacedKey.fromString(value.getAsIdentifier().asString()));
            NamespacedKey overlayModel = section == null ? null : section.getValue("ingredient-overlay-model",
                    value -> NamespacedKey.fromString(value.getAsIdentifier().asString()));
            Map<String, NamespacedKey> models = new HashMap<>();
            ConfigSection modelSection = section == null ? null : section.getSection("ingredient-models");
            if (modelSection != null) {
                for (String ingredient : modelSection.keySet()) {
                    NamespacedKey ingredientKey = NamespacedKey.fromString(ingredient);
                    if (ingredientKey == null) {
                        throw new KnownResourceException(ConfigConstants.PARSE_IDENTIFIER_FAILED,
                                modelSection.assemblePath(ingredient), ingredient);
                    }
                    models.put(ingredientKey.toString(), NamespacedKey.fromString(
                            modelSection.getNonNullIdentifier(ingredient).asString()));
                }
            }
            return new SkilletItemBehavior(permission, cookingModel, overlayModel, models);
        }
    }
}
