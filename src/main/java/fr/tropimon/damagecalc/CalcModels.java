package fr.tropimon.damagecalc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.Language;

enum PokeType {
    NORMAL, FIRE, WATER, ELECTRIC, GRASS, ICE, FIGHTING, POISON, GROUND, FLYING,
    PSYCHIC, BUG, ROCK, GHOST, DRAGON, DARK, STEEL, FAIRY, NONE;

    static PokeType byName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        String normalized = name.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (PokeType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return NONE;
    }

    String displayName() {
        if (this == NONE) {
            return "-";
        }
        String lower = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}

enum DamageCategory {
    PHYSICAL, SPECIAL, STATUS
}

enum Stat {
    HP, ATK, DEF, SPA, SPD, SPE
}

enum Weather {
    NONE("None"), SUN("Sun"), RAIN("Rain"), SAND("Sand"), SNOW("Snow");

    final String label;

    Weather(String label) {
        this.label = label;
    }
}

enum Terrain {
    NONE("None"), ELECTRIC("Electric"), GRASSY("Grassy"), MISTY("Misty"), PSYCHIC("Psychic");

    final String label;

    Terrain(String label) {
        this.label = label;
    }
}

enum StatusCondition {
    NONE("Statut"), BURN("BRN"), POISON("PSN"), PARALYSIS("PAR"), SLEEP("SLP"), FREEZE("FRZ");

    final String label;

    StatusCondition(String label) {
        this.label = label;
    }
}

record SpeciesData(String id, String name, PokeType primaryType, PokeType secondaryType, EnumMap<Stat, Integer> baseStats,
                   boolean notFullyEvolved, String texturePath, String cobblemonSpeciesId, List<String> aspects,
                   double weightKg) {
    SpeciesData(String id, String name, PokeType primaryType, PokeType secondaryType, EnumMap<Stat, Integer> baseStats,
                boolean notFullyEvolved, String texturePath) {
        this(id, name, primaryType, secondaryType, baseStats, notFullyEvolved, texturePath, id, List.of(), 0.0);
    }

    SpeciesData(String id, String name, PokeType primaryType, PokeType secondaryType, EnumMap<Stat, Integer> baseStats,
                boolean notFullyEvolved, String texturePath, String cobblemonSpeciesId, List<String> aspects) {
        this(id, name, primaryType, secondaryType, baseStats, notFullyEvolved, texturePath,
                cobblemonSpeciesId, aspects, 0.0);
    }

    List<PokeType> types() {
        ArrayList<PokeType> types = new ArrayList<>();
        if (primaryType != PokeType.NONE) {
            types.add(primaryType);
        }
        if (secondaryType != PokeType.NONE && secondaryType != primaryType) {
            types.add(secondaryType);
        }
        return types;
    }
}

record MoveData(String id, String name, PokeType type, DamageCategory category, int basePower, boolean spreadMove,
                boolean contact, Set<String> flags, int priority) {
    MoveData(String id, String name, PokeType type, DamageCategory category, int basePower, boolean spreadMove,
             boolean contact) {
        this(id, name, type, category, basePower, spreadMove, contact,
                contact ? Set.of("contact") : Set.of(), 0);
    }

    MoveData(String id, String name, PokeType type, DamageCategory category, int basePower, boolean spreadMove,
             boolean contact, Set<String> flags) {
        this(id, name, type, category, basePower, spreadMove, contact, flags, 0);
    }

    MoveData {
        LinkedHashSet<String> normalizedFlags = new LinkedHashSet<>();
        if (flags != null) {
            for (String flag : flags) {
                String normalized = TropimonDex.normalize(flag);
                if (!normalized.isBlank()) normalizedFlags.add(normalized);
            }
        }
        if (contact) normalizedFlags.add("contact");
        flags = Set.copyOf(normalizedFlags);
        contact = normalizedFlags.contains("contact");
    }

    boolean hasFlag(String flag) {
        return flags.contains(TropimonDex.normalize(flag));
    }
}

record BattlePokemonSnapshot(PokemonSet player, PokemonSet opponent, boolean doubles,
                             String playerPartnerAbility, String opponentPartnerAbility,
                             String playerPartnerName, String opponentPartnerName,
                             int playerActiveCount, int opponentActiveCount) {
    BattlePokemonSnapshot(PokemonSet player, PokemonSet opponent, boolean doubles) {
        this(player, opponent, doubles, null, null, null, null,
                doubles ? 2 : 1, doubles ? 2 : 1);
    }

    BattlePokemonSnapshot(PokemonSet player, PokemonSet opponent, boolean doubles,
                          String playerPartnerAbility, String opponentPartnerAbility,
                          int playerActiveCount, int opponentActiveCount) {
        this(player, opponent, doubles, playerPartnerAbility, opponentPartnerAbility,
                null, null, playerActiveCount, opponentActiveCount);
    }

    static BattlePokemonSnapshot empty() {
        return new BattlePokemonSnapshot(null, null, false);
    }

    boolean hasAny() {
        return player != null || opponent != null;
    }
}

record NatureData(String id, String name, Stat plus, Stat minus) {
    double modifier(Stat stat) {
        if (stat == Stat.HP) {
            return 1.0;
        }
        if (stat == plus) {
            return 1.1;
        }
        if (stat == minus) {
            return 0.9;
        }
        return 1.0;
    }
}

final class PokemonSet {
    SpeciesData species;
    String battleId = "";
    String battleName = "";
    int level = 100;
    String item = "None";
    String ability = "None";
    NatureData nature = TropimonDex.nature("serious");
    boolean itemKnown = true;
    boolean abilityKnown = true;
    boolean natureKnown = true;
    boolean statsKnown = true;
    boolean movesKnown = true;
    PokeType teraType = PokeType.NONE;
    boolean terastallized;
    StatusCondition status = StatusCondition.NONE;
    int currentHp = -1;
    int observedMaxHp = -1;
    final EnumMap<Stat, Integer> evs = new EnumMap<>(Stat.class);
    final EnumMap<Stat, Integer> ivs = new EnumMap<>(Stat.class);
    final EnumMap<Stat, Integer> boosts = new EnumMap<>(Stat.class);
    final List<MoveData> moves = new ArrayList<>();
    final boolean[] zMoves = new boolean[4];
    boolean battleHistoryKnown;
    int timesHit;
    int faintedAllies;
    String lastMoveId = "";
    int consecutiveMoveUses;
    int echoedVoiceChain;
    boolean defenseCurlUsed;
    boolean switchedInThisTurn;
    boolean allyFaintedPreviousTurn;
    boolean lastMoveFailed;
    boolean flashFireActive;
    boolean paradoxBoostActive;
    int turnsActive = -1;
    int lastDamageTaken;
    DamageCategory lastDamageCategory = DamageCategory.STATUS;

