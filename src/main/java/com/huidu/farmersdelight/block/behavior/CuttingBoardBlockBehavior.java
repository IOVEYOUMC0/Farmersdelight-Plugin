package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import com.huidu.farmersdelight.util.*;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.CustomItem;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CuttingBoardBlockBehavior extends BlockBehavior {

    private static final Map<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldBlockEntities = new ConcurrentHashMap<>();
    private static final String BLOCK_TYPE = "cutting_board";

    private final Property<?> facingProperty;
    private final List<Key> toolTags;
    private final List<Key> toolItems;
    private final String knifeSound;

    private CuttingBoardBlockBehavior(CustomBlock block, Property<?> facingProperty, List<Key> toolTags, List<Key> toolItems, String knifeSound) {
        super(block);
        this.facingProperty = facingProperty;
        this.toolTags = toolTags;
        this.toolItems = toolItems;
        this.knifeSound = knifeSound;
    }

    public static CuttingBoardBlockEntity getBlockEntity(World world, BlockPos pos) {
        return getBlockEntity(world, new BlockPosKey(pos));
    }

    public static CuttingBoardBlockEntity getBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return null;
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return null;
        return worldEntities.get(posKey);
    }

    public static Map<BlockPosKey, CuttingBoardBlockEntity> getAllBlockEntities(World world) {
        if (world == null) return Map.of();
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null) {
            return Map.copyOf(worldEntities);
        }
        return Map.of();
    }

    public static CuttingBoardBlockEntity putBlockEntity(World world, BlockPosKey posKey, CuttingBoardBlockEntity entity) {
        if (world == null || posKey == null || entity == null) {
            return entity;
        }
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        worldEntities.put(posKey, entity);
        return entity;
    }

    public static void removeBlockEntity(World world, BlockPos pos) {
        removeBlockEntity(world, new BlockPosKey(pos));
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey) {
        removeBlockEntity(world, posKey, true);
    }

    public static void removeBlockEntity(World world, BlockPosKey posKey, boolean removeStoredData) {
        if (world == null || posKey == null) return;
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities != null) {
            CuttingBoardBlockEntity entity = worldEntities.remove(posKey);
            if (entity != null) {
                entity.removeDisplayEntity();
                BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
                if (removeStoredData && storage != null) {
                    storage.removeBlockData(posKey.toLocation(world));
                }
            }
        }
    }

    public static void cleanupWorld(UUID worldId) {
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.remove(worldId);
        if (worldEntities != null) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                entity.removeDisplayEntity();
            }
            worldEntities.clear();
        }
    }

    public static void cleanupAll() {
        for (Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities : worldBlockEntities.values()) {
            for (CuttingBoardBlockEntity entity : worldEntities.values()) {
                entity.removeDisplayEntity();
            }
            worldEntities.clear();
        }
        worldBlockEntities.clear();
    }

    public static void saveAllData() {
        BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
        if (storage == null) return;

        for (Map.Entry<UUID, Map<BlockPosKey, CuttingBoardBlockEntity>> worldEntry : worldBlockEntities.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) continue;

            for (Map.Entry<BlockPosKey, CuttingBoardBlockEntity> posEntry : worldEntry.getValue().entrySet()) {
                BlockPosKey posKey = posEntry.getKey();
                saveBlockEntityData(world, posKey);
            }
        }
        storage.saveAll();
    }

    public static void saveBlockEntityData(World world, BlockPos pos) {
        saveBlockEntityData(world, new BlockPosKey(pos));
    }

    public static void saveBlockEntityData(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
        if (storage == null) return;

        if (!isCuttingBoardBlock(world, posKey)) {
            removeBlockEntity(world, posKey, true);
            return;
        }

        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.get(world.getUID());
        if (worldEntities == null) return;

        CuttingBoardBlockEntity entity = worldEntities.get(posKey);
        if (entity == null) return;

        if (entity.hasItem()) {
            Map<String, Object> data = new HashMap<>();
            data.put("storedItem", entity.getStoredItem());
            data.put("itemCarved", entity.isItemCarved());

            Location loc = posKey.toLocation(world);
            storage.saveBlockData(loc, BLOCK_TYPE, data);
        } else {
            storage.removeBlockData(posKey.toLocation(world));
        }
    }

    public static void loadBlockEntity(World world, BlockPos pos) {
        loadBlockEntity(world, new BlockPosKey(pos));
    }

    public static void loadBlockEntity(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) return;
        BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
        if (storage == null) return;

        Location loc = posKey.toLocation(world);
        if (!isCuttingBoardBlock(world, posKey)) {
            storage.removeBlockData(loc);
            removeBlockEntity(world, posKey, false);
            return;
        }

        Map<String, Object> data = storage.loadBlockData(loc, BLOCK_TYPE);
        if (data == null) return;

        CuttingBoardBlockEntity entity = new CuttingBoardBlockEntity(posKey, world);

        if (data.get("storedItem") instanceof ItemStack storedItem) {
            boolean itemCarved = data.get("itemCarved") instanceof Boolean carved && carved;
            entity.setItem(storedItem, world, posKey, getStoredBlockFacing(world, posKey), itemCarved);
        }

        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        worldEntities.put(posKey, entity);
    }

    public static boolean isCuttingBoardBlock(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return false;
        }
        return CustomBlockUtils.idContains(world.getBlockAt(posKey.x(), posKey.y(), posKey.z()), "cutting_board");
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<CuttingBoardBlockBehavior> FACTORY = new BlockBehaviorFactory<CuttingBoardBlockBehavior>() {
        @Override
        public CuttingBoardBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
            Property<?> facingProperty = block.getProperty("facing");

            List<String> toolTagStrings = getStringList(arguments, "tool-tags");
            if (toolTagStrings.isEmpty()) {
                toolTagStrings = List.of(Constants.TAG_KNIVES, Constants.TAG_AXES, Constants.TAG_PICKAXES);
            }

            List<Key> toolTags = toolTagStrings.stream()
                    .map(CuttingBoardBlockBehavior::normalizeTagKey)
                    .toList();

            List<String> toolItemStrings = getStringList(arguments, "tool-items");
            if (toolItemStrings.isEmpty()) {
                toolItemStrings = List.of(Constants.ITEM_SHEARS);
            }

            List<Key> toolItems = toolItemStrings.stream()
                    .map(Key::of)
                    .toList();

            String knifeSound = getArgumentString(arguments, "knife-sound", Constants.SOUND_CUTTING_BOARD_KNIFE);
            return new CuttingBoardBlockBehavior(block, facingProperty, toolTags, toolItems, knifeSound);
        }
    };

    public String getKnifeSound() {
        return knifeSound;
    }

    public static CuttingBoardBlockBehavior getBlockBehavior(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ImmutableBlockState state = CustomBlockUtils.getState(location);
        if (state == null) {
            return null;
        }
        var behavior = state.behavior();
        if (behavior instanceof CuttingBoardBlockBehavior cuttingBoardBehavior) {
            return cuttingBoardBehavior;
        }
        return null;
    }

    private static String getArgumentString(Map<String, Object> arguments, String key, String defaultValue) {
        if (arguments == null) {
            return defaultValue;
        }
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text;
    }

    private static Key normalizeTagKey(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1).trim();
        }
        return Key.of(text);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        BlockPosKey posKey = new BlockPosKey(pos);

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        if (!bukkitPlayer.hasPermission("farmersdelight.use.cutting_board")) {
            bukkitPlayer.sendActionBar(I18n.getComponent("general.no_permission", bukkitPlayer));
            return InteractionResult.FAIL;
        }

        World world = bukkitPlayer.getWorld();
        Map<BlockPosKey, CuttingBoardBlockEntity> worldEntities = worldBlockEntities.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        CuttingBoardBlockEntity blockEntity = worldEntities.get(posKey);
        if (blockEntity == null) {
            loadBlockEntity(world, posKey);
            blockEntity = worldEntities.get(posKey);
        }
        if (blockEntity == null) {
            blockEntity = new CuttingBoardBlockEntity(posKey, world);
            worldEntities.put(posKey, blockEntity);
        }

        ItemStack mainHand = bukkitPlayer.getInventory().getItemInMainHand();
        ItemStack offHand = bukkitPlayer.getInventory().getItemInOffHand();

        BlockFace facing = getFacing(state);

        if (blockEntity.hasItem()) {
            ItemStack tool = findMatchingTool(blockEntity, mainHand, offHand);
            if (tool != null) {
                boolean result = processCutting(blockEntity, tool, bukkitPlayer, facing, world, posKey);
                if (result) {
                    return InteractionResult.SUCCESS_AND_CANCEL;
                }
            } else if (isTool(mainHand) || isTool(offHand)) {
                bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.no_recipe", bukkitPlayer));
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
                return InteractionResult.SUCCESS_AND_CANCEL;
            } else if (!mainHand.getType().isAir() && !bukkitPlayer.isSneaking() && !isTool(mainHand)) {
                bukkitPlayer.sendActionBar(I18n.getComponent("messages.cutting_board.need_tool", bukkitPlayer));
                bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        if (!blockEntity.hasItem() && !mainHand.getType().isAir()) {
            ItemStack itemToPlace = mainHand.clone();
            itemToPlace.setAmount(1);
            boolean carveTool = bukkitPlayer.isSneaking() && isTool(mainHand);
            blockEntity.setItem(itemToPlace, world, posKey, facing, carveTool);
            if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                mainHand.setAmount(mainHand.getAmount() - 1);
            }
            Sound placeSound = carveTool ? Sound.ITEM_TRIDENT_HIT : Sound.BLOCK_WOOD_PLACE;
            float pitch = carveTool ? 1.2f : 1.0f;
            String soundKey = carveTool ? knifeSound : null;
            SoundUtils.play(bukkitPlayer.getWorld(), bukkitPlayer.getLocation(), soundKey, placeSound, 1.0f, pitch);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        if (blockEntity.hasItem() && mainHand.getType().isAir()) {
            ItemStack storedItem = blockEntity.getStoredItem();
            blockEntity.clearItem();
            removeStoredData(world, posKey);

            if (storedItem != null && !storedItem.getType().isAir() && bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                Map<Integer, ItemStack> leftovers = bukkitPlayer.getInventory().addItem(storedItem);
                if (!leftovers.isEmpty()) {
                    Location dropLocation = posKey.toLocation(world).add(0.5, 0.2, 0.5);
                    leftovers.values().forEach(item -> world.dropItemNaturally(dropLocation, item));
                }
            }

            bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.BLOCK_WOOD_HIT, 0.25f, 0.5f);
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.PASS;
    }

    @Override
    public void tick(Object thisBlock, Object[] args, Callable<Object> superMethod) {
        // Managed by interaction and block entity state.
    }

    private ItemStack findMatchingTool(CuttingBoardBlockEntity blockEntity, ItemStack mainHand, ItemStack offHand) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }

        if (matchesAnyRecipe(storedItem, mainHand)) {
            return mainHand;
        }
        if (matchesAnyRecipe(storedItem, offHand)) {
            return offHand;
        }
        return null;
    }

    private boolean matchesAnyRecipe(ItemStack storedItem, ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        return FarmersDelightPlugin.getInstance().getCuttingBoardRecipes().matchRecipe(storedItem, tool) != null;
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        try {
            String facingValue = facingProperty != null ? state.get(facingProperty).toString() : "north";
            return CustomBlockUtils.parseFacing(facingValue);
        } catch (Exception e) {
            return BlockFace.NORTH;
        }
    }

    private static BlockFace getStoredBlockFacing(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return BlockFace.NORTH;
        }

        try {
            return CustomBlockUtils.getFacing(posKey.toLocation(world).getBlock());
        } catch (Exception ignored) {
            return BlockFace.NORTH;
        }
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        if (isKnifeTool(item) || isAxeTool(item) || isPickaxeTool(item) || isConfiguredToolItem(item)) {
            return true;
        }

        String customId = ItemUtils.getCustomItemId(item);

        if (customId != null) {
            CustomItem<?> customItem = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .getCustomItem(Key.of(customId)).orElse(null);
            if (customItem != null) {
                Set<Key> itemTags = customItem.settings().tags();
                for (Key tag : toolTags) {
                    if (itemTags.contains(tag)) return true;
                }
            }
        }

        for (Key toolTag : toolTags) {
            var vanillaItems = FarmersDelightPlugin.getInstance().getCraftEngine().itemManager()
                    .vanillaItemIdsByTag(toolTag);
            for (var vanillaItem : vanillaItems) {
                if (vanillaItem.toString().equals("minecraft:" + item.getType().name().toLowerCase()))
                    return true;
            }
        }

        return false;
    }

    private boolean isKnifeTool(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            List<String> configuredKnives = FarmersDelightPlugin.getInstance()
                    .getConfig()
                    .getStringList("knife-config.items");
            if (configuredKnives.stream().anyMatch(id -> id.equalsIgnoreCase(customId))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAxeTool(ItemStack item) {
        return item.getType().name().endsWith("_AXE");
    }

    private boolean isPickaxeTool(ItemStack item) {
        return item.getType().name().endsWith("_PICKAXE");
    }

    private boolean isConfiguredToolItem(ItemStack item) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null && toolItems.contains(Key.of(customId))) {
            return true;
        }

        String vanillaId = "minecraft:" + item.getType().name().toLowerCase();
        return toolItems.contains(Key.of(vanillaId));
    }

    private boolean processCutting(CuttingBoardBlockEntity blockEntity, ItemStack tool, Player player, 
                                    BlockFace facing, World world, BlockPosKey posKey) {
        ItemStack storedItem = blockEntity.getStoredItem();
        if (storedItem == null) return false;

        CuttingBoardRecipe recipe = FarmersDelightPlugin.getInstance().getCuttingBoardRecipes()
                .matchRecipe(storedItem, tool);

        if (recipe == null) return false;

        Location location = player.getLocation();

        int fortuneLevel = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE);

        for (CuttingBoardRecipe.ResultEntry resultEntry : recipe.getResults()) {
            if (resultEntry.chance() < 1.0d && ThreadLocalRandom.current().nextDouble() > resultEntry.chance()) {
                continue;
            }

            ItemStack result = resultEntry.item().clone();

            if (fortuneLevel > 0 && isFortuneAffected(result)) {
                double bonusChance = 0.3 * fortuneLevel;
                if (ThreadLocalRandom.current().nextDouble() < bonusChance) {
                    result.setAmount(result.getAmount() + 1);
                }
            }

            spawnItemEntity(world, posKey, result, facing);
        }

        playCuttingFeedback(world, posKey, storedItem, recipe);
        swingToolHand(player, tool);

        if (player.getGameMode() != GameMode.CREATIVE) {
            if (tool.getItemMeta() instanceof Damageable damageable) {
                int maxDamage = tool.getType().getMaxDurability();
                int currentDamage = damageable.getDamage();
                if (currentDamage + 1 >= maxDamage) {
                    tool.setAmount(0);
                    player.playSound(location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                } else {
                    damageable.setDamage(currentDamage + 1);
                    tool.setItemMeta(damageable);
                }
            }
        }

        blockEntity.clearItem();
        removeStoredData(world, posKey);

        AdvancementManager advancementManager = FarmersDelightPlugin.getInstance().getAdvancementManager();
        if (advancementManager != null) {
            advancementManager.award(player, "use_cutting_board");
        }

        return true;
    }

    private void swingToolHand(Player player, ItemStack tool) {
        if (player == null || tool == null) {
            return;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand == tool) {
            player.swingOffHand();
        } else {
            player.swingMainHand();
        }
    }

    private void playCuttingFeedback(World world, BlockPosKey posKey, ItemStack storedItem, CuttingBoardRecipe recipe) {
        if (world == null || posKey == null) {
            return;
        }

        Location effectLocation = posKey.toLocation(world).add(0.5, 0.1, 0.5);
        if (ItemUtils.shouldUseBlockStyleDisplay(storedItem)) {
            BlockData blockData = storedItem.getType().createBlockData();
            SoundGroup soundGroup = blockData.getSoundGroup();
            world.playSound(effectLocation, soundGroup.getBreakSound(), 1.0f, 1.0f);
            world.spawnParticle(Particle.BLOCK, effectLocation, 10, 0.08, 0.05, 0.08, blockData);
            return;
        }

        SoundUtils.play(world, effectLocation, recipe.getSound(), Sound.BLOCK_WOOD_BREAK, 1.0f, 1.0f);
        world.spawnParticle(Particle.ITEM, effectLocation, 8, 0.08, 0.05, 0.08, 0.0, storedItem);
    }

    private static final Set<Material> FORTUNE_AFFECTED_MATERIALS = Set.of(
            Material.DIAMOND,
            Material.EMERALD,
            Material.COAL,
            Material.CHARCOAL,
            Material.REDSTONE,
            Material.LAPIS_LAZULI,
            Material.QUARTZ,
            Material.FLINT,
            Material.APPLE,
            Material.CARROT,
            Material.POTATO,
            Material.WHEAT,
            Material.BEETROOT,
            Material.MELON_SLICE,
            Material.PUMPKIN,
            Material.GLOWSTONE_DUST,
            Material.GOLD_NUGGET,
            Material.AMETHYST_SHARD,
            Material.RAW_IRON,
            Material.RAW_GOLD,
            Material.RAW_COPPER
    );

    private boolean isFortuneAffected(ItemStack item) {
        if (item == null) return false;
        return FORTUNE_AFFECTED_MATERIALS.contains(item.getType());
    }

    private void spawnItemEntity(World world, BlockPosKey posKey, ItemStack item, BlockFace facing) {
        if (world == null) return;

        BlockFace ejectFace = getCounterClockWise(facing);
        double offsetX = ejectFace.getModX() * 0.2;
        double offsetZ = ejectFace.getModZ() * 0.2;

        Location location = new Location(world,
                posKey.x() + 0.5 + offsetX,
                posKey.y() + 0.2,
                posKey.z() + 0.5 + offsetZ);

        int remaining = item.getAmount();
        int maxStackSize = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            ItemStack droppedStack = item.clone();
            droppedStack.setAmount(Math.min(remaining, maxStackSize));
            remaining -= droppedStack.getAmount();

            org.bukkit.entity.Item droppedItem = world.dropItemNaturally(location, droppedStack);
            droppedItem.setVelocity(new Vector(
                    ejectFace.getModX() * 0.2,
                    0.0,
                    ejectFace.getModZ() * 0.2
            ));
        }
    }

    private BlockFace getCounterClockWise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private void removeStoredData(World world, BlockPosKey posKey) {
        BlockStorageManager storage = FarmersDelightPlugin.getInstance().getBlockStorageManager();
        if (storage != null) {
            storage.removeBlockData(posKey.toLocation(world));
        }
    }

    private static List<String> getStringList(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return List.of();
        }
        Object value = arguments.get(key);
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
