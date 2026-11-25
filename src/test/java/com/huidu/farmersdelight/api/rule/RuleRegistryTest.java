package com.huidu.farmersdelight.api.rule;

import com.huidu.farmersdelight.api.content.ContentId;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRegistryTest {

    private static final ContentId ID = ContentId.parse("testaddon:rule");

    private static Block anyBlock() {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> null);
    }

    private static StationRule station(boolean matches) {
        return new StationRule() {
            @Override
            public ContentId id() {
                return ID;
            }

            @Override
            public boolean matches(Block block) {
                return matches;
            }

            @Override
            public String recipeTypeId() {
                return "testaddon:station";
            }
        };
    }

    private static CropRule crop(boolean matches) {
        return new CropRule() {
            @Override
            public ContentId id() {
                return ID;
            }

            @Override
            public boolean matches(Block block) {
                return matches;
            }

            @Override
            public boolean isHarvestReady(Block block) {
                return true;
            }

            @Override
            public int age(Block block) {
                return 3;
            }

            @Override
            public boolean resetsOnHarvest(Block block) {
                return true;
            }
        };
    }

    private static RecipeCondition condition(boolean result, boolean throwing) {
        return new RecipeCondition() {
            @Override
            public ContentId id() {
                return ID;
            }

            @Override
            public boolean test(RecipeContext context) {
                if (throwing) {
                    throw new IllegalStateException("addon condition failure");
                }
                return result;
            }
        };
    }

    private static RecipeContext context() {
        return RecipeContext.of(null, null, List.of(), null);
    }

    @BeforeEach
    void reset() {
        StationRules.clearForTests();
        CropRules.clearForTests();
        RecipeConditions.clearForTests();
    }

    @AfterEach
    void clear() {
        reset();
    }

    @Test
    void stationRegistrationRejectsDuplicatesAndSupportsUnregister() {
        StationRule rule = station(true);
        assertTrue(StationRules.register(rule));
        assertFalse(StationRules.register(rule));
        assertSame(rule, StationRules.find(ID).orElseThrow());
        assertEquals(1, StationRules.ids().size());

        assertTrue(StationRules.unregister(ID));
        assertFalse(StationRules.unregister(ID));
        assertTrue(StationRules.find(ID).isEmpty());
    }

    @Test
    void stationMatchingIgnoresNonMatchingAndBrokenRules() {
        assertNull(StationRules.matching(anyBlock()));
        StationRules.register(station(false));
        assertNull(StationRules.matching(anyBlock()));

        StationRule throwing = new StationRule() {
            @Override
            public ContentId id() {
                return ContentId.parse("testaddon:throwing");
            }

            @Override
            public boolean matches(Block block) {
                throw new IllegalStateException("boom");
            }
        };
        StationRules.register(throwing);
        assertNull(StationRules.matching(anyBlock()));
    }

    @Test
    void stationMatchingUsesRegistrationOrder() {
        StationRule first = station(true);
        StationRules.register(first);
        StationRules.register(new StationRule() {
            @Override
            public ContentId id() {
                return ContentId.parse("testaddon:second");
            }

            @Override
            public boolean matches(Block block) {
                return true;
            }
        });
        assertSame(first, StationRules.matching(anyBlock()));
    }

    @Test
    void cropRegistrationAndMatchingFollowTheSameContract() {
        CropRule rule = crop(true);
        assertTrue(CropRules.register(rule));
        assertFalse(CropRules.register(rule));
        assertSame(rule, CropRules.find(ID).orElseThrow());
        assertSame(rule, CropRules.matching(anyBlock()));
        assertTrue(CropRules.matching(anyBlock()).isHarvestReady(anyBlock()));
        assertEquals(3, CropRules.matching(anyBlock()).age(anyBlock()));
        assertTrue(CropRules.matching(anyBlock()).resetsOnHarvest(anyBlock()));
        assertTrue(CropRules.unregister(ID));
    }

    @Test
    void pluginShutdownClearsEveryRuleTable() {
        StationRules.register(station(true));
        CropRules.register(crop(true));
        RecipeConditions.register(condition(true, false));
        assertTrue(StationRules.find(ID).isPresent());
        assertTrue(CropRules.find(ID).isPresent());
        assertTrue(RecipeConditions.find(ID).isPresent());

        StationRules.clearAll();
        CropRules.clearAll();
        RecipeConditions.clearAll();

        assertTrue(StationRules.find(ID).isEmpty());
        assertTrue(CropRules.find(ID).isEmpty());
        assertTrue(RecipeConditions.find(ID).isEmpty());
    }

    @Test
    void recipeConditionsFailClosedAndHaveACheapEmptyCheck() {
        assertFalse(RecipeConditions.hasConditions());
        assertTrue(RecipeConditions.allMatch(null));

        assertTrue(RecipeConditions.register(condition(true, false)));
        assertTrue(RecipeConditions.hasConditions());
        assertTrue(RecipeConditions.allMatch(context()));

        RecipeConditions.clearForTests();
        assertTrue(RecipeConditions.register(condition(false, false)));
        assertFalse(RecipeConditions.allMatch(context()));

        RecipeConditions.clearForTests();
        assertTrue(RecipeConditions.register(condition(true, true)));
        assertFalse(RecipeConditions.allMatch(context()));
    }

    @Test
    void aConditionCanScopeItselfAwayFromUnrelatedRecipes() {
        RecipeCondition scoped = new RecipeCondition() {
            @Override
            public ContentId id() {
                return ID;
            }

            @Override
            public boolean appliesTo(String recipeType, String recipeId) {
                return "myaddon:keg".equals(recipeType);
            }

            @Override
            public boolean test(RecipeContext context) {
                return false;
            }
        };
        assertTrue(RecipeConditions.register(scoped));
        assertTrue(RecipeConditions.allMatch(
                RecipeContext.of(null, null, List.of(), null, null, "cooking_pot", "farmersdelight:stew")));
        assertFalse(RecipeConditions.allMatch(
                RecipeContext.of(null, null, List.of(), null, null, "myaddon:keg", "myaddon:booze")));
    }

    @Test
    void recipeConditionsEvaluateInRegistrationOrder() {
        List<ContentId> evaluated = new ArrayList<>();
        for (String id : List.of("testaddon:first", "testaddon:second")) {
            ContentId contentId = ContentId.parse(id);
            assertTrue(RecipeConditions.register(new RecipeCondition() {
                @Override
                public ContentId id() {
                    return contentId;
                }

                @Override
                public boolean test(RecipeContext context) {
                    evaluated.add(contentId);
                    return true;
                }
            }));
        }

        assertTrue(RecipeConditions.allMatch(context()));
        assertEquals(List.of(ContentId.parse("testaddon:first"), ContentId.parse("testaddon:second")), evaluated);
    }

    @Test
    void recipeContextAcceptsEmptyInputsWithoutABukkitServer() {
        RecipeContext context = RecipeContext.of(null, null, List.of(), null, null);
        assertNotNull(context.inputs());
        assertEquals(0, context.inputs().size());
        assertNull(context.container());
        assertNull(context.tool());
    }
}