    PokemonSet(SpeciesData species) {
        this.species = species;
        for (Stat stat : Stat.values()) {
            evs.put(stat, 0);
            ivs.put(stat, 31);
            boosts.put(stat, 0);
        }
        ability = TropimonDex.defaultAbility(species);
        moves.addAll(TropimonDex.defaultMoves(species));
    }

    PokemonSet copy() {
        PokemonSet copy = new PokemonSet(species);
        copy.battleId = battleId;
        copy.battleName = battleName;
        copy.level = level;
        copy.item = item;
        copy.ability = ability;
        copy.nature = nature;
        copy.itemKnown = itemKnown;
        copy.abilityKnown = abilityKnown;
        copy.natureKnown = natureKnown;
        copy.statsKnown = statsKnown;
        copy.movesKnown = movesKnown;
        copy.teraType = teraType;
        copy.terastallized = terastallized;
        copy.status = status;
        copy.currentHp = currentHp;
        copy.observedMaxHp = observedMaxHp;
        copy.evs.clear();
        copy.evs.putAll(evs);
        copy.ivs.clear();
        copy.ivs.putAll(ivs);
        copy.boosts.clear();
        copy.boosts.putAll(boosts);
        copy.moves.clear();
        copy.moves.addAll(moves);
        System.arraycopy(zMoves, 0, copy.zMoves, 0, zMoves.length);
        copy.battleHistoryKnown = battleHistoryKnown;
        copy.timesHit = timesHit;
        copy.faintedAllies = faintedAllies;
        copy.lastMoveId = lastMoveId;
        copy.consecutiveMoveUses = consecutiveMoveUses;
        copy.echoedVoiceChain = echoedVoiceChain;
        copy.defenseCurlUsed = defenseCurlUsed;
        copy.switchedInThisTurn = switchedInThisTurn;
        copy.allyFaintedPreviousTurn = allyFaintedPreviousTurn;
        copy.lastMoveFailed = lastMoveFailed;
        copy.flashFireActive = flashFireActive;
        copy.paradoxBoostActive = paradoxBoostActive;
        copy.turnsActive = turnsActive;
        copy.lastDamageTaken = lastDamageTaken;
        copy.lastDamageCategory = lastDamageCategory;
        return copy;
    }

