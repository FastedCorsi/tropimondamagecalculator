package fr.tropimon.damagecalc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class DamageCacheParityTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void resultsMatchThePreOptimizationCache() {
        for (var entry : scenarios().entrySet()) {
            DamageCalcState state = entry.getValue();
            LegacyDamageCache before = new LegacyDamageCache(state);
            state.prepareCalculations();
            for (boolean fromAttacker : new boolean[]{true, false}) {
                for (int slot = 0; slot < 4; slot++) {
                    assertEquals(before.calculateMove(fromAttacker, slot), state.calculateMove(fromAttacker, slot),
                            entry.getKey() + ":" + fromAttacker + ":" + slot);
                }
                PokemonSet pokemon = fromAttacker ? state.attacker : state.defender;
                for (Stat stat : Stat.values()) assertEquals(stat == Stat.HP ? pokemon.maxHp()
                        : DamageCalculator.displayedStat(pokemon, stat, state.field,
                        fromAttacker ? state.field.attackerSide : state.field.defenderSide),
                        state.preparedStat(fromAttacker, stat), entry.getKey() + ":" + stat);
            }
        }
        System.out.println("Full-result parity: 85 cases, 680 moves and 1020 stat totals");
    }

    @Test
    void benchmarkRequestedSlots() throws Exception {
        assumeTrue(Boolean.getBoolean("damagecalc.benchmark"));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        Map<String, String> metrics = new LinkedHashMap<>();
        for (String mode : List.of("single-slot-changing", "single-slot-unique", "eight-slots-changing", "single-slot-hit", "short-lived-context", "ui-frame-hit")) {
            for (boolean legacy : new boolean[]{true, false}) {
                DamageCalculationCache.clearShared();
                DamageCalcState state = basicState();
                LegacyDamageCache before = new LegacyDamageCache(state);
                for (int i = 0; i < 400; i++) {
                    state.attacker.level = 50 + i % 50;
                    if (legacy) before.calculateMove(true, 0);
                    else state.calculateMove(true, 0);
                }
                int iterations = 600;
                long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    if (mode.equals("short-lived-context")) { state = basicState(); before = new LegacyDamageCache(state); }
                    if (mode.endsWith("changing")) state.attacker.level = 50 + i % 50;
                    if (mode.equals("single-slot-unique")) state.attacker.item = "item" + i;
                    if (mode.equals("ui-frame-hit")) {
                        if (!legacy) state.prepareCalculations();
                        for (boolean attacker : new boolean[]{true, false}) {
                            for (int slot = 0; slot < 4; slot++) {
                                if (legacy) before.calculateMove(attacker, slot);
                                else state.calculatePreparedMove(attacker, slot);
                            }
                            for (Stat stat : Stat.values()) {
                                if (!legacy) state.preparedStat(attacker, stat);
                                else {
                                    PokemonSet p = attacker ? state.attacker : state.defender;
                                    if (stat == Stat.HP) p.maxHp();
                                    else DamageCalculator.displayedStat(p, stat, state.field,
                                            attacker ? state.field.attackerSide : state.field.defenderSide);
                                }
                            }
                        }
                        continue;
                    }
                    assertNotNull(legacy ? before.calculateMove(true, 0) : state.calculateMove(true, 0));
                    if (mode.startsWith("eight")) for (int slot = 0; slot < 4; slot++) {
                        if (legacy) { before.calculateMove(true, slot); before.calculateMove(false, slot); }
                        else { state.calculateMove(true, slot); state.calculateMove(false, slot); }
                    }
                }
                long elapsed = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
                metrics.put(mode + (legacy ? "-before" : "-after"),
                        "ns/op=" + elapsed / iterations + ", allocated bytes/op=" + bytes / iterations);
            }
        }
        System.out.println("Damage cache benchmark: " + GSON.toJson(metrics));
        Path output = Path.of("build/reports/damage-cache-benchmark.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(metrics));
    }

    static DamageCalcState basicState() {
        DamageCalcState state = new DamageCalcState();
        state.attacker = pokemon("garchomp", PokeType.DRAGON, PokeType.GROUND, 95.0);
        state.defender = pokemon("snorlax", PokeType.NORMAL, PokeType.NONE, 460.0);
        state.attacker.ability = "Strong Jaw";
        state.defender.ability = "Thick Fat";
        state.attacker.moves.clear();
        state.attacker.moves.addAll(List.of(move("crunch", PokeType.DARK, 80, Set.of("bite")),
                move("earthquake", PokeType.GROUND, 100, Set.of()),
                move("icefang", PokeType.ICE, 65, Set.of("bite")),
                move("heavyslam", PokeType.STEEL, 1, Set.of())));
        state.defender.moves.clear();
        state.defender.moves.addAll(List.of(move("bodypress", PokeType.FIGHTING, 80, Set.of()),
                move("ragefist", PokeType.GHOST, 50, Set.of()),
                move("rollout", PokeType.ROCK, 30, Set.of()),
                move("lastrespects", PokeType.GHOST, 50, Set.of())));
        return state;
    }

    static PokemonSet pokemon(String id, PokeType first, PokeType second, double weight) {
        EnumMap<Stat, Integer> base = new EnumMap<>(Stat.class);
        int[] values = {108, 130, 95, 80, 85, 102};
        for (Stat stat : Stat.values()) base.put(stat, values[stat.ordinal()]);
        PokemonSet pokemon = new PokemonSet(new SpeciesData(id, id, first, second, base,
                false, "", id, List.of(), weight));
        pokemon.nature = new NatureData("serious", "Serious", null, null);
        return pokemon;
    }

    static MoveData move(String id, PokeType type, int power, Set<String> flags) {
        return new MoveData(id, id, type, DamageCategory.PHYSICAL, power,
                id.equals("earthquake"), true, flags);
    }

    static Map<String, DamageCalcState> scenarios() {
        Map<String, DamageCalcState> cases = new LinkedHashMap<>();
        cases.put("solo-basic", basicState());
        DamageCalcState random = basicState();
        random.attacker.level = 82; random.defender.level = 87;
        for (PokemonSet pokemon : List.of(random.attacker, random.defender)) {
            for (Stat stat : Stat.values()) pokemon.evs.put(stat, 85);
            pokemon.item = "Leftovers";
        }
        cases.put("random-85-ev", random);
        DamageCalcState multi = basicState();
        multi.attacker.moves.set(0, move("populationbomb", PokeType.NORMAL, 20, Set.of("multihit10", "multiaccuracy", "accuracy90")));
        multi.attacker.moves.set(1, move("tripleaxel", PokeType.ICE, 20, Set.of("multihit3", "multiaccuracy", "accuracy90")));
        multi.attacker.moves.set(2, move("bulletseed", PokeType.GRASS, 25, Set.of("multihit2to5")));
        multi.attacker.item = "Loaded Dice";
        cases.put("multi-hit", multi);
        DamageCalcState dynamic = basicState();
        dynamic.attacker.ability = "Protean"; dynamic.attacker.status = StatusCondition.BURN;
        dynamic.defender.timesHit = 4; dynamic.defender.faintedAllies = 3;
        dynamic.defender.lastMoveId = "rollout"; dynamic.defender.consecutiveMoveUses = 3;
        dynamic.defender.defenseCurlUsed = true; dynamic.defender.battleHistoryKnown = true;
        cases.put("dynamic-history", dynamic);
        DamageCalcState forms = basicState();
        forms.attacker.species = new SpeciesData("aegislashblade", "Aegislash Blade", PokeType.STEEL, PokeType.GHOST,
                forms.attacker.species.baseStats(), false, "", "aegislash", List.of("blade-forme"), 53.0);
        forms.defender.terastallized = true; forms.defender.teraType = PokeType.FAIRY;
        forms.attacker.zMoves[0] = true;
        cases.put("forms-tera-z", forms);
        Random rng = new Random(20260830L);
        for (int i = 0; i < 80; i++) {
            DamageCalcState state = basicState();
            if (i % 3 == 0) {
                state.attacker.moves.set(1, new MoveData("weatherball", "Weather Ball", PokeType.NORMAL,
                        DamageCategory.SPECIAL, 50, false, false));
                state.defender.moves.set(0, new MoveData("protect", "Protect", PokeType.NORMAL,
                        DamageCategory.STATUS, 0, false, false));
            }
            state.field.doubles = i % 2 == 0;
            state.field.weather = Weather.values()[i % Weather.values().length];
            state.field.terrain = Terrain.values()[i % Terrain.values().length];
            state.field.trickRoom = i % 3 == 0; state.field.gravity = i % 4 == 0;
            state.field.criticalHit = i % 7 == 0;
            state.field.attackerSide.helpingHand = i % 3 == 0;
            state.field.attackerSide.partnerAbility = i % 2 == 0 ? "Battery" : "Steely Spirit";
            state.field.defenderSide.friendGuard = i % 5 == 0;
            state.field.defenderSide.reflect = i % 4 == 0;
            state.field.defenderSide.lightScreen = i % 6 == 0;
            state.field.defenderSide.wideGuard = i % 11 == 0;
            state.field.attackerSide.spreadTargets = i % 3 == 0 ? 1 : 2;
            for (PokemonSet p : List.of(state.attacker, state.defender)) {
                p.level = List.of(5, 50, 82, 100).get(i % 4);
                p.status = StatusCondition.values()[i % StatusCondition.values().length];
                p.currentHp = i % 2 == 0 ? 40 : -1;
                p.item = List.of("Choice Band", "Life Orb", "Metronome", "Assault Vest").get(i % 4);
                p.ability = List.of("Intimidate", "Guts", "Swift Swim", "Protosynthesis", "Huge Power").get(i % 5);
                p.paradoxBoostActive = i % 3 == 0;
                p.itemKnown = i % 3 != 0; p.statsKnown = i % 4 != 0;
                p.nature = new NatureData("adamant", "Adamant", Stat.ATK, Stat.SPA);
                for (Stat stat : Stat.values()) {
                    p.evs.put(stat, rng.nextInt(253)); p.ivs.put(stat, rng.nextInt(32));
                    p.boosts.put(stat, stat == Stat.HP ? 0 : rng.nextInt(13) - 6);
                }
            }
            cases.put("conditions-" + i, state);
        }
        return cases;
    }
}
