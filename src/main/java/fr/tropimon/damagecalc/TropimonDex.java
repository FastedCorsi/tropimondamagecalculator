package fr.tropimon.damagecalc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;

final class TropimonDex {
    private static final LinkedHashMap<String, SpeciesData> SPECIES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, MoveData> MOVES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> LEGAL_MOVE_IDS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> LEGAL_ABILITIES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<String>> HIDDEN_ABILITIES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, NatureData> NATURES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, SpeciesData> SPECIES_BY_NAME = new LinkedHashMap<>();
    private static final LinkedHashMap<String, MoveData> MOVES_BY_NAME = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> ITEMS_BY_NAME = new LinkedHashMap<>();
    private static final LinkedHashMap<String, NatureData> NATURES_BY_NAME = new LinkedHashMap<>();
    private static List<SpeciesData> sortedSpecies = List.of();
    private static List<MoveData> sortedMoves = List.of();
    private static List<NatureData> sortedNatures = List.of();
    private static List<String> items = List.of("None");
    private static List<String> abilities = List.of("None");
    private static boolean naturesLoaded;
    private static boolean cobblemonLoaded;
    private static long nextCobblemonLoadAttempt;

    private TropimonDex() {
    }

    static void load() {
        loadNatures();
        tryLoadCobblemonData();
    }

    static SpeciesData species(String id) {
        load();
        SpeciesData species = SPECIES.get(normalize(id));
        if (species != null) {
            return species;
        }
        return SPECIES.values().stream().findFirst().orElseGet(() -> placeholderSpecies(id));
    }

    static MoveData move(String id) {
        load();
        MoveData move = MOVES.get(normalize(id));
        if (move != null) {
            return move;
        }
        return MOVES.values().stream().findFirst().orElseGet(() -> placeholderMove(id));
    }

    static NatureData nature(String id) {
        loadNatures();
        return NATURES.getOrDefault(normalize(id), NATURES.get("serious"));
    }

    static List<SpeciesData> speciesList() {
        load();
        return sortedSpecies;
    }

    static List<MoveData> moveList() {
        load();
        return sortedMoves;
    }

    static List<MoveData> moveListFor(SpeciesData species) {
        load();
        ArrayList<MoveData> output = new ArrayList<>(sortedMoves.size());
        Set<String> legal = Set.copyOf(LEGAL_MOVE_IDS.getOrDefault(species.id(), List.of()));
        for (MoveData move : sortedMoves) {
            if (legal.contains(move.id())) output.add(move);
        }
        for (MoveData move : sortedMoves) {
            if (!legal.contains(move.id())) output.add(move);
        }
        return output;
    }

    static List<NatureData> natureList() {
        loadNatures();
        return sortedNatures;
    }

    static List<String> itemList() {
        load();
        return items;
    }

    static List<String> abilityList() {
        load();
        return abilities;
    }

    static String diagnosticSummary() {
        load();
        long flaggedMoves = sortedMoves.stream().filter(move -> !move.flags().isEmpty()).count();
        long forms = sortedSpecies.stream()
                .filter(species -> !normalize(species.id()).equals(normalize(species.cobblemonSpeciesId())))
                .count();
        return "species=" + sortedSpecies.size() + ", forms=" + forms + ", moves=" + sortedMoves.size()
                + ", items=" + items.size() + ", abilities=" + abilities.size()
                + ", natures=" + sortedNatures.size() + ", flaggedMoves=" + flaggedMoves + "/" + sortedMoves.size();
    }

    static List<String> abilityList(SpeciesData species) {
        load();
        List<String> speciesAbilities = LEGAL_ABILITIES.get(species.id());
        if (speciesAbilities == null || speciesAbilities.isEmpty()) {
            speciesAbilities = LEGAL_ABILITIES.get(species.cobblemonSpeciesId());
        }
        return speciesAbilities == null || speciesAbilities.isEmpty() ? List.of("None") : speciesAbilities;
    }

