package fr.tropimon.damagecalc;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Language;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class DamageCalcScreen extends Screen {
    private static final int PANEL = 280;
    private static final int ROW = 18;
    private static final int MOVE_ROW = 20;
    private static final int MOVE_FIELD_WIDTH = 146;
    private static final int MOVE_DELETE_X = 150;
    private static final int MOVE_DELETE_WIDTH = 22;
    private static final int MOVE_Z_X = 176;
    private static final int MOVE_Z_WIDTH = 28;
    private static final int MOVE_RESULT_X = 208;
    private static final int MOVE_RESULT_WIDTH = 72;
    private static final int TOOLBAR_BUTTON_WIDTH = 76;
    private static final int CONTROL_GAP = 4;
    private static final int MAX_VISIBLE_SUGGESTIONS = 8;
    private static final int SUGGESTION_ROW_HEIGHT = 18;
    private static final int POKEMON_SUGGESTION_ROW_HEIGHT = 22;
    private static final Stat[] STATS = {Stat.HP, Stat.ATK, Stat.DEF, Stat.SPA, Stat.SPD, Stat.SPE};

    private final DamageCalcState state;
    private final ArrayList<SearchField> searchFields = new ArrayList<>();
    private final ArrayList<RenderedSuggestion> renderedSuggestions = new ArrayList<>();
    private final ArrayList<ActiveButton> activeButtons = new ArrayList<>();
    private final EnumMap<SearchKind, CachedSuggestions> suggestionCache = new EnumMap<>(SearchKind.class);
    private final ButtonWidget[][] damageButtons = new ButtonWidget[2][4];
    private long renderedDamageFingerprint = Long.MIN_VALUE;
    private SearchKind activeKind;
    private SearchKind openKind;
    private int suggestionScroll;
    private int selectedSuggestion = -1;
    private boolean compactStatsVisible;
    private boolean battleRefreshPending;
    private int verticalScroll;
    private String attackerRandomSetSpecies = "";
    private String defenderRandomSetSpecies = "";
    private int attackerRandomSetIndex = -1;
    private int defenderRandomSetIndex = -1;

    public DamageCalcScreen(DamageCalcState state) {
        super(Text.translatable("screen.tropimon_damage_calc.title"));
        this.state = state;
    }

    @Override
    public void close() {
        TropimonDamageCalcClient.closeCalculator(this);
    }

    void refreshBattleConditions() {
        battleRefreshPending = true;
    }

    void refreshBattleSnapshot() {
        battleRefreshPending = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (battleRefreshPending && openKind == null && !(getFocused() instanceof TextFieldWidget)) {
            battleRefreshPending = false;
            clearAndInit();
        }
    }

    @Override
    protected void init() {
        searchFields.clear();
        activeButtons.clear();
        for (ButtonWidget[] side : damageButtons) {
            java.util.Arrays.fill(side, null);
        }
        int panelX = (width - panelWidth()) / 2;
        int panelW = panelWidth();
        int leftX = leftX(panelX, panelW);
        int rightX = rightX(panelX, panelW);
        int editorY = editorY();

        addFieldEditor(panelX + 14, 28 - verticalScroll, panelW - 28);
        ButtonWidget closeButton = addButton(panelX + panelW - 24, 10, 18, 18, "X", button -> close());
        closeButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.close")));
        boolean compact = compactLayout();
        boolean showStats = !compact || compactStatsVisible;
        boolean showMoves = !compact || !compactStatsVisible;
        addPokemonEditor(state.attacker, leftX, editorY, true, showStats);
        addPokemonEditor(state.defender, rightX, editorY, false, showStats);

        if (compact) {
            addButton(panelX + panelW - 84, 10, 56, 18,
                    tr(compactStatsVisible ? "screen.tropimon_damage_calc.view.moves"
                            : "screen.tropimon_damage_calc.view.stats"), button -> {
                        compactStatsVisible = !compactStatsVisible;
                        reopen();
                    });
        }

        int moveY = moveY(editorY);
        if (showMoves) {
            addMoveEditor(state.attacker, state.defender, leftX, moveY, true);
            addMoveEditor(state.defender, state.attacker, rightX, moveY, false);
        }
        renderedDamageFingerprint = state.calculationFingerprint();
    }

    private void addMoveEditor(PokemonSet source, PokemonSet target, int x, int y, boolean fromAttacker) {
        for (int slot = 0; slot < 4; slot++) {
            int moveSlot = slot;
            MoveData move = source.moveAt(slot);
            DamageResult result = state.calculateMove(fromAttacker, slot);
            String value = move == null ? "" : moveDisplayName(DamageCalcState.displayMove(source, slot));
            String placeholder = tr("screen.tropimon_damage_calc.search.move", slot + 1);
            TextFieldWidget moveField = addSearchField(moveKind(fromAttacker, slot), x, y + slot * MOVE_ROW,
                    MOVE_FIELD_WIDTH, value, placeholder, ignored -> {
            });
            if (move != null) {
                moveField.setTooltip(Tooltip.of(Text.literal(moveDescription(move))));
            }
            ButtonWidget deleteButton = addButton(x + MOVE_DELETE_X, y + slot * MOVE_ROW,
                    MOVE_DELETE_WIDTH, "X", button -> {
                source.deleteMove(moveSlot);
                if (state.selectedMoveAttacker == fromAttacker && state.selectedMoveIndex == moveSlot) {
                    state.selectedMoveIndex = 0;
                }
                reopen();
            });
            deleteButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.move.delete")));
            ButtonWidget zButton = addButton(x + MOVE_Z_X, y + slot * MOVE_ROW, MOVE_Z_WIDTH, "Z", button -> {
                source.toggleZMove(moveSlot);
                reopen();
            });
            zButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.move.z")));
            ButtonWidget resultButton = addButton(x + MOVE_RESULT_X, y + slot * MOVE_ROW, MOVE_RESULT_WIDTH,
                    result == null ? "-" : damageRange(result), button -> {
                if (source.moveAt(moveSlot) != null) {
                    state.selectedMoveAttacker = fromAttacker;
                    state.selectedMoveIndex = moveSlot;
                    reopen();
                }
            });
            if (result != null) {
                resultButton.setTooltip(Tooltip.of(Text.literal(damageTooltip(result))));
            }
            damageButtons[fromAttacker ? 0 : 1][slot] = resultButton;
        }
    }

    private void addPokemonEditor(PokemonSet pokemon, int x, int y, boolean attacker, boolean showStats) {
        SearchKind pokemonKind = attacker ? SearchKind.ATTACKER_POKEMON : SearchKind.DEFENDER_POKEMON;
        SearchKind itemKind = attacker ? SearchKind.ATTACKER_ITEM : SearchKind.DEFENDER_ITEM;
        SearchKind abilityKind = attacker ? SearchKind.ATTACKER_ABILITY : SearchKind.DEFENDER_ABILITY;
        SearchKind partnerAbilityKind = attacker
                ? SearchKind.ATTACKER_PARTNER_ABILITY : SearchKind.DEFENDER_PARTNER_ABILITY;
        SearchKind natureKind = attacker ? SearchKind.ATTACKER_NATURE : SearchKind.DEFENDER_NATURE;

        String pokemonSearch = attacker ? state.attackerSearch : state.defenderSearch;
        int selectorX = x + 64;
        addSearchField(pokemonKind, selectorX, y, 146,
                localizedSelectionValue(pokemonSearch, pokemon.species.name(), speciesDisplayName(pokemon.species)),
                tr("screen.tropimon_damage_calc.search.pokemon"),
                value -> {
                    if (attacker) state.attackerSearch = value;
                    else state.defenderSearch = value;
                });
        addClearButton(x + PANEL - 22, y, pokemonKind);
        boolean showRandomSet = prepareRandomSetSelector(pokemon, attacker);

        String itemSearch = attacker ? state.attackerItemSearch : state.defenderItemSearch;
        TextFieldWidget itemField = addSearchField(itemKind, selectorX, y + 22, 194,
                localizedSelectionValue(itemSearch, pokemon.item, itemDisplayName(pokemon.item)),
                tr("screen.tropimon_damage_calc.search.item"),
                value -> {
                    if (attacker) state.attackerItemSearch = value;
                    else state.defenderItemSearch = value;
                });
        itemField.setTooltip(Tooltip.of(Text.literal(itemDescription(pokemon.item))));
        addClearButton(x + PANEL - 22, y + 22, itemKind);

        String abilitySearch = attacker ? state.attackerAbilitySearch : state.defenderAbilitySearch;
        TextFieldWidget abilityField = addSearchField(abilityKind, selectorX, y + 44, 78,
                localizedSelectionValue(abilitySearch, pokemon.ability, abilityDisplayName(pokemon.ability)),
                tr("screen.tropimon_damage_calc.search.ability"),
                value -> {
                    if (attacker) state.attackerAbilitySearch = value;
                    else state.defenderAbilitySearch = value;
                });
        abilityField.setTooltip(Tooltip.of(Text.literal(abilityDescription(pokemon.ability))));
        addClearButton(selectorX + 80, y + 44, abilityKind);
        TextFieldWidget natureField = addSearchField(natureKind, x + 168, y + 44, 88,
                pokemon.natureKnown ? natureDisplayName(pokemon.nature) : "", tr("screen.tropimon_damage_calc.search.nature"),
                value -> {
                    if (attacker) state.attackerNatureSearch = value;
                    else state.defenderNatureSearch = value;
                });
        natureField.setTooltip(Tooltip.of(Text.literal(natureDescription(pokemon.nature))));
        addClearButton(x + 258, y + 44, natureKind);

        boolean doubles = state.field.doubles;
        int levelWidth = showRandomSet ? (doubles ? 43 : 50) : (doubles ? 52 : 90);
        int setWidth = showRandomSet ? (doubles ? 43 : 50) : 0;
        int setX = x + levelWidth + CONTROL_GAP;
        int statusX = showRandomSet ? setX + setWidth + CONTROL_GAP : setX;
        int statusWidth = showRandomSet ? (doubles ? 43 : 86) : (doubles ? 52 : 90);
        int teraX = statusX + statusWidth + CONTROL_GAP;
        int teraWidth = doubles ? (showRandomSet ? 43 : 52) : PANEL - (teraX - x);
        addButton(x, y + 66, levelWidth, "Nv " + pokemon.level, button -> {
            pokemon.level = nextLevel(pokemon.level);
            pokemon.currentHp = -1;
            pokemon.observedMaxHp = -1;
            reopen();
        });
        if (showRandomSet) {
            addRandomSetSelector(pokemon, setX, y + 66, setWidth, attacker);
        }
        addButton(statusX, y + 66, statusWidth, pokemon.status.label, button -> {
            pokemon.status = DamageCalcState.cycle(List.of(StatusCondition.values()), pokemon.status);
            reopen();
        });
        addButton(teraX, y + 66, teraWidth, "Tera " + typeDisplayName(pokemon.teraType), button -> {
            pokemon.teraType = DamageCalcState.cycle(TropimonDex.teraTypes(), pokemon.teraType);
            pokemon.terastallized = pokemon.teraType != PokeType.NONE;
            reopen();
        });
        if (doubles) {
            int supportWidth = showRandomSet ? 43 : 54;
            int helpingHandX = teraX + teraWidth + CONTROL_GAP;
            ButtonWidget helpingHand = addToggleButton(helpingHandX, y + 66, supportWidth,
                    "HH", sideFor(attacker).helpingHand, button -> {
                sideFor(attacker).helpingHand = !sideFor(attacker).helpingHand;
                reopen();
            });
            helpingHand.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.helping_hand")));
            ButtonWidget friendGuard = addToggleButton(helpingHandX + supportWidth + CONTROL_GAP,
                    y + 66, supportWidth, "FG", sideFor(attacker).friendGuard, button -> {
                sideFor(attacker).friendGuard = !sideFor(attacker).friendGuard;
                reopen();
            });
            friendGuard.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.friend_guard")));

            SideConditions side = sideFor(attacker);
            String partnerSearch = attacker
                    ? state.attackerPartnerAbilitySearch : state.defenderPartnerAbilitySearch;
            TextFieldWidget partnerField = addSearchField(partnerAbilityKind, x, y + 88, 140,
                    localizedSelectionValue(partnerSearch, side.partnerAbility,
                            abilityDisplayName(side.partnerAbility)),
                    tr("screen.tropimon_damage_calc.search.partner_ability"), value -> {
                        if (attacker) state.attackerPartnerAbilitySearch = value;
                        else state.defenderPartnerAbilitySearch = value;
                    });
            partnerField.setTooltip(Tooltip.of(Text.literal(abilityDescription(side.partnerAbility))));
            addClearButton(x + 142, y + 88, partnerAbilityKind);
            ButtonWidget targets = addButton(x + 164, y + 88, 52,
                    tr("screen.tropimon_damage_calc.targets", side.spreadTargets), button -> {
                        side.spreadTargets = side.spreadTargets == 2 ? 1 : 2;
                        reopen();
                    });
            targets.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.targets")));
            ButtonWidget wideGuard = addToggleButton(x + 220, y + 88, 60, "WG", side.wideGuard, button -> {
                side.wideGuard = !side.wideGuard;
                reopen();
            });
            wideGuard.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.wide_guard")));
        }

        addSideConditionsEditor(attacker ? state.field.attackerSide : state.field.defenderSide,
                x, y + (doubles ? 110 : 88));

        if (!showStats) {
            return;
        }
        int statY = y + (doubles ? 142 : 120);
        for (int i = 0; i < STATS.length; i++) {
            Stat stat = STATS[i];
            int rowY = statY + i * ROW;
            addButton(x, rowY, 32, 16, statLabel(stat), button -> {
            });
            addNumberField(x + 36, rowY, 28, pokemon.evs.get(stat), 0, 252, true, value -> {
                pokemon.evs.put(stat, value);
                if (stat == Stat.HP) {
                    pokemon.currentHp = -1;
                    pokemon.observedMaxHp = -1;
                }
            });
            addNumberField(x + 68, rowY, 28, pokemon.ivs.get(stat), 0, 31, value -> {
                pokemon.ivs.put(stat, value);
                if (stat == Stat.HP) {
                    pokemon.currentHp = -1;
                    pokemon.observedMaxHp = -1;
                }
            });
            addButton(x + 100, rowY, 42, 16, signed(pokemon.boosts.get(stat)), button -> {
                pokemon.boosts.put(stat, nextBoost(pokemon.boosts.get(stat)));
                reopen();
            });
        }
        ButtonWidget atkPreset = addButton(x + 188, statY, 92, "Atk", button -> {
            DamageCalcState.presetEvs(pokemon, EvPreset.ATK);
            reopen();
        });
        atkPreset.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.preset.atk")));
        ButtonWidget spaPreset = addButton(x + 188, statY + 28, 92, "SpA", button -> {
            DamageCalcState.presetEvs(pokemon, EvPreset.SPA);
            reopen();
        });
        spaPreset.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.preset.spa")));
        ButtonWidget defPreset = addButton(x + 188, statY + 56, 92, "Def", button -> {
            DamageCalcState.presetEvs(pokemon, EvPreset.DEF);
            reopen();
        });
        defPreset.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.preset.def")));
        ButtonWidget spdPreset = addButton(x + 188, statY + 84, 92, "SpD", button -> {
            DamageCalcState.presetEvs(pokemon, EvPreset.SPD);
            reopen();
        });
        spdPreset.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.preset.spd")));
    }

    private boolean prepareRandomSetSelector(PokemonSet pokemon, boolean attacker) {
        if (!CobblemonBattleDataProvider.randomBattleActive(MinecraftClient.getInstance())) {
            resetRandomSetSelector(attacker);
            return false;
        }
        List<TropimonRandomBattleSets.RandomBattleSet> sets = TropimonRandomBattleSets.setsFor(pokemon.species);
        if (sets.size() < 2) {
            resetRandomSetSelector(attacker);
            return false;
        }
        String speciesId = pokemon.species.id();
        if (!speciesId.equals(attacker ? attackerRandomSetSpecies : defenderRandomSetSpecies)) {
            if (attacker) {
                attackerRandomSetSpecies = speciesId;
                attackerRandomSetIndex = -1;
            } else {
                defenderRandomSetSpecies = speciesId;
                defenderRandomSetIndex = -1;
            }
        }
        int storedIndex = attacker ? attackerRandomSetIndex : defenderRandomSetIndex;
        int candidates = TropimonRandomBattleSets.matchingSets(pokemon).size();
        if (candidates == 1 && storedIndex < 0) {
            return false;
        }
        if (storedIndex < 0) {
            TropimonRandomBattleSets.applySet(pokemon, sets.getFirst());
            setRandomSetIndex(attacker, 0);
            updateRandomSetSearchFields(pokemon, attacker);
        }
        return true;
    }

    private void addRandomSetSelector(PokemonSet pokemon, int x, int y, int width, boolean attacker) {
        List<TropimonRandomBattleSets.RandomBattleSet> sets = TropimonRandomBattleSets.setsFor(pokemon.species);
        int setIndex = Math.floorMod(attacker ? attackerRandomSetIndex : defenderRandomSetIndex, sets.size());
        TropimonRandomBattleSets.RandomBattleSet activeSet = sets.get(setIndex);
        ButtonWidget button = addButton(x, y, width,
                tr("screen.tropimon_damage_calc.random_set", setIndex + 1), pressed -> {
                    int nextIndex = (setIndex + 1) % sets.size();
                    TropimonRandomBattleSets.applySet(pokemon, sets.get(nextIndex));
                    setRandomSetIndex(attacker, nextIndex);
                    updateRandomSetSearchFields(pokemon, attacker);
                    state.selectedMoveIndex = 0;
                    reopen();
                });
        button.setTooltip(Tooltip.of(Text.literal(randomSetTooltip(activeSet, setIndex, sets.size()))));
    }

    private void setRandomSetIndex(boolean attacker, int index) {
        if (attacker) attackerRandomSetIndex = index;
        else defenderRandomSetIndex = index;
    }

    private void updateRandomSetSearchFields(PokemonSet pokemon, boolean attacker) {
        if (attacker) {
            state.attackerItemSearch = itemDisplayName(pokemon.item);
            state.attackerAbilitySearch = abilityDisplayName(pokemon.ability);
        } else {
            state.defenderItemSearch = itemDisplayName(pokemon.item);
            state.defenderAbilitySearch = abilityDisplayName(pokemon.ability);
        }
    }

    private void resetRandomSetSelector(boolean attacker) {
        if (attacker) {
            attackerRandomSetSpecies = "";
            attackerRandomSetIndex = -1;
        } else {
            defenderRandomSetSpecies = "";
            defenderRandomSetIndex = -1;
        }
    }

    private String randomSetTooltip(TropimonRandomBattleSets.RandomBattleSet set, int index, int count) {
        String item = itemDisplayName(set.itemId());
        String ability = abilityDisplayName(set.abilityId());
        String moves = set.moveIds().stream()
                .map(TropimonDex::findMoveByQuery)
                .filter(java.util.Objects::nonNull)
                .map(DamageCalcScreen::moveDisplayName)
                .collect(java.util.stream.Collectors.joining(", "));
        return tr("screen.tropimon_damage_calc.random_set.tooltip",
                index + 1, count, item, ability, moves);
    }

    private void addSideConditionsEditor(SideConditions side, int x, int y) {
        ButtonWidget reflect = addToggleButton(x, y, 60, moveTranslation("reflect", "Reflect"), side.reflect, button -> {
            side.reflect = !side.reflect;
            reopen();
        });
        reflect.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.reflect")));
        ButtonWidget lightScreen = addToggleButton(x + 64, y, 82, moveTranslation("lightscreen", "Light Screen"), side.lightScreen, button -> {
            side.lightScreen = !side.lightScreen;
            reopen();
        });
        lightScreen.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.light_screen")));
        ButtonWidget veil = addToggleButton(x + 150, y, 62, moveTranslation("auroraveil", "Veil"), side.auroraVeil, button -> {
            side.auroraVeil = !side.auroraVeil;
            reopen();
        });
        veil.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.veil")));
        ButtonWidget tailwind = addToggleButton(x + 216, y, 64, moveTranslation("tailwind", "Tailwind"), side.tailwind, button -> {
            side.tailwind = !side.tailwind;
            reopen();
        });
        tailwind.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.tailwind")));
    }

    private void addFieldEditor(int x, int y, int w) {
        boolean inBattle = CobblemonBattleDataProvider.isInBattle();
        int controlCount = inBattle ? 6 : 5;
        int toolbarButtonWidth = Math.min(TOOLBAR_BUTTON_WIDTH,
                Math.max(64, (w - (controlCount - 1) * CONTROL_GAP) / controlCount));
        int toolbarStep = toolbarButtonWidth + CONTROL_GAP;
        int controlsW = controlCount * toolbarButtonWidth + (controlCount - 1) * CONTROL_GAP;
        int startX = x + Math.max(0, (w - controlsW) / 2);
        int actionX = startX;
        if (inBattle) {
            ButtonWidget syncButton = addButton(actionX, y, toolbarButtonWidth, tr("screen.tropimon_damage_calc.sync"), button -> {
                state.setFromBattle(CobblemonBattleDataProvider.activeBattlePokemon(MinecraftClient.getInstance()));
                reopen();
            });
            syncButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.sync")));
            actionX += toolbarStep;
        }
        ButtonWidget swapButton = addButton(actionX, y, toolbarButtonWidth, tr("screen.tropimon_damage_calc.swap"), button -> {
            state.swap();
            reopen();
        });
        swapButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.swap")));
        int gx = actionX + toolbarStep;
        ButtonWidget modeButton = addButton(gx, y, toolbarButtonWidth, tr("screen.tropimon_damage_calc.mode",
                tr(state.field.doubles ? "screen.tropimon_damage_calc.double" : "screen.tropimon_damage_calc.single")), button -> {
            state.field.doubles = !state.field.doubles;
            if (!state.field.doubles) {
                state.field.helpingHand = false;
                state.field.friendGuard = false;
                state.field.attackerSide.helpingHand = false;
                state.field.attackerSide.friendGuard = false;
                state.field.defenderSide.helpingHand = false;
                state.field.defenderSide.friendGuard = false;
                clearDoublesSide(state.field.attackerSide);
                clearDoublesSide(state.field.defenderSide);
                state.attackerPartnerAbilitySearch = "";
                state.defenderPartnerAbilitySearch = "";
            } else {
                state.field.attackerSide.spreadTargets = 2;
                state.field.defenderSide.spreadTargets = 2;
            }
            reopen();
        });
        modeButton.setTooltip(Tooltip.of(Text.translatable(state.field.doubles
                ? "screen.tropimon_damage_calc.tooltip.single" : "screen.tropimon_damage_calc.tooltip.double")));
        ButtonWidget weatherButton = addButton(gx + toolbarStep, y, toolbarButtonWidth, tr("screen.tropimon_damage_calc.weather",
                tr("screen.tropimon_damage_calc.weather." + state.field.weather.name().toLowerCase(Locale.ROOT))), button -> {
            state.field.weather = DamageCalcState.cycle(List.of(Weather.values()), state.field.weather);
            reopen();
        });
        weatherButton.setTooltip(Tooltip.of(Text.literal(weatherDescription(state.field.weather))));
        ButtonWidget terrainButton = addButton(gx + toolbarStep * 2, y, toolbarButtonWidth, tr("screen.tropimon_damage_calc.terrain",
                tr("screen.tropimon_damage_calc.terrain." + state.field.terrain.name().toLowerCase(Locale.ROOT))), button -> {
            state.field.terrain = DamageCalcState.cycle(List.of(Terrain.values()), state.field.terrain);
            reopen();
        });
        terrainButton.setTooltip(Tooltip.of(Text.literal(terrainDescription(state.field.terrain))));
        ButtonWidget criticalButton = addToggleButton(gx + toolbarStep * 3, y, toolbarButtonWidth, state.field.criticalHit ? "Crit ON" : "Crit OFF", state.field.criticalHit, button -> {
            state.field.criticalHit = !state.field.criticalHit;
            reopen();
        });
        criticalButton.setTooltip(Tooltip.of(Text.translatable("screen.tropimon_damage_calc.tooltip.critical")));
    }

    private SideConditions sideFor(boolean attacker) {
        return attacker ? state.field.attackerSide : state.field.defenderSide;
    }

    private static void clearDoublesSide(SideConditions side) {
        side.helpingHand = false;
        side.friendGuard = false;
        side.wideGuard = false;
        side.partnerAbility = "None";
        side.partnerName = "";
        side.spreadTargets = 1;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int panelW = panelWidth();
        int panelX = (width - panelW) / 2;
        int panelY = 8;
        int leftX = leftX(panelX, panelW);
        int rightX = rightX(panelX, panelW);
        int editorY = editorY();

        int moveY = moveY(editorY);
        int panelBottom = compactLayout() ? height - 2 : Math.min(height - 8, moveY + MOVE_ROW * 4 + 8);
        CobblemonPanelRenderer.draw(context, panelX, panelY, panelW, panelBottom - panelY);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        int profileY = editorY;
        drawPokemonInfo(context, state.attacker, leftX, profileY, delta, "attacker");
        drawPokemonInfo(context, state.defender, rightX, profileY, delta, "defender");
        boolean showStats = !compactLayout() || compactStatsVisible;
        boolean showMoves = !compactLayout() || !compactStatsVisible;
        if (showStats) {
            int statOffset = state.field.doubles ? 22 : 0;
            drawStatHeaders(context, leftX, editorY + 110 + statOffset);
            drawStatHeaders(context, rightX, editorY + 110 + statOffset);
            drawStatTotals(context, state.attacker, state.field.attackerSide, leftX, editorY + 120 + statOffset);
            drawStatTotals(context, state.defender, state.field.defenderSide, rightX, editorY + 120 + statOffset);
        }
        if (showMoves) {
            drawTrimmed(context, tr("screen.tropimon_damage_calc.moves_to", state.defender.species.name()), leftX, moveY - 14, PANEL, 0xFFFFFF55);
            drawTrimmed(context, tr("screen.tropimon_damage_calc.moves_to", state.attacker.species.name()), rightX, moveY - 14, PANEL, 0xFFFFFF55);
        }
        refreshDamageButtons();

        super.render(context, mouseX, mouseY, delta);
        drawActiveButtons(context);
        if (showMoves) {
            drawActiveZMoveButtons(context, leftX, rightX, moveY);
        }
        drawSearchSuggestions(context, mouseX, mouseY);
    }

    private void refreshDamageButtons() {
        long fingerprint = state.calculationFingerprint();
        if (fingerprint == renderedDamageFingerprint) {
            return;
        }
        for (int side = 0; side < damageButtons.length; side++) {
            boolean fromAttacker = side == 0;
            for (int slot = 0; slot < damageButtons[side].length; slot++) {
                ButtonWidget button = damageButtons[side][slot];
                if (button == null) {
                    continue;
                }
                DamageResult result = state.calculateMove(fromAttacker, slot);
                button.setMessage(Text.literal(result == null ? "-" : damageRange(result)));
                if (result != null) {
                    button.setTooltip(Tooltip.of(Text.literal(damageTooltip(result))));
                }
            }
        }
        renderedDamageFingerprint = fingerprint;
    }

    private void drawActiveButtons(DrawContext context) {
        for (ActiveButton button : activeButtons) {
            context.fill(button.x, button.y, button.x + button.w, button.y + 20, 0xD0209E28);
            context.drawBorder(button.x, button.y, button.w, 20, 0xFF70FF72);
            context.drawCenteredTextWithShadow(textRenderer, button.label, button.x + button.w / 2, button.y + 6, 0xFFFFFFFF);
        }
    }

    private void drawActiveZMoveButtons(DrawContext context, int leftX, int rightX, int moveY) {
        drawActiveZMoveButtons(context, state.attacker, leftX, moveY);
        drawActiveZMoveButtons(context, state.defender, rightX, moveY);
    }

    private void drawActiveZMoveButtons(DrawContext context, PokemonSet pokemon, int x, int moveY) {
        for (int slot = 0; slot < 4; slot++) {
            if (!pokemon.zMoveAt(slot) || pokemon.moveAt(slot) == null) {
                continue;
            }
            int buttonX = x + MOVE_Z_X;
            int buttonY = moveY + slot * MOVE_ROW;
            context.fill(buttonX, buttonY, buttonX + MOVE_Z_WIDTH, buttonY + 20, 0xD0209E28);
            context.drawBorder(buttonX, buttonY, MOVE_Z_WIDTH, 20, 0xFF70FF72);
            context.drawCenteredTextWithShadow(textRenderer, "Z", buttonX + MOVE_Z_WIDTH / 2,
                    buttonY + 6, 0xFFFFFFFF);
        }
    }

    private void drawPokemonInfo(DrawContext context, PokemonSet pokemon, int x, int y,
                                 float frameDelta, String animationSlot) {
        drawPokemonTexture(context, pokemon, x, y, frameDelta, animationSlot);
        drawTypeIcons(context, pokemon.defensiveTypes(), x + 214, y, 18);
    }

    private int drawTypeIcons(DrawContext context, List<PokeType> types, int x, int y, int size) {
        int currentX = x;
        for (PokeType type : types) {
            if (type == PokeType.NONE) {
                continue;
            }
            TypeIconRenderer.draw(context, type, currentX, y, size);
            currentX += size + 3;
        }
        return currentX;
    }

    private void drawPokemonTexture(DrawContext context, PokemonSet pokemon, int x, int y,
                                    float frameDelta, String animationSlot) {
        CobblemonPanelRenderer.draw(context, x, y, 56, 56);
        if (!CobblemonPokemonProfileRenderer.drawAnimated(
                context, pokemon.species, x + 2, y + 2, 52, frameDelta, animationSlot)) {
            drawTrimmed(context, tr("screen.tropimon_damage_calc.model_unavailable"),
                    x + 4, y + 24, 48, 0xFFB0B0B0);
        }
        if (!pokemon.itemKnown || !pokemon.abilityKnown || !pokemon.natureKnown || !pokemon.statsKnown) {
            drawTrimmed(context, "?", x + 47, y + 3, 8, 0xFFFFD166);
        }
    }

    private void drawPokemonMiniTexture(DrawContext context, SpeciesData species, int x, int y, int size) {
        context.fill(x, y, x + size, y + size, 0xD014171C);
        if (!CobblemonPokemonProfileRenderer.draw(context, species, x, y, size)) {
            context.drawBorder(x, y, size, size, 0xFF606060);
        }
    }

    private void drawStatHeaders(DrawContext context, int x, int y) {
        drawTrimmed(context, "Stat", x, y, 32, 0xFFBEEBC9);
        drawTrimmed(context, "EV", x + 36, y, 28, 0xFFBEEBC9);
        drawTrimmed(context, "IV", x + 68, y, 28, 0xFFBEEBC9);
        drawTrimmed(context, "Boost", x + 100, y, 42, 0xFFBEEBC9);
        drawTrimmed(context, "Total", x + 146, y, 48, 0xFFBEEBC9);
    }

    private void drawStatTotals(DrawContext context, PokemonSet pokemon, SideConditions side, int x, int y) {
        for (int index = 0; index < STATS.length; index++) {
            Stat stat = STATS[index];
            int value = stat == Stat.HP
                    ? pokemon.maxHp()
                    : DamageCalculator.displayedStat(pokemon, stat, state.field, side);
            String text = String.valueOf(value);
            int textX = x + 170 - textRenderer.getWidth(text) / 2;
            context.drawTextWithShadow(textRenderer, text, textX, y + index * ROW + 4,
                    statValueColor(stat, value, pokemon.level));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        TropimonDamageCalcClient.debug("mouseClicked x=" + (int) mouseX + " y=" + (int) mouseY + " button=" + button
                + " open=" + openKind + " active=" + activeKind + " rendered=" + renderedSuggestions.size());
        if (applyClickedSuggestion((int) mouseX, (int) mouseY)) {
            TropimonDamageCalcClient.debug("mouseClicked suggestion handled");
            return true;
        }

        SearchField clickedField = searchFieldAt((int) mouseX, (int) mouseY);
        if (clickedField != null && button == 1 && clearSelection(clickedField.kind)) {
            TropimonDamageCalcClient.debug("search field cleared kind=" + clickedField.kind);
            reopen();
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);

        if (clickedField != null) {
            if (openKind != clickedField.kind) {
                suggestionScroll = 0;
                selectedSuggestion = -1;
            }
            activeKind = clickedField.kind;
            openKind = clickedField.kind;
            clickedField.widget.setFocused(true);
            TropimonDamageCalcClient.debug("search field opened kind=" + clickedField.kind);
            return true;
        }

        if (!handled) {
            activeKind = null;
            openKind = null;
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        TropimonDamageCalcClient.debug("mouseReleased x=" + (int) mouseX + " y=" + (int) mouseY + " button=" + button
                + " open=" + openKind + " active=" + activeKind + " rendered=" + renderedSuggestions.size());
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        SearchField field = field(openKind);
        if (field != null) {
            List<Suggestion> suggestions = suggestionsForField(field);
            int visible = visibleSuggestionCount(suggestions);
            if (suggestions.size() > visible) {
                int maxStart = suggestions.size() - visible;
                suggestionScroll = Math.max(0, Math.min(maxStart, suggestionScroll - (int) Math.signum(verticalAmount)));
                selectedSuggestion = -1;
                return true;
            }
            return true;
        }
        if (compactLayout() && height < 360) {
            int maxScroll = 360 - height;
            int previous = verticalScroll;
            verticalScroll = Math.max(0, Math.min(maxScroll,
                    verticalScroll - (int) Math.signum(verticalAmount) * 18));
            if (verticalScroll != previous) {
                reopen();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 261) {
            SearchField field = activeSearchField();
            if (field != null && clearSelection(field.kind)) {
                reopen();
                return true;
            }
        }
        if (keyCode == 264 || keyCode == 265) {
            SearchField field = activeSearchField();
            if (field != null && moveSuggestionSelection(field, keyCode == 264 ? 1 : -1)) {
                return true;
            }
        }
        if (keyCode == 257 || keyCode == 335) {
            SearchField field = activeSearchField();
            if (field != null) {
                List<Suggestion> suggestions = suggestionsForField(field);
                if (!suggestions.isEmpty()) {
                    int index = selectedSuggestion >= 0 && selectedSuggestion < suggestions.size() ? selectedSuggestion : 0;
                    return applySuggestion(suggestions.get(index));
                }
                if (applySearchValue(field.kind)) {
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void drawSearchSuggestions(DrawContext context, int mouseX, int mouseY) {
        renderedSuggestions.clear();
        SearchField field = field(openKind);
        if (field == null) {
            return;
        }
        List<Suggestion> suggestions = suggestionsForField(field);
        if (suggestions.isEmpty()) {
            return;
        }
        int visible = visibleSuggestionCount(suggestions);
        int start = suggestionStart(suggestions);
        int w = suggestionWidth(field);
        int rowHeight = suggestionRowHeight(field);
        int h = visible * rowHeight + 2;
        int x = field.widget.getX();
        int y = suggestionY(field, h);
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 500);
        context.fill(x, y, x + w, y + h, 0xFF101014);
        context.drawBorder(x, y, w, h, 0xFF48D597);
        for (int i = 0; i < visible; i++) {
            Suggestion suggestion = suggestions.get(start + i);
            int rowY = y + 1 + i * rowHeight;
            int color = inside(mouseX, mouseY, x, rowY, w, rowHeight) || start + i == selectedSuggestion
                    ? 0xFF2F6B58 : 0xFF23242A;
            context.fill(x + 1, rowY, x + w - 1, rowY + rowHeight - 1, color);
            int textColor = suggestion.hidden() ? 0xFFFFD166 : 0xFFFFFFFF;
            drawSuggestionRow(context, suggestion, x, rowY, w, rowHeight, textColor);
            renderedSuggestions.add(new RenderedSuggestion(x, rowY, w, rowHeight, suggestion));
        }
        if (suggestions.size() > visible) {
            int trackX = x + w - 4;
            int trackHeight = h - 4;
            int thumbHeight = Math.max(10, trackHeight * visible / suggestions.size());
            int maxStart = suggestions.size() - visible;
            int thumbY = y + 2 + (trackHeight - thumbHeight) * start / Math.max(1, maxStart);
            context.fill(trackX, y + 2, trackX + 2, y + h - 2, 0xFF3A3B42);
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFF70DFAE);
        }
        context.getMatrices().pop();
    }

    private void drawSuggestionRow(DrawContext context, Suggestion suggestion, int x, int y, int w, int h, int textColor) {
        if (suggestion.species() != null) {
            int iconSize = Math.min(18, h - 2);
            drawPokemonMiniTexture(context, suggestion.species(), x + 3, y + 1, iconSize);
            drawTrimmed(context, suggestion.label(), x + 25, y + 6, w - 30, textColor);
            return;
        }
        if (suggestion.move() != null) {
            MoveData move = suggestion.move();
            drawTrimmed(context, moveDisplayName(move), x + 5, y + 5, 116, textColor);
            TypeIconRenderer.draw(context, move.type(), x + 126, y + 1, 16);
            drawTrimmed(context, move.basePower() + " "
                    + tr("screen.tropimon_damage_calc.category." + move.category().name().toLowerCase(Locale.ROOT)),
                    x + 146, y + 5, w - 151, textColor);
            return;
        }
        if (suggestion.item() != null) {
            boolean icon = GameItemIconRenderer.draw(context, suggestion.item(), x + 2, y + 1);
            drawTrimmed(context, suggestion.label(), x + (icon ? 22 : 5), y + 5,
                    w - (icon ? 27 : 10), textColor);
            return;
        }
        drawTrimmed(context, suggestion.label(), x + 5, y + 5, w - 10, textColor);
    }

    private boolean applyClickedSuggestion(int mouseX, int mouseY) {
        for (RenderedSuggestion rendered : renderedSuggestions) {
            if (inside(mouseX, mouseY, rendered.x, rendered.y, rendered.w, rendered.h)) {
                TropimonDamageCalcClient.debug("rendered suggestion clicked label=" + rendered.suggestion.label);
                return applySuggestion(rendered.suggestion);
            }
        }
        SearchField openField = field(openKind);
        if (openField != null && applyClickedSuggestion(openField, mouseX, mouseY)) {
            TropimonDamageCalcClient.debug("open field suggestion clicked kind=" + openField.kind);
            return true;
        }
        return false;
    }

    private boolean applyClickedSuggestion(SearchField field, int mouseX, int mouseY) {
        List<Suggestion> suggestions = suggestionsForField(field);
        if (suggestions.isEmpty()) {
            return false;
        }
        int visible = visibleSuggestionCount(suggestions);
        int start = suggestionStart(suggestions);
        int w = suggestionWidth(field);
        int rowHeight = suggestionRowHeight(field);
        int h = visible * rowHeight + 2;
        int x = field.widget.getX();
        int y = suggestionY(field, h);
        for (int i = 0; i < visible; i++) {
            if (inside(mouseX, mouseY, x, y + 1 + i * rowHeight, w, rowHeight)) {
                Suggestion suggestion = suggestions.get(start + i);
                TropimonDamageCalcClient.debug("computed suggestion clicked kind=" + field.kind + " label=" + suggestion.label()
                        + " rect=" + x + "," + (y + 1 + i * rowHeight) + "," + w + "," + rowHeight);
                return applySuggestion(suggestion);
            }
        }
        return false;
    }

    private int suggestionRowHeight(SearchField field) {
        return field.kind == SearchKind.ATTACKER_POKEMON || field.kind == SearchKind.DEFENDER_POKEMON
                ? POKEMON_SUGGESTION_ROW_HEIGHT : SUGGESTION_ROW_HEIGHT;
    }

    private int visibleSuggestionCount(List<Suggestion> suggestions) {
        return Math.min(MAX_VISIBLE_SUGGESTIONS, suggestions.size());
    }

    private int suggestionStart(List<Suggestion> suggestions) {
        int maxStart = Math.max(0, suggestions.size() - visibleSuggestionCount(suggestions));
        suggestionScroll = Math.max(0, Math.min(suggestionScroll, maxStart));
        return suggestionScroll;
    }

    private boolean moveSuggestionSelection(SearchField field, int direction) {
        List<Suggestion> suggestions = suggestionsForField(field);
        if (suggestions.isEmpty()) {
            selectedSuggestion = -1;
            return false;
        }
        if (selectedSuggestion < 0) {
            selectedSuggestion = direction > 0 ? 0 : suggestions.size() - 1;
        } else {
            selectedSuggestion = Math.floorMod(selectedSuggestion + direction, suggestions.size());
        }
        int visible = visibleSuggestionCount(suggestions);
        if (selectedSuggestion < suggestionScroll) {
            suggestionScroll = selectedSuggestion;
        } else if (selectedSuggestion >= suggestionScroll + visible) {
            suggestionScroll = selectedSuggestion - visible + 1;
        }
        return true;
    }

    private int suggestionWidth(SearchField field) {
        int width = switch (field.kind) {
            case ATTACKER_POKEMON, DEFENDER_POKEMON -> Math.max(field.widget.getWidth(), 200);
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
                 DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 -> 220;
            case ATTACKER_ITEM, DEFENDER_ITEM -> field.widget.getWidth();
            case ATTACKER_ABILITY, DEFENDER_ABILITY,
                 ATTACKER_PARTNER_ABILITY, DEFENDER_PARTNER_ABILITY,
                 ATTACKER_NATURE, DEFENDER_NATURE ->
                    Math.max(field.widget.getWidth(), 150);
        };
        return Math.min(width, this.width - field.widget.getX() - 8);
    }

    private int suggestionY(SearchField field, int height) {
        int below = field.widget.getY() + field.widget.getHeight() + 2;
        if (below + height <= this.height - 8) {
            return below;
        }
        return Math.max(8, field.widget.getY() - height - 2);
    }

    private SearchField activeSearchField() {
        for (SearchField candidate : searchFields) {
            if (candidate.widget.isFocused()) {
                activeKind = candidate.kind;
                openKind = candidate.kind;
                return candidate;
            }
        }
        SearchField field = field(activeKind);
        if (field != null) {
            return field;
        }
        return null;
    }

    private SearchField searchFieldAt(int x, int y) {
        for (SearchField field : searchFields) {
            if (inside(x, y, field.widget.getX(), field.widget.getY(), field.widget.getWidth(), field.widget.getHeight())) {
                return field;
            }
        }
        return null;
    }

    private boolean applySearchValue(SearchKind kind) {
        if (kind == null) {
            return false;
        }
        boolean applied = switch (kind) {
            case ATTACKER_POKEMON -> selectSpeciesByQuery(state.attackerSearch, true);
            case DEFENDER_POKEMON -> selectSpeciesByQuery(state.defenderSearch, false);
            case ATTACKER_ITEM -> selectItemByQuery(state.attackerItemSearch, true);
            case DEFENDER_ITEM -> selectItemByQuery(state.defenderItemSearch, false);
            case ATTACKER_ABILITY -> selectAbilityByQuery(state.attackerAbilitySearch, true);
            case DEFENDER_ABILITY -> selectAbilityByQuery(state.defenderAbilitySearch, false);
            case ATTACKER_PARTNER_ABILITY -> selectPartnerAbilityByQuery(state.attackerPartnerAbilitySearch, true);
            case DEFENDER_PARTNER_ABILITY -> selectPartnerAbilityByQuery(state.defenderPartnerAbilitySearch, false);
            case ATTACKER_NATURE -> selectNatureByQuery(state.attackerNatureSearch, true);
            case DEFENDER_NATURE -> selectNatureByQuery(state.defenderNatureSearch, false);
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
                 DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 ->
                    selectMoveByQuery(searchText(kind), isAttackerMove(kind), moveSlot(kind));
        };
        if (applied) {
            activeKind = null;
            openKind = null;
            reopen();
        }
        return applied;
    }

    private boolean applyExactSearchValue(SearchKind kind, String text) {
        String normalized = TropimonDex.normalize(text);
        if (normalized.isBlank()) {
            return false;
        }
        boolean applied = switch (kind) {
            case ATTACKER_POKEMON -> exactSpecies(state.attacker, normalized) ? false : selectExactSpecies(text, true);
            case DEFENDER_POKEMON -> exactSpecies(state.defender, normalized) ? false : selectExactSpecies(text, false);
            case ATTACKER_ITEM -> exactText(state.attacker.item, normalized) ? false : selectExactItem(text, true);
            case DEFENDER_ITEM -> exactText(state.defender.item, normalized) ? false : selectExactItem(text, false);
            case ATTACKER_ABILITY -> exactText(state.attacker.ability, normalized) ? false : selectExactAbility(text, true);
            case DEFENDER_ABILITY -> exactText(state.defender.ability, normalized) ? false : selectExactAbility(text, false);
            case ATTACKER_PARTNER_ABILITY -> exactText(state.field.attackerSide.partnerAbility, normalized)
                    ? false : selectExactPartnerAbility(text, true);
            case DEFENDER_PARTNER_ABILITY -> exactText(state.field.defenderSide.partnerAbility, normalized)
                    ? false : selectExactPartnerAbility(text, false);
            case ATTACKER_NATURE -> exactText(state.attacker.nature.name(), normalized) ? false : selectExactNature(text, true);
            case DEFENDER_NATURE -> exactText(state.defender.nature.name(), normalized) ? false : selectExactNature(text, false);
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
                 DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 ->
                    exactMove(kind, normalized) ? false : selectExactMove(text, isAttackerMove(kind), moveSlot(kind));
        };
        if (applied) {
            activeKind = null;
            openKind = null;
            reopen();
        }
        return applied;
    }

    private boolean applySuggestion(Suggestion suggestion) {
        TropimonDamageCalcClient.debug("applySuggestion label=" + suggestion.label);
        suggestion.apply().run();
        activeKind = null;
        openKind = null;
        selectedSuggestion = -1;
        reopen();
        return true;
    }

    private List<Suggestion> suggestionsForField(SearchField field) {
        String query = suggestionQuery(field);
        String normalizedQuery = TropimonDex.normalize(query);
        String context = suggestionContext(field.kind);
        long now = System.currentTimeMillis();
        CachedSuggestions cached = suggestionCache.get(field.kind);
        if (cached != null && cached.matches(normalizedQuery, context, now)) {
            return cached.values();
        }
        List<Suggestion> values = switch (field.kind) {
            case ATTACKER_POKEMON -> speciesSuggestions(query, true);
            case DEFENDER_POKEMON -> speciesSuggestions(query, false);
            case ATTACKER_ITEM -> itemSuggestions(query, true);
            case DEFENDER_ITEM -> itemSuggestions(query, false);
            case ATTACKER_ABILITY -> abilitySuggestions(query, true);
            case DEFENDER_ABILITY -> abilitySuggestions(query, false);
            case ATTACKER_PARTNER_ABILITY -> partnerAbilitySuggestions(query, true);
            case DEFENDER_PARTNER_ABILITY -> partnerAbilitySuggestions(query, false);
            case ATTACKER_NATURE -> natureSuggestions(query, true);
            case DEFENDER_NATURE -> natureSuggestions(query, false);
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
                 DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 ->
                    moveSuggestions(query, isAttackerMove(field.kind), moveSlot(field.kind));
        };
        long expiresAt = isPokemonKind(field.kind) ? now + 1_000L : Long.MAX_VALUE;
        List<Suggestion> immutable = List.copyOf(values);
        suggestionCache.put(field.kind, new CachedSuggestions(normalizedQuery, context, expiresAt, immutable));
        return immutable;
    }

    private String suggestionContext(SearchKind kind) {
        return switch (kind) {
            case ATTACKER_ABILITY -> state.attacker.species.id();
            case DEFENDER_ABILITY -> state.defender.species.id();
            default -> "";
        };
    }

    private static boolean isPokemonKind(SearchKind kind) {
        return kind == SearchKind.ATTACKER_POKEMON || kind == SearchKind.DEFENDER_POKEMON;
    }

    private String suggestionQuery(SearchField field) {
        String text = field.widget.getText();
        String normalized = TropimonDex.normalize(text);
        if (normalized.isBlank()) {
            return "";
        }
        return switch (field.kind) {
            case ATTACKER_POKEMON -> exactSpecies(state.attacker, normalized)
                    || exactText(speciesDisplayName(state.attacker.species), normalized) ? "" : text;
            case DEFENDER_POKEMON -> exactSpecies(state.defender, normalized)
                    || exactText(speciesDisplayName(state.defender.species), normalized) ? "" : text;
            case ATTACKER_ITEM -> exactText(state.attacker.item, normalized)
                    || exactText(itemDisplayName(state.attacker.item), normalized) ? "" : text;
            case DEFENDER_ITEM -> exactText(state.defender.item, normalized)
                    || exactText(itemDisplayName(state.defender.item), normalized) ? "" : text;
            case ATTACKER_ABILITY -> exactText(state.attacker.ability, normalized)
                    || exactText(abilityDisplayName(state.attacker.ability), normalized) ? "" : text;
            case DEFENDER_ABILITY -> exactText(state.defender.ability, normalized)
                    || exactText(abilityDisplayName(state.defender.ability), normalized) ? "" : text;
            case ATTACKER_PARTNER_ABILITY -> exactText(state.field.attackerSide.partnerAbility, normalized)
                    || exactText(abilityDisplayName(state.field.attackerSide.partnerAbility), normalized) ? "" : text;
            case DEFENDER_PARTNER_ABILITY -> exactText(state.field.defenderSide.partnerAbility, normalized)
                    || exactText(abilityDisplayName(state.field.defenderSide.partnerAbility), normalized) ? "" : text;
            case ATTACKER_NATURE -> exactText(state.attacker.nature.name(), normalized)
                    || exactText(natureDisplayName(state.attacker.nature), normalized) ? "" : text;
            case DEFENDER_NATURE -> exactText(state.defender.nature.name(), normalized)
                    || exactText(natureDisplayName(state.defender.nature), normalized) ? "" : text;
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
                 DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 ->
                    exactMove(field.kind, normalized) ? "" : text;
        };
    }

    private List<Suggestion> speciesSuggestions(String query, boolean attacker) {
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        ArrayList<Suggestion> catalogSuggestions = new ArrayList<>();
        Set<String> liveSpecies = new HashSet<>();
        List<PokemonSet> sideParty = attacker
                ? CobblemonBattleDataProvider.playerParty(MinecraftClient.getInstance())
                : CobblemonBattleDataProvider.opponentParty(MinecraftClient.getInstance());
        for (PokemonSet live : sideParty) {
            String label = speciesDisplayName(live.species) + " "
                    + tr("screen.tropimon_damage_calc.level_short", live.level) + " "
                    + tr(attacker ? "screen.tropimon_damage_calc.party_suffix"
                            : "screen.tropimon_damage_calc.opponent_suffix");
            if (!matches(label, query) && !matches(live.species.name(), query)) {
                continue;
            }
            PokemonSet captured = live.copy();
            suggestions.add(Suggestion.species(label, () -> selectLivePokemon(captured, attacker), live.species));
            liveSpecies.add(live.species.id());
        }
        for (SpeciesData species : TropimonDex.speciesList()) {
            if (liveSpecies.contains(species.id())) continue;
            if (!matches(species.name(), query) && !matches(speciesDisplayName(species), query)
                    && !matches(species.id(), query)) continue;
            catalogSuggestions.add(Suggestion.species(speciesDisplayName(species), () -> selectSpecies(species, attacker), species));
        }
        sortSuggestions(catalogSuggestions);
        suggestions.addAll(catalogSuggestions);
        return suggestions;
    }

    private List<Suggestion> itemSuggestions(String query, boolean attacker) {
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        for (String item : TropimonDex.itemList()) {
            String display = itemDisplayName(item);
            if (!matches(item, query) && !matches(display, query)) continue;
            suggestions.add(Suggestion.item(display, () -> selectItem(item, attacker), item));
        }
        sortSuggestions(suggestions);
        return suggestions;
    }

    private List<Suggestion> abilitySuggestions(String query, boolean attacker) {
        PokemonSet pokemon = attacker ? state.attacker : state.defender;
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        for (String ability : TropimonDex.abilityList(pokemon.species)) {
            String display = abilityDisplayName(ability);
            if (!matches(ability, query) && !matches(display, query)) continue;
            suggestions.add(new Suggestion(display, () -> selectAbility(ability, attacker), TropimonDex.isHiddenAbility(pokemon.species, ability)));
        }
        sortSuggestions(suggestions);
        return suggestions;
    }

    private List<Suggestion> partnerAbilitySuggestions(String query, boolean attacker) {
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        for (String ability : TropimonDex.abilityList()) {
            String display = abilityDisplayName(ability);
            if (!matches(ability, query) && !matches(display, query)) continue;
            suggestions.add(new Suggestion(display, () -> selectPartnerAbility(ability, attacker)));
        }
        sortSuggestions(suggestions);
        return suggestions;
    }

    private List<Suggestion> natureSuggestions(String query, boolean attacker) {
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        for (NatureData nature : TropimonDex.natureList()) {
            if (nature.plus() == null && nature.minus() == null && !"serious".equals(nature.id())) continue;
            String display = natureDisplayName(nature);
            if (!matches(nature.name(), query) && !matches(display, query) && !matches(nature.id(), query)) continue;
            suggestions.add(new Suggestion(display, () -> selectNature(nature, attacker)));
        }
        sortSuggestions(suggestions);
        return suggestions;
    }

    private static void sortSuggestions(List<Suggestion> suggestions) {
        suggestions.sort(Comparator.comparing(Suggestion::label, String.CASE_INSENSITIVE_ORDER));
    }

    private List<Suggestion> moveSuggestions(String query, boolean fromAttacker, int slot) {
        ArrayList<Suggestion> suggestions = new ArrayList<>();
        PokemonSet source = fromAttacker ? state.attacker : state.defender;
        for (MoveData move : TropimonDex.moveListFor(source.species)) {
            if (!matches(move.name(), query) && !matches(moveDisplayName(move), query) && !matches(move.id(), query)) continue;
            suggestions.add(Suggestion.move(moveLabel(move), () -> selectMove(move, fromAttacker, slot), move));
        }
        return suggestions;
    }

    private void selectSpecies(SpeciesData species, boolean attacker) {
        TropimonDamageCalcClient.debug("selectSpecies side=" + (attacker ? "attacker" : "defender") + " species=" + species.name());
        PokemonSet pokemon = attacker ? state.attacker : state.defender;
        String previousMegaStone = TropimonDex.megaStoneForSpecies(pokemon.species);
        pokemon.clearBattleContext();
        pokemon.species = species;
        pokemon.ability = TropimonDex.defaultAbility(species);
        pokemon.itemKnown = true;
        pokemon.abilityKnown = true;
        pokemon.natureKnown = true;
        pokemon.statsKnown = true;
        pokemon.movesKnown = true;
        pokemon.moves.clear();
        pokemon.moves.addAll(TropimonDex.defaultMoves(species));
        String megaStone = TropimonDex.megaStoneForSpecies(species);
        if (megaStone != null) {
            pokemon.item = megaStone;
        } else if (previousMegaStone != null && TropimonDex.normalize(previousMegaStone).equals(TropimonDex.normalize(pokemon.item))) {
            pokemon.item = "None";
        }
        if (attacker) {
            state.selectedMoveIndex = 0;
            state.attackerSearch = speciesDisplayName(species);
            state.attackerItemSearch = itemDisplayName(pokemon.item);
            state.attackerAbilitySearch = abilityDisplayName(pokemon.ability);
            state.attackerNatureSearch = natureDisplayName(pokemon.nature);
            if (megaStone != null) {
                state.attackerItemSearch = megaStone;
            }
        } else {
            state.defenderSearch = speciesDisplayName(species);
            state.defenderItemSearch = itemDisplayName(pokemon.item);
            state.defenderAbilitySearch = abilityDisplayName(pokemon.ability);
            state.defenderNatureSearch = natureDisplayName(pokemon.nature);
            if (megaStone != null) {
                state.defenderItemSearch = megaStone;
            }
        }
    }

    private void selectLivePokemon(PokemonSet live, boolean attacker) {
        TropimonDamageCalcClient.debug("selectLivePokemon side=" + (attacker ? "attacker" : "defender") + " species=" + live.species.name());
        PokemonSet copy = live.copy();
        String megaStone = TropimonDex.megaStoneForSpecies(copy.species);
        if (megaStone != null && (TropimonDex.normalize(copy.item).isBlank() || "none".equals(TropimonDex.normalize(copy.item)))) {
            copy.item = megaStone;
        }
        if (attacker) {
            state.attacker = copy;
            state.selectedMoveIndex = 0;
            state.attackerSearch = speciesDisplayName(copy.species);
            state.attackerItemSearch = copy.itemKnown ? itemDisplayName(copy.item) : "";
            state.attackerAbilitySearch = copy.abilityKnown ? abilityDisplayName(copy.ability) : "";
            state.attackerNatureSearch = copy.natureKnown ? natureDisplayName(copy.nature) : "";
        } else {
            state.defender = copy;
            state.defenderSearch = speciesDisplayName(copy.species);
            state.defenderItemSearch = copy.itemKnown ? itemDisplayName(copy.item) : "";
            state.defenderAbilitySearch = copy.abilityKnown ? abilityDisplayName(copy.ability) : "";
            state.defenderNatureSearch = copy.natureKnown ? natureDisplayName(copy.nature) : "";
        }
    }

    private boolean selectSpeciesByQuery(String query, boolean attacker) {
        SpeciesData species = TropimonDex.findSpeciesByQuery(query);
        if (species == null) {
            return false;
        }
        selectSpecies(species, attacker);
        return true;
    }

    private boolean selectExactSpecies(String query, boolean attacker) {
        SpeciesData species = TropimonDex.findSpeciesByQuery(query);
        String normalized = TropimonDex.normalize(query);
        if (species == null || !exactText(species.name(), normalized)
                && !exactText(speciesDisplayName(species), normalized)
                && !exactText(species.id(), normalized)) {
            return false;
        }
        selectSpecies(species, attacker);
        return true;
    }

    private void selectItem(String item, boolean attacker) {
        TropimonDamageCalcClient.debug("selectItem side=" + (attacker ? "attacker" : "defender") + " item=" + item);
        if (attacker) {
            state.attacker.item = item;
            state.attacker.itemKnown = true;
            state.attackerItemSearch = item;
        } else {
            state.defender.item = item;
            state.defender.itemKnown = true;
            state.defenderItemSearch = item;
        }
    }

    private boolean selectItemByQuery(String query, boolean attacker) {
        String item = TropimonDex.findItemByQuery(query);
        if (item == null) {
            return false;
        }
        selectItem(item, attacker);
        return true;
    }

    private boolean selectExactItem(String query, boolean attacker) {
        String item = TropimonDex.findItemByQuery(query);
        String normalized = TropimonDex.normalize(query);
        if (item == null || !exactText(item, normalized) && !exactText(itemDisplayName(item), normalized)) {
            return false;
        }
        selectItem(item, attacker);
        return true;
    }

    private void selectAbility(String ability, boolean attacker) {
        TropimonDamageCalcClient.debug("selectAbility side=" + (attacker ? "attacker" : "defender") + " ability=" + ability);
        if (attacker) {
            state.attacker.ability = ability;
            state.attacker.abilityKnown = true;
            state.attackerAbilitySearch = ability;
        } else {
            state.defender.ability = ability;
            state.defender.abilityKnown = true;
            state.defenderAbilitySearch = ability;
        }
    }

    private boolean selectAbilityByQuery(String query, boolean attacker) {
        PokemonSet pokemon = attacker ? state.attacker : state.defender;
        String ability = TropimonDex.findAbilityByQuery(pokemon.species, query);
        if (ability == null) {
            return false;
        }
        selectAbility(ability, attacker);
        return true;
    }

    private boolean selectExactAbility(String query, boolean attacker) {
        PokemonSet pokemon = attacker ? state.attacker : state.defender;
        String ability = TropimonDex.findAbilityByQuery(pokemon.species, query);
        String normalized = TropimonDex.normalize(query);
        if (ability == null || !exactText(ability, normalized)
                && !exactText(abilityDisplayName(ability), normalized)) {
            return false;
        }
        selectAbility(ability, attacker);
        return true;
    }

    private void selectPartnerAbility(String ability, boolean attacker) {
        SideConditions side = sideFor(attacker);
        side.partnerAbility = ability;
        if (attacker) state.attackerPartnerAbilitySearch = abilityDisplayName(ability);
        else state.defenderPartnerAbilitySearch = abilityDisplayName(ability);
    }

    private boolean selectPartnerAbilityByQuery(String query, boolean attacker) {
        String ability = TropimonDex.findAbilityByQuery(query);
        if (ability == null) return false;
        selectPartnerAbility(ability, attacker);
        return true;
    }

    private boolean selectExactPartnerAbility(String query, boolean attacker) {
        String ability = TropimonDex.findAbilityByQuery(query);
        String normalized = TropimonDex.normalize(query);
        if (ability == null || !exactText(ability, normalized)
                && !exactText(abilityDisplayName(ability), normalized)) {
            return false;
        }
        selectPartnerAbility(ability, attacker);
        return true;
    }

    private void selectNature(NatureData nature, boolean attacker) {
        TropimonDamageCalcClient.debug("selectNature side=" + (attacker ? "attacker" : "defender") + " nature=" + nature.name());
        if (attacker) {
            state.attacker.nature = nature;
            state.attacker.natureKnown = true;
            state.attackerNatureSearch = natureDisplayName(nature);
        } else {
            state.defender.nature = nature;
            state.defender.natureKnown = true;
            state.defenderNatureSearch = natureDisplayName(nature);
        }
    }

    private boolean selectNatureByQuery(String query, boolean attacker) {
        NatureData nature = TropimonDex.findNatureByQuery(query);
        if (nature == null) {
            return false;
        }
        selectNature(nature, attacker);
        return true;
    }

    private boolean selectExactNature(String query, boolean attacker) {
        NatureData nature = TropimonDex.findNatureByQuery(query);
        String normalized = TropimonDex.normalize(query);
        if (nature == null || !exactText(nature.name(), normalized)
                && !exactText(natureDisplayName(nature), normalized)) {
            return false;
        }
        selectNature(nature, attacker);
        return true;
    }

    private void selectMove(MoveData move, boolean fromAttacker, int slot) {
        TropimonDamageCalcClient.debug("selectMove side=" + (fromAttacker ? "attacker" : "defender") + " slot=" + slot + " move=" + move.name());
        PokemonSet source = fromAttacker ? state.attacker : state.defender;
        source.setMove(slot, move);
        state.selectedMoveAttacker = fromAttacker;
        state.selectedMoveIndex = slot;
    }

    private boolean clearSelection(SearchKind kind) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case ATTACKER_POKEMON -> {
                selectSpecies(TropimonDex.species("abomasnow"), true);
                state.attackerSearch = "";
                yield true;
            }
            case DEFENDER_POKEMON -> {
                selectSpecies(TropimonDex.species("abomasnow"), false);
                state.defenderSearch = "";
                yield true;
            }
            case ATTACKER_ITEM -> {
                selectItem("None", true);
                state.attackerItemSearch = "";
                yield true;
            }
            case DEFENDER_ITEM -> {
                selectItem("None", false);
                state.defenderItemSearch = "";
                yield true;
            }
            case ATTACKER_ABILITY -> {
                selectAbility("None", true);
                state.attackerAbilitySearch = "";
                yield true;
            }
            case DEFENDER_ABILITY -> {
                selectAbility("None", false);
                state.defenderAbilitySearch = "";
                yield true;
            }
            case ATTACKER_PARTNER_ABILITY -> {
                selectPartnerAbility("None", true);
                state.attackerPartnerAbilitySearch = "";
                yield true;
            }
            case DEFENDER_PARTNER_ABILITY -> {
                selectPartnerAbility("None", false);
                state.defenderPartnerAbilitySearch = "";
                yield true;
            }
            case ATTACKER_NATURE -> {
                selectNature(TropimonDex.nature("serious"), true);
                state.attackerNatureSearch = "";
                yield true;
            }
            case DEFENDER_NATURE -> {
                selectNature(TropimonDex.nature("serious"), false);
                state.defenderNatureSearch = "";
                yield true;
            }
            case ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3 -> {
                state.attacker.deleteMove(moveSlot(kind));
                yield true;
            }
            case DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3 -> {
                state.defender.deleteMove(moveSlot(kind));
                yield true;
            }
        };
    }

    private boolean selectMoveByQuery(String query, boolean fromAttacker, int slot) {
        MoveData move = TropimonDex.findMoveByQuery(query);
        if (move == null) {
            return false;
        }
        selectMove(move, fromAttacker, slot);
        return true;
    }

    private boolean selectExactMove(String query, boolean fromAttacker, int slot) {
        MoveData move = TropimonDex.findMoveByQuery(query);
        String normalized = TropimonDex.normalize(query);
        if (move == null || !exactText(move.name(), normalized)
                && !exactText(moveDisplayName(move), normalized)
                && !exactText(move.id(), normalized)) {
            return false;
        }
        selectMove(move, fromAttacker, slot);
        return true;
    }

    private void setFromTarget(boolean attacker) {
        PokemonSet live = CobblemonBattleDataProvider.targetPokemon(MinecraftClient.getInstance());
        if (live == null) return;
        live.ability = TropimonDex.defaultAbility(live.species);
        if (attacker) {
            state.setAttackerFromLive(live);
            state.attackerSearch = live.species.name();
            state.attackerAbilitySearch = live.ability;
        } else {
            state.setDefenderFromLive(live);
            state.defenderSearch = live.species.name();
            state.defenderAbilitySearch = live.ability;
        }
        reopen();
    }

    private void replaceSelectedMove(boolean forward) {
        List<MoveData> moves = TropimonDex.moveList();
        if (moves.isEmpty()) return;
        MoveData current = state.selectedMove();
        MoveData next = forward ? DamageCalcState.cycle(moves, current) : DamageCalcState.cycleBack(moves, current);
        selectMove(next, state.selectedMoveAttacker, state.selectedMoveIndex);
    }

    private ButtonWidget addButton(int x, int y, int w, String label, ButtonWidget.PressAction action) {
        return addButton(x, y, w, 20, label, action);
    }

    private ButtonWidget addButton(int x, int y, int w, int h, String label, ButtonWidget.PressAction action) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, h).build();
        addDrawableChild(button);
        return button;
    }

    private ButtonWidget addToggleButton(int x, int y, int w, String label, boolean active, ButtonWidget.PressAction action) {
        ButtonWidget button = addButton(x, y, w, label, action);
        if (active) {
            activeButtons.add(new ActiveButton(x, y, w, label));
        }
        return button;
    }

    private void addClearButton(int x, int y, SearchKind kind) {
        addButton(x, y, 22, "X", button -> {
            clearSelection(kind);
            reopen();
        });
    }

    private TextFieldWidget addSearchField(SearchKind kind, int x, int y, int w, String value, String placeholder, Consumer<String> onChanged) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, 20, Text.literal(placeholder));
        field.setMaxLength(64);
        field.setText(value);
        field.setPlaceholder(Text.literal(placeholder));
        field.setChangedListener(text -> {
            activeKind = kind;
            openKind = kind;
            suggestionScroll = 0;
            onChanged.accept(text);
        });
        addDrawableChild(field);
        searchFields.add(new SearchField(kind, field));
        return field;
    }

    private void addNumberField(int x, int y, int w, int value, int min, int max, Consumer<Integer> onChanged) {
        addNumberField(x, y, w, value, min, max, false, onChanged);
    }

    private void addNumberField(int x, int y, int w, int value, int min, int max, boolean blankWhenZero, Consumer<Integer> onChanged) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, 16, Text.literal(""));
        field.setMaxLength(3);
        field.setTextPredicate(text -> {
            if (text.isBlank()) {
                return true;
            }
            try {
                int parsed = Integer.parseInt(text);
                return parsed >= min && parsed <= max;
            } catch (NumberFormatException ignored) {
                return false;
            }
        });
        field.setText(blankWhenZero && value == 0 ? "" : String.valueOf(value));
        field.setChangedListener(text -> {
            if (text.isBlank()) {
                onChanged.accept(0);
                return;
            }
            try {
                int parsed = Math.max(min, Math.min(max, Integer.parseInt(text)));
                onChanged.accept(parsed);
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(field);
    }

    private void openSearch(SearchKind kind) {
        activeKind = kind;
        openKind = kind;
        selectedSuggestion = -1;
        SearchField field = field(kind);
        if (field != null) field.widget.setFocused(true);
    }

    private SearchField field(SearchKind kind) {
        if (kind == null) return null;
        for (SearchField field : searchFields) {
            if (field.kind == kind) return field;
        }
        return null;
    }

    private void drawTrimmed(DrawContext context, String value, int x, int y, int w, int color) {
        context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(value, w)), x, y, color);
    }

    private int editorY() {
        if (compactLayout()) {
            return 64 - verticalScroll;
        }
        verticalScroll = 0;
        return 64;
    }

    private boolean compactLayout() {
        return height < (state.field.doubles ? 470 : 440);
    }

    private int moveY(int editorY) {
        return Math.min(editorY + (state.field.doubles ? 262 : 240), height - 88);
    }

    private int leftX(int panelX, int panelW) {
        return panelX + panelW / 2 - PANEL - 8;
    }

    private int rightX(int panelX, int panelW) {
        return panelX + panelW / 2 + 8;
    }

    private static int nextLevel(int level) {
        if (level == 100) return 50;
        if (level == 50) return 5;
        return 100;
    }

    private void reopen() {
        activeKind = null;
        openKind = null;
        suggestionScroll = 0;
        selectedSuggestion = -1;
        suggestionCache.clear();
        renderedSuggestions.clear();
        clearAndInit();
    }

    private int panelWidth() {
        return Math.min(604, Math.max(592, width - 24));
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static boolean matches(String value, String query) {
        String normalizedQuery = TropimonDex.normalize(query);
        return normalizedQuery.isBlank() || TropimonDex.normalize(value).contains(normalizedQuery);
    }

    private static boolean exactSpecies(PokemonSet pokemon, String normalized) {
        return pokemon != null && (pokemon.species.id().equals(normalized) || exactText(pokemon.species.name(), normalized));
    }

    private boolean exactMove(SearchKind kind, String normalized) {
        PokemonSet source = isAttackerMove(kind) ? state.attacker : state.defender;
        MoveData move = source.moveAt(moveSlot(kind));
        return move != null && (exactText(move.name(), normalized) || exactText(moveDisplayName(move), normalized));
    }

    private static boolean exactText(String value, String normalized) {
        return TropimonDex.normalize(value).equals(normalized);
    }

    private static int nextBoost(int value) {
        return value >= 6 ? -6 : value + 1;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.format(Locale.ROOT, "%d", value);
    }

    private static String statLabel(Stat stat) {
        return switch (stat) {
            case HP -> "PV";
            case ATK -> "Atk";
            case DEF -> "Def";
            case SPA -> "SpA";
            case SPD -> "SpD";
            case SPE -> "Vit";
        };
    }

    private static int statValueColor(Stat stat, int value, int level) {
        double scale = Math.max(0.05, level / 100.0);
        int low = stat == Stat.HP ? Math.max(18, (int) Math.round(180 * scale)) : Math.max(8, (int) Math.round(80 * scale));
        int high = stat == Stat.HP ? Math.max(40, (int) Math.round(360 * scale)) : Math.max(25, (int) Math.round(280 * scale));
        double t = Math.max(0.0, Math.min(1.0, (value - low) / (double) (high - low)));
        int red = 0xFF5555;
        int yellow = 0xFFFF55;
        int green = 0x55FF55;
        return 0xFF000000 | (t < 0.5
                ? interpolateColor(red, yellow, t * 2.0)
                : interpolateColor(yellow, green, (t - 0.5) * 2.0));
    }

    private static int interpolateColor(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static String moveLabel(MoveData move) {
        return moveDisplayName(move) + " | " + move.basePower() + " "
                + tr("screen.tropimon_damage_calc.category." + move.category().name().toLowerCase(Locale.ROOT));
    }

    private static String damageRange(DamageResult result) {
        return Math.round(result.minPercent()) + "-" + Math.round(result.maxPercent()) + "%";
    }

    private static String damageTooltip(DamageResult result) {
        String base = tr("screen.tropimon_damage_calc.damage", result.minDamage() + "-" + result.maxDamage(),
                damageRange(result), localizedKoChance(result.koChance()));
        MoveData move = result.move();
        if (Set.of("fissure", "guillotine", "horndrill", "sheercold").contains(move.id())) {
            base += "\n" + tr("diagnostic.tropimon_damage_calc.ohko");
        } else if (move.hasFlag("multiaccuracy")) {
            base += "\n" + tr("diagnostic.tropimon_damage_calc.accuracy_per_hit",
                    DamageCalculator.moveAccuracy(move));
        } else if (DamageCalculator.moveAccuracy(move) < 100) {
            base += "\n" + tr("diagnostic.tropimon_damage_calc.accuracy",
                    DamageCalculator.moveAccuracy(move));
        }
        if (result.warnings().isEmpty()) return base;
        return base + "\n[!] " + result.warnings().stream()
                .map(DamageCalcScreen::warningText)
                .collect(java.util.stream.Collectors.joining("\n[!] "));
    }

    private static String warningText(CalcWarning warning) {
        return tr("warning.tropimon_damage_calc." + warning.key(), warning.arguments().toArray());
    }

    private static String tr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }

    private static String natureDescription(NatureData nature) {
        String translated = translatedFirst("cobblemon.nature." + nature.id() + ".desc", "nature." + nature.id() + ".desc");
        if (!translated.isBlank()) return translated;
        if (nature.plus() == null || nature.minus() == null) {
            return tr("description.tropimon_damage_calc.nature.neutral", nature.name());
        }
        return tr("description.tropimon_damage_calc.nature.changed", nature.name(),
                statLabel(nature.plus()), statLabel(nature.minus()));
    }

    private static String natureDisplayName(NatureData nature) {
        String translated = translatedFirst("cobblemon.nature." + nature.id());
        return translated.isBlank() ? nature.name() : translated;
    }

    private static String typeDisplayName(PokeType type) {
        if (type == PokeType.NONE) return "-";
        String translated = translatedFirst("cobblemon.type." + type.name().toLowerCase(Locale.ROOT));
        return translated.isBlank() ? type.displayName() : translated;
    }

    private static String localizedSelectionValue(String search, String canonical, String localized) {
        String normalizedSearch = TropimonDex.normalize(search);
        return normalizedSearch.isBlank() || normalizedSearch.equals(TropimonDex.normalize(canonical))
                || normalizedSearch.equals(TropimonDex.normalize(localized)) ? localized : search;
    }

    private static String speciesDisplayName(SpeciesData species) {
        String direct = translatedFirst("cobblemon.species." + species.id() + ".name");
        if (!direct.isBlank()) {
            return direct;
        }
        String base = translatedFirst("cobblemon.species." + species.cobblemonSpeciesId() + ".name");
        return species.id().equals(species.cobblemonSpeciesId()) && !base.isBlank() ? base : species.name();
    }

    private static String itemDisplayName(String item) {
        String normalized = TropimonDex.normalize(item);
        if (normalized.isBlank() || normalized.equals("none")) {
            return tr("screen.tropimon_damage_calc.none");
        }
        String translated = translatedFirst("item.cobblemon." + snakeId(item), "item.cobblemon." + normalized);
        return translated.isBlank() ? item : translated;
    }

    private static String abilityDisplayName(String ability) {
        String normalized = TropimonDex.normalize(ability);
        if (normalized.isBlank() || normalized.equals("none")) {
            return tr("screen.tropimon_damage_calc.none");
        }
        String translated = translatedFirst("cobblemon.ability." + normalized);
        return translated.isBlank() ? ability : translated;
    }

    private static String moveDisplayName(MoveData move) {
        return move == null ? "" : moveTranslation(move.id(), move.name());
    }

    private static String moveTranslation(String id, String fallback) {
        String translated = translatedFirst("cobblemon.move." + TropimonDex.normalize(id));
        return translated.isBlank() ? fallback : translated;
    }

    private static String localizedKoChance(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.equals("no damage")) return tr("result.tropimon_damage_calc.no_damage");
        if (value.equals("guaranteed OHKO")) return tr("result.tropimon_damage_calc.ko.guaranteed", 1);
        if (value.equals("guaranteed 2HKO")) return tr("result.tropimon_damage_calc.ko.guaranteed", 2);
        if (value.equals("guaranteed 3HKO")) return tr("result.tropimon_damage_calc.ko.guaranteed", 3);
        if (value.equals("possible 4HKO+")) return tr("result.tropimon_damage_calc.ko.four_plus");
        java.util.regex.Matcher ohko = java.util.regex.Pattern.compile("([0-9.]+)% chance to OHKO").matcher(value);
        if (ohko.matches()) {
            return tr("result.tropimon_damage_calc.ko.chance", ohko.group(1), 1);
        }
        java.util.regex.Matcher chance = java.util.regex.Pattern
                .compile("([0-9.]+)% chance to ([123])H?KO")
                .matcher(value);
        if (chance.matches()) {
            return tr("result.tropimon_damage_calc.ko.chance", chance.group(1), chance.group(2));
        }
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("possible ([0-9]+)-([0-9]+)HKO")
                .matcher(value);
        if (range.matches()) {
            return tr("result.tropimon_damage_calc.ko.range", range.group(1), range.group(2));
        }
        if (value.equals("survives at 1 HP")) {
            return tr("result.tropimon_damage_calc.ko.survives");
        }
        return value;
    }

    private static String terrainDescription(Terrain terrain) {
        return tr("description.tropimon_damage_calc.terrain." + terrain.name().toLowerCase(Locale.ROOT));
    }

    private static String weatherDescription(Weather weather) {
        return tr("description.tropimon_damage_calc.weather." + weather.name().toLowerCase(Locale.ROOT));
    }

    private static String itemDescription(String item) {
        String normalized = TropimonDex.normalize(item);
        if (normalized.isBlank() || "none".equals(normalized)) return tr("description.tropimon_damage_calc.item.none");
        String snake = snakeId(item);
        String compact = normalized;
        String translated = translatedFirst(
                "item.cobblemon." + snake + ".tooltip",
                "tooltip.cobblemon." + snake + ".tooltip",
                "item.mega_showdown." + snake + ".tooltip",
                "tooltip.mega_showdown." + snake + ".tooltip",
                "item.cobblemon." + compact + ".tooltip",
                "tooltip.cobblemon." + compact + ".tooltip",
                "tooltip.mega_showdown." + compact + ".tooltip",
                "item.mega_showdown." + compact + ".description",
                "item.cobblemon." + compact + ".description"
        );
        return translated.isBlank() ? tr("description.tropimon_damage_calc.item.fallback", item) : stripFormatting(translated);
    }

    private static String abilityDescription(String ability) {
        String normalized = TropimonDex.normalize(ability);
        if (normalized.isBlank() || "none".equals(normalized)) return tr("description.tropimon_damage_calc.ability.none");
        String translated = translatedFirst(
                "cobblemon.ability." + normalized + ".desc",
                "ability." + normalized + ".desc",
                "mega_showdown.ability." + normalized + ".desc"
        );
        return translated.isBlank() ? tr("description.tropimon_damage_calc.ability.fallback", ability) : stripFormatting(translated);
    }

    private static String moveDescription(MoveData move) {
        String translated = translatedFirst(
                "cobblemon.move." + move.id() + ".desc",
                "move." + move.id() + ".desc"
        );
        return translated.isBlank() ? tr("description.tropimon_damage_calc.move.fallback", move.name())
                : stripFormatting(translated);
    }

    private static String translatedFirst(String... keys) {
        Language language = Language.getInstance();
        for (String key : keys) {
            if (language.hasTranslation(key)) {
                String value = language.get(key);
                if (!value.isBlank() && !value.equals(key)) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String snakeId(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("([a-z])([A-Z])", "$1_$2");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String stripFormatting(String value) {
        String stripped = Formatting.strip(value);
        return stripped == null ? value : stripped;
    }

    private String searchText(SearchKind kind) {
        SearchField field = field(kind);
        return field == null ? "" : field.widget.getText();
    }

    private static SearchKind moveKind(boolean attacker, int slot) {
        return attacker ? switch (slot) {
            case 0 -> SearchKind.ATTACKER_MOVE_0;
            case 1 -> SearchKind.ATTACKER_MOVE_1;
            case 2 -> SearchKind.ATTACKER_MOVE_2;
            default -> SearchKind.ATTACKER_MOVE_3;
        } : switch (slot) {
            case 0 -> SearchKind.DEFENDER_MOVE_0;
            case 1 -> SearchKind.DEFENDER_MOVE_1;
            case 2 -> SearchKind.DEFENDER_MOVE_2;
            default -> SearchKind.DEFENDER_MOVE_3;
        };
    }

    private static boolean isAttackerMove(SearchKind kind) {
        return kind == SearchKind.ATTACKER_MOVE_0 || kind == SearchKind.ATTACKER_MOVE_1
                || kind == SearchKind.ATTACKER_MOVE_2 || kind == SearchKind.ATTACKER_MOVE_3;
    }

    private static boolean isMoveKind(SearchKind kind) {
        return isAttackerMove(kind) || kind == SearchKind.DEFENDER_MOVE_0 || kind == SearchKind.DEFENDER_MOVE_1
                || kind == SearchKind.DEFENDER_MOVE_2 || kind == SearchKind.DEFENDER_MOVE_3;
    }

    private static int moveSlot(SearchKind kind) {
        return switch (kind) {
            case ATTACKER_MOVE_0, DEFENDER_MOVE_0 -> 0;
            case ATTACKER_MOVE_1, DEFENDER_MOVE_1 -> 1;
            case ATTACKER_MOVE_2, DEFENDER_MOVE_2 -> 2;
            case ATTACKER_MOVE_3, DEFENDER_MOVE_3 -> 3;
            default -> 0;
        };
    }

    private enum SearchKind {
        ATTACKER_POKEMON, DEFENDER_POKEMON,
        ATTACKER_ITEM, DEFENDER_ITEM,
        ATTACKER_ABILITY, DEFENDER_ABILITY,
        ATTACKER_PARTNER_ABILITY, DEFENDER_PARTNER_ABILITY,
        ATTACKER_NATURE, DEFENDER_NATURE,
        ATTACKER_MOVE_0, ATTACKER_MOVE_1, ATTACKER_MOVE_2, ATTACKER_MOVE_3,
        DEFENDER_MOVE_0, DEFENDER_MOVE_1, DEFENDER_MOVE_2, DEFENDER_MOVE_3
    }

    private record SearchField(SearchKind kind, TextFieldWidget widget) {
    }

    private record Suggestion(String label, Runnable apply, boolean hidden, SpeciesData species,
                              MoveData move, String item) {
        private Suggestion(String label, Runnable apply) {
            this(label, apply, false, null, null, null);
        }

        private Suggestion(String label, Runnable apply, boolean hidden) {
            this(label, apply, hidden, null, null, null);
        }

        private static Suggestion species(String label, Runnable apply, SpeciesData species) {
            return new Suggestion(label, apply, false, species, null, null);
        }

        private static Suggestion move(String label, Runnable apply, MoveData move) {
            return new Suggestion(label, apply, false, null, move, null);
        }

        private static Suggestion item(String label, Runnable apply, String item) {
            return new Suggestion(label, apply, false, null, null, item);
        }
    }

    private record RenderedSuggestion(int x, int y, int w, int h, Suggestion suggestion) {
    }

    private record CachedSuggestions(String query, String context, long expiresAt, List<Suggestion> values) {
        private boolean matches(String expectedQuery, String expectedContext, long now) {
            return query.equals(expectedQuery) && context.equals(expectedContext) && now < expiresAt;
        }
    }

    private record ActiveButton(int x, int y, int w, String label) {
    }
}
