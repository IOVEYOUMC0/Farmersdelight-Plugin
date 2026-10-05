package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.enchant.EnchantGroup;
import com.huidu.farmersdelight.api.enchant.FarmersDelightEnchantments;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.tool.ToolAttackListener;
import com.huidu.farmersdelight.tool.ToolData;
import com.huidu.farmersdelight.tool.ToolRegistry;
import com.huidu.farmersdelight.util.Constants;
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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KnifeEnchantFilter implements Listener {

    private static final int ANVIL_CONFLICT_PENALTY = 1;
    private static final int ANVIL_MINIMUM_REPAIR_COST = 1;

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, PreparedOffers> preparedOffers = new ConcurrentHashMap<>();
    private volatile EnchantmentSettings settings = EnchantmentSettings.defaults();
    private volatile Map<EnchantmentSettings.GroupId, List<Enchantment>> tableEnchantments = Map.of();
    private volatile Map<EnchantmentSettings.GroupId, Set<Enchantment>> anvilEnchantments = Map.of();

    public KnifeEnchantFilter(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        // The config read is left to reload(...), which the registration path calls right after construction
        // and the reload path calls again. Keeping it out of the constructor lets the handler be built
        // without a live plugin (the registry's order is asserted by a test).
    }

    public void reload(EnchantmentSettings newSettings, boolean backstabbingEnabled) {
        settings = newSettings == null ? EnchantmentSettings.defaults() : newSettings;

        Map<EnchantmentSettings.GroupId, List<Enchantment>> tableGroups =
                new EnumMap<>(EnchantmentSettings.GroupId.class);
        Map<EnchantmentSettings.GroupId, Set<Enchantment>> anvilGroups =
                new EnumMap<>(EnchantmentSettings.GroupId.class);
        for (EnchantmentSettings.GroupId groupId : EnchantmentSettings.GroupId.values()) {
            EnchantmentSettings.Group group = settings.group(groupId);
            List<Enchantment> resolved = new ArrayList<>(resolveEnchantments(
                    group.table().enchantments(),
                    backstabbingEnabled
            ));
            appendRegisteredEnchants(groupId, resolved);
            tableGroups.put(groupId, List.copyOf(resolved));

            // The anvil accepts everything the table offers, plus the group's anvil-only additions.
            // Keeping the two lists separate is what stops an anvil-only enchant (mending, or whatever
            // the operator adds) from turning up in the table's offer rolls.
            Set<Enchantment> anvilSet = new LinkedHashSet<>(resolved);
            anvilSet.addAll(resolveEnchantments(group.extraEnchantments(), backstabbingEnabled));
            anvilGroups.put(groupId, Set.copyOf(anvilSet));
        }
        tableEnchantments = Map.copyOf(tableGroups);
        anvilEnchantments = Map.copyOf(anvilGroups);
        preparedOffers.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        UUID playerId = event.getEnchanter().getUniqueId();
        preparedOffers.remove(playerId);

        ItemStack item = event.getItem();
        EnchantmentSettings.GroupId groupId = enchantmentGroup(item);
        EnchantmentSettings current = settings;
        if (!current.enabled() || groupId == null) {
            return;
        }
        EnchantmentSettings.Table table = current.group(groupId).table();
        List<Enchantment> allowedEnchantments = tableEnchantments.getOrDefault(groupId, List.of());
        if (!table.enabled() || allowedEnchantments.isEmpty()) {
            return;
        }

        Player player = event.getEnchanter();
        diagnoseIfEnchantable(player, item, groupId, event);
        int seed = player.getEnchantmentSeed();
        int bonus = Math.min(event.getEnchantmentBonus(), 15);
        Random costRandom = new Random(seed);
        int[] costs = new int[3];
        List<Map<Enchantment, Integer>> choices = new ArrayList<>(3);
        int enchantability = enchantability(item, table);
        EnchantmentOffer[] offers = event.getOffers();

        for (int slot = 0; slot < 3; slot++) {
            costs[slot] = calculateSlotCost(costRandom, slot, bonus);
            if (costs[slot] < slot + 1) {
                costs[slot] = 0;
            }
            Map<Enchantment, Integer> selected = costs[slot] <= 0
                    ? Map.of()
                    : selectEnchantments(
                            allowedEnchantments,
                            costs[slot],
                            new Random((long) seed + slot),
                            enchantability
                    );
            choices.add(selected);

            if (table.overrideOffers() && offers != null && slot < offers.length) {
                if (selected.isEmpty()) {
                    offers[slot] = null;
                } else {
                    Map.Entry<Enchantment, Integer> first = selected.entrySet().iterator().next();
                    offers[slot] = new EnchantmentOffer(first.getKey(), first.getValue(), costs[slot]);
                }
            }
        }

        preparedOffers.put(playerId, PreparedOffers.capture(event, choices));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (plugin != null && plugin.isDebugEnabled("enchant")) {
            plugin.getLogger().info("[enchant] EnchantItemEvent fired for " + event.getEnchanter().getName()
                    + " (button " + event.whichButton() + ")");
        }
        EnchantmentSettings.GroupId groupId = enchantmentGroup(event.getItem());
        EnchantmentSettings current = settings;
        if (!current.enabled() || groupId == null) {
            return;
        }
        EnchantmentSettings.Table table = current.group(groupId).table();
        List<Enchantment> allowedEnchantments = tableEnchantments.getOrDefault(groupId, List.of());
        if (!table.enabled() || allowedEnchantments.isEmpty()) {
            return;
        }

        int button = event.whichButton();
        PreparedOffers prepared = preparedOffers.remove(event.getEnchanter().getUniqueId());
        Map<Enchantment, Integer> selected = prepared == null ? Map.of() : prepared.selection(event, button);
        if (selected.isEmpty() && button >= 0 && button < 3) {
            int enchantability = enchantability(event.getItem(), table);
            selected = selectEnchantments(
                    allowedEnchantments,
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
        // Non-override mode always appends the enchantment.
        for (Map.Entry<Enchantment, Integer> entry : selected.entrySet()) {
            if (!conflictsWithAny(entry.getKey(), additions.keySet())) {
                additions.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        EnchantmentSettings.GroupId groupId = enchantmentGroup(first);
        EnchantmentSettings current = settings;
        if (!current.enabled()) {
            return;
        }
        // A non-knife item (or a group whose anvil enchanting is off) must not carry the knife-only backstab
        // enchant. Vanilla's supported_items guard is bypassed on the anvil in creative (and by some fork
        // configs), so scrub any backstab the vanilla result leaked onto the item. Only the plugin's own
        // datapack enchant here, never vanilla enchants.
        if (groupId == null || !current.group(groupId).anvilEnabled()) {
            // If the incoming book holds a FarmersDelight-managed enchant (backstab or a registered addon),
            // merely stripping it leaves the anvil clickable: the player spends the book and levels but the
            // enchant never lands. Block the whole combine so the slot shows grey and cannot be clicked.
            if (hasManagedEnchant(second)) {
                event.setResult(null);
                return;
            }
            stripManagedEnchants(event);
            return;
        }
        if (isEmpty(second)) {
            return;
        }
        Set<Enchantment> allowedEnchantments = anvilEnchantments.getOrDefault(groupId, Set.of());
        if (allowedEnchantments.isEmpty()) {
            return;
        }
        ItemMeta secondMeta = second.getItemMeta();
        if (!(secondMeta instanceof EnchantmentStorageMeta storage) || storage.getStoredEnchants().isEmpty()) {
            return;
        }

        Map<Enchantment, Integer> original = first.getEnchantments();
        Set<Enchantment> present = new LinkedHashSet<>(original.keySet());
        Map<Enchantment, Integer> merged = new LinkedHashMap<>();
        int addedCost = 0;
        for (Map.Entry<Enchantment, Integer> entry : storage.getStoredEnchants().entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (!allowedEnchantments.contains(enchantment)) {
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
            merged.put(enchantment, level);
            present.add(enchantment);
            addedCost += Math.max(0, enchantment.getAnvilCost()) * level;
        }
        if (merged.isEmpty()) {
            event.setResult(null);
            return;
        }

        ItemStack vanillaResult = event.getResult();
        ItemStack result = isEmpty(vanillaResult) ? first.clone() : vanillaResult.clone();
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) {
            event.setResult(null);
            return;
        }

        Map<Enchantment, Integer> sanitized = new LinkedHashMap<>(original);
        sanitized.putAll(merged);
        for (Enchantment enchantment : new ArrayList<>(resultMeta.getEnchants().keySet())) {
            resultMeta.removeEnchant(enchantment);
        }
        for (Map.Entry<Enchantment, Integer> entry : sanitized.entrySet()) {
            resultMeta.addEnchant(entry.getKey(), entry.getValue(), true);
        }
        result.setItemMeta(resultMeta);
        event.setResult(result);

        int repairCost = Math.max(ANVIL_MINIMUM_REPAIR_COST, addedCost);
        Player player = (Player) event.getView().getPlayer();
        var view = event.getView();
        plugin.scheduler().runLaterForEntity(player, () -> {
            var openView = player.getOpenInventory();
            if (player.isOnline()
                    && openView instanceof AnvilView openAnvilView
                    && openView.getTopInventory().equals(view.getTopInventory())) {
                openAnvilView.setRepairCost(repairCost);
            }
        }, 1L);
    }

    // True when an incoming anvil book carries any FarmersDelight-managed enchant (backstab or an API
    // registered addon). Used to block such books from combining onto a non-knife target outright.
    private boolean hasManagedEnchant(ItemStack book) {
        if (isEmpty(book)) {
            return false;
        }
        ItemMeta meta = book.getItemMeta();
        if (!(meta instanceof EnchantmentStorageMeta storage) || storage.getStoredEnchants().isEmpty()) {
            return false;
        }
        Set<Enchantment> managed = managedEnchants();
        if (managed.isEmpty()) {
            return false;
        }
        for (Enchantment enchantment : storage.getStoredEnchants().keySet()) {
            if (managed.contains(enchantment)) {
                return true;
            }
        }
        return false;
    }

    // Removes the plugin's knife-only backstab enchant from an anvil result on an item that isn't an enchantable knife.
    // Only backstab is touched, so a creative player anvil-ing vanilla enchants onto arbitrary items is left
    // alone; if backstab was the only thing the anvil produced, the result is cancelled outright.
    private void stripManagedEnchants(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (isEmpty(result)) {
            return;
        }
        Set<Enchantment> managed = managedEnchants();
        if (managed.isEmpty()) {
            return;
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return;
        }
        boolean changed = false;
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Enchantment enchantment : managed) {
                if (storage.hasStoredEnchant(enchantment)) {
                    storage.removeStoredEnchant(enchantment);
                    changed = true;
                }
            }
        } else {
            for (Enchantment enchantment : managed) {
                if (meta.hasEnchant(enchantment)) {
                    meta.removeEnchant(enchantment);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return;
        }
        result.setItemMeta(meta);
        ItemStack first = event.getInventory().getFirstItem();
        event.setResult(first != null && result.isSimilar(first) ? null : result);
    }

    // The FarmersDelight-managed knife-only enchants (built-in backstab + every API-registered enchant): the
    // only enchants scrubbed off a non-knife anvil result. Vanilla enchants a creative player applies to
    // arbitrary items are left alone.
    private Set<Enchantment> managedEnchants() {
        Set<Enchantment> managed = new LinkedHashSet<>();
        Enchantment backstab = resolveBackstab();
        if (backstab != null) {
            managed.add(backstab);
        }
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (String id : FarmersDelightEnchantments.registeredIds()) {
            NamespacedKey key = NamespacedKey.fromString(id);
            if (key != null) {
                Enchantment enchantment = registry.get(key);
                if (enchantment != null) {
                    managed.add(enchantment);
                }
            }
        }
        return managed;
    }

    // Adds every API-registered enchant that targets this group into the group's candidate pool, so registered
    // addon enchants are offered at the table and anvil alongside the config-listed ones.
    private void appendRegisteredEnchants(EnchantmentSettings.GroupId groupId, List<Enchantment> into) {
        Map<String, Set<EnchantGroup>> targets = FarmersDelightEnchantments.poolTargets();
        if (targets.isEmpty()) {
            return;
        }
        EnchantGroup apiGroup;
        try {
            apiGroup = EnchantGroup.valueOf(groupId.name());
        } catch (IllegalArgumentException noMatchingGroup) {
            return;
        }
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Map.Entry<String, Set<EnchantGroup>> entry : targets.entrySet()) {
            if (!entry.getValue().contains(apiGroup)) {
                continue;
            }
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            if (key == null) {
                continue;
            }
            Enchantment enchantment = registry.get(key);
            if (enchantment != null && !into.contains(enchantment)) {
                into.add(enchantment);
            }
        }
    }

    private Enchantment resolveBackstab() {
        String id = settings.backstabbing().id();
        if (id == null || id.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(id);
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
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

    /**
     * One diagnostic line per call, printed only while the enchant debug category is on: this is how the
     * "the three options cannot be clicked" report is pinned down in the field. The line is deliberately not
     * deduplicated — the method keeps no per-player state, because such a set would need its own logout and
     * expiry lifecycle while the category is off in production. It prints what vanilla itself looks at
     * (enchantment value, damage, max stack size) plus whether vanilla had offers at all — the item's own
     * minecraft:enchantable declaration is what makes those costs non-zero, and a component that CraftEngine
     * ignores reads back as 0 here. Not on a hot path: only a player opening an enchanting table.
     */
    private void diagnoseIfEnchantable(Player player, ItemStack item, EnchantmentSettings.GroupId groupId,
                                       PrepareItemEnchantEvent event) {
        if (plugin == null || !plugin.isDebugEnabled("enchant") || groupId == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        int enchantable = meta == null ? -1 : meta.getEnchantable();
        int maxDamage = meta instanceof Damageable damageable && damageable.hasMaxDamage()
                ? damageable.getMaxDamage() : -1;
        int maxStackSize = item.getMaxStackSize();
        // The same two conditions vanilla's EnchantmentMenu checks before it computes any cost: a single-item
        // stack carrying an enchantment value above zero. Both read back from the real stack, so a component
        // CraftEngine ignored shows up here as 0.
        boolean vanillaWouldOffer = maxStackSize == 1 && enchantable > 0;
        plugin.getLogger().info("[enchant] " + player.getName() + " item=" + item.getType()
                + " group=" + groupId
                + " getEnchantable=" + enchantable
                + " maxDamage=" + maxDamage
                + " maxStackSize=" + maxStackSize
                + " vanillaWouldOffer=" + vanillaWouldOffer
                + " vanillaOffers=" + (event.getOffers() == null ? -1 : event.getOffers().length));
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

    private EnchantmentSettings.GroupId enchantmentGroup(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        // Durability-only custom items (farmersdelight:durable) are not weapons: route them to their own
        // group so the minimal whitelist is enforced instead of letting any enchant through the anvil.
        if (customId != null && ToolRegistry.isDurable(customId)) {
            return EnchantmentSettings.GroupId.DURABLE;
        }
        if (Constants.ITEM_SKILLET.equalsIgnoreCase(customId)) {
            return EnchantmentSettings.GroupId.SKILLET;
        }
        if (ToolAttackListener.resolveToolData(item) != null) {
            return EnchantmentSettings.GroupId.KNIVES;
        }
        for (String itemId : ItemUtils.getItemIds(item)) {
            if (plugin.isKnifeItemId(itemId)) {
                return EnchantmentSettings.GroupId.KNIVES;
            }
        }
        Set<String> configuredTags = plugin.getKnifeTagIds();
        for (String tagId : ItemUtils.getItemTagIds(item)) {
            if (configuredTags.contains(tagId.toLowerCase(Locale.ROOT))) {
                return EnchantmentSettings.GroupId.KNIVES;
            }
        }
        return null;
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
                    I18n.logWarning("plugin.enchantment.invalid_tag_key", "tag", tagName);
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
                        I18n.logWarning("plugin.enchantment.unknown_tag", "tag", tagName);
                    }
                } catch (Exception e) {
                    I18n.logWarning("plugin.enchantment.tag_resolve_failed", "tag", tagName, "error", e.getMessage());
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
                I18n.logWarning("plugin.enchantment.unknown", "id", id);
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
                                  List<Map<Enchantment, Integer>> choices) {
        static PreparedOffers capture(PrepareItemEnchantEvent event, List<Map<Enchantment, Integer>> choices) {
            var location = event.getEnchantBlock().getLocation();
            UUID worldId = location.getWorld() == null ? null : location.getWorld().getUID();
            return new PreparedOffers(worldId, location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                    event.getItem().clone(), List.copyOf(choices));
        }

        Map<Enchantment, Integer> selection(EnchantItemEvent event, int button) {
            if (button < 0 || button >= choices.size()) {
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
            Map<Enchantment, Integer> selected = choices.get(button);
            return selected == null ? Map.of() : selected;
        }
    }
}
