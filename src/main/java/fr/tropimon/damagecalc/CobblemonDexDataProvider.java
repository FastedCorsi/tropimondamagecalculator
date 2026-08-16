package fr.tropimon.damagecalc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.io.Reader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class CobblemonDexDataProvider {
    private static final Pattern SHOWDOWN_MOVE_START = Pattern.compile("^  ([a-zA-Z0-9]+): \\{$");
    private static final Pattern SHOWDOWN_FLAGS = Pattern.compile("^    flags: \\{([^}]*)},?$");
    private static final Pattern SHOWDOWN_FLAG = Pattern.compile("([a-zA-Z0-9]+)\\s*:\\s*1");
    private static final Pattern SHOWDOWN_MULTIHIT = Pattern.compile("^    multihit: (?:\\[(\\d+),\\s*(\\d+)]|(\\d+)),?$");
    private static final Pattern SHOWDOWN_ACCURACY = Pattern.compile("^    accuracy: (\\d+),?$");
    private static final Map<String, String> COMPOUND_NAMES = Map.ofEntries(
            Map.entry("snowwarning", "Snow Warning"),
            Map.entry("woodhammer", "Wood Hammer"),
            Map.entry("razorleaf", "Razor Leaf"),
            Map.entry("leafstorm", "Leaf Storm"),
            Map.entry("earthpower", "Earth Power"),
            Map.entry("auroraveil", "Aurora Veil"),
            Map.entry("icepunch", "Ice Punch"),
            Map.entry("powdersnow", "Powder Snow"),
            Map.entry("grasswhistle", "Grass Whistle"),
            Map.entry("iceshard", "Ice Shard"),
            Map.entry("icywind", "Icy Wind"),
            Map.entry("sheercold", "Sheer Cold"),
            Map.entry("doubleedge", "Double Edge"),
            Map.entry("leechseed", "Leech Seed"),
            Map.entry("weatherball", "Weather Ball"),
            Map.entry("energyball", "Energy Ball"),
            Map.entry("gigadrain", "Giga Drain"),
            Map.entry("focusblast", "Focus Blast"),
            Map.entry("bulletseed", "Bullet Seed"),
            Map.entry("grassyglide", "Grassy Glide"),
            Map.entry("magicalleaf", "Magical Leaf"),
            Map.entry("terablast", "Tera Blast")
    );

    private CobblemonDexDataProvider() {
    }

    static CobblemonDexSnapshot loadSnapshot() {
        try {
            SpeciesLoadResult speciesLoad = loadSpecies();
            LinkedHashMap<String, MoveData> moves = loadMoves(loadBundledMoveFlags());
            List<String> abilities = loadAbilities();
            List<String> items = loadHeldItems();
            boolean hasData = !speciesLoad.species().isEmpty() || !moves.isEmpty() || abilities.size() > 1 || items.size() > 1;
            return new CobblemonDexSnapshot(hasData, speciesLoad.species(), moves, speciesLoad.legalMoveIds(),
                    speciesLoad.legalAbilities(), speciesLoad.hiddenAbilities(), abilities, items);
        } catch (Throwable throwable) {
            TropimonDamageCalcClient.warnOnce("cobblemon-dex-load", "Chargement de la BDD Cobblemon impossible", throwable);
            return CobblemonDexSnapshot.empty();
        }
    }

    private static SpeciesLoadResult loadSpecies() throws ReflectiveOperationException {
        LinkedHashMap<String, SpeciesData> output = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> legalMoveIds = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> legalAbilities = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> hiddenAbilities = new LinkedHashMap<>();
        Map<String, SpeciesResourceData> cobblemonResourceData = loadSpeciesDataFromCobblemonResources();
        Class<?> pokemonSpecies = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
        Collection<?> cobblemonSpecies = asCollection(pokemonSpecies.getMethod("getSpecies").invoke(null));
        for (Object species : cobblemonSpecies) {
            SpeciesData converted = convertSpecies(species);
            if (converted != null) {
                output.put(converted.id(), converted);
                List<String> baseMoves = legalMoveIds(species);
                legalMoveIds.put(converted.id(), baseMoves);
                SpeciesResourceData resourceData = cobblemonResourceData.get(converted.id());
                SpeciesAbilityData resourceAbilities = resourceData == null ? null : resourceData.abilities();
                LinkedHashSet<String> legal;
                LinkedHashSet<String> hidden;
                if (resourceAbilities != null) {
                    legal = new LinkedHashSet<>(resourceAbilities.legal());
                    hidden = new LinkedHashSet<>(resourceAbilities.hidden());
                } else {
                    legal = new LinkedHashSet<>(legalAbilityNames(species));
                    hidden = new LinkedHashSet<>(hiddenAbilityNames(species));
                }
                legalAbilities.put(converted.id(), new ArrayList<>(legal));
                hiddenAbilities.put(converted.id(), new ArrayList<>(hidden));
                for (FormResourceData form : formDataFromCobblemonSpecies(species, converted, legal, hidden)) {
                    SpeciesData formSpecies = form.toSpecies(converted);
                    output.put(formSpecies.id(), formSpecies);
                    legalMoveIds.put(formSpecies.id(), form.legalMoveIds().isEmpty() ? baseMoves : form.legalMoveIds());
                    legalAbilities.put(formSpecies.id(), form.abilities().legal().isEmpty()
                            ? new ArrayList<>(legal) : form.abilities().legal());
                    hiddenAbilities.put(formSpecies.id(), resolvedFormHiddenAbilities(form.abilities(), hidden));
                }
                if (resourceData != null) {
                    for (FormResourceData form : resourceData.forms()) {
                        SpeciesData formSpecies = form.toSpecies(converted);
                        output.putIfAbsent(formSpecies.id(), formSpecies);
                        List<String> formMoves = form.legalMoveIds().isEmpty() ? baseMoves : form.legalMoveIds();
                        legalMoveIds.put(formSpecies.id(), mergedValues(legalMoveIds.get(formSpecies.id()), formMoves));
                        List<String> formLegal = form.abilities().legal().isEmpty()
                                ? new ArrayList<>(legal) : form.abilities().legal();
                        List<String> formHidden = resolvedFormHiddenAbilities(form.abilities(), hidden);
                        legalAbilities.put(formSpecies.id(), mergedValues(legalAbilities.get(formSpecies.id()), formLegal));
                        hiddenAbilities.put(formSpecies.id(), mergedValues(hiddenAbilities.get(formSpecies.id()), formHidden));
                    }
                }
            }
        }
        return new SpeciesLoadResult(output, legalMoveIds, legalAbilities, hiddenAbilities);
    }

    private static Map<String, SpeciesResourceData> loadSpeciesDataFromCobblemonResources() {
        LinkedHashMap<String, SpeciesResourceData> output = new LinkedHashMap<>();
        try {
            ResourceManager resourceManager = MinecraftClient.getInstance().getResourceManager();
            loadSpeciesResourceRoot(resourceManager, output, "species");
            loadSpeciesResourceRoot(resourceManager, output, "species_additions");
            loadSpeciesFromFabricModRoots(output);
        } catch (Throwable ignored) {
        }
        return output;
    }

    private static void loadSpeciesResourceRoot(ResourceManager resourceManager, LinkedHashMap<String, SpeciesResourceData> output, String root) {
        try {
            Map<Identifier, Resource> resources = resourceManager.findResources(root,
                    id -> id.getNamespace().equals("cobblemon") && id.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                List<Resource> stack;
                try {
                    stack = resourceManager.getAllResources(entry.getKey());
                } catch (Throwable ignored) {
                    stack = List.of(entry.getValue());
                }
                for (Resource resource : stack) {
                    loadSpeciesResource(output, entry.getKey(), resource);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadSpeciesResource(LinkedHashMap<String, SpeciesResourceData> output, Identifier resourceId, Resource resource) {
        try (Reader reader = resource.getReader()) {
            loadSpeciesResource(output, resourceId, reader);
        } catch (Throwable ignored) {
        }
    }

    private static void loadSpeciesResource(LinkedHashMap<String, SpeciesResourceData> output, Identifier resourceId, Reader reader) {
        JsonElement parsed = JsonParser.parseReader(reader);
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject json = parsed.getAsJsonObject();
        String speciesId = speciesIdFromResource(resourceId, json);
        if (speciesId.isBlank()) {
            return;
        }
        LinkedHashSet<String> legal = new LinkedHashSet<>();
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        if (json.has("abilities") && json.get("abilities").isJsonArray()) {
            addAbilityJsonArray(json.getAsJsonArray("abilities"), legal, hidden);
        }
        ArrayList<FormResourceData> forms = formResourceData(speciesId, json, legal, hidden);
        mergeSpeciesResourceData(output, speciesId, new SpeciesAbilityData(new ArrayList<>(legal), new ArrayList<>(hidden)), forms);
    }

    private static void loadSpeciesFromFabricModRoots(LinkedHashMap<String, SpeciesResourceData> output) {
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                for (Path root : mod.getRootPaths()) {
                    loadSpeciesFromRoot(output, root, "data/cobblemon/species");
                    loadSpeciesFromRoot(output, root, "data/cobblemon/species_additions");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadSpeciesFromRoot(LinkedHashMap<String, SpeciesResourceData> output, Path root, String directory) {
        Path speciesRoot = root.resolve(directory);
        if (!Files.exists(speciesRoot)) {
            return;
        }
        try (var paths = Files.walk(speciesRoot)) {
            paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"))
                    .forEach(path -> {
                        try (Reader reader = Files.newBufferedReader(path)) {
                            String relative = speciesRoot.relativize(path).toString().replace('\\', '/');
                            String resourcePath = directory.substring("data/cobblemon/".length()) + "/" + relative;
                            loadSpeciesResource(output, Identifier.of("cobblemon", resourcePath), reader);
                        } catch (Throwable ignored) {
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void mergeSpeciesResourceData(LinkedHashMap<String, SpeciesResourceData> output, String speciesId,
                                                 SpeciesAbilityData abilities, List<FormResourceData> forms) {
        SpeciesResourceData existing = output.get(speciesId);
        LinkedHashSet<String> legal = new LinkedHashSet<>();
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        LinkedHashMap<String, FormResourceData> mergedForms = new LinkedHashMap<>();
        if (existing != null) {
            legal.addAll(existing.abilities().legal());
            hidden.addAll(existing.abilities().hidden());
            for (FormResourceData form : existing.forms()) {
                mergedForms.put(form.id(), form);
            }
        }
        legal.addAll(abilities.legal());
        hidden.addAll(abilities.hidden());
        for (FormResourceData form : forms) {
            mergedForms.merge(form.id(), form, CobblemonDexDataProvider::mergeFormResourceData);
        }
        output.put(speciesId, new SpeciesResourceData(
                new SpeciesAbilityData(new ArrayList<>(legal), new ArrayList<>(hidden)),
                new ArrayList<>(mergedForms.values())));
    }

    private static FormResourceData mergeFormResourceData(FormResourceData existing, FormResourceData addition) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(existing.baseStats());
        for (Stat stat : Stat.values()) {
            int candidate = addition.baseStats().getOrDefault(stat, 1);
            if (candidate > 1) stats.put(stat, candidate);
        }
        SpeciesAbilityData abilities = new SpeciesAbilityData(
                mergedValues(existing.abilities().legal(), addition.abilities().legal()),
                mergedValues(existing.abilities().hidden(), addition.abilities().hidden()));
        return new FormResourceData(
                existing.id(),
                addition.name().isBlank() ? existing.name() : addition.name(),
                addition.primaryType() == PokeType.NONE ? existing.primaryType() : addition.primaryType(),
                addition.secondaryType() == PokeType.NONE ? existing.secondaryType() : addition.secondaryType(),
                stats,
                mergedValues(existing.aspects(), addition.aspects()),
                mergedValues(existing.legalMoveIds(), addition.legalMoveIds()),
                abilities,
                addition.weightKg() > 0.0 ? addition.weightKg() : existing.weightKg());
    }

    private static <T> List<T> mergedValues(List<T> first, List<T> second) {
        LinkedHashSet<T> merged = new LinkedHashSet<>();
        if (first != null) merged.addAll(first);
        if (second != null) merged.addAll(second);
        return new ArrayList<>(merged);
    }

    static List<String> resolvedFormHiddenAbilities(SpeciesAbilityData formAbilities,
                                                    Collection<String> baseHiddenAbilities) {
        return formAbilities.legal().isEmpty()
                ? new ArrayList<>(baseHiddenAbilities)
                : new ArrayList<>(formAbilities.hidden());
    }

    static void addAbilityJsonArray(JsonArray abilities, LinkedHashSet<String> legal, LinkedHashSet<String> hidden) {
        LinkedHashSet<String> normalAbilities = new LinkedHashSet<>();
        for (JsonElement abilityElement : abilities) {
            if (!abilityElement.isJsonPrimitive()) {
                continue;
            }
            String raw = abilityElement.getAsString().trim();
            boolean hiddenAbility = raw.startsWith("h:");
            if (hiddenAbility) {
                raw = raw.substring(2);
            }
            String display = abilityDisplayName(raw);
            if (!display.isBlank()) {
                legal.add(display);
                if (hiddenAbility) {
                    if (!normalAbilities.contains(display)) {
                        hidden.add(display);
                    }
                } else {
                    normalAbilities.add(display);
                    hidden.remove(display);
                }
            }
        }
    }

    private static String abilityDisplayName(String raw) {
        String id = raw == null ? "" : raw.trim();
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        id = TropimonDex.normalize(id);
        if (id.isBlank()) {
            return "";
        }
        String key = "cobblemon.ability." + id;
        Language language = Language.getInstance();
        if (language.hasTranslation(key)) {
            String translated = language.get(key);
            if (!translated.isBlank() && !translated.equals(key)) {
                return translated;
            }
        }
        return cleanCobblemonName(id);
    }

    private static ArrayList<FormResourceData> formResourceData(String speciesId, JsonObject speciesJson,
                                                               LinkedHashSet<String> baseLegalAbilities,
                                                               LinkedHashSet<String> baseHiddenAbilities) {
        ArrayList<FormResourceData> forms = new ArrayList<>();
        if (!speciesJson.has("forms") || !speciesJson.get("forms").isJsonArray()) {
            return forms;
        }
        String baseName = speciesJson.has("name") && speciesJson.get("name").isJsonPrimitive()
                ? cleanCobblemonName(speciesJson.get("name").getAsString()) : pretty(speciesId);
        EnumMap<Stat, Integer> baseStats = parseStats(speciesJson, null);
        PokeType basePrimary = parseType(speciesJson, "primaryType", PokeType.NONE);
        PokeType baseSecondary = parseType(speciesJson, "secondaryType", PokeType.NONE);
        double baseWeight = parseDouble(speciesJson, "weight", 0.0);
        for (JsonElement formElement : speciesJson.getAsJsonArray("forms")) {
            if (!formElement.isJsonObject()) {
                continue;
            }
            JsonObject form = formElement.getAsJsonObject();
            String formName = form.has("name") && form.get("name").isJsonPrimitive()
                    ? cleanCobblemonName(form.get("name").getAsString()) : "";
            if (formName.isBlank()) {
                continue;
            }
            ArrayList<String> labels = stringArray(form, "labels");
            ArrayList<String> aspects = stringArray(form, "aspects");
            EnumMap<Stat, Integer> stats = parseStats(form, baseStats);
            PokeType primary = parseType(form, "primaryType", basePrimary);
            PokeType secondary = parseType(form, "secondaryType", baseSecondary);
            String normalizedFormName = TropimonDex.normalize(formName);
            if (normalizedFormName.equals("normal") || normalizedFormName.equals("standard")) {
                continue;
            }
            LinkedHashSet<String> legalAbilities = new LinkedHashSet<>();
            LinkedHashSet<String> hiddenAbilities = new LinkedHashSet<>();
            if (form.has("abilities") && form.get("abilities").isJsonArray()) {
                addAbilityJsonArray(form.getAsJsonArray("abilities"), legalAbilities, hiddenAbilities);
            }
            if (legalAbilities.isEmpty()) {
                legalAbilities.addAll(baseLegalAbilities);
                hiddenAbilities.addAll(baseHiddenAbilities);
            }
            String displayName = formDisplayName(baseName, formName);
            String id = speciesId + normalizedFormName;
            forms.add(new FormResourceData(
                    TropimonDex.normalize(id),
                    displayName,
                    primary,
                    secondary,
                    stats,
                    aspects,
                    parseMoveIds(form),
                    new SpeciesAbilityData(new ArrayList<>(legalAbilities), new ArrayList<>(hiddenAbilities)),
                    parseDouble(form, "weight", baseWeight)
            ));
        }
        return forms;
    }

    private static ArrayList<FormResourceData> formDataFromCobblemonSpecies(Object species, SpeciesData baseSpecies,
                                                                            LinkedHashSet<String> baseLegalAbilities,
                                                                            LinkedHashSet<String> baseHiddenAbilities) {
        ArrayList<FormResourceData> forms = new ArrayList<>();
        Object rawForms = invokeOptional(species, "getForms");
        if (!(rawForms instanceof Iterable<?> iterable)) {
            return forms;
        }
        for (Object form : iterable) {
            String formName = cleanCobblemonName(text(invokeOptional(form, "getName")));
            String formShowdown = TropimonDex.normalize(text(invokeOptional(form, "showdownId")));
            if (formName.isBlank() && formShowdown.isBlank()) {
                continue;
            }
            String normalizedFormName = TropimonDex.normalize(formName);
            String id = !formShowdown.isBlank() && !formShowdown.equals(baseSpecies.id())
                    ? formShowdown
                    : baseSpecies.id() + normalizedFormName;
            if (id.equals(baseSpecies.id()) || normalizedFormName.equals("normal") || normalizedFormName.equals("standard")) {
                continue;
            }

            EnumMap<Stat, Integer> stats = statsFromCobblemon(invokeOptional(form, "getBaseStats"), baseSpecies.baseStats());
            PokeType primary = typeFromCobblemon(invokeOptional(form, "getPrimaryType"));
            if (primary == PokeType.NONE) {
                primary = baseSpecies.primaryType();
            }
            PokeType secondary = typeFromCobblemon(invokeOptional(form, "getSecondaryType"));
            if (secondary == PokeType.NONE) {
                secondary = baseSpecies.secondaryType();
            }
            ArrayList<String> aspects = stringList(invokeOptional(form, "getAspects"));
            Object weightValue = invokeOptional(form, "getWeight");
            double weightKg = weightValue instanceof Number number ? number.doubleValue() : baseSpecies.weightKg();
            List<String> moves = legalMoveIds(form);
            LinkedHashSet<String> legalAbilities = new LinkedHashSet<>(legalAbilityNames(form));
            LinkedHashSet<String> hiddenAbilities = new LinkedHashSet<>(hiddenAbilityNames(form));
            if (legalAbilities.isEmpty()) {
                legalAbilities.addAll(baseLegalAbilities);
                hiddenAbilities.addAll(baseHiddenAbilities);
            }
            forms.add(new FormResourceData(
                    id,
                    formDisplayName(baseSpecies.name(), formName.isBlank() ? formShowdown : formName),
                    primary,
                    secondary,
                    stats,
                    aspects,
                    moves,
                    new SpeciesAbilityData(new ArrayList<>(legalAbilities), new ArrayList<>(hiddenAbilities)),
                    weightKg
            ));
        }
        return forms;
    }

    private static EnumMap<Stat, Integer> statsFromCobblemon(Object rawStats, EnumMap<Stat, Integer> fallback) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            stats.put(stat, fallback.getOrDefault(stat, 1));
        }
        if (rawStats instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Stat stat = statFromCobblemon(entry.getKey());
                if (stat != null && entry.getValue() instanceof Number number) {
                    stats.put(stat, number.intValue());
                }
            }
        }
        return stats;
    }

    private static String formDisplayName(String baseName, String formName) {
        String normalized = TropimonDex.normalize(formName);
        if (normalized.startsWith("mega")) {
            String suffix = formName.replace('-', ' ').trim();
            if (suffix.length() > 4) {
                suffix = suffix.substring(4).trim();
            } else {
                suffix = "";
            }
            return suffix.isBlank() ? "Mega " + baseName : "Mega " + baseName + " " + suffix;
        }
        return baseName + " " + formName.replace("-", " ");
    }

    private static EnumMap<Stat, Integer> parseStats(JsonObject source, EnumMap<Stat, Integer> fallback) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            stats.put(stat, fallback == null ? 1 : fallback.getOrDefault(stat, 1));
        }
        if (!source.has("baseStats") || !source.get("baseStats").isJsonObject()) {
            return stats;
        }
        JsonObject jsonStats = source.getAsJsonObject("baseStats");
        for (Map.Entry<String, JsonElement> entry : jsonStats.entrySet()) {
            Stat stat = statFromJsonName(entry.getKey());
            if (stat != null && entry.getValue().isJsonPrimitive()) {
                try {
                    stats.put(stat, entry.getValue().getAsInt());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return stats;
    }

    private static Stat statFromJsonName(String name) {
        return switch (TropimonDex.normalize(name)) {
            case "hp" -> Stat.HP;
            case "attack", "atk" -> Stat.ATK;
            case "defence", "defense", "def" -> Stat.DEF;
            case "specialattack", "spa" -> Stat.SPA;
            case "specialdefence", "specialdefense", "spd" -> Stat.SPD;
            case "speed", "spe" -> Stat.SPE;
            default -> null;
        };
    }

    private static PokeType parseType(JsonObject source, String key, PokeType fallback) {
        if (!source.has(key) || !source.get(key).isJsonPrimitive()) {
            return fallback;
        }
        PokeType parsed = PokeType.byName(source.get(key).getAsString());
        return parsed == PokeType.NONE ? fallback : parsed;
    }

    private static double parseDouble(JsonObject source, String key, double fallback) {
        if (!source.has(key) || !source.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return source.get(key).getAsDouble();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static ArrayList<String> stringArray(JsonObject source, String key) {
        ArrayList<String> values = new ArrayList<>();
        if (!source.has(key) || !source.get(key).isJsonArray()) {
            return values;
        }
        for (JsonElement element : source.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static ArrayList<String> stringList(Object value) {
        ArrayList<String> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = text(item);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private static ArrayList<String> parseMoveIds(JsonObject source) {
        ArrayList<String> moves = new ArrayList<>();
        if (!source.has("moves") || !source.get("moves").isJsonArray()) {
            return moves;
        }
        for (JsonElement element : source.getAsJsonArray("moves")) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String raw = element.getAsString();
            int colon = raw.lastIndexOf(':');
            if (colon >= 0) {
                raw = raw.substring(colon + 1);
            }
            String moveId = TropimonDex.normalize(raw);
            if (!moveId.isBlank() && !moves.contains(moveId)) {
                moves.add(moveId);
            }
        }
        return moves;
    }

    private static String speciesIdFromResource(Identifier resourceId, JsonObject json) {
        if (json.has("name") && json.get("name").isJsonPrimitive()) {
            String fromName = TropimonDex.normalize(json.get("name").getAsString());
            if (!fromName.isBlank()) {
                return fromName;
            }
        }
        if (json.has("target") && json.get("target").isJsonPrimitive()) {
            String target = json.get("target").getAsString();
            int colon = target.indexOf(':');
            if (colon >= 0) {
                target = target.substring(colon + 1);
            }
            String fromTarget = TropimonDex.normalize(target);
            if (!fromTarget.isBlank()) {
                return fromTarget;
            }
        }
        String path = resourceId.getPath();
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        if (fileName.endsWith(".json")) {
            fileName = fileName.substring(0, fileName.length() - ".json".length());
        }
        return TropimonDex.normalize(fileName);
    }

    private static List<String> legalMoveIds(Object species) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try {
            Object learnset = invokeOptional(species, "getMoves");
            Object legalMoves = invokeOptional(learnset, "getAllLegalMoves");
            if (legalMoves instanceof Collection<?> collection) {
                for (Object move : collection) {
                    String id = TropimonDex.normalize(text(invokeOptional(move, "getName")));
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return new ArrayList<>(ids);
    }

    private static SpeciesData convertSpecies(Object species) {
        try {
            String id = text(invokeOptional(species, "showdownId"));
            if (id.isBlank()) {
                id = text(invokeOptional(species, "getName"));
            }
            String name = cleanCobblemonName(text(invokeOptional(species, "getName")));
            if (name.isBlank()) {
                name = pretty(id);
            }

            EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
            for (Stat stat : Stat.values()) {
                stats.put(stat, stat == Stat.HP ? 1 : 1);
            }
            Object baseStats = invokeOptional(species, "getBaseStats");
            if (baseStats instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Stat stat = statFromCobblemon(entry.getKey());
                    if (stat != null && entry.getValue() instanceof Number number) {
                        stats.put(stat, number.intValue());
                    }
                }
            }

            PokeType primary = typeFromCobblemon(invokeOptional(species, "getPrimaryType"));
            PokeType secondary = typeFromCobblemon(invokeOptional(species, "getSecondaryType"));
            Object weightValue = invokeOptional(species, "getWeight");
            double weightKg = weightValue instanceof Number number ? number.doubleValue() : 0.0;
            boolean nfe = false;
            Object evolutions = invokeOptional(species, "getEvolutions");
            if (evolutions instanceof Collection<?> collection) {
                nfe = !collection.isEmpty();
            }
            return new SpeciesData(TropimonDex.normalize(id), name, primary, secondary, stats, nfe,
                    texturePath(species, id), TropimonDex.normalize(id), List.of(), weightKg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> legalAbilityNames(Object species) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            Object abilityPool = invokeOptional(species, "getAbilities");
            addAbilityNames(names, abilityPool);
            for (String method : List.of(
                    "getAbilities", "getAllAbilities", "getAll", "getPossibleAbilities",
                    "getNormalAbilities", "getPrimaryAbilities", "getSecondaryAbilities",
                    "getStandardAbilities", "getRegularAbilities", "getHiddenAbilities",
                    "getNormal", "getPrimary", "getSecondary", "getStandard", "getRegular", "getHidden"
            )) {
                addAbilityNames(names, invokeOptional(abilityPool, method));
            }
            addReturnedAbilityCollections(names, abilityPool, false);
        } catch (Throwable ignored) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    private static List<String> hiddenAbilityNames(Object species) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            Object abilityPool = invokeOptional(species, "getAbilities");
            Object hiddenAbilities = invokeOptional(abilityPool, "getHiddenAbilities");
            addAbilityNames(names, hiddenAbilities);
            Object hidden = invokeOptional(abilityPool, "getHidden");
            addAbilityNames(names, hidden);
            addAbilityNames(names, invokeOptional(abilityPool, "getHiddenAbility"));
            addReturnedAbilityCollections(names, abilityPool, true);
            if (abilityPool instanceof Iterable<?> iterable) {
                for (Object potential : iterable) {
                    if (isHiddenPotential(potential)) {
                        addAbilityName(names, potential);
                    }
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    private static void addAbilityNames(LinkedHashSet<String> names, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(inner -> addAbilityNames(names, inner));
        } else if (value instanceof Map<?, ?> map) {
            addAbilityNames(names, map.values());
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addAbilityName(names, java.lang.reflect.Array.get(value, i));
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object potential : iterable) {
                addAbilityName(names, potential);
            }
        } else {
            addAbilityName(names, value);
        }
    }

    private static void addReturnedAbilityCollections(LinkedHashSet<String> names, Object abilityPool, boolean hiddenOnly) {
        if (abilityPool == null) return;
        for (Method method : abilityPool.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
                continue;
            }
            String normalizedName = TropimonDex.normalize(method.getName());
            boolean likelyAbilityMethod = normalizedName.contains("abilit") || normalizedName.contains("hidden")
                    || normalizedName.contains("primary") || normalizedName.contains("secondary")
                    || normalizedName.contains("normal") || normalizedName.contains("regular")
                    || normalizedName.contains("standard");
            if (!likelyAbilityMethod || (hiddenOnly && !normalizedName.contains("hidden"))) {
                continue;
            }
            try {
                addAbilityNames(names, method.invoke(abilityPool));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addAbilityName(LinkedHashSet<String> names, Object potential) {
        if (potential == null) return;
        Object template = invokeOptional(potential, "getTemplate");
        if (template == null) {
            template = potential;
        }
        String display = cleanCobblemonName(displayText(invokeOptional(template, "getDisplayName")));
        if (display.isBlank()) {
            display = cleanCobblemonName(text(invokeOptional(template, "getName")));
        }
        if (!display.isBlank()) {
            names.add(display);
        }
    }

    private static boolean isHiddenPotential(Object potential) {
        if (potential == null) return false;
        for (String method : List.of("isHidden", "getHidden", "getIsHidden")) {
            Object value = invokeOptional(potential, method);
            if (value instanceof Boolean bool) {
                return bool;
            }
        }
        for (String method : List.of("getType", "getSlot", "getPriority", "getKind")) {
            String value = text(invokeOptional(potential, method));
            if (TropimonDex.normalize(value).contains("hidden")) {
                return true;
            }
        }
        return potential.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("hidden");
    }

    private static String texturePath(Object species, String fallbackId) {
        int dex = 0;
        Object dexValue = invokeOptional(species, "getNationalPokedexNumber");
        if (dexValue instanceof Number number) {
            dex = number.intValue();
        }
        String id = TropimonDex.normalize(fallbackId);
        if (dex <= 0 || id.isBlank()) {
            return "";
        }
        return String.format(Locale.ROOT, "textures/pokemon/%04d_%s/%s.png", dex, id, id);
    }

    private static LinkedHashMap<String, MoveData> loadMoves(Map<String, Set<String>> moveFlags) throws ReflectiveOperationException {
        LinkedHashMap<String, MoveData> output = new LinkedHashMap<>();
        Class<?> movesClass = Class.forName("com.cobblemon.mod.common.api.moves.Moves");
        List<?> moves = asList(movesClass.getMethod("all").invoke(null));
        for (Object move : moves) {
            MoveData converted = convertMove(move, moveFlags);
            if (converted != null) {
                output.put(converted.id(), converted);
            }
        }
        return output;
    }

    private static MoveData convertMove(Object move, Map<String, Set<String>> moveFlags) {
        try {
            String id = text(invokeOptional(move, "getName"));
            if (id.isBlank()) {
                return null;
            }
            String name = cleanCobblemonName(displayText(invokeOptional(move, "getDisplayName")));
            if (name.isBlank()) {
                name = pretty(id);
            }
            PokeType type = typeFromCobblemon(invokeOptional(move, "getElementalType"));
            DamageCategory category = categoryFromCobblemon(invokeOptional(move, "getDamageCategory"));
            Object powerValue = invokeOptional(move, "getPower");
            int power = powerValue instanceof Number number ? (int) Math.round(number.doubleValue()) : 0;
            Object priorityValue = invokeOptional(move, "getPriority");
            int priority = priorityValue instanceof Number number ? number.intValue() : 0;
            boolean spread = targetLooksSpread(invokeOptional(move, "getTarget"));
            String normalizedId = TropimonDex.normalize(id);
            Set<String> flags = moveFlags.getOrDefault(normalizedId, Set.of());
            boolean contact = flags.contains("contact") || (flags.isEmpty() && DamageCalculator.isContactMoveId(id));
            return new MoveData(normalizedId, name, type, category, power, spread, contact, flags, priority);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Set<String>> loadBundledMoveFlags() {
        Optional<ModContainer> cobblemon = FabricLoader.getInstance().getModContainer("cobblemon");
        if (cobblemon.isEmpty()) {
            return Map.of();
        }
        for (Path root : cobblemon.get().getRootPaths()) {
            Path archive = root.resolve("data/cobblemon/showdown.zip");
            if (!Files.isRegularFile(archive)) {
                continue;
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals("data/moves.js")) {
                        return parseMoveFlags(new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8)));
                    }
                }
            } catch (Throwable throwable) {
                TropimonDamageCalcClient.warnOnce("cobblemon-move-flags",
                        "Lecture des flags d'attaques Cobblemon impossible", throwable);
            }
        }
        return Map.of();
    }

    static Map<String, Set<String>> parseMoveFlags(BufferedReader reader) {
        LinkedHashMap<String, LinkedHashSet<String>> parsed = new LinkedHashMap<>();
        String currentMove = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher moveMatcher = SHOWDOWN_MOVE_START.matcher(line);
                if (moveMatcher.matches()) {
                    currentMove = TropimonDex.normalize(moveMatcher.group(1));
                    parsed.computeIfAbsent(currentMove, ignored -> new LinkedHashSet<>());
                    continue;
                }
                if (currentMove == null) {
                    continue;
                }
                Matcher flagsMatcher = SHOWDOWN_FLAGS.matcher(line);
                if (flagsMatcher.matches()) {
                    Matcher flagMatcher = SHOWDOWN_FLAG.matcher(flagsMatcher.group(1));
                    while (flagMatcher.find()) {
                        parsed.get(currentMove).add(TropimonDex.normalize(flagMatcher.group(1)));
                    }
                } else if (line.startsWith("    recoil: [") || line.startsWith("    hasCrashDamage: true")) {
                    parsed.get(currentMove).add("recoil");
                } else if (line.startsWith("    secondary: {") || line.startsWith("    secondaries: [")) {
                    parsed.get(currentMove).add("secondary");
                } else if (line.startsWith("    multiaccuracy: true")) {
                    parsed.get(currentMove).add("multiaccuracy");
                } else if (line.startsWith("    willCrit: true")) {
                    parsed.get(currentMove).add("alwayscrit");
                } else {
                    Matcher multiHitMatcher = SHOWDOWN_MULTIHIT.matcher(line);
                    if (multiHitMatcher.matches()) {
                        parsed.get(currentMove).add("multihit");
                        if (multiHitMatcher.group(3) != null) {
                            parsed.get(currentMove).add("hits" + multiHitMatcher.group(3));
                        } else {
                            parsed.get(currentMove).add("hits" + multiHitMatcher.group(1) + "to" + multiHitMatcher.group(2));
                        }
                    } else {
                        Matcher accuracyMatcher = SHOWDOWN_ACCURACY.matcher(line);
                        if (accuracyMatcher.matches()) {
                            parsed.get(currentMove).add("accuracy" + accuracyMatcher.group(1));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return Map.of();
        }
        LinkedHashMap<String, Set<String>> output = new LinkedHashMap<>();
        parsed.forEach((move, flags) -> output.put(move, Set.copyOf(flags)));
        return Map.copyOf(output);
    }

    private static List<String> loadAbilities() throws ReflectiveOperationException {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        output.add("None");
        Class<?> abilitiesClass = Class.forName("com.cobblemon.mod.common.api.abilities.Abilities");
        List<?> abilities = asList(abilitiesClass.getMethod("all").invoke(null));
        for (Object ability : abilities) {
            String display = cleanCobblemonName(text(invokeOptional(ability, "getDisplayName")));
            if (display.isBlank()) {
                display = cleanCobblemonName(text(invokeOptional(ability, "getName")));
            }
            if (!display.isBlank()) {
                output.add(display);
            }
        }
        return new ArrayList<>(output);
    }

    private static List<String> loadHeldItems() throws ReflectiveOperationException {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        output.add("None");
        Class<?> heldItemsClass = Class.forName("com.cobblemon.mod.common.api.item.HeldItems");
        Object instance = heldItemsClass.getField("INSTANCE").get(null);
        Object items = heldItemsClass.getMethod("getShowdownItems").invoke(instance);
        if (items instanceof Collection<?> collection) {
            for (Object item : collection) {
                String id = text(item);
                if (!id.isBlank()) {
                    output.add(cleanCobblemonName(id));
                }
            }
        }
        addRegistryUsefulItems(output);
        return new ArrayList<>(output);
    }

    private static void addRegistryUsefulItems(LinkedHashSet<String> output) {
        try {
            for (Item item : Registries.ITEM) {
                Identifier id = Registries.ITEM.getId(item);
                if (id != null && isUsefulItemNamespace(id.getNamespace()) && isUsefulItemCandidate(id.getPath())) {
                    output.add(cleanCobblemonName(id.getPath()));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isUsefulItemNamespace(String namespace) {
        return namespace.equals("cobblemon") || namespace.equals("mega_showdown");
    }

    private static boolean isUsefulItemCandidate(String path) {
        return !path.endsWith("_ball")
                && !path.endsWith("_rod")
                && !path.contains("apricorn")
                && !path.contains("tumblestone")
                && !path.contains("plank")
                && !path.contains("slab")
                && !path.contains("stairs")
                && !path.contains("wall")
                && !path.contains("door")
                && !path.contains("log")
                && !path.contains("wood")
                && !path.contains("leaves")
                && !path.contains("sapling")
                && !path.contains("sign")
                && !path.contains("template");
    }

    private static Stat statFromCobblemon(Object stat) {
        String id = text(invokeOptional(stat, "getShowdownId"));
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "hp" -> Stat.HP;
            case "atk" -> Stat.ATK;
            case "def" -> Stat.DEF;
            case "spa" -> Stat.SPA;
            case "spd" -> Stat.SPD;
            case "spe" -> Stat.SPE;
            default -> null;
        };
    }

    private static PokeType typeFromCobblemon(Object type) {
        String id = text(invokeOptional(type, "getShowdownId"));
        if (id.isBlank()) {
            id = text(invokeOptional(type, "getName"));
        }
        return PokeType.byName(id);
    }

    private static DamageCategory categoryFromCobblemon(Object category) {
        String name = text(invokeOptional(category, "getName")).toLowerCase(Locale.ROOT);
        if (name.contains("physical")) {
            return DamageCategory.PHYSICAL;
        }
        if (name.contains("special")) {
            return DamageCategory.SPECIAL;
        }
        return DamageCategory.STATUS;
    }

    private static boolean targetLooksSpread(Object target) {
        String value = text(target).toLowerCase(Locale.ROOT);
        return value.contains("all") || value.contains("adjacent") || value.contains("foes");
    }

    private static Object invokeOptional(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String displayText(Object value) {
        Object string = invokeOptional(value, "getString");
        return string == null ? "" : string.toString();
    }

    private static String cleanCobblemonName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("cobblemon.ability.")) {
            cleaned = cleaned.substring("cobblemon.ability.".length());
        } else if (cleaned.startsWith("ability.")) {
            cleaned = cleaned.substring("ability.".length());
        } else if (cleaned.startsWith("cobblemon.move.")) {
            cleaned = cleaned.substring("cobblemon.move.".length());
        } else if (cleaned.startsWith("move.")) {
            cleaned = cleaned.substring("move.".length());
        } else if (cleaned.contains(":")) {
            cleaned = cleaned.substring(cleaned.indexOf(':') + 1);
        }
        return pretty(cleaned);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Collection<?> asCollection(Object value) {
        return value instanceof Collection<?> collection ? collection : List.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String pretty(String id) {
        String normalized = id == null ? "" : id.replace('-', ' ').replace('_', ' ').trim();
        if (normalized.isBlank()) {
            return "";
        }
        String compact = normalized.replace(" ", "").toLowerCase(Locale.ROOT);
        String compound = COMPOUND_NAMES.get(compact);
        if (compound != null) {
            return compound;
        }
        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }
}

record CobblemonDexSnapshot(
        boolean loaded,
        LinkedHashMap<String, SpeciesData> species,
        LinkedHashMap<String, MoveData> moves,
        LinkedHashMap<String, List<String>> legalMoveIds,
        LinkedHashMap<String, List<String>> legalAbilities,
        LinkedHashMap<String, List<String>> hiddenAbilities,
        List<String> abilities,
        List<String> items
) {
    static CobblemonDexSnapshot empty() {
        return new CobblemonDexSnapshot(false, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), List.of(), List.of());
    }
}

record SpeciesLoadResult(
        LinkedHashMap<String, SpeciesData> species,
        LinkedHashMap<String, List<String>> legalMoveIds,
        LinkedHashMap<String, List<String>> legalAbilities,
        LinkedHashMap<String, List<String>> hiddenAbilities
) {
}

record SpeciesAbilityData(List<String> legal, List<String> hidden) {
}

record SpeciesResourceData(SpeciesAbilityData abilities, List<FormResourceData> forms) {
}

record FormResourceData(String id, String name, PokeType primaryType, PokeType secondaryType,
                        EnumMap<Stat, Integer> baseStats, List<String> aspects, List<String> legalMoveIds,
                        SpeciesAbilityData abilities, double weightKg) {
    FormResourceData(String id, String name, PokeType primaryType, PokeType secondaryType,
                     EnumMap<Stat, Integer> baseStats, List<String> aspects, List<String> legalMoveIds,
                     SpeciesAbilityData abilities) {
        this(id, name, primaryType, secondaryType, baseStats, aspects, legalMoveIds, abilities, 0.0);
    }

    SpeciesData toSpecies(SpeciesData baseSpecies) {
        return new SpeciesData(id, name, primaryType, secondaryType, new EnumMap<>(baseStats),
                baseSpecies.notFullyEvolved(), baseSpecies.texturePath(), baseSpecies.cobblemonSpeciesId(),
                List.copyOf(aspects), weightKg > 0.0 ? weightKg : baseSpecies.weightKg());
    }
}
