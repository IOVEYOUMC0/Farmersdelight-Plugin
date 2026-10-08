package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.manager.HandheldDisplays;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durability bar of handheld skewer cooking is a client-only copy, but the rewriter also rewrites the held
 * slot inside the player's own inventory menu. Opening that menu therefore showed the fake durable skewer, and an
 * inventory interaction carried it back to the server — the owner saw a durable skewer "somehow" appear while
 * cooking with the bag open.
 *
 * These cases pin the two halves of the fix: the display path never writes real item data at all, and every
 * inventory interaction that could capture the fake item closes the display and resends the real slot first,
 * exactly as the handheld skillet already does. The creative set-slot packet is refused outright, because the
 * server stores what the client sends there.
 */
class SkewerDurabilityLeakTest {

    private static final Map<String, String> ONE_RESULT = Map.of(
            "farmersdelight:meat_skewer", "farmersdelight:cooked_meat_skewer");
    private static final String RAW_ID = "farmersdelight:meat_skewer";
    private static final String COOKED_ID = "farmersdelight:cooked_meat_skewer";

    // ── the display never writes real item data ──────────────────────────

    /** Progress ticks update the bar, and the real inventory is not written once while they do. */
    @Test
    void progressUpdatesNeverWriteTheRealInventory() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        int writesBefore = fixture.player.writes;
        int updatesBefore = fixture.display.updates;

        fixture.runTicks(20);