    MoveData moveAt(int slot) {
        return slot >= 0 && slot < moves.size() ? moves.get(slot) : null;
    }

    void setMove(int slot, MoveData move) {
        if (slot < 0) {
            return;
        }
        while (moves.size() <= slot) {
            moves.add(null);
        }
        moves.set(slot, move);
        movesKnown = true;
        if (slot < zMoves.length) {
            zMoves[slot] = false;
        }
    }

    void deleteMove(int slot) {
        if (slot >= 0 && slot < moves.size()) {
            moves.set(slot, null);
        }
        if (slot >= 0 && slot < zMoves.length) {
            zMoves[slot] = false;
        }
    }

    boolean zMoveAt(int slot) {
        return slot >= 0 && slot < zMoves.length && zMoves[slot];
    }

    void toggleZMove(int slot) {
        if (slot >= 0 && slot < zMoves.length && moveAt(slot) != null) {
            zMoves[slot] = !zMoves[slot];
        }
    }

    void clearBattleContext() {
        battleId = "";
        battleName = "";
        currentHp = -1;
        observedMaxHp = -1;
        status = StatusCondition.NONE;
        terastallized = false;
        teraType = PokeType.NONE;
        boosts.replaceAll((stat, value) -> 0);
        battleHistoryKnown = false;
        timesHit = 0;
        faintedAllies = 0;
        lastMoveId = "";
        consecutiveMoveUses = 0;
        echoedVoiceChain = 0;
        defenseCurlUsed = false;
        switchedInThisTurn = false;
        allyFaintedPreviousTurn = false;
        lastMoveFailed = false;
        flashFireActive = false;
        paradoxBoostActive = false;
        turnsActive = -1;
        lastDamageTaken = 0;
        lastDamageCategory = DamageCategory.STATUS;
    }

    int maxHp() {
        return observedMaxHp > 0 ? observedMaxHp : calculatedMaxHp();
    }

    int calculatedMaxHp() {
        return DamageCalculator.stat(this, Stat.HP, false);
    }

    int visibleHp() {
        return currentHp >= 0 ? Math.min(currentHp, maxHp()) : maxHp();
    }

    String evSummary() {
        return evs.get(Stat.HP) + " HP / " + evs.get(Stat.ATK) + " Atk / " + evs.get(Stat.DEF) + " Def / "
                + evs.get(Stat.SPA) + " SpA / " + evs.get(Stat.SPD) + " SpD / " + evs.get(Stat.SPE) + " Spe";
    }

    List<PokeType> defensiveTypes() {
        if (terastallized && teraType != PokeType.NONE) {
            return List.of(teraType);
        }
        return species.types();
    }
}

final class FieldState {
    Weather weather = Weather.NONE;
    Terrain terrain = Terrain.NONE;
    boolean doubles;
    boolean criticalHit;
    boolean trickRoom;
    boolean gravity;
    boolean helpingHand;
    boolean friendGuard;

    final SideConditions attackerSide = new SideConditions();
    final SideConditions defenderSide = new SideConditions();

    // Legacy global side conditions are kept for direct calculator tests and older callers.
    boolean reflect;
    boolean lightScreen;
    boolean auroraVeil;
    boolean tailwind;

    SideConditions legacySideConditions() {
        SideConditions side = new SideConditions();
        side.reflect = reflect;
        side.lightScreen = lightScreen;
        side.auroraVeil = auroraVeil;
        side.tailwind = tailwind;
        return side;
    }

    void swapSides() {
        attackerSide.swapWith(defenderSide);
    }
}

final class SideConditions {
    boolean reflect;
    boolean lightScreen;
    boolean auroraVeil;
    boolean tailwind;
    boolean helpingHand;
    boolean friendGuard;
    boolean wideGuard;
    String partnerAbility = "None";
    String partnerName = "";
    int spreadTargets = 2;

    boolean hasAny() {
        return reflect || lightScreen || auroraVeil || tailwind || helpingHand || friendGuard || wideGuard
                || !"none".equals(TropimonDex.normalize(partnerAbility));
    }

    String summary() {
        ArrayList<String> parts = new ArrayList<>();
        if (reflect) parts.add("Reflect");
        if (lightScreen) parts.add("LS");
        if (auroraVeil) parts.add("Veil");
        if (tailwind) parts.add("Tailwind");
        if (helpingHand) parts.add("HH");
        if (friendGuard) parts.add("FG");
        if (wideGuard) parts.add("Wide Guard");
        if (!"none".equals(TropimonDex.normalize(partnerAbility))) parts.add("Partner: " + partnerAbility);
        if (spreadTargets != 2) parts.add("Targets: " + spreadTargets);
        return String.join(" / ", parts);
    }

