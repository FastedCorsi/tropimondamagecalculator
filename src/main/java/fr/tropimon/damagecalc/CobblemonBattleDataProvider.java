package fr.tropimon.damagecalc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CobblemonBattleDataProvider {
    private static final Pattern LEVEL_PATTERN = Pattern.compile("(?i)(?:lv\\.?|lvl\\.?|niveau|nv\\.?)\\s*(\\d{1,3})");
    private static final Pattern SHOWDOWN_LEVEL_PATTERN = Pattern.compile("(?i)(?:^|[,\\s])l(\\d{1,3})(?:$|[,\\s])");
    private static long nextPartyDebug;
    private static long nextPartyRefresh;
    private static UUID partyCacheOwner;
    private static List<PokemonSet> partyCache = List.of();
    private static final Map<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final EnumMap<Stat, Object> COBBLEMON_STATS = new EnumMap<>(Stat.class);
    private static volatile Object cobblemonClientInstance;
    private static boolean cobblemonStatsLoaded;
    private static Object battleCacheWorld;
    private static long battleCacheTick = Long.MIN_VALUE;
    private static Object battleCache;
    private static Object opponentRosterBattle;
    private static final LinkedHashMap<String, PokemonSet> opponentRoster = new LinkedHashMap<>();
    private static final LinkedHashMap<String, PokemonSet> previewPlayerRoster = new LinkedHashMap<>();
    private static final LinkedHashMap<String, PokemonSet> previewOpponentRoster = new LinkedHashMap<>();
    private static final LinkedHashMap<String, PokemonSet> previewOpponentFullRoster = new LinkedHashMap<>();
    private static final Set<String> exactRandomOpponentKeys = new HashSet<>();
    private static Object capturedPreviewScreen;
    private static Object capturedPreviewInformation;
    private static Object loggedPreviewScreen;
    private static Object loggedRandomPreviewCandidate;
    private static long previewOpponentRosterExpiresAt;
    private static long previewOpponentFullRosterExpiresAt;
    private static Object exactRandomPreviewBattle;
    private static final long TEAM_PREVIEW_TTL_MS = 600_000L;
    private static Object loggedOpponentRosterBattle;
    private static int loggedOpponentRosterSize = -1;
    private static String loggedLocalHydration = "";
    private static Object randomBattleIdentity;
    private static Boolean randomBattleDetected;
    private static long randomBattleQueueExpiresAt;
    private static final long RANDOM_BATTLE_QUEUE_TTL_MS = 900_000L;

    private CobblemonBattleDataProvider() {
    }

    static List<PokemonSet> playerParty(MinecraftClient client) {
        if (client == null || client.player == null) {
            partyCacheOwner = null;
            partyCache = List.of();
            nextPartyRefresh = 0L;
            return List.of();
        }
        long now = System.currentTimeMillis();
        UUID owner = client.player.getUuid();
        Object battle = null;
        try {
            battle = currentBattle(client);
        } catch (Throwable ignored) {
        }
        boolean previewAvailable = previewIsCurrent() && !previewPlayerRoster.isEmpty();
        if (battle == null && !previewAvailable && owner.equals(partyCacheOwner) && now < nextPartyRefresh) {
            return copyParty(partyCache);
        }
        ArrayList<PokemonSet> output = new ArrayList<>();
        HashSet<UUID> seen = new HashSet<>();
        try {
            Object actor = battle == null ? null : localBattleActor(battle, owner);
            Object battleTeam = invokeOptional(actor, "getPokemon");
            if (battleTeam != null) {
                addPartyPokemon(output, seen, battleTeam);
            }
            if (!output.isEmpty()) {
                debugParty("party loaded count=" + output.size() + " source=battle actor");
            } else if (previewAvailable) {
                output.addAll(previewPlayerParty());
                debugParty("party loaded count=" + output.size() + " source=team preview");
            } else {
                Object storage = invokeOptional(cobblemonClient(), "getStorage");
                for (Object party : partyCandidates(storage)) {
                    addPartyPokemon(output, seen, party);
                }
                debugParty("party loaded count=" + output.size() + " source=storage " + className(storage));
            }
            boolean randomBattle = battle != null
                    ? actor != null && isRandomBattle(battle, actor, client)
                    : previewAvailable && randomBattlePreviewExpected();
            if (randomBattle) {
                for (PokemonSet pokemon : output) {
                    applyRandomBattlePlayerEvDefaults(pokemon);
                    TropimonRandomBattleSets.applyInference(pokemon);
                }
            }
        } catch (Throwable ignored) {
            debugParty("party load failed: " + ignored.getClass().getSimpleName() + " " + ignored.getMessage());
        }
        partyCacheOwner = owner;
        nextPartyRefresh = now + 1_000L;
        partyCache = copyParty(output);
        return copyParty(partyCache);
    }

    static List<PokemonSet> opponentParty(MinecraftClient client) {
        if (client == null || client.player == null) {
            clearOpponentRoster(null);
            return List.of();
        }
        try {
            Object battle = currentBattle(client);
            resetOpponentRosterIfBattleChanged(battle);
            if (battle == null) {
                return previewOpponentParty();
            }
            Object localActor = localBattleActor(battle, client.player.getUuid());
            Object localSide = invokeOptional(localActor, "getSide");
            for (Object side : sides(battle)) {
                if (side == localSide) {
                    continue;
                }
                Object actors = invokeOptional(side, "getActors");
                if (actors instanceof Iterable<?> iterable) {
                    for (Object actor : iterable) {
                        rememberKnownActorPokemon(actor);
                    }
                }
                Object active = invokeOptional(side, "getActiveClientBattlePokemon");
                if (active instanceof Iterable<?> iterable) {
                    for (Object value : iterable) {
                        rememberOpponent(battlePokemonSet(value, false, client));
                    }
                }
            }
            logOpponentRosterSize(battle, "battle actor");
            PokemonSet randomTemplate = randomBattlePlayerTemplate(battle, localActor, client);
            if (randomTemplate != null) {
                attachFullRandomPreviewRoster(battle);
                for (PokemonSet opponent : opponentRoster.values()) {
                    if (!hasExactRandomOpponent(opponent)) {
                        applyRandomBattleOpponentRules(randomTemplate, opponent);
                        TropimonRandomBattleSets.applyInference(opponent);
                    }
                }
            }
            return copyParty(new ArrayList<>(opponentRoster.values()));
        } catch (Throwable throwable) {
            debugParty("opponent party load failed: " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            return copyParty(new ArrayList<>(opponentRoster.values()));
        }
    }

    static void captureVisibleTeamPreview(Object screen) {
        logRandomPreviewCandidate(screen);
        if (captureInventoryTeamPreview(screen)) {
            return;
        }
        Object information = teamPreviewInformation(screen);
        if (information == null) {
            return;
        }
        if (screen == capturedPreviewScreen && information == capturedPreviewInformation
                && (!previewPlayerRoster.isEmpty() || !previewOpponentRoster.isEmpty())) {
            previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
            if (!previewOpponentFullRoster.isEmpty()) {
                previewOpponentFullRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
            }
            return;
        }
        LinkedHashMap<String, PokemonSet> capturedPlayers = decodedPreviewRoster(
                invokeOptional(information, "getPlayerParty"), false);
        LinkedHashMap<String, PokemonSet> capturedFullOpponents = decodedPreviewRoster(
                invokeOptional(information, "getOpponentParty"), false);
        LinkedHashMap<String, PokemonSet> capturedOpponents = hiddenPreviewRoster(capturedFullOpponents);
        if (capturedPlayers.isEmpty() && capturedOpponents.isEmpty()) {
            return;
        }
        capturedPreviewScreen = screen;
        capturedPreviewInformation = information;
        previewPlayerRoster.clear();
        previewPlayerRoster.putAll(capturedPlayers);
        previewOpponentRoster.clear();
        previewOpponentRoster.putAll(capturedOpponents);
        previewOpponentFullRoster.clear();
        previewOpponentFullRoster.putAll(capturedFullOpponents);
        previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
        previewOpponentFullRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] team preview captured source={} players={} opponents={}",
                className(screen), capturedPlayers.size(), capturedOpponents.size());
    }

    private static LinkedHashMap<String, PokemonSet> hiddenPreviewRoster(Map<String, PokemonSet> fullRoster) {
        LinkedHashMap<String, PokemonSet> hidden = new LinkedHashMap<>();
        for (Map.Entry<String, PokemonSet> entry : fullRoster.entrySet()) {
            hidden.put(entry.getKey(), hideOpponentPrivateData(entry.getValue()));
        }
        return hidden;
    }

    private static LinkedHashMap<String, PokemonSet> decodedPreviewRoster(Object party, boolean hidePrivateData) {
        LinkedHashMap<String, PokemonSet> captured = new LinkedHashMap<>();
        Object decoded = invokeOptional(party, "getDecodedPokemons");
        if (!(decoded instanceof Iterable<?> iterable)) {
            return captured;
        }
        for (Object live : iterable) {
            PokemonSet converted = convertPartyPokemon(live);
            if (converted == null) {
                continue;
            }
            PokemonSet preview = hidePrivateData ? hideOpponentPrivateData(converted) : converted;
            captured.put(opponentKey(preview), preview);
        }
        return captured;
    }

    private static boolean captureInventoryTeamPreview(Object screen) {
        boolean recognizedTitle = looksLikeTeamPreviewScreen(screen);
        boolean randomBattleFallback = !recognizedTitle && randomBattlePreviewExpected();
        if (!recognizedTitle && !randomBattleFallback) {
            return false;
        }
        if (!(screen instanceof HandledScreen<?> handledScreen)) {
            return false;
        }
        List<Slot> slots = handledScreen.getScreenHandler().slots;
        int containerSlots = Math.max(0, slots.size() - 36);
        if (containerSlots == 0) {
            return false;
        }
        String title = TropimonDex.normalize(handledScreen.getTitle().getString());
        boolean doublesPreview = title.contains("select2pokemonfordoubles");
        LinkedHashMap<String, PokemonSet> capturedPlayers = inventoryPreviewRoster(
                slots, containerSlots, doublesPreview, true);
        LinkedHashMap<String, PokemonSet> capturedOpponents = inventoryPreviewRoster(
                slots, containerSlots, doublesPreview, false);
        if (capturedPlayers.isEmpty() && capturedOpponents.isEmpty()) {
            return false;
        }
        if (randomBattleFallback && (capturedPlayers.isEmpty() || capturedOpponents.isEmpty())) {
            return false;
        }
        if (screen == capturedPreviewScreen && screen == capturedPreviewInformation
                && previewPlayerRoster.keySet().equals(capturedPlayers.keySet())
                && previewOpponentRoster.keySet().equals(capturedOpponents.keySet())) {
            previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
            return true;
        }
        capturedPreviewScreen = screen;
        capturedPreviewInformation = screen;
        previewPlayerRoster.clear();
        previewPlayerRoster.putAll(capturedPlayers);
        previewOpponentRoster.clear();
        previewOpponentRoster.putAll(capturedOpponents);
        previewOpponentFullRoster.clear();
        previewOpponentFullRosterExpiresAt = 0L;
        previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
        TropimonDamageCalcClient.LOGGER.info(
                "[CalcDBG] team preview captured source=inventory title={} fallback={} players={} opponents={}",
                minecraftScreenTitle(screen), randomBattleFallback,
                capturedPlayers.size(), capturedOpponents.size());
        return true;
    }

    private static boolean randomBattlePreviewExpected() {
        return Boolean.TRUE.equals(randomBattleDetected)
                || System.currentTimeMillis() <= randomBattleQueueExpiresAt;
    }

    static boolean randomBattleActive(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        try {
            Object battle = currentBattle(client);
            if (battle != null) {
                Object actor = localBattleActor(battle, client.player.getUuid());
                return actor != null && isRandomBattle(battle, actor, client);
            }
            return previewIsCurrent() && randomBattlePreviewExpected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logRandomPreviewCandidate(Object screen) {
        if (!randomBattlePreviewExpected() || screen == null || screen == loggedRandomPreviewCandidate) {
            return;
        }
        loggedRandomPreviewCandidate = screen;
        TropimonDamageCalcClient.LOGGER.info(
                "[CalcDBG] random preview candidate class={} title={}",
                className(screen), minecraftScreenTitle(screen));
    }

    private static String minecraftScreenTitle(Object screen) {
        return screen instanceof Screen minecraftScreen ? minecraftScreen.getTitle().getString() : "";
    }

    private static LinkedHashMap<String, PokemonSet> inventoryPreviewRoster(List<Slot> slots, int containerSlots,
                                                                             boolean doublesPreview, boolean player) {
        LinkedHashMap<String, PokemonSet> captured = new LinkedHashMap<>();
        for (Slot slot : slots) {
            boolean teamSlot = player
                    ? isPlayerPreviewSlot(slot.id, containerSlots, doublesPreview)
                    : isOpponentPreviewSlot(slot.id, containerSlots, doublesPreview);
            if (!teamSlot) {
                continue;
            }
            PokemonSet preview = previewPokemonFromItem(slot.getStack());
            if (preview != null) {
                captured.put("slot:" + slot.id, preview);
            }
        }
        return captured;
    }

    static boolean isPlayerPreviewSlot(int slotId, int containerSlots, boolean doublesPreview) {
        if (slotId < 0 || slotId >= containerSlots) {
            return false;
        }
        if (doublesPreview) {
            return slotId >= 18 && slotId < 45 && (slotId % 9 == 1 || slotId % 9 == 2);
        }
        return slotId % 9 == 0;
    }

    static boolean isOpponentPreviewSlot(int slotId, int containerSlots, boolean doublesPreview) {
        if (slotId < 0 || slotId >= containerSlots) {
            return false;
        }
        if (doublesPreview) {
            return slotId >= 18 && slotId < 45 && (slotId % 9 == 6 || slotId % 9 == 7);
        }
        return slotId % 9 == 8;
    }

    private static PokemonSet previewPokemonFromItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        SpeciesData species = null;
        try {
            Object componentType = Registries.DATA_COMPONENT_TYPE.get(Identifier.of("cobblemon", "pokemon_item"));
            Object component = invokeOptional(stack, "get", componentType);
            Object speciesId = invokeOptional(component, "getSpecies");
            List<String> aspects = stringList(invokeOptional(component, "getAspects"));
            String id = speciesId instanceof Identifier identifier ? identifier.getPath() : text(speciesId);
            species = TropimonDex.findFormSpecies(id, "", "", aspects);
            if (species == null) {
                species = TropimonDex.findSpeciesByQuery(id);
            }
        } catch (Throwable ignored) {
        }
        if (species == null) {
            String display = stack.getName().getString();
            species = TropimonDex.findSpeciesByDisplayName(display);
            if (species == null) {
                species = TropimonDex.findSpeciesByQuery(display);
            }
        }
        return species == null ? null : hideOpponentPrivateData(new PokemonSet(species));
    }

    private static boolean looksLikeTeamPreviewScreen(Object screen) {
        if (!(screen instanceof Screen minecraftScreen)) {
            return false;
        }
        String title = TropimonDex.normalize(minecraftScreen.getTitle().getString());
        boolean preview = title.contains("selectyourleadpokemon")
                || title.contains("select2pokemonfordoubles")
                || title.contains("selectiondelequipe");
        if (preview && screen != loggedPreviewScreen) {
            loggedPreviewScreen = screen;
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] team preview screen recognized title={}",
                    minecraftScreen.getTitle().getString());
        }
        return preview;
    }

    private static Object teamPreviewInformation(Object screen) {
        if (screen == null) {
            return null;
        }
        for (String method : List.of("getInformations", "getBattleInformations", "getBattleInfo")) {
            Object value = invokeOptional(screen, method);
            if (hasOpponentParty(value)) {
                return value;
            }
        }
        for (String field : List.of("informations", "battleInformations", "battleInfo", "information")) {
            Object value = readFieldOptional(screen, field);
            if (hasOpponentParty(value)) {
                return value;
            }
        }
        return hasOpponentParty(screen) ? screen : null;
    }

    private static boolean hasOpponentParty(Object value) {
        return value != null && invokeOptional(value, "getOpponentParty") != null;
    }

    private static List<PokemonSet> previewOpponentParty() {
        if (!previewIsCurrent()) {
            previewOpponentRoster.clear();
            return List.of();
        }
        return copyParty(new ArrayList<>(previewOpponentRoster.values()));
    }

    private static List<PokemonSet> previewPlayerParty() {
        if (!previewIsCurrent()) {
            previewPlayerRoster.clear();
            return List.of();
        }
        return copyParty(new ArrayList<>(previewPlayerRoster.values()));
    }

    static PokemonSet hideOpponentPrivateData(PokemonSet source) {
        PokemonSet hidden = source.copy();
        hidden.item = "None";
        hidden.itemKnown = false;
        hidden.abilityKnown = false;
        hidden.natureKnown = false;
        hidden.statsKnown = false;
        hidden.movesKnown = false;
        hidden.currentHp = -1;
        hidden.observedMaxHp = -1;
        hidden.status = StatusCondition.NONE;
        hidden.evs.replaceAll((stat, value) -> 0);
        hidden.ivs.replaceAll((stat, value) -> 31);
        hidden.boosts.replaceAll((stat, value) -> 0);
        hidden.moves.clear();
        while (hidden.moves.size() < 4) {
            hidden.moves.add(null);
        }
        return hidden;
    }

    private static List<PokemonSet> copyParty(List<PokemonSet> source) {
        ArrayList<PokemonSet> copies = new ArrayList<>(source.size());
        for (PokemonSet pokemon : source) {
            if (pokemon != null) {
                copies.add(pokemon.copy());
            }
        }
        return copies;
    }

    private static void rememberKnownActorPokemon(Object actor) {
        Object known = invokeOptional(actor, "getPokemon");
        if (!(known instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object live : iterable) {
            rememberOpponent(convertPartyPokemon(live));
        }
    }

    private static void rememberOpponent(PokemonSet pokemon) {
        if (pokemon == null) {
            return;
        }
        if (Boolean.TRUE.equals(randomBattleDetected)) {
            TropimonRandomBattleSets.applyInference(pokemon);
        }
        String key = opponentKey(pokemon);
        PokemonSet existing = opponentRoster.get(key);
        boolean existingWasExact = exactRandomOpponentKeys.contains(key);
        if (existing == null && !pokemon.battleId.isBlank()) {
            String anonymousKey = matchingAnonymousOpponentKey(opponentRoster, pokemon);
            if (anonymousKey != null) {
                existing = opponentRoster.get(anonymousKey);
                opponentRoster.remove(anonymousKey);
                existingWasExact = exactRandomOpponentKeys.remove(anonymousKey);
                if (!existing.species.id().equals(pokemon.species.id())) {
                    TropimonDamageCalcClient.LOGGER.info(
                            "[CalcDBG] opponent form reconciled preview={} active={} battleId={}",
                            existing.species.name(), pokemon.species.name(), pokemon.battleId);
                }
            }
        }
        if (existing == null) {
            opponentRoster.put(key, pokemon.copy());
            return;
        }
        PokemonSet merged = pokemon.copy();
        merged.species = preferSpecificForm(existing.species, pokemon.species);
        if (!pokemon.itemKnown && existing.itemKnown) {
            merged.item = existing.item;
            merged.itemKnown = true;
        }
        if (!pokemon.abilityKnown && existing.abilityKnown) {
            merged.ability = existing.ability;
            merged.abilityKnown = true;
        }
        if (!pokemon.natureKnown && existing.natureKnown) {
            merged.nature = existing.nature;
            merged.natureKnown = true;
        }
        if (!pokemon.statsKnown && existing.statsKnown) {
            merged.evs.clear();
            merged.evs.putAll(existing.evs);
            merged.ivs.clear();
            merged.ivs.putAll(existing.ivs);
            merged.statsKnown = true;
        }
        mergeKnownMoves(merged, existing);
        opponentRoster.put(key, merged);
        if (existingWasExact) {
            exactRandomOpponentKeys.add(key);
        }
    }

    static String matchingAnonymousOpponentKey(Map<String, PokemonSet> roster, PokemonSet pokemon) {
        if (roster == null || pokemon == null) return null;
        String exactKey = null;
        ArrayList<String> baseMatches = new ArrayList<>();
        String incomingBase = TropimonDex.normalize(pokemon.species.cobblemonSpeciesId());
        for (Map.Entry<String, PokemonSet> entry : roster.entrySet()) {
            PokemonSet candidate = entry.getValue();
            if (candidate == null || !candidate.battleId.isBlank()) continue;
            if (candidate.species.id().equals(pokemon.species.id())) {
                exactKey = entry.getKey();
                break;
            }
            if (TropimonDex.normalize(candidate.species.cobblemonSpeciesId()).equals(incomingBase)) {
                baseMatches.add(entry.getKey());
            }
        }
        if (exactKey != null) return exactKey;
        return baseMatches.size() == 1 ? baseMatches.getFirst() : null;
    }

    private static void logOpponentRosterSize(Object battle, String source) {
        int size = opponentRoster.size();
        if (battle != loggedOpponentRosterBattle || size != loggedOpponentRosterSize) {
            loggedOpponentRosterBattle = battle;
            loggedOpponentRosterSize = size;
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] opponent roster source={} count={}", source, size);
        }
    }

    private static void mergeKnownMoves(PokemonSet target, PokemonSet source) {
        ArrayList<MoveData> known = new ArrayList<>();
        for (MoveData move : target.moves) {
            if (move != null && known.stream().noneMatch(value -> value.id().equals(move.id()))) {
                known.add(move);
            }
        }
        for (MoveData move : source.moves) {
            if (move != null && known.stream().noneMatch(value -> value.id().equals(move.id()))) {
                known.add(move);
            }
        }
        if (known.isEmpty()) {
            return;
        }
        target.moves.clear();
        target.moves.addAll(known.subList(0, Math.min(4, known.size())));
        while (target.moves.size() < 4) {
            target.moves.add(null);
        }
        target.movesKnown = true;
    }

    static void rememberOpponentRevelation(PokemonSet pokemon) {
        if (opponentRosterBattle != null) {
            rememberOpponent(pokemon);
            if (Boolean.TRUE.equals(randomBattleDetected)) {
                PokemonSet resolved = knownOpponent(pokemon);
                int candidates = TropimonRandomBattleSets.matchingSets(resolved).size();
                TropimonDamageCalcClient.LOGGER.info(
                        "[CalcDBG] random opponent inference pokemon={} candidates={} item={} ability={} moves={}",
                        resolved.species.name(), candidates, resolved.item, resolved.ability,
                        resolved.moves.stream().filter(java.util.Objects::nonNull).map(MoveData::name).toList());
            }
        }
    }

    private static PokemonSet knownOpponent(PokemonSet pokemon) {
        PokemonSet known = pokemon == null ? null : opponentRoster.get(opponentKey(pokemon));
        return known == null ? pokemon : known.copy();
    }

    private static String opponentKey(PokemonSet pokemon) {
        return pokemon.battleId.isBlank()
                ? pokemon.species.id() + ":" + TropimonDex.normalize(pokemon.battleName)
                : pokemon.battleId;
    }

    private static void resetOpponentRosterIfBattleChanged(Object battle) {
        // Cobblemon can briefly expose no battle while changing/minimizing its UI. Keeping the roster here
        // prevents a captured team preview from collapsing to only the currently active Pokemon.
        if (battle == null) {
            return;
        }
        if (battle != opponentRosterBattle) {
            clearOpponentRoster(battle);
        }
    }

    private static void clearOpponentRoster(Object battle) {
        opponentRosterBattle = battle;
        opponentRoster.clear();
        exactRandomOpponentKeys.clear();
        exactRandomPreviewBattle = null;
        loggedOpponentRosterBattle = null;
        loggedOpponentRosterSize = -1;
        if (battle != null && previewIsCurrent()) {
            for (PokemonSet preview : previewOpponentRoster.values()) {
                rememberOpponent(preview);
            }
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] team preview attached to battle opponents={}",
                    opponentRoster.size());
            logOpponentRosterSize(battle, "team preview");
            previewOpponentRoster.clear();
            previewOpponentRosterExpiresAt = 0L;
        }
    }

    private static boolean previewIsCurrent() {
        return !previewOpponentRoster.isEmpty() && System.currentTimeMillis() <= previewOpponentRosterExpiresAt;
    }

    private static void attachFullRandomPreviewRoster(Object battle) {
        if (battle == null || !Boolean.TRUE.equals(randomBattleDetected)
                || exactRandomPreviewBattle == battle || previewOpponentFullRoster.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() > previewOpponentFullRosterExpiresAt) {
            previewOpponentFullRoster.clear();
            previewOpponentFullRosterExpiresAt = 0L;
            return;
        }
        for (PokemonSet preview : previewOpponentFullRoster.values()) {
            rememberOpponent(preview);
            exactRandomOpponentKeys.add(opponentKey(preview));
        }
        exactRandomPreviewBattle = battle;
        TropimonDamageCalcClient.LOGGER.info(
                "[CalcDBG] exact random preview attached opponents={}", exactRandomOpponentKeys.size());
        previewOpponentFullRoster.clear();
        previewOpponentFullRosterExpiresAt = 0L;
    }

    private static boolean hasExactRandomOpponent(PokemonSet pokemon) {
        return pokemon != null && exactRandomPreviewBattle == opponentRosterBattle
                && exactRandomOpponentKeys.contains(opponentKey(pokemon));
    }

    static BattlePokemonSnapshot activeBattlePokemon(MinecraftClient client) {
        if (client == null || client.player == null) {
            return BattlePokemonSnapshot.empty();
        }
        try {
            Object battle = currentBattle(client);
            if (battle == null) {
                return BattlePokemonSnapshot.empty();
            }
            Object localActor = localBattleActor(battle, client.player.getUuid());
            ArrayList<Object> localActives = activePokemon(localActor);
            ArrayList<Object> opponentActives = opponentActivePokemon(battle, localActor);
            Object localActive = localActives.isEmpty() ? null : localActives.getFirst();
            Object opponentActive = opponentActives.isEmpty() ? null : opponentActives.getFirst();
            boolean randomBattle = isRandomBattle(battle, localActor, client);
            if (randomBattle) {
                attachFullRandomPreviewRoster(battle);
            }
            PokemonSet player = battlePokemonSet(localActive, true, client);
            PokemonSet opponent = battlePokemonSet(opponentActive, false, client);
            PokemonSet playerPartner = localActives.size() > 1
                    ? battlePokemonSet(localActives.get(1), true, client) : null;
            PokemonSet opponentPartner = opponentActives.size() > 1
                    ? battlePokemonSet(opponentActives.get(1), false, client) : null;
            if (randomBattle) {
                applyRandomBattlePlayerEvDefaults(player);
                applyRandomBattlePlayerEvDefaults(playerPartner);
                TropimonRandomBattleSets.applyInference(player);
                TropimonRandomBattleSets.applyInference(playerPartner);
                if (!hasExactRandomOpponent(opponent)) {
                    applyRandomBattleOpponentRules(player, opponent);
                    TropimonRandomBattleSets.applyInference(opponent);
                }
                if (!hasExactRandomOpponent(opponentPartner)) {
                    applyRandomBattleOpponentRules(player, opponentPartner);
                    TropimonRandomBattleSets.applyInference(opponentPartner);
                }
            }
            resetOpponentRosterIfBattleChanged(battle);
            rememberOpponent(opponent);
            rememberOpponent(opponentPartner);
            opponent = knownOpponent(opponent);
            boolean doubles = battleUsesMultiplePokemon(battle, localActor);
            debugParty("battle prefill player=" + (player == null ? "null" : player.species.name())
                    + " opponent=" + (opponent == null ? "null" : opponent.species.name())
                    + " doubles=" + doubles
                    + " active=" + localActives.size() + "/" + opponentActives.size()
                    + " partners=" + safeLogValue(pokemonName(playerPartner)) + "/"
                    + safeLogValue(pokemonName(opponentPartner))
                    + " abilities=" + safeLogValue(knownAbility(playerPartner)) + "/"
                    + safeLogValue(knownAbility(opponentPartner)));
            return new BattlePokemonSnapshot(player, opponent, doubles,
                    knownAbility(playerPartner), knownAbility(opponentPartner),
                    pokemonName(playerPartner), pokemonName(opponentPartner),
                    Math.max(1, localActives.size()), Math.max(1, opponentActives.size()));
        } catch (Throwable throwable) {
            debugParty("battle prefill failed: " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            return BattlePokemonSnapshot.empty();
        }
    }

    static boolean isInBattle() {
        try {
            return currentBattle(MinecraftClient.getInstance()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void synchronizeBattleTracker() {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            Object previousBattle = battleCache;
            Object current = invokeOptional(cobblemonClient(), "getBattle");
            battleCache = current;
            battleCacheWorld = client == null ? null : client.world;
            battleCacheTick = client != null && client.world != null
                    ? client.world.getTime()
                    : System.currentTimeMillis() / 50L;
            if (previousBattle != current) {
                CobblemonBattleConditionTracker.resetForBattle(current);
                resetOpponentRosterIfBattleChanged(current);
            }
        } catch (Throwable throwable) {
            debugParty("battle tracker sync failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static Object currentBattle(MinecraftClient client) throws ReflectiveOperationException {
        long tick = client != null && client.world != null
                ? client.world.getTime()
                : System.currentTimeMillis() / 50L;
        Object world = client == null ? null : client.world;
        if (world == battleCacheWorld && tick == battleCacheTick) {
            return battleCache;
        }
        Object previousBattle = battleCache;
        battleCache = invokeOptional(cobblemonClient(), "getBattle");
        if (previousBattle != battleCache) {
            CobblemonBattleConditionTracker.resetForBattle(battleCache);
            resetOpponentRosterIfBattleChanged(battleCache);
        }
        battleCacheWorld = world;
        battleCacheTick = tick;
        return battleCache;
    }

    private static int parseLevel(String display, int fallback) {
        Matcher matcher = LEVEL_PATTERN.matcher(display);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            int level = Integer.parseInt(matcher.group(1));
            return Math.max(1, Math.min(100, level));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static PokemonSet convertPartyPokemon(Object live) {
        if (live == null) {
            return null;
        }
        SpeciesData species = speciesFromLivePokemon(live);
        if (species == null) {
            debugParty("party pokemon not mapped live=" + className(live) + " display=" + displayText(invokeOptional(live, "getDisplayName", false))
                    + " showdown=" + text(invokeOptional(live, "showdownId")));
            return null;
        }
        PokemonSet set = new PokemonSet(species);
        Object uuid = invokeOptional(live, "getUuid");
        if (uuid != null) {
            set.battleId = uuid.toString();
        }
        set.battleName = displayText(invokeOptional(live, "getDisplayName", false));
        Object level = invokeOptional(live, "getLevel");
        if (level instanceof Number number) {
            set.level = Math.max(1, Math.min(100, number.intValue()));
        }
        Object hp = invokeOptional(live, "getCurrentHealth");
        if (hp instanceof Number number && number.intValue() >= 0) {
            set.currentHp = number.intValue();
        }
        Object maxHealth = invokeOptional(live, "getMaxHealth");
        if (maxHealth instanceof Number number && number.intValue() > 0) {
            set.observedMaxHp = number.intValue();
        }
        set.item = itemName(invokeOptional(live, "heldItem"));
        if (TropimonDex.normalize(set.item).isBlank()) {
            set.item = itemName(invokeOptional(live, "getHeldItem$common"));
        }
        if (TropimonDex.normalize(set.item).isBlank()) {
            set.item = "None";
        }
        String ability = abilityName(invokeOptional(live, "getAbility"));
        if (!ability.isBlank()) {
            set.ability = ability;
        }
        NatureData nature = natureFromLive(live);
        if (nature != null) {
            set.nature = nature;
        }
        copyStats(invokeOptional(live, "getEvs"), set.evs);
        copyStats(invokeOptional(live, "getIvs"), set.ivs);
        debugParty("converted " + set.species.name() + " ev=" + set.evSummary() + " nature=" + set.nature.name() + " ability=" + set.ability);
        set.moves.clear();
        for (MoveData move : movesFromLive(live)) {
            set.moves.add(move);
        }
        if (set.moves.isEmpty()) {
            set.moves.addAll(TropimonDex.defaultMoves(species));
        }
        return set;
    }

    private static PokemonSet randomBattlePlayerTemplate(Object battle, Object localActor, MinecraftClient client) {
        if (!isRandomBattle(battle, localActor, client)) {
            return null;
        }
        Object team = invokeOptional(localActor, "getPokemon");
        if (team instanceof Iterable<?> iterable) {
            for (Object live : iterable) {
                PokemonSet converted = convertPartyPokemon(live);
                if (converted != null) {
                    applyRandomBattlePlayerEvDefaults(converted);
                    return converted;
                }
            }
        }
        return null;
    }

    private static boolean isRandomBattle(Object battle, Object localActor, MinecraftClient client) {
        if (battle == null || localActor == null || client == null) {
            return false;
        }
        if (randomBattleIdentity != battle) {
            randomBattleIdentity = battle;
            randomBattleDetected = null;
        }
        if (randomBattleDetected != null) {
            return randomBattleDetected;
        }
        if (System.currentTimeMillis() <= randomBattleQueueExpiresAt) {
            randomBattleQueueExpiresAt = 0L;
            randomBattleDetected = true;
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] random battle detected source=queue-message");
            return true;
        }
        Object format = invokeOptional(battle, "getBattleFormat");
        Object battleType = invokeOptional(format, "getBattleType");
        if (randomFormatLabel(text(invokeOptional(format, "getMod")),
                text(invokeOptional(battleType, "getName")),
                text(invokeOptional(format, "getRuleSet")))) {
            randomBattleDetected = true;
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] random battle detected source=format");
            return true;
        }
        Object liveTeam = invokeOptional(localActor, "getPokemon");
        if (!(liveTeam instanceof Iterable<?> iterable)) {
            return false;
        }
        ArrayList<PokemonSet> converted = new ArrayList<>();
        boolean persistentMemberFound = false;
        for (Object live : iterable) {
            PokemonSet pokemon = convertPartyPokemon(live);
            if (pokemon == null) {
                continue;
            }
            converted.add(pokemon);
            Object uuid = invokeOptional(live, "getUuid");
            if (uuid instanceof UUID pokemonUuid && persistentPartyContainsUuid(client, pokemonUuid)) {
                persistentMemberFound = true;
            }
        }
        if (converted.size() < 6) {
            return false;
        }
        randomBattleDetected = isGeneratedRandomTeam(converted.size(), persistentMemberFound);
        TropimonDamageCalcClient.LOGGER.info(
                "[CalcDBG] random battle detected={} source=generated-team size={} persistentMember={} levels={}",
                randomBattleDetected, converted.size(), persistentMemberFound,
                converted.stream().map(pokemon -> pokemon.species.name() + "=" + pokemon.level).toList());
        return randomBattleDetected;
    }

    static void observeSystemMessage(Text message) {
        if (message == null || !isRandomBattleQueueMessage(message.getString())) {
            return;
        }
        randomBattleQueueExpiresAt = System.currentTimeMillis() + RANDOM_BATTLE_QUEUE_TTL_MS;
        randomBattleDetected = null;
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] random battle queue message detected");
    }

    static boolean isRandomBattleQueueMessage(String message) {
        return TropimonDex.normalize(message).contains("randombattle");
    }

    static boolean isGeneratedRandomTeam(int teamSize, boolean persistentMemberFound) {
        return teamSize >= 6 && !persistentMemberFound;
    }

    static boolean randomFormatLabel(String... values) {
        for (String value : values) {
            if (TropimonDex.normalize(value).contains("random")) {
                return true;
            }
        }
        return false;
    }

    private static boolean persistentPartyContainsUuid(MinecraftClient client, UUID uuid) {
        if (client == null || uuid == null) {
            return false;
        }
        try {
            Object storage = invokeOptional(cobblemonClient(), "getStorage");
            for (Object party : partyCandidates(storage)) {
                if (partyPokemonByUuid(party, uuid) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void applyRandomBattleOpponentRules(PokemonSet player, PokemonSet opponent) {
        if (player == null || opponent == null) {
            return;
        }
        opponent.evs.clear();
        opponent.evs.putAll(player.evs);
        opponent.nature = TropimonDex.nature("serious");
        opponent.statsKnown = true;
        opponent.natureKnown = true;
    }

    static void applyRandomBattlePlayerEvDefaults(PokemonSet pokemon) {
        if (pokemon == null || pokemon.evs.values().stream().anyMatch(value -> value != 0)) {
            return;
        }
        pokemon.evs.replaceAll((stat, value) -> 85);
        pokemon.statsKnown = true;
    }

    private static PokemonSet convertPartyPokemonByUuid(MinecraftClient client, UUID uuid) {
        if (uuid == null || client == null || client.player == null) {
            return null;
        }
        try {
            Object storage = invokeOptional(cobblemonClient(), "getStorage");
            for (Object party : partyCandidates(storage)) {
                Object found = partyPokemonByUuid(party, uuid);
                PokemonSet converted = convertPartyPokemon(found);
                if (converted != null) {
                    return converted;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object partyPokemonByUuid(Object party, UUID uuid) {
        if (party == null || uuid == null) {
            return null;
        }
        Object slots = invokeOptional(party, "getSlots");
        if (slots instanceof Iterable<?> iterable) {
            for (Object pokemon : iterable) {
                if (uuid.equals(invokeOptional(pokemon, "getUuid"))) {
                    return pokemon;
                }
            }
        }
        if (party instanceof Iterable<?> iterable) {
            for (Object pokemon : iterable) {
                if (uuid.equals(invokeOptional(pokemon, "getUuid"))) {
                    return pokemon;
                }
            }
        }
        return null;
    }

    private static Object localBattleActor(Object battle, UUID playerUuid) {
        for (Object side : sides(battle)) {
            Object actors = invokeOptional(side, "getActors");
            if (actors instanceof Iterable<?> iterable) {
                for (Object actor : iterable) {
                    if (playerUuid.equals(invokeOptional(actor, "getUuid"))) {
                        return actor;
                    }
                }
            }
        }
        for (Object side : sides(battle)) {
            Object actors = invokeOptional(side, "getActors");
            if (actors instanceof Iterable<?> iterable) {
                for (Object actor : iterable) {
                    if ("PLAYER".equals(text(invokeOptional(actor, "getType")))) {
                        return actor;
                    }
                }
            }
        }
        return null;
    }

    private static ArrayList<Object> sides(Object battle) {
        ArrayList<Object> sides = new ArrayList<>();
        Object side1 = invokeOptional(battle, "getSide1");
        Object side2 = invokeOptional(battle, "getSide2");
        if (side1 != null) sides.add(side1);
        if (side2 != null) sides.add(side2);
        return sides;
    }

    private static ArrayList<Object> activePokemon(Object actor) {
        ArrayList<Object> output = new ArrayList<>();
        Object active = invokeOptional(actor, "getActivePokemon");
        if (active instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (invokeOptional(value, "getBattlePokemon") != null) {
                    output.add(value);
                }
            }
        }
        return output;
    }

    private static ArrayList<Object> opponentActivePokemon(Object battle, Object localActor) {
        ArrayList<Object> output = new ArrayList<>();
        Object localSide = invokeOptional(localActor, "getSide");
        for (Object side : sides(battle)) {
            if (side == localSide) {
                continue;
            }
            Object active = invokeOptional(side, "getActiveClientBattlePokemon");
            if (active instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    Object battlePokemon = invokeOptional(value, "getBattlePokemon");
                    if (battlePokemon != null) {
                        output.add(value);
                    }
                }
            }
            if (!output.isEmpty()) {
                return output;
            }
            Object actors = invokeOptional(side, "getActors");
            if (actors instanceof Iterable<?> iterable) {
                for (Object actor : iterable) {
                    output.addAll(activePokemon(actor));
                }
            }
        }
        return output;
    }

    private static String knownAbility(PokemonSet pokemon) {
        return pokemon != null && pokemon.abilityKnown ? pokemon.ability : null;
    }

    private static String pokemonName(PokemonSet pokemon) {
        if (pokemon == null) return null;
        return pokemon.battleName == null || pokemon.battleName.isBlank()
                ? pokemon.species.name() : pokemon.battleName;
    }

    private static String safeLogValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static boolean battleUsesMultiplePokemon(Object battle, Object localActor) {
        Object format = invokeOptional(battle, "getBattleFormat");
        Object battleType = invokeOptional(format, "getBattleType");
        Object pokemonPerSide = invokeOptional(battleType, "getPokemonPerSide");
        if (pokemonPerSide instanceof Number number) {
            return number.intValue() > 1;
        }
        Object slotsPerActor = invokeOptional(battleType, "getSlotsPerActor");
        if (slotsPerActor instanceof Number number && number.intValue() > 1) {
            return true;
        }
        Object localSide = invokeOptional(localActor, "getSide");
        Object active = invokeOptional(localSide, "getActiveClientBattlePokemon");
        int count = 0;
        if (active instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (invokeOptional(value, "getBattlePokemon") != null && ++count > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PokemonSet battlePokemonSet(Object activeBattlePokemon, boolean localPlayer, MinecraftClient client) {
        Object battlePokemon = invokeOptional(activeBattlePokemon, "getBattlePokemon");
        if (battlePokemon == null) {
            return null;
        }
        if (localPlayer) {
            Object uuid = invokeOptional(battlePokemon, "getUuid");
            SpeciesData activeSpecies = speciesFromBattlePokemon(battlePokemon);
            PokemonSet partySet = localBattleActorPokemon(activeBattlePokemon, uuid, activeSpecies);
            if (partySet == null) {
                partySet = localPartyPokemon(client, uuid, activeSpecies);
            }
            if (partySet != null) {
                if (activeSpecies != null) {
                    partySet.species = preferSpecificForm(partySet.species, activeSpecies);
                }
                applyBattleRuntime(partySet, battlePokemon);
                CobblemonBattleConditionTracker.applyHistory(partySet, true);
                return partySet;
            }
        }
        SpeciesData species = speciesFromBattlePokemon(battlePokemon);
        if (species == null) {
            debugParty("battle pokemon not mapped display=" + displayText(invokeOptional(battlePokemon, "getDisplayName", false))
                    + " species=" + text(invokeOptional(invokeOptional(battlePokemon, "getProperties"), "getSpecies")));
            return null;
        }
        PokemonSet set = new PokemonSet(species);
        if (localPlayer) {
            set.itemKnown = false;
            set.abilityKnown = false;
            set.natureKnown = false;
            set.statsKnown = false;
            set.movesKnown = false;
        } else {
            set.itemKnown = false;
            set.abilityKnown = false;
            set.natureKnown = false;
            set.statsKnown = false;
            set.movesKnown = false;
            set.moves.clear();
            while (set.moves.size() < 4) {
                set.moves.add(null);
            }
        }
        int fallbackLevel = Boolean.TRUE.equals(randomBattleDetected)
                ? TropimonRandomBattleSets.suggestedLevel(species, set.level)
                : set.level;
        set.level = battlePokemonLevel(battlePokemon, fallbackLevel);
        Object properties = invokeOptional(battlePokemon, "getProperties");
        applyBattleProperties(set, properties);
        applyBattleRuntime(set, battlePokemon);
        CobblemonBattleConditionTracker.applyHistory(set, localPlayer);
        return set;
    }

    private static PokemonSet localBattleActorPokemon(Object activeBattlePokemon, Object battleUuid,
                                                       SpeciesData activeSpecies) {
        Object actor = invokeOptional(activeBattlePokemon, "getActor");
        Object liveTeam = invokeOptional(actor, "getPokemon");
        if (!(liveTeam instanceof Iterable<?> iterable)) {
            return null;
        }
        ArrayList<PokemonSet> converted = new ArrayList<>();
        for (Object live : iterable) {
            PokemonSet pokemon = convertPartyPokemon(live);
            if (pokemon != null) {
                converted.add(pokemon);
            }
        }
        PokemonSet matched = matchingOwnedPokemon(converted, battleUuid, activeSpecies);
        if (matched != null) {
            logLocalHydration(matched, "battle actor");
        }
        return matched;
    }

    static PokemonSet matchingOwnedPokemon(List<PokemonSet> team, Object battleUuid, SpeciesData activeSpecies) {
        if (team == null || team.isEmpty()) {
            return null;
        }
        String wantedId = battleUuid == null ? "" : battleUuid.toString();
        if (!wantedId.isBlank()) {
            for (PokemonSet candidate : team) {
                if (wantedId.equals(candidate.battleId)) {
                    return candidate.copy();
                }
            }
        }
        if (activeSpecies == null) {
            return null;
        }
        ArrayList<PokemonSet> exactForms = new ArrayList<>();
        for (PokemonSet candidate : team) {
            if (candidate.species.id().equals(activeSpecies.id())) {
                exactForms.add(candidate);
            }
        }
        if (exactForms.size() == 1) {
            return exactForms.getFirst().copy();
        }
        ArrayList<PokemonSet> baseSpecies = new ArrayList<>();
        for (PokemonSet candidate : team) {
            if (candidate.species.cobblemonSpeciesId().equals(activeSpecies.cobblemonSpeciesId())) {
                baseSpecies.add(candidate);
            }
        }
        return baseSpecies.size() == 1 ? baseSpecies.getFirst().copy() : null;
    }

    private static PokemonSet localPartyPokemon(MinecraftClient client, Object battleUuid, SpeciesData activeSpecies) {
        PokemonSet exact = battleUuid instanceof UUID uuid ? convertPartyPokemonByUuid(client, uuid) : null;
        if (exact != null) {
            logLocalHydration(exact, "uuid");
            return exact;
        }
        List<PokemonSet> party = playerParty(client);
        String wantedId = battleUuid == null ? "" : battleUuid.toString();
        if (!wantedId.isBlank()) {
            for (PokemonSet candidate : party) {
                if (wantedId.equals(candidate.battleId)) {
                    logLocalHydration(candidate, "party UUID");
                    return candidate.copy();
                }
            }
        }
        if (activeSpecies != null) {
            for (PokemonSet candidate : party) {
                if (candidate.species.id().equals(activeSpecies.id())) {
                    logLocalHydration(candidate, "species/form");
                    return candidate.copy();
                }
            }
            for (PokemonSet candidate : party) {
                if (candidate.species.cobblemonSpeciesId().equals(activeSpecies.cobblemonSpeciesId())) {
                    logLocalHydration(candidate, "base species");
                    return candidate.copy();
                }
            }
        }
        logLocalHydration(null, "not found");
        return null;
    }

    private static void logLocalHydration(PokemonSet pokemon, String source) {
        String message = source + ":" + (pokemon == null ? "none" : pokemon.battleId + ":" + pokemon.species.id());
        if (message.equals(loggedLocalHydration)) {
            return;
        }
        loggedLocalHydration = message;
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] local party hydration source={} pokemon={} privateData={}",
                source,
                pokemon == null ? "none" : pokemon.species.name(),
                pokemon != null && pokemon.itemKnown && pokemon.abilityKnown
                        && pokemon.natureKnown && pokemon.statsKnown && pokemon.movesKnown);
    }

    private static SpeciesData speciesFromBattlePokemon(Object battlePokemon) {
        Object properties = invokeOptional(battlePokemon, "getProperties");
        Object speciesObject = invokeOptional(battlePokemon, "getSpecies");
        LinkedHashSet<String> activeAspects = new LinkedHashSet<>(stringList(invokeOptional(properties, "getAspects")));
        activeAspects.addAll(stringList(invokeOptional(battlePokemon, "getAspects")));
        activeAspects.addAll(stringList(readFieldOptional(battlePokemon, "aspects")));
        Object renderState = invokeOptional(battlePokemon, "getState");
        activeAspects.addAll(stringList(invokeOptional(renderState, "getCurrentAspects")));
        Object formObject = invokeOptional(properties, "getForm");
        String formName = text(invokeOptional(formObject, "getName"));
        if (formName.isBlank()) formName = text(formObject);
        String liveShowdown = text(invokeOptional(battlePokemon, "showdownId"));
        if (liveShowdown.isBlank()) liveShowdown = text(invokeOptional(battlePokemon, "getShowdownId"));
        if (liveShowdown.isBlank()) liveShowdown = text(invokeOptional(speciesObject, "showdownId"));
        SpeciesData formSpecies = TropimonDex.findFormSpecies(
                text(invokeOptional(properties, "getSpecies")),
                formName,
                liveShowdown,
                new ArrayList<>(activeAspects)
        );
        if (formSpecies == null) {
            formSpecies = TropimonDex.findFormSpecies(
                    text(invokeOptional(speciesObject, "showdownId")),
                    formName,
                    liveShowdown,
                    new ArrayList<>(activeAspects)
            );
        }
        if (formSpecies != null) {
            return formSpecies;
        }
        SpeciesData species = TropimonDex.findSpeciesByQuery(text(invokeOptional(properties, "getSpecies")));
        if (species != null) {
            return species;
        }
        species = TropimonDex.findSpeciesByQuery(text(invokeOptional(speciesObject, "showdownId")));
        if (species != null) {
            return species;
        }
        species = TropimonDex.findSpeciesByDisplayName(text(invokeOptional(speciesObject, "getName")));
        if (species != null) {
            return species;
        }
        return TropimonDex.findSpeciesByDisplayName(displayText(invokeOptional(battlePokemon, "getDisplayName", false)));
    }

    static SpeciesData preferSpecificForm(SpeciesData partySpecies, SpeciesData battleSpecies) {
        if (partySpecies == null) {
            return battleSpecies;
        }
        if (battleSpecies == null || partySpecies.id().equals(battleSpecies.id())) {
            return partySpecies;
        }
        if (!TropimonDex.normalize(partySpecies.cobblemonSpeciesId())
                .equals(TropimonDex.normalize(battleSpecies.cobblemonSpeciesId()))) {
            return battleSpecies;
        }
        boolean partyIsForm = !TropimonDex.normalize(partySpecies.id())
                .equals(TropimonDex.normalize(partySpecies.cobblemonSpeciesId()));
        boolean battleIsForm = !TropimonDex.normalize(battleSpecies.id())
                .equals(TropimonDex.normalize(battleSpecies.cobblemonSpeciesId()));
        if (battleIsForm) {
            return battleSpecies;
        }
        return partyIsForm ? partySpecies : battleSpecies;
    }

    private static void applyBattleProperties(PokemonSet set, Object properties) {
        if (properties == null) {
            return;
        }
        Object item = invokeOptional(properties, "getHeldItem");
        if (item != null && !text(item).isBlank()) {
            String found = TropimonDex.findItemByQuery(text(item));
            set.item = found == null ? pretty(text(item)) : found;
            set.itemKnown = true;
        }
        Object ability = invokeOptional(properties, "getAbility");
        if (ability != null && !text(ability).isBlank()) {
            set.ability = canonicalAbilityName(text(ability));
            set.abilityKnown = true;
        }
        NatureData nature = TropimonDex.findNatureByQuery(text(invokeOptional(properties, "getNature")));
        if (nature != null) {
            set.nature = nature;
            set.natureKnown = true;
        }
        Object evs = invokeOptional(properties, "getEvs");
        Object ivs = invokeOptional(properties, "getIvs");
        copyStats(evs, set.evs);
        copyStats(ivs, set.ivs);
        if (evs != null || ivs != null) {
            set.statsKnown = true;
        }
        Object moves = invokeOptional(properties, "getMoves");
        if (moves instanceof Iterable<?> iterable) {
            set.moves.clear();
            for (Object rawMove : iterable) {
                MoveData move = TropimonDex.findMoveByQuery(text(rawMove));
                if (move != null) {
                    set.moves.add(move);
                }
            }
            while (set.moves.size() < 4) {
                set.moves.add(null);
            }
            set.movesKnown = set.moves.stream().anyMatch(move -> move != null);
        }
    }

    private static void applyBattleRuntime(PokemonSet set, Object battlePokemon) {
        Object uuid = invokeOptional(battlePokemon, "getUuid");
        if (uuid != null) {
            set.battleId = uuid.toString();
        }
        set.battleName = displayText(invokeOptional(battlePokemon, "getDisplayName", false));
        set.level = battlePokemonLevel(battlePokemon, set.level);
        Object hp = invokeOptional(battlePokemon, "getHpValue");
        if (hp instanceof Number number && number.floatValue() >= 0) {
            float hpValue = number.floatValue();
            Object flat = invokeOptional(battlePokemon, "isHpFlat");
            Object maxHp = invokeOptional(battlePokemon, "getMaxHp");
            if (!(maxHp instanceof Number)) {
                maxHp = invokeOptional(battlePokemon, "getMaxHealth");
            }
            if (maxHp instanceof Number maxNumber && maxNumber.floatValue() > 1.0F) {
                set.observedMaxHp = Math.max(1, Math.round(maxNumber.floatValue()));
            }
            if (Boolean.FALSE.equals(flat) && maxHp instanceof Number maxNumber && maxNumber.floatValue() > 1.0F) {
                set.currentHp = Math.max(0, Math.round(maxNumber.floatValue() * Math.min(1.0F, hpValue)));
            } else {
                set.currentHp = Math.max(0, Math.round(hpValue));
            }
        }
        Object status = invokeOptional(battlePokemon, "getStatus");
        set.status = statusCondition(text(invokeOptional(status, "getShowdownName")));
        if (set.status == StatusCondition.NONE) {
            set.status = statusCondition(text(invokeOptional(status, "getName")));
        }
        copyStats(invokeOptional(battlePokemon, "getStatChanges"), set.boosts);
    }

    static int battlePokemonLevel(Object battlePokemon, int fallback) {
        if (battlePokemon == null) {
            return clampLevel(fallback);
        }
        for (Object source : new Object[] {
                battlePokemon,
                invokeOptional(battlePokemon, "getProperties"),
                invokeOptional(battlePokemon, "getOriginalPokemon"),
                invokeOptional(battlePokemon, "getEffectedPokemon")}) {
            if (source == null) {
                continue;
            }
            Object level = invokeOptional(source, "getLevel");
            if (level instanceof Number number && number.intValue() > 0) {
                return clampLevel(number.intValue());
            }
        }
        for (String details : List.of(
                text(invokeOptional(battlePokemon, "getDetails")),
                displayText(invokeOptional(battlePokemon, "getDisplayName")))) {
            Matcher matcher = SHOWDOWN_LEVEL_PATTERN.matcher(details);
            if (matcher.find()) {
                try {
                    return clampLevel(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
            int parsed = parseLevel(details, -1);
            if (parsed > 0) {
                return parsed;
            }
        }
        return clampLevel(fallback);
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(100, level));
    }

    private static StatusCondition statusCondition(String raw) {
        return switch (TropimonDex.normalize(raw)) {
            case "brn", "burn", "burned" -> StatusCondition.BURN;
            case "psn", "tox", "poison", "poisoned", "toxic" -> StatusCondition.POISON;
            case "par", "paralysis", "paralyzed" -> StatusCondition.PARALYSIS;
            case "slp", "sleep", "asleep" -> StatusCondition.SLEEP;
            case "frz", "freeze", "frozen" -> StatusCondition.FREEZE;
            default -> StatusCondition.NONE;
        };
    }

    private static SpeciesData speciesFromLivePokemon(Object live) {
        Object speciesObject = invokeOptional(live, "getSpecies");
        Object formObject = invokeOptional(live, "getForm");
        SpeciesData formSpecies = TropimonDex.findFormSpecies(
                text(invokeOptional(speciesObject, "showdownId")),
                text(invokeOptional(formObject, "getName")),
                text(invokeOptional(formObject, "showdownId")),
                stringList(invokeOptional(live, "getAspects"))
        );
        if (formSpecies != null) {
            return formSpecies;
        }
        String showdown = text(invokeOptional(live, "showdownId"));
        SpeciesData species = TropimonDex.findSpeciesByQuery(showdown);
        if (species != null) {
            return species;
        }
        species = TropimonDex.findSpeciesByQuery(text(invokeOptional(speciesObject, "showdownId")));
        if (species != null) {
            return species;
        }
        species = TropimonDex.findSpeciesByDisplayName(text(invokeOptional(speciesObject, "getName")));
        if (species != null) {
            return species;
        }
        species = TropimonDex.findSpeciesByQuery(text(invokeOptional(formObject, "showdownId")));
        if (species != null) {
            return species;
        }
        return TropimonDex.findSpeciesByDisplayName(displayText(invokeOptional(live, "getDisplayName", false)));
    }

    private static ArrayList<Object> partyCandidates(Object storage) {
        ArrayList<Object> parties = new ArrayList<>();
        Object selected = invokeOptional(storage, "getParty");
        if (selected != null) {
            parties.add(selected);
        } else {
            Object partyStores = invokeOptional(storage, "getPartyStores");
            if (partyStores instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value != null && !parties.contains(value)) {
                        parties.add(value);
                    }
                }
            }
        }
        debugParty("party candidates=" + parties.size() + " selected=" + className(selected));
        return parties;
    }

    private static void addPartyPokemon(ArrayList<PokemonSet> output, Set<UUID> seen, Object party) {
        Object slots = invokeOptional(party, "getSlots");
        if (slots instanceof Iterable<?> iterable) {
            int slotCount = 0;
            for (Object pokemon : iterable) {
                slotCount++;
                if (!markSeen(seen, pokemon)) {
                    continue;
                }
                PokemonSet converted = convertPartyPokemon(pokemon);
                if (converted != null) {
                    output.add(converted);
                }
            }
            debugParty("party slots=" + slotCount + " convertedTotal=" + output.size() + " party=" + className(party));
            return;
        }
        if (party instanceof Iterable<?> iterable) {
            int slotCount = 0;
            for (Object pokemon : iterable) {
                slotCount++;
                if (!markSeen(seen, pokemon)) {
                    continue;
                }
                PokemonSet converted = convertPartyPokemon(pokemon);
                if (converted != null) {
                    output.add(converted);
                }
            }
            debugParty("party iterable slots=" + slotCount + " convertedTotal=" + output.size() + " party=" + className(party));
            return;
        }
        debugParty("party has no iterable slots: " + className(party));
    }

    private static boolean markSeen(Set<UUID> seen, Object pokemon) {
        Object uuid = invokeOptional(pokemon, "getUuid");
        return !(uuid instanceof UUID pokemonUuid) || seen.add(pokemonUuid);
    }

    private static String itemName(Object value) {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (id != null) {
                String found = TropimonDex.findItemByQuery(id.getPath());
                return found == null ? pretty(id.getPath()) : found;
            }
            String display = stack.getName().getString();
            String found = TropimonDex.findItemByQuery(display);
            return found == null ? display : found;
        }
        return "";
    }

    private static String abilityName(Object ability) {
        String display = text(invokeOptional(ability, "getDisplayName"));
        if (display.isBlank()) {
            display = text(invokeOptional(ability, "getName"));
        }
        return canonicalAbilityName(display);
    }

    private static String canonicalAbilityName(String raw) {
        String pretty = pretty(raw);
        String normalized = TropimonDex.normalize(pretty);
        if (normalized.isBlank()) {
            return "";
        }
        for (String ability : TropimonDex.abilityList()) {
            if (TropimonDex.normalize(ability).equals(normalized)) {
                return ability;
            }
        }
        return pretty;
    }

    private static NatureData natureFromLive(Object live) {
        Object nature = invokeOptional(live, "getEffectiveNature");
        for (String candidate : List.of(
                text(invokeOptional(nature, "getName")),
                text(invokeOptional(nature, "getDisplayName"))
        )) {
            NatureData found = TropimonDex.findNatureByQuery(pretty(candidate));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void copyStats(Object statContainer, Map<Stat, Integer> target) {
        if (statContainer == null) {
            return;
        }
        if (statContainer instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Stat stat = statFromCobblemon(entry.getKey());
                if (stat != null && entry.getValue() instanceof Number number) {
                    target.put(stat, number.intValue());
                }
            }
            return;
        }
        if (statContainer instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                Object key;
                Object value;
                if (entry instanceof Map.Entry<?, ?> mapEntry) {
                    key = mapEntry.getKey();
                    value = mapEntry.getValue();
                } else {
                    key = invokeOptional(entry, "getKey");
                    value = invokeOptional(entry, "getValue");
                }
                Stat stat = statFromCobblemon(key);
                if (stat != null && value instanceof Number number) {
                    target.put(stat, number.intValue());
                }
            }
            return;
        }
        for (Stat stat : Stat.values()) {
            Object cobblemonStat = cobblemonStat(stat);
            Object value = invokeOptional(statContainer, "getOrDefault", cobblemonStat);
            if (!(value instanceof Number)) {
                value = invokeOptional(statContainer, "get", cobblemonStat);
            }
            if (value instanceof Number number) {
                target.put(stat, number.intValue());
            }
        }
    }

    private static synchronized Object cobblemonStat(Stat stat) {
        if (!cobblemonStatsLoaded) {
            try {
                Class<?> statsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stats");
                for (Stat candidate : Stat.values()) {
                    String field = switch (candidate) {
                        case HP -> "HP";
                        case ATK -> "ATTACK";
                        case DEF -> "DEFENCE";
                        case SPA -> "SPECIAL_ATTACK";
                        case SPD -> "SPECIAL_DEFENCE";
                        case SPE -> "SPEED";
                    };
                    COBBLEMON_STATS.put(candidate, statsClass.getField(field).get(null));
                }
            } catch (Throwable ignored) {
                COBBLEMON_STATS.clear();
            }
            cobblemonStatsLoaded = true;
        }
        return COBBLEMON_STATS.get(stat);
    }

    private static ArrayList<MoveData> movesFromLive(Object live) {
        ArrayList<MoveData> moves = new ArrayList<>();
        Object moveSet = invokeOptional(live, "getMoveSet");
        Object liveMoves = invokeOptional(moveSet, "getMoves");
        if (liveMoves instanceof Iterable<?> iterable) {
            for (Object liveMove : iterable) {
                String name = text(invokeOptional(liveMove, "getName"));
                MoveData move = TropimonDex.findMoveByQuery(name);
                if (move != null) {
                    moves.add(move);
                }
            }
        }
        return moves;
    }

    private static Stat statFromCobblemon(Object stat) {
        String id = text(invokeOptional(stat, "getShowdownId"));
        if (id.isBlank()) {
            id = text(invokeOptional(stat, "getName"));
        }
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "hp" -> Stat.HP;
            case "atk", "attack" -> Stat.ATK;
            case "def", "defence", "defense" -> Stat.DEF;
            case "spa", "specialattack" -> Stat.SPA;
            case "spd", "specialdefence", "specialdefense" -> Stat.SPD;
            case "spe", "speed" -> Stat.SPE;
            default -> null;
        };
    }

    static Object invokeOptional(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            ArrayList<Class<?>> argumentTypes = new ArrayList<>(args.length);
            for (Object argument : args) {
                argumentTypes.add(argument == null ? NullArgument.class : argument.getClass());
            }
            MethodKey key = new MethodKey(target.getClass(), methodName, List.copyOf(argumentTypes));
            Optional<Method> cached = METHOD_CACHE.computeIfAbsent(key, CobblemonBattleDataProvider::findMethod);
            return cached.isPresent() ? cached.get().invoke(target, args) : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object readFieldOptional(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            FieldKey key = new FieldKey(target.getClass(), fieldName);
            Optional<Field> cached = FIELD_CACHE.computeIfAbsent(key, CobblemonBattleDataProvider::findField);
            return cached.isPresent() ? cached.get().get(target) : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Optional<Field> findField(FieldKey key) {
        Class<?> owner = key.owner();
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(key.name());
                field.setAccessible(true);
                return Optional.of(field);
            } catch (ReflectiveOperationException ignored) {
                owner = owner.getSuperclass();
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> findMethod(MethodKey key) {
        Method best = null;
        int bestScore = -1;
        for (Method method : key.owner().getMethods()) {
            if (!method.getName().equals(key.name()) || method.getParameterCount() != key.argumentTypes().size()) {
                continue;
            }
            int score = compatibilityScore(method.getParameterTypes(), key.argumentTypes());
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        try {
            best.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return Optional.of(best);
    }

    private static int compatibilityScore(Class<?>[] parameters, List<Class<?>> arguments) {
        int score = 0;
        for (int index = 0; index < parameters.length; index++) {
            Class<?> parameter = parameters[index];
            Class<?> argument = arguments.get(index);
            if (argument == NullArgument.class) {
                if (parameter.isPrimitive()) return -1;
                continue;
            }
            Class<?> boxedParameter = boxed(parameter);
            if (!boxedParameter.isAssignableFrom(argument)) return -1;
            score += boxedParameter.equals(argument) ? 2 : 1;
        }
        return score;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static Object cobblemonClient() throws ReflectiveOperationException {
        Object cached = cobblemonClientInstance;
        if (cached != null) {
            return cached;
        }
        synchronized (CobblemonBattleDataProvider.class) {
            if (cobblemonClientInstance == null) {
                Class<?> clientClass = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
                cobblemonClientInstance = clientClass.getField("INSTANCE").get(null);
            }
            return cobblemonClientInstance;
        }
    }

    static void invalidateRuntimeCaches() {
        partyCacheOwner = null;
        partyCache = List.of();
        nextPartyRefresh = 0L;
        battleCacheWorld = null;
        battleCacheTick = Long.MIN_VALUE;
        battleCache = null;
        clearOpponentRoster(null);
        previewPlayerRoster.clear();
        previewOpponentRoster.clear();
        previewOpponentFullRoster.clear();
        exactRandomOpponentKeys.clear();
        capturedPreviewScreen = null;
        capturedPreviewInformation = null;
        loggedPreviewScreen = null;
        loggedRandomPreviewCandidate = null;
        loggedLocalHydration = "";
        randomBattleIdentity = null;
        randomBattleDetected = null;
        randomBattleQueueExpiresAt = 0L;
        previewOpponentRosterExpiresAt = 0L;
        previewOpponentFullRosterExpiresAt = 0L;
        exactRandomPreviewBattle = null;
        CobblemonBattleConditionTracker.resetForBattle(null);
    }

    private static String displayText(Object value) {
        Object string = invokeOptional(value, "getString");
        return string == null ? "" : string.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<String> stringList(Object value) {
        ArrayList<String> output = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = text(item);
                if (!text.isBlank()) {
                    output.add(text);
                }
            }
        }
        return output;
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static void debugParty(String message) {
        long now = System.currentTimeMillis();
        if (now < nextPartyDebug) {
            return;
        }
        nextPartyDebug = now + 3_000L;
        TropimonDamageCalcClient.debug("party: " + message);
    }

    private static String pretty(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim();
        for (String prefix : List.of(
                "cobblemon.ability.", "ability.",
                "cobblemon.move.", "move.",
                "item.cobblemon.", "item.mega_showdown.",
                "tooltip.cobblemon.", "tooltip.mega_showdown."
        )) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length());
                break;
            }
        }
        if (cleaned.endsWith(".desc")) {
            cleaned = cleaned.substring(0, cleaned.length() - ".desc".length());
        }
        if (cleaned.endsWith(".tooltip")) {
            cleaned = cleaned.substring(0, cleaned.length() - ".tooltip".length());
        }
        if (cleaned.contains(":")) {
            cleaned = cleaned.substring(cleaned.indexOf(':') + 1);
        }
        cleaned = cleaned.replace('-', ' ').replace('_', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) builder.append(part.substring(1));
        }
        return builder.toString();
    }

    private record MethodKey(Class<?> owner, String name, List<Class<?>> argumentTypes) {
    }

    private static final class NullArgument {
        private NullArgument() {
        }
    }

    private record FieldKey(Class<?> owner, String name) {
    }

}
