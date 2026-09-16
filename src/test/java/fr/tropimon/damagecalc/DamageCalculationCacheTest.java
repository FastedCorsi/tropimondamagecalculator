package fr.tropimon.damagecalc;

import net.minecraft.util.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class DamageCalculationCacheTest {
    @BeforeEach void resetShared() { DamageCalculationCache.clearShared(); }

    @Test void onlyRequestedSlotsAreCalculatedAndUnrelatedMovesDoNotInvalidate() throws Exception {
        DamageCalcState state = DamageCacheParityTest.basicState();
        DamageCalculationCache cache = cache(state);
        assertNull(state.calculateMove(true, -1));
        assertNull(state.calculateMove(false, 4));
        assertEquals(0, cache.calculationCount());
        DamageResult first = state.calculateMove(true, 0);
        assertEquals(1, cache.calculationCount());
        state.attacker.setMove(1, state.attacker.moveAt(0));
        state.defender.toggleZMove(3);
        assertSame(first, state.calculateMove(true, 0));
        assertEquals(1, cache.calculationCount());
        state.attacker.toggleZMove(0);
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        assertEquals(2, cache.calculationCount());
        state.attacker.deleteMove(0);
        assertNull(state.calculateMove(true, 0));
        assertEquals(2, cache.calculationCount());
    }

    @Test void independentContextsReuseValuesWithoutSharingMutablePokemonState() throws Exception {
        DamageCalcState first = DamageCacheParityTest.basicState();
        DamageResult result = first.calculateMove(true, 0);
        DamageCalcState second = DamageCacheParityTest.basicState();
        assertNotSame(first.attacker, second.attacker);
        assertSame(result, second.calculateMove(true, 0));
        assertEquals(0, cache(second).calculationCount());
        second.attacker.boosts.put(Stat.ATK, 2);
        assertNotSame(result, second.calculateMove(true, 0));
        assertEquals(0, first.attacker.boosts.get(Stat.ATK));
        assertSame(result, first.calculateMove(true, 0));
        assertThrows(UnsupportedOperationException.class, () -> result.rolls().add(123));
    }

    @Test void optimizedPokemonCopyPreservesEveryFieldAndOwnsItsContainers() throws Exception {
        PokemonSet source = DamageCacheParityTest.basicState().attacker;
        for (Field field : PokemonSet.class.getDeclaredFields()) {
            if (field.getType().isPrimitive() || field.getType() == String.class || field.getType().isEnum()) {
                mutate(field, source);
            }
        }
        source.nature = new NatureData("test", "Test", Stat.ATK, Stat.SPA);
        source.rankedEvSpread = new TropimonRankedUsageService.RankedEvSpread(6, 252, 0, 0, 0, 252, 25);
        source.evs.put(Stat.SPE, 85);
        source.ivs.put(Stat.SPD, 17);
        source.boosts.put(Stat.ATK, 2);
        source.moves.set(1, null);
        source.zMoves[0] = true;
        source.rankedMoveUsage.put("tackle", 0.7);
        source.observedMoveIds.add("tackle");
        source.manualMoveIds.add("protect");
        source.suppressedMoveIds.add("surf");
        PokemonSet copy = source.copy();
        for (Field field : PokemonSet.class.getDeclaredFields()) {
            Object original = field.get(source);
            Object copied = field.get(copy);
            if (original instanceof boolean[] flags) {
                assertArrayEquals(flags, (boolean[]) copied, field.getName());
                assertNotSame(original, copied, field.getName());
            } else {
                assertEquals(original, copied, field.getName());
                if (original instanceof java.util.Map || original instanceof java.util.Collection) {
                    assertNotSame(original, copied, field.getName());
                }
            }
        }
        copy.evs.put(Stat.SPE, 0);
        copy.observedMoveIds.clear();
        assertEquals(85, source.evs.get(Stat.SPE));
        assertTrue(source.observedMoveIds.contains("tackle"));
    }

    @Test void hashCollisionsDoNotReuseWrongMoveOrPokemonData() {
        DamageCalcState state = DamageCacheParityTest.basicState();
        state.attacker.item = "Aa";
        DamageResult first = state.calculateMove(true, 0);
        state.attacker.item = "BB";
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotSame(first, state.calculateMove(true, 0));
        MoveData original = state.attacker.moveAt(0);
        state.attacker.moves.set(0, new MoveData(original.id(), "Aa", original.type(), original.category(),
                original.basePower(), original.spreadMove(), original.contact(), original.flags(), original.priority()));
        first = state.calculateMove(true, 0);
        state.attacker.moves.set(0, new MoveData(original.id(), "BB", original.type(), original.category(),
                original.basePower(), original.spreadMove(), original.contact(), original.flags(), original.priority()));
        assertNotSame(first, state.calculateMove(true, 0));
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
    }

    @Test void mutableSpeciesAndNatureDataWithSameIdInvalidate() throws Exception {
        DamageCalcState state = DamageCacheParityTest.basicState();
        DamageResult before = state.calculateMove(true, 0);
        state.attacker.species.baseStats().put(Stat.ATK, 200);
        assertNotEquals(before, state.calculateMove(true, 0));
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        state.attacker.nature = new NatureData("serious", "Serious", Stat.ATK, Stat.SPA);
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        assertEquals(3, cache(state).calculationCount());
        SpeciesData s = state.attacker.species;
        ArrayList<String> aspects = new ArrayList<>(s.aspects());
        state.attacker.species = new SpeciesData(s.id(), s.name(), s.primaryType(), s.secondaryType(), s.baseStats(),
                true, s.texturePath(), "ogerpon", aspects, s.weightKg() * 2);
        state.calculateMove(true, 0);
        aspects.add("wellspring");
        assertTrue(cache(state).prepare(state));
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
    }

    @Test void moveDataChangesWithTheSameIdentifierCannotReuseStaleResults() {
        DamageCalcState state = DamageCacheParityTest.basicState();
        MoveData move = state.attacker.moveAt(0);
        List<MoveData> variants = List.of(
                new MoveData(move.id(), move.name(), PokeType.FAIRY, move.category(), move.basePower(), false, true, move.flags(), 0),
                new MoveData(move.id(), move.name(), move.type(), DamageCategory.SPECIAL, move.basePower(), false, true, move.flags(), 0),
                new MoveData(move.id(), move.name(), move.type(), move.category(), 160, false, true, move.flags(), 0),
                new MoveData(move.id(), move.name(), move.type(), move.category(), move.basePower(), true, true, move.flags(), 0),
                new MoveData(move.id(), move.name(), move.type(), move.category(), move.basePower(), false, false, Set.of("punch"), 0),
                new MoveData(move.id(), move.name(), move.type(), move.category(), move.basePower(), false, true, move.flags(), 2));
        for (MoveData changed : variants) {
            state.attacker.moves.set(0, move);
            DamageResult before = state.calculateMove(true, 0);
            state.attacker.moves.set(0, changed);
            assertNotSame(before, state.calculateMove(true, 0));
            assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        }
    }

    @Test void allFormulaFieldsInvalidateEvenWhenWrittenReflectively() throws Exception {
        Set<String> provenanceOnly = Set.of("species", "nature", "evs", "ivs", "boosts", "moves", "zMoves",
                "battleDataMode", "battleId", "battleName", "battleFormObserved", "rankedMoveUsage", "observedMoveIds", "manualMoveIds",
                "suppressedMoveIds", "rankedProfileKey", "rankedItemSuggested", "rankedAbilitySuggested", "rankedNatureSuggested",
                "rankedEvSpread", "evsManuallyEdited");
        for (boolean attacker : new boolean[]{true, false}) {
            for (Field field : PokemonSet.class.getDeclaredFields()) {
                if (provenanceOnly.contains(field.getName())) continue;
                DamageCalcState state = DamageCacheParityTest.basicState();
                cache(state).prepare(state);
                mutate(field, attacker ? state.attacker : state.defender);
                assertTrue(cache(state).prepare(state), field.getName());
                assertEquals(direct(state, true, 0), state.calculateMove(true, 0), field.getName());
            }
            for (Field field : SideConditions.class.getDeclaredFields()) {
                if (field.getName().equals("partnerName")) continue;
                DamageCalcState state = DamageCacheParityTest.basicState();
                cache(state).prepare(state);
                mutate(field, attacker ? state.field.attackerSide : state.field.defenderSide);
                assertTrue(cache(state).prepare(state), field.getName());
                assertEquals(direct(state, true, 1), state.calculateMove(true, 1), field.getName());
            }
        }
        for (Field field : FieldState.class.getDeclaredFields()) {
            if (field.getType() == SideConditions.class) continue;
            DamageCalcState state = DamageCacheParityTest.basicState();
            cache(state).prepare(state);
            mutate(field, state.field);
            assertTrue(cache(state).prepare(state), field.getName());
            assertEquals(direct(state, false, 1), state.calculateMove(false, 1), field.getName());
        }
    }

    @Test void everyStatAndFormInputUpdatesDamageAndDisplayedTotals() throws Exception {
        for (boolean attacker : new boolean[]{true, false}) {
            for (Stat stat : Stat.values()) {
                DamageCalcState state = DamageCacheParityTest.basicState();
                PokemonSet pokemon = attacker ? state.attacker : state.defender;
                for (var values : List.of(pokemon.evs, pokemon.ivs, pokemon.boosts)) {
                    state.prepareCalculations();
                    state.preparedStat(attacker, stat);
                    values.put(stat, values.get(stat) == 31 ? 15 : 2);
                    assertTrue(cache(state).prepare(state), stat.name());
                    assertEquals(direct(state, attacker, 0), state.calculateMove(attacker, 0));
                    assertEquals(stat == Stat.HP ? pokemon.maxHp() : DamageCalculator.displayedStat(pokemon, stat,
                            state.field, attacker ? state.field.attackerSide : state.field.defenderSide), state.preparedStat(attacker, stat));
                }
            }
        }
    }

    @Test void reloadEvictionSwapAndLanguageCannotRetainStaleValues() throws Exception {
        DamageCalcState state = DamageCacheParityTest.basicState();
        DamageResult first = state.calculateMove(true, 0);
        DamageCalculationCache.clearShared();
        assertNotSame(first, state.calculateMove(true, 0));
        PokemonSet previous = state.attacker;
        state.attacker = state.defender;
        state.defender = previous;
        state.field.swapSides();
        assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        for (int i = 0; i < 150; i++) {
            state.attacker.item = "item" + i;
            state.calculateMove(true, 0);
        }
        Field shared = DamageCalculationCache.class.getDeclaredField("SHARED");
        shared.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) shared.get(null)).size() <= 128);
        Language original = Language.getInstance();
        try {
            Language.setInstance(new Language() {
                @Override public String get(String key, String fallback) { return "translated:" + key; }
                @Override public boolean hasTranslation(String key) { return true; }
                @Override public boolean isRightToLeft() { return false; }
                @Override public net.minecraft.text.OrderedText reorder(net.minecraft.text.StringVisitable text) {
                    return net.minecraft.text.OrderedText.EMPTY;
                }
            });
            assertTrue(cache(state).prepare(state));
            state.attacker.zMoves[0] = true;
            assertEquals(direct(state, true, 0), state.calculateMove(true, 0));
        } finally { Language.setInstance(original); }
    }

    private static void mutate(Field field, Object target) throws Exception {
        if (field.getType() == boolean.class) field.setBoolean(target, !field.getBoolean(target));
        else if (field.getType() == int.class) field.setInt(target, field.getInt(target) + 1);
        else if (field.getType() == String.class) field.set(target, field.get(target) + " changed");
        else if (field.getType().isEnum()) {
            Object[] values = field.getType().getEnumConstants();
            field.set(target, values[(((Enum<?>) field.get(target)).ordinal() + 1) % values.length]);
        } else fail("New model field needs a cache test: " + field);
    }

    private static DamageCalculationCache cache(DamageCalcState state) throws Exception {
        Field field = DamageCalcState.class.getDeclaredField("damageCache");
        field.setAccessible(true);
        return (DamageCalculationCache) field.get(state);
    }

    private static DamageResult direct(DamageCalcState state, boolean attacker, int slot) {
        PokemonSet source = attacker ? state.attacker : state.defender;
        return DamageCalculator.calculate(source, attacker ? state.defender : state.attacker,
                DamageCalcState.effectiveMove(source, slot), state.field,
                attacker ? state.field.attackerSide : state.field.defenderSide,
                attacker ? state.field.defenderSide : state.field.attackerSide);
    }
}