    void swapWith(SideConditions other) {
        boolean previousReflect = reflect;
        boolean previousLightScreen = lightScreen;
        boolean previousAuroraVeil = auroraVeil;
        boolean previousTailwind = tailwind;
        boolean previousHelpingHand = helpingHand;
        boolean previousFriendGuard = friendGuard;
        boolean previousWideGuard = wideGuard;
        String previousPartnerAbility = partnerAbility;
        String previousPartnerName = partnerName;
        int previousSpreadTargets = spreadTargets;

        reflect = other.reflect;
        lightScreen = other.lightScreen;
        auroraVeil = other.auroraVeil;
        tailwind = other.tailwind;
        helpingHand = other.helpingHand;
        friendGuard = other.friendGuard;
        wideGuard = other.wideGuard;
        partnerAbility = other.partnerAbility;
        partnerName = other.partnerName;
        spreadTargets = other.spreadTargets;

        other.reflect = previousReflect;
        other.lightScreen = previousLightScreen;
        other.auroraVeil = previousAuroraVeil;
        other.tailwind = previousTailwind;
        other.helpingHand = previousHelpingHand;
        other.friendGuard = previousFriendGuard;
        other.wideGuard = previousWideGuard;
        other.partnerAbility = previousPartnerAbility;
        other.partnerName = previousPartnerName;
        other.spreadTargets = previousSpreadTargets;
    }
}

record CalcWarning(String key, List<String> arguments) {
    CalcWarning {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    static CalcWarning of(String key, String... arguments) {
        return new CalcWarning(key, arguments == null ? List.of() : List.of(arguments));
    }
}

record DamageResult(
        MoveData move,
        int minDamage,
        int maxDamage,
        double minPercent,
        double maxPercent,
        List<Integer> rolls,
        int entryDamage,
        String koChance,
        String showdownLine,
        String shortLine,
        List<String> notes,
        List<CalcWarning> warnings
) {
    static DamageResult zero(MoveData move, String reason) {
        return zero(move, reason, List.of());
    }

    static DamageResult zero(MoveData move, String reason, List<CalcWarning> warnings) {
        return new DamageResult(move, 0, 0, 0, 0, List.of(), 0, "no damage", move.name() + ": 0 damage -- " + reason,
                move.name() + " 0% | " + reason, List.of(reason), List.copyOf(warnings));
    }
}

final class DamageCalcState {
    private static final DamageCalcState SHARED = new DamageCalcState();

    PokemonSet attacker = new PokemonSet(TropimonDex.species("abomasnow"));
    PokemonSet defender = new PokemonSet(TropimonDex.species("abomasnow"));
    FieldState field = new FieldState();
    String attackerSearch = "";
    String defenderSearch = "";
    String attackerItemSearch = "";
    String defenderItemSearch = "";
    String attackerAbilitySearch = "";
    String defenderAbilitySearch = "";
    String attackerNatureSearch = "";
    String defenderNatureSearch = "";
    String attackerPartnerAbilitySearch = "";
    String defenderPartnerAbilitySearch = "";
    private long damageCacheFingerprint = Long.MIN_VALUE;
    private final DamageResult[][] damageCache = new DamageResult[2][4];

    DamageCalcState() {
        attacker.ability = TropimonDex.defaultAbility(attacker.species);
        defender.ability = TropimonDex.defaultAbility(defender.species);
    }

    static DamageCalcState shared() {
        return SHARED;
    }

    DamageResult calculateMove(boolean fromAttacker, int slot) {
        if (slot < 0 || slot >= 4) {
            return null;
        }
        refreshDamageCache();
        return damageCache[fromAttacker ? 0 : 1][slot];
    }

    long calculationFingerprint() {
        long hash = pokemonFingerprint(attacker);
        hash = mix(hash, pokemonFingerprint(defender));
        hash = mix(hash, field.weather.ordinal());
        hash = mix(hash, field.terrain.ordinal());
        hash = mix(hash, field.doubles ? 1 : 0);
        hash = mix(hash, field.criticalHit ? 1 : 0);
        hash = mix(hash, field.trickRoom ? 1 : 0);
        hash = mix(hash, field.gravity ? 1 : 0);
        hash = mix(hash, field.helpingHand ? 1 : 0);
        hash = mix(hash, field.friendGuard ? 1 : 0);
        hash = mix(hash, field.reflect ? 1 : 0);
        hash = mix(hash, field.lightScreen ? 1 : 0);
        hash = mix(hash, field.auroraVeil ? 1 : 0);
        hash = mix(hash, field.tailwind ? 1 : 0);
        hash = mix(hash, sideFingerprint(field.attackerSide));
        return mix(hash, sideFingerprint(field.defenderSide));
    }

