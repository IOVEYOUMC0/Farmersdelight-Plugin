package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-tick gate of handheld skewer cooking and the click that owns the vanilla use, driven without a server.
 *
 * The rules under test come from the owner report: the cook must stop the moment the right-click is let go, and
 * one raw skewer must never turn into a meal and a cooked skewer at the same time. The handheld skillet is the
 * reference, with one addition — the skewer is itself food, so a native use of the cooking hand also ends the
 * session.
 */
class HandCookedSkewerGateTest {

    private static final Map<String, String> ONE_RESULT = Map.of(
            "farmersdelight:meat_skewer", "farmersdelight:cooked_meat_skewer");
    private static final String RAW_ID = "farmersdelight:meat_skewer";
    private static final String COOKED_ID = "farmersdelight:cooked_meat_skewer";

    // ── the click that owns the session ──────────────────────────────────

    /**
     * The accept path of the listener: an accepted click cancels the vanilla use, so a hungry player does not
     * eat the skewer this same click is cooking, and it starts the session and its bar.
     */
    @Test
    void anAcceptedClickCancelsTheVanillaUseAndStartsTheSession() {
        Fixture fixture = new Fixture();
        BoardPlayer player = fixture.player;
        player.selected = 3;
        fixture.heat = true;

        PlayerInteractEvent event = airClick(player, raw(1));
        fixture.hooks.onInteract(event);

        assertTrue(event.isCancelled(), "the accepted click has to stop the vanilla use");
        assertEquals(Event.Result.DENY, event.useItemInHand(), "and deny the item use for other plugins");
        assertTrue(fixture.hooks.service().isCooking(player.id()), "and start the session");
        assertEquals(1, fixture.display.starts, "and open the bar for it");
    }

    /** A click this path refuses must not be touched: the raw skewer stays edible everywhere else. */
    @Test
    void aRefusedClickLeavesTheVanillaUseAlone() {
        Fixture noHeat = new Fixture();
        noHeat.player.selected = 3;
        PlayerInteractEvent cold = airClick(noHeat.player, raw(1));
        noHeat.hooks.onInteract(cold);
        assertEquals(Event.Result.DEFAULT, cold.useItemInHand(), "no heat: the click is not ours");
        assertFalse(noHeat.hooks.service().isCooking(noHeat.player.id()));

        Fixture noResult = new Fixture();
        noResult.hooks.setResultSource(held -> null);
        noResult.player.selected = 3;
        PlayerInteractEvent plain = airClick(noResult.player, raw(1));
        noResult.hooks.onInteract(plain);
        assertEquals(Event.Result.DEFAULT, plain.useItemInHand(), "no result: the click is not ours");
        assertFalse(noResult.hooks.service().isCooking(noResult.player.id()));

        Fixture sneakOnly = new Fixture(true);
        sneakOnly.heat = true;
        sneakOnly.player.selected = 3;
        PlayerInteractEvent standing = airClick(sneakOnly.player, raw(1));
        sneakOnly.hooks.onInteract(standing);
        assertEquals(Event.Result.DEFAULT, standing.useItemInHand(), "sneak required: a standing click is not ours");
        assertFalse(sneakOnly.hooks.service().isCooking(sneakOnly.player.id()));
    }

    /** A second click on the same stack confirms the session; it does not restart it or re-open the bar. */
    @Test
    void aRepeatClickConfirmsTheSessionInsteadOfRestartingIt() {
        Fixture fixture = new Fixture();
        fixture.player.selected = 3;
        fixture.heat = true;

        fixture.hooks.onInteract(airClick(fixture.player, raw(1)));
        int armedAfterFirst = fixture.arm.armed;
        fixture.hooks.onInteract(airClick(fixture.player, raw(1)));

        assertEquals(1, fixture.display.starts, "the bar is opened once per session");
        assertEquals(armedAfterFirst, fixture.arm.armed, "the loop is armed once");
        assertTrue(fixture.hooks.service().isCooking(fixture.player.id()));
    }

    // ── releasing the click ──────────────────────────────────────────────