    static boolean isHiddenAbility(SpeciesData species, String ability) {
        load();
        if (species == null || ability == null) {
            return false;
        }
        String normalized = normalize(ability);
        for (String hidden : HIDDEN_ABILITIES.getOrDefault(species.id(), List.of())) {
            if (normalize(hidden).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static String defaultAbility(SpeciesData species) {
        List<String> speciesAbilities = abilityList(species);
        if (speciesAbilities.isEmpty()) {
            return "None";
        }
        for (String ability : speciesAbilities) {
            if (!isHiddenAbility(species, ability)) {
                return ability;
            }
        }
        return speciesAbilities.getFirst();
    }

    static String megaStoneForSpecies(SpeciesData species) {
        load();
        if (species == null || !normalize(species.name()).startsWith("mega")) {
            return null;
        }
        String normalizedName = normalize(species.name());
        String wanted = switch (normalizedName) {
            case "megaabomasnow" -> "Abomasite";
            case "megaabsol" -> "Absolite";
            case "megaaerodactyl" -> "Aerodactylite";
            case "megaaggron" -> "Aggronite";
            case "megaalakazam" -> "Alakazite";
            case "megaaltaria" -> "Altarianite";
            case "megaampharos" -> "Ampharosite";
            case "megaaudino" -> "Audinite";
            case "megabanette" -> "Banettite";
            case "megabeedrill" -> "Beedrillite";
            case "megablastoise" -> "Blastoisinite";
            case "megablaziken" -> "Blazikenite";
            case "megacamerupt" -> "Cameruptite";
            case "megacharizardx" -> "Charizardite X";
            case "megacharizardy" -> "Charizardite Y";
            case "megadiancie" -> "Diancite";
            case "megagallade" -> "Galladite";
            case "megagarchomp" -> "Garchompite";
            case "megagardevoir" -> "Gardevoirite";
            case "megagengar" -> "Gengarite";
            case "megaglalie" -> "Glalitite";
            case "megagyarados" -> "Gyaradosite";
            case "megaheracross" -> "Heracronite";
            case "megahoundoom" -> "Houndoominite";
            case "megakangaskhan" -> "Kangaskhanite";
            case "megalatias" -> "Latiasite";
            case "megalatios" -> "Latiosite";
            case "megalopunny" -> "Lopunnite";
            case "megalucario" -> "Lucarionite";
            case "megamanectric" -> "Manectite";
            case "megamawile" -> "Mawilite";
            case "megamedicham" -> "Medichamite";
            case "megametagross" -> "Metagrossite";
            case "megamewtwox" -> "Mewtwonite X";
            case "megamewtwoy" -> "Mewtwonite Y";
            case "megapidgeot" -> "Pidgeotite";
            case "megapinsir" -> "Pinsirite";
            case "megasableye" -> "Sablenite";
            case "megasalamence" -> "Salamencite";
            case "megascizor" -> "Scizorite";
            case "megasceptile" -> "Sceptilite";
            case "megasharpedo" -> "Sharpedonite";
            case "megaslowbro" -> "Slowbronite";
            case "megasteelix" -> "Steelixite";
            case "megaswampert" -> "Swampertite";
            case "megatyranitar" -> "Tyranitarite";
            case "megavenusaur" -> "Venusaurite";
            default -> null;
        };
        return wanted == null ? null : findItemByQuery(wanted);
    }

    static String dataSourceLabel() {
        load();
        if (cobblemonLoaded) {
            return "Source: BDD Cobblemon (" + SPECIES.size() + " Pokemon, " + MOVES.size() + " attaques, " + items.size() + " objets)";
        }
        return "Source: Cobblemon en chargement";
    }

    static List<PokeType> teraTypes() {
        return List.of(PokeType.NONE, PokeType.NORMAL, PokeType.FIRE, PokeType.WATER, PokeType.ELECTRIC, PokeType.GRASS,
                PokeType.ICE, PokeType.FIGHTING, PokeType.POISON, PokeType.GROUND, PokeType.FLYING, PokeType.PSYCHIC,
                PokeType.BUG, PokeType.ROCK, PokeType.GHOST, PokeType.DRAGON, PokeType.DARK, PokeType.STEEL, PokeType.FAIRY);
    }

    static SpeciesData findSpeciesByDisplayName(String displayName) {
        load();
        String normalized = normalize(displayName);
        SpeciesData exact = SPECIES.get(normalized);
        if (exact == null) {
            exact = SPECIES_BY_NAME.get(normalized);
        }
        if (exact != null) {
            return exact;
        }
        SpeciesData best = null;
        int bestLength = -1;
        for (Map.Entry<String, SpeciesData> entry : SPECIES.entrySet()) {
            String name = normalize(entry.getValue().name());
            if (normalized.contains(entry.getKey()) || normalized.contains(name)) {
                int matchLength = Math.max(entry.getKey().length(), name.length());
                if (matchLength > bestLength) {
                    best = entry.getValue();
                    bestLength = matchLength;
                }
            }
        }
        return best;
    }

    static SpeciesData findSpeciesByQuery(String query) {
        load();
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        SpeciesData exact = SPECIES.get(normalized);
        if (exact == null) {
            exact = SPECIES_BY_NAME.get(normalized);
        }
        if (exact != null) {
            return exact;
        }
        for (SpeciesData species : sortedSpecies) {
            String name = normalize(species.name());
            if (name.startsWith(normalized) || species.id().startsWith(normalized)) {
                return species;
            }
        }
        for (SpeciesData species : sortedSpecies) {
            String name = normalize(species.name());
            if (name.contains(normalized) || species.id().contains(normalized)) {
                return species;
            }
        }
        return null;
    }

    static SpeciesData findFormSpecies(String baseSpeciesId, String formName, String formShowdownId, List<String> aspects) {
        load();
        String base = normalize(baseSpeciesId);
        String form = normalize(formName);
        String showdown = normalize(formShowdownId);
        if (!showdown.isBlank()) {
            SpeciesData exact = SPECIES.get(showdown);
            if (exact != null && (base.isBlank() || normalize(exact.cobblemonSpeciesId()).equals(base) || exact.id().startsWith(base))) {
                return exact;
            }
        }
        if (!base.isBlank() && !form.isBlank()) {
            SpeciesData exact = SPECIES.get(base + form);
            if (exact != null) {
                return exact;
            }
        }
        ArrayList<String> normalizedAspects = new ArrayList<>();
        for (String aspect : aspects == null ? List.<String>of() : aspects) {
            String normalized = normalize(aspect);
            if (!normalized.isBlank()) {
                normalizedAspects.add(normalized);
            }
        }
        SpeciesData bestNameMatch = null;
        SpeciesData bestAspectMatch = null;
        int bestAspectScore = 0;
        for (SpeciesData species : SPECIES.values()) {
            if (!base.isBlank() && !normalize(species.cobblemonSpeciesId()).equals(base) && !species.id().startsWith(base)) {
                continue;
            }
            if (!normalizedAspects.isEmpty()) {
                int aspectScore = matchingAspectCount(species, normalizedAspects);
                if (aspectScore > bestAspectScore
                        || aspectScore == bestAspectScore && aspectScore > 0
                        && bestAspectMatch != null && species.id().compareTo(bestAspectMatch.id()) < 0) {
                    bestAspectMatch = species;
                    bestAspectScore = aspectScore;
                }
            }
            String speciesName = normalize(species.name());
            if (!form.isBlank() && (species.id().contains(form) || speciesName.contains(form))
                    && (bestNameMatch == null || species.id().compareTo(bestNameMatch.id()) < 0)) {
                bestNameMatch = species;
            }
        }
        return bestAspectMatch != null ? bestAspectMatch : bestNameMatch;
    }

    private static int matchingAspectCount(SpeciesData species, List<String> normalizedAspects) {
        if (species.aspects().isEmpty()) {
            return 0;
        }
        int matches = 0;
        for (String speciesAspect : species.aspects()) {
            String normalizedSpeciesAspect = normalize(speciesAspect);
            if (!normalizedSpeciesAspect.isBlank() && normalizedAspects.contains(normalizedSpeciesAspect)) {
                matches++;
            }
        }
        return matches;
    }

    static MoveData findMoveByQuery(String query) {
        load();
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        MoveData exact = MOVES.get(normalized);
        if (exact == null) {
            exact = MOVES_BY_NAME.get(normalized);
        }
        if (exact != null) {
            return exact;
        }
        for (MoveData move : sortedMoves) {
            String name = normalize(move.name());
            if (name.startsWith(normalized) || move.id().startsWith(normalized)) {
                return move;
            }
        }
        for (MoveData move : sortedMoves) {
            String name = normalize(move.name());
            if (name.contains(normalized) || move.id().contains(normalized)) {
                return move;
            }
        }
        return null;
    }

    static String findItemByQuery(String query) {
        load();
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        String exact = ITEMS_BY_NAME.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (String item : items) {
            if (normalize(item).startsWith(normalized)) {
                return item;
            }
        }
        for (String item : items) {
            if (normalize(item).contains(normalized)) {
                return item;
            }
        }
        return null;
    }

    static NatureData findNatureByQuery(String query) {
        loadNatures();
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        NatureData exact = NATURES.get(normalized);
        if (exact == null) {
            exact = NATURES_BY_NAME.get(normalized);
        }
        if (exact != null) {
            return exact;
        }
        for (NatureData nature : sortedNatures) {
            if (normalize(nature.name()).startsWith(normalized)) {
                return nature;
            }
        }
        for (NatureData nature : sortedNatures) {
            if (normalize(nature.name()).contains(normalized)) {
                return nature;
            }
        }
        return null;
    }

    static String findAbilityByQuery(SpeciesData species, String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        List<String> candidates = abilityList(species);
        for (String ability : candidates) {
            if (normalize(ability).equals(normalized)) {
                return ability;
            }
        }
        for (String ability : candidates) {
            if (normalize(ability).startsWith(normalized)) {
                return ability;
            }
        }
        for (String ability : candidates) {
            if (normalize(ability).contains(normalized)) {
                return ability;
            }
        }
        return null;
    }

    static String findAbilityByQuery(String query) {
        load();
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }
        for (String ability : abilities) {
            if (normalize(ability).equals(normalized)) {
                return ability;
            }
        }
        for (String ability : abilities) {
            if (normalize(ability).startsWith(normalized)) {
                return ability;
            }
        }
        return null;
    }

    static List<MoveData> defaultMoves(SpeciesData species) {
        load();
        ArrayList<MoveData> selected = new ArrayList<>();
        List<String> legalMoveIds = LEGAL_MOVE_IDS.getOrDefault(species.id(), List.of());
        for (String moveId : legalMoveIds) {
            MoveData move = MOVES.get(moveId);
            if (move != null && move.category() != DamageCategory.STATUS) {
                selected.add(move);
            }
        }
        selected.sort(Comparator
                .comparing((MoveData move) -> species.types().contains(move.type())).reversed()
                .thenComparing(MoveData::basePower, Comparator.reverseOrder()));
        if (selected.size() >= 4) {
            return new ArrayList<>(selected.subList(0, 4));
        }
        for (String moveId : legalMoveIds) {
            if (selected.size() >= 4) {
                break;
            }
            MoveData move = MOVES.get(moveId);
            if (move != null && !selected.contains(move)) {
                selected.add(move);
            }
        }
        if (selected.isEmpty() && !cobblemonLoaded) {
            selected.add(placeholderMove("loading"));
        }
        return selected;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(decomposed.length());
        for (int index = 0; index < decomposed.length(); index++) {
            char character = decomposed.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static synchronized void tryLoadCobblemonData() {
        if (cobblemonLoaded || System.currentTimeMillis() < nextCobblemonLoadAttempt) {
            return;
        }
        nextCobblemonLoadAttempt = System.currentTimeMillis() + 2_000L;
        CobblemonDexSnapshot snapshot = CobblemonDexDataProvider.loadSnapshot();
        if (!snapshot.loaded() || snapshot.species().isEmpty() || snapshot.moves().isEmpty()) {
            return;
        }
        SPECIES.clear();
        SPECIES.putAll(snapshot.species());
        MOVES.clear();
        MOVES.putAll(snapshot.moves());
        LEGAL_MOVE_IDS.clear();
        LEGAL_MOVE_IDS.putAll(snapshot.legalMoveIds());
        HIDDEN_ABILITIES.clear();
        snapshot.hiddenAbilities().forEach((id, values) -> HIDDEN_ABILITIES.put(id, sortedCopy(values)));
        replaceAbilityLists(snapshot.legalAbilities());
        abilities = snapshot.abilities().isEmpty() ? List.of("None") : sortedCopy(snapshot.abilities());
        items = snapshot.items().isEmpty() ? List.of("None") : sortedCopy(snapshot.items());
        rebuildIndexes();
        cobblemonLoaded = true;
    }

    static synchronized void invalidateCobblemonData() {
        cobblemonLoaded = false;
        nextCobblemonLoadAttempt = 0L;
        SPECIES.clear();
        MOVES.clear();
        LEGAL_MOVE_IDS.clear();
        LEGAL_ABILITIES.clear();
        HIDDEN_ABILITIES.clear();
        SPECIES_BY_NAME.clear();
        MOVES_BY_NAME.clear();
        ITEMS_BY_NAME.clear();
        sortedSpecies = List.of();
        sortedMoves = List.of();
        items = List.of("None");
        abilities = List.of("None");
    }

    private static void replaceAbilityLists(Map<String, List<String>> source) {
        LEGAL_ABILITIES.clear();
        source.forEach((id, values) -> LEGAL_ABILITIES.put(id, sortedCopy(values)));
    }

    private static List<String> sortedCopy(List<String> source) {
        ArrayList<String> sorted = new ArrayList<>(source);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sorted);
    }

    private static void rebuildIndexes() {
        SPECIES_BY_NAME.clear();
        for (SpeciesData species : SPECIES.values()) {
            SPECIES_BY_NAME.putIfAbsent(normalize(species.name()), species);
        }
        sortedSpecies = SPECIES.values().stream()
                .sorted(Comparator.comparing(SpeciesData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        MOVES_BY_NAME.clear();
        for (MoveData move : MOVES.values()) {
            MOVES_BY_NAME.putIfAbsent(normalize(move.name()), move);
        }
        sortedMoves = MOVES.values().stream()
                .sorted(Comparator.comparing(MoveData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        ITEMS_BY_NAME.clear();
        for (String item : items) {
            ITEMS_BY_NAME.putIfAbsent(normalize(item), item);
        }
    }

    private static SpeciesData placeholderSpecies(String id) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            stats.put(stat, 1);
        }
        String name = id == null || id.isBlank() ? "Chargement Cobblemon" : id;
        return new SpeciesData(normalize(name), name, PokeType.NORMAL, PokeType.NONE, stats, false, "");
    }

    private static MoveData placeholderMove(String id) {
        String name = id == null || id.isBlank() ? "Chargement" : id;
        return new MoveData(normalize(name), name, PokeType.NORMAL, DamageCategory.STATUS, 0, false, false);
    }

    private static void loadNatures() {
        if (naturesLoaded) {
            return;
        }
        naturesLoaded = true;
        nature("hardy", "Hardy", null, null);
        nature("lonely", "Lonely", Stat.ATK, Stat.DEF);
        nature("brave", "Brave", Stat.ATK, Stat.SPE);
        nature("adamant", "Adamant", Stat.ATK, Stat.SPA);
        nature("naughty", "Naughty", Stat.ATK, Stat.SPD);
        nature("bold", "Bold", Stat.DEF, Stat.ATK);
        nature("relaxed", "Relaxed", Stat.DEF, Stat.SPE);
        nature("impish", "Impish", Stat.DEF, Stat.SPA);
        nature("lax", "Lax", Stat.DEF, Stat.SPD);
        nature("timid", "Timid", Stat.SPE, Stat.ATK);
        nature("hasty", "Hasty", Stat.SPE, Stat.DEF);
        nature("jolly", "Jolly", Stat.SPE, Stat.SPA);
        nature("naive", "Naive", Stat.SPE, Stat.SPD);
        nature("modest", "Modest", Stat.SPA, Stat.ATK);
        nature("mild", "Mild", Stat.SPA, Stat.DEF);
        nature("quiet", "Quiet", Stat.SPA, Stat.SPE);
        nature("rash", "Rash", Stat.SPA, Stat.SPD);
        nature("calm", "Calm", Stat.SPD, Stat.ATK);
        nature("gentle", "Gentle", Stat.SPD, Stat.DEF);
        nature("sassy", "Sassy", Stat.SPD, Stat.SPE);
        nature("careful", "Careful", Stat.SPD, Stat.SPA);
        nature("serious", "Serious", null, null);
        NATURES_BY_NAME.clear();
        for (NatureData nature : NATURES.values()) {
            NATURES_BY_NAME.putIfAbsent(normalize(nature.name()), nature);
        }
        sortedNatures = NATURES.values().stream()
                .sorted(Comparator.comparing(NatureData::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void nature(String id, String name, Stat plus, Stat minus) {
        NATURES.put(id, new NatureData(id, name, plus, minus));
    }
}
