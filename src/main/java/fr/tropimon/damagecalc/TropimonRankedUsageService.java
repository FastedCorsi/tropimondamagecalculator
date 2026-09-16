package fr.tropimon.damagecalc;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Lightweight, read-only client for opponent suggestions from Ranked Tropimon usage data. */
final class TropimonRankedUsageService {
    static final TropimonRankedUsageService INSTANCE = new TropimonRankedUsageService();

    private static final String API = "https://rankedapi.tropimon.fr/api";
    private static final String FORMAT = "SINGLES";
    private static final Gson GSON = new Gson();
    private static final Pattern EV_COMPONENT = Pattern.compile("(\\d{1,3})\\s+(HP|Atk|Def|SpA|SpD|Spe)", Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Tropimon-DamageCalc-Ranked");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final Map<String, CompletableFuture<RankedProfile>> profileRequests = new ConcurrentHashMap<>();
    private volatile CompletableFuture<UsageIndex> indexFuture;

    private TropimonRankedUsageService() {
    }

    void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        indexFuture = CompletableFuture.supplyAsync(this::loadIndex, executor);
        indexFuture.whenComplete((index, error) -> {
            if (error != null) {
                TropimonDamageCalcClient.LOGGER.warn("[CalcDBG] Ranked Tropimon usage unavailable", rootCause(error));
            } else {
                TropimonDamageCalcClient.LOGGER.info(
                        "[CalcDBG] Ranked Tropimon usage ready season={} species={}",
                        index.season(), index.apiNamesByKey().size());
            }
        });
    }

    void enrichOpponent(PokemonSet pokemon) {
        if (pokemon == null || pokemon.species == null || pokemon.battleDataMode != BattleDataMode.NORMAL) {
            return;
        }
        initialize();
        UsageIndex index = completedValue(indexFuture);
        if (index == null) {
            return;
        }
        String apiName = index.apiNameFor(pokemon.species);
        if (apiName == null) {
            return;
        }
        CompletableFuture<RankedProfile> request = profileRequests.computeIfAbsent(apiName,
                ignored -> CompletableFuture.supplyAsync(() -> loadProfile(index.season(), apiName), executor)
                        .whenComplete((profile, error) -> {
                            if (error != null) {
                                TropimonDamageCalcClient.LOGGER.debug(
                                        "Ranked Tropimon profile unavailable for {}", apiName, rootCause(error));
                            }
                        }));
        RankedProfile profile = completedValue(request);
        if (profile != null) {
            applyProfile(pokemon, profile);
        }
    }

    String diagnosticSummary() {
        UsageIndex index = completedValue(indexFuture);
        long readyProfiles = profileRequests.values().stream()
                .filter(future -> completedValue(future) != null)
                .count();
        return index == null
                ? initialized.get() ? "loading/unavailable" : "not started"
                : index.season() + ", " + readyProfiles + " profile(s) cached";
    }

    private UsageIndex loadIndex() {
        SeasonResponse[] seasons = get("/seasons", SeasonResponse[].class);
        SeasonResponse selected = Arrays.stream(seasons == null ? new SeasonResponse[0] : seasons)
                .filter(season -> season != null && season.name != null && !season.name.isBlank())
                .min(Comparator.comparing((SeasonResponse season) -> !season.active))
                .orElseThrow(() -> new RankedUsageException("No Ranked Tropimon season"));
        String path = "/species-list?season=" + encode(selected.name)
                + "&format=" + FORMAT + "&tier=all";
        UsageEntryResponse[] entries = get(path, UsageEntryResponse[].class);
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (UsageEntryResponse entry : entries == null ? new UsageEntryResponse[0] : entries) {
            if (entry == null || entry.name == null || entry.name.isBlank()) {
                continue;
            }
            names.putIfAbsent(key(entry.name), entry.name);
        }
        return new UsageIndex(selected.name, Map.copyOf(names));
    }

    private RankedProfile loadProfile(String season, String apiName) {
        String path = "/species?season=" + encode(season) + "&format=" + FORMAT
                + "&tier=all&name=" + encode(apiName);
        return profileFromJson(key(apiName), getBody(path));
    }

    static RankedProfile profileFromJson(String speciesKey, String json) {
        try {
            SpeciesStatsResponse response = GSON.fromJson(json, SpeciesStatsResponse.class);
            if (response == null) {
                throw new RankedUsageException("Empty Ranked Tropimon profile");
            }
            List<RankedMove> moves = rankedValues(response.moves).stream()
                    .map(entry -> new RankedMove(entry.getKey(), entry.getValue()))
                    .toList();
            return new RankedProfile(key(speciesKey), mostUsed(response.items), mostUsed(response.abilities),
                    mostUsed(response.natures), moves, mostUsedEvSpread(response.spreads));
        } catch (JsonSyntaxException exception) {
            throw new RankedUsageException("Invalid Ranked Tropimon profile", exception);
        }
    }

