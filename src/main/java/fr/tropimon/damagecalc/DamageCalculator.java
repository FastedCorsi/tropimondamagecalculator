package fr.tropimon.damagecalc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DamageCalculator {
    private static final Set<String> ATTACKER_HP_POWER_MOVES = Set.of(
            "eruption", "waterspout", "dragonenergy", "flail", "reversal"
    );
    private static final Set<String> DEFENDER_HP_POWER_MOVES = Set.of(
            "brine", "crushgrip", "wringout", "hardpress"
    );
    private static final Set<String> WEIGHT_POWER_MOVES = Set.of(
            "lowkick", "grassknot", "heavyslam", "heatcrash"
    );
    private static final Set<String> UNSUPPORTED_CONTEXT_MOVES = Set.of(
            "assurance", "payback", "pursuit", "lashout",
            "trumpcard", "return", "frustration",
            "beatup", "bide", "spitup", "magnitude", "present", "fling", "naturalgift", "round",
            "fusionbolt", "fusionflare", "ficklebeam",
            "fishiousrend", "boltbeak"
    );
    private static final Set<String> OHKO_MOVES = Set.of("fissure", "guillotine", "horndrill", "sheercold");
    private static final Set<String> REACTIVE_DAMAGE_MOVES = Set.of("counter", "mirrorcoat", "metalburst", "comeuppance");
    private static final EnumMap<PokeType, EnumMap<PokeType, Double>> TYPE_CHART = new EnumMap<>(PokeType.class);
    private static final Set<String> BITE_MOVES = Set.of(
            "bite", "crunch", "firefang", "fishiousrend", "hyperfang", "icefang", "jawlock", "poisonfang", "psychicfangs", "thunderfang"
    );
    private static final Set<String> PUNCH_MOVES = Set.of(
            "bulletpunch", "cometpunch", "dizzypunch", "doubleironbash", "drainpunch", "dynamicpunch", "firepunch", "focuspunch",
            "hammerarm", "icehammer", "icepunch", "machpunch", "megapunch", "meteormash", "plasmafists", "poweruppunch",
            "jetpunch", "ragefist", "shadowpunch", "skyuppercut", "surgingstrikes", "thunderpunch", "wickedblow"
    );
    private static final Set<String> CONTACT_MOVES = Set.of(
            "aerialace", "aquajet", "aquatail", "bite", "bodyslam", "boltbeak", "bravebird", "brickbreak", "bulldoze",
            "bulletpunch", "closecombat", "crabhammer", "crosschop", "crunch", "doubleedge", "dragonclaw", "drainpunch",
            "drillpeck", "facade", "fakeout", "firefang", "firepunch", "fishiousrend", "flareblitz", "focuspunch",
            "headbutt", "headsmash", "icefang", "icepunch", "ironhead", "leafblade", "liquidation", "lowkick",
            "machpunch", "megahorn", "nightslash", "playrough", "poisonjab", "poweruppunch", "psychicfangs", "quickattack",
            "razorshell", "sacredsword", "shadowclaw", "shadowpunch", "slash", "stoneedge", "suckerpunch", "tackle",
            "thunderfang", "thunderpunch", "uturn", "volttackle", "waterfall", "wildcharge", "woodhammer", "xscissor", "zenheadbutt"
    );
    private static final Set<String> SLICING_MOVES = Set.of(
            "aerialace", "aircutter", "aquacutter", "behemothblade", "bitterblade", "ceaselessedge", "crosspoison", "cut",
            "furycutter", "kowtowcleave", "leafblade", "nightslash", "psychocut", "razorleaf", "razorshell", "sacredsword",
            "slash", "solarblade", "stoneaxe", "xscissor"
    );
    private static final Set<String> PULSE_MOVES = Set.of(
            "aurasphere", "darkpulse", "dragonpulse", "originpulse", "terrainpulse", "waterpulse"
    );
    private static final Set<String> SOUND_MOVES = Set.of(
            "alluringvoice", "boomburst", "bugbuzz", "clangingscales", "disarmingvoice", "echoedvoice", "hypervoice",
            "overdrive", "psychicnoise", "relicsong", "round", "snarl", "snore", "sparklingaria", "torchsong", "uproar"
    );
    private static final Set<String> BULLET_MOVES = Set.of(
            "acidspray", "aurasphere", "barrage", "bulletseed", "eggbomb", "electroball", "energyball", "focusblast",
            "gyroball", "iceball", "magnetbomb", "mistball", "mudbomb", "octazooka", "pollenpuff", "pyroball",
            "rockblast", "rockwrecker", "seedbomb", "shadowball", "sludgebomb", "weatherball", "zapcannon"
    );
    private static final Set<String> WIND_MOVES = Set.of(
            "aircutter", "bleakwindstorm", "blizzard", "fairywind", "gust", "heatwave", "hurricane", "icywind",
            "petalblizzard", "sandsearstorm", "springtidestorm", "twister", "wildboltstorm"
    );
    private static final Set<String> RECOIL_MOVES = Set.of(
            "bravebird", "chloroblast", "doubleedge", "flareblitz", "headcharge", "headsmash", "highjumpkick", "jumpkick",
            "lightofruin", "submission", "takedown", "volttackle", "wavecrash", "wildcharge", "woodhammer"
    );
    private static final Set<String> SPEED_HALVING_ITEMS = Set.of(
            "ironball", "poweranklet", "powerband", "powerbelt", "powerbracer", "powerlens", "powerweight", "machobrace"
    );
    private static final Map<PokeType, Set<String>> TYPE_BOOST_ITEMS = Map.ofEntries(
            Map.entry(PokeType.NORMAL, Set.of("silkscarf")),
            Map.entry(PokeType.FIRE, Set.of("charcoal", "charcoalstick", "flameplate")),
            Map.entry(PokeType.WATER, Set.of("mysticwater", "splashplate", "seaincense", "waveincense")),
            Map.entry(PokeType.ELECTRIC, Set.of("magnet", "zapplate")),
            Map.entry(PokeType.GRASS, Set.of("miracleseed", "meadowplate", "roseincense")),
            Map.entry(PokeType.ICE, Set.of("nevermeltice", "icicleplate")),
            Map.entry(PokeType.FIGHTING, Set.of("blackbelt", "fistplate")),
            Map.entry(PokeType.POISON, Set.of("poisonbarb", "toxicplate")),
            Map.entry(PokeType.GROUND, Set.of("softsand", "earthplate")),
            Map.entry(PokeType.FLYING, Set.of("sharpbeak", "skyplate")),
            Map.entry(PokeType.PSYCHIC, Set.of("twistedspoon", "mindplate", "oddincense")),
            Map.entry(PokeType.BUG, Set.of("silverpowder", "insectplate")),
            Map.entry(PokeType.ROCK, Set.of("hardstone", "stoneplate", "rockincense")),
            Map.entry(PokeType.GHOST, Set.of("spelltag", "spookyplate")),
            Map.entry(PokeType.DRAGON, Set.of("dragonfang", "dracoplate")),
            Map.entry(PokeType.DARK, Set.of("blackglasses", "dreadplate")),
            Map.entry(PokeType.STEEL, Set.of("metalcoat", "ironplate")),
            Map.entry(PokeType.FAIRY, Set.of("fairyfeather", "pixieplate"))
    );

    static {
        for (PokeType attacking : PokeType.values()) {
            TYPE_CHART.put(attacking, new EnumMap<>(PokeType.class));
        }
        weak(PokeType.NORMAL, PokeType.ROCK, PokeType.STEEL);
        immune(PokeType.NORMAL, PokeType.GHOST);
        strong(PokeType.FIRE, PokeType.GRASS, PokeType.ICE, PokeType.BUG, PokeType.STEEL);
        weak(PokeType.FIRE, PokeType.FIRE, PokeType.WATER, PokeType.ROCK, PokeType.DRAGON);
        strong(PokeType.WATER, PokeType.FIRE, PokeType.GROUND, PokeType.ROCK);
        weak(PokeType.WATER, PokeType.WATER, PokeType.GRASS, PokeType.DRAGON);
        strong(PokeType.ELECTRIC, PokeType.WATER, PokeType.FLYING);
        weak(PokeType.ELECTRIC, PokeType.ELECTRIC, PokeType.GRASS, PokeType.DRAGON);
        immune(PokeType.ELECTRIC, PokeType.GROUND);
        strong(PokeType.GRASS, PokeType.WATER, PokeType.GROUND, PokeType.ROCK);
        weak(PokeType.GRASS, PokeType.FIRE, PokeType.GRASS, PokeType.POISON, PokeType.FLYING, PokeType.BUG, PokeType.DRAGON, PokeType.STEEL);
        strong(PokeType.ICE, PokeType.GRASS, PokeType.GROUND, PokeType.FLYING, PokeType.DRAGON);
        weak(PokeType.ICE, PokeType.FIRE, PokeType.WATER, PokeType.ICE, PokeType.STEEL);
        strong(PokeType.FIGHTING, PokeType.NORMAL, PokeType.ICE, PokeType.ROCK, PokeType.DARK, PokeType.STEEL);
        weak(PokeType.FIGHTING, PokeType.POISON, PokeType.FLYING, PokeType.PSYCHIC, PokeType.BUG, PokeType.FAIRY);
        immune(PokeType.FIGHTING, PokeType.GHOST);
        strong(PokeType.POISON, PokeType.GRASS, PokeType.FAIRY);
        weak(PokeType.POISON, PokeType.POISON, PokeType.GROUND, PokeType.ROCK, PokeType.GHOST);
        immune(PokeType.POISON, PokeType.STEEL);
        strong(PokeType.GROUND, PokeType.FIRE, PokeType.ELECTRIC, PokeType.POISON, PokeType.ROCK, PokeType.STEEL);
        weak(PokeType.GROUND, PokeType.GRASS, PokeType.BUG);
        immune(PokeType.GROUND, PokeType.FLYING);
        strong(PokeType.FLYING, PokeType.GRASS, PokeType.FIGHTING, PokeType.BUG);
        weak(PokeType.FLYING, PokeType.ELECTRIC, PokeType.ROCK, PokeType.STEEL);
        strong(PokeType.PSYCHIC, PokeType.FIGHTING, PokeType.POISON);
        weak(PokeType.PSYCHIC, PokeType.PSYCHIC, PokeType.STEEL);
        immune(PokeType.PSYCHIC, PokeType.DARK);
        strong(PokeType.BUG, PokeType.GRASS, PokeType.PSYCHIC, PokeType.DARK);
        weak(PokeType.BUG, PokeType.FIRE, PokeType.FIGHTING, PokeType.POISON, PokeType.FLYING, PokeType.GHOST, PokeType.STEEL, PokeType.FAIRY);
        strong(PokeType.ROCK, PokeType.FIRE, PokeType.ICE, PokeType.FLYING, PokeType.BUG);
        weak(PokeType.ROCK, PokeType.FIGHTING, PokeType.GROUND, PokeType.STEEL);
        strong(PokeType.GHOST, PokeType.PSYCHIC, PokeType.GHOST);
        weak(PokeType.GHOST, PokeType.DARK);
        immune(PokeType.GHOST, PokeType.NORMAL);
        strong(PokeType.DRAGON, PokeType.DRAGON);
        weak(PokeType.DRAGON, PokeType.STEEL);
        immune(PokeType.DRAGON, PokeType.FAIRY);
        strong(PokeType.DARK, PokeType.PSYCHIC, PokeType.GHOST);
        weak(PokeType.DARK, PokeType.FIGHTING, PokeType.DARK, PokeType.FAIRY);
        strong(PokeType.STEEL, PokeType.ICE, PokeType.ROCK, PokeType.FAIRY);
        weak(PokeType.STEEL, PokeType.FIRE, PokeType.WATER, PokeType.ELECTRIC, PokeType.STEEL);
        strong(PokeType.FAIRY, PokeType.FIGHTING, PokeType.DRAGON, PokeType.DARK);
        weak(PokeType.FAIRY, PokeType.FIRE, PokeType.POISON, PokeType.STEEL);
    }

    private DamageCalculator() {
    }

    static DamageResult calculate(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field) {
        return calculate(attacker, defender, move, field, field.legacySideConditions());
    }

    static DamageResult calculate(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field, SideConditions defenderSide) {
        return calculate(attacker, defender, move, field, new SideConditions(), defenderSide);
    }

    static DamageResult calculate(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field,
                                  SideConditions attackerSide, SideConditions defenderSide) {
        if (move.category() == DamageCategory.STATUS) {
            return DamageResult.zero(move, "status move");
        }
        if (field.doubles && move.spreadMove() && defenderSide.wideGuard) {
            return DamageResult.zero(move, "Wide Guard");
        }

        ArrayList<String> notes = new ArrayList<>();
        ArrayList<CalcWarning> warnings = new ArrayList<>();
        move = withEffectiveCategory(attacker, defender, move, field, notes);
        addContextWarnings(attacker, defender, move, warnings);
        PokeType moveType = effectiveMoveType(attacker, move, field, notes);
        String immunity = defensiveImmunity(attacker, defender, move, moveType, field);
        if (immunity != null) {
            return DamageResult.zero(move, immunity, warnings);
        }
        double effectiveness = typeEffectiveness(move, moveType, defender, attacker, field, notes);
        if (effectiveness == 0.0) {
            return DamageResult.zero(move, "immune", warnings);
        }
        if (!ignoresDefensiveAbilities(attacker) && hasAbility(defender, "Wonder Guard") && effectiveness <= 1.0) {
            return DamageResult.zero(move, "Wonder Guard immunity", warnings);
        }

        FixedDamage fixedDamage = fixedDamage(attacker, defender, move);
        if (fixedDamage != null) {
            return fixedDamageResult(attacker, defender, move, fixedDamage, warnings);
        }

        int power = effectivePower(attacker, defender, move, moveType, field, attackerSide, defenderSide, notes, warnings);
        if (power <= 0) {
            return DamageResult.zero(move, "no base power", warnings);
        }

        boolean criticalHit = isCriticalHit(attacker, defender, move, field);
        int attackStat = offensiveStat(attacker, defender, move, field, attackerSide, defenderSide,
                criticalHit, notes);
        int defenseStat = defensiveStat(attacker, defender, move, field, attackerSide, defenderSide,
                criticalHit, notes);
        if (defenseStat <= 0) {
            defenseStat = 1;
        }

        double modifier = 1.0;

        if (field.doubles && move.spreadMove() && attackerSide.spreadTargets > 1) {
            modifier *= 0.75;
            notes.add("spread x0.75");
        }
        modifier *= weatherModifier(attacker, defender, move, moveType, field, notes);
        if (criticalHit) {
            modifier *= 1.5;
            notes.add("crit");
            if (hasAbility(attacker, "Sniper")) {
                modifier *= 1.5;
                notes.add("Sniper");
            }
        }
        modifier *= randomlessModifier(attacker, defender, move, moveType, field, attackerSide, defenderSide,
                effectiveness, criticalHit, notes);
        modifier *= stabModifier(attacker, moveType, notes);
        modifier *= effectiveness;

        HitProfile hits = hitProfile(attacker, defender, move);
        DamageDistribution distribution = damageDistribution(attacker.level, power, attackStat, defenseStat,
                modifier, move, hits);
        ArrayList<Integer> rolls = new ArrayList<>();
        for (int roll = 85; roll <= 100; roll++) {
            rolls.add(damageForHits(attacker.level, power, attackStat, defenseStat, modifier, roll,
                    move, hits, hits.minHits()));
        }

        int min = distribution.minDamage();
        int max = distribution.maxDamage();
        if (hits.maxHits() > 1) {
            notes.add(hits.multiAccuracy() && !hits.forcedHits()
                    ? "1-" + hits.maxHits() + " hits"
                    : hits.minHits() == hits.maxHits()
                    ? hits.minHits() + " hits"
                    : hits.minHits() + "-" + hits.maxHits() + " hits");
        }
        int maxHp = defender.maxHp();
        int entryDamage = 0;
        int koHp = Math.max(1, defender.visibleHp() - entryDamage);
        double minPercent = min * 100.0 / maxHp;
        double maxPercent = max * 100.0 / maxHp;
        String koChance = koChance(distribution.probabilities(), koHp);
        if (hits.maxHits() == 1 && max >= koHp && defender.visibleHp() == defender.maxHp()
                && (hasItem(defender, "Focus Sash")
                || hasAbility(defender, "Sturdy") && !ignoresDefensiveAbilities(attacker))) {
            koChance = "survives at 1 HP";
        }
        if (entryDamage > 0) {
            koChance += " after " + entryDamage + " entry damage";
        }
        String attackerPrefix = attacker.evs.get(move.category() == DamageCategory.PHYSICAL ? Stat.ATK : Stat.SPA) + " "
                + (move.category() == DamageCategory.PHYSICAL ? "Atk" : "SpA") + " " + attacker.species.name();
        String defenderPrefix = defender.evs.get(Stat.HP) + " HP / "
                + defender.evs.get(move.category() == DamageCategory.PHYSICAL ? Stat.DEF : Stat.SPD) + " "
                + (move.category() == DamageCategory.PHYSICAL ? "Def" : "SpD") + " " + defender.species.name();
        String line = attackerPrefix + " " + move.name() + " vs. " + defenderPrefix + ": "
                + min + "-" + max + " (" + percent(minPercent) + " - " + percent(maxPercent) + "%) -- " + koChance;
        String estimated = warnings.isEmpty() ? "" : " [estimated]";
        String shortLine = move.name() + " " + min + "-" + max + " (" + percent(minPercent) + "-"
                + percent(maxPercent) + "%) | " + koChance + estimated;
        return new DamageResult(move, min, max, minPercent, maxPercent, rolls, entryDamage, koChance,
                line + estimated, shortLine, List.copyOf(notes), List.copyOf(warnings));
    }

    private static int damageForHits(int level, int power, int attackStat, int defenseStat, double modifier,
                                     int randomRoll, MoveData move, HitProfile profile, int hitCount) {
        int total = 0;
        for (int hit = 1; hit <= Math.max(1, hitCount); hit++) {
            if (profile.blockedFirstHit() && hit == 1) {
                continue;
            }
            int hitPower = progressiveHitPower(move, power, hit);
            int base = (int) Math.floor((Math.floor((2.0 * level / 5.0) + 2) * hitPower * attackStat / defenseStat) / 50.0) + 2;
            double hitModifier = profile.parentalBond() && hit == 2 ? 0.25 : 1.0;
            total += Math.max(1, (int) Math.floor(base * modifier * hitModifier * randomRoll / 100.0));
        }
        return total;
    }

    private static DamageDistribution damageDistribution(int level, int power, int attackStat, int defenseStat,
                                                          double modifier, MoveData move, HitProfile hits) {
        Map<Integer, Double> combined = new HashMap<>();
        for (Map.Entry<Integer, Double> hitCount : hitCountProbabilities(hits).entrySet()) {
            Map<Integer, Double> totals = Map.of(0, 1.0);
            HashMap<Integer, Double> stopped = new HashMap<>();
            double continuationAccuracy = move.hasFlag("multiaccuracy") && !hits.forcedHits()
                    ? moveAccuracy(move) / 100.0 : 1.0;
            for (int hit = 1; hit <= hitCount.getKey(); hit++) {
                int hitPower = progressiveHitPower(move, power, hit);
                HashMap<Integer, Double> next = new HashMap<>();
                for (Map.Entry<Integer, Double> total : totals.entrySet()) {
                    if (hit > 1 && continuationAccuracy < 1.0) {
                        stopped.merge(total.getKey(), total.getValue() * (1.0 - continuationAccuracy), Double::sum);
                    }
                    if (hits.blockedFirstHit() && hit == 1) {
                        next.merge(total.getKey(), total.getValue(), Double::sum);
                        continue;
                    }
                    for (int roll = 85; roll <= 100; roll++) {
                        int base = (int) Math.floor((Math.floor((2.0 * level / 5.0) + 2)
                                * hitPower * attackStat / defenseStat) / 50.0) + 2;
                        double hitModifier = hits.parentalBond() && hit == 2 ? 0.25 : 1.0;
                        int damage = Math.max(1, (int) Math.floor(base * modifier * hitModifier * roll / 100.0));
                        double hitChance = hit > 1 ? continuationAccuracy : 1.0;
                        next.merge(total.getKey() + damage, total.getValue() * hitChance / 16.0, Double::sum);
                    }
                }
                totals = next;
            }
            for (Map.Entry<Integer, Double> stoppedTotal : stopped.entrySet()) {
                combined.merge(stoppedTotal.getKey(), stoppedTotal.getValue() * hitCount.getValue(), Double::sum);
            }
            for (Map.Entry<Integer, Double> total : totals.entrySet()) {
                combined.merge(total.getKey(), total.getValue() * hitCount.getValue(), Double::sum);
            }
        }
        int min = combined.keySet().stream().min(Integer::compareTo).orElse(0);
        int max = combined.keySet().stream().max(Integer::compareTo).orElse(0);
        return new DamageDistribution(Map.copyOf(combined), min, max);
    }

    static Map<Integer, Double> hitCountProbabilities(HitProfile hits) {
        if (hits.minHits() == hits.maxHits()) {
            return Map.of(hits.minHits(), 1.0);
        }
        if (hits.minHits() == 2 && hits.maxHits() == 5) {
            return Map.of(2, 0.35, 3, 0.35, 4, 0.15, 5, 0.15);
        }
        if (hits.minHits() == 4 && hits.maxHits() == 5) {
            return Map.of(4, 0.5, 5, 0.5);
        }
        HashMap<Integer, Double> probabilities = new HashMap<>();
        double probability = 1.0 / (hits.maxHits() - hits.minHits() + 1);
        for (int count = hits.minHits(); count <= hits.maxHits(); count++) {
            probabilities.put(count, probability);
        }
        return probabilities;
    }

    private static int progressiveHitPower(MoveData move, int basePower, int hit) {
        return move.id().equals("tripleaxel") || move.id().equals("triplekick")
                ? basePower * hit
                : basePower;
    }

    private static MoveData withEffectiveCategory(PokemonSet attacker, PokemonSet defender, MoveData move,
                                                  FieldState field, List<String> notes) {
        DamageCategory category = move.category();
        String id = move.id();
        if ((id.equals("terablast") && attacker.terastallized)
                || id.equals("photongeyser") || id.equals("lightthatburnsthesky")) {
            int attack = stat(attacker, Stat.ATK, false, field, new SideConditions());
            int specialAttack = stat(attacker, Stat.SPA, false, field, new SideConditions());
            category = attack > specialAttack ? DamageCategory.PHYSICAL : DamageCategory.SPECIAL;
        } else if (id.equals("shellsidearm")) {
            double physical = stat(attacker, Stat.ATK, false, field, new SideConditions())
                    / (double) Math.max(1, stat(defender, Stat.DEF, false, field, new SideConditions()));
            double special = stat(attacker, Stat.SPA, false, field, new SideConditions())
                    / (double) Math.max(1, stat(defender, Stat.SPD, false, field, new SideConditions()));
            category = physical > special ? DamageCategory.PHYSICAL : DamageCategory.SPECIAL;
        }
        if (category == move.category()) {
            return move;
        }
        notes.add(move.name() + " uses " + category.name().toLowerCase(Locale.ROOT) + " damage");
        return new MoveData(move.id(), move.name(), move.type(), category, move.basePower(), move.spreadMove(),
                move.contact(), move.flags(), move.priority());
    }

    private static HitProfile hitProfile(PokemonSet attacker, PokemonSet defender, MoveData move) {
        int min = 1;
        int max = 1;
        for (String flag : move.flags()) {
            if (!flag.startsWith("hits")) continue;
            String value = flag.substring(4);
            int separator = value.indexOf("to");
            try {
                if (separator >= 0) {
                    min = Integer.parseInt(value.substring(0, separator));
                    max = Integer.parseInt(value.substring(separator + 2));
                } else {
                    min = max = Integer.parseInt(value);
                }
            } catch (NumberFormatException ignored) {
                min = max = 1;
            }
            break;
        }
        if (max <= 1 && move.hasFlag("multihit")) {
            min = 2;
            max = 5;
        }
        boolean forcedHits = false;
        if (max > min && hasAbility(attacker, "Skill Link")) {
            min = max;
            forcedHits = true;
        } else if (max > min && hasItem(attacker, "Loaded Dice")) {
            min = Math.min(max, Math.max(min, 4));
            forcedHits = true;
        } else if (move.hasFlag("multiaccuracy")
                && (hasAbility(attacker, "Skill Link") || hasItem(attacker, "Loaded Dice"))) {
            forcedHits = true;
        }
        boolean parentalBond = min == 1 && max == 1 && hasAbility(attacker, "Parental Bond")
                && !move.spreadMove();
        if (parentalBond) {
            min = max = 2;
        }
        boolean blockedFirstHit = !ignoresDefensiveAbilities(attacker)
                && (hasAbility(defender, "Disguise") && !hasAspect(defender, "busted")
                || move.category() == DamageCategory.PHYSICAL && hasAbility(defender, "Ice Face")
                && !hasAspect(defender, "noice"));
        return new HitProfile(Math.max(1, min), Math.max(Math.max(1, min), max), forcedHits,
                move.hasFlag("multiaccuracy"), parentalBond, blockedFirstHit);
    }

    static int moveAccuracy(MoveData move) {
        for (String flag : move.flags()) {
            if (!flag.startsWith("accuracy")) continue;
            try {
                return Math.max(1, Math.min(100, Integer.parseInt(flag.substring("accuracy".length()))));
            } catch (NumberFormatException ignored) {
                return 100;
            }
        }
        return 100;
    }

    private static String koChanceRange(int minDamage, int maxDamage, int hp) {
        int best = Math.max(1, (int) Math.ceil(hp / (double) Math.max(1, maxDamage)));
        int worst = Math.max(1, (int) Math.ceil(hp / (double) Math.max(1, minDamage)));
        return best == worst ? "guaranteed " + best + "HKO" : "possible " + best + "-" + worst + "HKO";
    }

    static int stat(PokemonSet pokemon, Stat stat, boolean ignoreBoosts) {
        int value = storedStat(pokemon, stat, ignoreBoosts);
        if (stat != Stat.HP) {
            value = itemStatModifier(pokemon, stat, value);
            value = abilityStatModifier(pokemon, stat, value);
        }
        return Math.max(1, value);
    }

    private static int storedStat(PokemonSet pokemon, Stat stat, boolean ignoreBoosts) {
        int base = pokemon.species.baseStats().get(stat);
        int iv = pokemon.ivs.get(stat);
        int ev = pokemon.evs.get(stat);
        int value;
        if (stat == Stat.HP) {
            value = (int) Math.floor(((2 * base + iv + Math.floor(ev / 4.0)) * pokemon.level) / 100.0) + pokemon.level + 10;
        } else {
            value = (int) Math.floor(((2 * base + iv + Math.floor(ev / 4.0)) * pokemon.level) / 100.0) + 5;
            value = (int) Math.floor(value * pokemon.nature.modifier(stat));
            if (!ignoreBoosts) {
                value = applyBoost(value, pokemon.boosts.get(stat));
            }
        }
        return Math.max(1, value);
    }

    static int stat(PokemonSet pokemon, Stat stat, boolean ignoreBoosts, FieldState field) {
        return stat(pokemon, stat, ignoreBoosts, field, new SideConditions());
    }

    static int stat(PokemonSet pokemon, Stat stat, boolean ignoreBoosts, FieldState field, SideConditions side) {
        int value = weatherStatModifier(pokemon, stat, stat(pokemon, stat, ignoreBoosts), field, null);
        return sideStatModifier(stat, value, side, null);
    }

    static int displayedStat(PokemonSet pokemon, Stat stat, FieldState field, SideConditions side) {
        int value = stat(pokemon, stat, false, field, side);
        if (stat == Stat.ATK && pokemon.status == StatusCondition.BURN && !hasAbility(pokemon, "Guts")) {
            value = Math.max(1, (int) Math.floor(value * 0.5));
        }
        return value;
    }

    static double typeEffectiveness(PokeType attackingType, PokemonSet defender, PokemonSet attacker, List<String> notes) {
        return typeEffectiveness(attackingType, defender, attacker, new FieldState(), notes);
    }

    private static double typeEffectiveness(PokeType attackingType, PokemonSet defender, PokemonSet attacker,
                                            FieldState field, List<String> notes) {
        return typeEffectiveness(null, attackingType, defender, attacker, field, notes);
    }

    private static double typeEffectiveness(MoveData move, PokeType attackingType, PokemonSet defender,
                                             PokemonSet attacker, FieldState field, List<String> notes) {
        if (attackingType == PokeType.GROUND && !field.gravity
                && (move == null || !move.id().equals("thousandarrows"))
                && hasAbility(defender, "Levitate") && !ignoresDefensiveAbilities(attacker)) {
            notes.add("Levitate immunity");
            return 0.0;
        }
        double effectiveness = 1.0;
        for (PokeType defendingType : defender.defensiveTypes()) {
            double factor = TYPE_CHART.get(attackingType).getOrDefault(defendingType, 1.0);
            boolean bypassGhostImmunity = defendingType == PokeType.GHOST
                    && (attackingType == PokeType.NORMAL || attackingType == PokeType.FIGHTING)
                    && (hasAbility(attacker, "Scrappy") || hasAbility(attacker, "Mind's Eye"));
            boolean groundedFlying = attackingType == PokeType.GROUND && defendingType == PokeType.FLYING
                    && (field.gravity || hasItem(defender, "Iron Ball")
                    || move != null && move.id().equals("thousandarrows"));
            if ((factor == 0.0 && hasItem(defender, "Ring Target")) || bypassGhostImmunity || groundedFlying) {
                factor = 1.0;
            }
            if (move != null && move.id().equals("freezedry") && defendingType == PokeType.WATER) {
                factor = 2.0;
            }
            effectiveness *= factor;
        }
        if (move != null && move.id().equals("flyingpress")) {
            effectiveness *= rawTypeEffectiveness(PokeType.FLYING, defender.defensiveTypes());
        }
        if (effectiveness > 0.0 && !ignoresDefensiveAbilities(attacker)
                && hasAbility(defender, "Tera Shell") && defender.visibleHp() == defender.maxHp()) {
            effectiveness = 0.5;
            notes.add("Tera Shell");
        }
        if (effectiveness > 1.0) {
            notes.add("super effective x" + trim(effectiveness));
        } else if (effectiveness < 1.0) {
            notes.add("resisted x" + trim(effectiveness));
        }
        return effectiveness;
    }

    private static PokeType effectiveMoveType(PokemonSet attacker, MoveData move, FieldState field, List<String> notes) {
        if (hasAbility(attacker, "Normalize")) {
            notes.add("Normalize");
            return PokeType.NORMAL;
        }
        if (move.id().equals("weatherball") && field.weather != Weather.NONE) {
            PokeType type = switch (field.weather) {
                case SUN -> PokeType.FIRE;
                case RAIN -> PokeType.WATER;
                case SAND -> PokeType.ROCK;
                case SNOW -> PokeType.ICE;
                case NONE -> PokeType.NORMAL;
            };
            notes.add("Weather Ball " + type.displayName());
            return type;
        }
        if (move.id().equals("terrainpulse") && isGrounded(attacker, field) && field.terrain != Terrain.NONE) {
            PokeType type = terrainType(field.terrain);
            notes.add("Terrain Pulse " + type.displayName());
            return type;
        }
        if (move.id().equals("terablast") && attacker.terastallized && attacker.teraType != PokeType.NONE) {
            notes.add("Tera Blast " + attacker.teraType.displayName());
            return attacker.teraType;
        }
        if (move.id().equals("revelationdance")) {
            return attacker.species.primaryType();
        }
        if (move.id().equals("aurawheel") && hasAspect(attacker, "hangry")) {
            return PokeType.DARK;
        }
        if (move.id().equals("ragingbull")) {
            return attacker.defensiveTypes().contains(PokeType.FIRE) ? PokeType.FIRE
                    : attacker.defensiveTypes().contains(PokeType.WATER) ? PokeType.WATER : PokeType.FIGHTING;
        }
        PokeType itemType = itemMoveType(attacker.item, move.id());
        if (itemType != PokeType.NONE) {
            notes.add(move.name() + " " + itemType.displayName());
            return itemType;
        }
        if (move.type() == PokeType.NORMAL) {
            if (hasAbility(attacker, "Aerilate")) {
                notes.add("Aerilate");
                return PokeType.FLYING;
            }
            if (hasAbility(attacker, "Galvanize")) {
                notes.add("Galvanize");
                return PokeType.ELECTRIC;
            }
            if (hasAbility(attacker, "Pixilate")) {
                notes.add("Pixilate");
                return PokeType.FAIRY;
            }
            if (hasAbility(attacker, "Refrigerate")) {
                notes.add("Refrigerate");
                return PokeType.ICE;
            }
        }
        if (hasAbility(attacker, "Liquid Voice") && hasMoveFlag(move, "sound", SOUND_MOVES)) {
            notes.add("Liquid Voice");
            return PokeType.WATER;
        }
        return move.type();
    }

    private static int effectivePower(PokemonSet attacker, PokemonSet defender, MoveData move, PokeType moveType, FieldState field,
                                      SideConditions attackerSide, SideConditions defenderSide, List<String> notes,
                                      List<CalcWarning> warnings) {
        int power = variableBasePower(attacker, defender, move, field, attackerSide, defenderSide, notes, warnings);
        if (move.id().equals("weatherball") && field.weather != Weather.NONE) {
            power *= 2;
            notes.add("Weather Ball power x2");
        }
        if (move.id().equals("terrainpulse") && isGrounded(attacker, field) && field.terrain != Terrain.NONE) {
            power *= 2;
            notes.add("Terrain Pulse power x2");
        }
        if (move.id().equals("steelroller") && field.terrain == Terrain.NONE) {
            notes.add("Steel Roller needs terrain");
            return 0;
        }
        if ((move.id().equals("solarbeam") || move.id().equals("solarblade"))
                && (field.weather == Weather.RAIN || field.weather == Weather.SAND || field.weather == Weather.SNOW)) {
            power = (int) Math.floor(power * 0.5);
            notes.add(move.name() + " weather x0.5");
        }
        if ((move.id().equals("fishiousrend") || move.id().equals("boltbeak"))
                && movesBefore(attacker, defender, field, attackerSide, defenderSide)) {
            power *= 2;
            notes.add(move.name() + " first x2");
        }
        if (hasAbility(attacker, "Technician") && power <= 60) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Technician");
        }
        if (hasAbility(attacker, "Strong Jaw") && hasMoveFlag(move, "bite", BITE_MOVES)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Strong Jaw");
        }
        if (hasAbility(attacker, "Iron Fist") && hasMoveFlag(move, "punch", PUNCH_MOVES)) {
            power = (int) Math.floor(power * 1.2);
            notes.add("Iron Fist");
        }
        if (hasAbility(attacker, "Sharpness") && hasMoveFlag(move, "slicing", SLICING_MOVES)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Sharpness");
        }
        if (hasAbility(attacker, "Mega Launcher") && hasMoveFlag(move, "pulse", PULSE_MOVES)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Mega Launcher");
        }
        if (hasAbility(attacker, "Tough Claws") && isContactMove(move)) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Tough Claws");
        }
        if (hasAbility(attacker, "Sheer Force") && move.hasFlag("secondary")) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Sheer Force");
        }
        if (hasAbility(attacker, "Analytic")
                && movesAfter(attacker, defender, field, attackerSide, defenderSide)) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Analytic");
        }
        if (hasAbility(attacker, "Normalize") || ((hasAbility(attacker, "Aerilate") || hasAbility(attacker, "Galvanize")
                || hasAbility(attacker, "Pixilate") || hasAbility(attacker, "Refrigerate")) && move.type() == PokeType.NORMAL)) {
            power = (int) Math.floor(power * 1.2);
            notes.add("type ability x1.2");
        }
        if (field.doubles && (field.helpingHand || attackerSide.helpingHand)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Helping Hand");
        }
        if (field.terrain == Terrain.GRASSY && isGrounded(defender, field)
                && (move.id().equals("earthquake") || move.id().equals("bulldoze") || move.id().equals("magnitude"))) {
            power = (int) Math.floor(power * 0.5);
            notes.add("Grassy Terrain halves " + move.name());
        }
        if (isGrounded(attacker, field) && field.terrain == Terrain.ELECTRIC && moveType == PokeType.ELECTRIC) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Electric Terrain");
        }
        if (field.terrain == Terrain.ELECTRIC && move.id().equals("risingvoltage") && isGrounded(defender, field)) {
            power *= 2;
            notes.add("Rising Voltage terrain x2");
        }
        if (isGrounded(attacker, field) && field.terrain == Terrain.GRASSY && moveType == PokeType.GRASS) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Grassy Terrain");
        }
        if (isGrounded(attacker, field) && field.terrain == Terrain.PSYCHIC && moveType == PokeType.PSYCHIC) {
            power = (int) Math.floor(power * 1.3);
            notes.add("Psychic Terrain");
        }
        if (field.terrain == Terrain.PSYCHIC && move.id().equals("expandingforce") && isGrounded(attacker, field)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Expanding Force terrain x1.5");
        }
        if (field.terrain == Terrain.MISTY && move.id().equals("mistyexplosion") && isGrounded(attacker, field)) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Misty Explosion terrain x1.5");
        }
        if (field.terrain == Terrain.ELECTRIC && move.id().equals("psyblade")) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Psyblade terrain x1.5");
        }
        if (field.gravity && move.id().equals("gravapple")) {
            power = (int) Math.floor(power * 1.5);
            notes.add("Grav Apple gravity x1.5");
        }
        return power;
    }

    private static int variableBasePower(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field,
                                         SideConditions attackerSide, SideConditions defenderSide, List<String> notes,
                                         List<CalcWarning> warnings) {
        String id = move.id();
        int power = move.basePower();
        if (id.equals("gyroball")) {
            power = Math.min(150, 1 + 25 * speedStat(defender, field, defenderSide)
                    / Math.max(1, speedStat(attacker, field, attackerSide)));
        } else if (id.equals("electroball")) {
            double ratio = speedStat(attacker, field, attackerSide)
                    / (double) Math.max(1, speedStat(defender, field, defenderSide));
            power = ratio >= 4 ? 150 : ratio >= 3 ? 120 : ratio >= 2 ? 80 : ratio > 1 ? 60 : 40;
        } else if (id.equals("lowkick") || id.equals("grassknot")) {
            power = weightBasedPower(defender.species.weightKg());
        } else if (id.equals("heavyslam") || id.equals("heatcrash")) {
            double ratio = attacker.species.weightKg() / Math.max(0.1, defender.species.weightKg());
            power = ratio >= 5 ? 120 : ratio >= 4 ? 100 : ratio >= 3 ? 80 : ratio >= 2 ? 60 : 40;
        } else if (id.equals("storedpower") || id.equals("powertrip")) {
            int positiveBoosts = 0;
            for (Stat stat : new Stat[]{Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE}) {
                positiveBoosts += Math.max(0, attacker.boosts.get(stat));
            }
            power = 20 + 20 * positiveBoosts;
        } else if (id.equals("punishment")) {
            int positiveBoosts = 0;
            for (Stat stat : new Stat[]{Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE}) {
                positiveBoosts += Math.max(0, defender.boosts.get(stat));
            }
            power = Math.min(200, 60 + 20 * positiveBoosts);
        } else if (id.equals("ragefist") && attacker.battleHistoryKnown) {
            power = Math.min(350, 50 + 50 * attacker.timesHit);
        } else if (id.equals("lastrespects") && attacker.battleHistoryKnown) {
            power = Math.min(300, 50 + 50 * attacker.faintedAllies);
        } else if (id.equals("furycutter") && attacker.battleHistoryKnown) {
            power = Math.min(160, move.basePower() * (1 << Math.min(3, Math.max(0, attacker.consecutiveMoveUses))));
        } else if ((id.equals("rollout") || id.equals("iceball")) && attacker.battleHistoryKnown) {
            power = move.basePower() * (1 << Math.min(4, Math.max(0, attacker.consecutiveMoveUses)));
            if (attacker.defenseCurlUsed) power *= 2;
        } else if (id.equals("echoedvoice") && attacker.battleHistoryKnown) {
            power = Math.min(200, move.basePower() + 40 * Math.max(0, attacker.echoedVoiceChain));
        } else if (id.equals("retaliate") && attacker.battleHistoryKnown && attacker.allyFaintedPreviousTurn) {
            power *= 2;
        } else if ((id.equals("stompingtantrum") || id.equals("temperflare"))
                && attacker.battleHistoryKnown && attacker.lastMoveFailed) {
            power *= 2;
        } else if ((id.equals("avalanche") || id.equals("revenge"))
                && attacker.battleHistoryKnown && attacker.lastDamageTaken > 0) {
            power *= 2;
        } else if (id.equals("payback")
                && movesAfter(attacker, defender, field, attackerSide, defenderSide)) {
            power *= 2;
        } else if (id.equals("watershuriken") && hasAspect(attacker, "ash")) {
            power = 20;
        } else if (id.equals("eruption") || id.equals("waterspout") || id.equals("dragonenergy")) {
            power = Math.max(1, move.basePower() * attacker.visibleHp() / attacker.maxHp());
        } else if (id.equals("flail") || id.equals("reversal")) {
            int ratio = 48 * attacker.visibleHp() / attacker.maxHp();
            power = ratio <= 1 ? 200 : ratio <= 4 ? 150 : ratio <= 9 ? 100 : ratio <= 16 ? 80 : ratio <= 32 ? 40 : 20;
        } else if (id.equals("crushgrip") || id.equals("wringout")) {
            power = Math.max(1, 1 + 120 * defender.visibleHp() / defender.maxHp());
        } else if (id.equals("hardpress")) {
            power = Math.max(1, 100 * defender.visibleHp() / defender.maxHp());
        } else if (id.equals("facade") && (attacker.status == StatusCondition.BURN
                || attacker.status == StatusCondition.POISON || attacker.status == StatusCondition.PARALYSIS)) {
            power *= 2;
        } else if ((id.equals("hex") || id.equals("infernalparade")) && defender.status != StatusCondition.NONE) {
            power *= 2;
        } else if ((id.equals("venoshock") || id.equals("barbbarrage")) && defender.status == StatusCondition.POISON) {
            power *= 2;
        } else if (id.equals("brine") && defender.visibleHp() * 2 <= defender.maxHp()) {
            power *= 2;
        } else if (id.equals("acrobatics") && hasItem(attacker, "None")) {
            power *= 2;
        } else if (id.equals("knockoff") && !hasItem(defender, "None")) {
            power = (int) Math.floor(power * 1.5);
        } else if (id.equals("wakeupslap") && defender.status == StatusCondition.SLEEP) {
            power *= 2;
        } else if (id.equals("smellingsalts") && defender.status == StatusCondition.PARALYSIS) {
            power *= 2;
        }
        if (power != move.basePower()) notes.add(move.name() + " power " + power);
        if (power <= 0) {
            warnings.add(CalcWarning.of("variable_power_fallback"));
            return Math.max(1, move.basePower());
        }
        return power;
    }

    private static int weightBasedPower(double weightKg) {
        if (weightKg < 10) return 20;
        if (weightKg < 25) return 40;
        if (weightKg < 50) return 60;
        if (weightKg < 100) return 80;
        if (weightKg < 200) return 100;
        return 120;
    }

    private static FixedDamage fixedDamage(PokemonSet attacker, PokemonSet defender, MoveData move) {
        if (OHKO_MOVES.contains(move.id())) {
            return new FixedDamage(defender.visibleHp(), defender.visibleHp(), move.id());
        }
        int damage = switch (move.id()) {
            case "seismictoss", "nightshade" -> attacker.level;
            case "dragonrage" -> 40;
            case "sonicboom" -> 20;
            case "superfang", "ruination", "naturesmadness" -> Math.max(1, defender.visibleHp() / 2);
            case "guardianofalola" -> Math.max(1, (int) Math.floor(defender.visibleHp() * 0.75));
            case "endeavor" -> Math.max(0, defender.visibleHp() - attacker.visibleHp());
            case "finalgambit" -> attacker.visibleHp();
            case "counter" -> attacker.lastDamageCategory == DamageCategory.PHYSICAL
                    ? attacker.lastDamageTaken * 2 : 0;
            case "mirrorcoat" -> attacker.lastDamageCategory == DamageCategory.SPECIAL
                    ? attacker.lastDamageTaken * 2 : 0;
            case "metalburst", "comeuppance" -> (int) Math.floor(attacker.lastDamageTaken * 1.5);
            default -> -1;
        };
        if (damage >= 0) {
            return new FixedDamage(damage, damage, move.id());
        }
        if (move.id().equals("psywave")) {
            return new FixedDamage(Math.max(1, attacker.level / 2), Math.max(1, attacker.level * 3 / 2), move.id());
        }
        return null;
    }

    private static DamageResult fixedDamageResult(PokemonSet attacker, PokemonSet defender, MoveData move,
                                                  FixedDamage fixed, List<CalcWarning> warnings) {
        if (fixed.maxDamage() <= 0) {
            return DamageResult.zero(move, "condition not met", warnings);
        }
        int min = Math.min(defender.visibleHp(), fixed.minDamage());
        int max = Math.min(defender.visibleHp(), fixed.maxDamage());
        HashMap<Integer, Double> distribution = new HashMap<>();
        double probability = 1.0 / (max - min + 1);
        for (int damage = min; damage <= max; damage++) {
            distribution.put(damage, probability);
        }
        double minPercent = min * 100.0 / defender.maxHp();
        double maxPercent = max * 100.0 / defender.maxHp();
        String koChance = koChance(distribution, defender.visibleHp());
        String line = attacker.species.name() + " " + move.name() + " vs. " + defender.species.name()
                + ": " + min + "-" + max + " (" + percent(minPercent) + " - " + percent(maxPercent)
                + "%) -- " + koChance;
        ArrayList<Integer> rolls = new ArrayList<>();
        for (int damage = min; damage <= max && rolls.size() < 16; damage++) rolls.add(damage);
        return new DamageResult(move, min, max, minPercent, maxPercent, List.copyOf(rolls), 0, koChance,
                line, move.name() + " " + min + "-" + max + " (" + percent(minPercent) + "-"
                + percent(maxPercent) + "%) | " + koChance, List.of("fixed damage: " + fixed.reason()),
                List.copyOf(warnings));
    }

    private static void addContextWarnings(PokemonSet attacker, PokemonSet defender, MoveData move, List<CalcWarning> warnings) {
        if (!attacker.itemKnown || !attacker.abilityKnown || !attacker.natureKnown || !attacker.statsKnown) {
            warnings.add(CalcWarning.of("attacker_set_unknown"));
        }
        if (!defender.itemKnown || !defender.abilityKnown || !defender.natureKnown || !defender.statsKnown) {
            warnings.add(CalcWarning.of("target_set_unknown"));
        }
        if (ATTACKER_HP_POWER_MOVES.contains(move.id()) && attacker.currentHp < 0) {
            warnings.add(CalcWarning.of("attacker_hp_unknown"));
        }
        if (DEFENDER_HP_POWER_MOVES.contains(move.id()) && defender.currentHp < 0) {
            warnings.add(CalcWarning.of("target_hp_unknown"));
        }
        if (WEIGHT_POWER_MOVES.contains(move.id())
                && (defender.species.weightKg() <= 0.0
                || ((move.id().equals("heavyslam") || move.id().equals("heatcrash")) && attacker.species.weightKg() <= 0.0))) {
            warnings.add(CalcWarning.of("weight_unavailable"));
        }
        if (UNSUPPORTED_CONTEXT_MOVES.contains(move.id())) {
            warnings.add(CalcWarning.of("turn_context", move.name()));
        }
        if ((move.id().equals("ragefist") || move.id().equals("lastrespects") || move.id().equals("furycutter")
                || move.id().equals("rollout") || move.id().equals("iceball") || move.id().equals("echoedvoice")
                || move.id().equals("retaliate") || move.id().equals("stompingtantrum")
                || move.id().equals("temperflare") || move.id().equals("avalanche")
                || move.id().equals("revenge") || REACTIVE_DAMAGE_MOVES.contains(move.id()))
                && !attacker.battleHistoryKnown) {
            warnings.add(CalcWarning.of("battle_history", move.name()));
        }
        if (hasAbility(attacker, "Stakeout") && !attacker.battleHistoryKnown) warnings.add(CalcWarning.of("stakeout"));
        if (hasAbility(attacker, "Supreme Overlord") && !attacker.battleHistoryKnown) warnings.add(CalcWarning.of("supreme_overlord"));
        if (hasAbility(attacker, "Rivalry")) warnings.add(CalcWarning.of("rivalry"));
        if (hasAbility(attacker, "Download")) warnings.add(CalcWarning.of("download"));
        if (hasAbility(attacker, "Analytic")) warnings.add(CalcWarning.of("move_order"));
        if (hasAbility(attacker, "Slow Start") && attacker.turnsActive < 0) {
            warnings.add(CalcWarning.of("slow_start"));
        }
        if (hasItem(attacker, "Metronome") && !attacker.battleHistoryKnown) {
            warnings.add(CalcWarning.of("metronome"));
        }
        if (hasAbility(attacker, "Flash Fire") && !attacker.battleHistoryKnown) {
            warnings.add(CalcWarning.of("flash_fire"));
        }
    }

    private static PokeType terrainType(Terrain terrain) {
        return switch (terrain) {
            case ELECTRIC -> PokeType.ELECTRIC;
            case GRASSY -> PokeType.GRASS;
            case MISTY -> PokeType.FAIRY;
            case PSYCHIC -> PokeType.PSYCHIC;
            case NONE -> PokeType.NORMAL;
        };
    }

    private static int offensiveStat(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field,
                                     SideConditions attackerSide, SideConditions defenderSide,
                                     boolean criticalHit, List<String> notes) {
        Stat stat = move.category() == DamageCategory.PHYSICAL ? Stat.ATK : Stat.SPA;
        PokemonSet statOwner = attacker;
        if (move.id().equals("bodypress")) {
            stat = Stat.DEF;
            notes.add("Body Press uses Defense");
        } else if (move.id().equals("foulplay")) {
            statOwner = defender;
            stat = Stat.ATK;
            notes.add("Foul Play uses target Attack");
        }
        PokemonSet copy = statOwner.copy();
        if (criticalHit && copy.boosts.get(stat) < 0) {
            copy.boosts.put(stat, 0);
        }
        boolean ignoreBoosts = statOwner == attacker
                ? hasAbility(defender, "Unaware")
                : hasAbility(attacker, "Unaware");
        int value = stat(copy, stat, ignoreBoosts);
        value = weatherStatModifier(copy, stat, value, field, notes);
        boolean usesAttackerAttack = statOwner == attacker && stat == Stat.ATK;
        boolean usesAttackerSpecialAttack = statOwner == attacker && stat == Stat.SPA;
        if (statOwner == attacker && move.category() == DamageCategory.PHYSICAL
                && (hasAbility(defender, "Tablets of Ruin")
                || hasPartnerAbility(attackerSide, "Tablets of Ruin")
                || hasPartnerAbility(defenderSide, "Tablets of Ruin"))) {
            value = (int) Math.floor(value * 0.75);
            notes.add("Tablets of Ruin");
        }
        if (statOwner == attacker && move.category() == DamageCategory.SPECIAL
                && (hasAbility(defender, "Vessel of Ruin")
                || hasPartnerAbility(attackerSide, "Vessel of Ruin")
                || hasPartnerAbility(defenderSide, "Vessel of Ruin"))) {
            value = (int) Math.floor(value * 0.75);
            notes.add("Vessel of Ruin");
        }
        if (field.doubles && statOwner == attacker && stat == Stat.ATK && field.weather == Weather.SUN
                && hasPartnerAbility(attackerSide, "Flower Gift")) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Partner Flower Gift");
        }
        if (field.doubles && statOwner == attacker && stat == Stat.SPA
                && (hasAbility(attacker, "Plus") || hasAbility(attacker, "Minus"))
                && (hasPartnerAbility(attackerSide, "Plus") || hasPartnerAbility(attackerSide, "Minus"))) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Plus/Minus");
        }
        if (usesAttackerAttack && hasAbility(attacker, "Hustle")) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Hustle");
        }
        if (usesAttackerAttack && hasAbility(attacker, "Gorilla Tactics")) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Gorilla Tactics");
        }
        if (usesAttackerSpecialAttack && hasAbility(attacker, "Flare Boost") && attacker.status == StatusCondition.BURN) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Flare Boost");
        }
        if (usesAttackerAttack && hasAbility(attacker, "Toxic Boost")
                && (attacker.status == StatusCondition.POISON)) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Toxic Boost");
        }
        if (hasAbility(attacker, "Defeatist") && attacker.visibleHp() * 2 <= attacker.maxHp()) {
            value = Math.max(1, (int) Math.floor(value * 0.5));
            notes.add("Defeatist");
        }
        if (usesAttackerAttack && slowStartActive(attacker)) {
            value = Math.max(1, (int) Math.floor(value * 0.5));
            notes.add("Slow Start");
        }
        return value;
    }

    private static int defensiveStat(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field,
                                     SideConditions attackerSide, SideConditions defenderSide,
                                     boolean criticalHit, List<String> notes) {
        Stat stat = move.category() == DamageCategory.PHYSICAL ? Stat.DEF : Stat.SPD;
        if (Set.of("psyshock", "psystrike", "secretsword").contains(move.id())) {
            stat = Stat.DEF;
            notes.add(move.name() + " targets Defense");
        }
        PokemonSet copy = defender.copy();
        if (criticalHit && copy.boosts.get(stat) > 0) {
            copy.boosts.put(stat, 0);
        }
        boolean ignoreBoosts = hasAbility(attacker, "Unaware");
        int value = stat(copy, stat, ignoreBoosts);
        value = weatherStatModifier(copy, stat, value, field, notes);
        if (move.category() == DamageCategory.PHYSICAL && (hasAbility(attacker, "Sword of Ruin")
                || hasPartnerAbility(attackerSide, "Sword of Ruin")
                || hasPartnerAbility(defenderSide, "Sword of Ruin"))) {
            value = (int) Math.floor(value * 0.75);
            notes.add("Sword of Ruin");
        }
        if (move.category() == DamageCategory.SPECIAL && (hasAbility(attacker, "Beads of Ruin")
                || hasPartnerAbility(attackerSide, "Beads of Ruin")
                || hasPartnerAbility(defenderSide, "Beads of Ruin"))) {
            value = (int) Math.floor(value * 0.75);
            notes.add("Beads of Ruin");
        }
        if (field.doubles && stat == Stat.SPD && field.weather == Weather.SUN
                && hasPartnerAbility(defenderSide, "Flower Gift")) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Partner Flower Gift");
        }
        boolean ignoreDefensiveAbility = ignoresDefensiveAbilities(attacker);
        if (!ignoreDefensiveAbility && stat == Stat.DEF && hasAbility(defender, "Fur Coat")) {
            value *= 2;
            notes.add("Fur Coat");
        }
        if (!ignoreDefensiveAbility && stat == Stat.DEF && hasAbility(defender, "Marvel Scale") && defender.status != StatusCondition.NONE) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Marvel Scale");
        }
        if (!ignoreDefensiveAbility && stat == Stat.DEF && hasAbility(defender, "Grass Pelt") && field.terrain == Terrain.GRASSY) {
            value = (int) Math.floor(value * 1.5);
            notes.add("Grass Pelt");
        }
        if (!ignoreDefensiveAbility && stat == Stat.SPD && hasAbility(defender, "Ice Scales")) {
            value *= 2;
            notes.add("Ice Scales");
        }
        return value;
    }

    private static double weatherModifier(PokemonSet attacker, PokemonSet defender, MoveData move,
                                          PokeType moveType, FieldState field, List<String> notes) {
        if ((hasItem(attacker, "Utility Umbrella") || hasItem(defender, "Utility Umbrella"))
                && (field.weather == Weather.SUN || field.weather == Weather.RAIN)) {
            notes.add("Utility Umbrella");
            return 1.0;
        }
        if (field.weather == Weather.SUN) {
            if (move.id().equals("hydrosteam") && moveType == PokeType.WATER) {
                notes.add("Sun Hydro Steam x1.5");
                return 1.5;
            }
            if (moveType == PokeType.FIRE) {
                notes.add("Sun fire x1.5");
                return 1.5;
            }
            if (moveType == PokeType.WATER) {
                notes.add("Sun water x0.5");
                return 0.5;
            }
        }
        if (field.weather == Weather.RAIN) {
            if (moveType == PokeType.WATER) {
                notes.add("Rain water x1.5");
                return 1.5;
            }
            if (moveType == PokeType.FIRE) {
                notes.add("Rain fire x0.5");
                return 0.5;
            }
        }
        return 1.0;
    }

    private static double randomlessModifier(PokemonSet attacker, PokemonSet defender, MoveData move, PokeType moveType,
                                             FieldState field, SideConditions attackerSide,
                                             SideConditions defenderSide, double effectiveness,
                                             boolean criticalHit, List<String> notes) {
        double modifier = 1.0;
        boolean usesAttackStat = move.category() == DamageCategory.PHYSICAL
                && !Set.of("bodypress", "foulplay").contains(move.id());
        if (attacker.status == StatusCondition.BURN && usesAttackStat
                && !move.id().equals("facade") && !hasAbility(attacker, "Guts")) {
            modifier *= 0.5;
            notes.add("burn x0.5");
        }
        if (hasAbility(attacker, "Guts") && attacker.status != StatusCondition.NONE && usesAttackStat) {
            modifier *= 1.5;
            notes.add("Guts");
        }
        if (hasItem(attacker, "Life Orb")) {
            modifier *= 1.3;
            notes.add("Life Orb");
        }
        if (hasItem(attacker, "Expert Belt") && effectiveness > 1.0) {
            modifier *= 1.2;
            notes.add("Expert Belt");
        }
        modifier *= itemDamageModifier(attacker, move, moveType, notes);
        modifier *= offensiveAbilityModifier(attacker, defender, move, moveType, field, effectiveness, notes);
        if (field.doubles && move.category() == DamageCategory.SPECIAL
                && hasPartnerAbility(attackerSide, "Battery")) {
            modifier *= 1.3;
            notes.add("Partner Battery");
        }
        if (field.doubles && hasPartnerAbility(attackerSide, "Power Spot")) {
            modifier *= 1.3;
            notes.add("Partner Power Spot");
        }
        if (field.doubles && moveType == PokeType.STEEL
                && hasPartnerAbility(attackerSide, "Steely Spirit")) {
            modifier *= 1.5;
            notes.add("Partner Steely Spirit");
        }
        if ((move.id().equals("collisioncourse") || move.id().equals("electrodrift")) && effectiveness > 1.0) {
            modifier *= 5461.0 / 4096.0;
            notes.add(move.name() + " super effective boost");
        }
        if (hasAbility(attacker, "Stakeout") && defender.switchedInThisTurn) {
            modifier *= 2.0;
            notes.add("Stakeout");
        }
        if (hasAbility(attacker, "Supreme Overlord") && attacker.battleHistoryKnown && attacker.faintedAllies > 0) {
            double supreme = 1.0 + 0.1 * Math.min(5, attacker.faintedAllies);
            modifier *= supreme;
            notes.add("Supreme Overlord x" + trim(supreme));
        }
        if (isResistBerry(defender.item, moveType) && (effectiveness > 1.0 || moveType == PokeType.NORMAL)) {
            double berryModifier = hasAbility(defender, "Ripen") ? 0.25 : 0.5;
            modifier *= berryModifier;
            notes.add(defender.item + (berryModifier == 0.25 ? " + Ripen" : ""));
        }
        if (!ignoresDefensiveAbilities(attacker)) {
            if (hasAbility(defender, "Filter") || hasAbility(defender, "Solid Rock") || hasAbility(defender, "Prism Armor")) {
                if (effectiveness > 1.0) {
                    modifier *= 0.75;
                    notes.add(defender.ability);
                }
            }
            if (hasAbility(defender, "Thick Fat") && (moveType == PokeType.FIRE || moveType == PokeType.ICE)) {
                modifier *= 0.5;
                notes.add("Thick Fat");
            }
            if (hasAbility(defender, "Heatproof") && moveType == PokeType.FIRE) {
                modifier *= 0.5;
                notes.add("Heatproof");
            }
            if (hasAbility(defender, "Water Bubble") && moveType == PokeType.FIRE) {
                modifier *= 0.5;
                notes.add("Water Bubble fire resist");
            }
            if (hasAbility(defender, "Dry Skin") && moveType == PokeType.FIRE) {
                modifier *= 1.25;
                notes.add("Dry Skin fire weakness");
            }
            if (hasAbility(defender, "Fluffy") && moveType == PokeType.FIRE) {
                modifier *= 2.0;
                notes.add("Fluffy fire weakness");
            }
            if (hasAbility(defender, "Fluffy") && isContactMove(move)) {
                modifier *= 0.5;
                notes.add("Fluffy contact resist");
            }
            if (hasAbility(defender, "Purifying Salt") && moveType == PokeType.GHOST) {
                modifier *= 0.5;
                notes.add("Purifying Salt");
            }
            if ((hasAbility(defender, "Multiscale") || hasAbility(defender, "Shadow Shield")) && defender.visibleHp() == defender.maxHp()) {
                modifier *= 0.5;
                notes.add(defender.ability);
            }
            if (hasAbility(defender, "Punk Rock") && hasMoveFlag(move, "sound", SOUND_MOVES)) {
                modifier *= 0.5;
                notes.add("Punk Rock resist");
            }
        }
        if (isGrounded(defender, field) && field.terrain == Terrain.MISTY && moveType == PokeType.DRAGON) {
            modifier *= 0.5;
            notes.add("Misty Terrain");
        }
        if (field.doubles && (field.friendGuard || defenderSide.friendGuard
                || hasPartnerAbility(defenderSide, "Friend Guard"))) {
            modifier *= 0.75;
            notes.add("Friend Guard");
        }
        boolean breaksScreens = Set.of("brickbreak", "psychicfangs", "ragingbull").contains(move.id());
        if (!criticalHit && !breaksScreens && !hasAbility(attacker, "Infiltrator")) {
            if (defenderSide.auroraVeil || (defenderSide.reflect && move.category() == DamageCategory.PHYSICAL) || (defenderSide.lightScreen && move.category() == DamageCategory.SPECIAL)) {
                modifier *= field.doubles ? 2.0 / 3.0 : 0.5;
                notes.add(defenderSide.auroraVeil ? "Aurora Veil" : move.category() == DamageCategory.PHYSICAL ? "Reflect" : "Light Screen");
            }
        }
        return modifier;
    }

    private static boolean hasPartnerAbility(SideConditions side, String expected) {
        return side != null && TropimonDex.normalize(side.partnerAbility)
                .equals(TropimonDex.normalize(expected));
    }

    private static double offensiveAbilityModifier(PokemonSet attacker, PokemonSet defender, MoveData move,
                                                    PokeType moveType, FieldState field, double effectiveness,
                                                    List<String> notes) {
        double modifier = 1.0;
        boolean matchingAura = moveType == PokeType.DARK
                && (hasAbility(attacker, "Dark Aura") || hasAbility(defender, "Dark Aura"))
                || moveType == PokeType.FAIRY
                && (hasAbility(attacker, "Fairy Aura") || hasAbility(defender, "Fairy Aura"));
        if (matchingAura) {
            boolean auraBreak = hasAbility(attacker, "Aura Break") || hasAbility(defender, "Aura Break");
            modifier *= auraBreak ? 0.75 : 4.0 / 3.0;
            notes.add(auraBreak ? "Aura Break" : moveType == PokeType.DARK ? "Dark Aura" : "Fairy Aura");
        }
        if (hasAbility(attacker, "Punk Rock") && hasMoveFlag(move, "sound", SOUND_MOVES)) {
            modifier *= 1.3;
            notes.add("Punk Rock");
        }
        if (hasAbility(attacker, "Reckless") && hasMoveFlag(move, "recoil", RECOIL_MOVES)) {
            modifier *= 1.2;
            notes.add("Reckless");
        }
        if (hasAbility(attacker, "Sand Force") && field.weather == Weather.SAND
                && (moveType == PokeType.ROCK || moveType == PokeType.GROUND || moveType == PokeType.STEEL)) {
            modifier *= 1.3;
            notes.add("Sand Force");
        }
        if (hasAbility(attacker, "Tinted Lens") && effectiveness < 1.0) {
            modifier *= 2.0;
            notes.add("Tinted Lens");
        }
        if (hasAbility(attacker, "Water Bubble") && moveType == PokeType.WATER) {
            modifier *= 2.0;
            notes.add("Water Bubble");
        }
        if (hasAbility(attacker, "Steelworker") && moveType == PokeType.STEEL) {
            modifier *= 1.5;
            notes.add("Steelworker");
        }
        if (hasAbility(attacker, "Steely Spirit") && moveType == PokeType.STEEL) {
            modifier *= 1.5;
            notes.add("Steely Spirit");
        }
        if (hasAbility(attacker, "Rocky Payload") && moveType == PokeType.ROCK) {
            modifier *= 1.5;
            notes.add("Rocky Payload");
        }
        if (hasAbility(attacker, "Dragon's Maw") && moveType == PokeType.DRAGON) {
            modifier *= 1.5;
            notes.add("Dragon's Maw");
        }
        if (hasAbility(attacker, "Transistor") && moveType == PokeType.ELECTRIC) {
            modifier *= 1.3;
            notes.add("Transistor");
        }
        if (hasAbility(attacker, "Neuroforce") && effectiveness > 1.0) {
            modifier *= 1.25;
            notes.add("Neuroforce");
        }
        if (hasAbility(attacker, "Flash Fire") && attacker.flashFireActive && moveType == PokeType.FIRE) {
            modifier *= 1.5;
            notes.add("Flash Fire");
        }
        if (lowHp(attacker)) {
            if (hasAbility(attacker, "Overgrow") && moveType == PokeType.GRASS) {
                modifier *= 1.5;
                notes.add("Overgrow");
            }
            if (hasAbility(attacker, "Blaze") && moveType == PokeType.FIRE) {
                modifier *= 1.5;
                notes.add("Blaze");
            }
            if (hasAbility(attacker, "Torrent") && moveType == PokeType.WATER) {
                modifier *= 1.5;
                notes.add("Torrent");
            }
            if (hasAbility(attacker, "Swarm") && moveType == PokeType.BUG) {
                modifier *= 1.5;
                notes.add("Swarm");
            }
        }
        return modifier;
    }

    private static double stabModifier(PokemonSet attacker, PokeType moveType, List<String> notes) {
        boolean originalStab = attacker.species.types().contains(moveType);
        boolean teraStab = attacker.terastallized && attacker.teraType == moveType;
        if (hasAbility(attacker, "Adaptability") && originalStab && teraStab) {
            notes.add("Adaptability Tera STAB");
            return 2.25;
        }
        if (hasAbility(attacker, "Adaptability") && (originalStab || teraStab)) {
            notes.add("Adaptability STAB");
            return 2.0;
        }
        if (originalStab && teraStab) {
            notes.add("Tera STAB x2");
            return 2.0;
        }
        if (originalStab || teraStab) {
            notes.add("STAB");
            return 1.5;
        }
        return 1.0;
    }

    private static int itemStatModifier(PokemonSet pokemon, Stat stat, int value) {
        if (hasItem(pokemon, "Choice Band") && stat == Stat.ATK) {
            return (int) Math.floor(value * 1.5);
        }
        if (hasItem(pokemon, "Choice Specs") && stat == Stat.SPA) {
            return (int) Math.floor(value * 1.5);
        }
        if (hasItem(pokemon, "Choice Scarf") && stat == Stat.SPE) {
            return (int) Math.floor(value * 1.5);
        }
        if (hasItem(pokemon, "Assault Vest") && stat == Stat.SPD) {
            return (int) Math.floor(value * 1.5);
        }
        if (hasItem(pokemon, "Eviolite") && pokemon.species.notFullyEvolved() && (stat == Stat.DEF || stat == Stat.SPD)) {
            return (int) Math.floor(value * 1.5);
        }
        if (hasItem(pokemon, "Light Ball") && isSpecies(pokemon, "pikachu") && (stat == Stat.ATK || stat == Stat.SPA)) {
            return value * 2;
        }
        if (hasItem(pokemon, "Thick Club") && (isSpecies(pokemon, "cubone") || isSpecies(pokemon, "marowak")) && stat == Stat.ATK) {
            return value * 2;
        }
        if (hasItem(pokemon, "Deep Sea Tooth") && isSpecies(pokemon, "clamperl") && stat == Stat.SPA) {
            return value * 2;
        }
        if (hasItem(pokemon, "Deep Sea Scale") && isSpecies(pokemon, "clamperl") && stat == Stat.SPD) {
            return value * 2;
        }
        if (hasItem(pokemon, "Metal Powder") && isSpecies(pokemon, "ditto") && stat == Stat.DEF) {
            return value * 2;
        }
        if (hasItem(pokemon, "Quick Powder") && isSpecies(pokemon, "ditto") && stat == Stat.SPE) {
            return value * 2;
        }
        if (stat == Stat.SPE && SPEED_HALVING_ITEMS.contains(normalize(pokemon.item))) {
            return Math.max(1, (int) Math.floor(value * 0.5));
        }
        return value;
    }

    private static double itemDamageModifier(PokemonSet attacker, MoveData move, PokeType moveType, List<String> notes) {
        double modifier = 1.0;
        String item = normalize(attacker.item);
        if (move.category() == DamageCategory.PHYSICAL && item.equals("muscleband")) {
            modifier *= 1.1;
            notes.add("Muscle Band");
        }
        if (move.category() == DamageCategory.SPECIAL && item.equals("wiseglasses")) {
            modifier *= 1.1;
            notes.add("Wise Glasses");
        }
        if (item.equals("punchingglove") && hasMoveFlag(move, "punch", PUNCH_MOVES)) {
            modifier *= 1.1;
            notes.add("Punching Glove");
        }
        if (TYPE_BOOST_ITEMS.getOrDefault(moveType, Set.of()).contains(item)) {
            modifier *= 1.2;
            notes.add(attacker.item);
        }
        if (item.equals(normalize(moveType.displayName() + " Gem"))) {
            modifier *= 1.3;
            notes.add(attacker.item);
        }
        if ((isSpecies(attacker, "latios") || isSpecies(attacker, "latias")) && item.equals("souldew")
                && (moveType == PokeType.PSYCHIC || moveType == PokeType.DRAGON)) {
            modifier *= 1.2;
            notes.add("Soul Dew");
        }
        if (isLegendaryOrbBoost(attacker, item, moveType)) {
            modifier *= 1.2;
            notes.add(attacker.item);
        }
        if ((item.equals("hearthflamemask") || item.equals("cornerstonemask") || item.equals("wellspringmask"))
                && normalize(attacker.species.cobblemonSpeciesId()).equals("ogerpon")) {
            modifier *= 4915.0 / 4096.0;
            notes.add(attacker.item);
        }
        if (item.equals("metronome") && attacker.battleHistoryKnown && attacker.consecutiveMoveUses > 0
                && normalize(attacker.lastMoveId).equals(move.id())) {
            double metronome = Math.min(2.0, 1.0 + 0.2 * attacker.consecutiveMoveUses);
            modifier *= metronome;
            notes.add("Metronome x" + trim(metronome));
        }
        return modifier;
    }

    private static boolean isLegendaryOrbBoost(PokemonSet attacker, String item, PokeType moveType) {
        if (isSpecies(attacker, "dialga") && (item.equals("adamantorb") || item.equals("adamantcrystal"))
                && (moveType == PokeType.STEEL || moveType == PokeType.DRAGON)) {
            return true;
        }
        if (isSpecies(attacker, "palkia") && (item.equals("lustrousorb") || item.equals("lustrousglobe"))
                && (moveType == PokeType.WATER || moveType == PokeType.DRAGON)) {
            return true;
        }
        return isSpecies(attacker, "giratina") && (item.equals("griseousorb") || item.equals("griseouscore"))
                && (moveType == PokeType.GHOST || moveType == PokeType.DRAGON);
    }

    private static int abilityStatModifier(PokemonSet pokemon, Stat stat, int value) {
        if ((hasAbility(pokemon, "Huge Power") || hasAbility(pokemon, "Pure Power")) && stat == Stat.ATK) {
            return value * 2;
        }
        return value;
    }

    private static int weatherStatModifier(PokemonSet pokemon, Stat stat, int value, FieldState field, List<String> notes) {
        if (field == null || stat == Stat.HP) {
            return value;
        }
        if (field.weather == Weather.SUN) {
            if (stat == Stat.SPE && hasAbility(pokemon, "Chlorophyll")) {
                value = multiplyStat(value, 2.0, notes, "Chlorophyll");
            }
            if (stat == Stat.SPA && hasAbility(pokemon, "Solar Power")) {
                value = multiplyStat(value, 1.5, notes, "Solar Power");
            }
            if (stat == Stat.ATK && hasAbility(pokemon, "Orichalcum Pulse")) {
                value = multiplyStat(value, 4.0 / 3.0, notes, "Orichalcum Pulse");
            }
            if ((stat == Stat.ATK || stat == Stat.SPD) && hasAbility(pokemon, "Flower Gift")) {
                value = multiplyStat(value, 1.5, notes, "Flower Gift");
            }
            if (hasAbility(pokemon, "Protosynthesis") && stat == highestStat(pokemon, field, false)) {
                value = multiplyStat(value, stat == Stat.SPE ? 1.5 : 1.3, notes, "Protosynthesis");
            }
        }
        if (field.weather == Weather.RAIN && stat == Stat.SPE && hasAbility(pokemon, "Swift Swim")) {
            value = multiplyStat(value, 2.0, notes, "Swift Swim");
        }
        if (field.weather == Weather.SAND) {
            if (stat == Stat.SPE && hasAbility(pokemon, "Sand Rush")) {
                value = multiplyStat(value, 2.0, notes, "Sand Rush");
            }
            if (stat == Stat.SPD && pokemon.defensiveTypes().contains(PokeType.ROCK)) {
                value = multiplyStat(value, 1.5, notes, "Sand rock SpD x1.5");
            }
        }
        if (field.weather == Weather.SNOW) {
            if (stat == Stat.SPE && hasAbility(pokemon, "Slush Rush")) {
                value = multiplyStat(value, 2.0, notes, "Slush Rush");
            }
            if (stat == Stat.DEF && pokemon.defensiveTypes().contains(PokeType.ICE)) {
                value = multiplyStat(value, 1.5, notes, "Snow ice Defense x1.5");
            }
        }
        if (field.terrain == Terrain.ELECTRIC) {
            if (stat == Stat.SPE && hasAbility(pokemon, "Surge Surfer")) {
                value = multiplyStat(value, 2.0, notes, "Surge Surfer");
            }
            if (stat == Stat.SPA && hasAbility(pokemon, "Hadron Engine")) {
                value = multiplyStat(value, 4.0 / 3.0, notes, "Hadron Engine");
            }
            if (hasAbility(pokemon, "Quark Drive") && stat == highestStat(pokemon, field, false)) {
                value = multiplyStat(value, stat == Stat.SPE ? 1.5 : 1.3, notes, "Quark Drive");
            }
        }
        if ((pokemon.paradoxBoostActive || hasItem(pokemon, "Booster Energy")) && field.weather != Weather.SUN
                && hasAbility(pokemon, "Protosynthesis") && stat == highestStat(pokemon, field, false)) {
            value = multiplyStat(value, stat == Stat.SPE ? 1.5 : 1.3, notes, "Protosynthesis (Booster Energy)");
        }
        if ((pokemon.paradoxBoostActive || hasItem(pokemon, "Booster Energy")) && field.terrain != Terrain.ELECTRIC
                && hasAbility(pokemon, "Quark Drive") && stat == highestStat(pokemon, field, false)) {
            value = multiplyStat(value, stat == Stat.SPE ? 1.5 : 1.3, notes, "Quark Drive (Booster Energy)");
        }
        if (stat == Stat.SPE && pokemon.status == StatusCondition.PARALYSIS && !hasAbility(pokemon, "Quick Feet")) {
            value = multiplyStat(value, 0.5, notes, "Paralysis Speed x0.5");
        } else if (pokemon.status != StatusCondition.NONE && stat == Stat.SPE && hasAbility(pokemon, "Quick Feet")) {
            value = multiplyStat(value, 1.5, notes, "Quick Feet");
        }
        if (stat == Stat.SPE && slowStartActive(pokemon)) {
            value = multiplyStat(value, 0.5, notes, "Slow Start");
        }
        return Math.max(1, value);
    }

    private static int multiplyStat(int value, double modifier, List<String> notes, String note) {
        if (notes != null && !notes.contains(note)) {
            notes.add(note);
        }
        return (int) Math.floor(value * modifier);
    }

    private static Stat highestStat(PokemonSet pokemon, FieldState field, boolean includeWeatherBoosts) {
        Stat best = Stat.ATK;
        int bestValue = -1;
        for (Stat candidate : new Stat[]{Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE}) {
            int value = storedStat(pokemon, candidate, false);
            if (includeWeatherBoosts) {
                value = weatherStatModifier(pokemon, candidate, value, field, null);
            }
            if (value > bestValue) {
                bestValue = value;
                best = candidate;
            }
        }
        return best;
    }

    private static int speedStat(PokemonSet pokemon, FieldState field, SideConditions side) {
        int value = weatherStatModifier(pokemon, Stat.SPE, stat(pokemon, Stat.SPE, false), field, null);
        return sideStatModifier(Stat.SPE, value, side, null);
    }

    private static boolean movesBefore(PokemonSet attacker, PokemonSet defender, FieldState field,
                                       SideConditions attackerSide, SideConditions defenderSide) {
        int attackerSpeed = speedStat(attacker, field, attackerSide);
        int defenderSpeed = speedStat(defender, field, defenderSide);
        return field.trickRoom ? attackerSpeed < defenderSpeed : attackerSpeed > defenderSpeed;
    }

    private static boolean movesAfter(PokemonSet attacker, PokemonSet defender, FieldState field,
                                      SideConditions attackerSide, SideConditions defenderSide) {
        int attackerSpeed = speedStat(attacker, field, attackerSide);
        int defenderSpeed = speedStat(defender, field, defenderSide);
        return field.trickRoom ? attackerSpeed > defenderSpeed : attackerSpeed < defenderSpeed;
    }

    private static int sideStatModifier(Stat stat, int value, SideConditions side, List<String> notes) {
        if (side != null && side.tailwind && stat == Stat.SPE) {
            value = multiplyStat(value, 2.0, notes, "Tailwind");
        }
        return value;
    }

    private static int applyBoost(int value, int boost) {
        if (boost > 0) {
            return (int) Math.floor(value * (2.0 + boost) / 2.0);
        }
        if (boost < 0) {
            return (int) Math.floor(value * 2.0 / (2.0 - boost));
        }
        return value;
    }

    private static String koChance(List<Integer> rolls, int hp) {
        long ohko = rolls.stream().filter(roll -> roll >= hp).count();
        if (ohko == rolls.size()) {
            return "guaranteed OHKO";
        }
        if (ohko > 0) {
            return percent(ohko * 100.0 / rolls.size()) + "% chance to OHKO";
        }
        int two = 0;
        int three = 0;
        for (int first : rolls) {
            for (int second : rolls) {
                if (first + second >= hp) {
                    two++;
                }
                for (int third : rolls) {
                    if (first + second + third >= hp) {
                        three++;
                    }
                }
            }
        }
        if (two == rolls.size() * rolls.size()) {
            return "guaranteed 2HKO";
        }
        if (two > 0) {
            return percent(two * 100.0 / (rolls.size() * rolls.size())) + "% chance to 2HKO";
        }
        if (three == rolls.size() * rolls.size() * rolls.size()) {
            return "guaranteed 3HKO";
        }
        if (three > 0) {
            return percent(three * 100.0 / (rolls.size() * rolls.size() * rolls.size())) + "% chance to 3HKO";
        }
        return "possible 4HKO+";
    }

    private static String koChance(Map<Integer, Double> distribution, int hp) {
        double ohko = chanceAtLeast(distribution, hp);
        if (ohko >= 1.0 - 1.0e-9) {
            return "guaranteed OHKO";
        }
        if (ohko > 1.0e-9) {
            return percent(ohko * 100.0) + "% chance to OHKO";
        }
        Map<Integer, Double> twoTurns = convolve(distribution, distribution);
        double two = chanceAtLeast(twoTurns, hp);
        if (two >= 1.0 - 1.0e-9) {
            return "guaranteed 2HKO";
        }
        if (two > 1.0e-9) {
            return percent(two * 100.0) + "% chance to 2HKO";
        }
        Map<Integer, Double> threeTurns = convolve(twoTurns, distribution);
        double three = chanceAtLeast(threeTurns, hp);
        if (three >= 1.0 - 1.0e-9) {
            return "guaranteed 3HKO";
        }
        if (three > 1.0e-9) {
            return percent(three * 100.0) + "% chance to 3HKO";
        }
        return "possible 4HKO+";
    }

    private static Map<Integer, Double> convolve(Map<Integer, Double> left, Map<Integer, Double> right) {
        HashMap<Integer, Double> output = new HashMap<>();
        for (Map.Entry<Integer, Double> first : left.entrySet()) {
            for (Map.Entry<Integer, Double> second : right.entrySet()) {
                output.merge(first.getKey() + second.getKey(), first.getValue() * second.getValue(), Double::sum);
            }
        }
        return output;
    }

    private static double chanceAtLeast(Map<Integer, Double> distribution, int hp) {
        return distribution.entrySet().stream()
                .filter(entry -> entry.getKey() >= hp)
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }

    private static boolean isResistBerry(String item, PokeType type) {
        String normalized = normalize(item);
        return switch (type) {
            case NORMAL -> normalized.equals("chilanberry");
            case FIRE -> normalized.equals("occaberry");
            case WATER -> normalized.equals("passhoberry");
            case ELECTRIC -> normalized.equals("wacanberry");
            case GRASS -> normalized.equals("rindoberry");
            case ICE -> normalized.equals("yacheberry");
            case FIGHTING -> normalized.equals("chopleberry");
            case POISON -> normalized.equals("kebiaberry");
            case GROUND -> normalized.equals("shucaberry");
            case FLYING -> normalized.equals("cobaberry");
            case PSYCHIC -> normalized.equals("payapaberry");
            case BUG -> normalized.equals("tangaberry");
            case ROCK -> normalized.equals("chartiberry");
            case GHOST -> normalized.equals("kasibberry");
            case DRAGON -> normalized.equals("habanberry");
            case DARK -> normalized.equals("colburberry");
            case STEEL -> normalized.equals("babiriberry");
            case FAIRY -> normalized.equals("roseliberry");
            default -> false;
        };
    }

    private static String defensiveImmunity(PokemonSet attacker, PokemonSet defender, MoveData move, PokeType moveType,
                                            FieldState field) {
        if (OHKO_MOVES.contains(move.id()) && attacker.level < defender.level) {
            return "OHKO level check";
        }
        if (move.id().equals("sheercold") && defender.defensiveTypes().contains(PokeType.ICE)) {
            return "Sheer Cold Ice immunity";
        }
        if (moveType == PokeType.GROUND && !field.gravity && !move.id().equals("thousandarrows")
                && hasItem(defender, "Air Balloon")) {
            return "Air Balloon immunity";
        }
        int priority = effectivePriority(attacker, move);
        if (priority > 0 && isGrounded(defender, field) && field.terrain == Terrain.PSYCHIC) {
            return "Psychic Terrain blocks priority";
        }
        if (ignoresDefensiveAbilities(attacker)) {
            return null;
        }
        if (OHKO_MOVES.contains(move.id()) && hasAbility(defender, "Sturdy")) {
            return "Sturdy";
        }
        if (priority > 0 && (hasAbility(defender, "Queenly Majesty") || hasAbility(defender, "Dazzling")
                || hasAbility(defender, "Armor Tail"))) {
            return defender.ability + " blocks priority";
        }
        if (moveType == PokeType.WATER && (hasAbility(defender, "Water Absorb") || hasAbility(defender, "Storm Drain") || hasAbility(defender, "Dry Skin"))) {
            return defender.ability + " immunity";
        }
        if (moveType == PokeType.FIRE && (hasAbility(defender, "Flash Fire") || hasAbility(defender, "Well-Baked Body"))) {
            return defender.ability + " immunity";
        }
        if (moveType == PokeType.ELECTRIC && (hasAbility(defender, "Volt Absorb") || hasAbility(defender, "Lightning Rod") || hasAbility(defender, "Motor Drive"))) {
            return defender.ability + " immunity";
        }
        if (moveType == PokeType.GRASS && hasAbility(defender, "Sap Sipper")) {
            return "Sap Sipper immunity";
        }
        if (moveType == PokeType.GROUND && hasAbility(defender, "Earth Eater")) {
            return "Earth Eater immunity";
        }
        if (hasMoveFlag(move, "sound", SOUND_MOVES) && hasAbility(defender, "Soundproof")) {
            return "Soundproof immunity";
        }
        if (hasMoveFlag(move, "bullet", BULLET_MOVES) && hasAbility(defender, "Bulletproof")) {
            return "Bulletproof immunity";
        }
        if (hasMoveFlag(move, "wind", WIND_MOVES) && hasAbility(defender, "Wind Rider")) {
            return "Wind Rider immunity";
        }
        return null;
    }

    private static int effectivePriority(PokemonSet attacker, MoveData move) {
        int priority = move.priority();
        if (hasAbility(attacker, "Triage") && move.hasFlag("heal")) priority += 3;
        if (hasAbility(attacker, "Gale Wings") && move.type() == PokeType.FLYING
                && attacker.visibleHp() == attacker.maxHp()) priority += 1;
        return priority;
    }

    private static boolean isCriticalHit(PokemonSet attacker, PokemonSet defender, MoveData move, FieldState field) {
        boolean requested = field.criticalHit || move.hasFlag("alwayscrit")
                || hasAbility(attacker, "Merciless") && defender.status == StatusCondition.POISON;
        if (!requested || ignoresDefensiveAbilities(attacker)) {
            return requested;
        }
        return !hasAbility(defender, "Battle Armor") && !hasAbility(defender, "Shell Armor");
    }

    private static boolean ignoresDefensiveAbilities(PokemonSet attacker) {
        return hasAbility(attacker, "Mold Breaker") || hasAbility(attacker, "Teravolt") || hasAbility(attacker, "Turboblaze");
    }

    static boolean isContactMoveId(String moveId) {
        return CONTACT_MOVES.contains(normalize(moveId));
    }

    private static boolean isContactMove(MoveData move) {
        return move.hasFlag("contact") || (move.flags().isEmpty() && isContactMoveId(move.id()));
    }

    private static boolean hasMoveFlag(MoveData move, String flag, Set<String> fallbackIds) {
        return move.hasFlag(flag) || (move.flags().isEmpty() && fallbackIds.contains(move.id()));
    }

    private static boolean hasItem(PokemonSet pokemon, String item) {
        return normalize(pokemon.item).equals(normalize(item));
    }

    private static boolean isSpecies(PokemonSet pokemon, String speciesId) {
        String normalized = normalize(speciesId);
        return normalize(pokemon.species.id()).equals(normalized)
                || normalize(pokemon.species.name()).equals(normalized)
                || normalize(pokemon.species.cobblemonSpeciesId()).equals(normalized);
    }

    private static boolean slowStartActive(PokemonSet pokemon) {
        return hasAbility(pokemon, "Slow Start") && (pokemon.turnsActive < 0 || pokemon.turnsActive < 5);
    }

    private static boolean hasAbility(PokemonSet pokemon, String ability) {
        return normalize(pokemon.ability).equals(normalize(ability));
    }

    private static boolean hasAspect(PokemonSet pokemon, String aspect) {
        String expected = normalize(aspect);
        return pokemon.species.aspects().stream().map(DamageCalculator::normalize).anyMatch(expected::equals)
                || normalize(pokemon.species.id()).contains(expected);
    }

    private static PokeType itemMoveType(String heldItem, String moveId) {
        String item = normalize(heldItem);
        if (moveId.equals("judgment")) {
            for (Map.Entry<PokeType, Set<String>> entry : TYPE_BOOST_ITEMS.entrySet()) {
                if (entry.getValue().contains(item) && item.endsWith("plate")) return entry.getKey();
            }
        }
        if (moveId.equals("multiattack") && item.endsWith("memory")) {
            String typeName = item.substring(0, item.length() - "memory".length());
            PokeType type = PokeType.byName(typeName);
            if (type != PokeType.NONE) return type;
        }
        if (moveId.equals("technoblast")) {
            return switch (item) {
                case "burndrive" -> PokeType.FIRE;
                case "dousedrive" -> PokeType.WATER;
                case "shockdrive" -> PokeType.ELECTRIC;
                case "chilldrive" -> PokeType.ICE;
                default -> PokeType.NONE;
            };
        }
        if (moveId.equals("ivycudgel")) {
            return switch (item) {
                case "hearthflamemask" -> PokeType.FIRE;
                case "wellspringmask" -> PokeType.WATER;
                case "cornerstonemask" -> PokeType.ROCK;
                default -> PokeType.GRASS;
            };
        }
        return PokeType.NONE;
    }

    private static boolean lowHp(PokemonSet pokemon) {
        return pokemon.visibleHp() * 3 <= pokemon.maxHp();
    }

    private static boolean isGrounded(PokemonSet pokemon, FieldState field) {
        if (field.gravity) {
            return true;
        }
        if (hasItem(pokemon, "Iron Ball")) {
            return true;
        }
        return !pokemon.defensiveTypes().contains(PokeType.FLYING)
                && !hasAbility(pokemon, "Levitate")
                && !hasItem(pokemon, "Air Balloon");
    }

    private static double rawTypeEffectiveness(PokeType attackingType, List<PokeType> defendingTypes) {
        double effectiveness = 1.0;
        for (PokeType defendingType : defendingTypes) {
            effectiveness *= TYPE_CHART.get(attackingType).getOrDefault(defendingType, 1.0);
        }
        return effectiveness;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void strong(PokeType attacking, PokeType... defending) {
        for (PokeType type : defending) {
            TYPE_CHART.get(attacking).put(type, 2.0);
        }
    }

    private static void weak(PokeType attacking, PokeType... defending) {
        for (PokeType type : defending) {
            TYPE_CHART.get(attacking).put(type, 0.5);
        }
    }

    private static void immune(PokeType attacking, PokeType defending) {
        TYPE_CHART.get(attacking).put(defending, 0.0);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((int) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    record HitProfile(int minHits, int maxHits, boolean forcedHits, boolean multiAccuracy,
                      boolean parentalBond, boolean blockedFirstHit) {
    }

    private record DamageDistribution(Map<Integer, Double> probabilities, int minDamage, int maxDamage) {
    }

    private record FixedDamage(int minDamage, int maxDamage, String reason) {
    }
}
