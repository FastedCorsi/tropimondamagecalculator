package fr.tropimon.damagecalc;

import net.minecraft.util.Language;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact input snapshots: callers (including reflective callers) may mutate the models directly. */
final class DamageCalculationCache {
    private static final int SHARED_LIMIT = 128;
    private static final Stat[] STATS = Stat.values();
    private static final Map<MoveKey, DamageResult> SHARED = new LinkedHashMap<>(SHARED_LIMIT, 0.75f, true);
    private static volatile long generation;

    private Inputs inputs;
    private final MoveKey[][] keys = new MoveKey[2][4];
    private final DamageResult[][] results = new DamageResult[2][4];
    private final int[][] totals = new int[2][];
    private long calculations;

    static synchronized void clearShared() {
        SHARED.clear();
        generation++;
    }

    boolean prepare(DamageCalcState state) {
        if (inputs != null && inputs.matches(state)) return false;
        inputs = new Inputs(PokemonInputs.capture(state.attacker), PokemonInputs.capture(state.defender),
                FieldInputs.capture(state.field), Language.getInstance(), generation);
        totals[0] = null;
        totals[1] = null;
        return true;
    }

    DamageResult calculatePrepared(DamageCalcState state, boolean fromAttacker, int slot) {
        if (slot < 0 || slot >= 4) return null;
        int side = fromAttacker ? 0 : 1;
        PokemonSet source = fromAttacker ? state.attacker : state.defender;
        MoveData move = source.moveAt(slot);
        boolean zMove = source.zMoveAt(slot);
        MoveKey previous = keys[side][slot];
        if (previous != null && previous.inputs == inputs && previous.zMove == zMove
                && Objects.equals(previous.move, move)) return results[side][slot];
        MoveKey key = new MoveKey(inputs, fromAttacker, move, zMove);
        DamageResult result = null;
        if (move != null) {
            result = findShared(key);
            if (result == null) {
                result = DamageCalculator.calculate(source, fromAttacker ? state.defender : state.attacker,
                        DamageCalcState.effectiveMove(source, slot), state.field,
                        fromAttacker ? state.field.attackerSide : state.field.defenderSide,
                        fromAttacker ? state.field.defenderSide : state.field.attackerSide);
                // Shared values cannot expose the calculator's mutable working lists.
                result = new DamageResult(result.move(), result.minDamage(), result.maxDamage(), result.minPercent(),
                        result.maxPercent(), List.copyOf(result.rolls()), result.entryDamage(), result.koChance(),
                        result.showdownLine(), result.shortLine(), List.copyOf(result.notes()), List.copyOf(result.warnings()));
                calculations++;
                storeShared(key, result);
            }
        }
        keys[side][slot] = key;
        results[side][slot] = result;
        return result;
    }

    int statPrepared(DamageCalcState state, boolean fromAttacker, Stat stat) {
        int side = fromAttacker ? 0 : 1;
        if (totals[side] == null) {
            PokemonSet pokemon = fromAttacker ? state.attacker : state.defender;
            SideConditions conditions = fromAttacker ? state.field.attackerSide : state.field.defenderSide;
            int[] values = new int[STATS.length];
            for (Stat value : STATS) values[value.ordinal()] = value == Stat.HP ? pokemon.maxHp()
                    : DamageCalculator.displayedStat(pokemon, value, state.field, conditions);
            totals[side] = values;
        }
        return totals[side][stat.ordinal()];
    }

    long calculationCount() { return calculations; }

    private static synchronized DamageResult findShared(MoveKey key) { return SHARED.get(key); }

    private static synchronized void storeShared(MoveKey key, DamageResult result) {
        if (key.inputs.generation != generation) return;
        SHARED.put(key, result);
        if (SHARED.size() > SHARED_LIMIT) SHARED.remove(SHARED.keySet().iterator().next());
    }

    private record MoveKey(Inputs inputs, boolean fromAttacker, MoveData move, boolean zMove) {}

    private record Inputs(PokemonInputs attacker, PokemonInputs defender, FieldInputs field,
                          Language language, long generation, int cachedHash) {
        Inputs(PokemonInputs attacker, PokemonInputs defender, FieldInputs field, Language language, long generation) {
            this(attacker, defender, field, language, generation, Objects.hash(attacker, defender, field, language, generation));
        }
        @Override public int hashCode() { return cachedHash; }

        boolean matches(DamageCalcState state) {
            return generation == DamageCalculationCache.generation && language == Language.getInstance()
                    && attacker.matches(state.attacker) && defender.matches(state.defender) && field.matches(state.field);
        }
    }

    private record StatValues(int hp, int atk, int def, int spa, int spd, int spe) {
        static StatValues capture(EnumMap<Stat, Integer> values) {
            return new StatValues(values.get(Stat.HP), values.get(Stat.ATK), values.get(Stat.DEF),
                    values.get(Stat.SPA), values.get(Stat.SPD), values.get(Stat.SPE));
        }
        boolean matches(EnumMap<Stat, Integer> values) {
            return hp == values.get(Stat.HP) && atk == values.get(Stat.ATK) && def == values.get(Stat.DEF)
                    && spa == values.get(Stat.SPA) && spd == values.get(Stat.SPD) && spe == values.get(Stat.SPE);
        }
    }