    static void applyProfile(PokemonSet pokemon, RankedProfile profile) {
        if (pokemon == null || profile == null || profile.speciesKey().isBlank()
                || pokemon.battleDataMode == BattleDataMode.RANDOM
                || profile.speciesKey().equals(pokemon.rankedProfileKey)) {
            return;
        }
        if (!pokemon.itemKnown) {
            String item = TropimonDex.findItemByQuery(profile.item());
            if (item != null) {
                pokemon.item = item;
                pokemon.rankedItemSuggested = true;
            } else if (!profile.item().isBlank()) {
                pokemon.item = profile.item();
                pokemon.rankedItemSuggested = true;
            }
        }
        if (!pokemon.abilityKnown) {
            String ability = TropimonDex.findAbilityByQuery(pokemon.species, profile.ability());
            if (ability == null) {
                ability = TropimonDex.findAbilityByQuery(profile.ability());
            }
            if (ability != null) {
                pokemon.ability = ability;
                pokemon.rankedAbilitySuggested = true;
            } else if (!profile.ability().isBlank()) {
                pokemon.ability = profile.ability();
                pokemon.rankedAbilitySuggested = true;
            }
        }
        if (!pokemon.natureKnown) {
            NatureData nature = TropimonDex.findNatureByQuery(profile.nature());
            if (nature != null) {
                pokemon.nature = nature;
                pokemon.rankedNatureSuggested = true;
            }
        }
        applyEvSuggestion(pokemon, profile.evSpread());

        pokemon.rankedProfileKey = profile.speciesKey();
        pokemon.rankedMoveUsage.clear();
        LinkedHashMap<String, MoveData> available = movesById(pokemon.moves);
        for (RankedMove rankedMove : profile.moves()) {
            MoveData move = TropimonDex.findMoveByQuery(rankedMove.name());
            if (move == null) {
                continue;
            }
            available.putIfAbsent(move.id(), move);
            pokemon.rankedMoveUsage.put(move.id(), rankedMove.usage());
        }
        rebuildMoveSlots(pokemon, available);
    }

    private static RankedEvSpread mostUsedEvSpread(Map<String, Double> spreads) {
        for (Map.Entry<String, Double> entry : rankedValues(spreads)) {
            if (!Double.isFinite(entry.getValue()) || entry.getValue() <= 0 || entry.getValue() > 100) continue;
            EnumMap<Stat, Integer> values = new EnumMap<>(Stat.class);
            int total = 0;
            boolean valid = true;
            for (String component : entry.getKey().split("/", -1)) {
                var match = EV_COMPONENT.matcher(component.trim());
                if (!match.matches()) { valid = false; break; }
                Stat stat = Stat.valueOf(match.group(2).toUpperCase(Locale.ROOT));
                int value = Integer.parseInt(match.group(1));
                if (value > 252 || values.putIfAbsent(stat, value) != null) { valid = false; break; }
                total += value;
            }
            if (valid && total <= 510 && !values.isEmpty()) {
                return new RankedEvSpread(values.getOrDefault(Stat.HP, 0), values.getOrDefault(Stat.ATK, 0),
                        values.getOrDefault(Stat.DEF, 0), values.getOrDefault(Stat.SPA, 0),
                        values.getOrDefault(Stat.SPD, 0), values.getOrDefault(Stat.SPE, 0), entry.getValue());
            }
        }
        return null;
    }

    private static void applyEvSuggestion(PokemonSet pokemon, RankedEvSpread spread) {
        if (spread == null || pokemon.statsKnown || pokemon.evsManuallyEdited || pokemon.rankedEvSpread != null) return;
        // Nonzero values may also have been entered by a reflective consumer rather than our UI.
        for (int value : pokemon.evs.values()) if (value != 0) return;
        spread.applyTo(pokemon);
        pokemon.rankedEvSpread = spread;
    }

    static void mergeEvSuggestion(PokemonSet target, PokemonSet source) {
        if (target == null || source == null || source.statsKnown || source.evsManuallyEdited
                || !target.canMergeBattleDataFrom(source)
                || target.battleDataMode == BattleDataMode.RANDOM
                || source.battleDataMode == BattleDataMode.RANDOM
                || source.rankedEvSpread == null || !source.rankedEvSpread.matches(source)
                || !target.species.id().equals(source.species.id())) return;
        applyEvSuggestion(target, source.rankedEvSpread);
    }

