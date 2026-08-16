package fr.tropimon.damagecalc;

import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tracks battle conditions from Cobblemon's language-independent translation keys. */
final class CobblemonBattleConditionTracker {
    private static final Pattern INTEGER = Pattern.compile("(\\d+)");
    private static Object battleIdentity;
    private static Weather weather = Weather.NONE;
    private static Terrain terrain = Terrain.NONE;
    private static final SideConditions ally = new SideConditions();
    private static final SideConditions opponent = new SideConditions();
    private static boolean weatherKnown;
    private static boolean terrainKnown;
    private static boolean sidesKnown;
    private static boolean roomsKnown;
    private static boolean trickRoom;
    private static boolean gravity;
    private static final Map<String, PokemonHistory> histories = new HashMap<>();
    private static final SideHistory allyHistory = new SideHistory();
    private static final SideHistory opponentHistory = new SideHistory();
    private static final Set<String> faintedPokemon = new HashSet<>();
    private static BattleSide lastDamagedSide;
    private static String lastDamagedHistoryKey = "";
    private static int pendingDamageEvents;
    private static BattleSide lastMoveSide;
    private static boolean lastMoveFromPartner;
    private static BattleSide mostRecentDamagedSide;
    private static int currentTurn;

    private CobblemonBattleConditionTracker() {
    }

    static synchronized void resetForBattle(Object battle) {
        if (battleIdentity == battle) {
            return;
        }
        battleIdentity = battle;
        weather = Weather.NONE;
        terrain = Terrain.NONE;
        clear(ally);
        clear(opponent);
        weatherKnown = battle != null;
        terrainKnown = battle != null;
        sidesKnown = battle != null;
        roomsKnown = battle != null;
        trickRoom = false;
        gravity = false;
        histories.clear();
        allyHistory.clear();
        opponentHistory.clear();
        faintedPokemon.clear();
        lastDamagedSide = null;
        lastDamagedHistoryKey = "";
        pendingDamageEvents = 0;
        lastMoveSide = null;
        lastMoveFromPartner = false;
        mostRecentDamagedSide = null;
        currentTurn = 0;
        FieldState field = DamageCalcState.shared().field;
        field.helpingHand = false;
        field.friendGuard = false;
        field.attackerSide.helpingHand = false;
        field.attackerSide.friendGuard = false;
        field.defenderSide.helpingHand = false;
        field.defenderSide.friendGuard = false;
        applyTo(field);
    }

    static synchronized void accept(Iterable<Text> messages) {
        if (messages == null) {
            return;
        }
        boolean changed = false;
        for (Text message : messages) {
            changed |= acceptText(message);
        }
        if (changed) {
            applyTo(DamageCalcState.shared().field);
            refreshOpenScreen();
        }
    }

    static synchronized boolean accept(Text root) {
        boolean changed = acceptText(root);
        if (changed) {
            applyTo(DamageCalcState.shared().field);
            refreshOpenScreen();
        }
        return changed;
    }

