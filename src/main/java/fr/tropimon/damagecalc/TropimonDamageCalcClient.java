package fr.tropimon.damagecalc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TropimonDamageCalcClient implements ClientModInitializer {
    public static final String MOD_ID = "tropimon_damage_calc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding openCalculatorKey;
    private static boolean rawOpenKeyDown;
    private static volatile boolean openCalculatorRequested;
    private static boolean loadMessageSent;
    private static boolean lastBattleButtonVisible;
    private static long nextBattleAutoSyncTick;
    private static long lastBattleSnapshotFingerprint = Long.MIN_VALUE;

    private static final int BATTLE_TILE_W = 90;
    private static final int BATTLE_TILE_H = 26;
    private static final Identifier BATTLE_TILE_TEXTURE = Identifier.of("cobblemon", "textures/gui/battle/battle_menu_switch.png");
    private static Screen battleReturnScreen;
    private static final Set<String> WARNED_DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static boolean battleGuiMixinActive;
    private static boolean battleMessageMixinActive;

    @Override
    public void onInitializeClient() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Identifier.of(MOD_ID, "cobblemon_data");
            }

            @Override
            public void reload(ResourceManager manager) {
                TropimonDex.invalidateCobblemonData();
                CobblemonBattleDataProvider.invalidateRuntimeCaches();
                CobblemonPokemonProfileRenderer.clearCaches();
            }
        });
        TropimonDex.load();

        openCalculatorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tropimon_damage_calc.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.tropimon_damage_calc"
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("tropicalc")
                        .executes(context -> {
                            openCalculatorRequested = true;
                            return 1;
                        })
                        .then(ClientCommandManager.literal("debug").executes(context -> {
                            MinecraftClient.getInstance().execute(TropimonDamageCalcClient::showDiagnostics);
                            return 1;
                        }))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CobblemonBattleDataProvider.captureVisibleTeamPreview(client.currentScreen);

            if (!loadMessageSent && client.player != null) {
                loadMessageSent = true;
                client.player.sendMessage(Text.translatable(
                        "message.tropimon_damage_calc.loaded",
                        openCalculatorKey.getBoundKeyLocalizedText()
                ), true);
            }

            if (openCalculatorRequested) {
                openCalculatorRequested = false;
                openCalculator();
            }

            while (openCalculatorKey.wasPressed()) {
                openCalculator();
            }

            boolean rawPressed = client.currentScreen == null && isConfiguredOpenKeyPressed(client);
            if (rawPressed && !rawOpenKeyDown) {
                openCalculator();
            }
            rawOpenKeyDown = rawPressed;

            logBattleButtonVisibility(client);
            autoSyncOpenCalculator(client);
        });
    }

    private static boolean isConfiguredOpenKeyPressed(MinecraftClient client) {
        if (openCalculatorKey == null || openCalculatorKey.isUnbound() || client.getWindow() == null) {
            return false;
        }

        InputUtil.Key boundKey = KeyBindingHelper.getBoundKeyOf(openCalculatorKey);
        long window = client.getWindow().getHandle();
        if (boundKey.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, boundKey.getCode()) == GLFW.GLFW_PRESS;
        }
        if (boundKey.getCategory() == InputUtil.Type.KEYSYM) {
            return InputUtil.isKeyPressed(window, boundKey.getCode());
        }
        return openCalculatorKey.isPressed();
    }

    public static void renderBattleGuiButton(DrawContext context, int mouseX, int mouseY, Object battleGui) {
        battleGuiMixinActive = true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!shouldShowBattleButton(client) || !isGeneralBattleSelection(battleGui)) {
            return;
        }
        int x = battleTileX(client);
        int y = battleTileY(client);
        int textureY = inside(mouseX, mouseY, x, y, BATTLE_TILE_W, BATTLE_TILE_H) ? BATTLE_TILE_H : 0;
        context.drawTexture(BATTLE_TILE_TEXTURE, x, y, BATTLE_TILE_W, BATTLE_TILE_H,
                0.0F, textureY, BATTLE_TILE_W, BATTLE_TILE_H, BATTLE_TILE_W, BATTLE_TILE_H * 2);
        context.drawTextWithShadow(client.textRenderer, "Calc", x + 6, y + 8, 0xFFFFFFFF);
    }

    public static boolean handleBattleGuiButtonClick(double mouseX, double mouseY, int button) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !shouldShowBattleButton(client)
                || !isGeneralBattleSelection(client.currentScreen)) {
            return false;
        }
        int x = battleTileX(client);
        int y = battleTileY(client);
        if (inside((int) mouseX, (int) mouseY, x, y, BATTLE_TILE_W, BATTLE_TILE_H)) {
            openCalculator();
            return true;
        }
        return false;
    }

    private static boolean shouldShowBattleButton(MinecraftClient client) {
        return client != null
                && client.player != null
                && !(client.currentScreen instanceof DamageCalcScreen)
                && client.getWindow() != null
                && CobblemonBattleDataProvider.isInBattle();
    }

    private static boolean isGeneralBattleSelection(Object battleGui) {
        if (battleGui == null || !isCobblemonBattleScreenObject(battleGui)) {
            return false;
        }
        try {
            Object selection = battleGui.getClass().getMethod("getCurrentActionSelection").invoke(battleGui);
            return selection != null && selection.getClass().getName().equals(
                    "com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection");
        } catch (ReflectiveOperationException exception) {
            warnOnce("battle-selection", "Unable to inspect the Cobblemon battle selection", exception);
            return false;
        }
    }

    private static boolean isCobblemonBattleScreenObject(Object screen) {
        return screen != null && screen.getClass().getName().equals(
                "com.cobblemon.mod.common.client.gui.battle.BattleGUI");
    }

    private static void logBattleButtonVisibility(MinecraftClient client) {
        boolean visible = shouldShowBattleButton(client);
        if (visible == lastBattleButtonVisible) {
            return;
        }
        lastBattleButtonVisible = visible;
        LOGGER.info("[CalcDBG] battle button visible={} inBattle={}",
                visible,
                CobblemonBattleDataProvider.isInBattle());
    }

    private static int battleTileX(MinecraftClient client) {
        return 198;
    }

    private static int battleTileY(MinecraftClient client) {
        return client.getWindow().getScaledHeight() - 70;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    public static void openCalculator() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof DamageCalcScreen) {
            return;
        }
        if (client.currentScreen != null && isCobblemonBattleScreen(client.currentScreen)) {
            battleReturnScreen = client.currentScreen;
        } else if (!CobblemonBattleDataProvider.isInBattle()) {
            battleReturnScreen = null;
        }
        DamageCalcState state = DamageCalcState.shared();
        if (CobblemonBattleDataProvider.isInBattle()) {
            BattlePokemonSnapshot snapshot = CobblemonBattleDataProvider.activeBattlePokemon(client);
            state.setFromBattle(snapshot);
            lastBattleSnapshotFingerprint = DamageCalcState.battleFingerprint(snapshot);
        }
        client.setScreen(new DamageCalcScreen(state));
    }

    public static void closeCalculator(DamageCalcScreen screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen restore = CobblemonBattleDataProvider.isInBattle()
                ? battleReturnScreen == null ? newCobblemonBattleScreen() : battleReturnScreen
                : null;
        battleReturnScreen = null;
        client.setScreen(restore);
    }

    private static boolean isCobblemonBattleScreen(Screen screen) {
        return isCobblemonBattleScreenObject(screen);
    }

    private static Screen newCobblemonBattleScreen() {
        try {
            Object screen = Class.forName("com.cobblemon.mod.common.client.gui.battle.BattleGUI")
                    .getConstructor()
                    .newInstance();
            return screen instanceof Screen minecraftScreen ? minecraftScreen : null;
        } catch (Throwable throwable) {
            LOGGER.warn("[CalcDBG] cannot restore Cobblemon battle screen", throwable);
            return null;
        }
    }

    static void debug(String message) {
        LOGGER.debug("[CalcDBG] {}", message);
    }

    public static void trackBattleMessages(Iterable<Text> messages) {
        if (!battleMessageMixinActive) {
            LOGGER.info("[CalcDBG] battle message packet hook active");
        }
        battleMessageMixinActive = true;
        // Establish the new battle identity before processing its first condition message.
        CobblemonBattleDataProvider.synchronizeBattleTracker();
        MinecraftClient client = MinecraftClient.getInstance();
        BattlePokemonSnapshot snapshot = CobblemonBattleDataProvider.activeBattlePokemon(client);
        if (snapshot.hasAny()) {
            DamageCalcState.shared().setFromBattle(snapshot);
        }
        CobblemonBattleConditionTracker.accept(messages);
        nextBattleAutoSyncTick = 0L;
    }

    private static void autoSyncOpenCalculator(MinecraftClient client) {
        if (!(client.currentScreen instanceof DamageCalcScreen screen)
                || client.world == null || !CobblemonBattleDataProvider.isInBattle()) {
            if (!(client.currentScreen instanceof DamageCalcScreen)) {
                lastBattleSnapshotFingerprint = Long.MIN_VALUE;
            }
            return;
        }
        long tick = client.world.getTime();
        if (tick < nextBattleAutoSyncTick) {
            return;
        }
        nextBattleAutoSyncTick = tick + 1L;
        BattlePokemonSnapshot snapshot = CobblemonBattleDataProvider.activeBattlePokemon(client);
        long fingerprint = DamageCalcState.battleFingerprint(snapshot);
        if (!snapshot.hasAny() || fingerprint == lastBattleSnapshotFingerprint) {
            return;
        }
        lastBattleSnapshotFingerprint = fingerprint;
        DamageCalcState.shared().setFromBattle(snapshot);
        screen.refreshBattleSnapshot();
    }

    private static void showDiagnostics() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        BattlePokemonSnapshot battle = CobblemonBattleDataProvider.activeBattlePokemon(client);
        String player = battle.player() == null ? "-" : battle.player().species.name();
        String opponent = battle.opponent() == null ? "-" : battle.opponent().species.name();
        client.player.sendMessage(Text.translatable("diagnostic.tropimon_damage_calc.dex",
                TropimonDex.diagnosticSummary()), false);
        client.player.sendMessage(Text.translatable("diagnostic.tropimon_damage_calc.battle",
                CobblemonBattleDataProvider.isInBattle(), battle.doubles() ? "Duo" : "Solo", player, opponent,
                CobblemonBattleDataProvider.playerParty(client).size()), false);
        client.player.sendMessage(Text.translatable("diagnostic.tropimon_damage_calc.conditions",
                CobblemonBattleConditionTracker.diagnosticSummary()), false);
        client.player.sendMessage(Text.translatable("diagnostic.tropimon_damage_calc.renderer",
                CobblemonPokemonProfileRenderer.diagnosticSummary()), false);
        client.player.sendMessage(Text.translatable("diagnostic.tropimon_damage_calc.mixins",
                battleGuiMixinActive, battleMessageMixinActive), false);
    }

    static void warnOnce(String key, String message, Throwable throwable) {
        if (WARNED_DIAGNOSTICS.add(key)) {
            LOGGER.warn("{}: {}", message, throwable.toString());
        }
    }
}