    static boolean observeMove(PokemonSet pokemon, MoveData move) {
        if (pokemon == null || move == null) {
            return false;
        }
        boolean newlyObserved = pokemon.observedMoveIds.add(move.id());
        pokemon.suppressedMoveIds.remove(move.id());
        LinkedHashMap<String, MoveData> available = movesById(pokemon.moves);
        available.put(move.id(), move);
        rebuildMoveSlots(pokemon, available);
        pokemon.movesKnown = true;
        return newlyObserved;
    }

    static void mergeMoveKnowledge(PokemonSet target, PokemonSet source) {
        if (target == null || source == null || !target.canMergeBattleDataFrom(source)
                || target.battleDataMode == BattleDataMode.RANDOM
                || source.battleDataMode == BattleDataMode.RANDOM) {
            return;
        }
        LinkedHashMap<String, MoveData> available = movesById(target.moves);
        movesById(source.moves).forEach(available::putIfAbsent);
        if (target.rankedProfileKey.isBlank() && !source.rankedProfileKey.isBlank()) {
            target.rankedProfileKey = source.rankedProfileKey;
        }
        source.rankedMoveUsage.forEach(target.rankedMoveUsage::putIfAbsent);
        target.observedMoveIds.addAll(source.observedMoveIds);
        target.manualMoveIds.addAll(source.manualMoveIds);
        target.suppressedMoveIds.addAll(source.suppressedMoveIds);
        rebuildMoveSlots(target, available);
        target.movesKnown = target.movesKnown || source.movesKnown;
    }

    static void clearPrediction(PokemonSet pokemon) {
        if (pokemon == null) return;
        if (pokemon.rankedEvSpread != null) {
            if (!pokemon.statsKnown && !pokemon.evsManuallyEdited && pokemon.rankedEvSpread.matches(pokemon)) {
                pokemon.evs.replaceAll((stat, value) -> 0);
            }
            pokemon.rankedEvSpread = null;
        }
        if (pokemon.rankedProfileKey.isBlank()) return;
        Set<String> predicted = Set.copyOf(pokemon.rankedMoveUsage.keySet());
        for (int slot = 0; slot < pokemon.moves.size(); slot++) {
            MoveData move = pokemon.moves.get(slot);
            if (move != null && predicted.contains(move.id())
                    && !pokemon.observedMoveIds.contains(move.id())
                    && !pokemon.manualMoveIds.contains(move.id())) {
                pokemon.moves.set(slot, null);
            }
        }
        pokemon.rankedProfileKey = "";
        if (pokemon.rankedItemSuggested && !pokemon.itemKnown) {
            pokemon.item = "None";
        }
        if (pokemon.rankedAbilitySuggested && !pokemon.abilityKnown) {
            pokemon.ability = "None";
        }
        if (pokemon.rankedNatureSuggested && !pokemon.natureKnown) {
            pokemon.nature = TropimonDex.nature("serious");
        }
        pokemon.rankedItemSuggested = false;
        pokemon.rankedAbilitySuggested = false;
        pokemon.rankedNatureSuggested = false;
        pokemon.rankedMoveUsage.clear();
    }

    private static void rebuildMoveSlots(PokemonSet pokemon, Map<String, MoveData> available) {
        LinkedHashMap<String, Boolean> previousZ = new LinkedHashMap<>();
        for (int slot = 0; slot < pokemon.moves.size(); slot++) {
            MoveData move = pokemon.moves.get(slot);
            if (move != null) {
                previousZ.put(move.id(), slot < pokemon.zMoves.length && pokemon.zMoves[slot]);
            }
        }

        ArrayList<MoveData> result = new ArrayList<>(4);
        addProtectedMoves(result, pokemon, available, pokemon.observedMoveIds);
        addProtectedMoves(result, pokemon, available, pokemon.manualMoveIds);
        pokemon.rankedMoveUsage.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> available.get(entry.getKey()))
                .filter(move -> move != null && !pokemon.suppressedMoveIds.contains(move.id()))
                .forEach(move -> addUnique(result, move));
        for (MoveData move : available.values()) {
            if (move != null && !pokemon.suppressedMoveIds.contains(move.id())) {
                addUnique(result, move);
            }
        }

