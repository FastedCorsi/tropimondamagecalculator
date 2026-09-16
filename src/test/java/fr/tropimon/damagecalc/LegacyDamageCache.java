package fr.tropimon.damagecalc;

/** Frozen eager-cache algorithm from before the optimization; test/benchmark only. */
final class LegacyDamageCache {
    private final DamageCalcState state;
    private long fingerprint = Long.MIN_VALUE;
    private final DamageResult[][] results = new DamageResult[2][4];

    LegacyDamageCache(DamageCalcState state) { this.state = state; }

    DamageResult calculateMove(boolean fromAttacker, int slot) {
        if (slot < 0 || slot >= 4) return null;
        long current = fingerprint();
        if (current != fingerprint) {
            for (int i = 0; i < 4; i++) {
                results[0][i] = calculate(true, i);
                results[1][i] = calculate(false, i);
            }
            fingerprint = current;
        }
        return results[fromAttacker ? 0 : 1][slot];
    }

    private DamageResult calculate(boolean fromAttacker, int slot) {
        PokemonSet source = fromAttacker ? state.attacker : state.defender;
        return source.moveAt(slot) == null ? null : DamageCalculator.calculate(source,
                fromAttacker ? state.defender : state.attacker, DamageCalcState.effectiveMove(source, slot), state.field,
                fromAttacker ? state.field.attackerSide : state.field.defenderSide,
                fromAttacker ? state.field.defenderSide : state.field.attackerSide);
    }

    private long fingerprint() {
        FieldState f = state.field;
        long h = mix(pokemonFingerprint(state.attacker), pokemonFingerprint(state.defender));
        h = mix(h, f.weather.ordinal()); h = mix(h, f.terrain.ordinal());
        h = mix(h, f.doubles ? 1 : 0); h = mix(h, f.criticalHit ? 1 : 0);
        h = mix(h, f.trickRoom ? 1 : 0); h = mix(h, f.gravity ? 1 : 0);
        h = mix(h, f.helpingHand ? 1 : 0); h = mix(h, f.friendGuard ? 1 : 0);
        h = mix(h, f.reflect ? 1 : 0); h = mix(h, f.lightScreen ? 1 : 0);
        h = mix(h, f.auroraVeil ? 1 : 0); h = mix(h, f.tailwind ? 1 : 0);
        return mix(mix(h, sideFingerprint(f.attackerSide)), sideFingerprint(f.defenderSide));
    }

    private static long pokemonFingerprint(PokemonSet p) {
        long h = p.species.id().hashCode();
        h = mix(h, p.battleId.hashCode()); h = mix(h, p.level);
        h = mix(h, p.item.hashCode()); h = mix(h, p.ability.hashCode()); h = mix(h, p.nature.id().hashCode());
        h = mix(h, p.itemKnown ? 1 : 0); h = mix(h, p.abilityKnown ? 1 : 0);
        h = mix(h, p.natureKnown ? 1 : 0); h = mix(h, p.statsKnown ? 1 : 0); h = mix(h, p.movesKnown ? 1 : 0);
        h = mix(h, p.teraType.ordinal()); h = mix(h, p.terastallized ? 1 : 0); h = mix(h, p.status.ordinal());
        h = mix(h, p.currentHp); h = mix(h, p.observedMaxHp);
        for (Stat stat : Stat.values()) {
            h = mix(h, p.evs.get(stat)); h = mix(h, p.ivs.get(stat)); h = mix(h, p.boosts.get(stat));
        }
        for (int i = 0; i < 4; i++) {
            h = mix(h, p.moveAt(i) == null ? 0 : p.moveAt(i).hashCode()); h = mix(h, p.zMoveAt(i) ? 1 : 0);
        }
        h = mix(h, p.battleHistoryKnown ? 1 : 0); h = mix(h, p.timesHit); h = mix(h, p.faintedAllies);
        h = mix(h, p.lastMoveId.hashCode()); h = mix(h, p.consecutiveMoveUses); h = mix(h, p.echoedVoiceChain);
        h = mix(h, p.defenseCurlUsed ? 1 : 0); h = mix(h, p.switchedInThisTurn ? 1 : 0);
        h = mix(h, p.allyFaintedPreviousTurn ? 1 : 0); h = mix(h, p.lastMoveFailed ? 1 : 0);
        h = mix(h, p.flashFireActive ? 1 : 0); h = mix(h, p.paradoxBoostActive ? 1 : 0);
        h = mix(h, p.turnsActive); h = mix(h, p.lastDamageTaken);
        return mix(h, p.lastDamageCategory.ordinal());
    }

    private static long sideFingerprint(SideConditions s) {
        int bits = (s.reflect ? 1 : 0) | (s.lightScreen ? 2 : 0) | (s.auroraVeil ? 4 : 0) | (s.tailwind ? 8 : 0)
                | (s.helpingHand ? 16 : 0) | (s.friendGuard ? 32 : 0) | (s.wideGuard ? 64 : 0);
        return mix(mix(bits, s.partnerAbility.hashCode()), s.spreadTargets);
    }

    private static long mix(long hash, long value) { return hash * 31L + value; }
}