    private void refreshDamageCache() {
        long fingerprint = calculationFingerprint();
        if (fingerprint == damageCacheFingerprint) {
            return;
        }
        for (int slot = 0; slot < 4; slot++) {
            damageCache[0][slot] = calculateMoveUncached(true, slot);
            damageCache[1][slot] = calculateMoveUncached(false, slot);
        }
        damageCacheFingerprint = fingerprint;
    }

    private DamageResult calculateMoveUncached(boolean fromAttacker, int slot) {
        PokemonSet source = fromAttacker ? attacker : defender;
        PokemonSet target = fromAttacker ? defender : attacker;
        SideConditions attackerSide = fromAttacker ? field.attackerSide : field.defenderSide;
        SideConditions targetSide = fromAttacker ? field.defenderSide : field.attackerSide;
        MoveData move = source.moveAt(slot);
        return move == null ? null : DamageCalculator.calculate(source, target, effectiveMove(source, slot), field, attackerSide, targetSide);
    }

    static long pokemonFingerprint(PokemonSet pokemon) {
        long hash = pokemon.species.id().hashCode();
        hash = mix(hash, pokemon.battleId.hashCode());
        hash = mix(hash, pokemon.level);
        hash = mix(hash, pokemon.item.hashCode());
        hash = mix(hash, pokemon.ability.hashCode());
        hash = mix(hash, pokemon.nature.id().hashCode());
        hash = mix(hash, pokemon.itemKnown ? 1 : 0);
        hash = mix(hash, pokemon.abilityKnown ? 1 : 0);
        hash = mix(hash, pokemon.natureKnown ? 1 : 0);
        hash = mix(hash, pokemon.statsKnown ? 1 : 0);
        hash = mix(hash, pokemon.movesKnown ? 1 : 0);
        hash = mix(hash, pokemon.teraType.ordinal());
        hash = mix(hash, pokemon.terastallized ? 1 : 0);
        hash = mix(hash, pokemon.status.ordinal());
        hash = mix(hash, pokemon.currentHp);
        hash = mix(hash, pokemon.observedMaxHp);
        for (Stat stat : Stat.values()) {
            hash = mix(hash, pokemon.evs.get(stat));
            hash = mix(hash, pokemon.ivs.get(stat));
            hash = mix(hash, pokemon.boosts.get(stat));
        }
        for (int slot = 0; slot < 4; slot++) {
            MoveData move = pokemon.moveAt(slot);
            hash = mix(hash, move == null ? 0 : move.hashCode());
            hash = mix(hash, pokemon.zMoveAt(slot) ? 1 : 0);
        }
        hash = mix(hash, pokemon.battleHistoryKnown ? 1 : 0);
        hash = mix(hash, pokemon.timesHit);
        hash = mix(hash, pokemon.faintedAllies);
        hash = mix(hash, pokemon.lastMoveId.hashCode());
        hash = mix(hash, pokemon.consecutiveMoveUses);
        hash = mix(hash, pokemon.echoedVoiceChain);
        hash = mix(hash, pokemon.defenseCurlUsed ? 1 : 0);
        hash = mix(hash, pokemon.switchedInThisTurn ? 1 : 0);
        hash = mix(hash, pokemon.allyFaintedPreviousTurn ? 1 : 0);
        hash = mix(hash, pokemon.lastMoveFailed ? 1 : 0);
        hash = mix(hash, pokemon.flashFireActive ? 1 : 0);
        hash = mix(hash, pokemon.paradoxBoostActive ? 1 : 0);
        hash = mix(hash, pokemon.turnsActive);
        hash = mix(hash, pokemon.lastDamageTaken);
        hash = mix(hash, pokemon.lastDamageCategory.ordinal());
        return hash;
    }