    private record History(boolean known, int timesHit, int faintedAllies, String lastMove, int consecutiveUses,
                           int echoedVoice, boolean defenseCurl, boolean switchedIn, boolean allyFainted,
                           boolean moveFailed, boolean flashFire, boolean paradox, int turnsActive,
                           int lastDamage, DamageCategory damageCategory) {
        static History capture(PokemonSet p) {
            return new History(p.battleHistoryKnown, p.timesHit, p.faintedAllies, p.lastMoveId, p.consecutiveMoveUses,
                    p.echoedVoiceChain, p.defenseCurlUsed, p.switchedInThisTurn, p.allyFaintedPreviousTurn,
                    p.lastMoveFailed, p.flashFireActive, p.paradoxBoostActive, p.turnsActive, p.lastDamageTaken, p.lastDamageCategory);
        }
        boolean matches(PokemonSet p) {
            return known == p.battleHistoryKnown && timesHit == p.timesHit && faintedAllies == p.faintedAllies
                    && Objects.equals(lastMove, p.lastMoveId) && consecutiveUses == p.consecutiveMoveUses
                    && echoedVoice == p.echoedVoiceChain && defenseCurl == p.defenseCurlUsed
                    && switchedIn == p.switchedInThisTurn && allyFainted == p.allyFaintedPreviousTurn
                    && moveFailed == p.lastMoveFailed && flashFire == p.flashFireActive && paradox == p.paradoxBoostActive
                    && turnsActive == p.turnsActive && lastDamage == p.lastDamageTaken && damageCategory == p.lastDamageCategory;
        }
    }

    private record PokemonInputs(SpeciesData species, int level, String item, String ability, NatureData nature,
                                 int known, PokeType tera, boolean terastallized, StatusCondition status,
                                 int hp, int maxHp, StatValues evs, StatValues ivs, StatValues boosts, History history) {
        static PokemonInputs capture(PokemonSet p) {
            SpeciesData s = p.species;
            SpeciesData snapshot = new SpeciesData(s.id(), s.name(), s.primaryType(), s.secondaryType(),
                    new EnumMap<>(s.baseStats()), s.notFullyEvolved(), s.texturePath(), s.cobblemonSpeciesId(),
                    List.copyOf(s.aspects()), s.weightKg());
            return new PokemonInputs(snapshot, p.level, p.item, p.ability, p.nature, known(p), p.teraType,
                    p.terastallized, p.status, p.currentHp, p.observedMaxHp, StatValues.capture(p.evs),
                    StatValues.capture(p.ivs), StatValues.capture(p.boosts), History.capture(p));
        }
        private static int known(PokemonSet p) {
            return (p.itemKnown ? 1 : 0) | (p.abilityKnown ? 2 : 0) | (p.natureKnown ? 4 : 0)
                    | (p.statsKnown ? 8 : 0) | (p.movesKnown ? 16 : 0);
        }
        boolean matches(PokemonSet p) {
            return level == p.level && hp == p.currentHp && maxHp == p.observedMaxHp && known == known(p)
                    && tera == p.teraType && terastallized == p.terastallized && status == p.status
                    && Objects.equals(item, p.item) && Objects.equals(ability, p.ability) && Objects.equals(nature, p.nature)
                    && species.equals(p.species) && evs.matches(p.evs) && ivs.matches(p.ivs) && boosts.matches(p.boosts)
                    && history.matches(p);
        }
    }

    private record SideInputs(int flags, String ability, int targets) {
        private static int flags(SideConditions s) {
            return (s.reflect ? 1 : 0) | (s.lightScreen ? 2 : 0) | (s.auroraVeil ? 4 : 0) | (s.tailwind ? 8 : 0)
                    | (s.helpingHand ? 16 : 0) | (s.friendGuard ? 32 : 0) | (s.wideGuard ? 64 : 0);
        }
        static SideInputs capture(SideConditions s) { return new SideInputs(flags(s), s.partnerAbility, s.spreadTargets); }
        boolean matches(SideConditions s) {
            return flags == flags(s) && Objects.equals(ability, s.partnerAbility) && targets == s.spreadTargets;
        }
    }

    private record FieldInputs(Weather weather, Terrain terrain, int flags, SideInputs attacker, SideInputs defender) {
        private static int flags(FieldState f) {
            return (f.doubles ? 1 : 0) | (f.criticalHit ? 2 : 0) | (f.trickRoom ? 4 : 0) | (f.gravity ? 8 : 0)
                    | (f.helpingHand ? 16 : 0) | (f.friendGuard ? 32 : 0) | (f.reflect ? 64 : 0)
                    | (f.lightScreen ? 128 : 0) | (f.auroraVeil ? 256 : 0) | (f.tailwind ? 512 : 0);
        }
        static FieldInputs capture(FieldState f) {
            return new FieldInputs(f.weather, f.terrain, flags(f), SideInputs.capture(f.attackerSide), SideInputs.capture(f.defenderSide));
        }
        boolean matches(FieldState f) {
            return weather == f.weather && terrain == f.terrain && flags == flags(f)
                    && attacker.matches(f.attackerSide) && defender.matches(f.defenderSide);
        }
    }
}
