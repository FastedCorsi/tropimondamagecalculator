package fr.tropimon.damagecalc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local Tropimon Random Battle sets bundled in the mod JAR. */
final class TropimonRandomBattleSets {
    private static final String RESOURCE = "/assets/tropimon_damage_calc/data/tropimon-random-battle-sets.json";
    private static final Map<String, List<RandomBattleSet>> SETS = new LinkedHashMap<>();
    private static boolean loaded;

    private TropimonRandomBattleSets() {
    }

    static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream stream = TropimonRandomBattleSets.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                TropimonDamageCalcClient.LOGGER.warn("Tropimon Random Battle resource is missing: {}", RESOURCE);
                return;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                int setCount = 0;
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
                        SETS.put(TropimonDex.normalize(speciesEntry.getKey()), List.copyOf(speciesSets));
                        setCount += speciesSets.size();
                    }
                }
                TropimonDamageCalcClient.LOGGER.info(
                        "Loaded local Tropimon Random Battle sets: {} Pokemon, {} sets", SETS.size(), setCount);
            }
        } catch (Exception exception) {
            SETS.clear();
            TropimonDamageCalcClient.LOGGER.error("Could not load local Tropimon Random Battle sets", exception);
        }
    }

    static List<RandomBattleSet> setsFor(String speciesId) {
        load();
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
        load();
        return SETS.size();
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
