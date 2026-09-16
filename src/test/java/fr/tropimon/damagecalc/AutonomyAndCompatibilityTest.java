package fr.tropimon.damagecalc;

import net.minecraft.client.MinecraftClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AutonomyAndCompatibilityTest {
    @Test void battleUiReflectiveContractRemainsCallableAndDoesNotMutateSharedState() throws Exception {
        String prefix = "fr.tropimon.damagecalc.";
        Class<?> stateClass = Class.forName(prefix + "DamageCalcState");
        Class<?> pokemon = Class.forName(prefix + "PokemonSet");
        Class<?> move = Class.forName(prefix + "MoveData");
        Class<?> type = Class.forName(prefix + "PokeType");
        Class<?> field = Class.forName(prefix + "FieldState");
        Class<?> side = Class.forName(prefix + "SideConditions");
        Class<?> snapshot = Class.forName(prefix + "BattlePokemonSnapshot");
        Class<?> calculator = Class.forName(prefix + "DamageCalculator");
        Class<?> provider = Class.forName(prefix + "CobblemonBattleDataProvider");
        method(provider, "activeBattlePokemon", MinecraftClient.class);
        method(provider, "battlePokemonSet", Object.class, boolean.class, MinecraftClient.class);
        method(snapshot, "player"); method(snapshot, "opponent");
        method(stateClass, "setFromBattle", snapshot);
        method(stateClass, "effectiveMove", pokemon, int.class);
        method(pokemon, "moveAt", int.class);
        pokemon.getDeclaredField("itemKnown").setAccessible(true);
        stateClass.getDeclaredField("field").setAccessible(true);
        field.getDeclaredField("attackerSide").setAccessible(true);
        field.getDeclaredField("defenderSide").setAccessible(true);
        for (String name : List.of("id", "name", "type")) method(move, name);
        for (String name : List.of("minPercent", "maxPercent", "koChance", "notes", "warnings")) {
            method(Class.forName(prefix + "DamageResult"), name);
        }
        method(calculator, "typeEffectiveness", move, type, pokemon, pokemon, field, List.class);
        method(calculator, "effectiveMoveType", pokemon, move, field, List.class);
        method(calculator, "effectivePower", pokemon, pokemon, move, type, field, side, side, List.class, List.class);
        Constructor<?> constructor = stateClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object isolated = constructor.newInstance();
        DamageCalcState fixture = DamageCacheParityTest.basicState();
        for (String name : List.of("attacker", "defender")) {
            var target = stateClass.getDeclaredField(name);
            target.setAccessible(true);
            target.set(isolated, target.get(fixture));
        }
        PokemonSet sharedAttacker = DamageCalcState.shared().attacker;
        Method calculate = method(stateClass, "calculateMove", boolean.class, int.class);
        assertEquals(fixture.calculateMove(true, 0), calculate.invoke(isolated, true, 0));
        assertSame(sharedAttacker, DamageCalcState.shared().attacker);
    }

    @Test void privateThirdPartyPreviewObjectsAreNeverInspected() {
        PrivatePreview preview = new PrivatePreview();
        CobblemonBattleDataProvider.captureVisibleTeamPreview(preview);
        assertFalse(preview.read);
    }

    public static final class PrivatePreview {
        boolean read;
        public Object getInformations() { read = true; throw new AssertionError("Another mod's internal state"); }
        public Object getOpponentParty() { read = true; throw new AssertionError("Another mod's internal state"); }
    }

    @Test void productionSourcesDoNotDependOnOtherTropimonMods() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbidden : List.of("fr.tropimon.battleui", "fr.tropimon.teamsaver", "fr.tropimon.teambuilder",
                        "fr.tropimon.teamhunt", "getAllMods()", "getDecodedPokemons", "getInformations",
                        "\"tropimodclient\"", "gameDir.resolve(\"config\")")) {
                    assertFalse(source.contains(forbidden), path + ": " + forbidden);
                }
            }
        }
        var stream = getClass().getResourceAsStream("/assets/tropimon_damage_calc/textures/gui/navigator_frame.png");
        assertNotNull(stream);
        try (stream) {
            var frame = ImageIO.read(stream);
            assertEquals(345, frame.getWidth());
            assertEquals(205, frame.getHeight());
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
}