    private static boolean acceptText(Text root) {
        if (root == null) {
            return false;
        }
        boolean changed = false;
        Deque<Text> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Text text = pending.removeFirst();
            if (text.getContent() instanceof TranslatableTextContent translatable) {
                changed |= acceptBattleMessage(translatable.getKey(), translatable.getArgs());
                changed |= acceptKey(translatable.getKey());
                for (Object argument : translatable.getArgs()) {
                    if (argument instanceof Text argumentText) {
                        pending.addLast(argumentText);
                    }
                }
            }
            pending.addAll(text.getSiblings());
        }
        return changed;
    }

    static synchronized boolean acceptTranslationKey(String key) {
        if (key == null || !key.startsWith("cobblemon.battle.")) {
            return false;
        }
        if (key.contains(".weather.")) {
            return acceptWeather(key);
        }
        if (key.contains(".fieldstart.") || key.contains(".fieldend.")) {
            return acceptField(key);
        }
        if (key.contains(".sidestart.") || key.contains(".sideend.")) {
            return acceptSide(key);
        }
        return false;
    }

    private static boolean acceptKey(String key) {
        return acceptTranslationKey(key);
    }

    private static boolean acceptBattleMessage(String key, Object[] arguments) {
        if (key == null || !key.startsWith("cobblemon.battle.")) {
            return false;
        }
        if (isStatChangeMessage(key)) {
            boolean changed = recordStatChange(key, arguments);
            if (changed) refreshCurrentHistory();
            return changed;
        }
        if (key.contains("protosynthesis") || key.contains("quarkdrive")) {
            boolean changed = recordParadoxBoost(key, arguments);
            if (changed) refreshCurrentHistory();
            return changed;
        }
        boolean changed = switch (key) {
            case "cobblemon.battle.turn" -> recordTurn(arguments);
            case "cobblemon.battle.used_move", "cobblemon.battle.used_move_on" -> recordMove(arguments);
            case "cobblemon.battle.damage_dealt" -> recordDamage(arguments);
            case "cobblemon.battle.hit_count" -> recordHitCount(arguments, false);
            case "cobblemon.battle.hit_count_singular" -> recordHitCount(arguments, true);
            case "cobblemon.battle.fainted" -> recordFaint(arguments);
            case "cobblemon.battle.switch.self" -> recordSwitch(BattleSide.ALLY);
            case "cobblemon.battle.switch.other", "cobblemon.battle.switch.other.nickname" -> recordSwitch(BattleSide.OPPONENT);
            case "cobblemon.battle.withdraw.self" -> recordWithdraw(BattleSide.ALLY);
            case "cobblemon.battle.withdraw.other" -> recordWithdraw(BattleSide.OPPONENT);
            case "cobblemon.battle.missed", "cobblemon.battle.immune" -> recordMoveFailure(lastMoveSide);
            case "cobblemon.battle.start.flashfire" -> recordFlashFire(arguments);
            case "cobblemon.battle.singleturn.helpinghand" -> recordHelpingHand(arguments);
            case "cobblemon.battle.formechange.ash", "cobblemon.battle.formechange.default.permanent",
                 "cobblemon.battle.formechange.default.temporary",
                 "cobblemon.battle.formechange.default.temporary.ended",
                 "cobblemon.battle.formechange.mega", "cobblemon.battle.formechange.meteor",
                 "cobblemon.battle.formechange.minior", "cobblemon.battle.formechange.school",
                 "cobblemon.battle.formechange.wishiwashi", "cobblemon.battle.mega",
                 "cobblemon.battle.transform" -> true;
            default -> recordRevealedBuild(key, arguments);
        };
        if (changed) {
            refreshCurrentHistory();
        }
        return changed;
    }

    private static boolean isStatChangeMessage(String key) {
        return key.startsWith("cobblemon.battle.boost.")
                || key.startsWith("cobblemon.battle.unboost.")
                || key.equals("cobblemon.battle.clearallboost")
                || key.startsWith("cobblemon.battle.clearboost")
                || key.startsWith("cobblemon.battle.clearallnegativeboost")
                || key.startsWith("cobblemon.battle.setboost.")
                || key.equals("cobblemon.battle.invertboost");
    }

    private static boolean recordStatChange(String key, Object[] arguments) {
        if (key.equals("cobblemon.battle.clearallboost")) {
            boolean allyChanged = clearBoosts(currentPokemon(BattleSide.ALLY), BattleSide.ALLY, false);
            boolean opponentChanged = clearBoosts(currentPokemon(BattleSide.OPPONENT), BattleSide.OPPONENT, false);
            return allyChanged || opponentChanged;
        }
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null || matchesPartner(side, actor)) return false;
        if (key.startsWith("cobblemon.battle.clearboost")) {
            return clearBoosts(pokemon, side, false);
        }
        if (key.startsWith("cobblemon.battle.clearallnegativeboost")) {
            return clearBoosts(pokemon, side, true);
        }
        if (key.equals("cobblemon.battle.invertboost")) {
            PokemonHistory history = boostHistory(pokemon, side);
            boolean changed = false;
            for (Stat stat : combatStats()) {
                int previous = history.boosts.getOrDefault(stat, 0);
                int next = -previous;
                history.boosts.put(stat, next);
                pokemon.boosts.put(stat, next);
                changed |= previous != next;
            }
            return changed;
        }
        if (key.startsWith("cobblemon.battle.setboost.")) {
            return key.endsWith(".bellydrum") || key.endsWith(".angerpoint")
                    ? setBoost(pokemon, side, Stat.ATK, 6, key) : false;
        }
        if (key.contains(".cap.")) return false;
        Stat stat = statArgument(argument(arguments, 1));
        if (stat == null || stat == Stat.HP) return false;
        int amount = key.contains(".severe") ? 3 : key.contains(".sharp") ? 2 : 1;
        if (key.startsWith("cobblemon.battle.unboost.")) amount = -amount;
        PokemonHistory history = boostHistory(pokemon, side);
        int previous = history.boosts.getOrDefault(stat, 0);
        int next = Math.max(-6, Math.min(6, previous + amount));
        if (previous == next) return false;
        history.boosts.put(stat, next);
        pokemon.boosts.put(stat, next);
        TropimonDamageCalcClient.LOGGER.info("[CalcDBG] stat stage pokemon={} stat={} {}->{} source={}",
                pokemon.species.name(), stat, previous, next, key);
        return true;
    }

    private static boolean setBoost(PokemonSet pokemon, BattleSide side, Stat stat, int stage, String source) {
        PokemonHistory history = boostHistory(pokemon, side);
        int previous = history.boosts.getOrDefault(stat, 0);
        int next = Math.max(-6, Math.min(6, stage));
        history.boosts.put(stat, next);
        pokemon.boosts.put(stat, next);
        if (previous != next) {
            TropimonDamageCalcClient.LOGGER.info("[CalcDBG] stat stage pokemon={} stat={} {}->{} source={}",
                    pokemon.species.name(), stat, previous, next, source);
        }
        return previous != next;
    }

    private static boolean clearBoosts(PokemonSet pokemon, BattleSide side, boolean negativeOnly) {
        if (pokemon == null || side == null) return false;
        PokemonHistory history = boostHistory(pokemon, side);
        boolean changed = false;
        for (Stat stat : combatStats()) {
            int previous = history.boosts.getOrDefault(stat, 0);
            if (!negativeOnly || previous < 0) {
                history.boosts.put(stat, 0);
                pokemon.boosts.put(stat, 0);
                changed |= previous != 0;
            }
        }
        return changed;
    }

    private static PokemonHistory boostHistory(PokemonSet pokemon, BattleSide side) {
        PokemonHistory history = history(pokemon, side);
        if (!history.boostsKnown) {
            for (Stat stat : combatStats()) {
                history.boosts.put(stat, pokemon.boosts.getOrDefault(stat, 0));
            }
            history.boostsKnown = true;
        }
        return history;
    }

    private static Set<Stat> combatStats() {
        return Set.of(Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE);
    }

    private static Stat statArgument(Object argument) {
        if (argument instanceof Text text) {
            Deque<Text> pending = new ArrayDeque<>();
            pending.add(text);
            while (!pending.isEmpty()) {
                Text current = pending.removeFirst();
                if (current.getContent() instanceof TranslatableTextContent translatable) {
                    String key = translatable.getKey();
                    if (key.startsWith("cobblemon.stat.") && key.endsWith(".name")) {
                        Stat stat = statIdentifier(key.substring("cobblemon.stat.".length(), key.length() - ".name".length()));
                        if (stat != null) return stat;
                    }
                    for (Object nested : translatable.getArgs()) {
                        if (nested instanceof Text nestedText) pending.addLast(nestedText);
                    }
                }
                pending.addAll(current.getSiblings());
            }
            return statIdentifier(text.getString());
        }
        return statIdentifier(String.valueOf(argument));
    }

    private static Stat statIdentifier(String raw) {
        return switch (TropimonDex.normalize(raw)) {
            case "attack", "atk", "attaque" -> Stat.ATK;
            case "defence", "defense", "def" -> Stat.DEF;
            case "specialattack", "spatk", "spa", "attaquespeciale", "atqspe" -> Stat.SPA;
            case "specialdefence", "specialdefense", "spdef", "spd", "defensespeciale", "defspe" -> Stat.SPD;
            case "speed", "spe", "vitesse" -> Stat.SPE;
            default -> null;
        };
    }

    private static boolean recordTurn(Object[] arguments) {
        int nextTurn = Math.max(0, integerArgument(argument(arguments, 0), currentTurn));
        if (nextTurn == currentTurn) {
            return false;
        }
        currentTurn = nextTurn;
        clearPendingDamage();
        lastMoveSide = null;
        lastMoveFromPartner = false;
        mostRecentDamagedSide = null;
        ally.helpingHand = false;
        opponent.helpingHand = false;
        ally.wideGuard = false;
        opponent.wideGuard = false;
        return true;
    }

    private static boolean recordMove(Object[] arguments) {
        clearPendingDamage();
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        String moveId = moveId(argument(arguments, 1));
        boolean partnerActor = side != null && matchesPartner(side, actor);
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null || moveId.isBlank()) {
            TropimonDamageCalcClient.LOGGER.warn(
                    "[CalcDBG] move reveal ignored actor={} move={} side={} activeOpponent={}",
                    argumentText(argument(arguments, 0)), argumentText(argument(arguments, 1)), side,
                    DamageCalcState.shared().defender == null ? "-" : DamageCalcState.shared().defender.species.name());
            return false;
        }
        lastMoveSide = side;
        lastMoveFromPartner = partnerActor;
        if (moveId.equals("wideguard")) {
            (side == BattleSide.ALLY ? ally : opponent).wideGuard = true;
        }
        if (partnerActor) {
            return true;
        }
        PokemonHistory history = history(pokemon, side);
        history.lastMoveFailed = false;
        history.consecutiveMoveUses = moveId.equals(history.lastMoveId) ? history.consecutiveMoveUses + 1 : 1;
        history.lastMoveId = moveId;
        if (moveId.equals("defensecurl")) {
            history.defenseCurlUsed = true;
        }
        SideHistory sideHistory = sideHistory(side);
        if (moveId.equals("echoedvoice")) {
            if (currentTurn <= 0) {
                sideHistory.echoedVoiceChain = Math.min(5, sideHistory.echoedVoiceChain + 1);
            } else if (sideHistory.lastEchoedVoiceTurn != currentTurn) {
                sideHistory.echoedVoiceChain = sideHistory.lastEchoedVoiceTurn == currentTurn - 1
                        ? Math.min(5, sideHistory.echoedVoiceChain + 1)
                        : 1;
                sideHistory.lastEchoedVoiceTurn = currentTurn;
            }
        }
        MoveData revealedMove = TropimonDex.findMoveByQuery(moveId);
        if (revealedMove != null) {
            boolean newlyRevealed = revealMove(pokemon, revealedMove);
            rememberOpponentBuild(side, pokemon);
            if (side == BattleSide.OPPONENT && newlyRevealed) {
                TropimonDamageCalcClient.LOGGER.info("[CalcDBG] opponent move revealed pokemon={} move={}",
                        pokemon.species.name(), revealedMove.name());
            }
        }
        return true;
    }

    private static String argumentText(Object argument) {
        return argument instanceof Text text ? text.getString() : String.valueOf(argument);
    }

    private static boolean recordRevealedBuild(String key, Object[] arguments) {
        if (key.startsWith("cobblemon.battle.ability.")) {
            return recordAbility(key, arguments);
        }
        if (key.startsWith("cobblemon.battle.item.")
                || key.startsWith("cobblemon.battle.enditem.")
                || key.equals("cobblemon.battle.damage.item")
                || key.equals("cobblemon.battle.heal.item")) {
            return recordItem(key, arguments);
        }
        return false;
    }

    private static boolean recordAbility(String key, Object[] arguments) {
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) {
            return false;
        }
        String ability = registryValue(arguments, "cobblemon.ability.", true);
        if (ability.isBlank()) {
            String suffix = key.substring("cobblemon.battle.ability.".length());
            ability = TropimonDex.findAbilityByQuery(suffix);
            if (ability == null && !Set.of("generic", "replace", "trace", "receiver", "magicbounce").contains(suffix)) {
                ability = prettyIdentifier(suffix);
            }
        }
        if (ability == null || ability.isBlank()) {
            return false;
        }
        if (matchesPartner(side, actor)) {
            DamageCalcState state = DamageCalcState.shared();
            SideConditions conditions = side == BattleSide.ALLY
                    ? state.field.attackerSide : state.field.defenderSide;
            boolean changed = !TropimonDex.normalize(conditions.partnerAbility)
                    .equals(TropimonDex.normalize(ability));
            conditions.partnerAbility = ability;
            if (side == BattleSide.ALLY) state.attackerPartnerAbilitySearch = ability;
            else state.defenderPartnerAbilitySearch = ability;
            return changed;
        }
        boolean changed = !pokemon.abilityKnown || !TropimonDex.normalize(pokemon.ability).equals(TropimonDex.normalize(ability));
        pokemon.ability = ability;
        pokemon.abilityKnown = true;
        rememberOpponentBuild(side, pokemon);
        return changed;
    }

    private static boolean recordItem(String key, Object[] arguments) {
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) {
            return false;
        }
        if (matchesPartner(side, actor)) {
            return false;
        }
        String item = registryValue(arguments, "item.", false);
        if (item.isBlank()) {
            item = TropimonDex.findItemByQuery(key.substring(key.lastIndexOf('.') + 1));
        }
        if (item == null || item.isBlank()) {
            return false;
        }
        String effectiveItem = key.startsWith("cobblemon.battle.enditem.") ? "None" : item;
        boolean changed = !pokemon.itemKnown
                || !TropimonDex.normalize(pokemon.item).equals(TropimonDex.normalize(effectiveItem));
        pokemon.item = effectiveItem;
        pokemon.itemKnown = true;
        rememberOpponentBuild(side, pokemon);
        return changed;
    }

    private static String registryValue(Object[] arguments, String translationPrefix, boolean ability) {
        if (arguments == null) {
            return "";
        }
        for (int index = 1; index < arguments.length; index++) {
            Object argument = arguments[index];
            String id = translatedId(argument, translationPrefix);
            String found = ability ? TropimonDex.findAbilityByQuery(id) : TropimonDex.findItemByQuery(id);
            if (found != null) {
                return found;
            }
            if (!id.isBlank()) {
                return prettyIdentifier(id);
            }
            if (argument instanceof Text text) {
                found = ability ? TropimonDex.findAbilityByQuery(text.getString())
                        : TropimonDex.findItemByQuery(text.getString());
                if (found != null) {
                    return found;
                }
            }
        }
        return "";
    }

    private static String prettyIdentifier(String id) {
        String value = id == null ? "" : id.replace('_', ' ').replace('-', ' ').trim();
        if (value.isBlank()) {
            return "";
        }
        StringBuilder output = new StringBuilder(value.length());
        boolean upper = true;
        for (char character : value.toCharArray()) {
            if (Character.isWhitespace(character)) {
                output.append(' ');
                upper = true;
            } else {
                output.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return output.toString();
    }

    private static String translatedId(Object argument, String prefix) {
        if (!(argument instanceof Text text)) {
            return "";
        }
        Deque<Text> pending = new ArrayDeque<>();
        pending.add(text);
        while (!pending.isEmpty()) {
            Text current = pending.removeFirst();
            if (current.getContent() instanceof TranslatableTextContent translatable) {
                String key = translatable.getKey();
                int prefixIndex = key.indexOf(prefix);
                if (prefixIndex >= 0) {
                    String id = key.substring(prefixIndex + prefix.length());
                    int separator = id.lastIndexOf('.');
                    return separator < 0 ? id : id.substring(separator + 1);
                }
                for (Object nested : translatable.getArgs()) {
                    if (nested instanceof Text nestedText) pending.addLast(nestedText);
                }
            }
            pending.addAll(current.getSiblings());
        }
        return "";
    }

    private static boolean revealMove(PokemonSet pokemon, MoveData move) {
        for (MoveData known : pokemon.moves) {
            if (known != null && known.id().equals(move.id())) {
                pokemon.movesKnown = true;
                return false;
            }
        }
        for (int slot = 0; slot < pokemon.moves.size(); slot++) {
            if (pokemon.moves.get(slot) == null) {
                pokemon.moves.set(slot, move);
                pokemon.movesKnown = true;
                return true;
            }
        }
        if (pokemon.moves.size() < 4) {
            pokemon.moves.add(move);
            pokemon.movesKnown = true;
            return true;
        }
        return false;
    }

    private static void rememberOpponentBuild(BattleSide side, PokemonSet pokemon) {
        if (side == BattleSide.OPPONENT) {
            CobblemonBattleDataProvider.rememberOpponentRevelation(pokemon);
        }
    }

    private static boolean recordDamage(Object[] arguments) {
        Object target = argument(arguments, 0);
        BattleSide side = sideFromArgument(target);
        if (side == null && lastMoveSide != null) {
            side = opposite(lastMoveSide);
        }
        if (side != null && matchesPartner(side, target)) {
            return false;
        }
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) {
            return false;
        }
        String key = historyKey(pokemon, side);
        PokemonHistory damagedHistory = history(pokemon, side);
        PokemonSet source = currentPokemon(opposite(side));
        PokemonHistory sourceHistory = source == null ? null : history(source, opposite(side));
        MoveData sourceMove = sourceHistory == null || lastMoveFromPartner
                ? null : TropimonDex.findMoveByQuery(sourceHistory.lastMoveId);
        boolean directMoveHit = lastMoveSide != null && !lastMoveFromPartner && side == opposite(lastMoveSide)
                && sourceMove != null && sourceMove.category() != DamageCategory.STATUS;
        int damage = Math.max(0, integerArgument(argument(arguments, 1), 0));
        if (damagedHistory.damageTakenTurn == currentTurn && sourceHistory != null
                && sourceHistory.lastMoveId.equals(damagedHistory.damageSourceMoveId)) {
            damagedHistory.lastDamageTaken += damage;
        } else {
            damagedHistory.lastDamageTaken = damage;
            damagedHistory.damageSourceMoveId = sourceHistory == null ? "" : sourceHistory.lastMoveId;
        }
        damagedHistory.damageTakenTurn = currentTurn;
        damagedHistory.lastDamageCategory = sourceMove == null ? DamageCategory.STATUS : sourceMove.category();
        if (directMoveHit) {
            damagedHistory.timesHit++;
            if (side == lastDamagedSide && key.equals(lastDamagedHistoryKey)) {
                pendingDamageEvents++;
            } else {
                pendingDamageEvents = 1;
                lastDamagedHistoryKey = key;
            }
        } else {
            pendingDamageEvents = 0;
            lastDamagedHistoryKey = key;
        }
        lastDamagedSide = side;
        mostRecentDamagedSide = side;
        return true;
    }

    private static boolean recordHitCount(Object[] arguments, boolean singular) {
        if (lastDamagedSide == null) {
            return false;
        }
        int hits = singular ? 1 : integerArgument(argument(arguments, 0), 1);
        PokemonHistory history = histories.get(lastDamagedHistoryKey);
        if (history == null) {
            return false;
        }
        history.timesHit += Math.max(0, hits - pendingDamageEvents);
        clearPendingDamage();
        return true;
    }

    private static void clearPendingDamage() {
        lastDamagedSide = null;
        lastDamagedHistoryKey = "";
        pendingDamageEvents = 0;
    }

    private static boolean recordFaint(Object[] arguments) {
        Object target = argument(arguments, 0);
        BattleSide side = sideFromArgument(target);
        if (side == null) {
            side = mostRecentDamagedSide != null ? mostRecentDamagedSide
                    : lastMoveSide == null ? null : opposite(lastMoveSide);
        }
        if (side != null && matchesPartner(side, target)) {
            String partnerKey = side + ":partner:" + TropimonDex.normalize(argumentText(target));
            if (!faintedPokemon.add(partnerKey)) return false;
            sideHistory(side).faintedAllies++;
            sideHistory(side).lastFaintTurn = currentTurn;
            return true;
        }
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) {
            return false;
        }
        String key = historyKey(pokemon, side);
        if (!faintedPokemon.add(key)) {
            return false;
        }
        sideHistory(side).faintedAllies++;
        sideHistory(side).lastFaintTurn = currentTurn;
        return true;
    }

    private static boolean recordSwitch(BattleSide side) {
        if (side == null) return false;
        SideHistory sideHistory = sideHistory(side);
        sideHistory.lastSwitchTurn = currentTurn;
        return true;
    }

    private static boolean recordWithdraw(BattleSide side) {
        PokemonSet pokemon = currentPokemon(side);
        if (pokemon == null) return false;
        PokemonHistory history = history(pokemon, side);
        history.consecutiveMoveUses = 0;
        history.lastMoveId = "";
        history.defenseCurlUsed = false;
        history.flashFireActive = false;
        history.paradoxBoostActive = false;
        history.boostsKnown = true;
        for (Stat stat : combatStats()) {
            history.boosts.put(stat, 0);
            pokemon.boosts.put(stat, 0);
        }
        return true;
    }

    private static boolean recordMoveFailure(BattleSide side) {
        if (lastMoveFromPartner) return false;
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) return false;
        PokemonHistory history = history(pokemon, side);
        history.lastMoveFailed = true;
        if (Set.of("furycutter", "rollout", "iceball").contains(history.lastMoveId)) {
            history.consecutiveMoveUses = 0;
        }
        return true;
    }

    private static boolean recordFlashFire(Object[] arguments) {
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        if (side != null && matchesPartner(side, actor)) return false;
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) return false;
        history(pokemon, side).flashFireActive = true;
        return true;
    }

    private static boolean recordParadoxBoost(String key, Object[] arguments) {
        Object actor = argument(arguments, 0);
        BattleSide side = sideFromArgument(actor);
        if (side != null && matchesPartner(side, actor)) return false;
        PokemonSet pokemon = currentPokemon(side);
        if (side == null || pokemon == null) return false;
        boolean active = !key.contains(".end.") && !key.endsWith(".end");
        PokemonHistory history = history(pokemon, side);
        boolean changed = history.paradoxBoostActive != active;
        history.paradoxBoostActive = active;
        if (active && key.contains("protosynthesis")) {
            pokemon.ability = "Protosynthesis";
            pokemon.abilityKnown = true;
            rememberOpponentBuild(side, pokemon);
        } else if (active && key.contains("quarkdrive")) {
            pokemon.ability = "Quark Drive";
            pokemon.abilityKnown = true;
            rememberOpponentBuild(side, pokemon);
        }
        return changed;
    }

    private static boolean recordHelpingHand(Object[] arguments) {
        BattleSide target = sideFromArgument(argument(arguments, 0));
        if (target == null) return false;
        SideConditions conditions = target == BattleSide.ALLY ? ally : opponent;
        boolean changed = !conditions.helpingHand;
        conditions.helpingHand = true;
        return changed;
    }

    private static Object argument(Object[] arguments, int index) {
        return arguments != null && index >= 0 && index < arguments.length ? arguments[index] : null;
    }

    private static int integerArgument(Object argument, int fallback) {
        if (argument instanceof Number number) {
            return number.intValue();
        }
        String value = argument instanceof Text text ? text.getString() : String.valueOf(argument);
        Matcher matcher = INTEGER.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static String moveId(Object argument) {
        if (!(argument instanceof Text text)) {
            return "";
        }
        Deque<Text> pending = new ArrayDeque<>();
        pending.add(text);
        while (!pending.isEmpty()) {
            Text current = pending.removeFirst();
            if (current.getContent() instanceof TranslatableTextContent translatable) {
                String key = translatable.getKey();
                if (key.startsWith("cobblemon.move.") && !key.endsWith(".desc") && !key.contains(".category.")) {
                    return TropimonDex.normalize(key.substring("cobblemon.move.".length()));
                }
                for (Object nested : translatable.getArgs()) {
                    if (nested instanceof Text nestedText) pending.addLast(nestedText);
                }
            }
            pending.addAll(current.getSiblings());
        }
        MoveData move = TropimonDex.findMoveByQuery(text.getString());
        return move == null ? "" : move.id();
    }

    private static BattleSide sideFromArgument(Object argument) {
        BattleSide ownerSide = sideFromOwnerPrefix(argument);
        if (ownerSide != null) return ownerSide;
        String rendered = argument instanceof Text text ? TropimonDex.normalize(text.getString())
                : TropimonDex.normalize(String.valueOf(argument));
        DamageCalcState state = DamageCalcState.shared();
        PokemonSet attacker = state.attacker;
        PokemonSet defender = state.defender;
        boolean allyMatch = matchesPokemon(attacker, rendered);
        boolean opponentMatch = matchesPokemon(defender, rendered);
        if (allyMatch != opponentMatch) {
            return allyMatch ? BattleSide.ALLY : BattleSide.OPPONENT;
        }
        boolean allyPartnerMatch = matchesPartnerName(state.field.attackerSide, rendered);
        boolean opponentPartnerMatch = matchesPartnerName(state.field.defenderSide, rendered);
        if (allyPartnerMatch != opponentPartnerMatch) {
            return allyPartnerMatch ? BattleSide.ALLY : BattleSide.OPPONENT;
        }
        if (argument instanceof Text text) {
            Set<String> speciesKeys = translationSpeciesIds(text);
            allyMatch = matchesSpeciesId(attacker, speciesKeys);
            opponentMatch = matchesSpeciesId(defender, speciesKeys);
            if (allyMatch != opponentMatch) return allyMatch ? BattleSide.ALLY : BattleSide.OPPONENT;
            if (translationIndicatesOpponent(text)) return BattleSide.OPPONENT;
        }
        return null;
    }

    private static boolean matchesPartner(BattleSide side, Object argument) {
        String rendered = argument instanceof Text text ? TropimonDex.normalize(text.getString())
                : TropimonDex.normalize(String.valueOf(argument));
        DamageCalcState state = DamageCalcState.shared();
        return matchesPartnerName(side == BattleSide.ALLY
                ? state.field.attackerSide : state.field.defenderSide, rendered);
    }

    private static boolean matchesPartnerName(SideConditions side, String rendered) {
        if (side == null || rendered.isBlank()) return false;
        String partner = TropimonDex.normalize(side.partnerName);
        return !partner.isBlank() && (rendered.equals(partner) || rendered.contains(partner));
    }

    private static BattleSide sideFromOwnerPrefix(Object argument) {
        String rendered = argument instanceof Text text ? text.getString() : String.valueOf(argument);
        String lower = rendered.toLowerCase(java.util.Locale.ROOT);
        int possessive = lower.indexOf("'s ");
        if (possessive < 0) possessive = lower.indexOf("’s ");
        if (possessive <= 0) return null;
        String owner = TropimonDex.normalize(rendered.substring(0, possessive));
        MinecraftClient client = MinecraftClient.getInstance();
        String localPlayer = client == null || client.player == null
                ? "" : TropimonDex.normalize(client.player.getName().getString());
        return !localPlayer.isBlank() && owner.equals(localPlayer) ? BattleSide.ALLY : BattleSide.OPPONENT;
    }

    private static boolean translationIndicatesOpponent(Text root) {
        Deque<Text> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Text current = pending.removeFirst();
            if (current.getContent() instanceof TranslatableTextContent translatable) {
                String key = translatable.getKey();
                if (key.equals("cobblemon.battle.owned_pokemon")
                        || key.contains(".opponent") || key.contains(".opposing")) {
                    return true;
                }
                for (Object nested : translatable.getArgs()) {
                    if (nested instanceof Text nestedText) pending.addLast(nestedText);
                }
            }
            pending.addAll(current.getSiblings());
        }
        return false;
    }

    private static boolean matchesPokemon(PokemonSet pokemon, String rendered) {
        if (pokemon == null || rendered.isBlank()) return false;
        String battleName = TropimonDex.normalize(pokemon.battleName);
        return rendered.equals(battleName)
                || !battleName.isBlank() && rendered.contains(battleName)
                || rendered.equals(TropimonDex.normalize(pokemon.species.name()))
                || rendered.contains(TropimonDex.normalize(pokemon.species.name()));
    }

    private static Set<String> translationSpeciesIds(Text root) {
        HashSet<String> ids = new HashSet<>();
        Deque<Text> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Text current = pending.removeFirst();
            if (current.getContent() instanceof TranslatableTextContent translatable) {
                String key = translatable.getKey();
                if (key.startsWith("cobblemon.species.")) {
                    ids.add(TropimonDex.normalize(key.substring("cobblemon.species.".length()).replace(".name", "")));
                }
                for (Object nested : translatable.getArgs()) {
                    if (nested instanceof Text nestedText) pending.addLast(nestedText);
                }
            }
            pending.addAll(current.getSiblings());
        }
        return ids;
    }

    private static boolean matchesSpeciesId(PokemonSet pokemon, Set<String> ids) {
        if (pokemon == null || ids.isEmpty()) return false;
        return ids.contains(TropimonDex.normalize(pokemon.species.id()))
                || ids.contains(TropimonDex.normalize(pokemon.species.cobblemonSpeciesId()));
    }

    static synchronized void applyHistory(PokemonSet pokemon, boolean allySide) {
        if (pokemon == null) return;
        BattleSide side = allySide ? BattleSide.ALLY : BattleSide.OPPONENT;
        PokemonHistory history = history(pokemon, side);
        SideHistory sideHistory = sideHistory(side);
        pokemon.battleHistoryKnown = battleIdentity != null;
        pokemon.timesHit = history.timesHit;
        pokemon.faintedAllies = sideHistory.faintedAllies;
        pokemon.lastMoveId = history.lastMoveId;
        pokemon.consecutiveMoveUses = history.consecutiveMoveUses;
        pokemon.echoedVoiceChain = currentTurn <= 0 || sideHistory.lastEchoedVoiceTurn >= currentTurn - 1
                ? sideHistory.echoedVoiceChain : 0;
        pokemon.defenseCurlUsed = history.defenseCurlUsed;
        pokemon.switchedInThisTurn = sideHistory.lastSwitchTurn == currentTurn;
        pokemon.allyFaintedPreviousTurn = sideHistory.lastFaintTurn >= 0
                && sideHistory.lastFaintTurn == currentTurn - 1;
        pokemon.lastMoveFailed = history.lastMoveFailed;
        pokemon.flashFireActive = history.flashFireActive;
        pokemon.paradoxBoostActive = history.paradoxBoostActive;
        pokemon.turnsActive = sideHistory.lastSwitchTurn >= 0
                ? Math.max(0, currentTurn - sideHistory.lastSwitchTurn) : -1;
        pokemon.lastDamageTaken = history.damageTakenTurn == currentTurn ? history.lastDamageTaken : 0;
        pokemon.lastDamageCategory = history.damageTakenTurn == currentTurn
                ? history.lastDamageCategory : DamageCategory.STATUS;
        if (history.boostsKnown) {
            for (Stat stat : combatStats()) {
                pokemon.boosts.put(stat, history.boosts.getOrDefault(stat, 0));
            }
        }
    }

    private static void refreshCurrentHistory() {
        applyHistory(DamageCalcState.shared().attacker, true);
        applyHistory(DamageCalcState.shared().defender, false);
    }

    private static PokemonHistory history(PokemonSet pokemon, BattleSide side) {
        return histories.computeIfAbsent(historyKey(pokemon, side), ignored -> new PokemonHistory());
    }

    private static String historyKey(PokemonSet pokemon, BattleSide side) {
        return pokemon.battleId == null || pokemon.battleId.isBlank()
                ? side.name() + ":" + pokemon.species.id()
                : pokemon.battleId;
    }

    private static PokemonSet currentPokemon(BattleSide side) {
        if (side == null) return null;
        return side == BattleSide.ALLY ? DamageCalcState.shared().attacker : DamageCalcState.shared().defender;
    }

    private static SideHistory sideHistory(BattleSide side) {
        return side == BattleSide.ALLY ? allyHistory : opponentHistory;
    }

    private static BattleSide opposite(BattleSide side) {
        return side == BattleSide.ALLY ? BattleSide.OPPONENT : BattleSide.ALLY;
    }

    private static boolean acceptWeather(String key) {
        Weather next = weatherFromKey(key);
        if (next == null) {
            return false;
        }
        weatherKnown = true;
        if (key.endsWith(".end")) {
            next = Weather.NONE;
        }
        boolean changed = weather != next;
        weather = next;
        return changed;
    }

    private static Weather weatherFromKey(String key) {
        if (key.contains(".raindance.")) return Weather.RAIN;
        if (key.contains(".sunnyday.")) return Weather.SUN;
        if (key.contains(".sandstorm.")) return Weather.SAND;
        if (key.contains(".snow.") || key.contains(".hail.")) return Weather.SNOW;
        return null;
    }

    private static boolean acceptField(String key) {
        boolean active = key.contains(".fieldstart.");
        if (key.endsWith(".trickroom")) {
            roomsKnown = true;
            boolean changed = trickRoom != active;
            trickRoom = active;
            return changed;
        }
        if (key.endsWith(".gravity")) {
            roomsKnown = true;
            boolean changed = gravity != active;
            gravity = active;
            return changed;
        }
        return acceptTerrain(key);
    }

    private static boolean acceptTerrain(String key) {
        Terrain next = terrainFromKey(key);
        if (next == null) {
            return false;
        }
        terrainKnown = true;
        if (key.contains(".fieldend.")) {
            next = Terrain.NONE;
        }
        boolean changed = terrain != next;
        terrain = next;
        return changed;
    }

    private static Terrain terrainFromKey(String key) {
        if (key.endsWith(".electricterrain")) return Terrain.ELECTRIC;
        if (key.endsWith(".grassyterrain")) return Terrain.GRASSY;
        if (key.endsWith(".mistyterrain")) return Terrain.MISTY;
        if (key.endsWith(".psychicterrain")) return Terrain.PSYCHIC;
        return null;
    }

    private static boolean acceptSide(String key) {
        boolean active = key.contains(".sidestart.");
        SideConditions side = key.contains(".ally.") ? ally : key.contains(".opponent.") ? opponent : null;
        if (side == null) {
            return false;
        }
        sidesKnown = true;
        if (key.endsWith(".reflect")) {
            return setReflect(side, active);
        }
        if (key.endsWith(".lightscreen")) {
            return setLightScreen(side, active);
        }
        if (key.endsWith(".auroraveil")) {
            return setAuroraVeil(side, active);
        }
        if (key.endsWith(".tailwind")) {
            return setTailwind(side, active);
        }
        return false;
    }

    static synchronized void applyTo(FieldState field) {
        if (field == null) {
            return;
        }
        if (weatherKnown) field.weather = weather;
        if (terrainKnown) field.terrain = terrain;
        if (roomsKnown) {
            field.trickRoom = trickRoom;
            field.gravity = gravity;
        }
        if (sidesKnown) {
            copy(ally, field.attackerSide);
            copy(opponent, field.defenderSide);
        }
    }

    static synchronized String diagnosticSummary() {
        return "weather=" + weather + ", terrain=" + terrain + ", trickRoom=" + trickRoom + ", gravity=" + gravity
                + ", ally=[" + ally.summary() + "], opponent=[" + opponent.summary() + "]"
                + ", history=" + histories.size() + ", fainted=" + allyHistory.faintedAllies
                + "/" + opponentHistory.faintedAllies + ", turn=" + currentTurn;
    }

    private static void refreshOpenScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.currentScreen instanceof DamageCalcScreen screen) {
                screen.refreshBattleConditions();
            }
        });
    }

    private static boolean setReflect(SideConditions side, boolean value) {
        boolean changed = side.reflect != value;
        side.reflect = value;
        return changed;
    }

    private static boolean setLightScreen(SideConditions side, boolean value) {
        boolean changed = side.lightScreen != value;
        side.lightScreen = value;
        return changed;
    }

    private static boolean setAuroraVeil(SideConditions side, boolean value) {
        boolean changed = side.auroraVeil != value;
        side.auroraVeil = value;
        return changed;
    }

    private static boolean setTailwind(SideConditions side, boolean value) {
        boolean changed = side.tailwind != value;
        side.tailwind = value;
        return changed;
    }

    private static void clear(SideConditions side) {
        side.reflect = false;
        side.lightScreen = false;
        side.auroraVeil = false;
        side.tailwind = false;
        side.helpingHand = false;
        side.friendGuard = false;
        side.wideGuard = false;
    }

    private static void copy(SideConditions source, SideConditions target) {
        target.reflect = source.reflect;
        target.lightScreen = source.lightScreen;
        target.auroraVeil = source.auroraVeil;
        target.tailwind = source.tailwind;
        target.helpingHand = source.helpingHand;
        target.wideGuard = source.wideGuard;
        // Friend Guard remains manual until an active partner is represented.
    }

    private enum BattleSide { ALLY, OPPONENT }

    private static final class PokemonHistory {
        int timesHit;
        String lastMoveId = "";
        int consecutiveMoveUses;
        boolean defenseCurlUsed;
        boolean lastMoveFailed;
        boolean flashFireActive;
        boolean paradoxBoostActive;
        int lastDamageTaken;
        int damageTakenTurn = -1;
        DamageCategory lastDamageCategory = DamageCategory.STATUS;
        String damageSourceMoveId = "";
        final EnumMap<Stat, Integer> boosts = new EnumMap<>(Stat.class);
        boolean boostsKnown;
    }

    private static final class SideHistory {
        int faintedAllies;
        int echoedVoiceChain;
        int lastEchoedVoiceTurn = -1;
        int lastFaintTurn = -1;
        int lastSwitchTurn = -1;

        void clear() {
            faintedAllies = 0;
            echoedVoiceChain = 0;
            lastEchoedVoiceTurn = -1;
            lastFaintTurn = -1;
            lastSwitchTurn = -1;
        }
    }
}
