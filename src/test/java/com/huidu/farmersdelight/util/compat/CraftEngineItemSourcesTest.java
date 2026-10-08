package com.huidu.farmersdelight.util.compat;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.plugin.compatibility.CompatibilityManager;
import net.momirealms.craftengine.core.plugin.compatibility.ItemSource;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.lang.reflect.Field;
import java.util.function.Supplier;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineItemSourcesTest {
    private Field blockType;
    private Object originalBlockType;

    @BeforeEach
    void installMaterialFixture() throws Exception {
        blockType = Material.class.getDeclaredField("blockType");
        blockType.setAccessible(true);
        originalBlockType = blockType.get(Material.STONE);
        blockType.set(Material.STONE, (Supplier<Object>) () -> null);
    }

    @AfterEach
    void restoreMaterialFixture() throws Exception {
        blockType.set(Material.STONE, originalBlockType);
    }

    @Test
    void validatesSourceIdsWithoutRejectingOriginalCaseOrNamespaces() {
        assertTrue(CraftEngineItemSources.isValidId("itemsadder:Food:Rice_A"));
        assertTrue(CraftEngineItemSources.isValidId("mmoitems:CUSTOM_SWORD:RICE_KNIFE_A"));
        assertTrue(CraftEngineItemSources.isValidId("minecraft:stone"));
        assertFalse(CraftEngineItemSources.isValidId("itemsadder:"));
        assertFalse(CraftEngineItemSources.isValidId("itemsadder:rice|minecraft:stone"));
        assertFalse(CraftEngineItemSources.isValidId("rice"));
    }

    @Test
    void referencedSourcesRoundTripNamespacedIdsAndKeepIndependentStacks() {
        Fixture fixture = new Fixture();
        Source source = new Source("itemsadder");
        fixture.registered.put("itemsadder", source);
        Stack rice = (Stack) fixture.sources.build("itemsadder:Food:Rice_A");
        Stack flour = (Stack) fixture.sources.build("itemsadder:Food:Flour_A");
        assertEquals("itemsadder:Food:Rice_A", fixture.sources.id(rice));
        assertEquals("itemsadder:Food:Flour_A", fixture.sources.id(flour));
        assertEquals(rice.getType(), flour.getType());
        rice.setAmount(3);
        assertEquals(1, source.template.getAmount());
        assertEquals("original data", rice.data);
        assertEquals(1, fixture.sources.build("itemsadder:Food:Rice_A").getAmount());

        String saved = RecipeSerializer.serializeIngredient(new RecipeIngredient.Item(Key.of(fixture.sources.id(rice))));
        assertEquals("itemsadder:Food:Rice_A", saved);
        assertEquals(saved, fixture.sources.id(fixture.sources.build(saved)));
        assertTrue(CraftEngineItemSources.matchesId(saved, "ItemsAdder:Food:Rice_A"));
        assertFalse(CraftEngineItemSources.matchesId(saved, "itemsadder:food:rice_a"));
        assertFalse(CraftEngineItemSources.matchesId(saved, "minecraft:stone"));
        assertFalse(CraftEngineItemSources.matchesId(saved, fixture.sources.id(flour)));
    }

    @Test
    void missingProvidersAreRetriedAndReloadDropsOldBindings() {
        Fixture fixture = new Fixture();
        fixture.sources.configure(List.of("nexo"));
        Stack held = new Stack("chair", "data");
        assertNull(fixture.sources.id(held));
        assertNull(fixture.sources.build("nexo:chair"));
        Source first = new Source("nexo");
        fixture.registered.put("nexo", first);
        assertEquals("nexo:chair", fixture.sources.id(held));
        fixture.sources.build("nexo:chair");
        fixture.sources.build("nexo:chair");
        assertEquals(1, fixture.nexoLookupsAfterRegistration.get());
        Source second = new Source("nexo");
        second.template.data = "reloaded data";
        fixture.registered.put("nexo", second);
        fixture.sources.clear();
        assertEquals("reloaded data", ((Stack) fixture.sources.build("nexo:chair")).data);
        fixture.sources.configure(List.of());
        assertNull(fixture.sources.id(held));
    }

    @Test
    void unknownIdsNullBuildsAndProviderFailuresNeverCreateVanillaItems() {
        Fixture fixture = new Fixture();
        Source source = new Source("itemsadder");
        fixture.registered.put(source.plugin(), source);
        assertNull(fixture.sources.build("unknown:stone"));
        assertNull(fixture.sources.build("itemsadder:"));
        assertNull(fixture.sources.build("itemsadder:missing"));
        assertNull(fixture.sources.build("itemsadder:broken"));
        fixture.sources.configure(List.of("itemsadder", "nexo"));
        fixture.registered.put("nexo", new Source("nexo"));
        source.failId = true;
        assertEquals("nexo:chair", fixture.sources.id(new Stack("chair", "data")));
    }

    @Test
    void identificationConsultsOnlyConfiguredOrReferencedSourcesInStableOrder() {
        Fixture fixture = new Fixture();
        fixture.registered.put("unused", new Source("unused"));
        fixture.registered.put("nexo", new Source("nexo"));
        fixture.registered.put("itemsadder", new Source("itemsadder"));
        fixture.sources.configure(List.of("NEXO", "itemsadder"));
        for (int i = 0; i < 10; i++) {
            assertEquals("itemsadder:chair", fixture.sources.id(new Stack("chair", "data")));
        }
        assertFalse(fixture.lookups.containsKey("unused"));
        assertEquals(1, fixture.lookups.get("itemsadder"));
        assertEquals(1, fixture.lookups.get("nexo"));
    }

    @Test
    void mmoItemsKeepsLegacyTypeAndIdWithoutSplittingUnderscores() {
        Fixture fixture = new Fixture();
        Source source = new Source("mmoitems");
        source.template.mmo = new CompoundTag();
        source.template.mmo.putString("MMOITEMS_ITEM_TYPE", "CUSTOM_SWORD");
        source.template.mmo.putString("MMOITEMS_ITEM_ID", "RICE_KNIFE_A");
        fixture.registered.put("mmoitems", source);
        ItemStack built = fixture.sources.build("mmoitems:CUSTOM_SWORD:RICE_KNIFE_A");
        assertEquals("CUSTOM_SWORD:RICE_KNIFE_A", source.lastBuilt);
        assertEquals("mmoitems:CUSTOM_SWORD:RICE_KNIFE_A", fixture.sources.id(built));
        assertEquals(0, source.idCalls, "the provider's lossy TYPE_ID form must not become the saved identity");
    }

    private static final class Fixture {
        final Map<String, ItemSource> registered = new HashMap<>();
        final Map<String, Integer> lookups = new HashMap<>();
        final AtomicInteger nexoLookupsAfterRegistration = new AtomicInteger();
        final CompatibilityManager manager = (CompatibilityManager) Proxy.newProxyInstance(
                CompatibilityManager.class.getClassLoader(), new Class<?>[]{CompatibilityManager.class},
                (proxy, method, args) -> {
                    assertEquals("getItemSource", method.getName(), "no sorted itemSources allocation on matching paths");
                    String plugin = (String) args[0];
                    lookups.merge(plugin, 1, Integer::sum);
                    if (plugin.equals("nexo") && registered.containsKey(plugin)) nexoLookupsAfterRegistration.incrementAndGet();
                    return registered.get(plugin);
                });
        final CraftEngineItemSources sources = new CraftEngineItemSources(() -> manager,
                stack -> item((Stack) stack), value -> (Stack) value.minecraftItem());
    }

    private static final class Source implements ItemSource {
        final String plugin;
        final Stack template = new Stack("template", "original data");
        String lastBuilt;
        boolean failId;
        int idCalls;
        Source(String plugin) { this.plugin = plugin; }
        @Override public String plugin() { return plugin; }
        @Override public Item build(String id, ItemBuildContext context) {
            assertNull(context.player());
            lastBuilt = id;
            if (id.equals("missing")) return null;
            if (id.equals("broken")) throw new IllegalArgumentException("missing template");
            template.id = id;
            return item(template);
        }
        @Override public String id(Item item) {
            idCalls++;
            if (failId) throw new IllegalStateException("integration unavailable");
            return ((Stack) item.minecraftItem()).id;
        }
    }

    private static Item item(Stack stack) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "minecraftItem" -> stack;
                    case "getSparrowTag" -> stack.mmo;
                    case "isEmpty" -> false;
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static final class Stack extends ItemStack {
        String id;
        String data;
        CompoundTag mmo;
        int amount = 1;
        Stack(String id, String data) { this.id = id; this.data = data; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public Stack clone() {
            Stack copy = new Stack(id, data);
            copy.amount = amount;
            copy.mmo = mmo == null ? null : mmo.copy();
            return copy;
        }
    }
}