    private static long sideFingerprint(SideConditions side) {
        int bits = 0;
        if (side.reflect) bits |= 1;
        if (side.lightScreen) bits |= 2;
        if (side.auroraVeil) bits |= 4;
        if (side.tailwind) bits |= 8;
        if (side.helpingHand) bits |= 16;
        if (side.friendGuard) bits |= 32;
        if (side.wideGuard) bits |= 64;
        long hash = bits;
        hash = mix(hash, side.partnerAbility.hashCode());
        return mix(hash, side.spreadTargets);
    }

    private static long mix(long hash, long value) {
        return hash * 31L + value;
    }

    static MoveData displayMove(PokemonSet source, int slot) {
        MoveData move = source.moveAt(slot);
        if (move == null || !source.zMoveAt(slot)) {
            return move;
        }
        if (move.category() == DamageCategory.STATUS) {
            return new MoveData("z" + move.id(), zStatusMoveName(move), move.type(), move.category(), 0, false, false);
        }
        return new MoveData("z" + move.id(), zDamagingMoveName(move.type()), move.type(), move.category(), zMovePower(move.basePower()), false, false);
    }

    static MoveData effectiveMove(PokemonSet source, int slot) {
        MoveData move = source.moveAt(slot);
        if (move == null || !source.zMoveAt(slot) || move.category() == DamageCategory.STATUS) {
            return move;
        }
        return new MoveData("z" + move.id(), zDamagingMoveName(move.type()), move.type(), move.category(), zMovePower(move.basePower()), false, false);
    }

    private static String zStatusMoveName(MoveData move) {
        String key = "cobblemon.move.z" + move.id();
        Language language = Language.getInstance();
        if (language.hasTranslation(key)) {
            String translated = language.get(key);
            if (!translated.isBlank() && !translated.equals(key)) {
                return translated;
            }
        }
        return move.name() + " Z";
    }

    private static String zDamagingMoveName(PokeType type) {
        String id = switch (type) {
            case NORMAL -> "breakneckblitz";
            case FIRE -> "infernooverdrive";
            case WATER -> "hydrovortex";
            case ELECTRIC -> "gigavolthavoc";
            case GRASS -> "bloomdoom";
            case ICE -> "subzeroslammer";
            case FIGHTING -> "alloutpummeling";
            case POISON -> "aciddownpour";
            case GROUND -> "tectonicrage";
            case FLYING -> "supersonicskystrike";
            case PSYCHIC -> "shatteredpsyche";
            case BUG -> "savagespinout";
            case ROCK -> "continentalcrush";
            case GHOST -> "neverendingnightmare";
            case DRAGON -> "devastatingdrake";
            case DARK -> "blackholeeclipse";
            case STEEL -> "corkscrewcrash";
            case FAIRY -> "twinkletackle";
            case NONE -> "breakneckblitz";
        };
        String key = "cobblemon.move." + id;
        Language language = Language.getInstance();
        if (language.hasTranslation(key)) {
            String translated = language.get(key);
            if (!translated.isBlank() && !translated.equals(key)) {
                return translated;
            }
        }
        return switch (type) {
            case NORMAL -> "Breakneck Blitz";
            case FIRE -> "Inferno Overdrive";
            case WATER -> "Hydro Vortex";
            case ELECTRIC -> "Gigavolt Havoc";
            case GRASS -> "Bloom Doom";
            case ICE -> "Subzero Slammer";
            case FIGHTING -> "All-Out Pummeling";
            case POISON -> "Acid Downpour";
            case GROUND -> "Tectonic Rage";
            case FLYING -> "Supersonic Skystrike";
            case PSYCHIC -> "Shattered Psyche";
            case BUG -> "Savage Spin-Out";
            case ROCK -> "Continental Crush";
            case GHOST -> "Never-Ending Nightmare";
            case DRAGON -> "Devastating Drake";
            case DARK -> "Black Hole Eclipse";
            case STEEL -> "Corkscrew Crash";
            case FAIRY -> "Twinkle Tackle";
            case NONE -> "Breakneck Blitz";
        };
    }

    private static int zMovePower(int basePower) {
        if (basePower <= 55) return 100;
        if (basePower <= 65) return 120;
        if (basePower <= 75) return 140;
        if (basePower <= 85) return 160;
        if (basePower <= 95) return 175;
        if (basePower <= 100) return 180;
        if (basePower <= 110) return 185;
        if (basePower <= 120) return 190;
        if (basePower <= 130) return 195;
        return 200;
    }

    void swap() {
        PokemonSet previous = attacker;
        attacker = defender;
        defender = previous;
        field.swapSides();
        syncSearchFieldsFromSets();
    }