        pokemon.moves.clear();
        pokemon.moves.addAll(result.subList(0, Math.min(4, result.size())));
        while (pokemon.moves.size() < 4) {
            pokemon.moves.add(null);
        }
        Arrays.fill(pokemon.zMoves, false);
        for (int slot = 0; slot < pokemon.moves.size() && slot < pokemon.zMoves.length; slot++) {
            MoveData move = pokemon.moves.get(slot);
            if (move != null) {
                pokemon.zMoves[slot] = previousZ.getOrDefault(move.id(), false);
            }
        }
    }

    private static void addProtectedMoves(List<MoveData> output, PokemonSet pokemon,
                                          Map<String, MoveData> available, Set<String> protectedIds) {
        for (MoveData current : pokemon.moves) {
            if (current != null && protectedIds.contains(current.id())) {
                addUnique(output, current);
            }
        }
        for (String id : protectedIds) {
            MoveData move = available.get(id);
            if (move != null) {
                addUnique(output, move);
            }
        }
    }

    private static void addUnique(List<MoveData> output, MoveData move) {
        if (output.size() < 4 && output.stream().noneMatch(existing -> existing.id().equals(move.id()))) {
            output.add(move);
        }
    }

    private static LinkedHashMap<String, MoveData> movesById(Iterable<MoveData> moves) {
        LinkedHashMap<String, MoveData> output = new LinkedHashMap<>();
        if (moves == null) {
            return output;
        }
        for (MoveData move : moves) {
            if (move != null) {
                output.putIfAbsent(move.id(), move);
            }
        }
        return output;
    }

    private <T> T get(String path, Class<T> type) {
        try {
            return GSON.fromJson(getBody(path), type);
        } catch (JsonSyntaxException exception) {
            throw new RankedUsageException("Invalid Ranked Tropimon response", exception);
        }
    }

    private String getBody(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", "TropimonDamageCalculator")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RankedUsageException("Ranked Tropimon API returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new RankedUsageException("Unable to reach Ranked Tropimon", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RankedUsageException("Ranked Tropimon request interrupted", exception);
        }
    }

    private static List<Map.Entry<String, Double>> rankedValues(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String mostUsed(Map<String, Double> values) {
        return rankedValues(values).stream().findFirst().map(Map.Entry::getKey).orElse("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String key(String value) {
        return TropimonDex.normalize(value);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getClass().getName().contains("ExecutionException"))
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> T completedValue(CompletableFuture<T> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (CompletionException ignored) {
            return null;
        }
    }

    record RankedMove(String name, double usage) {
    }

    record RankedEvSpread(int hp, int atk, int def, int spa, int spd, int spe, double usage) {
        void applyTo(PokemonSet pokemon) {
            pokemon.evs.put(Stat.HP, hp);
            pokemon.evs.put(Stat.ATK, atk);
            pokemon.evs.put(Stat.DEF, def);
            pokemon.evs.put(Stat.SPA, spa);
            pokemon.evs.put(Stat.SPD, spd);
            pokemon.evs.put(Stat.SPE, spe);
        }

        boolean matches(PokemonSet pokemon) {
            return pokemon.evs.get(Stat.HP) == hp && pokemon.evs.get(Stat.ATK) == atk
                    && pokemon.evs.get(Stat.DEF) == def && pokemon.evs.get(Stat.SPA) == spa
                    && pokemon.evs.get(Stat.SPD) == spd && pokemon.evs.get(Stat.SPE) == spe;
        }
    }

    record RankedProfile(String speciesKey, String item, String ability, String nature,
                         List<RankedMove> moves, RankedEvSpread evSpread) {
        RankedProfile(String speciesKey, String item, String ability, String nature, List<RankedMove> moves) {
            this(speciesKey, item, ability, nature, moves, null);
        }
        RankedProfile {
            speciesKey = key(speciesKey);
            item = item == null ? "" : item;
            ability = ability == null ? "" : ability;
            nature = nature == null ? "" : nature;
            moves = moves == null ? List.of() : List.copyOf(moves);
        }
    }

    private record UsageIndex(String season, Map<String, String> apiNamesByKey) {
        String apiNameFor(SpeciesData species) {
            for (String candidate : List.of(species.id(), species.name())) {
                String match = apiNamesByKey.get(key(candidate));
                if (match != null) {
                    return match;
                }
            }
            if (key(species.id()).equals(key(species.cobblemonSpeciesId()))) {
                return apiNamesByKey.get(key(species.cobblemonSpeciesId()));
            }
            return null;
        }
    }

    private static final class SeasonResponse {
        String name;
        boolean active;
    }

    private static final class UsageEntryResponse {
        String name;
    }

    private static final class SpeciesStatsResponse {
        Map<String, Double> abilities = new LinkedHashMap<>();
        Map<String, Double> items = new LinkedHashMap<>();
        Map<String, Double> moves = new LinkedHashMap<>();
        Map<String, Double> natures = new LinkedHashMap<>();
        Map<String, Double> spreads = new LinkedHashMap<>();
    }

    private static final class RankedUsageException extends RuntimeException {
        RankedUsageException(String message) {
            super(message);
        }

        RankedUsageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
