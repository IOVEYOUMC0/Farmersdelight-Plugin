package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import com.huidu.farmersdelight.tool.ToolData;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.ItemUtils;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.TagKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable enchantment-table and anvil support for CraftEngine knives.
 *
 * <p>Follows the vanilla modified-level algorithm. Weight, level cost bounds, conflicts and anvil cost
 * come from Paper's live enchantment registry. Anvil enchantments auto-inherit the table list plus mending.</p>
 */
public final class KnifeEnchantFilter implements Listener {

    /** Hardcoded anvil tuning values previously exposed as config knobs. */
    private static final int ANVIL_CONFLICT_PENALTY = 1;
    private static final int ANVIL_MINIMUM_REPAIR_COST = 1;

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, PreparedOffers> preparedOffers = new ConcurrentHashMap<>();
    private volatile EnchantmentSettings settings = EnchantmentSettings.defaults();
    private volatile List<Enchantment> tableEnchantments = List.of();
    private volatile Set<Enchantment> anvilEnchantments = Set.of();

    public KnifeEnchantFilter(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reload(plugin.getEnchantmentSettings(), plugin.isBackstabEnchantmentEnabled());
    }

    public void reload(EnchantmentSettings newSettings, boolean backstabbingEnabled) {
        settings = newSettings == null ? EnchantmentSettings.defaults() : newSettings;
        tableEnchantments = resolveEnchantments(settings.table().enchantments(), backstabbingEnabled);
        // 铁砧自动继承附魔台列表 + 经验修补
        Set<Enchantment> anvilSet = new LinkedHashSet<>(tableEnchantments);
        Enchantment mending = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft("mending"));
        if (mending != null) {
            anvilSet.add(mending);
        }
        anvilEnchantments = Set.copyOf(anvilSet);
        preparedOffers.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        UUID playerId = event.getEnchanter().getUniqueId();
        preparedOffers.remove(playerId);

        EnchantmentSettings current = settings;
        EnchantmentSettings.Table table = current.table();
        ItemStack item = event.getItem();
        if (!current.enabled() || !table.enabled() || !isKnife(item) || tableEnchantments.isEmpty()) {
            return;
        }

        Player player = event.getEnchanter();
        int seed = player.getEnchantmentSeed();
        int bonus = Math.min(event.getEnchantmentBonus(), 15);
        Random costRandom = new Random(seed);
        int[] costs = new int[3];
        @SuppressWarnings("unchecked")
        Map<Enchantment, Integer>[] choices = (Map<Enchantment, Integer>[]) new Map<?, ?>[3];
        int enchantability = enchantability(item, table);
        EnchantmentOffer[] offers = event.getOffers();

        for (int slot = 0; slot < choices.length; slot++) {
            costs[slot] = calculateSlotCost(costRandom, slot, bonus);
            if (costs[slot] < slot + 1) {
                costs[slot] = 0;
            }
            choices[slot] = costs[slot] <= 0
                    ? Map.of()
                    : selectEnchantments(tableEnchantments, costs[slot], new Random((long) seed + slot), enchantability);

            if (table.overrideOffers() && offers != null && slot < offers.length) {
                if (choices[slot].isEmpty()) {
                    offers[slot] = null;
                } else {
                    Map.Entry<Enchantment, Integer> first = choices[slot].entrySet().iterator().next();
                    offers[slot] = new EnchantmentOffer(first.getKey(), first.getValue(), costs[slot]);
                }
            }
        }

        preparedOffers.put(playerId, PreparedOffers.capture(event, choices));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        EnchantmentSettings current = settings;
        EnchantmentSettings.Table table = current.table();
        if (!current.enabled() || !table.enabled() || !isKnife(event.getItem())) {
            return;
        }

        int button = event.whichButton();
        PreparedOffers prepared = preparedOffers.remove(event.getEnchanter().getUniqueId());
        Map<Enchantment, Integer> selected = prepared == null ? Map.of() : prepared.selection(event, button);
        if (selected.isEmpty() && button >= 0 && button < 3) {
            int enchantability = enchantability(event.getItem(), table);
            selected = selectEnchantments(
                    tableEnchantments,
                    event.getExpLevelCost(),
                    new Random((long) event.getEnchanter().getEnchantmentSeed() + button),
                    enchantability
            );
        }
        if (selected.isEmpty()) {
            return;
        }

