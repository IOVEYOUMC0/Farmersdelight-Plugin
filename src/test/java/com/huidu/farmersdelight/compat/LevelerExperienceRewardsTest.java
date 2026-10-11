package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.config.CookingPotExperienceRewardConfig;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.compatibility.CompatibilityManager;
import net.momirealms.craftengine.core.plugin.compatibility.LevelerProvider;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LevelerExperienceRewardsTest {
    @Test
    void failedLegacyRawRewardDoesNotAbortOtherProviders() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("jobs");
        LevelerExperienceRewards.award(config("""
                mode: both
                auraskills: {skill: farming, raw: true}
                levelers: [{plugin: jobs, target: Cook, amount: 3.0}]
                """), 2, fixture.player, fixture.manager,
                xp -> { throw new NoClassDefFoundError("AuraSkills unavailable"); }, fixture.failures::add);
        assertEquals(List.of("jobs:Cook=3.0"), fixture.received);
        assertEquals(1, fixture.failures.size());
    }

    @Test
    void forwardsRealBaseAndProviderSpecificTargets() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("mmocore");
        fixture.add("jobs");
        fixture.award("""
                mode: levelers
                levelers:
                  - {plugin: MMOCORE, target: Custom:Cooking, multiplier: 2.5}
                  - {plugin: jobs, target: Cook, amount: 4.0}
                """, 1.2);
        assertEquals(List.of("mmocore:Custom:Cooking=3.0", "jobs:Cook=4.0"), fixture.received);
        assertTrue(fixture.raw.isEmpty());
    }

    @Test
    void modesAndGenericAuraSkillsPriorityAvoidDoubleRewards() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("auraskills");
        String rewards = """
                auraskills: {skill: farming, multiplier: 1.0, raw: true}
                levelers:
                  - {plugin: auraskills, target: cooking, amount: 6.0}
                """;
        fixture.award("mode: both\n" + rewards, 2);
        assertEquals(List.of("auraskills:cooking=6.0"), fixture.received);
        assertTrue(fixture.raw.isEmpty());
        fixture.received.clear();
        fixture.award("mode: auraskills\n" + rewards, 2);
        assertTrue(fixture.received.isEmpty());
        assertEquals(2.0, fixture.raw.getFirst().amount());
        fixture.raw.clear();
        fixture.award("mode: none\n" + rewards, 2);
        fixture.award("mode: vanilla\n" + rewards, 2);
        assertTrue(fixture.received.isEmpty());
        assertTrue(fixture.raw.isEmpty());
    }

    @Test
    void legacyNonRawUsesCeAndRawRetainsItsDedicatedPath() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("auraskills");
        fixture.award("""
                mode: auraskills
                auraskills:
                  - {skill: farming, multiplier: 2.0, raw: false}
                  - {skill: Custom:Cooking, amount: 5.0, raw: true}
                """, 1.5);
        assertEquals(List.of("auraskills:farming=3.0"), fixture.received);
        assertEquals(new AuraSkillsHook.XpDefinition("custom/cooking", 5.0, true), fixture.raw.getFirst());
    }

    @Test
    void missingAndThrowingProvidersDoNotReplaceOrBlockOtherRewards() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("jobs");
        fixture.add("broken");
        fixture.award("""
                mode: levelers
                levelers:
                  - {plugin: missing, target: Cook, amount: 1.0}
                  - {plugin: broken, target: Cook, amount: 2.0}
                  - {plugin: jobs, target: Cook, amount: 3.0}
                """, 2);
        assertEquals(List.of("jobs:Cook=3.0"), fixture.received);
        assertEquals(1, fixture.failures.size());
        fixture.add("missing");
        fixture.award("mode: levelers\nlevelers: [{plugin: missing, target: Cook, amount: 1.0}]", 2);
        assertEquals("missing:Cook=1.0", fixture.received.getLast());
    }

    @Test
    void nonFiniteValuesZeroChanceAndMissingPlayerCannotReachProviders() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add("jobs");
        String yaml = "mode: levelers\nlevelers: [{plugin: jobs, target: Cook, amount: 3.0}]";
        for (double base : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) fixture.award(yaml, base);
        fixture.award("mode: levelers\nlevelers: [{plugin: jobs, target: Cook, amount: 3.0, chance: 0}]", 2);
        fixture.award("mode: levelers\nlevelers: [{plugin: jobs, target: Cook, multiplier: 1.0e308}]", 2);
        LevelerExperienceRewards.award(config(yaml), 2, null, fixture.manager, fixture.raw::add, fixture.failures::add);
        LevelerExperienceRewards.award(config(yaml), 2, fixture.player, null, fixture.raw::add, fixture.failures::add);
        assertTrue(fixture.received.isEmpty());
    }

    private static CookingPotExperienceRewardConfig config(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        CookingPotExperienceRewardConfig config = new CookingPotExperienceRewardConfig();
        config.loadFromConfig(yaml);
        return config;
    }

    private static final class Fixture {
        final List<String> received = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<AuraSkillsHook.XpDefinition> raw = new ArrayList<>();
        final Map<String, LevelerProvider> providers = new HashMap<>();
        final Player player = playerToken();
        final CompatibilityManager manager = (CompatibilityManager) Proxy.newProxyInstance(
                CompatibilityManager.class.getClassLoader(), new Class<?>[]{CompatibilityManager.class},
                (proxy, method, args) -> {
                    assertEquals("getLevelerProvider", method.getName());
                    return providers.get(args[0]);
                });
        void add(String id) {
            providers.put(id, new LevelerProvider() {
                @Override public String plugin() { return id; }
                @Override public int getLevel(Player player, String target) { throw new AssertionError("not needed"); }
                @Override public void addExp(Player recipient, String target, double amount) {
                    assertSame(player, recipient);
                    if (id.equals("broken")) throw new IllegalStateException("provider unavailable");
                    received.add(id + ":" + target + "=" + amount);
                }
            });
        }
        void award(String yaml, double base) throws Exception {
            LevelerExperienceRewards.award(config(yaml), base, player, manager, raw::add, failures::add);
        }
    }

    private static Player playerToken() {
        try {
            // The bridge forwards this token unchanged; constructing a CE player requires a running server.
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Player) ((Unsafe) field.get(null)).allocateInstance(BukkitServerPlayer.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
