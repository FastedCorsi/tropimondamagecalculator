package fr.tropimon.damagecalc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Tropimon Random Battle sets discovered in the installed game files. */
final class TropimonRandomBattleSets {
    private static final String BUNDLED_RESOURCE =
            "/assets/tropimon_damage_calc/data/tropimon-random-battle-sets.json";
    private static final Set<String> FILE_NAMES = Set.of(
            "tropimon.json",
            "tropimon-random-battle-sets.json",
            "tropimon-random-battle-sets"
    );
    private static final long REFRESH_INTERVAL_MS = 5_000L;
    private static final Map<String, List<RandomBattleSet>> SETS = new LinkedHashMap<>();
    private static boolean loaded;
    private static Path sourcePath;
    private static long sourceModified = Long.MIN_VALUE;
    private static long sourceSize = Long.MIN_VALUE;
    private static boolean bundledSource;
    private static long nextRefreshCheck;

    private TropimonRandomBattleSets() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        SETS.clear();
        sourcePath = null;
        sourceModified = Long.MIN_VALUE;
        sourceSize = Long.MIN_VALUE;
        bundledSource = false;
        nextRefreshCheck = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
        for (Path candidate : localCandidates()) {
            try (Reader reader = Files.newBufferedReader(candidate)) {
                Map<String, List<RandomBattleSet>> parsed = parseDocument(reader);
                if (parsed.isEmpty()) {
                    continue;
                }
                SETS.putAll(parsed);
                sourcePath = candidate;
                sourceModified = lastModified(candidate);
                sourceSize = fileSize(candidate);
                int setCount = SETS.values().stream().mapToInt(List::size).sum();
                TropimonDamageCalcClient.LOGGER.info(
                        "Loaded Tropimon Random Battle sets from game files: {} Pokemon, {} sets, source={}",
                        SETS.size(), setCount, candidate);
                return;
            } catch (Exception exception) {
                TropimonDamageCalcClient.LOGGER.debug("Ignored invalid Random Battle set file: {}", candidate, exception);
            }
        }
        if (!loadBundledSnapshot()) {
            TropimonDamageCalcClient.LOGGER.warn("No Tropimon Random Battle set data is available");
        }
    }

    static synchronized void reload() {
        loaded = false;
        load();
    }

    static void pollForUpdates() {
        refreshIfChanged();
    }

    static List<RandomBattleSet> setsFor(String speciesId) {
        refreshIfChanged();
        return SETS.getOrDefault(TropimonDex.normalize(speciesId), List.of());
    }

    static List<RandomBattleSet> setsFor(SpeciesData species) {
        if (species == null) {
            return List.of();
        }
        List<RandomBattleSet> sets = setsFor(species.id());
        if (!sets.isEmpty()) {
            return sets;
        }
        sets = setsFor(species.name());
        if (!sets.isEmpty()) {
            return sets;
        }
        return setsFor(species.cobblemonSpeciesId());
    }

    static int suggestedLevel(SpeciesData species, int fallback) {
        List<RandomBattleSet> sets = setsFor(species);
        if (sets.isEmpty()) {
            return fallback;
        }
        LinkedHashMap<Integer, Integer> weights = new LinkedHashMap<>();
        for (RandomBattleSet set : sets) {
            weights.merge(set.level(), Math.max(1, set.weight()), Integer::sum);
        }
        int selectedLevel = fallback;
        int selectedWeight = -1;
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            if (entry.getValue() > selectedWeight) {
                selectedLevel = entry.getKey();
                selectedWeight = entry.getValue();
            }
        }
        return Math.max(1, Math.min(100, selectedLevel));
    }

    static int applyInference(PokemonSet pokemon) {
        List<RandomBattleSet> candidates = matchingSets(pokemon);
        if (pokemon == null || candidates.isEmpty()) {
            return 0;
        }
        String commonItem = commonValue(candidates, RandomBattleSet::itemId);
        if (!pokemon.itemKnown && commonItem != null) {
            String item = TropimonDex.findItemByQuery(commonItem);
            pokemon.item = item == null ? prettyIdentifier(commonItem) : item;
            pokemon.itemKnown = true;
        }
        String commonAbility = commonValue(candidates, RandomBattleSet::abilityId);
        if (!pokemon.abilityKnown && commonAbility != null) {
            String ability = TropimonDex.findAbilityByQuery(pokemon.species, commonAbility);
            pokemon.ability = ability == null ? prettyIdentifier(commonAbility) : ability;
            pokemon.abilityKnown = true;
        }
        String commonTera = commonValue(candidates, RandomBattleSet::teraTypeId);
        if (pokemon.teraType == PokeType.NONE && commonTera != null) {
            pokemon.teraType = PokeType.byName(commonTera);
        }
        List<String> inferredMoves = commonMoves(candidates);
        for (String moveId : inferredMoves) {
            MoveData move = TropimonDex.findMoveByQuery(moveId);
            if (move != null) {
                addMoveIfMissing(pokemon, move);
            }
        }
        return candidates.size();
    }

    static void applySet(PokemonSet pokemon, RandomBattleSet set) {
        if (pokemon == null || set == null) {
            return;
        }
        pokemon.level = Math.max(1, Math.min(100, set.level()));
        String item = TropimonDex.findItemByQuery(set.itemId());
        pokemon.item = item == null ? prettyIdentifier(set.itemId()) : item;
        pokemon.itemKnown = true;
        String ability = TropimonDex.findAbilityByQuery(pokemon.species, set.abilityId());
        pokemon.ability = ability == null ? prettyIdentifier(set.abilityId()) : ability;
        pokemon.abilityKnown = true;
        pokemon.teraType = PokeType.byName(set.teraTypeId());
        pokemon.moves.clear();
        for (String moveId : set.moveIds()) {
            MoveData move = TropimonDex.findMoveByQuery(moveId);
            if (move != null && pokemon.moves.size() < 4) {
                pokemon.moves.add(move);
            }
        }
        while (pokemon.moves.size() < 4) {
            pokemon.moves.add(null);
        }
        pokemon.movesKnown = pokemon.moves.stream().anyMatch(java.util.Objects::nonNull);
        java.util.Arrays.fill(pokemon.zMoves, false);
    }

    static List<RandomBattleSet> matchingSets(PokemonSet pokemon) {
        if (pokemon == null) {
            return List.of();
        }
        List<RandomBattleSet> candidates = new ArrayList<>(setsFor(pokemon.species));
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (pokemon.level > 0) {
            candidates = filter(candidates, set -> set.level() == pokemon.level);
        }
        String item = TropimonDex.normalize(pokemon.item);
        if (pokemon.itemKnown && !item.isBlank() && !item.equals("none")) {
            candidates = filter(candidates, set -> TropimonDex.normalize(set.itemId()).equals(item));
        }
        String ability = TropimonDex.normalize(pokemon.ability);
        if (pokemon.abilityKnown && !ability.isBlank() && !ability.equals("none")) {
            candidates = filter(candidates, set -> TropimonDex.normalize(set.abilityId()).equals(ability));
        }
        Set<String> knownMoves = new HashSet<>();
        for (MoveData move : pokemon.moves) {
            if (move != null) {
                knownMoves.add(TropimonDex.normalize(move.id()));
            }
        }
        if (!knownMoves.isEmpty()) {
            candidates = filter(candidates, set -> {
                Set<String> setMoves = new HashSet<>();
                for (String moveId : set.moveIds()) {
                    setMoves.add(TropimonDex.normalize(moveId));
                }
                return setMoves.containsAll(knownMoves);
            });
        }
        if (pokemon.teraType != PokeType.NONE) {
            candidates = filter(candidates,
                    set -> PokeType.byName(set.teraTypeId()) == pokemon.teraType);
        }
        return List.copyOf(candidates);
    }

    static int speciesCount() {
        refreshIfChanged();
        return SETS.size();
    }

    static String sourceDescription() {
        refreshIfChanged();
        if (sourcePath != null) {
            return sourcePath.toString();
        }
        return bundledSource ? "Bundled Tropimon snapshot" : "None";
    }

    static synchronized void replaceFromReaderForTest(Reader reader) {
        SETS.clear();
        SETS.putAll(parseDocument(reader));
        loaded = true;
        sourcePath = null;
        sourceModified = Long.MIN_VALUE;
        sourceSize = Long.MIN_VALUE;
        bundledSource = false;
        nextRefreshCheck = Long.MAX_VALUE;
    }

    private static synchronized void refreshIfChanged() {
        load();
        long now = System.currentTimeMillis();
        if (now < nextRefreshCheck) {
            return;
        }
        nextRefreshCheck = now + REFRESH_INTERVAL_MS;
        List<Path> candidates = localCandidates();
        Path preferred = candidates.isEmpty() ? null : candidates.getFirst();
        boolean localAppeared = sourcePath == null && preferred != null;
        boolean localDisappeared = sourcePath != null && preferred == null;
        boolean preferredChanged = sourcePath != null && preferred != null && !sourcePath.equals(preferred);
        boolean contentChanged = sourcePath != null && preferred != null
                && (lastModified(sourcePath) != sourceModified || fileSize(sourcePath) != sourceSize);
        if (localAppeared || localDisappeared || preferredChanged || contentChanged) {
            loaded = false;
            load();
        }
    }

    private static boolean loadBundledSnapshot() {
        try (InputStream stream = TropimonRandomBattleSets.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream == null) {
                return false;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, List<RandomBattleSet>> parsed = parseDocument(reader);
                if (parsed.isEmpty()) {
                    return false;
                }
                SETS.putAll(parsed);
                bundledSource = true;
                int setCount = SETS.values().stream().mapToInt(List::size).sum();
                TropimonDamageCalcClient.LOGGER.info(
                        "Loaded bundled Tropimon Random Battle snapshot: {} Pokemon, {} sets",
                        SETS.size(), setCount);
                return true;
            }
        } catch (Exception exception) {
            TropimonDamageCalcClient.LOGGER.error(
                    "Could not load bundled Tropimon Random Battle snapshot", exception);
            return false;
        }
    }

    private static List<Path> localCandidates() {
        HashSet<Path> found = new HashSet<>();
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            for (String fileName : FILE_NAMES) {
                addCandidate(found, gameDir.resolve(fileName));
                addCandidate(found, gameDir.resolve("showdown/data").resolve(fileName));
                addCandidate(found, gameDir.resolve("config").resolve(fileName));
                addCandidate(found, gameDir.resolve("data").resolve(fileName));
            }
            scan(found, gameDir.resolve("showdown"), 5);
            scan(found, gameDir.resolve("config"), 4);
            scan(found, gameDir.resolve("data"), 4);
            scan(found, gameDir.resolve("cobblemon"), 4);
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String modId = TropimonDex.normalize(mod.getMetadata().getId());
                if (!modId.contains("tropi") || modId.equals(TropimonDex.normalize(TropimonDamageCalcClient.MOD_ID))) {
                    continue;
                }
                for (Path root : mod.getRootPaths()) {
                    scan(found, root, 8);
                }
            }
        } catch (Throwable throwable) {
            TropimonDamageCalcClient.LOGGER.debug("Could not inspect local Tropimon game files", throwable);
        }
        return found.stream()
                .sorted(Comparator.comparingLong(TropimonRandomBattleSets::lastModified).reversed())
                .toList();
    }

    private static void scan(Set<Path> output, Path root, int depth) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root, depth)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> FILE_NAMES.contains(path.getFileName().toString().toLowerCase()))
                    .forEach(path -> addCandidate(output, path));
        } catch (Exception ignored) {
        }
    }

    private static void addCandidate(Set<Path> output, Path path) {
        if (path != null && Files.isRegularFile(path)) {
            output.add(path.toAbsolutePath().normalize());
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Map<String, List<RandomBattleSet>> parseDocument(Reader reader) {
        LinkedHashMap<String, List<RandomBattleSet>> output = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> speciesEntry : root.entrySet()) {
                if (!speciesEntry.getValue().isJsonObject()) {
                    continue;
                }
                LinkedHashMap<String, RandomBattleSet> speciesSets = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> setEntry : speciesEntry.getValue().getAsJsonObject().entrySet()) {
                    RandomBattleSet parsed = parse(setEntry.getKey(), setEntry.getValue());
                    if (parsed != null) {
                        speciesSets.merge(setKey(parsed), parsed, TropimonRandomBattleSets::mergeDuplicateSets);
                    }
                }
                if (!speciesSets.isEmpty()) {
                    output.put(TropimonDex.normalize(speciesEntry.getKey()), List.copyOf(speciesSets.values()));
                }
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return output;
    }

    private static String setKey(RandomBattleSet set) {
        ArrayList<String> moves = new ArrayList<>();
        for (String move : set.moveIds()) {
            moves.add(TropimonDex.normalize(move));
        }
        moves.sort(String::compareTo);
        return set.level() + "|" + TropimonDex.normalize(set.itemId()) + "|"
                + TropimonDex.normalize(set.abilityId()) + "|" + String.join(",", moves) + "|"
                + TropimonDex.normalize(set.teraTypeId());
    }

    private static RandomBattleSet mergeDuplicateSets(RandomBattleSet first, RandomBattleSet duplicate) {
        long combinedWeight = (long) Math.max(1, first.weight()) + Math.max(1, duplicate.weight());
        return new RandomBattleSet(first.level(), first.itemId(), first.abilityId(), first.moveIds(),
                first.teraTypeId(), (int) Math.min(Integer.MAX_VALUE, combinedWeight));
    }

    private static List<RandomBattleSet> filter(List<RandomBattleSet> source,
                                                 java.util.function.Predicate<RandomBattleSet> predicate) {
        return source.stream().filter(predicate).toList();
    }

    private static String commonValue(List<RandomBattleSet> sets,
                                      Function<RandomBattleSet, String> getter) {
        if (sets.isEmpty()) {
            return null;
        }
        String first = getter.apply(sets.getFirst());
        String normalized = TropimonDex.normalize(first);
        for (int index = 1; index < sets.size(); index++) {
            if (!TropimonDex.normalize(getter.apply(sets.get(index))).equals(normalized)) {
                return null;
            }
        }
        return first;
    }

    private static List<String> commonMoves(List<RandomBattleSet> sets) {
        if (sets.isEmpty()) {
            return List.of();
        }
        ArrayList<String> common = new ArrayList<>(sets.getFirst().moveIds());
        for (int index = 1; index < sets.size(); index++) {
            Set<String> candidateMoves = new HashSet<>();
            for (String move : sets.get(index).moveIds()) {
                candidateMoves.add(TropimonDex.normalize(move));
            }
            common.removeIf(move -> !candidateMoves.contains(TropimonDex.normalize(move)));
        }
        return List.copyOf(common);
    }

    private static void addMoveIfMissing(PokemonSet pokemon, MoveData move) {
        for (MoveData known : pokemon.moves) {
            if (known != null && known.id().equals(move.id())) {
                return;
            }
        }
        for (int slot = 0; slot < pokemon.moves.size(); slot++) {
            if (pokemon.moves.get(slot) == null) {
                pokemon.moves.set(slot, move);
                pokemon.movesKnown = true;
                return;
            }
        }
        if (pokemon.moves.size() < 4) {
            pokemon.moves.add(move);
            pokemon.movesKnown = true;
        }
    }

    private static String prettyIdentifier(String id) {
        String spaced = id == null ? "" : id.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isBlank()) {
            return "None";
        }
        StringBuilder output = new StringBuilder(spaced.length());
        boolean upper = true;
        for (char character : spaced.toCharArray()) {
            if (character == ' ') {
                output.append(character);
                upper = true;
            } else {
                output.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return output.toString();
    }

    private static RandomBattleSet parse(String encodedSet, JsonElement weightElement) {
        String[] parts = encodedSet.split(",", -1);
        if (parts.length < 5) {
            return null;
        }
        try {
            int level = Integer.parseInt(parts[0]);
            int weight = weightElement.isJsonPrimitive() ? weightElement.getAsInt() : 1;
            ArrayList<String> moves = new ArrayList<>();
            for (int index = 3; index < parts.length - 1; index++) {
                if (!parts[index].isBlank()) {
                    moves.add(parts[index]);
                }
            }
            return new RandomBattleSet(level, parts[1], parts[2], List.copyOf(moves),
                    parts[parts.length - 1], weight);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    record RandomBattleSet(int level, String itemId, String abilityId, List<String> moveIds,
                           String teraTypeId, int weight) {
    }
}