        Map<Enchantment, Integer> additions = event.getEnchantsToAdd();
        if (table.overrideOffers()) {
            additions.clear();
            additions.putAll(selected);
            return;
        }
        // 非覆盖模式总是附加（100% 概率）
        for (Map.Entry<Enchantment, Integer> entry : selected.entrySet()) {
            if (!conflictsWithAny(entry.getKey(), additions.keySet())) {
                additions.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        EnchantmentSettings current = settings;
        if (!current.enabled() || !current.anvilEnabled() || anvilEnchantments.isEmpty()) {
            return;
        }

        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        if (isEmpty(first) || isEmpty(second) || !isKnife(first)) {
            return;
        }
        ItemMeta secondMeta = second.getItemMeta();
        if (!(secondMeta instanceof EnchantmentStorageMeta storage) || storage.getStoredEnchants().isEmpty()) {
            return;
        }

        ItemStack vanillaResult = event.getResult();
        ItemStack result = isEmpty(vanillaResult) ? first.clone() : vanillaResult.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) {
            return;
        }

        Map<Enchantment, Integer> original = first.getEnchantments();
        Set<Enchantment> present = new LinkedHashSet<>(original.keySet());
        Map<Enchantment, Integer> merged = new LinkedHashMap<>();
        int addedCost = 0;
        for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!anvilEnchantments.contains(enchantment)) {
                continue;
            }
            if (conflictsWithAnyExcept(enchantment, present, enchantment)) {
                addedCost += ANVIL_CONFLICT_PENALTY;
                continue;
            }

