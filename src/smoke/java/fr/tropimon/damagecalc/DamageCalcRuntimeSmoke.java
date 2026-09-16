package fr.tropimon.damagecalc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.util.List;

/** Optional test harness only, excluded from release artifacts. */
public final class DamageCalcRuntimeSmoke implements ClientModInitializer {
    private int ticks;
    private boolean opened;
    private int openedAt;
    private boolean worldStarted;
    private DamageCalcState smokeState;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks > 2400) {
                TropimonDamageCalcClient.LOGGER.error("[CalcSmoke] FAIL timeout");
                client.scheduleStop(); return;
            }
            if (opened) {
                if (ticks == openedAt + 40 || ticks == openedAt + 80 || ticks == openedAt + 120) {
                    ScreenshotRecorder.saveScreenshot(client.runDirectory, "smoke-" + (ticks - openedAt) + ".png",
                            client.getFramebuffer(), message -> TropimonDamageCalcClient.LOGGER.info("[CalcSmoke] screenshot {}", message.getString()));
                }
                if (ticks == openedAt + 90 && client.currentScreen instanceof DamageCalcScreen) {
                    String stats = Text.translatable("screen.tropimon_damage_calc.view.stats").getString();
                    for (var child : List.copyOf(client.currentScreen.children())) {
                        if (child instanceof ButtonWidget button && button.getMessage().getString().equals(stats)) {
                            button.onPress();
                            break;
                        }
                    }
                }
                if (ticks == openedAt + 125 && client.currentScreen instanceof DamageCalcScreen screen) {
                    try {
                        PokemonSet prediction = smokeState.defender.copy();
                        TextFieldWidget evField = null;
                        for (var child : client.currentScreen.children()) {
                            if (child instanceof TextFieldWidget textField && textField.getX() > client.currentScreen.width / 2
                                    && textField.getText().equals("252")) {
                                evField = textField;
                                break;
                            }
                        }
                        if (evField == null) throw new AssertionError("Predicted EV field not populated");
                        evField.setText("");
                        if (!smokeState.defender.evsManuallyEdited || smokeState.defender.rankedEvSpread != null) {
                            throw new AssertionError("Manual EV edit not tracked by UI");
                        }
                        var edited = new java.util.EnumMap<>(smokeState.defender.evs);
                        smokeState.setFromBattle(new BattlePokemonSnapshot(null, prediction, true));
                        if (!edited.equals(smokeState.defender.evs)) throw new AssertionError("Sync overwrote manual EV edit");
                        TropimonDamageCalcClient.LOGGER.info("[CalcSmoke] ranked EV fields populated; manual UI edit preserved after sync");
                    } catch (Throwable error) {
                        TropimonDamageCalcClient.LOGGER.error("[CalcSmoke] FAIL ranked EV UI", error);
                        client.scheduleStop();
                        return;
                    }
                }
                if (ticks >= openedAt + 140) {
                    try { java.nio.file.Files.writeString(client.runDirectory.toPath().resolve("smoke-passed.txt"), "PASS"); }
                    catch (java.io.IOException error) { throw new RuntimeException(error); }
                    TropimonDamageCalcClient.LOGGER.info("[CalcSmoke] PASS runtime data, cache parity, reflection and UI captures");
                    client.scheduleStop();
                }
                return;
            }
            // A fresh game directory can show the accessibility onboarding screen instead of TitleScreen.
            if (ticks < 160 || client.getOverlay() != null) return;
            if (client.world == null) {
                if (!worldStarted) {
                    worldStarted = true;
                    String world = "damagecalc-smoke";
                    if (java.nio.file.Files.exists(client.runDirectory.toPath().resolve("saves").resolve(world).resolve("level.dat"))) {
                        client.createIntegratedServerLoader().start(world, () -> client.scheduleStop());
                    } else {
                        client.createIntegratedServerLoader().createAndStart(world,
                                new LevelInfo(world, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
                                        new GameRules(), DataConfiguration.SAFE_MODE),
                                new GeneratorOptions(20260830L, false, false),
                                registries -> registries.get(RegistryKeys.WORLD_PRESET).get(WorldPresets.FLAT).createDimensionsRegistryHolder(),
                                client.currentScreen);
                    }
                }
                return;
            }
            if (client.player == null) return;
            opened = true;
            openedAt = ticks;
            try {
                boolean coexistence = Boolean.getBoolean("damagecalc.smoke.coexistence");
                if (FabricLoader.getInstance().isModLoaded("tropimon_ui_battle") != coexistence) {
                    throw new AssertionError("Wrong verification profile");
                }
                if (client.getResourceManager().getResource(Identifier.of("tropimon_damage_calc",
                        "textures/gui/navigator_frame.png")).isEmpty()) throw new AssertionError("Missing own frame");
                TropimonDex.invalidateCobblemonData();
                TropimonDex.load();
                TropimonDamageCalcClient.LOGGER.info("[CalcSmoke] profile={} species={} moves={}",
                        coexistence ? "coexistence" : "isolated", TropimonDex.speciesList().size(),
                        TropimonDex.moveListFor(TropimonDex.species("goodrahisui")).size());
                if (TropimonDex.speciesList().size() < 1000) throw new AssertionError("Cobblemon data not ready");
                DamageCalcState state = new DamageCalcState();
                state.attacker = new PokemonSet(TropimonDex.species("goodrahisui"));
                state.defender = new PokemonSet(TropimonDex.species("ninetalesalola"));
                state.attacker.ability = "Shell Armor";
                state.defender.ability = "Snow Warning";
                state.attacker.moves.clear();
                state.defender.moves.clear();
                for (String move : List.of("dragonpulse", "flamethrower", "bodypress", "surf")) state.attacker.moves.add(TropimonDex.move(move));
                for (String move : List.of("blizzard", "moonblast", "freezedry", "auroraveil")) state.defender.moves.add(TropimonDex.move(move));
                for (Stat stat : Stat.values()) { state.attacker.evs.put(stat, 85); state.defender.evs.put(stat, 85); }
                state.field.weather = Weather.SNOW;
                state.field.doubles = true;
                state.field.attackerSide.helpingHand = true;
                for (int slot = 0; slot < 4; slot++) {
                    DamageResult expected = DamageCalculator.calculate(state.attacker, state.defender,
                            DamageCalcState.effectiveMove(state.attacker, slot), state.field, state.field.attackerSide, state.field.defenderSide);
                    if (!expected.equals(state.calculateMove(true, slot))) throw new AssertionError("Runtime cache parity");
                }
                state.defender.statsKnown = false;
                state.defender.battleId = "ranked-smoke-opponent";
                state.defender.evs.replaceAll((stat, value) -> 0);
                TropimonRankedUsageService.applyProfile(state.defender,
                        TropimonRankedUsageService.profileFromJson("ninetalesalola", """
                                {"spreads":{"252 HP / 252 Def / 6 SpD":40,"252 SpA / 252 Spe / 6 HP":30}}
                                """));
                if (state.defender.evs.get(Stat.HP) != 252 || state.defender.evs.get(Stat.DEF) != 252
                        || state.defender.evs.get(Stat.SPD) != 6 || state.defender.statsKnown) {
                    throw new AssertionError("Ranked EV runtime prefill");
                }
                for (int slot = 0; slot < 4; slot++) {
                    DamageResult expected = DamageCalculator.calculate(state.attacker, state.defender,
                            DamageCalcState.effectiveMove(state.attacker, slot), state.field, state.field.attackerSide, state.field.defenderSide);
                    if (!expected.equals(state.calculateMove(true, slot))) throw new AssertionError("Ranked EV cache invalidation");
                }
                String tooltip = Text.translatable("screen.tropimon_damage_calc.ranked_evs", "40.00").getString();
                if (!tooltip.contains("40.00%") || tooltip.contains("%%")) throw new AssertionError("Ranked EV tooltip translation");
                smokeState = state;
                if (coexistence) {
                    Class<?> bridge = Class.forName("fr.tropimon.battleui.TropimonDamageCalcBridge");
                    var available = bridge.getDeclaredMethod("available");
                    available.setAccessible(true);
                    if (!Boolean.TRUE.equals(available.invoke(null))) throw new AssertionError("UI Battle reflection contract");
                }
                client.options.getGuiScale().setValue(2);
                client.options.getMenuBackgroundBlurriness().setValue(0);
                client.onResolutionChanged();
                client.setScreen(new DamageCalcScreen(state));
                TropimonDamageCalcClient.LOGGER.info("[CalcSmoke] checked runtime data, cache parity, assets, reflection; calculator opened");
            } catch (Throwable error) {
                TropimonDamageCalcClient.LOGGER.error("[CalcSmoke] FAIL", error);
                client.scheduleStop();
            }
        });
    }
}
