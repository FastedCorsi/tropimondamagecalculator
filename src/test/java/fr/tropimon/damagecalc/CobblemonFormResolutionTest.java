package fr.tropimon.damagecalc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class CobblemonFormResolutionTest {
    @Test
    void runtimeFormsWinOverStalePropertiesForTheEntireReportedTeam() {
        for (String entry : List.of("slowbro:galarian", "samurott:hisuian", "typhlosion:hisuian",
                "weezing:galarian", "exeggutor:alolan", "zoroark:hisuian", "ninetales:alolan")) {
            String[] parts = entry.split(":");
            SpeciesData base = base(parts[0]);
            SpeciesData regional = form(base, parts[1], List.of(parts[1]));
            SpeciesModel species = new SpeciesModel(base, List.of(regional));
            BattleModel battle = new BattleModel(species, new State(Set.of(parts[1])),
                    new Properties(base.id(), "Normal", Set.of()), Set.of());

            var observed = CobblemonBattleDataProvider.observeBattleForm(battle);

            assertTrue(observed.authoritative(), entry);
            assertSame(regional, observed.select(List.of(base, regional)), entry);
            assertEquals(List.of(parts[1]), observed.aspects(), entry);
        }
    }

    @Test
    void dynamicFormUpdatesReplaceOldAspectsInsteadOfCombiningThem() {
        SpeciesData base = base("aegislash");
        SpeciesData shield = form(base, "Shield", List.of("shield-forme"));
        SpeciesData blade = form(base, "Blade", List.of("blade-forme"));
        SpeciesModel species = new SpeciesModel(base, List.of(shield, blade));
        BattleModel battle = new BattleModel(species, new State(Set.of("shield-forme")),
                new Properties(base.id(), "Blade", Set.of("blade-forme")), Set.of("blade-forme"));

        var observed = CobblemonBattleDataProvider.observeBattleForm(battle);

        assertSame(shield, observed.select(List.of(base, shield, blade)));
        assertEquals(List.of("shield-forme"), observed.aspects());
    }

    @Test
    void anEmptyRuntimeAspectSetCanReturnToTheBaseForm() {
        SpeciesData base = base("palafin");
        SpeciesData hero = form(base, "Hero", List.of("hero-form"));
        BattleModel battle = new BattleModel(new SpeciesModel(base, List.of(hero)), new State(Set.of()),
                new Properties(base.id(), "Hero", Set.of("hero-form")), Set.of("hero-form"));

        var observed = CobblemonBattleDataProvider.observeBattleForm(battle);

        assertTrue(observed.authoritative());
        assertSame(base, observed.select(List.of(base, hero)));
    }

    @Test
    void runtimeSpeciesWinsOverPropertiesAfterTransform() {
        SpeciesData base = base("samurott");
        SpeciesData hisui = form(base, "Hisui", List.of("hisuian"));
        BattleModel battle = new BattleModel(new SpeciesModel(base, List.of(hisui)),
                new State(Set.of("hisuian")), new Properties("ditto", "Normal", Set.of()), Set.of());

        assertSame(hisui, CobblemonBattleDataProvider.observeBattleForm(battle)
                .select(List.of(base("ditto"), base, hisui)));
    }

    @Test
    void missingStateFallsBackToTheBattlePacketAspectsBeforeProperties() {
        SpeciesData base = base("zoroark");
        SpeciesData hisui = form(base, "Hisui", List.of("hisuian"));
        BattleModel battle = new BattleModel(new SpeciesModel(base, List.of(hisui)), null,
                new Properties(base.id(), "Normal", Set.of()), Set.of("hisuian"));

        var observed = CobblemonBattleDataProvider.observeBattleForm(battle);

        assertTrue(observed.authoritative());
        assertSame(hisui, observed.select(List.of(base, hisui)));
    }

    @Test
    void propertiesAreUsedOnlyWhenRuntimeAspectsAreUnavailable() {
        SpeciesData base = base("slowking");
        SpeciesData galar = form(base, "Galar", List.of("galarian"));
        BattleModel battle = new BattleModel(new SpeciesModel(base, List.of(galar)), null,
                new Properties(base.id(), "Galar", Set.of("galarian")), null);

        var observed = CobblemonBattleDataProvider.observeBattleForm(battle);

        assertFalse(observed.authoritative());
        assertSame(galar, observed.select(List.of(base, galar)));
    }

    @Test
    void aPartialAspectMatchCannotSelectAnotherMegaVariant() {
        SpeciesData base = base("charizard");
        SpeciesData megaX = form(base, "Mega X", List.of("mega", "mega-x"));
        SpeciesData megaY = form(base, "Mega Y", List.of("mega", "mega-y"));

        assertSame(megaY, TropimonDex.selectFormSpecies(List.of(base, megaX, megaY),
                base.id(), "", base.id(), List.of("mega", "mega-y")));
        assertSame(base, TropimonDex.selectFormSpecies(List.of(base, megaX, megaY),
                base.id(), "", base.id(), List.of("mega")));
    }

    @Test
    void rosterRetainsRuntimeFormWhenAnOlderPartySnapshotArrives() {
        SpeciesData base = base("ninetales");
        SpeciesData alola = form(base, "Alola", List.of("alolan"));
        PokemonSet observed = new PokemonSet(alola);
        observed.battleFormObserved = true;
        PokemonSet stale = new PokemonSet(base);

        assertSame(alola, CobblemonBattleDataProvider.mergedOpponentSpecies(observed, stale));
        assertTrue(observed.copy().battleFormObserved);
        stale.battleFormObserved = true;
        assertSame(base, CobblemonBattleDataProvider.mergedOpponentSpecies(observed, stale));
    }

    @Test
    void punctuationFormsHaveDistinctIdsAndAspects() {
        JsonObject json = JsonParser.parseString("""
                {"name":"Unown", "primaryType":"psychic", "forms":[
                    {"name":"!", "aspects":["character-!"]},
                    {"name":"?", "aspects":["character-?"]}
                ]}
                """).getAsJsonObject();
        SpeciesData base = base("unown");
        List<SpeciesData> forms = CobblemonDexDataProvider.formResourceData(
                base.id(), json, new LinkedHashSet<>(), new LinkedHashSet<>()).stream()
                .map(form -> form.toSpecies(base)).toList();

        assertEquals("unownexclamation", forms.get(0).id());
        assertEquals("unownquestion", forms.get(1).id());
        assertSame(forms.get(1), TropimonDex.selectFormSpecies(forms,
                base.id(), "?", base.id(), List.of("character-?")));
    }

    @Test
    void changingToAMonotypeFormDoesNotInheritTheBaseSecondaryType() {
        JsonObject json = JsonParser.parseString("""
                {"name":"Testmon", "primaryType":"fire", "secondaryType":"flying", "forms":[
                    {"name":"Ice", "aspects":["ice-form"], "primaryType":"ice"},
                    {"name":"Cosmetic", "aspects":["cosmetic-form"]}
                ]}
                """).getAsJsonObject();
        List<FormResourceData> forms = CobblemonDexDataProvider.formResourceData(
                "testmon", json, new LinkedHashSet<>(), new LinkedHashSet<>());
        assertEquals(PokeType.ICE, forms.get(0).primaryType());
        assertEquals(PokeType.NONE, forms.get(0).secondaryType());
        assertEquals(PokeType.FIRE, forms.get(1).primaryType());
        assertEquals(PokeType.FLYING, forms.get(1).secondaryType());
    }

    @Test
    void aFormChangeClearsOldUsagePredictionsButKeepsRevealedMoves() {
        SpeciesData base = base("ninetales");
        SpeciesData alola = form(base, "Alola", List.of("alolan"));
        PokemonSet previous = new PokemonSet(base);
        previous.battleId = "same-battle-pokemon";
        previous.abilityKnown = false;
        previous.ability = "Flash Fire";
        previous.rankedAbilitySuggested = true;
        previous.rankedProfileKey = "ninetales";
        MoveData revealed = TropimonDex.move("protect");
        previous.moves.clear();
        previous.moves.add(revealed);
        previous.observedMoveIds.add(revealed.id());
        previous.rankedMoveUsage.put(revealed.id(), 50.0);
        PokemonSet current = new PokemonSet(alola);
        current.battleId = previous.battleId;
        current.battleFormObserved = true;
        current.abilityKnown = false;
        DamageCalcState state = new DamageCalcState();
        state.defender = previous;

        state.setFromBattle(new BattlePokemonSnapshot(null, current, false));

        assertSame(alola, state.defender.species);
        assertTrue(state.defender.battleFormObserved);
        assertEquals("", state.defender.rankedProfileKey);
        assertEquals("None", state.defender.ability);
        assertFalse(state.defender.rankedAbilitySuggested);
        assertTrue(state.defender.moves.stream().anyMatch(move -> move != null && move.id().equals(revealed.id())));
        assertTrue(state.defender.observedMoveIds.contains(revealed.id()));
    }

    @Test
    void runtimeResolutionCoversEveryFormInTheInstalledCobblemonJar() throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".tropimon", "mods",
                "Cobblemon-fabric-1.7.2+1.21.1.jar");
        assumeTrue(Files.isRegularFile(jar), "Optional integration test requires the installed Cobblemon JAR");
        int checked = 0;
        Set<String> verified = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("data/cobblemon/species/")
                        || !entry.getName().endsWith(".json")) continue;
                JsonObject json;
                try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(reader).getAsJsonObject();
                }
                String id = TropimonDex.normalize(json.get("name").getAsString());
                SpeciesData base = base(id);
                List<FormResourceData> definitions = CobblemonDexDataProvider.formResourceData(
                        id, json, new LinkedHashSet<>(), new LinkedHashSet<>());
                List<SpeciesData> forms = definitions.stream().map(form -> form.toSpecies(base)).toList();
                SpeciesModel model = new SpeciesModel(base, forms);
                List<SpeciesData> candidates = new ArrayList<>(forms);
                candidates.addFirst(base);
                for (SpeciesData form : forms) {
                    // Cobblemon chooses the last satisfied definition; some cosmetic
                    // variants share aspects, so compare against that same resolution.
                    Set<String> aspects = Set.copyOf(form.aspects());
                    SpeciesData expected = model.getForm(aspects).data();
                    BattleModel battle = new BattleModel(model, new State(aspects),
                            new Properties(base.id(), "Normal", Set.of()), Set.of());
                    var observed = CobblemonBattleDataProvider.observeBattleForm(battle);
                    SpeciesData actual = observed.select(candidates);
                    assertSame(expected, actual, entry.getName() + ": " + form.id());
                    assertEquals(expected.primaryType(), actual.primaryType());
                    assertEquals(expected.secondaryType(), actual.secondaryType());
                    assertEquals(expected.baseStats(), actual.baseStats());
                    assertEquals(expected.aspects(), actual.aspects());
                    verified.add(actual.id());
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "The integration test must read the complete installed form catalogue");
        for (String id : List.of("slowbrogalar", "samurotthisui", "typhlosionhisui", "weezinggalar",
                "exeggutoralola", "zoroarkhisui", "ninetalesalola", "palafinhero", "aegislashblade")) {
            assertTrue(verified.contains(id), "Missing regression case: " + id);
        }
        System.out.println("Cobblemon forms verified through runtime resolver: " + checked);
    }

    private static SpeciesData base(String id) {
        EnumMap<Stat, Integer> stats = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) stats.put(stat, 80);
        return new SpeciesData(id, id, PokeType.NORMAL, PokeType.NONE, stats, false, "", id, List.of());
    }

    private static SpeciesData form(SpeciesData base, String name, List<String> aspects) {
        return new SpeciesData(base.id() + TropimonDex.normalize(name), base.name() + " " + name,
                PokeType.ICE, PokeType.FAIRY, base.baseStats(), false, "", base.id(), aspects);
    }

    private record State(Set<String> aspects) {
        public Set<String> getCurrentAspects() { return aspects; }
    }

    private record Properties(String species, String form, Set<String> aspects) {
        public String getSpecies() { return species; }
        public String getForm() { return form; }
        public Set<String> getAspects() { return aspects; }
    }

    private record BattleModel(SpeciesModel species, State state, Properties properties, Set<String> aspects) {
        public SpeciesModel getSpecies() { return species; }
        public State getState() { return state; }
        public Properties getProperties() { return properties; }
    }

    private record FormModel(SpeciesData data, String name) {
        public String getName() { return name; }
        public String showdownId() { return data.id(); }
        public List<String> getAspects() { return data.aspects(); }
    }

    private record SpeciesModel(SpeciesData base, List<SpeciesData> forms) {
        public String showdownId() { return base.id(); }
        public FormModel getForm(Set<String> aspects) {
            for (int i = forms.size() - 1; i >= 0; i--) {
                SpeciesData form = forms.get(i);
                if (aspects.containsAll(form.aspects())) return wrap(form);
            }
            return new FormModel(base, "Normal");
        }
        public FormModel getFormByName(String name) {
            for (SpeciesData form : forms) {
                if (wrap(form).name().equalsIgnoreCase(name)) return wrap(form);
            }
            return new FormModel(base, "Normal");
        }
        private FormModel wrap(SpeciesData form) {
            return new FormModel(form, form.name().substring(base.name().length()).trim());
        }
    }
}