            int oldLevel = original.getOrDefault(enchantment, 0);
            int bookLevel = entry.getValue();
            int level = oldLevel == bookLevel && oldLevel < enchantment.getMaxLevel()
                    ? oldLevel + 1
                    : Math.max(oldLevel, bookLevel);
            level = Math.min(level, enchantment.getMaxLevel());
            if (level <= oldLevel) {
                continue;
            }
            if (result.getEnchantmentLevel(enchantment) >= level) {
                present.add(enchantment);
                continue;
            }
            merged.put(enchantment, level);
            present.add(enchantment);
            addedCost += Math.max(0, enchantment.getAnvilCost()) * level;
        }
        if (merged.isEmpty()) {
            return;
        }

        for (Map.Entry<Enchantment, Integer> entry : merged.entrySet()) {
            resultMeta.addEnchant(entry.getKey(), entry.getValue(), true);
        }
        result.setItemMeta(resultMeta);
        event.setResult(result);

        int repairCost = Math.max(ANVIL_MINIMUM_REPAIR_COST, event.getView().getRepairCost() + addedCost);
        Player player = (Player) event.getView().getPlayer();
        var view = event.getView();
        plugin.scheduler().runLaterForEntity(player, () -> {
            var openView = player.getOpenInventory();
            if (player.isOnline()
                    && openView instanceof org.bukkit.inventory.view.AnvilView openAnvilView
                    && openView.getTopInventory().equals(view.getTopInventory())) {
                openAnvilView.setRepairCost(repairCost);
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        preparedOffers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() == InventoryType.ENCHANTING) {
            preparedOffers.remove(event.getPlayer().getUniqueId());
        }
    }

    static int calculateSlotCost(Random random, int slot, int bonus) {
        int base = random.nextInt(8) + 1 + (bonus >> 1) + random.nextInt(bonus + 1);
        return switch (slot) {
            case 0 -> Math.max(base / 3, 1);
            case 1 -> base * 2 / 3 + 1;
            case 2 -> Math.max(base, bonus * 2);
            default -> base;
        };
    }

    static int modifyLevel(Random random, int level, int enchantability) {
        int spread = Math.max(1, enchantability / 4 + 1);
        int modified = level + 1 + random.nextInt(spread) + random.nextInt(spread);
        float variance = (random.nextFloat() + random.nextFloat() - 1.0F) * 0.15F;
        return Math.max(1, Math.round(modified * (1.0F + variance)));
    }

    private static Map<Enchantment, Integer> selectEnchantments(
            List<Enchantment> allowed,
            int offeredLevel,
            Random random,
            int enchantability
    ) {
        Map<Enchantment, Integer> selected = new LinkedHashMap<>();
        if (allowed.isEmpty() || offeredLevel < 1) {
            return selected;
        }

        int modifiedLevel = modifyLevel(random, offeredLevel, enchantability);
        List<Candidate> candidates = candidates(allowed, modifiedLevel);
        Candidate first = chooseWeighted(candidates, random);
        if (first == null) {
            return selected;
        }
        selected.put(first.enchantment(), first.level());

        while (random.nextInt(50) <= modifiedLevel) {
            candidates.removeIf(candidate -> selected.containsKey(candidate.enchantment())
                    || conflictsWithAny(candidate.enchantment(), selected.keySet()));
            Candidate next = chooseWeighted(candidates, random);
            if (next == null) {
                break;
            }
            selected.put(next.enchantment(), next.level());
            modifiedLevel /= 2;
        }
        return selected;
    }

    private static List<Candidate> candidates(List<Enchantment> allowed, int modifiedLevel) {
        List<Candidate> candidates = new ArrayList<>();
        for (Enchantment enchantment : allowed) {
            for (int level = enchantment.getMaxLevel(); level >= enchantment.getStartLevel(); level--) {
                if (modifiedLevel >= enchantment.getMinModifiedCost(level)
                        && modifiedLevel <= enchantment.getMaxModifiedCost(level)) {
                    candidates.add(new Candidate(enchantment, level, Math.max(1, enchantment.getWeight())));
                    break;
                }
            }
        }
        return candidates;
    }

    private static Candidate chooseWeighted(List<Candidate> candidates, Random random) {
        if (candidates.isEmpty()) {
            return null;
        }
        int totalWeight = candidates.stream().mapToInt(Candidate::weight).sum();
        int value = random.nextInt(Math.max(1, totalWeight));
        for (Candidate candidate : candidates) {
            value -= candidate.weight();
            if (value < 0) {
                return candidate;
            }
        }
        return candidates.getLast();
    }

    private int enchantability(ItemStack item, EnchantmentSettings.Table table) {
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            ToolData toolData = ToolRegistry.get(customId).orElse(null);
            if (toolData != null && toolData.enchantability() > 0) {
                return toolData.enchantability();
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasEnchantable() && meta.getEnchantable() > 0) {
            return meta.getEnchantable();
        }
        return table.defaultEnchantability();
    }

    private boolean isKnife(ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }
        // 检查所有 farmersdelight:tool 物品（刀具 + 煎锅等）
        if (ToolAttackListener.resolveToolData(item) != null) {
            return true;
        }
        for (String itemId : ItemUtils.getItemIds(item)) {
            if (plugin.isKnifeItemId(itemId)) {
                return true;
            }
        }
        Set<String> configuredTags = plugin.getKnifeTagIds();
        for (String tagId : ItemUtils.getItemTagIds(item)) {
            if (configuredTags.contains(tagId.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<Enchantment> resolveEnchantments(List<String> configured, boolean backstabbingEnabled) {
        Set<Enchantment> resolved = new LinkedHashSet<>();
        String backstabbingId = settings.backstabbing().id();
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (String configuredId : configured) {
            String id = "$backstabbing".equals(configuredId) ? backstabbingId : configuredId;
            if (!backstabbingEnabled && id.equals(backstabbingId)) {
                continue;
            }

            // Tag syntax: entries starting with # are expanded as enchantment tags
            if (id.startsWith("#")) {
                String tagName = id.substring(1);
                NamespacedKey tagKey = NamespacedKey.fromString(tagName);
                if (tagKey == null) {
                    I18n.logWarning("enchantment.invalid_tag_key", "tag", tagName);
                    continue;
                }
                try {
                    var tagged = registry.getTag(TagKey.create(RegistryKey.ENCHANTMENT, tagKey));
                    if (tagged != null) {
                        for (var entry : tagged) {
                            Enchantment ench = registry.getOrThrow(entry);
                            if (ench.getKey().asString().equals(backstabbingId) && !backstabbingEnabled) {
                                continue;
                            }
                            resolved.add(ench);
                        }
                    } else {
                        I18n.logWarning("enchantment.unknown_tag", "tag", tagName);
                    }
                } catch (Exception e) {
                    I18n.logWarning("enchantment.tag_resolve_failed", "tag", tagName, "error", e.getMessage());
                }
                continue;
            }

            // Single enchantment ID
            NamespacedKey key = NamespacedKey.fromString(id);
            if (key == null) {
                continue;
            }
            Enchantment enchantment = registry.get(key);
            if (enchantment != null) {
                resolved.add(enchantment);
            } else if (!id.equals(backstabbingId)) {
                I18n.logWarning("enchantment.unknown", "id", id);
            }
        }
        return List.copyOf(resolved);
    }

    private static boolean conflictsWithAny(Enchantment enchantment, Set<Enchantment> existing) {
        return conflictsWithAnyExcept(enchantment, existing, null);
    }

    private static boolean conflictsWithAnyExcept(
            Enchantment enchantment,
            Set<Enchantment> existing,
            Enchantment ignored
    ) {
        for (Enchantment other : existing) {
            if (other.equals(ignored) || other.equals(enchantment)) {
                continue;
            }
            if (other.conflictsWith(enchantment) || enchantment.conflictsWith(other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private record Candidate(Enchantment enchantment, int level, int weight) {
    }

    private record PreparedOffers(UUID worldId, int x, int y, int z, ItemStack item,
                                  Map<Enchantment, Integer>[] choices) {
        static PreparedOffers capture(PrepareItemEnchantEvent event, Map<Enchantment, Integer>[] choices) {
            var location = event.getEnchantBlock().getLocation();
            UUID worldId = location.getWorld() == null ? null : location.getWorld().getUID();
            return new PreparedOffers(worldId, location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                    event.getItem().clone(), choices.clone());
        }

        Map<Enchantment, Integer> selection(EnchantItemEvent event, int button) {
            if (button < 0 || button >= choices.length) {
                return Map.of();
            }
            var location = event.getEnchantBlock().getLocation();
            if (location.getWorld() == null
                    || !location.getWorld().getUID().equals(worldId)
                    || location.getBlockX() != x
                    || location.getBlockY() != y
                    || location.getBlockZ() != z
                    || !item.isSimilar(event.getItem())) {
                return Map.of();
            }
            Map<Enchantment, Integer> selected = choices[button];
            return selected == null ? Map.of() : selected;
        }
    }
}