        assertTrue(fixture.display.updates > updatesBefore,
                "the bar is driven while the cook runs (progress % 4 == 0 paints)");
        assertEquals(writesBefore, fixture.player.writes,
                "and no slot write happens while the bar is only being displayed");
        assertEquals(1, fixture.player.get(3).getAmount(), "the real skewer keeps its count");
    }

    /**
     * A stack of two is the normal way to cook a batch, and the bar has to survive it: the rewriter claims the
     * slot for any count, and the display still never touches the real stack.
     */
    @Test
    void aStackOfTwoKeepsItsBarAndItsRealStack() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(2);
        int writesBefore = fixture.player.writes;
        int updatesBefore = fixture.display.updates;

        fixture.runTicks(8);

        assertEquals(1, fixture.display.running(), "the bar stays up for a stack of two");
        assertEquals(updatesBefore + 2, fixture.display.updates,
                "and keeps painting (progress 4 and 8 are the % 4 points)");
        assertEquals(2, fixture.player.get(3).getAmount(), "the real stack is still two");
        assertEquals(writesBefore, fixture.player.writes, "and no slot write happened");
    }

    /**
     * The opener is handed the whole stack, not a single item: that is exactly what the shared rewriter's claim
     * had to accept, and what the count == 1 requirement refused.
     */
    @Test
    void theBarIsOpenedForTheWholeStack() {
        Fixture fixture = new Fixture();
        fixture.player.put(3, raw(3));
        int[] openedWith = {-1};

        HandCookedSkewerHooks.HeldSkewerProgressDisplay display =
                new HandCookedSkewerHooks.HeldSkewerProgressDisplay(null,
                        id -> fixture.player.asPlayer(),
                        (player, slot, original, duration) -> {
                            openedWith[0] = original == null ? -1 : original.getAmount();
                            return closeRecordingHandle();
                        },
                        stack -> RAW_ID);

        display.start(fixture.player.id(), 3, RAW_ID, HandCookedSkewerService.COOKING_TICKS);

        assertEquals(3, openedWith[0], "the display opens for the stack the slot holds");
    }

    private static HandheldDisplays.Handle closeRecordingHandle() {
        return new HandheldDisplays.Handle() {

            @Override
            public void update(int progress) {
            }

            @Override
            public void close() {
            }
        };
    }

    /** The two display files write components on a clone and send packets, never on a Bukkit inventory. */
    @Test
    void theDisplaySourceClonesBeforeItPaintsAndNeverTouchesAnInventory() throws IOException {
        String handle = read("manager/HandheldDisplays.java");
        // The base is a clone of the slot's current stack, and the per-update copy is a clone of that base: the
        // real stack is read, never painted.
        assertTrue(handle.contains("ItemStack base = source.clone();"),
                "the display base has to come from a clone of the live stack");
        assertTrue(handle.contains("ItemStack copy = this.displayBase.clone();"),
                "and each painted item has to be a clone too");
        assertTrue(handle.indexOf("ItemStack base = source.clone();")
                        < handle.indexOf("setJavaComponent(DataComponentKeys.MAX_DAMAGE"),
                "the clone has to exist before the damage components are painted");
        assertTrue(handle.contains("getInventory().getItem("),
                "its inventory reach is the read of the slot it displays and resends");
        for (String forbidden : new String[]{"getInventory().set", "getInventory().clear", "setItem(",
                "setItemInMainHand", "setItemInOffHand", "setItemMeta", "setContents("}) {
            assertFalse(handle.contains(forbidden),
                    "HandheldDisplays must not write real state through " + forbidden);
        }

        String display = read("manager/HandheldCookingDisplay.java");
        assertFalse(display.contains("getInventory"),
                "the packet rewriter must never reach into a real inventory");
        assertTrue(display.contains("copyDisplay()"),
                "it paints a copy of its own display stack into the outgoing packet");
    }

    // ── the capture window closes before the client can use it ───────────

    @Test
    void openingAnInventoryTakesTheFakeBarDownBeforeTheWindowIsUsed() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        assertEquals(1, fixture.display.running(), "the bar is up before the bag opens");

        fixture.hooks.onInventoryOpen(openEvent(fixture.player));

        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()),
                "opening a window ends the cook, so the fake item cannot be picked out of it");
        assertEquals(0, fixture.display.running(), "and the bar is taken down");
        assertTrue(fixture.display.closes >= 1, "through the close path, which resends the real slot");
    }

    @Test
    void clickingAndDraggingInsideAnInventoryDoTheSame() {
        Fixture clicking = new Fixture();
        clicking.cookingPlayer(1);
        clicking.hooks.onInventoryClick(clickEvent(clicking.player, ClickType.LEFT));
        assertFalse(clicking.hooks.service().isCooking(clicking.player.id()), "a click ends the cook");
        assertEquals(0, clicking.display.running(), "and takes the bar down");

        Fixture dragging = new Fixture();
        dragging.cookingPlayer(1);
        dragging.hooks.onInventoryDrag(dragEvent(dragging.player));
        assertFalse(dragging.hooks.service().isCooking(dragging.player.id()), "a drag ends the cook");
        assertEquals(0, dragging.display.running(), "and takes the bar down");
    }

    /** The creative packet is the one path the server stores verbatim, so it has to be refused. */
    @Test
    void aCreativeSetSlotIsRefusedWhileCookingAndAllowedOtherwise() {
        Fixture cooking = new Fixture();
        cooking.cookingPlayer(1);
        InventoryCreativeEvent carried = creativeEvent(cooking.player, raw(1));
        cooking.hooks.onInventoryCreative(carried);

        assertTrue(carried.isCancelled(), "a client stack carrying display-only damage must not be stored");
        assertFalse(cooking.hooks.service().isCooking(cooking.player.id()), "and the cook ends with it");
        assertEquals(0, cooking.display.running(), "with the bar down");

        Fixture idle = new Fixture();
        idle.player.put(3, raw(1));
        InventoryCreativeEvent unrelated = creativeEvent(idle.player, raw(1));
        idle.hooks.onInventoryCreative(unrelated);
        assertFalse(unrelated.isCancelled(), "an ordinary creative set-slot is not ours to cancel");
    }

    /** The close path is the one task-22 added: the bar goes down and the current slot is resent. */
    @Test
    void theCaptureGuardClosesThroughTheRestoringPath() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        fixture.display.track(fixture.player);

        fixture.hooks.onInventoryOpen(openEvent(fixture.player));

        assertEquals(1, fixture.display.closes, "closed once, not twice");
        assertEquals(1, fixture.display.amountAtClose,
                "the slot still held the real skewer when the view was restored");
    }

    /**
     * The capture set is where the two handheld paths drifted apart: the skillet had all four guards and the
     * skewer had none, which is exactly the leak the owner hit. Keep the two lists together from here on.
     */
    @Test
    void theSkewerGuardsTheSameInventoryEventsAsTheSkillet() throws IOException {
        String skillet = read("listener/SkilletLifecycleListener.java");
        String skewer = read("handheld/HandCookedSkewerHooks.java");
        for (String event : new String[]{"InventoryOpenEvent", "InventoryClickEvent", "InventoryDragEvent",
                "InventoryCreativeEvent"}) {
            assertTrue(skillet.contains(event), "the skillet guards " + event);
            assertTrue(skewer.contains(event), "and the skewer has to guard it too: " + event);
        }
        assertTrue(skewer.contains("event.setCancelled(true)"),
                "the creative guard has to refuse the packet, not only close the bar");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static InventoryOpenEvent openEvent(BoardPlayer player) {
        return new InventoryOpenEvent(view(player));
    }

    private static InventoryClickEvent clickEvent(BoardPlayer player, ClickType type) {
        return new InventoryClickEvent(view(player), InventoryType.SlotType.CONTAINER, 39, type,
                InventoryAction.PICKUP_ONE);
    }

    private static InventoryDragEvent dragEvent(BoardPlayer player) {
        ItemStack cursor = raw(1);
        return new InventoryDragEvent(view(player), cursor, cursor, false, Map.of(39, raw(1)));
    }

    private static InventoryCreativeEvent creativeEvent(BoardPlayer player, ItemStack carried) {
        return new InventoryCreativeEvent(view(player), InventoryType.SlotType.CONTAINER, 39, carried);
    }

    /** An InventoryView needs a player and two inventories; the events only read them. */
    private static InventoryView view(BoardPlayer player) {
        Player handle = player.asPlayer();
        Inventory top = inventory();
        Inventory bottom = player.inventoryView();
        return (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[]{InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> top;
                    case "getBottomInventory" -> bottom;
                    case "getPlayer" -> handle;
                    case "getType" -> InventoryType.CRAFTING;
                    case "convertSlot" -> args[0];
                    case "getSlotType" -> InventoryType.SlotType.CONTAINER;
                    case "getItem" -> bottom.getItem((Integer) args[0]);
                    case "setItem" -> null;
                    case "getCursor", "setCursor" -> null;
                    case "getInventory" -> bottom;
                    case "toString" -> "fake view";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected view call: " + method.getName());
                });
    }

    private static Inventory inventory() {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> 9;
                    case "getItem" -> null;
                    case "toString" -> "fake container";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected inventory call: " + method.getName());
                });
    }

    private static ItemStack raw(int amount) {
        return new SlotStack("raw", amount);
    }

    private static ItemStack cooked(int amount) {
        return new SlotStack("cooked", amount);
    }

    private static String read(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        assertNotNull(null, "the source file has to be reachable from the test working directory: " + relative);
        return "";
    }

    /** An ItemStack without a live server: the tests only carry a kind and an amount. */
    private static final class SlotStack extends ItemStack {

        private final String kind;
        private int amount;

        private SlotStack(String kind, int amount) {
            super();
            this.kind = kind;
            this.amount = amount;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int value) {
            amount = value;
        }

        @Override
        public Material getType() {
            return Material.STONE;
        }

        @Override
        public boolean isSimilar(ItemStack other) {
            return other instanceof SlotStack stack && kind.equals(stack.kind);
        }

        @Override
        public SlotStack clone() {
            return new SlotStack(kind, amount);
        }
    }

    /** A player with an in-memory slot map, counting every write the display path could ever make. */
    private static final class BoardPlayer implements HandCookedSkewerHooks.LoopSeam {

        private final UUID id = UUID.randomUUID();
        private final Map<Integer, ItemStack> slots = new LinkedHashMap<>();
        private final List<ItemStack> added = new ArrayList<>();
        private int selected;
        private int writes;
        private final World world = world();
        private final PlayerInventory inventory = inventory();
        private final Player player = player();

        UUID id() {
            return id;
        }

        Player asPlayer() {
            return player;
        }

        PlayerInventory inventoryView() {
            return inventory;
        }

        void put(int slot, ItemStack stack) {
            slots.put(slot, stack);
        }

        ItemStack get(int slot) {
            return slots.get(slot);
        }

        @Override
        public Player player(UUID playerId) {
            return player;
        }

        @Override
        public void dispatch(Player target, Runnable task) {
            task.run();
        }

        private PlayerInventory inventory() {
            return (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                    new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getHeldItemSlot" -> selected;
                        case "getItem" -> slots.get((Integer) args[0]);
                        case "setItem" -> {
                            writes++;
                            slots.put((Integer) args[0], (ItemStack) args[1]);
                            yield null;
                        }
                        case "getItemInMainHand" -> slots.get(selected);
                        case "getItemInOffHand" -> slots.get(40);
                        case "setItemInMainHand" -> {
                            writes++;
                            slots.put(selected, (ItemStack) args[0]);
                            yield null;
                        }
                        case "setItemInOffHand" -> {
                            writes++;
                            slots.put(40, (ItemStack) args[0]);
                            yield null;
                        }
                        case "addItem" -> {
                            writes++;
                            added.add(((ItemStack[]) args[0])[0]);
                            yield new java.util.HashMap<Integer, ItemStack>();
                        }
                        case "getContents" -> {
                            ItemStack[] copy = new ItemStack[41];
                            for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
                                if (entry.getKey() >= 0 && entry.getKey() < copy.length) {
                                    copy[entry.getKey()] = entry.getValue();
                                }
                            }
                            yield copy;
                        }
                        case "setContents" -> {
                            writes++;
                            slots.clear();
                            ItemStack[] restored = (ItemStack[]) args[0];
                            for (int index = 0; index < restored.length; index++) {
                                if (restored[index] != null) {
                                    slots.put(index, restored[index]);
                                }
                            }
                            yield null;
                        }
                        case "clear" -> {
                            writes++;
                            slots.clear();
                            yield null;
                        }
                        case "toString" -> "fake inventory";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected inventory call: " + method.getName());
                    });
        }

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> "cook";
                        case "isSneaking" -> false;
                        case "isDead" -> false;
                        case "isHandRaised" -> false;
                        case "getHandRaised" -> EquipmentSlot.HAND;
                        case "getInventory" -> inventory;
                        case "getWorld" -> world;
                        case "getLocation" -> new Location(world, 0.5, 64.0, 0.5);
                        case "getGameMode" -> GameMode.SURVIVAL;
                        case "isOnline" -> true;
                        case "playSound" -> null;
                        case "swingMainHand", "swingOffHand" -> null;
                        case "sendActionBar" -> null;
                        case "toString" -> "fake player";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected Player call: " + method.getName());
                    });
        }

        private World world() {
            return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUID" -> UUID.fromString("00000000-0000-0000-0000-0000000000cc");
                        case "dropItem" -> dropEntity();
                        case "toString" -> "fake world";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected World call: " + method.getName());
                    });
        }

        private Item dropEntity() {
            return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "remove" -> null;
                        case "toString" -> "fake drop";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected Item call: " + method.getName());
                    });
        }
    }

    /** Records what the bar seam was asked to do, and what the slot held when it was closed. */
    private static final class SlotDisplay implements HandCookedSkewerHooks.SkewerProgressDisplay {

        private final Map<UUID, Integer> open = new LinkedHashMap<>();
        private int starts;
        private int updates;
        private int closes;
        private int amountAtClose = -1;
        private BoardPlayer closing;

        @Override
        public void start(UUID player, int slot, String expectedId, int duration) {
            starts++;
            open.put(player, slot);
        }

        @Override
        public void update(UUID player, int progress, int duration) {
            updates++;
        }

        @Override
        public void close(UUID player) {
            closes++;
            open.remove(player);
            if (closing != null) {
                ItemStack stack = closing.get(3);
                amountAtClose = stack == null ? -1 : stack.getAmount();
            }
        }

        void track(BoardPlayer player) {
            this.closing = player;
        }

        int running() {
            return open.size();
        }
    }

    /** Records the loop Runnable so a test can drive the production tick(), and counts arming. */
    private static final class Arm implements HandCookedSkewerHooks.TaskArm {

        private int armed;
        private Runnable tick;

        @Override
        public PluginTask arm(Runnable task) {
            armed++;
            tick = task;
            return new PluginTask() {
                private boolean cancelled;

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        void runTick() {
            tick.run();
        }
    }

    /** The production wiring with every seam replaced, one player and a controllable clock. */
    private static final class Fixture {

        private final BoardPlayer player = new BoardPlayer();
        private final HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        private final Arm arm = new Arm();
        private final SlotDisplay display = new SlotDisplay();
        private final long[] now = {1_000L};
        private boolean heat;

        private Fixture() {
            hooks.setLoopSeam(player);
            hooks.setProgressDisplay(display);
            hooks.setHeatProbe(ignored -> heat);
            hooks.setIdResolver(stack -> RAW_ID);
            hooks.setItemFactory(id -> COOKED_ID.equals(id) ? cooked(1) : null);
            hooks.setClock(() -> now[0]);
            assertTrue(hooks.applyConfig(true, false, HandCookedSkewerService.COOKING_TICKS, ONE_RESULT,
                    arm, null), "the fixture arms the path");
            hooks.setResultSource(held -> COOKED_ID);
        }

        /** A player with one raw skewer in slot 3, selected, already cooking and with the bar up. */
        private void cookingPlayer(int amount) {
            player.put(3, raw(amount));
            player.selected = 3;
            heat = true;
            assertEquals(HandCookedSkewerHooks.SessionStart.STARTED,
                    hooks.beginSession(player.id(), new HandCookedSkewerHooks.StartTarget(
                            EquipmentSlot.HAND, 3, raw(1), RAW_ID)),
                    "the fixture starts the session");
            hooks.startProgressDisplay(player.id(), 3, RAW_ID);
            display.track(player);
            assertEquals(1, display.running(), "and opens the bar");
        }

        private void runTicks(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                arm.runTick();
            }
        }
    }
}
