package fr.tropimon.damagecalc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

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
    private static final LinkedHashMap<String, PokemonSet> previewOpponentRoster = new LinkedHashMap<>();
    private static Object capturedPreviewScreen;
    private static Object capturedPreviewInformation;
    private static Object loggedPreviewScreen;
    private static long previewOpponentRosterExpiresAt;
    private static final long TEAM_PREVIEW_TTL_MS = 600_000L;
    private static volatile Object hunterBoardPvpDetector;
    private static volatile boolean hunterBoardLookupDone;
    private static Object loggedOpponentRosterBattle;
    private static int loggedOpponentRosterSize = -1;
    private static String loggedLocalHydration = "";

    private CobblemonBattleDataProvider() {
    }

    static PokemonSet targetPokemon(MinecraftClient client) {
        Entity entity = targetCobblemonEntity(client);
        if (entity == null) {
            return null;
        }
        String display = displayName(entity);
        SpeciesData species = TropimonDex.findSpeciesByDisplayName(display);
        if (species == null) {
            return null;
        }
        PokemonSet pokemon = new PokemonSet(species);
        pokemon.level = parseLevel(display, 100);
        pokemon.currentHp = -1;
        return pokemon;
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
        if (owner.equals(partyCacheOwner) && now < nextPartyRefresh) {
            return copyParty(partyCache);
        }
        ArrayList<PokemonSet> output = new ArrayList<>();
        HashSet<UUID> seen = new HashSet<>();
        try {
            Object storage = invokeOptional(cobblemonClient(), "getStorage");
            for (Object party : partyCandidates(storage)) {
                addPartyPokemon(output, seen, party);
            }
            debugParty("party loaded count=" + output.size() + " storage=" + className(storage));
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
            return copyParty(new ArrayList<>(opponentRoster.values()));
        } catch (Throwable throwable) {
            debugParty("opponent party load failed: " + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            return copyParty(new ArrayList<>(opponentRoster.values()));
        }
    }

    static void captureVisibleTeamPreview(Object screen) {
        if (captureHunterBoardTeamPreview(screen)) {
            return;
        }
        Object information = teamPreviewInformation(screen);
        if (information == null) {
            return;
        }
        if (screen == capturedPreviewScreen && information == capturedPreviewInformation
                && !previewOpponentRoster.isEmpty()) {
            previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
            return;
        }
        Object party = invokeOptional(information, "getOpponentParty");
        Object decoded = invokeOptional(party, "getDecodedPokemons");
        if (!(decoded instanceof Iterable<?> iterable)) {
            return;
        }
        LinkedHashMap<String, PokemonSet> captured = new LinkedHashMap<>();
        for (Object live : iterable) {
            PokemonSet converted = convertPartyPokemon(live);
            if (converted == null) {
                continue;
            }
            PokemonSet preview = hideOpponentPrivateData(converted);
            captured.put(opponentKey(preview), preview);
        }
        if (captured.isEmpty()) {
            return;
        }
        capturedPreviewScreen = screen;
        capturedPreviewInformation = information;
        previewOpponentRoster.clear();
        previewOpponentRoster.putAll(captured);
        previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] team preview captured source={} opponents={}",
                className(screen), captured.size());
    }

    private static boolean captureHunterBoardTeamPreview(Object screen) {
        if (!looksLikeTeamPreviewScreen(screen)) {
            return false;
        }
        Object detector = hunterBoardPvpDetector();
        Object team = invokeOptional(detector, "getOpponentTeam");
        if (!(team instanceof Iterable<?> iterable)) {
            return false;
        }
        LinkedHashMap<String, PokemonSet> captured = new LinkedHashMap<>();
        for (Object entry : iterable) {
            String speciesId = text(invokeOptional(entry, "getSpeciesId"));
            List<String> aspects = stringList(invokeOptional(entry, "getAspects"));
            SpeciesData species = TropimonDex.findFormSpecies(speciesId, "", "", aspects);
            if (species == null) {
                species = TropimonDex.findSpeciesByQuery(speciesId);
            }
            if (species == null) {
                continue;
            }
            PokemonSet preview = hideOpponentPrivateData(new PokemonSet(species));
            captured.put(opponentKey(preview), preview);
        }
        if (captured.isEmpty()) {
            return false;
        }
        if (screen == capturedPreviewScreen && detector == capturedPreviewInformation
                && samePreviewRoster(captured)) {
            previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
            return true;
        }
        capturedPreviewScreen = screen;
        capturedPreviewInformation = detector;
        previewOpponentRoster.clear();
        previewOpponentRoster.putAll(captured);
        previewOpponentRosterExpiresAt = System.currentTimeMillis() + TEAM_PREVIEW_TTL_MS;
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] team preview captured source=hunterboard opponents={}",
                captured.size());
        return true;
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

    private static Object hunterBoardPvpDetector() {
        if (hunterBoardLookupDone) {
            return hunterBoardPvpDetector;
        }
        synchronized (CobblemonBattleDataProvider.class) {
            if (!hunterBoardLookupDone) {
                try {
                    Class<?> detectorClass = Class.forName("com.hunterboard.PvpDetector");
                    hunterBoardPvpDetector = detectorClass.getField("INSTANCE").get(null);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    hunterBoardPvpDetector = null;
                }
                hunterBoardLookupDone = true;
            }
        }
        return hunterBoardPvpDetector;
    }

    private static boolean samePreviewRoster(Map<String, PokemonSet> captured) {
        return previewOpponentRoster.keySet().equals(captured.keySet());
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
        String key = opponentKey(pokemon);
        PokemonSet existing = opponentRoster.get(key);
        if (existing == null && !pokemon.battleId.isBlank()) {
            String anonymousKey = matchingAnonymousOpponentKey(opponentRoster, pokemon);
            if (anonymousKey != null) {
                existing = opponentRoster.get(anonymousKey);
                opponentRoster.remove(anonymousKey);
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
        mergeKnownMoves(merged, existing);
        opponentRoster.put(key, merged);
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
            PokemonSet player = battlePokemonSet(localActive, true, client);
            PokemonSet opponent = battlePokemonSet(opponentActive, false, client);
            PokemonSet playerPartner = localActives.size() > 1
                    ? battlePokemonSet(localActives.get(1), true, client) : null;
            PokemonSet opponentPartner = opponentActives.size() > 1
                    ? battlePokemonSet(opponentActives.get(1), false, client) : null;
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

    private static Entity targetCobblemonEntity(MinecraftClient client) {
        if (client == null || client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.ENTITY) {
            return null;
        }
        Entity entity = ((EntityHitResult) client.crosshairTarget).getEntity();
        String namespace = Registries.ENTITY_TYPE.getId(entity.getType()).getNamespace();
        return namespace.equals("cobblemon") ? entity : null;
    }

    private static String displayName(Entity entity) {
        Text text = entity.getDisplayName() == null ? entity.getName() : entity.getDisplayName();
        return text == null ? "" : text.getString();
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

    private static Object firstActivePokemon(Object actor) {
        ArrayList<Object> active = activePokemon(actor);
        return active.isEmpty() ? null : active.getFirst();
    }

    private static Object firstOpponentActivePokemon(Object battle, Object localActor) {
        ArrayList<Object> active = opponentActivePokemon(battle, localActor);
        return active.isEmpty() ? null : active.getFirst();
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
            PokemonSet partySet = localPartyPokemon(client, uuid, activeSpecies);
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
        Object level = invokeOptional(battlePokemon, "getLevel");
        if (level instanceof Number number) {
            set.level = Math.max(1, Math.min(100, number.intValue()));
        }
        Object properties = invokeOptional(battlePokemon, "getProperties");
        applyBattleProperties(set, properties);
        applyBattleRuntime(set, battlePokemon);
        CobblemonBattleConditionTracker.applyHistory(set, localPlayer);
        return set;
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
        Object level = invokeOptional(battlePokemon, "getLevel");
        if (level instanceof Number number) {
            set.level = Math.max(1, Math.min(100, number.intValue()));
        }
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
        previewOpponentRoster.clear();
        capturedPreviewScreen = null;
        capturedPreviewInformation = null;
        loggedPreviewScreen = null;
        loggedLocalHydration = "";
        previewOpponentRosterExpiresAt = 0L;
        hunterBoardPvpDetector = null;
        hunterBoardLookupDone = false;
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
