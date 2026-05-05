package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.manager.TickManager;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CookingPotItemDataHelper {

    private static final String POT_ITEM_ID = Constants.ITEM_COOKING_POT;
    private static final int MAX_SERVINGS = 64;
    private static final int BAR_RESOLUTION = 4096;
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final String BLOCK_TYPE = "cooking_pot";

    private CookingPotItemDataHelper() {
    }

    public static boolean isEnabled() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("cooking-pot-packed-drop.enabled", true);
    }

    public static boolean isDurabilityBarEnabled() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("cooking-pot-packed-drop.durability-bar.enabled", true);
    }

    public static ItemStack createPackedPotItem(CookingPotBlockEntity entity) {
        ItemStack potItem = ItemUtils.createItem(POT_ITEM_ID);
        if (potItem == null || potItem.getType().isAir()) {
            return null;
        }
        return applyPackedData(potItem, entity);
    }

    public static ItemStack applyPackedData(ItemStack potItem, CookingPotBlockEntity entity) {
        if (potItem == null || potItem.getType().isAir() || entity == null) {
            return potItem;
        }

        ItemMeta meta = potItem.getItemMeta();
        if (meta == null) {
            return potItem;
        }

        try {
            meta.getPersistentDataContainer().set(key("packed"), PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(
                    key("instance_id"),
                    PersistentDataType.STRING,
                    UUID.randomUUID().toString()
            );

            byte[] inventoryBytes = serializeInventory(entity.getInventory());
            meta.getPersistentDataContainer().set(
                    key("inventory"),
                    PersistentDataType.BYTE_ARRAY,
                    inventoryBytes
            );

            ItemStack mealContainer = entity.getMealContainer();
            if (mealContainer != null && !mealContainer.getType().isAir()) {
                byte[] containerBytes = serializeSingleItem(mealContainer);
                meta.getPersistentDataContainer().set(
                        key("meal_container"),
                        PersistentDataType.BYTE_ARRAY,
                        containerBytes
                );
            } else {
                meta.getPersistentDataContainer().remove(key("meal_container"));
            }

            potItem.setItemMeta(meta);

            ItemStack preview = getMealPreview(entity.getInventory(), mealContainer);
            updatePackedLore(potItem, preview);
            updatePackedBar(potItem, preview);
            return potItem;
        } catch (IOException e) {
            FarmersDelightPlugin.getInstance().getLogger().warning(
                    "Failed to serialize packed cooking pot item: " + e.getMessage());
            return potItem;
        }
    }

    public static boolean restorePackedData(Location location, ItemStack potItem) {
        if (location == null || potItem == null || potItem.getType().isAir() || !hasPackedData(potItem)) {
            return false;
        }

        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        CookingPotBlockEntity entity = CookingPotBlockBehavior.getOrCreateBlockEntity(location);
        if (entity == null) {
            return false;
        }

        ItemMeta meta = potItem.getItemMeta();
        if (meta == null) {
            return false;
        }

        try {
            byte[] inventoryData = meta.getPersistentDataContainer().get(
                    key("inventory"), PersistentDataType.BYTE_ARRAY);
            if (inventoryData != null && inventoryData.length > 0) {
                ItemStack[] restored = deserializeInventory(inventoryData);
                for (int i = 0; i < restored.length && i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
                    entity.setInventorySlot(i, restored[i]);
                }
            }

            byte[] mealContainerData = meta.getPersistentDataContainer().get(
                    key("meal_container"), PersistentDataType.BYTE_ARRAY);
            if (mealContainerData != null && mealContainerData.length > 0) {
                entity.setMealContainer(deserializeSingleItem(mealContainerData));
            } else {
                entity.setMealContainer(null);
            }

            entity.setCookingProgress(0);
            entity.setCookingDuration(200);
            entity.tryMovePendingToOutput();

            saveEntityToStorage(world, entity);

            TickManager tickManager = FarmersDelightPlugin.getInstance().getTickManager();
            if (tickManager != null
                    && (entity.hasInput() || entity.hasPendingOutput() || entity.getMealDisplayItem() != null)) {
                tickManager.markActive(world, entity.getPosKey(), TickManager.BlockType.COOKING_POT);
            }
            return true;
        } catch (IOException e) {
            FarmersDelightPlugin.getInstance().getLogger().warning(
                    "Failed to restore packed cooking pot item: " + e.getMessage());
            return false;
        }
    }

    private static void saveEntityToStorage(World world, CookingPotBlockEntity entity) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage == null) return;

        Map<String, Object> data = new HashMap<>();
        ItemStack[] inventory = entity.getInventory();
        for (int i = 0; i < CookingPotBlockBehavior.INVENTORY_SIZE; i++) {
            ItemStack item = inventory[i];
            if (item != null && !item.getType().isAir()) {
                data.put("slot_" + i, item.clone());
            }
        }
        data.put("cookingProgress", entity.getCookingProgress());
        data.put("cookingDuration", entity.getCookingDuration());
        ItemStack mealContainer = entity.getMealContainer();
        if (mealContainer != null && !mealContainer.getType().isAir()) {
            data.put("mealContainer", mealContainer);
        }

        Location loc = entity.getPosKey().toLocation(world);
        storage.saveBlockData(loc, BLOCK_TYPE, data);
    }

    public static boolean hasPackedData(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(
                key("packed"), PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public static ItemStack getStoredMealPreview(ItemStack potItem) {
        if (!hasPackedData(potItem) || potItem.getItemMeta() == null) {
            return null;
        }

        try {
            byte[] inventoryData = potItem.getItemMeta().getPersistentDataContainer().get(
                    key("inventory"), PersistentDataType.BYTE_ARRAY);
            if (inventoryData == null || inventoryData.length == 0) {
                return null;
            }
            ItemStack[] inventory = deserializeInventory(inventoryData);
            ItemStack mealContainer = null;
            byte[] mealContainerData = potItem.getItemMeta().getPersistentDataContainer().get(
                    key("meal_container"), PersistentDataType.BYTE_ARRAY);
            if (mealContainerData != null && mealContainerData.length > 0) {
                mealContainer = deserializeSingleItem(mealContainerData);
            }
            return getMealPreview(inventory, mealContainer);
        } catch (IOException e) {
            return null;
        }
    }

    public static void refreshPackedVisuals(ItemStack potItem) {
        if (!hasPackedData(potItem) || potItem.getItemMeta() == null) {
            return;
        }

        ItemStack preview = getStoredMealPreview(potItem);
        updatePackedLore(potItem, preview);
        updatePackedBar(potItem, preview);
    }

    private static ItemStack getMealPreview(ItemStack[] inventory, ItemStack mealContainer) {
        if (inventory == null || inventory.length == 0) {
            return null;
        }

        ItemStack output = inventory.length > CookingPotBlockBehavior.SLOT_OUTPUT
                ? inventory[CookingPotBlockBehavior.SLOT_OUTPUT]
                : null;
        if (output != null && !output.getType().isAir()) {
            ItemStack preview = output.clone();
            ItemStack pending = inventory.length > CookingPotBlockBehavior.SLOT_MEAL_DISPLAY
                    ? inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY]
                    : null;
            if (pending != null && isSameMealPreviewType(preview, pending)) {
                preview.setAmount(Math.min(64, preview.getAmount() + pending.getAmount()));
            }
            return preview;
        }

        ItemStack pending = inventory.length > CookingPotBlockBehavior.SLOT_MEAL_DISPLAY
                ? inventory[CookingPotBlockBehavior.SLOT_MEAL_DISPLAY]
                : null;
        if (pending != null && !pending.getType().isAir()) {
            ItemStack preview = pending.clone();
            if (mealContainer != null && !mealContainer.getType().isAir()) {
                preview.setAmount(Math.min(preview.getAmount(), 64));
            }
            return preview;
        }

        return null;
    }

    private static boolean isSameMealPreviewType(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.getType().isAir() || second.getType().isAir()) {
            return false;
        }

        String firstCustomId = ItemUtils.getCustomItemId(first);
        String secondCustomId = ItemUtils.getCustomItemId(second);
        if (firstCustomId != null || secondCustomId != null) {
            return firstCustomId != null && firstCustomId.equals(secondCustomId);
        }

        ItemStack firstSingle = first.clone();
        firstSingle.setAmount(1);
        ItemStack secondSingle = second.clone();
        secondSingle.setAmount(1);
        return firstSingle.isSimilar(secondSingle);
    }

    private static void updatePackedLore(ItemStack potItem, ItemStack preview) {
        ItemMeta meta = potItem.getItemMeta();
        if (meta == null) return;

        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();

        String previousDynamicLine = meta.getPersistentDataContainer().get(
                key("dynamic_lore"), PersistentDataType.STRING);
        if (previousDynamicLine != null && !previousDynamicLine.isEmpty()) {
            lore.removeIf(component -> component != null
                    && previousDynamicLine.equals(PLAIN_TEXT.serialize(component)));
        }

        if (preview != null && !preview.getType().isAir()) {
            String name = ItemUtils.getDisplayName(preview, (String) null);
            String dynamicLore = I18n.formatNamed(
                    "gui.packed_pot.contains",
                    Map.of("amount", String.valueOf(preview.getAmount()), "item", name));
            lore.add(Component.text(dynamicLore));
            meta.getPersistentDataContainer().set(
                    key("dynamic_lore"), PersistentDataType.STRING, dynamicLore);
        } else {
            meta.getPersistentDataContainer().remove(key("dynamic_lore"));
        }

        meta.lore(lore.isEmpty() ? null : lore);
        potItem.setItemMeta(meta);
    }

    private static void updatePackedBar(ItemStack stack, ItemStack preview) {
        if (!isDurabilityBarEnabled() || preview == null || preview.getType().isAir()) {
            stack.unsetData(DataComponentTypes.MAX_DAMAGE);
            stack.unsetData(DataComponentTypes.DAMAGE);
            return;
        }

        int servings = Math.max(1, Math.min(MAX_SERVINGS, preview.getAmount()));
        double filledFraction = servings / (double) MAX_SERVINGS;
        int filledUnits = (int) Math.round(filledFraction * BAR_RESOLUTION);
        int damage = Math.max(0, BAR_RESOLUTION - filledUnits);
        stack.setData(DataComponentTypes.MAX_DAMAGE, BAR_RESOLUTION);
        stack.setData(DataComponentTypes.DAMAGE, damage);
    }

    private static byte[] serializeInventory(ItemStack[] inventory) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeInt(inventory.length);
            for (ItemStack item : inventory) {
                if (item != null && !item.getType().isAir()) {
                    byte[] bytes = item.serializeAsBytes();
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                } else {
                    dos.writeInt(0);
                }
            }
        }
        return Base64.getEncoder().encode(bos.toByteArray());
    }

    private static ItemStack[] deserializeInventory(byte[] data) throws IOException {
        byte[] raw = Base64.getDecoder().decode(data);
        ByteArrayInputStream bis = new ByteArrayInputStream(raw);
        DataInputStream dis = new DataInputStream(bis);
        int size = dis.readInt();
        ItemStack[] inventory = new ItemStack[Math.max(CookingPotBlockBehavior.INVENTORY_SIZE, size)];
        for (int i = 0; i < size; i++) {
            int len = dis.readInt();
            if (len > 0) {
                byte[] itemBytes = new byte[len];
                dis.readFully(itemBytes);
                inventory[i] = ItemStack.deserializeBytes(itemBytes);
            }
        }
        return inventory;
    }

    private static byte[] serializeSingleItem(ItemStack item) throws IOException {
        return Base64.getEncoder().encode(item.serializeAsBytes());
    }

    private static ItemStack deserializeSingleItem(byte[] data) throws IOException {
        byte[] itemBytes = Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(itemBytes);
    }

    private static NamespacedKey key(String path) {
        return new NamespacedKey(FarmersDelightPlugin.getInstance(), "packed_cooking_pot_" + path);
    }
}
