package fr.tropimon.damagecalc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Tropimon Random Battle sets discovered in the installed game files. */
final class TropimonRandomBattleSets {
    private static final Set<String> FILE_NAMES = Set.of(
            "tropimon.json",
            "tropimon-random-battle-sets.json"
    );
    private static final long REFRESH_INTERVAL_MS = 30_000L;
    private static final Map<String, List<RandomBattleSet>> SETS = new LinkedHashMap<>();
    private static boolean loaded;
    private static Path sourcePath;
    private static long sourceModified = Long.MIN_VALUE;
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
                int setCount = SETS.values().stream().mapToInt(List::size).sum();
                TropimonDamageCalcClient.LOGGER.info(
                        "Loaded Tropimon Random Battle sets from game files: {} Pokemon, {} sets, source={}",
                        SETS.size(), setCount, candidate);
                return;
            } catch (Exception exception) {
                TropimonDamageCalcClient.LOGGER.debug("Ignored invalid Random Battle set file: {}", candidate, exception);
            }
        }
        TropimonDamageCalcClient.LOGGER.warn(
                "No local Tropimon Random Battle set file found; waiting for a game update");
    }

    static synchronized void reload() {
        loaded = false;
        load();
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

    static int speciesCount() {
        refreshIfChanged();
        return SETS.size();
    }

    static String sourceDescription() {
        refreshIfChanged();
        return sourcePath == null ? "None" : sourcePath.toString();
    }

    static synchronized void replaceFromReaderForTest(Reader reader) {
        SETS.clear();
        SETS.putAll(parseDocument(reader));
        loaded = true;
        sourcePath = null;
        sourceModified = Long.MIN_VALUE;
        nextRefreshCheck = Long.MAX_VALUE;
    }

    private static synchronized void refreshIfChanged() {
        load();
        long now = System.currentTimeMillis();
        if (now < nextRefreshCheck) {
            return;
        }
        nextRefreshCheck = now + REFRESH_INTERVAL_MS;
        if (sourcePath == null || lastModified(sourcePath) != sourceModified) {
            loaded = false;
            load();
        }
    }

    private static List<Path> localCandidates() {
        HashSet<Path> found = new HashSet<>();
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            addCandidate(found, gameDir.resolve("tropimon.json"));
            addCandidate(found, gameDir.resolve("showdown/data/tropimon.json"));
            addCandidate(found, gameDir.resolve("config/tropimon.json"));
            addCandidate(found, gameDir.resolve("data/tropimon.json"));
            scan(found, gameDir.resolve("showdown"), 5);
            scan(found, gameDir.resolve("config"), 4);
            scan(found, gameDir.resolve("data"), 4);
            scan(found, gameDir.resolve("cobblemon"), 4);
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                if (!TropimonDex.normalize(mod.getMetadata().getId()).contains("tropi")) {
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

    private static Map<String, List<RandomBattleSet>> parseDocument(Reader reader) {
        LinkedHashMap<String, List<RandomBattleSet>> output = new LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> speciesEntry : root.entrySet()) {
                if (!speciesEntry.getValue().isJsonObject()) {
                    continue;
                }
                ArrayList<RandomBattleSet> speciesSets = new ArrayList<>();
                for (Map.Entry<String, JsonElement> setEntry : speciesEntry.getValue().getAsJsonObject().entrySet()) {
                    RandomBattleSet parsed = parse(setEntry.getKey(), setEntry.getValue());
                    if (parsed != null) {
                        speciesSets.add(parsed);
                    }
                }
                if (!speciesSets.isEmpty()) {
                    output.put(TropimonDex.normalize(speciesEntry.getKey()), List.copyOf(speciesSets));
                }
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return output;
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
