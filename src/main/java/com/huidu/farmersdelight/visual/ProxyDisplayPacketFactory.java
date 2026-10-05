package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.visual.ItemDisplayManager.DisplaySpec;
import com.huidu.farmersdelight.visual.ItemDisplayManager.TextDisplaySpec;
import it.unimi.dsi.fastutil.ints.IntList;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.bukkit.entity.data.BaseEntityData;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.craftengine.core.entity.display.TextDisplayAlignment;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Builds the raw client packets for proxy display entities from their specs. Stateless apart from the
// display ViewRange metadata, so ProxyItemDisplayManager keeps the visibility/sync/registry logic.
class ProxyDisplayPacketFactory {

    // Display ViewRange metadata: the client renders the display within ~ViewRange x 64 blocks. Derived
    // from the send distance so the client's render cutoff tracks the server's send cutoff — otherwise a
    // raised view-distance would send displays the client (ViewRange fixed at 1.0 ~= 64) refuses to draw.
    // Default 64 -> 1.0, unchanged. Mirrors CE furniture's viewRange-from-config approach.
    private volatile float viewRangeMeta = 1.0f;

    void reload(float viewRangeMeta) {
        this.viewRangeMeta = viewRangeMeta;
    }

    Object createItemSpawnPacket(int entityId, UUID entityUuid, DisplaySpec spec) {
        return buildSpawnPacket(entityId, entityUuid, spec.location(), EntityTypesProxy.ITEM_DISPLAY);
    }

    Object createTextSpawnPacket(int entityId, UUID entityUuid, TextDisplaySpec spec) {
        return buildSpawnPacket(entityId, entityUuid, spec.location(), EntityTypesProxy.TEXT_DISPLAY);
    }

    private Object buildSpawnPacket(int entityId, UUID entityUuid, Location location, Object entityType) {
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                entityId,
                entityUuid,
                location.getX(),
                location.getY(),
                location.getZ(),
                0.0F,
                0.0F,
                entityType,
                0,
                Vec3Proxy.ZERO,
                0.0D
        );
    }

    Object createPositionPacket(int entityId, Location location) {
        return EntityUtils.createUpdatePosPacket(entityId, location.getX(), location.getY(), location.getZ(), 0.0F, 0.0F, false);
    }

    Object createItemMetadataPacket(int entityId, DisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var wrappedItem = BukkitItemManager.instance().wrap(spec.itemStack());
        if (wrappedItem != null && !wrappedItem.isEmpty()) {
            DisplayData.ItemDisplayData.ItemStack.addEntityData(wrappedItem.minecraftItem(), values);
        } else if (spec.itemStack() != null && !spec.itemStack().getType().isAir()) {
            // The display item could not be wrapped into a client item, so the entity would render empty.
            // Surface it rather than showing a silently invisible display.
            I18n.logWarning("visual.item_display_no_client_item",
                    "type", spec.itemStack().getType().name());
        }

        var transformation = spec.transformation();
        DisplayData.ItemDisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.ItemDisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.ItemDisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.ItemDisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        if (spec.interpolationDurationTicks() > 0) {
            // Animate transform changes (e.g. the grill's skewer flip): the client tweens from its current
            // transform to this one over `duration` ticks, starting after `delay`. Only meaningful on an
            // update that changes the transform; on a fresh spawn/resync of an unchanged transform it is a
            // no-op tween. Floor is 1.21 (>=1.20.2), so only the Transformation* interpolation fields apply.
            DisplayData.TransformationInterpolationDelay.addEntityData(spec.interpolationDelayTicks(), values);
            DisplayData.TransformationInterpolationDuration.addEntityData(spec.interpolationDurationTicks(), values);
        }
        DisplayData.ItemDisplayData.ItemTransform.addEntityData(toCeDisplayContext(spec.itemTransform()), values);
        DisplayData.ItemDisplayData.ShadowRadius.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ShadowStrength.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Width.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Height.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ViewRange.addEntityData(viewRangeMeta, values);
        return createEntityDataPacket(entityId, values);
    }

    Object createTextMetadataPacket(int entityId, TextDisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var transformation = spec.transformation();
        DisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        // Billboard CENTER (3) — text always faces the viewer.
        DisplayData.BillboardConstraints.addEntityData((byte) 3, values);
        DisplayData.ViewRange.addEntityData(viewRangeMeta, values);

        // Text bodies for cooking-pot progress are pure ASCII ("100%" / "Apple 80%"), so a plain-text
        // round-trip through ComponentProxy.literal avoids the shaded-Adventure bridge in
        // CE's ComponentUtils.adventureToMinecraft (whose Component arg resolves to CE's relocated
        // adventure package, unreachable from addon compile classpath).
        String plain = PlainTextComponentSerializer.plainText().serialize(spec.text());
        Object componentValue = ComponentProxy.INSTANCE.literal(plain);
        DisplayData.TextDisplayData.Text.addEntityData(componentValue, values);
        DisplayData.TextDisplayData.BackgroundColor.addEntityData(colorToArgb(spec.backgroundColor()), values);
        byte flags = DisplayData.TextDisplayData.encodeFlags(
                spec.shadowed(),
                spec.seeThrough(),
                false,
                TextDisplayAlignment.CENTER);
        DisplayData.TextDisplayData.Flags.addEntityData(flags, values);
        return createEntityDataPacket(entityId, values);
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    /** A per-viewer ViewRange update, mirroring CE's setCulled: range 0 culls, the base range restores. */
    Object createViewRangePacket(int entityId, float range) {
        List<Object> values = new ArrayList<>(1);
        DisplayData.ViewRange.addEntityData(range, values, true);
        return createEntityDataPacket(entityId, values);
    }

    Object createDestroyPacket(int entityId) {
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(entityId));
    }

    private static int colorToArgb(Color color) {
        if (color == null) return 0;
        return (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private static byte toCeDisplayContext(ItemDisplay.ItemDisplayTransform transform) {
        if (transform == null) {
            return 0;
        }
        return switch (transform) {
            case THIRDPERSON_LEFTHAND -> 1;
            case THIRDPERSON_RIGHTHAND -> 2;
            case FIRSTPERSON_LEFTHAND -> 3;
            case FIRSTPERSON_RIGHTHAND -> 4;
            case HEAD -> 5;
            case GUI -> 6;
            case GROUND -> 7;
            case FIXED -> 8;
            default -> 0;
        };
    }
}