    @Test
    void aReleasedClickEndsTheSessionAndTakesTheBarDown() {
        Fixture fixture = new Fixture();
        UUID player = UUID.randomUUID();
        assertTrue(fixture.hooks.beginSession(player, EquipmentSlot.HAND));
        fixture.hooks.startProgressDisplay(player, 3, RAW_ID);

        fixture.arm.runTick();
        assertTrue(fixture.hooks.service().isCooking(player), "a fresh click is still held");
        assertEquals(1, fixture.display.running(), "with its bar up");

        fixture.now[0] += HandCookedSkewerHooks.INPUT_TIMEOUT_MILLIS;
        fixture.arm.runTick();
        assertTrue(fixture.hooks.service().isCooking(player),
                "the grace boundary still counts as held: the client repeats its use packet");

        fixture.now[0] += 1;
        fixture.arm.runTick();
        assertFalse(fixture.hooks.service().isCooking(player),
                "past the grace the released click ends the session");
        assertEquals(0, fixture.display.running(), "and the bar goes with it in the same tick");
    }

    /** The stop-using event removes the bar at once, without waiting for the next tick. */
    @Test
    void theStopUsingEventTakesTheBarDownImmediately() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.hooks.beginSession(fixture.player.id(), EquipmentSlot.HAND));
        fixture.hooks.startProgressDisplay(fixture.player.id(), 3, RAW_ID);
        assertEquals(1, fixture.display.running());

        fixture.hooks.onStopUsing(new PlayerStopUsingItemEvent(fixture.player.asPlayer(), raw(1), 5));

        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()), "the released use ends the session");
        assertEquals(0, fixture.display.running(), "and the bar is gone before the next tick");
    }

    /**
     * A native use takes the click: on the cooking hand it is the skewer being eaten, on the other hand it is
     * the skillet rule that the native action wins.
     */
    @Test
    void aNativeUseEndsTheSession() {
        Fixture sameHand = new Fixture();
        assertTrue(sameHand.hooks.beginSession(sameHand.player.id(), EquipmentSlot.HAND));
        sameHand.player.handRaised = true;
        sameHand.player.raisedHand = EquipmentSlot.HAND;
        sameHand.arm.runTick();
        assertFalse(sameHand.hooks.service().isCooking(sameHand.player.id()),
                "eating the skewer must not cook it at the same time");

        Fixture otherHand = new Fixture();
        assertTrue(otherHand.hooks.beginSession(otherHand.player.id(), EquipmentSlot.HAND));
        otherHand.player.handRaised = true;
        otherHand.player.raisedHand = EquipmentSlot.OFF_HAND;
        otherHand.arm.runTick();
        assertFalse(otherHand.hooks.service().isCooking(otherHand.player.id()),
                "a native use of the other hand owns the click, as it does for the skillet");
    }

    /** A dead player has no session left. */
    @Test
    void aDeadPlayerEndsTheSession() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.hooks.beginSession(fixture.player.id(), EquipmentSlot.HAND));

        fixture.player.dead = true;
        fixture.arm.runTick();

        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()));
        assertEquals(0, fixture.display.running());
    }

    // ── the stack the session is about ───────────────────────────────────

    @Test
    void movingTheSelectionOffTheSkewerEndsTheSession() {
        Fixture fixture = new Fixture();
        fixture.player.put(3, raw(1));
        fixture.player.put(5, raw(1));
        fixture.player.selected = 3;
        fixture.startCooking(3, raw(1));

        fixture.arm.runTick();
        assertTrue(fixture.hooks.service().isCooking(fixture.player.id()),
                "the selection still points at the skewer");
        assertEquals(1, fixture.display.running());

        fixture.player.selected = 5;
        fixture.arm.runTick();
        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()),
                "the hand no longer points at the stack the session was started on");
        assertEquals(0, fixture.display.running());
    }

    @Test
    void movingTheSkewerOutOfItsSlotEndsTheSession() {
        Fixture fixture = new Fixture();
        fixture.player.put(3, raw(1));
        fixture.player.selected = 3;
        fixture.startCooking(3, raw(1));

        fixture.player.slots.remove(3);
        fixture.arm.runTick();

        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()), "an empty slot cannot keep cooking");
        assertEquals(0, fixture.display.running());
    }

    @Test
    void replacingTheSkewerWithAnotherStackEndsTheSession() {
        Fixture fixture = new Fixture();
        fixture.player.put(3, raw(1));
        fixture.player.selected = 3;
        fixture.startCooking(3, raw(1));

        fixture.player.put(3, cooked(1));
        fixture.arm.runTick();

        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()), "another stack is not this session");
        assertEquals(0, fixture.display.running());
    }

    // ── the conversion ───────────────────────────────────────────────────

    @Test
    void theCompletionConsumesOneSkewerAndServesTheCookedOne() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        fixture.runTicks(HandCookedSkewerService.COOKING_TICKS);

        assertNull(fixture.player.get(3), "the single raw skewer is consumed");
        assertEquals(1, fixture.player.added.size(), "exactly one cooked skewer is served");
        assertTrue(fixture.player.added.get(0).isSimilar(cooked(1)), "and it is the configured result");
        assertFalse(fixture.hooks.service().isCooking(fixture.player.id()), "the session is over");
        assertEquals(1, fixture.display.closes, "the bar is taken down once");
        assertEquals(-1, fixture.display.amountAtClose,
                "the bar is closed after the stack changed, so its resend carries the real slot");
    }

    @Test
    void aStackOfTwoLosesExactlyOneSkewer() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(2);
        fixture.runTicks(HandCookedSkewerService.COOKING_TICKS);

        assertEquals(1, fixture.player.get(3).getAmount(), "one of the two is consumed");
        assertEquals(1, fixture.player.added.size());
    }

    /** A commit that throws must leave the hand untouched and hand nothing over. */
    @Test
    void aThrowingCommitRestoresTheHandAndServesNothing() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        fixture.player.failAddItem = true;

        assertThrows(IllegalStateException.class, () -> fixture.runTicks(HandCookedSkewerService.COOKING_TICKS));

        assertEquals(1, fixture.player.get(3).getAmount(), "the rollback puts the raw skewer back");
        assertTrue(fixture.player.added.isEmpty(), "and no cooked skewer is served");
        assertTrue(fixture.player.dropped.isEmpty(), "and nothing is dropped either");
        assertEquals(1, fixture.display.closes, "the bar still goes down");
    }

    /**
     * The completion re-checks the session: a stack that left the slot between the gate and the conversion
     * consumes and serves nothing. The fake slot answers the gate with the skewer and the conversion without
     * it, which is the window a lifecycle event opens.
     */
    @Test
    void aStackThatLeftTheSlotConsumesAndServesNothing() {
        Fixture fixture = new Fixture();
        fixture.cookingPlayer(1);
        // The gate reads the slot on every tick, so the completion's own re-check is the next read after them.
        fixture.player.vanishOnRead = HandCookedSkewerService.COOKING_TICKS + 1;

        fixture.runTicks(HandCookedSkewerService.COOKING_TICKS);

        assertEquals(1, fixture.player.get(3).getAmount(), "the raw skewer stays where it is");
        assertTrue(fixture.player.added.isEmpty(), "and no cooked skewer appears");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static PlayerInteractEvent airClick(BoardPlayer player, ItemStack item) {
        return new PlayerInteractEvent(player.asPlayer(), Action.RIGHT_CLICK_AIR, item, null, null,
                EquipmentSlot.HAND);
    }

    private static ItemStack raw(int amount) {
        return new SlotStack("raw", amount);
    }

    private static ItemStack cooked(int amount) {
        return new SlotStack("cooked", amount);
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
            this(false);
        }

        private Fixture(boolean requireSneak) {
            hooks.setLoopSeam(player);
            hooks.setProgressDisplay(display);
            hooks.setHeatProbe(ignored -> heat);
            hooks.setIdResolver(stack -> RAW_ID);
            hooks.setItemFactory(id -> COOKED_ID.equals(id) ? cooked(1) : null);
            hooks.setClock(() -> now[0]);
            assertTrue(hooks.applyConfig(true, requireSneak, HandCookedSkewerService.COOKING_TICKS, ONE_RESULT,
                    arm, null), "the fixture arms the path");
            // The result source is item-level, so the fixture answers it directly instead of resolving the
            // stack id through CraftEngine.
            hooks.setResultSource(held -> COOKED_ID);
        }

        /** Starts a session over the given slot and opens its bar, the way the listener does. */
        private void startCooking(int slot, ItemStack stack) {
            assertEquals(HandCookedSkewerHooks.SessionStart.STARTED,
                    hooks.beginSession(player.id(), new HandCookedSkewerHooks.StartTarget(
                            EquipmentSlot.HAND, slot, stack, RAW_ID)),
                    "the fixture starts the session");
            hooks.startProgressDisplay(player.id(), slot, RAW_ID);
            display.track(player);
            assertEquals(1, display.running(), "and opens the bar");
        }

        /** A player with one raw skewer in slot 3, selected, already cooking and with the bar up. */
        private void cookingPlayer(int amount) {
            player.put(3, raw(amount));
            player.selected = 3;
            heat = true;
            startCooking(3, raw(1));
        }

        private void runTicks(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                arm.runTick();
            }
        }
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

    /** A player with an in-memory slot map: enough of the API for the click and the conversion. */
    private static final class BoardPlayer implements HandCookedSkewerHooks.LoopSeam {

        private final UUID id = UUID.randomUUID();
        private final Map<Integer, ItemStack> slots = new LinkedHashMap<>();
        private final List<ItemStack> added = new ArrayList<>();
        private final List<ItemStack> dropped = new ArrayList<>();
        private int selected;
        private boolean dead;
        private boolean handRaised;
        private EquipmentSlot raisedHand = EquipmentSlot.HAND;
        private boolean failAddItem;
        /** The slot read that answers "the stack is gone", so the gate and the conversion can disagree. */
        private int vanishOnRead = -1;
        private int slotReads;
        private GameMode gameMode = GameMode.SURVIVAL;
        private int dispatches;
        private final World world = world();
        private final PlayerInventory inventory = inventory();
        private final Player player = player();

        UUID id() {
            return id;
        }

        Player asPlayer() {
            return player;
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
            dispatches++;
            task.run();
        }

        private PlayerInventory inventory() {
            return (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                    new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getHeldItemSlot" -> selected;
                        case "getItem" -> item((Integer) args[0]);
                        case "setItem" -> {
                            slots.put((Integer) args[0], (ItemStack) args[1]);
                            yield null;
                        }
                        case "getItemInMainHand" -> item(selected);
                        case "getItemInOffHand" -> item(40);
                        case "setItemInMainHand" -> {
                            slots.put(selected, (ItemStack) args[0]);
                            yield null;
                        }
                        case "setItemInOffHand" -> {
                            slots.put(40, (ItemStack) args[0]);
                            yield null;
                        }
                        case "addItem" -> addItem((ItemStack[]) args[0]);
                        case "getContents" -> contents();
                        case "setContents" -> {
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
                        case "isDead" -> dead;
                        case "isHandRaised" -> handRaised;
                        case "getHandRaised" -> handRaised ? raisedHand : EquipmentSlot.HAND;
                        case "getInventory" -> inventory;
                        case "getWorld" -> world;
                        case "getLocation" -> new Location(world, 0.5, 64.0, 0.5);
                        case "getGameMode" -> gameMode;
                        case "isOnline" -> true;
                        case "playSound" -> null;
                        case "swingMainHand" -> null;
                        case "swingOffHand" -> null;
                        case "sendActionBar" -> null;
                        case "toString" -> "fake player";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected Player call: " + method.getName());
                    });
        }

        private ItemStack item(int slot) {
            slotReads++;
            if (slotReads == vanishOnRead) {
                return null;
            }
            return slots.get(slot);
        }

        private Map<Integer, ItemStack> addItem(ItemStack[] items) {
            if (failAddItem) {
                throw new IllegalStateException("inventory write failed");
            }
            // Bukkit's addItem is declared to return a HashMap specifically, so the proxy cannot hand back
            // an immutable Map.of() here.
            Map<Integer, ItemStack> overflow = new java.util.HashMap<>();
            for (ItemStack stack : items) {
                added.add(stack);
                slots.put(20 + added.size(), stack);
            }
            return overflow;
        }

        private ItemStack[] contents() {
            ItemStack[] copy = new ItemStack[41];
            for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
                if (entry.getKey() >= 0 && entry.getKey() < copy.length) {
                    copy[entry.getKey()] = entry.getValue();
                }
            }
            return copy;
        }

        private World world() {
            return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUID" -> UUID.fromString("00000000-0000-0000-0000-0000000000bb");
                        case "dropItem" -> {
                            dropped.add((ItemStack) args[1]);
                            yield dropEntity();
                        }
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
}
