package fr.tropimon.damagecalc;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RankedEvSpreadTest {
    @Test void selectsTheMostUsedCompleteSpreadFromTheApiResponse() {
        var profile = TropimonRankedUsageService.profileFromJson("Rillaboom", """
                {"spreads": {
                  "6 HP / 252 Atk / 252 Spe": 21.15,
                  "252 Atk / 6 SpD / 252 Spe": 25,
                  "4 HP / 252 Atk / 1 Def / 1 SpD / 252 Spe": 11.54
                }}
                """);
        assertEquals(new TropimonRankedUsageService.RankedEvSpread(0, 252, 0, 0, 6, 252, 25), profile.evSpread());
    }

    @Test void invalidSpreadsAreSkippedWithoutLosingOtherProfileData() {
        for (String invalid : List.of("253 Atk", "252 HP / 252 Atk / 252 Spe", "4 Atk / 8 Atk",
                "-1 Atk", "252 Unknown", "252 Atk /", "252 Atk / garbage", "252 Atk + 252 Spe", "999999 Atk")) {
            String json = new Gson().toJson(Map.of("items", Map.of("Leftovers", 100),
                    "spreads", Map.of(invalid, 90, "252 SpA / 252 Spe / 6 HP", 10)));
            var profile = TropimonRankedUsageService.profileFromJson("abomasnow", json);
            assertEquals("Leftovers", profile.item());
            assertEquals(new TropimonRankedUsageService.RankedEvSpread(6, 0, 0, 252, 0, 252, 10), profile.evSpread(), invalid);
        }
        var badUsage = TropimonRankedUsageService.profileFromJson("abomasnow", """
                {"spreads":{"252 Atk": "NaN", "252 SpA": 101, "252 Spe": -1, "252 HP": 0, "6 Def": 10}}
                """);
        assertEquals(new TropimonRankedUsageService.RankedEvSpread(0, 0, 6, 0, 0, 0, 10), badUsage.evSpread());
    }

    @Test void missingOrEmptySpreadsDoNotInventEvs() {
        for (String json : List.of("{}", "{\"spreads\": null}", "{\"spreads\": {}}", "{\"spreads\": {\"bad\": 100}}")) {
            PokemonSet pokemon = unknown();
            var profile = TropimonRankedUsageService.profileFromJson("abomasnow", json);
            TropimonRankedUsageService.applyProfile(pokemon, profile);
            assertNull(profile.evSpread());
            assertNull(pokemon.rankedEvSpread);
            assertTrue(pokemon.evs.values().stream().allMatch(value -> value == 0));
        }
    }

    @Test void tiesAreDeterministicAndOmittedStatsAreZero() {
        var first = TropimonRankedUsageService.profileFromJson("abomasnow", """
                {"spreads":{"252 SpA / 252 Spe": 50, "252 Atk / 252 Spe": 50}}
                """);
        var reversed = TropimonRankedUsageService.profileFromJson("abomasnow", """
                {"spreads":{"252 Atk / 252 Spe": 50, "252 SpA / 252 Spe": 50}}
                """);
        assertEquals(first.evSpread(), reversed.evSpread());
        assertEquals(new TropimonRankedUsageService.RankedEvSpread(0, 252, 0, 0, 0, 252, 50), first.evSpread());
    }

    @Test void suggestionRemainsUnknownAndDoesNotModifyIvsOrObservedHp() {
        PokemonSet pokemon = unknown();
        pokemon.ivs.put(Stat.ATK, 17);
        pokemon.currentHp = 40;
        pokemon.observedMaxHp = 80;
        var ivs = new EnumMap<>(pokemon.ivs);
        TropimonRankedUsageService.applyProfile(pokemon, profile());
        assertEquals(252, pokemon.evs.get(Stat.HP));
        assertEquals(252, pokemon.evs.get(Stat.DEF));
        assertEquals(6, pokemon.evs.get(Stat.SPD));
        assertEquals(ivs, pokemon.ivs);
        assertFalse(pokemon.statsKnown);
        assertNotNull(pokemon.rankedEvSpread);
        assertEquals(40, pokemon.currentHp);
        assertEquals(80, pokemon.observedMaxHp);
    }

    @Test void actualStatsAndExistingManualValuesAreNeverReplacedByApiEvs() {
        PokemonSet owned = unknown();
        owned.statsKnown = true;
        TropimonRankedUsageService.applyProfile(owned, profile());
        assertTrue(owned.evs.values().stream().allMatch(value -> value == 0));
        assertNull(owned.rankedEvSpread);

        PokemonSet configured = unknown();
        configured.evs.put(Stat.SPE, 100);
        TropimonRankedUsageService.applyProfile(configured, profile());
        assertEquals(100, configured.evs.get(Stat.SPE));
        assertEquals(0, configured.evs.get(Stat.HP));
        assertNull(configured.rankedEvSpread);

        PokemonSet explicitlyEmpty = unknown();
        explicitlyEmpty.markEvsEdited();
        TropimonRankedUsageService.applyProfile(explicitlyEmpty, profile());
        assertTrue(explicitlyEmpty.evs.values().stream().allMatch(value -> value == 0));
        assertNull(explicitlyEmpty.rankedEvSpread);
    }

    @Test void lateApiResponseHydratesTheDisplayedOpponentDuringSync() {
        DamageCalcState state = new DamageCalcState();
        PokemonSet live = unknown();
        state.setFromBattle(new BattlePokemonSnapshot(null, live, false));
        PokemonSet enriched = live.copy();
        TropimonRankedUsageService.applyProfile(enriched, profile());
        state.setFromBattle(new BattlePokemonSnapshot(null, enriched, false));
        assertEquals(enriched.evs, state.defender.evs);
        assertEquals(enriched.rankedEvSpread, state.defender.rankedEvSpread);
        assertFalse(state.defender.statsKnown);
        state.setFromBattle(new BattlePokemonSnapshot(null, live.copy(), false));
        assertEquals(enriched.evs, state.defender.evs);
    }

    @Test void rosterCopiesPreserveSuggestionsWithoutReapplyingOnEverySnapshot() {
        PokemonSet existing = unknown();
        TropimonRankedUsageService.applyProfile(existing, profile());
        PokemonSet fresh = unknown();
        TropimonRankedUsageService.mergeEvSuggestion(fresh, existing);
        TropimonRankedUsageService.mergeMoveKnowledge(fresh, existing);
        assertEquals(existing.evs, fresh.evs);
        assertEquals(existing.rankedProfileKey, fresh.rankedProfileKey);
        assertSame(existing.rankedEvSpread, fresh.copy().rankedEvSpread);
        fresh.evs.put(Stat.DEF, 0);
        fresh.markEvsEdited();
        TropimonRankedUsageService.mergeEvSuggestion(fresh, existing);
        assertEquals(0, fresh.evs.get(Stat.DEF));
        assertNull(fresh.rankedEvSpread);
    }

    @Test void manualEditsAndPresetsSurviveRepeatedSyncAndPredictionCleanup() {
        PokemonSet live = unknown();
        TropimonRankedUsageService.applyProfile(live, profile());
        DamageCalcState state = new DamageCalcState();
        state.setFromBattle(new BattlePokemonSnapshot(null, live.copy(), false));
        state.defender.evs.replaceAll((stat, value) -> 0);
        state.defender.markEvsEdited();
        for (int i = 0; i < 3; i++) state.setFromBattle(new BattlePokemonSnapshot(null, live.copy(), false));
        TropimonRankedUsageService.clearPrediction(state.defender);
        assertTrue(state.defender.evs.values().stream().allMatch(value -> value == 0));
        assertTrue(state.defender.evsManuallyEdited);
        for (EvPreset preset : EvPreset.values()) {
            DamageCalcState.presetEvs(state.defender, preset);
            var expected = new EnumMap<>(state.defender.evs);
            state.setFromBattle(new BattlePokemonSnapshot(null, live.copy(), false));
            assertEquals(expected, state.defender.evs);
            assertNull(state.defender.rankedEvSpread);
        }
    }

    @Test void newlyKnownEvsReplaceOnlyPredictions() {
        PokemonSet live = unknown();
        TropimonRankedUsageService.applyProfile(live, profile());
        DamageCalcState state = new DamageCalcState();
        state.setFromBattle(new BattlePokemonSnapshot(null, live.copy(), false));
        PokemonSet known = live.copy();
        known.evs.replaceAll((stat, value) -> 0);
        known.evs.put(Stat.SPE, 200);
        known.ivs.put(Stat.ATK, 15);
        known.statsKnown = true;
        state.setFromBattle(new BattlePokemonSnapshot(null, known, false));
        assertEquals(known.evs, state.defender.evs);
        assertEquals(15, state.defender.ivs.get(Stat.ATK));
        assertNull(state.defender.rankedEvSpread);

        state.defender = live.copy();
        state.defender.evs.put(Stat.SPE, 120);
        state.defender.markEvsEdited();
        state.setFromBattle(new BattlePokemonSnapshot(null, known, false));
        assertEquals(120, state.defender.evs.get(Stat.SPE));
    }

    @Test void randomBattleKeepsItsOwnEvsAndSeriousNature() {
        PokemonSet player = unknown();
        CobblemonBattleDataProvider.applyRandomBattlePlayerEvDefaults(player);
        PokemonSet opponent = unknown();
        TropimonRankedUsageService.applyProfile(opponent, profile());
        DamageCalcState state = new DamageCalcState();
        state.setFromBattle(new BattlePokemonSnapshot(null, opponent.copy(), false));
        TropimonRankedUsageService.clearPrediction(opponent);
        CobblemonBattleDataProvider.applyRandomBattleOpponentRules(player, opponent);
        state.setFromBattle(new BattlePokemonSnapshot(null, opponent.copy(), false));
        assertTrue(state.defender.evs.values().stream().allMatch(value -> value == 85));
        assertEquals("serious", state.defender.nature.id());
        assertNull(state.defender.rankedEvSpread);
        TropimonRankedUsageService.applyProfile(opponent, profile());
        assertTrue(opponent.evs.values().stream().allMatch(value -> value == 85));
        assertNull(opponent.rankedEvSpread);
    }

    @Test void normalAndRandomBattleDataNeverMergeEvenForTheSamePokemon() {
        PokemonSet random = unknown();
        random.battleId = "same-runtime-id";
        CobblemonBattleDataProvider.applyRandomBattlePlayerEvDefaults(random);
        random.nature = TropimonDex.nature("serious");
        random.natureKnown = true;

        DamageCalcState state = new DamageCalcState();
        state.setFromBattle(new BattlePokemonSnapshot(null, random.copy(), false));
        assertEquals(BattleDataMode.RANDOM, state.defender.battleDataMode);
        assertTrue(state.defender.evs.values().stream().allMatch(value -> value == 85));

        PokemonSet normal = unknown();
        normal.battleId = "same-runtime-id";
        normal.battleDataMode = BattleDataMode.NORMAL;
        TropimonRankedUsageService.applyProfile(normal, profile());
        state.setFromBattle(new BattlePokemonSnapshot(null, normal.copy(), false));

        assertEquals(BattleDataMode.NORMAL, state.defender.battleDataMode);
        assertEquals(normal.evs, state.defender.evs);
        assertEquals(normal.rankedEvSpread, state.defender.rankedEvSpread);
        assertFalse(state.defender.evs.values().stream().allMatch(value -> value == 85));

        PokemonSet nextRandom = unknown();
        nextRandom.battleId = "same-runtime-id";
        CobblemonBattleDataProvider.applyRandomBattlePlayerEvDefaults(nextRandom);
        state.setFromBattle(new BattlePokemonSnapshot(null, nextRandom.copy(), false));

        assertEquals(BattleDataMode.RANDOM, state.defender.battleDataMode);
        assertTrue(state.defender.evs.values().stream().allMatch(value -> value == 85));
        assertNull(state.defender.rankedEvSpread);
        assertTrue(state.defender.rankedProfileKey.isBlank());
    }

    @Test void eachPredictionPipelineRejectsTheOtherBattleMode() {
        PokemonSet random = unknown();
        CobblemonBattleDataProvider.applyRandomBattlePlayerEvDefaults(random);
        TropimonRankedUsageService.applyProfile(random, profile());
        assertTrue(random.rankedProfileKey.isBlank());
        assertNull(random.rankedEvSpread);

        PokemonSet normal = unknown();
        normal.battleDataMode = BattleDataMode.NORMAL;
        assertEquals(0, TropimonRandomBattleSets.applyInference(normal));
        assertEquals(BattleDataMode.NORMAL, normal.battleDataMode);
    }

    @Test void changingFormsClearsOnlyTheOldPredictedEvs() {
        PokemonSet pokemon = unknown();
        TropimonRankedUsageService.applyProfile(pokemon, profile());
        TropimonRankedUsageService.clearPrediction(pokemon);
        assertTrue(pokemon.evs.values().stream().allMatch(value -> value == 0));
        assertNull(pokemon.rankedEvSpread);
        TropimonRankedUsageService.applyProfile(pokemon, profile());
        pokemon.evs.put(Stat.ATK, 120); // A reflective consumer can also modify values.
        var modified = new EnumMap<>(pokemon.evs);
        TropimonRankedUsageService.clearPrediction(pokemon);
        assertEquals(modified, pokemon.evs);
        PokemonSet different = unknown();
        different.species = DamageCacheParityTest.basicState().attacker.species;
        PokemonSet predicted = unknown();
        TropimonRankedUsageService.applyProfile(predicted, profile());
        assertNotEquals(different.species.id(), predicted.species.id());
        TropimonRankedUsageService.mergeEvSuggestion(different, predicted);
        assertNull(different.rankedEvSpread);
    }

    @Test void suggestedAndManualEvsInvalidateDamageAndStatsButProvenanceDoesNot() {
        DamageCalcState state = DamageCacheParityTest.basicState();
        state.defender.statsKnown = false;
        DamageResult before = state.calculateMove(true, 0);
        TropimonRankedUsageService.applyProfile(state.defender, profile());
        DamageResult after = state.calculateMove(true, 0);
        assertNotEquals(before, after);
        assertEquals(DamageCalculator.calculate(state.attacker, state.defender,
                state.attacker.moveAt(0), state.field, state.field.attackerSide, state.field.defenderSide), after);
        state.defender.markEvsEdited();
        assertSame(after, state.calculateMove(true, 0));
        state.defender.evs.put(Stat.DEF, 0);
        assertNotEquals(after, state.calculateMove(true, 0));
        state.prepareCalculations();
        assertEquals(DamageCalculator.displayedStat(state.defender, Stat.DEF, state.field, state.field.defenderSide),
                state.preparedStat(false, Stat.DEF));
    }

    @Test void resetPrivacyAndSwapDoNotMixUpEvProvenance() {
        PokemonSet pokemon = unknown();
        TropimonRankedUsageService.applyProfile(pokemon, profile());
        PokemonSet hidden = CobblemonBattleDataProvider.hideOpponentPrivateData(pokemon);
        assertNull(hidden.rankedEvSpread);
        assertFalse(hidden.evsManuallyEdited);
        assertTrue(hidden.evs.values().stream().allMatch(value -> value == 0));
        DamageCalcState state = new DamageCalcState();
        state.defender = pokemon.copy();
        state.swap();
        assertEquals(pokemon.rankedEvSpread, state.attacker.rankedEvSpread);
        assertNull(state.defender.rankedEvSpread);
        state.resetAfterRandomBattle();
        assertNull(state.attacker.rankedEvSpread);
        assertFalse(state.attacker.evsManuallyEdited);
    }

    private static PokemonSet unknown() {
        PokemonSet pokemon = new PokemonSet(TropimonDex.species("abomasnow"));
        pokemon.battleId = "opponent";
        pokemon.statsKnown = false;
        pokemon.itemKnown = false;
        pokemon.abilityKnown = false;
        pokemon.natureKnown = false;
        pokemon.movesKnown = false;
        return pokemon;
    }

    private static TropimonRankedUsageService.RankedProfile profile() {
        return TropimonRankedUsageService.profileFromJson("abomasnow", """
                {"spreads":{"252 HP / 252 Def / 6 SpD": 40}}
                """);
    }
}