    void syncSearchFieldsFromSets() {
        attackerSearch = attacker.species.name();
        defenderSearch = defender.species.name();
        attackerItemSearch = attacker.itemKnown ? attacker.item : "";
        defenderItemSearch = defender.itemKnown ? defender.item : "";
        attackerAbilitySearch = attacker.abilityKnown ? attacker.ability : "";
        defenderAbilitySearch = defender.abilityKnown ? defender.ability : "";
        attackerNatureSearch = attacker.natureKnown ? attacker.nature.name() : "";
        defenderNatureSearch = defender.natureKnown ? defender.nature.name() : "";
        attackerPartnerAbilitySearch = field.attackerSide.partnerAbility;
        defenderPartnerAbilitySearch = field.defenderSide.partnerAbility;
    }

    void setFromBattle(BattlePokemonSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasAny()) {
            return;
        }
        if (snapshot.player() != null) {
            PokemonSet livePlayer = snapshot.player();
            boolean sameBattlePokemon = attacker != null && !attacker.battleId.isBlank()
                    && attacker.battleId.equals(livePlayer.battleId);
            if (sameBattlePokemon && hasCompletePrivateData(attacker) && !hasCompletePrivateData(livePlayer)) {
                updateBattleRuntime(attacker, livePlayer);
                mergeRevealedInformation(attacker, livePlayer);
                copyBattleHistory(livePlayer, attacker);
            } else {
                attacker = livePlayer;
            }
        }
        if (snapshot.opponent() != null) {
            PokemonSet liveOpponent = snapshot.opponent();
            boolean sameBattlePokemon = defender != null && !defender.battleId.isBlank()
                    && defender.battleId.equals(liveOpponent.battleId);
            boolean configuredSameSpecies = defender != null
                    && defender.species.id().equals(liveOpponent.species.id()) && hasConfiguredBuild(defender);
            if (sameBattlePokemon || configuredSameSpecies) {
                defender.species = liveOpponent.species;
                defender.battleId = liveOpponent.battleId;
                defender.battleName = liveOpponent.battleName;
                defender.level = liveOpponent.level;
                defender.currentHp = liveOpponent.currentHp;
                defender.observedMaxHp = liveOpponent.observedMaxHp;
                defender.status = liveOpponent.status;
                defender.boosts.clear();
                defender.boosts.putAll(liveOpponent.boosts);
                mergeRevealedInformation(defender, liveOpponent);
                copyBattleHistory(liveOpponent, defender);
            } else {
                defender = liveOpponent.copy();
            }
        }
        field.doubles = snapshot.doubles();
        if (snapshot.playerPartnerAbility() != null) {
            field.attackerSide.partnerAbility = snapshot.playerPartnerAbility();
        }
        if (snapshot.opponentPartnerAbility() != null) {
            field.defenderSide.partnerAbility = snapshot.opponentPartnerAbility();
        }
        if (snapshot.playerPartnerName() != null) {
            field.attackerSide.partnerName = snapshot.playerPartnerName();
        }
        if (snapshot.opponentPartnerName() != null) {
            field.defenderSide.partnerName = snapshot.opponentPartnerName();
        }
        field.attackerSide.spreadTargets = Math.max(1, Math.min(2, snapshot.opponentActiveCount()));
        field.defenderSide.spreadTargets = Math.max(1, Math.min(2, snapshot.playerActiveCount()));
        CobblemonBattleConditionTracker.applyTo(field);
        if (!field.doubles) {
            field.helpingHand = false;
            field.friendGuard = false;
            field.attackerSide.helpingHand = false;
            field.attackerSide.friendGuard = false;
            field.defenderSide.helpingHand = false;
            field.defenderSide.friendGuard = false;
            clearDoublesContext(field.attackerSide);
            clearDoublesContext(field.defenderSide);
        }
        syncSearchFieldsFromSets();
        attackerPartnerAbilitySearch = field.attackerSide.partnerAbility;
        defenderPartnerAbilitySearch = field.defenderSide.partnerAbility;
    }

    private static void clearDoublesContext(SideConditions side) {
        side.helpingHand = false;
        side.friendGuard = false;
        side.wideGuard = false;
        side.partnerAbility = "None";
        side.partnerName = "";
        side.spreadTargets = 1;
    }

