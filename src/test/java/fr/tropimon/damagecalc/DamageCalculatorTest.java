package fr.tropimon.damagecalc;

import com.google.gson.JsonParser;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DamageCalculatorTest {
    @Test
    void randomBattleOwnedSetIsMatchedFromTheBattleActorByUuid() {
        SpeciesData species = species("randommon", "Randommon", PokeType.DRAGON, PokeType.NONE,
                90, 100, 90, 100, 90, 100, false);
        PokemonSet first = new PokemonSet(species);
        first.battleId = "first-random-uuid";
        first.evs.put(Stat.ATK, 252);
        first.ivs.put(Stat.SPE, 17);
        first.setMove(0, move("dragonclaw", "Dragon Claw", PokeType.DRAGON,
                DamageCategory.PHYSICAL, 80, false));
        PokemonSet second = new PokemonSet(species);
        second.battleId = "second-random-uuid";
        second.evs.put(Stat.SPA, 252);

        PokemonSet matched = CobblemonBattleDataProvider.matchingOwnedPokemon(
                List.of(first, second), "first-random-uuid", species);

        assertNotNull(matched);
        assertEquals(252, matched.evs.get(Stat.ATK));
        assertEquals(17, matched.ivs.get(Stat.SPE));
        assertEquals("dragonclaw", matched.moveAt(0).id());
        assertTrue(matched != first);
    }

    @Test
    void randomBattleSpeciesFallbackRefusesAnAmbiguousMirrorTeam() {
        SpeciesData species = species("ditto", "Ditto", PokeType.NORMAL, PokeType.NONE,
                48, 48, 48, 48, 48, 48, false);
        PokemonSet first = new PokemonSet(species);
        PokemonSet second = new PokemonSet(species);

        assertEquals(null, CobblemonBattleDataProvider.matchingOwnedPokemon(
                List.of(first, second), "missing-uuid", species));
    }

    @Test
    void inventoryTeamPreviewRecognizesBothSoloAndDoublesColumns() {
        assertTrue(CobblemonBattleDataProvider.isPlayerPreviewSlot(0, 54, false));
        assertTrue(CobblemonBattleDataProvider.isPlayerPreviewSlot(45, 54, false));
        assertTrue(CobblemonBattleDataProvider.isOpponentPreviewSlot(8, 54, false));
        assertTrue(CobblemonBattleDataProvider.isOpponentPreviewSlot(53, 54, false));
        assertFalse(CobblemonBattleDataProvider.isPlayerPreviewSlot(54, 54, false));

        assertTrue(CobblemonBattleDataProvider.isPlayerPreviewSlot(19, 54, true));
        assertTrue(CobblemonBattleDataProvider.isPlayerPreviewSlot(38, 54, true));
        assertTrue(CobblemonBattleDataProvider.isOpponentPreviewSlot(24, 54, true));
        assertTrue(CobblemonBattleDataProvider.isOpponentPreviewSlot(43, 54, true));
        assertFalse(CobblemonBattleDataProvider.isOpponentPreviewSlot(8, 54, true));
    }

    @Test
    void randomBattleCopiesPlayerEvsAndForcesSeriousOpponentNature() {
        PokemonSet player = new PokemonSet(species("randomally", "Random Ally", PokeType.FIRE,
                PokeType.NONE, 80, 100, 80, 100, 80, 100, false));
        player.evs.put(Stat.HP, 84);
        player.evs.put(Stat.ATK, 84);
        player.evs.put(Stat.SPE, 84);
        PokemonSet opponent = new PokemonSet(species("randomenemy", "Random Enemy", PokeType.WATER,
                PokeType.NONE, 80, 100, 80, 100, 80, 100, false));
        opponent.evs.put(Stat.HP, 252);
        opponent.nature = new NatureData("timid", "Timid", Stat.SPE, Stat.ATK);
        opponent.statsKnown = false;
        opponent.natureKnown = false;

        CobblemonBattleDataProvider.applyRandomBattleOpponentRules(player, opponent);

        assertEquals(player.evs, opponent.evs);
        assertEquals("serious", opponent.nature.id());
        assertTrue(opponent.statsKnown);
        assertTrue(opponent.natureKnown);
        assertTrue(CobblemonBattleDataProvider.randomFormatLabel("gen9RandomBattle"));
        assertFalse(CobblemonBattleDataProvider.randomFormatLabel("gen9singles", "standard"));
        assertTrue(CobblemonBattleDataProvider.isRandomBattleQueueMessage(
                "Tu as rejoins la file: Random Battle!"));
        assertFalse(CobblemonBattleDataProvider.isRandomBattleQueueMessage(
                "Tu as rejoins la file: Ranked Singles!"));
    }

    @Test
    void generatedRandomTeamDoesNotRequireUniformEvs() {
        assertTrue(CobblemonBattleDataProvider.isGeneratedRandomTeam(6, false));
        assertFalse(CobblemonBattleDataProvider.isGeneratedRandomTeam(6, true));
        assertFalse(CobblemonBattleDataProvider.isGeneratedRandomTeam(5, false));
    }

    @Test
    void localRegionalFormIsNotDowngradedByBaseBattleSpecies() {
        SpeciesData baseStats = species("ninetales", "Ninetales", PokeType.FIRE, PokeType.NONE,
                73, 76, 75, 81, 100, 100, false);
        SpeciesData alolaStats = species("ninetalesalola", "Ninetales Alola", PokeType.ICE, PokeType.FAIRY,
                73, 67, 75, 81, 100, 109, false);
        SpeciesData base = new SpeciesData("ninetales", "Ninetales", PokeType.FIRE, PokeType.NONE,
                baseStats.baseStats(), false, "", "ninetales", List.of());
        SpeciesData alola = new SpeciesData("ninetalesalola", "Ninetales Alola", PokeType.ICE, PokeType.FAIRY,
                alolaStats.baseStats(), false, "", "ninetales", List.of("alolan"));

        assertSame(alola, CobblemonBattleDataProvider.preferSpecificForm(alola, base));
        assertSame(alola, CobblemonBattleDataProvider.preferSpecificForm(base, alola));
    }

    @Test
    void activeBaseSpeciesReusesUniqueRegionalPreviewEntry() {
        SpeciesData baseStats = species("samurott", "Samurott", PokeType.WATER, PokeType.NONE,
                95, 100, 85, 108, 70, 70, false);
        SpeciesData hisuiStats = species("samurotthisui", "Samurott Hisui", PokeType.WATER, PokeType.DARK,
                90, 108, 80, 100, 65, 85, false);
        SpeciesData base = new SpeciesData("samurott", "Samurott", PokeType.WATER, PokeType.NONE,
                baseStats.baseStats(), false, "", "samurott", List.of());
        SpeciesData hisui = new SpeciesData("samurotthisui", "Samurott Hisui", PokeType.WATER, PokeType.DARK,
                hisuiStats.baseStats(), false, "", "samurott", List.of("hisuian"));
        PokemonSet preview = new PokemonSet(hisui);
        preview.battleId = "";
        preview.battleName = "Samurott Hisui";
        PokemonSet active = new PokemonSet(base);
        active.battleId = "battle-samurott";
        active.battleName = "Samurott";
        LinkedHashMap<String, PokemonSet> roster = new LinkedHashMap<>();
        roster.put("samurotthisui:samurotthisui", preview);

        String matched = CobblemonBattleDataProvider.matchingAnonymousOpponentKey(roster, active);

        assertEquals("samurotthisui:samurotthisui", matched);
        assertSame(hisui, CobblemonBattleDataProvider.preferSpecificForm(preview.species, active.species));
    }

    @Test
    void ambiguousRegionalPreviewEntriesAreNotMergedByBaseSpecies() {
        SpeciesData baseStats = species("samurott", "Samurott", PokeType.WATER, PokeType.NONE,
                95, 100, 85, 108, 70, 70, false);
        SpeciesData base = new SpeciesData("samurott", "Samurott", PokeType.WATER, PokeType.NONE,
                baseStats.baseStats(), false, "", "samurott", List.of());
        SpeciesData hisui = new SpeciesData("samurotthisui", "Samurott Hisui", PokeType.WATER, PokeType.DARK,
                baseStats.baseStats(), false, "", "samurott", List.of("hisuian"));
        SpeciesData unresolved = new SpeciesData("samurottbattle", "Samurott", PokeType.WATER, PokeType.NONE,
                baseStats.baseStats(), false, "", "samurott", List.of());
        PokemonSet active = new PokemonSet(unresolved);
        active.battleId = "battle-samurott";
        LinkedHashMap<String, PokemonSet> roster = new LinkedHashMap<>();
        roster.put("base", new PokemonSet(base));
        roster.put("hisui", new PokemonSet(hisui));

        assertEquals(null, CobblemonBattleDataProvider.matchingAnonymousOpponentKey(roster, active));
    }

    @Test
    void teamPreviewKeepsIdentityButHidesPrivateOpponentData() {
        PokemonSet source = new PokemonSet(species("preview", "Preview", PokeType.WATER, PokeType.NONE,
                90, 80, 90, 100, 110, 70, false));
        source.battleId = "preview-uuid";
        source.level = 50;
        source.item = "Leftovers";
        source.ability = "Regenerator";
        source.nature = new NatureData("bold", "Bold", Stat.DEF, Stat.ATK);
        source.evs.put(Stat.HP, 252);
        source.ivs.put(Stat.ATK, 12);
        source.setMove(0, move("scald", "Scald", PokeType.WATER, DamageCategory.SPECIAL, 80, false));
        source.currentHp = 180;
        source.observedMaxHp = 200;

        PokemonSet preview = CobblemonBattleDataProvider.hideOpponentPrivateData(source);

        assertEquals("preview-uuid", preview.battleId);
        assertEquals(50, preview.level);
        assertEquals("preview", preview.species.id());
        assertFalse(preview.itemKnown);
        assertFalse(preview.abilityKnown);
        assertFalse(preview.natureKnown);
        assertFalse(preview.statsKnown);
        assertFalse(preview.movesKnown);
        assertEquals("None", preview.item);
        assertEquals(-1, preview.currentHp);
        assertEquals(-1, preview.observedMaxHp);
        assertEquals(0, preview.evs.get(Stat.HP));
        assertEquals(31, preview.ivs.get(Stat.ATK));
        assertEquals(null, preview.moveAt(0));
    }

    @Test
    void opponentBuildIsFilledFromBattleRevelations() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        Object battle = new Object();
        try {
            state.attacker = new PokemonSet(species("ally", "Ally", PokeType.NORMAL, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.defender = new PokemonSet(species("enemy", "Enemy", PokeType.ROCK, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.defender.battleName = "Enemy";
            state.defender.itemKnown = false;
            state.defender.abilityKnown = false;
            state.defender.movesKnown = false;
            state.defender.moves.clear();
            while (state.defender.moves.size() < 4) state.defender.moves.add(null);
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.used_move",
                    Text.literal("The wild Enemy"), Text.translatable("cobblemon.move.rockslide")));
            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.ability.sturdy",
                    Text.literal("Enemy")));
            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.enditem.generic",
                    Text.literal("Enemy"), Text.translatable("item.cobblemon.leftovers")));

            assertEquals("rockslide", state.defender.lastMoveId);
            assertEquals("Sturdy", state.defender.ability);
            assertTrue(state.defender.abilityKnown);
            assertEquals("None", state.defender.item);
            assertTrue(state.defender.itemKnown);
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void observedCobblemonMaxHpOverridesAnImperfectLocalRecalculation() {
        PokemonSet pokemon = new PokemonSet(species("goodra", "Goodra", PokeType.DRAGON, PokeType.NONE,
                90, 100, 70, 110, 150, 80, false));
        int calculated = pokemon.calculatedMaxHp();

        pokemon.currentHp = 361;
        pokemon.observedMaxHp = 361;

        assertEquals(361, pokemon.maxHp());
        assertEquals(361, pokemon.visibleHp());
        assertTrue(calculated != 361);
        assertEquals(361, pokemon.copy().maxHp());
    }

    @Test
    void manualSpeciesChangeCanClearEveryLiveBattleValue() {
        PokemonSet pokemon = new PokemonSet(species("old", "Old", PokeType.NORMAL, PokeType.NONE,
                80, 80, 80, 80, 80, 80, false));
        pokemon.battleId = "battle-id";
        pokemon.battleName = "Nickname";
        pokemon.currentHp = 12;
        pokemon.observedMaxHp = 200;
        pokemon.status = StatusCondition.BURN;
        pokemon.terastallized = true;
        pokemon.teraType = PokeType.FIRE;
        pokemon.boosts.put(Stat.ATK, 4);
        pokemon.battleHistoryKnown = true;
        pokemon.timesHit = 5;
        pokemon.lastMoveId = "ragefist";

        pokemon.clearBattleContext();

        assertEquals("", pokemon.battleId);
        assertEquals("", pokemon.battleName);
        assertEquals(-1, pokemon.currentHp);
        assertEquals(-1, pokemon.observedMaxHp);
        assertEquals(StatusCondition.NONE, pokemon.status);
        assertFalse(pokemon.terastallized);
        assertEquals(PokeType.NONE, pokemon.teraType);
        assertEquals(0, pokemon.boosts.get(Stat.ATK));
        assertFalse(pokemon.battleHistoryKnown);
        assertEquals(0, pokemon.timesHit);
        assertEquals("", pokemon.lastMoveId);
    }

    @Test
    void ownedPokemonTranslationDisambiguatesMirrorMatchMoves() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        Object battle = new Object();
        try {
            SpeciesData mirror = species("mirror", "Mirror", PokeType.NORMAL, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false);
            state.attacker = new PokemonSet(mirror);
            state.defender = new PokemonSet(mirror);
            state.attacker.moves.clear();
            state.defender.moves.clear();
            state.attacker.movesKnown = false;
            state.defender.movesKnown = false;
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.used_move",
                    Text.translatable("cobblemon.battle.owned_pokemon", Text.literal("Opponent"), Text.literal("Mirror")),
                    Text.translatable("cobblemon.move.rockslide")));

            assertEquals("rockslide", state.defender.lastMoveId);
            assertEquals("", state.attacker.lastMoveId);
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void dragonDanceMessagesSynchronizeAttackSpeedAndDamage() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        FieldState previousField = state.field;
        Object battle = new Object();
        try {
            state.attacker = new PokemonSet(species("dragonite", "Dragonite", PokeType.DRAGON, PokeType.FLYING,
                    91, 134, 95, 100, 100, 80, false));
            state.defender = new PokemonSet(species("target", "Target", PokeType.NORMAL, PokeType.NONE,
                    100, 80, 100, 80, 100, 80, false));
            state.attacker.battleName = "Dragonite";
            state.defender.battleName = "Target";
            state.field = new FieldState();
            MoveData dragonClaw = move("dragonclaw", "Dragon Claw", PokeType.DRAGON,
                    DamageCategory.PHYSICAL, 80, false);
            int baseAttack = DamageCalculator.displayedStat(state.attacker, Stat.ATK,
                    state.field, state.field.attackerSide);
            int baseSpeed = DamageCalculator.displayedStat(state.attacker, Stat.SPE,
                    state.field, state.field.attackerSide);
            DamageResult baseDamage = DamageCalculator.calculate(state.attacker, state.defender, dragonClaw,
                    state.field, state.field.attackerSide, state.field.defenderSide);
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.boost.slight",
                    Text.literal("Dragonite"), Text.translatable("cobblemon.stat.attack.name")));
            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.boost.slight",
                    Text.literal("Dragonite"), Text.translatable("cobblemon.stat.speed.name")));

            assertEquals(1, state.attacker.boosts.get(Stat.ATK));
            assertEquals(1, state.attacker.boosts.get(Stat.SPE));
            assertTrue(DamageCalculator.displayedStat(state.attacker, Stat.ATK,
                    state.field, state.field.attackerSide) > baseAttack);
            assertTrue(DamageCalculator.displayedStat(state.attacker, Stat.SPE,
                    state.field, state.field.attackerSide) > baseSpeed);
            DamageResult boostedDamage = DamageCalculator.calculate(state.attacker, state.defender, dragonClaw,
                    state.field, state.field.attackerSide, state.field.defenderSide);
            assertTrue(boostedDamage.maxDamage() > baseDamage.maxDamage());

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.clearboost",
                    Text.literal("Dragonite")));
            assertEquals(0, state.attacker.boosts.get(Stat.ATK));
            assertEquals(0, state.attacker.boosts.get(Stat.SPE));
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            state.field = previousField;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void trainerPossessiveNicknameIsRecognizedAsTheOpponent() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        Object battle = new Object();
        try {
            state.attacker = new PokemonSet(species("goodra", "Goodra", PokeType.DRAGON, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.defender = new PokemonSet(species("dragapult", "Dragapult", PokeType.DRAGON, PokeType.GHOST,
                    80, 80, 80, 80, 80, 80, false));
            state.defender.battleName = "RAFALE F4";
            state.defender.moves.clear();
            state.defender.movesKnown = false;
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.used_move",
                    Text.literal("capriseum001's RAFALE F4"), Text.translatable("cobblemon.move.rockslide")));

            assertEquals("rockslide", state.defender.lastMoveId);
            assertEquals("", state.attacker.lastMoveId);
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void blizzardAgainstAbomasnowMatchesBaselineFixture() {
        PokemonSet attacker = new PokemonSet(species("abomasnow", "Abomasnow", PokeType.GRASS, PokeType.ICE, 90, 92, 75, 92, 85, 60, false));
        PokemonSet defender = new PokemonSet(species("abomasnow", "Abomasnow", PokeType.GRASS, PokeType.ICE, 90, 92, 75, 92, 85, 60, false));
        attacker.level = 100;
        defender.level = 100;
        attacker.evs.put(Stat.SPA, 252);

        DamageResult result = DamageCalculator.calculate(attacker, defender, move("blizzard", "Blizzard", PokeType.ICE, DamageCategory.SPECIAL, 110, true), new FieldState());

        assertTrue(Math.abs(result.minDamage() - 162) <= 1);
        assertEquals(192, result.maxDamage());
        assertEquals("guaranteed 2HKO", result.koChance());
        assertEquals(16, result.rolls().size());
        assertTrue(result.notes().contains("STAB"));
    }

    @Test
    void burnHalvesPhysicalDamageUnlessGutsApplies() {
        PokemonSet attacker = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData move = move("earthquake", "Earthquake", PokeType.GROUND, DamageCategory.PHYSICAL, 100, true);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        int displayedAttack = DamageCalculator.displayedStat(attacker, Stat.ATK, new FieldState(), new SideConditions());
        attacker.status = StatusCondition.BURN;
        DamageResult burned = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        int displayedBurnedAttack = DamageCalculator.displayedStat(attacker, Stat.ATK, new FieldState(), new SideConditions());
        attacker.ability = "Guts";
        DamageResult guts = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        int displayedGutsAttack = DamageCalculator.displayedStat(attacker, Stat.ATK, new FieldState(), new SideConditions());

        assertTrue(burned.maxDamage() < normal.maxDamage());
        assertTrue(guts.maxDamage() > normal.maxDamage());
        assertEquals(displayedAttack / 2, displayedBurnedAttack);
        assertEquals(displayedAttack, displayedGutsAttack);
    }

    @Test
    void screenAndInfiltratorInteractionIsApplied() {
        PokemonSet attacker = new PokemonSet(species("gengar", "Gengar", PokeType.GHOST, PokeType.POISON, 60, 65, 60, 130, 75, 110, false));
        PokemonSet defender = new PokemonSet(species("clefable", "Clefable", PokeType.FAIRY, PokeType.NONE, 95, 70, 73, 95, 90, 60, false));
        FieldState field = new FieldState();
        field.lightScreen = true;

        DamageResult screened = DamageCalculator.calculate(attacker, defender, move("shadowball", "Shadow Ball", PokeType.GHOST, DamageCategory.SPECIAL, 80, false), field);
        attacker.ability = "Infiltrator";
        DamageResult infiltrator = DamageCalculator.calculate(attacker, defender, move("shadowball", "Shadow Ball", PokeType.GHOST, DamageCategory.SPECIAL, 80, false), field);

        assertTrue(infiltrator.maxDamage() > screened.maxDamage());
        assertTrue(screened.notes().contains("Light Screen"));
    }

    @Test
    void sideScreensOnlyApplyToTheDefendingSide() {
        PokemonSet attacker = new PokemonSet(species("gengar", "Gengar", PokeType.GHOST, PokeType.POISON, 60, 65, 60, 130, 75, 110, false));
        PokemonSet defender = new PokemonSet(species("clefable", "Clefable", PokeType.FAIRY, PokeType.NONE, 95, 70, 73, 95, 90, 60, false));
        MoveData move = move("shadowball", "Shadow Ball", PokeType.GHOST, DamageCategory.SPECIAL, 80, false);
        FieldState field = new FieldState();

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, field, field.defenderSide);
        field.attackerSide.lightScreen = true;
        DamageResult wrongSide = DamageCalculator.calculate(attacker, defender, move, field, field.defenderSide);
        field.defenderSide.lightScreen = true;
        DamageResult rightSide = DamageCalculator.calculate(attacker, defender, move, field, field.defenderSide);

        assertEquals(normal.maxDamage(), wrongSide.maxDamage());
        assertTrue(rightSide.maxDamage() < normal.maxDamage());
    }

    @Test
    void snowBoostsIceDefenseAgainstPhysicalMoves() {
        PokemonSet attacker = new PokemonSet(species("lucario", "Lucario", PokeType.FIGHTING, PokeType.STEEL, 70, 110, 70, 115, 70, 90, false));
        PokemonSet defender = new PokemonSet(species("abomasnow", "Abomasnow", PokeType.GRASS, PokeType.ICE, 90, 92, 75, 92, 85, 60, false));
        FieldState snow = new FieldState();
        snow.weather = Weather.SNOW;

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move("closecombat", "Close Combat", PokeType.FIGHTING, DamageCategory.PHYSICAL, 120, false), new FieldState());
        DamageResult snowy = DamageCalculator.calculate(attacker, defender, move("closecombat", "Close Combat", PokeType.FIGHTING, DamageCategory.PHYSICAL, 120, false), snow);

        assertTrue(snowy.maxDamage() < normal.maxDamage());
        assertTrue(snowy.notes().contains("Snow ice Defense x1.5"));
    }

    @Test
    void tailwindAffectsMoveOrderAndHelpingHandOnlyAppliesInDoubles() {
        PokemonSet attacker = new PokemonSet(species("dracovish", "Dracovish", PokeType.WATER, PokeType.DRAGON, 90, 90, 100, 70, 80, 75, false));
        PokemonSet defender = new PokemonSet(species("dragapult", "Dragapult", PokeType.DRAGON, PokeType.GHOST, 88, 120, 75, 100, 75, 142, false));
        MoveData fishiousRend = move("fishiousrend", "Fishious Rend", PokeType.WATER, DamageCategory.PHYSICAL, 85, false);
        FieldState field = new FieldState();

        DamageResult normal = DamageCalculator.calculate(attacker, defender, fishiousRend, field, field.attackerSide, field.defenderSide);
        field.attackerSide.tailwind = true;
        DamageResult tailwind = DamageCalculator.calculate(attacker, defender, fishiousRend, field, field.attackerSide, field.defenderSide);
        field.helpingHand = true;
        DamageResult soloHelpingHand = DamageCalculator.calculate(attacker, defender, fishiousRend, field, field.attackerSide, field.defenderSide);
        field.doubles = true;
        DamageResult doublesHelpingHand = DamageCalculator.calculate(attacker, defender, fishiousRend, field, field.attackerSide, field.defenderSide);

        assertTrue(tailwind.maxDamage() > normal.maxDamage());
        assertTrue(tailwind.notes().contains("Fishious Rend first x2"));
        assertEquals(tailwind.maxDamage(), soloHelpingHand.maxDamage());
        assertTrue(doublesHelpingHand.maxDamage() > soloHelpingHand.maxDamage());
        assertTrue(doublesHelpingHand.notes().contains("Helping Hand"));
    }

    @Test
    void ruinAbilitiesModifyRelevantStats() {
        PokemonSet physicalAttacker = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        PokemonSet physicalDefender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        PokemonSet specialAttacker = new PokemonSet(species("gengar", "Gengar", PokeType.GHOST, PokeType.POISON, 60, 65, 60, 130, 75, 110, false));
        PokemonSet specialDefender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));

        DamageResult baselinePhysical = DamageCalculator.calculate(physicalAttacker, physicalDefender, move("earthquake", "Earthquake", PokeType.GROUND, DamageCategory.PHYSICAL, 100, true), new FieldState());
        physicalAttacker.ability = "Sword of Ruin";
        DamageResult sword = DamageCalculator.calculate(physicalAttacker, physicalDefender, move("earthquake", "Earthquake", PokeType.GROUND, DamageCategory.PHYSICAL, 100, true), new FieldState());

        DamageResult baselineSpecial = DamageCalculator.calculate(specialAttacker, specialDefender, move("thunderbolt", "Thunderbolt", PokeType.ELECTRIC, DamageCategory.SPECIAL, 90, false), new FieldState());
        specialAttacker.ability = "Beads of Ruin";
        DamageResult beads = DamageCalculator.calculate(specialAttacker, specialDefender, move("thunderbolt", "Thunderbolt", PokeType.ELECTRIC, DamageCategory.SPECIAL, 90, false), new FieldState());

        assertTrue(sword.maxDamage() > baselinePhysical.maxDamage());
        assertTrue(beads.maxDamage() > baselineSpecial.maxDamage());
    }

    @Test
    void strongJawBoostsFishiousRendAndFastFishiousRendDoublesPower() {
        PokemonSet dracovish = new PokemonSet(species("dracovish", "Dracovish", PokeType.WATER, PokeType.DRAGON, 90, 90, 100, 70, 80, 75, false));
        PokemonSet slowTarget = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        PokemonSet fastTarget = new PokemonSet(species("dragapult", "Dragapult", PokeType.DRAGON, PokeType.GHOST, 88, 120, 75, 100, 75, 142, false));
        MoveData fishiousRend = move("fishiousrend", "Fishious Rend", PokeType.WATER, DamageCategory.PHYSICAL, 85, false);

        DamageResult noDouble = DamageCalculator.calculate(dracovish, fastTarget, fishiousRend, new FieldState());
        DamageResult doubled = DamageCalculator.calculate(dracovish, slowTarget, fishiousRend, new FieldState());
        dracovish.ability = "Strong Jaw";
        DamageResult strongJaw = DamageCalculator.calculate(dracovish, slowTarget, fishiousRend, new FieldState());

        assertTrue(doubled.maxDamage() > noDouble.maxDamage());
        assertTrue(strongJaw.maxDamage() > doubled.maxDamage());
        assertTrue(doubled.notes().contains("Fishious Rend first x2"));
        assertTrue(strongJaw.notes().contains("Strong Jaw"));
    }

    @Test
    void weatherSpeedAbilitiesAreUsedForFishiousRendOrder() {
        PokemonSet barraskewda = new PokemonSet(species("barraskewda", "Barraskewda", PokeType.WATER, PokeType.NONE, 61, 123, 60, 60, 50, 136, false));
        PokemonSet fasterTarget = new PokemonSet(species("regieleki", "Regieleki", PokeType.ELECTRIC, PokeType.NONE, 80, 100, 50, 100, 50, 200, false));
        MoveData fishiousRend = move("fishiousrend", "Fishious Rend", PokeType.WATER, DamageCategory.PHYSICAL, 85, false);

        DamageResult baseline = DamageCalculator.calculate(barraskewda, fasterTarget, fishiousRend, new FieldState());
        barraskewda.ability = "Swift Swim";
        FieldState rain = new FieldState();
        rain.weather = Weather.RAIN;
        DamageResult swiftSwim = DamageCalculator.calculate(barraskewda, fasterTarget, fishiousRend, rain);

        assertTrue(swiftSwim.maxDamage() > baseline.maxDamage());
        assertTrue(swiftSwim.notes().contains("Fishious Rend first x2"));
    }

    @Test
    void weatherAbilitiesUpdateFieldAwareStats() {
        PokemonSet kingdra = new PokemonSet(species("kingdra", "Kingdra", PokeType.WATER, PokeType.DRAGON, 75, 95, 95, 95, 95, 85, false));
        kingdra.ability = "Swift Swim";
        FieldState rain = new FieldState();
        rain.weather = Weather.RAIN;

        assertTrue(DamageCalculator.stat(kingdra, Stat.SPE, false, rain) > DamageCalculator.stat(kingdra, Stat.SPE, false));

        PokemonSet charizard = new PokemonSet(species("charizard", "Charizard", PokeType.FIRE, PokeType.FLYING, 78, 84, 78, 109, 85, 100, false));
        charizard.ability = "Solar Power";
        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;

        assertTrue(DamageCalculator.stat(charizard, Stat.SPA, false, sun) > DamageCalculator.stat(charizard, Stat.SPA, false));
    }

    @Test
    void weatherBallChangesTypeAndPower() {
        PokemonSet attacker = new PokemonSet(species("castform", "Castform", PokeType.NORMAL, PokeType.NONE, 70, 70, 70, 70, 70, 70, false));
        PokemonSet defender = new PokemonSet(species("abomasnow", "Abomasnow", PokeType.GRASS, PokeType.ICE, 90, 92, 75, 92, 85, 60, false));
        MoveData weatherBall = move("weatherball", "Weather Ball", PokeType.NORMAL, DamageCategory.SPECIAL, 50, false);
        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;

        DamageResult normal = DamageCalculator.calculate(attacker, defender, weatherBall, new FieldState());
        DamageResult sunny = DamageCalculator.calculate(attacker, defender, weatherBall, sun);

        assertTrue(sunny.maxDamage() > normal.maxDamage());
        assertTrue(sunny.notes().contains("Weather Ball Fire"));
        assertTrue(sunny.notes().contains("Weather Ball power x2"));
    }

    @Test
    void weatherSpecificMovesUseWeatherPowerRules() {
        PokemonSet attacker = new PokemonSet(species("walkingwake", "Walking Wake", PokeType.WATER, PokeType.DRAGON, 99, 83, 91, 125, 83, 109, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;

        MoveData hydroSteam = move("hydrosteam", "Hydro Steam", PokeType.WATER, DamageCategory.SPECIAL, 80, false);
        DamageResult normalHydroSteam = DamageCalculator.calculate(attacker, defender, hydroSteam, new FieldState());
        DamageResult sunHydroSteam = DamageCalculator.calculate(attacker, defender, hydroSteam, sun);

        assertTrue(sunHydroSteam.maxDamage() > normalHydroSteam.maxDamage());
        assertTrue(sunHydroSteam.notes().contains("Sun Hydro Steam x1.5"));

        MoveData solarBeam = move("solarbeam", "Solar Beam", PokeType.GRASS, DamageCategory.SPECIAL, 120, false);
        FieldState rain = new FieldState();
        rain.weather = Weather.RAIN;
        DamageResult normalSolarBeam = DamageCalculator.calculate(attacker, defender, solarBeam, new FieldState());
        DamageResult rainSolarBeam = DamageCalculator.calculate(attacker, defender, solarBeam, rain);

        assertTrue(rainSolarBeam.maxDamage() < normalSolarBeam.maxDamage());
        assertTrue(rainSolarBeam.notes().contains("Solar Beam weather x0.5"));
    }

    @Test
    void terrainSpecificMovesUseTerrainPowerAndTypeRules() {
        PokemonSet attacker = new PokemonSet(species("raichu", "Raichu", PokeType.ELECTRIC, PokeType.NONE, 60, 90, 55, 90, 80, 110, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        FieldState electric = new FieldState();
        electric.terrain = Terrain.ELECTRIC;

        DamageResult normalTerrainPulse = DamageCalculator.calculate(attacker, defender,
                move("terrainpulse", "Terrain Pulse", PokeType.NORMAL, DamageCategory.SPECIAL, 50, false), new FieldState());
        DamageResult electricTerrainPulse = DamageCalculator.calculate(attacker, defender,
                move("terrainpulse", "Terrain Pulse", PokeType.NORMAL, DamageCategory.SPECIAL, 50, false), electric);
        DamageResult risingVoltage = DamageCalculator.calculate(attacker, defender,
                move("risingvoltage", "Rising Voltage", PokeType.ELECTRIC, DamageCategory.SPECIAL, 70, false), electric);

        assertTrue(electricTerrainPulse.maxDamage() > normalTerrainPulse.maxDamage());
        assertTrue(electricTerrainPulse.notes().contains("Terrain Pulse Electric"));
        assertTrue(electricTerrainPulse.notes().contains("Terrain Pulse power x2"));
        assertTrue(risingVoltage.notes().contains("Rising Voltage terrain x2"));
    }

    @Test
    void grassyAndMistyTerrainApplyDamageRules() {
        PokemonSet attacker = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData earthquake = move("earthquake", "Earthquake", PokeType.GROUND, DamageCategory.PHYSICAL, 100, true);
        FieldState grassy = new FieldState();
        grassy.terrain = Terrain.GRASSY;

        DamageResult normalEarthquake = DamageCalculator.calculate(attacker, defender, earthquake, new FieldState());
        DamageResult grassyEarthquake = DamageCalculator.calculate(attacker, defender, earthquake, grassy);

        assertTrue(grassyEarthquake.maxDamage() < normalEarthquake.maxDamage());
        assertTrue(grassyEarthquake.notes().contains("Grassy Terrain halves Earthquake"));

        MoveData dragonPulse = move("dragonpulse", "Dragon Pulse", PokeType.DRAGON, DamageCategory.SPECIAL, 85, false);
        FieldState misty = new FieldState();
        misty.terrain = Terrain.MISTY;
        DamageResult normalDragon = DamageCalculator.calculate(attacker, defender, dragonPulse, new FieldState());
        DamageResult mistyDragon = DamageCalculator.calculate(attacker, defender, dragonPulse, misty);

        assertTrue(mistyDragon.maxDamage() < normalDragon.maxDamage());
        assertTrue(mistyDragon.notes().contains("Misty Terrain"));
    }

    @Test
    void typeChangingAbilitiesChangeTypeAndBoostPower() {
        PokemonSet sylveon = new PokemonSet(species("sylveon", "Sylveon", PokeType.FAIRY, PokeType.NONE, 95, 65, 65, 110, 130, 60, false));
        PokemonSet dragonite = new PokemonSet(species("dragonite", "Dragonite", PokeType.DRAGON, PokeType.FLYING, 91, 134, 95, 100, 100, 80, false));
        MoveData hyperVoice = move("hypervoice", "Hyper Voice", PokeType.NORMAL, DamageCategory.SPECIAL, 90, false);

        DamageResult normal = DamageCalculator.calculate(sylveon, dragonite, hyperVoice, new FieldState());
        sylveon.ability = "Pixilate";
        DamageResult pixilate = DamageCalculator.calculate(sylveon, dragonite, hyperVoice, new FieldState());

        assertTrue(pixilate.maxDamage() > normal.maxDamage());
        assertTrue(pixilate.notes().contains("Pixilate"));
        assertTrue(pixilate.notes().contains("type ability x1.2"));
        assertTrue(pixilate.notes().contains("super effective x2"));
    }

    @Test
    void statBoostingAbilitiesAffectDamage() {
        PokemonSet charizard = new PokemonSet(species("charizard", "Charizard", PokeType.FIRE, PokeType.FLYING, 78, 84, 78, 109, 85, 100, false));
        PokemonSet snorlax = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData flamethrower = move("flamethrower", "Flamethrower", PokeType.FIRE, DamageCategory.SPECIAL, 90, false);
        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;

        DamageResult normal = DamageCalculator.calculate(charizard, snorlax, flamethrower, sun);
        charizard.ability = "Solar Power";
        DamageResult solarPower = DamageCalculator.calculate(charizard, snorlax, flamethrower, sun);

        assertTrue(solarPower.maxDamage() > normal.maxDamage());
        assertTrue(solarPower.notes().contains("Solar Power"));
    }

    @Test
    void defensiveAbilitiesReduceDamage() {
        PokemonSet attacker = new PokemonSet(species("lucario", "Lucario", PokeType.FIGHTING, PokeType.STEEL, 70, 110, 70, 115, 70, 90, false));
        PokemonSet defender = new PokemonSet(species("furfrou", "Furfrou", PokeType.NORMAL, PokeType.NONE, 75, 80, 60, 65, 90, 102, false));
        MoveData closeCombat = move("closecombat", "Close Combat", PokeType.FIGHTING, DamageCategory.PHYSICAL, 120, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, closeCombat, new FieldState());
        defender.ability = "Fur Coat";
        DamageResult furCoat = DamageCalculator.calculate(attacker, defender, closeCombat, new FieldState());

        assertTrue(furCoat.maxDamage() < normal.maxDamage());
        assertTrue(furCoat.notes().contains("Fur Coat"));
    }

    @Test
    void statBoostingItemsUpdateDisplayedStats() {
        PokemonSet garchomp = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        int baseAttack = DamageCalculator.stat(garchomp, Stat.ATK, false);
        garchomp.item = "Choice Band";

        assertTrue(DamageCalculator.stat(garchomp, Stat.ATK, false) > baseAttack);

        PokemonSet pikachu = new PokemonSet(species("pikachu", "Pikachu", PokeType.ELECTRIC, PokeType.NONE, 35, 55, 40, 50, 50, 90, false));
        int baseSpecialAttack = DamageCalculator.stat(pikachu, Stat.SPA, false);
        pikachu.item = "Light Ball";

        assertTrue(DamageCalculator.stat(pikachu, Stat.ATK, false) > DamageCalculator.stat(new PokemonSet(pikachu.species), Stat.ATK, false));
        assertTrue(DamageCalculator.stat(pikachu, Stat.SPA, false) > baseSpecialAttack);
    }

    @Test
    void damageBoostingItemsAffectDamage() {
        PokemonSet charizard = new PokemonSet(species("charizard", "Charizard", PokeType.FIRE, PokeType.FLYING, 78, 84, 78, 109, 85, 100, false));
        PokemonSet snorlax = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData flamethrower = move("flamethrower", "Flamethrower", PokeType.FIRE, DamageCategory.SPECIAL, 90, false);

        DamageResult normal = DamageCalculator.calculate(charizard, snorlax, flamethrower, new FieldState());
        charizard.item = "Charcoal";
        DamageResult boosted = DamageCalculator.calculate(charizard, snorlax, flamethrower, new FieldState());

        assertTrue(boosted.maxDamage() > normal.maxDamage());
        assertTrue(boosted.notes().contains("Charcoal"));
    }

    @Test
    void categoryAndPunchItemsAffectDamage() {
        PokemonSet attacker = new PokemonSet(species("lucario", "Lucario", PokeType.FIGHTING, PokeType.STEEL, 70, 110, 70, 115, 70, 90, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData icePunch = move("icepunch", "Ice Punch", PokeType.ICE, DamageCategory.PHYSICAL, 75, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, icePunch, new FieldState());
        attacker.item = "Muscle Band";
        DamageResult muscleBand = DamageCalculator.calculate(attacker, defender, icePunch, new FieldState());
        attacker.item = "Punching Glove";
        DamageResult punchingGlove = DamageCalculator.calculate(attacker, defender, icePunch, new FieldState());

        assertTrue(muscleBand.maxDamage() > normal.maxDamage());
        assertTrue(punchingGlove.maxDamage() > normal.maxDamage());
        assertTrue(muscleBand.notes().contains("Muscle Band"));
        assertTrue(punchingGlove.notes().contains("Punching Glove"));
    }

    @Test
    void zeroHpRemainsZeroAndDoesNotTriggerFullHpAbilities() {
        PokemonSet attacker = new PokemonSet(species("lucario", "Lucario", PokeType.FIGHTING, PokeType.STEEL, 70, 110, 70, 115, 70, 90, false));
        PokemonSet defender = new PokemonSet(species("dragonite", "Dragonite", PokeType.DRAGON, PokeType.FLYING, 91, 134, 95, 100, 100, 80, false));
        defender.currentHp = 0;
        defender.ability = "Multiscale";
        MoveData move = move("icepunch", "Ice Punch", PokeType.ICE, DamageCategory.PHYSICAL, 75, false);

        DamageResult withMultiscale = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        defender.ability = "None";
        DamageResult withoutMultiscale = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertEquals(0, defender.visibleHp());
        assertEquals(withoutMultiscale.maxDamage(), withMultiscale.maxDamage());
    }

    @Test
    void paralysisAndQuickFeetUpdateSpeedCorrectly() {
        PokemonSet pokemon = new PokemonSet(species("jolteon", "Jolteon", PokeType.ELECTRIC, PokeType.NONE, 65, 65, 60, 110, 95, 130, false));
        int normal = DamageCalculator.stat(pokemon, Stat.SPE, false, new FieldState());

        pokemon.status = StatusCondition.PARALYSIS;
        int paralyzed = DamageCalculator.stat(pokemon, Stat.SPE, false, new FieldState());
        pokemon.ability = "Quick Feet";
        int quickFeet = DamageCalculator.stat(pokemon, Stat.SPE, false, new FieldState());

        assertEquals((int) Math.floor(normal * 0.5), paralyzed);
        assertEquals((int) Math.floor(normal * 1.5), quickFeet);
    }

    @Test
    void defensiveImmunitiesAreBypassedByMoldBreaker() {
        PokemonSet attacker = new PokemonSet(species("gyarados", "Gyarados", PokeType.WATER, PokeType.FLYING, 95, 125, 79, 60, 100, 81, false));
        PokemonSet defender = new PokemonSet(species("vaporeon", "Vaporeon", PokeType.WATER, PokeType.NONE, 130, 65, 60, 110, 95, 65, false));
        defender.ability = "Water Absorb";
        MoveData move = move("waterfall", "Waterfall", PokeType.WATER, DamageCategory.PHYSICAL, 80, false);

        DamageResult immune = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        attacker.ability = "Mold Breaker";
        DamageResult bypassed = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertEquals(0, immune.maxDamage());
        assertTrue(bypassed.maxDamage() > 0);
    }

    @Test
    void friendGuardOnlyAppliesWhenAnAllyIsEnabled() {
        PokemonSet attacker = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        PokemonSet defender = new PokemonSet(species("clefairy", "Clefairy", PokeType.FAIRY, PokeType.NONE, 70, 45, 48, 60, 65, 35, true));
        defender.ability = "Friend Guard";
        MoveData move = move("ironhead", "Iron Head", PokeType.STEEL, DamageCategory.PHYSICAL, 80, false);
        FieldState field = new FieldState();
        field.doubles = true;

        DamageResult holder = DamageCalculator.calculate(attacker, defender, move, field);
        field.friendGuard = true;
        DamageResult protectedByAlly = DamageCalculator.calculate(attacker, defender, move, field);

        assertTrue(protectedByAlly.maxDamage() < holder.maxDamage());
    }

    @Test
    void adaptabilityUsesTeraSameTypeMultiplier() {
        PokemonSet attacker = new PokemonSet(species("porygonz", "Porygon-Z", PokeType.NORMAL, PokeType.NONE, 85, 80, 70, 135, 75, 90, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        attacker.ability = "Adaptability";
        MoveData move = move("triattack", "Tri Attack", PokeType.NORMAL, DamageCategory.SPECIAL, 80, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        attacker.teraType = PokeType.NORMAL;
        attacker.terastallized = true;
        DamageResult tera = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertTrue(tera.maxDamage() > normal.maxDamage());
        assertTrue(tera.notes().contains("Adaptability Tera STAB"));
    }

    @Test
    void normalFormAbilityIsNotMarkedHiddenWhenCobblemonListsBothSlots() {
        LinkedHashSet<String> legal = new LinkedHashSet<>();
        LinkedHashSet<String> hidden = new LinkedHashSet<>();

        CobblemonDexDataProvider.addAbilityJsonArray(
                JsonParser.parseString("[\"toughclaws\",\"h:toughclaws\"]").getAsJsonArray(), legal, hidden);

        assertEquals(1, legal.size());
        assertTrue(legal.stream().anyMatch(ability -> TropimonDex.normalize(ability).equals("toughclaws")));
        assertTrue(hidden.isEmpty());
    }

    @Test
    void contactAbilitiesUseCobblemonMoveIdentity() {
        PokemonSet attacker = new PokemonSet(species("charizardmegax", "Mega Charizard X", PokeType.FIRE, PokeType.DRAGON, 78, 130, 111, 130, 85, 100, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        MoveData move = move("dragonclaw", "Dragon Claw", PokeType.DRAGON, DamageCategory.PHYSICAL, 80, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        attacker.ability = "Tough Claws";
        DamageResult boosted = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        defender.ability = "Fluffy";
        DamageResult resisted = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertTrue(boosted.maxDamage() > normal.maxDamage());
        assertTrue(resisted.maxDamage() < boosted.maxDamage());
        assertTrue(boosted.notes().contains("Tough Claws"));
        assertTrue(resisted.notes().contains("Fluffy contact resist"));
    }

    @Test
    void boosterEnergyActivatesParadoxHighestStat() {
        PokemonSet attacker = new PokemonSet(species("ironbundle", "Iron Bundle", PokeType.ICE, PokeType.WATER, 56, 80, 114, 124, 60, 136, false));
        attacker.ability = "Quark Drive";
        int normalSpeed = DamageCalculator.stat(attacker, Stat.SPE, false, new FieldState());
        attacker.item = "Booster Energy";
        int boostedSpeed = DamageCalculator.stat(attacker, Stat.SPE, false, new FieldState());

        assertTrue(boostedSpeed > normalSpeed);
    }

    @Test
    void normalizedCobblemonSearchHandlesLocalizedAccents() {
        assertEquals("pokemonmegaeclair", TropimonDex.normalize("Pokémon Méga Éclair"));
    }

    @Test
    void immutableDexViewsAreReusedInsteadOfAllocatedPerSearch() {
        var first = TropimonDex.natureList();
        var second = TropimonDex.natureList();

        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add(TropimonDex.nature("serious")));
        for (int index = 1; index < first.size(); index++) {
            assertTrue(first.get(index - 1).name().compareToIgnoreCase(first.get(index).name()) <= 0);
        }
    }

    @Test
    void damageCacheInvalidatesWhenAnEvChanges() {
        DamageCalcState state = new DamageCalcState();
        state.attacker = new PokemonSet(species("gengar", "Gengar", PokeType.GHOST, PokeType.POISON, 60, 65, 60, 130, 75, 110, false));
        state.defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));
        state.attacker.moves.clear();
        state.attacker.moves.add(move("sludgebomb", "Sludge Bomb", PokeType.POISON, DamageCategory.SPECIAL, 90, false));

        DamageResult before = state.calculateMove(true, 0);
        state.attacker.evs.put(Stat.SPA, 252);
        DamageResult after = state.calculateMove(true, 0);

        assertTrue(after.maxDamage() > before.maxDamage());
    }

    @Test
    void battleSnapshotSynchronizesTheCobblemonBattleFormat() {
        DamageCalcState state = new DamageCalcState();
        PokemonSet player = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND, 108, 130, 95, 80, 85, 102, false));
        PokemonSet opponent = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE, 160, 110, 65, 65, 110, 30, false));

        state.setFromBattle(new BattlePokemonSnapshot(player, opponent, true));
        assertTrue(state.field.doubles);
        state.field.helpingHand = true;
        state.field.friendGuard = true;

        state.setFromBattle(new BattlePokemonSnapshot(player, opponent, false));
        assertFalse(state.field.doubles);
        assertFalse(state.field.helpingHand);
        assertFalse(state.field.friendGuard);
    }

    @Test
    void cobblemonBattleMessagesSynchronizeWeatherTerrainAndBothSides() {
        FieldState field = new FieldState();
        Object battle = new Object();
        try {
            CobblemonBattleConditionTracker.resetForBattle(battle);
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.weather.raindance.start");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldstart.electricterrain");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldstart.trickroom");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldstart.gravity");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.sidestart.ally.tailwind");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.sidestart.opponent.reflect");
            CobblemonBattleConditionTracker.applyTo(field);

            assertEquals(Weather.RAIN, field.weather);
            assertEquals(Terrain.ELECTRIC, field.terrain);
            assertTrue(field.trickRoom);
            assertTrue(field.gravity);
            assertTrue(field.attackerSide.tailwind);
            assertTrue(field.defenderSide.reflect);

            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.weather.raindance.end");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldend.electricterrain");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldend.trickroom");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.fieldend.gravity");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.sideend.ally.tailwind");
            CobblemonBattleConditionTracker.acceptTranslationKey("cobblemon.battle.sideend.opponent.reflect");
            CobblemonBattleConditionTracker.applyTo(field);

            assertEquals(Weather.NONE, field.weather);
            assertEquals(Terrain.NONE, field.terrain);
            assertFalse(field.trickRoom);
            assertFalse(field.gravity);
            assertFalse(field.attackerSide.tailwind);
            assertFalse(field.defenderSide.reflect);
        } finally {
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void doublesSupportEffectsOnlyApplyToTheirSelectedSide() {
        PokemonSet left = new PokemonSet(species("left", "Left", PokeType.NORMAL, PokeType.NONE, 100, 100, 100, 100, 100, 100, false));
        PokemonSet right = new PokemonSet(species("right", "Right", PokeType.NORMAL, PokeType.NONE, 100, 100, 100, 100, 100, 100, false));
        MoveData move = move("tackle", "Tackle", PokeType.NORMAL, DamageCategory.PHYSICAL, 40, false);
        FieldState field = new FieldState();
        field.doubles = true;
        field.attackerSide.helpingHand = true;
        field.attackerSide.friendGuard = true;

        DamageResult leftToRight = DamageCalculator.calculate(left, right, move, field,
                field.attackerSide, field.defenderSide);
        DamageResult rightToLeft = DamageCalculator.calculate(right, left, move, field,
                field.defenderSide, field.attackerSide);

        assertTrue(leftToRight.maxDamage() > rightToLeft.maxDamage());
        assertTrue(leftToRight.notes().contains("Helping Hand"));
        assertTrue(rightToLeft.notes().contains("Friend Guard"));
    }

    @Test
    void doublesSpreadPenaltyRequiresTwoTargetsAndWideGuardBlocksSpreadMoves() {
        PokemonSet attacker = new PokemonSet(species("garchomp", "Garchomp", PokeType.DRAGON, PokeType.GROUND,
                108, 130, 95, 80, 85, 102, false));
        PokemonSet defender = new PokemonSet(species("metagross", "Metagross", PokeType.STEEL, PokeType.PSYCHIC,
                80, 135, 130, 95, 90, 70, false));
        MoveData earthquake = move("earthquake", "Earthquake", PokeType.GROUND, DamageCategory.PHYSICAL, 100, true);
        FieldState field = new FieldState();
        field.doubles = true;

        field.attackerSide.spreadTargets = 1;
        DamageResult oneTarget = DamageCalculator.calculate(attacker, defender, earthquake, field,
                field.attackerSide, field.defenderSide);
        field.attackerSide.spreadTargets = 2;
        DamageResult twoTargets = DamageCalculator.calculate(attacker, defender, earthquake, field,
                field.attackerSide, field.defenderSide);

        assertTrue(oneTarget.maxDamage() > twoTargets.maxDamage());
        assertFalse(oneTarget.notes().contains("spread x0.75"));
        assertTrue(twoTargets.notes().contains("spread x0.75"));

        field.defenderSide.wideGuard = true;
        DamageResult blocked = DamageCalculator.calculate(attacker, defender, earthquake, field,
                field.attackerSide, field.defenderSide);
        assertEquals(0, blocked.maxDamage());
        assertTrue(blocked.notes().contains("Wide Guard"));
    }

    @Test
    void doublesPartnerAbilitiesApplyToTheCorrectSide() {
        PokemonSet attacker = new PokemonSet(species("magnezone", "Magnezone", PokeType.ELECTRIC, PokeType.STEEL,
                70, 70, 115, 130, 90, 60, false));
        PokemonSet defender = new PokemonSet(species("snorlax", "Snorlax", PokeType.NORMAL, PokeType.NONE,
                160, 110, 65, 65, 110, 30, false));
        MoveData flashCannon = move("flashcannon", "Flash Cannon", PokeType.STEEL, DamageCategory.SPECIAL, 80, false);
        FieldState field = new FieldState();
        field.doubles = true;

        DamageResult baseline = DamageCalculator.calculate(attacker, defender, flashCannon, field,
                field.attackerSide, field.defenderSide);

        field.attackerSide.partnerAbility = "Battery";
        DamageResult battery = DamageCalculator.calculate(attacker, defender, flashCannon, field,
                field.attackerSide, field.defenderSide);
        assertTrue(battery.maxDamage() > baseline.maxDamage());
        assertTrue(battery.notes().contains("Partner Battery"));

        field.attackerSide.partnerAbility = "Power Spot";
        DamageResult powerSpot = DamageCalculator.calculate(attacker, defender, flashCannon, field,
                field.attackerSide, field.defenderSide);
        assertTrue(powerSpot.maxDamage() > baseline.maxDamage());
        assertTrue(powerSpot.notes().contains("Partner Power Spot"));

        field.attackerSide.partnerAbility = "Steely Spirit";
        DamageResult steelySpirit = DamageCalculator.calculate(attacker, defender, flashCannon, field,
                field.attackerSide, field.defenderSide);
        assertTrue(steelySpirit.maxDamage() > baseline.maxDamage());
        assertTrue(steelySpirit.notes().contains("Partner Steely Spirit"));

        field.attackerSide.partnerAbility = "None";
        field.defenderSide.partnerAbility = "Friend Guard";
        DamageResult friendGuard = DamageCalculator.calculate(attacker, defender, flashCannon, field,
                field.attackerSide, field.defenderSide);
        assertTrue(friendGuard.maxDamage() < baseline.maxDamage());
        assertTrue(friendGuard.notes().contains("Friend Guard"));
    }

    @Test
    void doublesPartnerStatAbilitiesIncludeFlowerGiftPlusMinusAndRuin() {
        PokemonSet physicalAttacker = new PokemonSet(species("physical", "Physical", PokeType.FIRE, PokeType.NONE,
                100, 120, 90, 80, 90, 100, false));
        PokemonSet specialAttacker = new PokemonSet(species("special", "Special", PokeType.ELECTRIC, PokeType.NONE,
                100, 80, 90, 120, 90, 100, false));
        PokemonSet defender = new PokemonSet(species("target", "Target", PokeType.NORMAL, PokeType.NONE,
                100, 90, 110, 90, 110, 80, false));
        MoveData physicalMove = move("flareblitz", "Flare Blitz", PokeType.FIRE, DamageCategory.PHYSICAL, 120, false);
        MoveData specialMove = move("thunderbolt", "Thunderbolt", PokeType.ELECTRIC, DamageCategory.SPECIAL, 90, false);
        FieldState field = new FieldState();
        field.doubles = true;

        DamageResult physicalBaseline = DamageCalculator.calculate(physicalAttacker, defender, physicalMove, field,
                field.attackerSide, field.defenderSide);
        field.weather = Weather.SUN;
        field.attackerSide.partnerAbility = "Flower Gift";
        DamageResult flowerGift = DamageCalculator.calculate(physicalAttacker, defender, physicalMove, field,
                field.attackerSide, field.defenderSide);
        assertTrue(flowerGift.maxDamage() > physicalBaseline.maxDamage());
        assertTrue(flowerGift.notes().contains("Partner Flower Gift"));

        field.weather = Weather.NONE;
        field.attackerSide.partnerAbility = "Minus";
        specialAttacker.ability = "Plus";
        DamageResult plusMinus = DamageCalculator.calculate(specialAttacker, defender, specialMove, field,
                field.attackerSide, field.defenderSide);
        field.attackerSide.partnerAbility = "None";
        DamageResult specialBaseline = DamageCalculator.calculate(specialAttacker, defender, specialMove, field,
                field.attackerSide, field.defenderSide);
        assertTrue(plusMinus.maxDamage() > specialBaseline.maxDamage());
        assertTrue(plusMinus.notes().contains("Plus/Minus"));

        field.attackerSide.partnerAbility = "Sword of Ruin";
        DamageResult ruin = DamageCalculator.calculate(physicalAttacker, defender, physicalMove, field,
                field.attackerSide, field.defenderSide);
        field.attackerSide.partnerAbility = "None";
        DamageResult noRuin = DamageCalculator.calculate(physicalAttacker, defender, physicalMove, field,
                field.attackerSide, field.defenderSide);
        assertTrue(ruin.maxDamage() > noRuin.maxDamage());
        assertTrue(ruin.notes().contains("Sword of Ruin"));
    }

    @Test
    void battleSnapshotSynchronizesAndClearsDoublesContext() {
        PokemonSet player = new PokemonSet(species("player", "Player", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        PokemonSet opponent = new PokemonSet(species("opponent", "Opponent", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        DamageCalcState state = new DamageCalcState();

        state.setFromBattle(new BattlePokemonSnapshot(player, opponent, true,
                "Battery", "Friend Guard", "Partner Ally", "Partner Enemy", 2, 1));

        assertTrue(state.field.doubles);
        assertEquals("Battery", state.field.attackerSide.partnerAbility);
        assertEquals("Friend Guard", state.field.defenderSide.partnerAbility);
        assertEquals("Partner Ally", state.field.attackerSide.partnerName);
        assertEquals("Partner Enemy", state.field.defenderSide.partnerName);
        assertEquals(1, state.field.attackerSide.spreadTargets);
        assertEquals(2, state.field.defenderSide.spreadTargets);

        state.field.attackerSide.wideGuard = true;
        state.setFromBattle(new BattlePokemonSnapshot(player, opponent, false));
        assertFalse(state.field.doubles);
        assertEquals("None", state.field.attackerSide.partnerAbility);
        assertEquals("None", state.field.defenderSide.partnerAbility);
        assertEquals("", state.field.attackerSide.partnerName);
        assertEquals(1, state.field.attackerSide.spreadTargets);
        assertFalse(state.field.attackerSide.wideGuard);
    }

    @Test
    void partnerWideGuardIsTrackedWithoutRevealingItOnThePrimaryPokemon() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        FieldState previousField = state.field;
        Object battle = new Object();
        try {
            state.attacker = new PokemonSet(species("ally", "Ally", PokeType.NORMAL, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.defender = new PokemonSet(species("enemy", "Enemy", PokeType.ROCK, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.field = new FieldState();
            state.field.doubles = true;
            state.field.attackerSide.partnerName = "Partner";
            state.attacker.moves.clear();
            state.attacker.movesKnown = false;
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.used_move",
                    Text.literal("Partner"), Text.translatable("cobblemon.move.wideguard")));
            CobblemonBattleConditionTracker.applyTo(state.field);

            assertTrue(state.field.attackerSide.wideGuard);
            assertTrue(state.attacker.moves.isEmpty());

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.turn", 2));
            CobblemonBattleConditionTracker.applyTo(state.field);
            assertFalse(state.field.attackerSide.wideGuard);
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            state.field = previousField;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void partnerAbilityRevealUpdatesPartnerContextWithoutOverwritingPrimaryAbility() {
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet previousAttacker = state.attacker;
        PokemonSet previousDefender = state.defender;
        FieldState previousField = state.field;
        Object battle = new Object();
        try {
            state.attacker = new PokemonSet(species("ally", "Ally", PokeType.NORMAL, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.defender = new PokemonSet(species("enemy", "Enemy", PokeType.ROCK, PokeType.NONE,
                    80, 80, 80, 80, 80, 80, false));
            state.attacker.ability = "Pressure";
            state.attacker.abilityKnown = true;
            state.field = new FieldState();
            state.field.doubles = true;
            state.field.attackerSide.partnerName = "Partner";
            CobblemonBattleConditionTracker.resetForBattle(battle);

            CobblemonBattleConditionTracker.accept(Text.translatable("cobblemon.battle.ability.battery",
                    Text.literal("Partner")));

            assertEquals("Pressure", state.attacker.ability);
            assertEquals("Battery", state.field.attackerSide.partnerAbility);
            assertEquals("Battery", state.attackerPartnerAbilitySearch);
        } finally {
            state.attacker = previousAttacker;
            state.defender = previousDefender;
            state.field = previousField;
            CobblemonBattleConditionTracker.resetForBattle(null);
        }
    }

    @Test
    void parsesMoveFlagsFromCobblemonsBundledShowdownData() {
        String source = """
                exports.Moves = {
                  customfang: {
                    num: 9999,
                    flags: { contact: 1, protect: 1, bite: 1, sound: 1 },
                    recoil: [33, 100],
                    secondary: {
                      chance: 10
                    }
                  },
                  plainmove: {
                    flags: {},
                    secondary: null
                  }
                };
                """;

        var flags = CobblemonDexDataProvider.parseMoveFlags(new BufferedReader(new StringReader(source)));

        assertEquals(Set.of("contact", "protect", "bite", "sound", "recoil", "secondary"), flags.get("customfang"));
        assertTrue(flags.get("plainmove").isEmpty());
    }

    @Test
    void cobblemonFlagsDriveAbilityBoostsForCustomMoves() {
        PokemonSet attacker = new PokemonSet(species("custom", "Custom", PokeType.DARK, PokeType.NONE, 80, 120, 80, 70, 80, 90, false));
        PokemonSet defender = new PokemonSet(species("target", "Target", PokeType.NORMAL, PokeType.NONE, 100, 80, 100, 80, 100, 80, false));
        MoveData customBite = new MoveData("addonfang", "Addon Fang", PokeType.DARK, DamageCategory.PHYSICAL,
                80, false, true, Set.of("contact", "bite"));

        DamageResult normal = DamageCalculator.calculate(attacker, defender, customBite, new FieldState());
        attacker.ability = "Strong Jaw";
        DamageResult boosted = DamageCalculator.calculate(attacker, defender, customBite, new FieldState());

        assertTrue(boosted.maxDamage() > normal.maxDamage());
        assertTrue(boosted.notes().contains("Strong Jaw"));
    }

    @Test
    void sheerForceUsesCobblemonsSecondaryEffectMetadata() {
        PokemonSet attacker = new PokemonSet(species("custom", "Custom", PokeType.POISON, PokeType.NONE, 80, 80, 80, 120, 80, 90, false));
        PokemonSet defender = new PokemonSet(species("target", "Target", PokeType.NORMAL, PokeType.NONE, 100, 80, 100, 80, 100, 80, false));
        MoveData move = new MoveData("addonblast", "Addon Blast", PokeType.POISON, DamageCategory.SPECIAL,
                80, false, false, Set.of("secondary"));

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        attacker.ability = "Sheer Force";
        DamageResult boosted = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertTrue(boosted.maxDamage() > normal.maxDamage());
        assertTrue(boosted.notes().contains("Sheer Force"));
    }

    @Test
    void battleRuntimeUpdatesPreserveManualOpponentBuildForSameSpecies() {
        DamageCalcState state = new DamageCalcState();
        SpeciesData target = species("target", "Target", PokeType.WATER, PokeType.NONE, 100, 80, 100, 80, 100, 80, false);
        state.defender = new PokemonSet(target);
        state.defender.evs.put(Stat.SPD, 252);
        state.defender.item = "Assault Vest";

        PokemonSet live = new PokemonSet(target);
        live.currentHp = 47;
        live.status = StatusCondition.BURN;
        live.boosts.put(Stat.DEF, 2);
        state.setFromBattle(new BattlePokemonSnapshot(null, live, false));

        assertEquals(252, state.defender.evs.get(Stat.SPD));
        assertEquals("Assault Vest", state.defender.item);
        assertEquals(47, state.defender.currentHp);
        assertEquals(StatusCondition.BURN, state.defender.status);
        assertEquals(2, state.defender.boosts.get(Stat.DEF));
    }

    @Test
    void psychicTerrainAndAntiPriorityAbilitiesUseCobblemonsMovePriority() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE, 80, 120, 80, 80, 80, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE, 100, 80, 100, 80, 100, 80, false));
        MoveData priorityMove = new MoveData("addonpriority", "Addon Priority", PokeType.NORMAL,
                DamageCategory.PHYSICAL, 60, false, true, Set.of("contact"), 1);
        FieldState psychic = new FieldState();
        psychic.terrain = Terrain.PSYCHIC;

        assertEquals(0, DamageCalculator.calculate(attacker, defender, priorityMove, psychic).maxDamage());

        psychic.terrain = Terrain.NONE;
        defender.ability = "Armor Tail";
        assertEquals(0, DamageCalculator.calculate(attacker, defender, priorityMove, psychic).maxDamage());
    }

    @Test
    void teraShellForcesNeutralAndSuperEffectiveHitsToNotVeryEffectiveAtFullHp() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIGHTING, PokeType.NONE, 80, 120, 80, 80, 80, 100, false));
        PokemonSet defender = new PokemonSet(species("terapagos", "Terapagos", PokeType.NORMAL, PokeType.NONE, 95, 95, 110, 105, 110, 85, false));
        MoveData move = move("closecombat", "Close Combat", PokeType.FIGHTING, DamageCategory.PHYSICAL, 120, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        defender.ability = "Tera Shell";
        DamageResult shell = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        defender.currentHp = defender.maxHp() - 1;
        DamageResult broken = DamageCalculator.calculate(attacker, defender, move, new FieldState());

        assertTrue(shell.maxDamage() < normal.maxDamage());
        assertTrue(broken.maxDamage() > shell.maxDamage());
        assertTrue(shell.notes().contains("Tera Shell"));
    }

    @Test
    void variablePowerMovesUseLiveSpeedHpBoostsAndStatus() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE,
                100, 110, 90, 110, 90, 40, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 90, 100, 90, 100, 140, false));
        FieldState field = new FieldState();

        DamageResult gyroBall = DamageCalculator.calculate(attacker, defender,
                move("gyroball", "Gyro Ball", PokeType.STEEL, DamageCategory.PHYSICAL, 1, false), field);
        attacker.evs.put(Stat.SPE, 252);
        DamageResult fasterGyroBall = DamageCalculator.calculate(attacker, defender,
                move("gyroball", "Gyro Ball", PokeType.STEEL, DamageCategory.PHYSICAL, 1, false), field);
        assertTrue(gyroBall.maxDamage() > fasterGyroBall.maxDamage());

        MoveData eruption = move("eruption", "Eruption", PokeType.FIRE, DamageCategory.SPECIAL, 150, false);
        attacker.currentHp = attacker.maxHp();
        DamageResult fullHpEruption = DamageCalculator.calculate(attacker, defender, eruption, field);
        attacker.currentHp = Math.max(1, attacker.maxHp() / 4);
        DamageResult lowHpEruption = DamageCalculator.calculate(attacker, defender, eruption, field);
        assertTrue(fullHpEruption.maxDamage() > lowHpEruption.maxDamage());

        MoveData storedPower = move("storedpower", "Stored Power", PokeType.PSYCHIC, DamageCategory.SPECIAL, 20, false);
        DamageResult unboosted = DamageCalculator.calculate(attacker, defender, storedPower, field);
        attacker.boosts.put(Stat.SPA, 2);
        attacker.boosts.put(Stat.SPE, 1);
        DamageResult boosted = DamageCalculator.calculate(attacker, defender, storedPower, field);
        assertTrue(boosted.maxDamage() > unboosted.maxDamage());

        MoveData facade = move("facade", "Facade", PokeType.NORMAL, DamageCategory.PHYSICAL, 70, false);
        attacker.status = StatusCondition.NONE;
        DamageResult healthyFacade = DamageCalculator.calculate(attacker, defender, facade, field);
        attacker.status = StatusCondition.PARALYSIS;
        DamageResult statusFacade = DamageCalculator.calculate(attacker, defender, facade, field);
        assertTrue(statusFacade.maxDamage() > healthyFacade.maxDamage());
    }

    @Test
    void burnedFacadeKeepsItsDoubledPowerWithoutTheBurnPenalty() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE,
                100, 120, 90, 70, 90, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 90, 100, 90, 100, 80, false));
        MoveData facade = move("facade", "Facade", PokeType.NORMAL, DamageCategory.PHYSICAL, 70, false);

        DamageResult healthy = DamageCalculator.calculate(attacker, defender, facade, new FieldState());
        attacker.status = StatusCondition.BURN;
        DamageResult burned = DamageCalculator.calculate(attacker, defender, facade, new FieldState());

        assertTrue(burned.maxDamage() >= healthy.maxDamage() * 1.9);
        assertFalse(burned.notes().contains("burn x0.5"));
    }

    @Test
    void atypicalMovesUseTheirRealOffensiveAndDefensiveStats() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIGHTING, PokeType.PSYCHIC,
                100, 60, 140, 120, 90, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 150, 50, 90, 180, 80, false));
        FieldState field = new FieldState();

        MoveData bodyPress = move("bodypress", "Body Press", PokeType.FIGHTING, DamageCategory.PHYSICAL, 80, false);
        DamageResult bodyPressBaseline = DamageCalculator.calculate(attacker, defender, bodyPress, field);
        attacker.evs.put(Stat.DEF, 252);
        DamageResult bodyPressInvested = DamageCalculator.calculate(attacker, defender, bodyPress, field);
        assertTrue(bodyPressInvested.maxDamage() > bodyPressBaseline.maxDamage());
        assertTrue(bodyPressInvested.notes().contains("Body Press uses Defense"));
        attacker.status = StatusCondition.BURN;
        DamageResult burnedBodyPress = DamageCalculator.calculate(attacker, defender, bodyPress, field);
        assertEquals(bodyPressInvested.maxDamage(), burnedBodyPress.maxDamage());
        attacker.ability = "Guts";
        DamageResult gutsBodyPress = DamageCalculator.calculate(attacker, defender, bodyPress, field);
        assertEquals(bodyPressInvested.maxDamage(), gutsBodyPress.maxDamage());
        attacker.status = StatusCondition.NONE;
        attacker.ability = "None";

        MoveData foulPlay = move("foulplay", "Foul Play", PokeType.DARK, DamageCategory.PHYSICAL, 95, false);
        DamageResult foulPlayStrongTarget = DamageCalculator.calculate(attacker, defender, foulPlay, field);
        defender.evs.put(Stat.ATK, 0);
        defender.species = species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 40, 50, 90, 180, 80, false);
        DamageResult foulPlayWeakTarget = DamageCalculator.calculate(attacker, defender, foulPlay, field);
        assertTrue(foulPlayStrongTarget.maxDamage() > foulPlayWeakTarget.maxDamage());

        MoveData psychic = move("psychic", "Psychic", PokeType.PSYCHIC, DamageCategory.SPECIAL, 90, false);
        MoveData psyshock = move("psyshock", "Psyshock", PokeType.PSYCHIC, DamageCategory.SPECIAL, 80, false);
        assertTrue(DamageCalculator.calculate(attacker, defender, psyshock, field).maxDamage()
                > DamageCalculator.calculate(attacker, defender, psychic, field).maxDamage());
    }

    @Test
    void teraBlastUsesTheActiveTeraTypeAndTheHigherOffensiveStat() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE,
                100, 140, 90, 60, 90, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.GRASS, PokeType.NONE,
                100, 90, 100, 90, 100, 80, false));
        attacker.terastallized = true;
        attacker.teraType = PokeType.FIRE;

        DamageResult result = DamageCalculator.calculate(attacker, defender,
                move("terablast", "Tera Blast", PokeType.NORMAL, DamageCategory.SPECIAL, 80, false), new FieldState());

        assertTrue(result.notes().contains("Tera Blast Fire"));
        assertTrue(result.notes().contains("Tera Blast uses physical damage"));
        assertTrue(result.notes().stream().anyMatch(note -> note.startsWith("super effective")));
    }

    @Test
    void weightMovesUseCobblemonSpeciesWeight() {
        PokemonSet attacker = new PokemonSet(weightedSpecies("attacker", "Attacker", PokeType.FIGHTING,
                100, 120, 100, 80, 100, 80, 500.0));
        PokemonSet lightTarget = new PokemonSet(weightedSpecies("light", "Light", PokeType.NORMAL,
                100, 80, 100, 80, 100, 80, 5.0));
        PokemonSet heavyTarget = new PokemonSet(weightedSpecies("heavy", "Heavy", PokeType.NORMAL,
                100, 80, 100, 80, 100, 80, 250.0));
        MoveData lowKick = move("lowkick", "Low Kick", PokeType.FIGHTING, DamageCategory.PHYSICAL, 1, false);

        DamageResult light = DamageCalculator.calculate(attacker, lightTarget, lowKick, new FieldState());
        DamageResult heavy = DamageCalculator.calculate(attacker, heavyTarget, lowKick, new FieldState());

        assertTrue(heavy.maxDamage() > light.maxDamage());
        assertTrue(heavy.warnings().isEmpty());
    }

    @Test
    void missingBattleContextProducesAnExplicitEstimateWarning() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.GHOST, PokeType.NONE,
                100, 120, 100, 80, 100, 80, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));

        DamageResult rageFist = DamageCalculator.calculate(attacker, defender,
                move("ragefist", "Rage Fist", PokeType.GHOST, DamageCategory.PHYSICAL, 50, false), new FieldState());
        DamageResult eruption = DamageCalculator.calculate(attacker, defender,
                move("eruption", "Eruption", PokeType.FIRE, DamageCategory.SPECIAL, 150, false), new FieldState());

        assertTrue(rageFist.warnings().stream().anyMatch(warning -> warning.key().equals("battle_history")));
        assertTrue(eruption.warnings().stream().anyMatch(warning -> warning.key().equals("attacker_hp_unknown")));
    }

    @Test
    void parsesMultiHitMetadataFromCobblemonsBundledShowdownData() {
        String source = """
                exports.Moves = {
                  custombarrage: {
                    flags: { protect: 1 },
                    multihit: [2, 5]
                  },
                  doubletap: {
                    flags: { contact: 1 },
                    accuracy: 90,
                    willCrit: true,
                    multihit: 2,
                    multiaccuracy: true
                  }
                };
                """;

        var flags = CobblemonDexDataProvider.parseMoveFlags(new BufferedReader(new StringReader(source)));

        assertTrue(flags.get("custombarrage").contains("multihit"));
        assertTrue(flags.get("custombarrage").contains("hits2to5"));
        assertTrue(flags.get("doubletap").contains("hits2"));
        assertTrue(flags.get("doubletap").contains("accuracy90"));
        assertTrue(flags.get("doubletap").contains("alwayscrit"));
        assertTrue(flags.get("doubletap").contains("multiaccuracy"));
    }

    @Test
    void multiHitDamageUsesTheCompleteHitRangeAndAbilityOrItemRules() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE,
                100, 120, 100, 80, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        MoveData barrage = new MoveData("barrage", "Barrage", PokeType.NORMAL, DamageCategory.PHYSICAL,
                25, false, false, Set.of("multihit", "hits2to5"));

        DamageResult variable = DamageCalculator.calculate(attacker, defender, barrage, new FieldState());
        assertTrue(variable.notes().contains("2-5 hits"));

        attacker.ability = "Skill Link";
        DamageResult skillLink = DamageCalculator.calculate(attacker, defender, barrage, new FieldState());
        assertTrue(skillLink.notes().contains("5 hits"));
        assertTrue(skillLink.minDamage() > variable.minDamage());

        attacker.ability = "None";
        attacker.item = "Loaded Dice";
        DamageResult loadedDice = DamageCalculator.calculate(attacker, defender, barrage, new FieldState());
        assertTrue(loadedDice.notes().contains("4-5 hits"));
        assertTrue(loadedDice.minDamage() > variable.minDamage());
        assertEquals(variable.maxDamage(), loadedDice.maxDamage());
    }

    @Test
    void variableMultiHitMovesUseModernHitCountProbabilities() {
        var probabilities = DamageCalculator.hitCountProbabilities(
                new DamageCalculator.HitProfile(2, 5, false, false, false, false));

        assertEquals(0.35, probabilities.get(2), 0.000001);
        assertEquals(0.35, probabilities.get(3), 0.000001);
        assertEquals(0.15, probabilities.get(4), 0.000001);
        assertEquals(0.15, probabilities.get(5), 0.000001);
    }

    @Test
    void wonderGuardUsesMoveSpecificTypeEffectiveness() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.ICE, PokeType.NONE,
                100, 80, 100, 120, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.WATER, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        defender.ability = "Wonder Guard";

        DamageResult iceBeam = DamageCalculator.calculate(attacker, defender,
                move("icebeam", "Ice Beam", PokeType.ICE, DamageCategory.SPECIAL, 90, false), new FieldState());
        DamageResult freezeDry = DamageCalculator.calculate(attacker, defender,
                move("freezedry", "Freeze-Dry", PokeType.ICE, DamageCategory.SPECIAL, 70, false), new FieldState());

        assertEquals(0, iceBeam.maxDamage());
        assertTrue(freezeDry.maxDamage() > 0);
    }

    @Test
    void foulPlayAppliesUnawareFromTheCorrectPokemon() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.DARK, PokeType.NONE,
                100, 80, 100, 80, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 120, 100, 80, 100, 80, false));
        defender.boosts.put(Stat.ATK, 6);
        MoveData foulPlay = move("foulplay", "Foul Play", PokeType.DARK, DamageCategory.PHYSICAL, 95, false);

        DamageResult baseline = DamageCalculator.calculate(attacker, defender, foulPlay, new FieldState());
        defender.ability = "Unaware";
        DamageResult targetUnaware = DamageCalculator.calculate(attacker, defender, foulPlay, new FieldState());
        defender.ability = "None";
        attacker.ability = "Unaware";
        DamageResult attackerUnaware = DamageCalculator.calculate(attacker, defender, foulPlay, new FieldState());

        assertEquals(baseline.maxDamage(), targetUnaware.maxDamage());
        assertTrue(attackerUnaware.maxDamage() < baseline.maxDamage());
    }

    @Test
    void reflectionCacheDistinguishesSameArityOverloads() {
        OverloadedTarget target = new OverloadedTarget();

        assertEquals("text", CobblemonBattleDataProvider.invokeOptional(target, "select", "value"));
        assertEquals("number", CobblemonBattleDataProvider.invokeOptional(target, "select", 4));
    }

    @Test
    void consumedBoosterEnergyKeepsItsActivatedParadoxBoost() {
        PokemonSet pokemon = new PokemonSet(species("paradox", "Paradox", PokeType.FIRE, PokeType.NONE,
                100, 150, 90, 80, 90, 100, false));
        pokemon.ability = "Protosynthesis";
        pokemon.item = "None";
        FieldState field = new FieldState();
        int baseline = DamageCalculator.stat(pokemon, Stat.ATK, false, field);

        pokemon.paradoxBoostActive = true;
        int activated = DamageCalculator.stat(pokemon, Stat.ATK, false, field);

        assertTrue(activated > baseline);
        assertEquals("None", pokemon.item);
    }

    @Test
    void paradoxBestStatIgnoresChoiceItemModifiersLikeCobblemonShowdown() {
        PokemonSet pokemon = new PokemonSet(species("paradox", "Paradox", PokeType.FIRE, PokeType.NONE,
                100, 120, 80, 100, 80, 90, false));
        pokemon.ability = "Protosynthesis";
        pokemon.item = "Choice Specs";
        FieldState neutral = new FieldState();
        int neutralAttack = DamageCalculator.stat(pokemon, Stat.ATK, false, neutral);
        int neutralSpecialAttack = DamageCalculator.stat(pokemon, Stat.SPA, false, neutral);

        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;
        int sunAttack = DamageCalculator.stat(pokemon, Stat.ATK, false, sun);
        int sunSpecialAttack = DamageCalculator.stat(pokemon, Stat.SPA, false, sun);

        assertTrue(sunAttack > neutralAttack);
        assertEquals(neutralSpecialAttack, sunSpecialAttack);
    }

    @Test
    void explicitFormAbilitiesDoNotInheritAnUnrelatedBaseHiddenAbility() {
        SpeciesAbilityData explicitForm = new SpeciesAbilityData(List.of("Stance Change"), List.of());
        SpeciesAbilityData inheritedForm = new SpeciesAbilityData(List.of(), List.of());

        assertEquals(List.of(), CobblemonDexDataProvider.resolvedFormHiddenAbilities(
                explicitForm, List.of("Base Hidden")));
        assertEquals(List.of("Base Hidden"), CobblemonDexDataProvider.resolvedFormHiddenAbilities(
                inheritedForm, List.of("Base Hidden")));
    }

    private static final class OverloadedTarget {
        public String select(String value) {
            return "text";
        }

        public String select(Number value) {
            return "number";
        }
    }

    @Test
    void progressiveMultiHitMovesAddEveryHitsPower() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.ICE, PokeType.NONE,
                100, 120, 100, 80, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        MoveData firstHit = move("icehit", "Ice Hit", PokeType.ICE, DamageCategory.PHYSICAL, 20, false);
        MoveData tripleAxel = new MoveData("tripleaxel", "Triple Axel", PokeType.ICE, DamageCategory.PHYSICAL,
                20, false, true, Set.of("multihit", "hits3", "multiaccuracy", "accuracy90"));

        DamageResult single = DamageCalculator.calculate(attacker, defender, firstHit, new FieldState());
        DamageResult triple = DamageCalculator.calculate(attacker, defender, tripleAxel, new FieldState());

        assertTrue(triple.notes().contains("1-3 hits"));
        assertEquals(single.minDamage(), triple.minDamage());
        assertTrue(triple.maxDamage() > single.maxDamage() * 4);

        attacker.item = "Loaded Dice";
        DamageResult loadedDice = DamageCalculator.calculate(attacker, defender, tripleAxel, new FieldState());
        assertTrue(loadedDice.notes().contains("3 hits"));
        assertTrue(loadedDice.minDamage() > triple.minDamage());
    }

    @Test
    void guaranteedCritsAndMercilessRespectCriticalHitImmunity() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.GRASS, PokeType.NONE,
                100, 120, 80, 80, 80, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        MoveData flowerTrick = new MoveData("flowertrick", "Flower Trick", PokeType.GRASS,
                DamageCategory.PHYSICAL, 70, false, false, Set.of("alwayscrit"));
        MoveData leafBlade = move("leafblade", "Leaf Blade", PokeType.GRASS, DamageCategory.PHYSICAL, 70, false);

        DamageResult normal = DamageCalculator.calculate(attacker, defender, leafBlade, new FieldState());
        DamageResult guaranteedCrit = DamageCalculator.calculate(attacker, defender, flowerTrick, new FieldState());
        assertTrue(guaranteedCrit.maxDamage() > normal.maxDamage());

        defender.ability = "Battle Armor";
        DamageResult blockedCrit = DamageCalculator.calculate(attacker, defender, flowerTrick, new FieldState());
        assertEquals(normal.maxDamage(), blockedCrit.maxDamage());

        defender.ability = "None";
        defender.status = StatusCondition.POISON;
        attacker.ability = "Merciless";
        assertEquals(guaranteedCrit.maxDamage(), DamageCalculator.calculate(attacker, defender, leafBlade, new FieldState()).maxDamage());
    }

    @Test
    void battleHistoryAbilitiesAndConditionalMovesUseObservedState() {
        PokemonSet attacker = new PokemonSet(species("kingambit", "Kingambit", PokeType.DARK, PokeType.STEEL,
                100, 135, 120, 60, 85, 50, false));
        PokemonSet defender = new PokemonSet(species("target", "Target", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        MoveData slash = move("slash", "Slash", PokeType.NORMAL, DamageCategory.PHYSICAL, 70, false);
        attacker.battleHistoryKnown = true;
        attacker.ability = "Supreme Overlord";
        DamageResult base = DamageCalculator.calculate(attacker, defender, slash, new FieldState());
        attacker.faintedAllies = 5;
        DamageResult supreme = DamageCalculator.calculate(attacker, defender, slash, new FieldState());
        assertTrue(supreme.maxDamage() > base.maxDamage());

        attacker.ability = "Stakeout";
        defender.switchedInThisTurn = true;
        DamageResult stakeout = DamageCalculator.calculate(attacker, defender, slash, new FieldState());
        defender.switchedInThisTurn = false;
        assertTrue(stakeout.maxDamage() > DamageCalculator.calculate(attacker, defender, slash, new FieldState()).maxDamage());

        attacker.ability = "None";
        attacker.allyFaintedPreviousTurn = true;
        DamageResult retaliate = DamageCalculator.calculate(attacker, defender,
                move("retaliate", "Retaliate", PokeType.NORMAL, DamageCategory.PHYSICAL, 70, false), new FieldState());
        attacker.allyFaintedPreviousTurn = false;
        assertTrue(retaliate.maxDamage() > DamageCalculator.calculate(attacker, defender,
                move("retaliate", "Retaliate", PokeType.NORMAL, DamageCategory.PHYSICAL, 70, false), new FieldState()).maxDamage());
    }

    @Test
    void battleHistoryPowersUseTheNextMoveState() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.GHOST, PokeType.NONE,
                100, 120, 100, 120, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.PSYCHIC, PokeType.NONE,
                100, 80, 100, 80, 100, 80, false));
        attacker.battleHistoryKnown = true;

        MoveData furyCutter = move("furycutter", "Fury Cutter", PokeType.BUG, DamageCategory.PHYSICAL, 40, false);
        DamageResult firstFuryCutter = DamageCalculator.calculate(attacker, defender, furyCutter, new FieldState());
        attacker.lastMoveId = "furycutter";
        attacker.consecutiveMoveUses = 1;
        DamageResult secondFuryCutter = DamageCalculator.calculate(attacker, defender, furyCutter, new FieldState());
        assertTrue(secondFuryCutter.minDamage() > firstFuryCutter.minDamage());

        attacker.timesHit = 3;
        DamageResult rageFist = DamageCalculator.calculate(attacker, defender,
                move("ragefist", "Rage Fist", PokeType.GHOST, DamageCategory.PHYSICAL, 50, false), new FieldState());
        attacker.timesHit = 0;
        DamageResult baseRageFist = DamageCalculator.calculate(attacker, defender,
                move("ragefist", "Rage Fist", PokeType.GHOST, DamageCategory.PHYSICAL, 50, false), new FieldState());
        assertTrue(rageFist.maxDamage() > baseRageFist.maxDamage());

        attacker.item = "Metronome";
        attacker.lastMoveId = "shadowball";
        attacker.consecutiveMoveUses = 2;
        MoveData shadowBall = move("shadowball", "Shadow Ball", PokeType.GHOST, DamageCategory.SPECIAL, 80, false);
        DamageResult metronome = DamageCalculator.calculate(attacker, defender, shadowBall, new FieldState());
        attacker.item = "None";
        DamageResult noItem = DamageCalculator.calculate(attacker, defender, shadowBall, new FieldState());
        assertTrue(metronome.maxDamage() > noItem.maxDamage());
    }

    @Test
    void trickRoomReversesSpeedBasedMoveOrder() {
        PokemonSet slow = new PokemonSet(species("slow", "Slow", PokeType.WATER, PokeType.NONE,
                100, 120, 100, 80, 100, 30, false));
        PokemonSet fast = new PokemonSet(species("fast", "Fast", PokeType.NORMAL, PokeType.NONE,
                100, 80, 100, 80, 100, 150, false));
        MoveData fishiousRend = move("fishiousrend", "Fishious Rend", PokeType.WATER,
                DamageCategory.PHYSICAL, 85, false);
        FieldState field = new FieldState();

        DamageResult normal = DamageCalculator.calculate(slow, fast, fishiousRend, field);
        field.trickRoom = true;
        DamageResult trickRoom = DamageCalculator.calculate(slow, fast, fishiousRend, field);

        assertTrue(trickRoom.maxDamage() > normal.maxDamage());
        assertTrue(trickRoom.notes().contains("Fishious Rend first x2"));
    }

    @Test
    void gravityGroundsFlyingLevitateAndAirBalloonTargets() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.GROUND, PokeType.NONE,
                100, 130, 100, 80, 100, 80, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.ELECTRIC, PokeType.FLYING,
                100, 80, 100, 80, 100, 80, false));
        defender.ability = "Levitate";
        defender.item = "Air Balloon";
        MoveData earthquake = move("earthquake", "Earthquake", PokeType.GROUND,
                DamageCategory.PHYSICAL, 100, false);

        DamageResult immune = DamageCalculator.calculate(attacker, defender, earthquake, new FieldState());
        FieldState gravity = new FieldState();
        gravity.gravity = true;
        DamageResult grounded = DamageCalculator.calculate(attacker, defender, earthquake, gravity);

        assertEquals(0, immune.maxDamage());
        assertTrue(grounded.maxDamage() > 0);
    }

    @Test
    void battleIdentityKeepsManualAssumptionsAcrossDynamicForms() {
        DamageCalcState state = new DamageCalcState();
        SpeciesData shield = species("aegislashshield", "Aegislash Shield", PokeType.STEEL, PokeType.GHOST,
                60, 50, 140, 50, 140, 60, false);
        SpeciesData blade = species("aegislashblade", "Aegislash Blade", PokeType.STEEL, PokeType.GHOST,
                60, 140, 50, 140, 50, 60, false);
        state.defender = new PokemonSet(shield);
        state.defender.battleId = "battle-pokemon-1";
        state.defender.item = "Leftovers";

        PokemonSet liveBlade = new PokemonSet(blade);
        liveBlade.battleId = "battle-pokemon-1";
        liveBlade.currentHp = 80;
        state.setFromBattle(new BattlePokemonSnapshot(null, liveBlade, false));

        assertEquals("aegislashblade", state.defender.species.id());
        assertEquals("Leftovers", state.defender.item);
        assertEquals(80, state.defender.currentHp);
    }

    @Test
    void unknownOpponentInformationStaysExplicitlyUnknown() {
        DamageCalcState state = new DamageCalcState();
        PokemonSet live = new PokemonSet(state.defender.species);
        live.battleId = "unknown-opponent";
        live.itemKnown = false;
        live.abilityKnown = false;
        live.natureKnown = false;
        live.statsKnown = false;
        live.movesKnown = false;
        live.moves.clear();
        while (live.moves.size() < 4) live.moves.add(null);

        state.setFromBattle(new BattlePokemonSnapshot(null, live, false));

        assertFalse(state.defender.itemKnown);
        assertFalse(state.defender.abilityKnown);
        assertFalse(state.defender.movesKnown);
        assertEquals(null, state.defender.moveAt(0));
        assertEquals("", state.defenderItemSearch);
        DamageResult result = DamageCalculator.calculate(state.defender, state.attacker,
                move("tackle", "Tackle", PokeType.NORMAL, DamageCategory.PHYSICAL, 40, false), state.field);
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.key().equals("attacker_set_unknown")),
                result.warnings().toString());
    }

    @Test
    void newlyRevealedOpponentInformationHydratesTheSameBattlePokemon() {
        DamageCalcState state = new DamageCalcState();
        PokemonSet hidden = new PokemonSet(state.defender.species);
        hidden.battleId = "revealed-opponent";
        hidden.itemKnown = false;
        hidden.abilityKnown = false;
        hidden.natureKnown = false;
        hidden.statsKnown = false;
        hidden.movesKnown = false;
        hidden.moves.clear();
        while (hidden.moves.size() < 4) hidden.moves.add(null);
        state.setFromBattle(new BattlePokemonSnapshot(null, hidden, false));

        PokemonSet revealed = hidden.copy();
        revealed.item = "Leftovers";
        revealed.itemKnown = true;
        revealed.ability = "Regenerator";
        revealed.abilityKnown = true;
        revealed.nature = new NatureData("bold", "Bold", Stat.DEF, Stat.ATK);
        revealed.natureKnown = true;
        revealed.evs.put(Stat.HP, 252);
        revealed.statsKnown = true;
        revealed.setMove(0, move("scald", "Scald", PokeType.WATER, DamageCategory.SPECIAL, 80, false));
        state.setFromBattle(new BattlePokemonSnapshot(null, revealed, false));

        assertEquals("Leftovers", state.defender.item);
        assertEquals("Regenerator", state.defender.ability);
        assertEquals("bold", state.defender.nature.id());
        assertEquals(252, state.defender.evs.get(Stat.HP));
        assertEquals("scald", state.defender.moveAt(0).id());
    }

    @Test
    void fixedDamageMovesUseVisibleBattleHistoryAndCurrentHp() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIGHTING, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                200, 100, 100, 100, 100, 100, false));
        attacker.level = 50;
        attacker.lastDamageTaken = 37;
        attacker.lastDamageCategory = DamageCategory.PHYSICAL;

        DamageResult seismicToss = DamageCalculator.calculate(attacker, defender,
                move("seismictoss", "Seismic Toss", PokeType.FIGHTING, DamageCategory.PHYSICAL, 0, false), new FieldState());
        DamageResult superFang = DamageCalculator.calculate(attacker, defender,
                move("superfang", "Super Fang", PokeType.NORMAL, DamageCategory.PHYSICAL, 0, false), new FieldState());
        DamageResult counter = DamageCalculator.calculate(attacker, defender,
                move("counter", "Counter", PokeType.FIGHTING, DamageCategory.PHYSICAL, 0, false), new FieldState());

        assertEquals(50, seismicToss.maxDamage());
        assertEquals(defender.visibleHp() / 2, superFang.maxDamage());
        assertEquals(74, counter.maxDamage());
    }

    @Test
    void screenBreakingMovesDealDamageBeforeScreensCanReduceThem() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIGHTING, PokeType.NONE,
                100, 120, 100, 80, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        SideConditions screens = new SideConditions();
        screens.reflect = true;
        DamageResult baseline = DamageCalculator.calculate(attacker, defender,
                move("brickbreak", "Brick Break", PokeType.FIGHTING, DamageCategory.PHYSICAL, 75, false),
                new FieldState(), new SideConditions(), new SideConditions());
        DamageResult throughReflect = DamageCalculator.calculate(attacker, defender,
                move("brickbreak", "Brick Break", PokeType.FIGHTING, DamageCategory.PHYSICAL, 75, false),
                new FieldState(), new SideConditions(), screens);
        assertEquals(baseline.maxDamage(), throughReflect.maxDamage());
    }

    @Test
    void ripenDoublesResistBerryAndUtilityUmbrellaSuppressesWeatherDamage() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIRE, PokeType.NONE,
                100, 100, 100, 120, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.GRASS, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        MoveData flamethrower = move("flamethrower", "Flamethrower", PokeType.FIRE,
                DamageCategory.SPECIAL, 90, false);
        defender.item = "Occa Berry";
        DamageResult berry = DamageCalculator.calculate(attacker, defender, flamethrower, new FieldState());
        defender.ability = "Ripen";
        DamageResult ripen = DamageCalculator.calculate(attacker, defender, flamethrower, new FieldState());
        assertTrue(ripen.maxDamage() < berry.maxDamage());

        defender.item = "Utility Umbrella";
        defender.ability = "None";
        FieldState sun = new FieldState();
        sun.weather = Weather.SUN;
        DamageResult umbrella = DamageCalculator.calculate(attacker, defender, flamethrower, sun);
        DamageResult clear = DamageCalculator.calculate(attacker, defender, flamethrower, new FieldState());
        assertEquals(clear.maxDamage(), umbrella.maxDamage());
    }

    @Test
    void disguiseAndIceFaceBlockTheRelevantFirstHit() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.NORMAL, PokeType.NONE,
                100, 120, 100, 120, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("mimikyu", "Mimikyu", PokeType.GHOST, PokeType.FAIRY,
                55, 90, 80, 50, 105, 96, false));
        defender.ability = "Disguise";
        DamageResult disguised = DamageCalculator.calculate(attacker, defender,
                move("shadowclaw", "Shadow Claw", PokeType.GHOST, DamageCategory.PHYSICAL, 70, false), new FieldState());
        assertEquals(0, disguised.maxDamage());

        defender.species = species("eiscue", "Eiscue", PokeType.ICE, PokeType.NONE,
                75, 80, 110, 65, 90, 50, false);
        defender.ability = "Ice Face";
        DamageResult physical = DamageCalculator.calculate(attacker, defender,
                move("tackle", "Tackle", PokeType.NORMAL, DamageCategory.PHYSICAL, 40, false), new FieldState());
        DamageResult special = DamageCalculator.calculate(attacker, defender,
                move("swift", "Swift", PokeType.NORMAL, DamageCategory.SPECIAL, 60, false), new FieldState());
        assertEquals(0, physical.maxDamage());
        assertTrue(special.maxDamage() > 0);

        defender.species = species("mimikyu", "Mimikyu", PokeType.GHOST, PokeType.FAIRY,
                55, 90, 80, 50, 105, 96, false);
        defender.ability = "Disguise";
        MoveData doubleHit = new MoveData("doublehit", "Double Hit", PokeType.NORMAL, DamageCategory.PHYSICAL,
                35, false, true, Set.of("multihit", "hits2"), 0);
        attacker.ability = "Scrappy";
        DamageResult secondHitLands = DamageCalculator.calculate(attacker, defender, doubleHit, new FieldState());
        assertTrue(secondHitLands.maxDamage() > 0);
    }

    @Test
    void sturdyAndFocusSashPreventSingleHitOhkoLabelsAtFullHp() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.FIGHTING, PokeType.NONE,
                100, 200, 100, 100, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                30, 100, 20, 100, 20, 100, false));
        MoveData closeCombat = move("closecombat", "Close Combat", PokeType.FIGHTING,
                DamageCategory.PHYSICAL, 120, false);

        defender.item = "Focus Sash";
        assertEquals("survives at 1 HP", DamageCalculator.calculate(attacker, defender,
                closeCombat, new FieldState()).koChance());
        defender.item = "None";
        defender.ability = "Sturdy";
        assertEquals("survives at 1 HP", DamageCalculator.calculate(attacker, defender,
                closeCombat, new FieldState()).koChance());
    }

    @Test
    void ohkoMovesRespectLevelIceImmunityAndSturdy() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.GROUND, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        attacker.level = 50;
        defender.level = 50;
        DamageResult fissure = DamageCalculator.calculate(attacker, defender,
                new MoveData("fissure", "Fissure", PokeType.GROUND, DamageCategory.PHYSICAL,
                        0, false, false, Set.of("accuracy30"), 0), new FieldState());
        assertEquals(defender.visibleHp(), fissure.maxDamage());

        defender.level = 51;
        assertEquals(0, DamageCalculator.calculate(attacker, defender,
                move("fissure", "Fissure", PokeType.GROUND, DamageCategory.PHYSICAL, 0, false), new FieldState()).maxDamage());
        defender.level = 50;
        defender.ability = "Sturdy";
        assertEquals(0, DamageCalculator.calculate(attacker, defender,
                move("fissure", "Fissure", PokeType.GROUND, DamageCategory.PHYSICAL, 0, false), new FieldState()).maxDamage());

        defender.ability = "None";
        defender.species = species("ice", "Ice", PokeType.ICE, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false);
        assertEquals(0, DamageCalculator.calculate(attacker, defender,
                move("sheercold", "Sheer Cold", PokeType.ICE, DamageCategory.SPECIAL, 0, false), new FieldState()).maxDamage());
    }

    @Test
    void retaliationMovesUseObservedDamageFromTheCurrentTurn() {
        PokemonSet attacker = new PokemonSet(species("attacker", "Attacker", PokeType.ICE, PokeType.NONE,
                100, 120, 100, 80, 100, 60, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 120, false));
        attacker.battleHistoryKnown = true;
        MoveData avalanche = move("avalanche", "Avalanche", PokeType.ICE, DamageCategory.PHYSICAL, 60, false);
        DamageResult beforeHit = DamageCalculator.calculate(attacker, defender, avalanche, new FieldState());
        attacker.lastDamageTaken = 20;
        attacker.lastDamageCategory = DamageCategory.PHYSICAL;
        DamageResult afterHit = DamageCalculator.calculate(attacker, defender, avalanche, new FieldState());
        assertTrue(afterHit.maxDamage() > beforeHit.maxDamage());
    }

    @Test
    void slowStartExpiresAfterFiveObservedActiveTurns() {
        PokemonSet regigigas = new PokemonSet(species("regigigas", "Regigigas", PokeType.NORMAL, PokeType.NONE,
                110, 160, 110, 80, 110, 100, false));
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        regigigas.ability = "Slow Start";
        regigigas.turnsActive = 0;
        MoveData move = move("bodyslam", "Body Slam", PokeType.NORMAL, DamageCategory.PHYSICAL, 85, false);
        DamageResult active = DamageCalculator.calculate(regigigas, defender, move, new FieldState());
        int slowSpeed = DamageCalculator.stat(regigigas, Stat.SPE, false, new FieldState());
        regigigas.turnsActive = 5;
        DamageResult expired = DamageCalculator.calculate(regigigas, defender, move, new FieldState());
        int normalSpeed = DamageCalculator.stat(regigigas, Stat.SPE, false, new FieldState());
        assertTrue(expired.maxDamage() > active.maxDamage());
        assertTrue(normalSpeed > slowSpeed);
    }

    @Test
    void speciesSpecificItemsAlsoRecognizeCobblemonForms() {
        SpeciesData alolanMarowak = new SpeciesData("marowakalola", "Marowak Alola", PokeType.FIRE, PokeType.GHOST,
                species("base", "Base", PokeType.FIRE, PokeType.GHOST, 60, 80, 110, 50, 80, 45, false).baseStats(),
                false, "", "marowak", java.util.List.of("alolan"), 34.0);
        PokemonSet attacker = new PokemonSet(alolanMarowak);
        PokemonSet defender = new PokemonSet(species("defender", "Defender", PokeType.NORMAL, PokeType.NONE,
                100, 100, 100, 100, 100, 100, false));
        MoveData move = move("flareblitz", "Flare Blitz", PokeType.FIRE, DamageCategory.PHYSICAL, 120, false);
        DamageResult baseline = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        attacker.item = "Thick Club";
        DamageResult boosted = DamageCalculator.calculate(attacker, defender, move, new FieldState());
        assertTrue(boosted.maxDamage() > baseline.maxDamage());
    }

    @Test
    void bundledTranslationsContainAllCalculatorWarningKeys() throws Exception {
        for (String language : new String[]{"en_us", "fr_fr"}) {
            String path = "/assets/tropimon_damage_calc/lang/" + language + ".json";
            try (var stream = DamageCalculatorTest.class.getResourceAsStream(path)) {
                assertNotNull(stream);
                var json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String warning : new String[]{"variable_power_fallback", "attacker_hp_unknown",
                        "target_hp_unknown", "weight_unavailable", "turn_context", "battle_history",
                        "stakeout", "supreme_overlord", "rivalry", "download", "metronome",
                        "attacker_set_unknown", "target_set_unknown", "move_order", "slow_start",
                        "partner_ability", "multi_hit_accuracy"}) {
                    assertTrue(json.has("warning.tropimon_damage_calc." + warning), language + ": " + warning);
                }
            }
        }
    }

    @Test
    void typeIconAtlasIsPackagedWithTheExpectedGrid() throws Exception {
        try (var stream = DamageCalculatorTest.class.getResourceAsStream(
                "/assets/tropimon_damage_calc/textures/gui/type_icons.png")) {
            assertNotNull(stream);
            var atlas = ImageIO.read(stream);
            assertNotNull(atlas);
            assertEquals(300, atlas.getWidth());
            assertEquals(240, atlas.getHeight());
            assertTrue((atlas.getRGB(30, 30) >>> 24) > 0);
            assertTrue((atlas.getRGB(270, 210) >>> 24) == 0);
        }
    }

    private static SpeciesData species(String id, String name, PokeType type1, PokeType type2, int hp, int atk, int def, int spa, int spd, int spe, boolean nfe) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
        stats.put(Stat.HP, hp);
        stats.put(Stat.ATK, atk);
        stats.put(Stat.DEF, def);
        stats.put(Stat.SPA, spa);
        stats.put(Stat.SPD, spd);
        stats.put(Stat.SPE, spe);
        return new SpeciesData(id, name, type1, type2, stats, nfe, "");
    }

    private static SpeciesData weightedSpecies(String id, String name, PokeType type, int hp, int atk, int def,
                                               int spa, int spd, int spe, double weightKg) {
        SpeciesData base = species(id, name, type, PokeType.NONE, hp, atk, def, spa, spd, spe, false);
        return new SpeciesData(base.id(), base.name(), base.primaryType(), base.secondaryType(), base.baseStats(),
                base.notFullyEvolved(), base.texturePath(), base.cobblemonSpeciesId(), base.aspects(), weightKg);
    }

    private static MoveData move(String id, String name, PokeType type, DamageCategory category, int power, boolean spread) {
        return new MoveData(id, name, type, category, power, spread, false);
    }
}