    private static void copyBattleHistory(PokemonSet source, PokemonSet target) {
        target.battleHistoryKnown = source.battleHistoryKnown;
        target.timesHit = source.timesHit;
        target.faintedAllies = source.faintedAllies;
        target.lastMoveId = source.lastMoveId;
        target.consecutiveMoveUses = source.consecutiveMoveUses;
        target.echoedVoiceChain = source.echoedVoiceChain;
        target.defenseCurlUsed = source.defenseCurlUsed;
        target.switchedInThisTurn = source.switchedInThisTurn;
        target.allyFaintedPreviousTurn = source.allyFaintedPreviousTurn;
        target.lastMoveFailed = source.lastMoveFailed;
        target.flashFireActive = source.flashFireActive;
        target.paradoxBoostActive = source.paradoxBoostActive;
        target.turnsActive = source.turnsActive;
        target.lastDamageTaken = source.lastDamageTaken;
        target.lastDamageCategory = source.lastDamageCategory;
    }

    private static void updateBattleRuntime(PokemonSet target, PokemonSet source) {
        target.species = source.species;
        target.battleId = source.battleId;
        target.battleName = source.battleName;
        target.level = source.level;
        target.currentHp = source.currentHp;
        target.observedMaxHp = source.observedMaxHp;
        target.status = source.status;
        target.boosts.clear();
        target.boosts.putAll(source.boosts);
    }

    private static boolean hasCompletePrivateData(PokemonSet pokemon) {
        return pokemon != null && pokemon.itemKnown && pokemon.abilityKnown
                && pokemon.natureKnown && pokemon.statsKnown && pokemon.movesKnown;
    }

    private static void mergeRevealedInformation(PokemonSet target, PokemonSet source) {
        if (!target.itemKnown && source.itemKnown) {
            target.item = source.item;
            target.itemKnown = true;
        }
        if (!target.abilityKnown && source.abilityKnown) {
            target.ability = source.ability;
            target.abilityKnown = true;
        }
        if (!target.natureKnown && source.natureKnown) {
            target.nature = source.nature;
            target.natureKnown = true;
        }
        if (!target.statsKnown && source.statsKnown) {
            target.evs.clear();
            target.evs.putAll(source.evs);
            target.ivs.clear();
            target.ivs.putAll(source.ivs);
            target.statsKnown = true;
        }
        if (!target.movesKnown && source.movesKnown) {
            target.moves.clear();
            target.moves.addAll(source.moves);
            target.movesKnown = true;
        }
    }

    private static boolean hasConfiguredBuild(PokemonSet pokemon) {
        if (!"none".equals(TropimonDex.normalize(pokemon.item))) return true;
        if (!pokemon.nature.id().equals("serious")) return true;
        if (!TropimonDex.normalize(pokemon.ability)
                .equals(TropimonDex.normalize(TropimonDex.defaultAbility(pokemon.species)))) return true;
        for (Stat stat : Stat.values()) {
            if (pokemon.evs.get(stat) != 0 || pokemon.ivs.get(stat) != 31) return true;
        }
        return false;
    }

    static long battleFingerprint(BattlePokemonSnapshot snapshot) {
        if (snapshot == null) return 0L;
        long hash = snapshot.doubles() ? 1L : 0L;
        hash = mix(hash, snapshot.player() == null ? 0L : pokemonFingerprint(snapshot.player()));
        return mix(hash, snapshot.opponent() == null ? 0L : pokemonFingerprint(snapshot.opponent()));
    }

    static <T> T cycle(List<T> values, T current) {
        int index = values.indexOf(current);
        return values.get(Math.floorMod(index + 1, values.size()));
    }

    static void presetEvs(PokemonSet pokemon, EvPreset preset) {
        for (Map.Entry<Stat, Integer> entry : pokemon.evs.entrySet()) {
            entry.setValue(0);
        }
        switch (preset) {
            case ATK -> {
                pokemon.evs.put(Stat.ATK, 252);
                pokemon.evs.put(Stat.SPE, 252);
                pokemon.evs.put(Stat.HP, 6);
            }
            case SPA -> {
                pokemon.evs.put(Stat.SPA, 252);
                pokemon.evs.put(Stat.SPE, 252);
                pokemon.evs.put(Stat.HP, 6);
            }
            case DEF -> {
                pokemon.evs.put(Stat.HP, 252);
                pokemon.evs.put(Stat.DEF, 252);
                pokemon.evs.put(Stat.SPD, 6);
            }
            case SPD -> {
                pokemon.evs.put(Stat.HP, 252);
                pokemon.evs.put(Stat.SPD, 252);
                pokemon.evs.put(Stat.DEF, 6);
            }
        }
        pokemon.currentHp = -1;
        pokemon.observedMaxHp = -1;
    }
}

enum EvPreset {
    ATK, SPA, DEF, SPD
}
