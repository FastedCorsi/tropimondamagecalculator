# Tropimon Damage Calculator - Notes techniques

## Architecture

- `TropimonDamageCalcClient` : initialisation Fabric, commande, raccourci configurable et integration a l'interface de combat.
- `DamageCalcScreen` : interface de comparaison, recherche et edition des deux sets.
- `DamageCalcState` et les modeles de `CalcModels` : etat courant et synchronisation.
- `CobblemonDexDataProvider` : lecture des especes, formes, attaques, objets et talents depuis Cobblemon.
- `CobblemonBattleDataProvider` : equipes, Team Preview, formes et donnees visibles du combat.
- `CobblemonBattleConditionTracker` : conditions, boosts, attaques revelees et historique observable.
- `CobblemonPokemonProfileRenderer` : rendu des modeles Cobblemon dans l'ecran.
- `DamageCalculator` : statistiques et formule de degats.

## Source de verite

Le contenu installe de Cobblemon est la source de verite pour le catalogue. Le mod ne telecharge pas de base Pokemon et ne depend pas d'une API externe. Le moteur de calcul reste local afin de pouvoir appliquer les informations observees en jeu et les valeurs editees par le joueur.

## Couverture fonctionnelle

Le calcul inclut notamment les statistiques, EV, IV, natures, boosts, STAB, types, Tera, critique, statut, meteo, terrains, protections, objets, talents, multi-coups, historique observable et mecanismes Duo pris en charge.

Les informations adverses privees ne sont jamais supposees. Une valeur inconnue reste explicite et editable.

## Tests et build

Le projet contient 91 tests unitaires JUnit couvrant les principaux modificateurs et cas limites du moteur.

```powershell
$env:JAVA_HOME="$env:APPDATA\.tropimon\runtime\x64\jdk-21.0.6+7"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build
```

Le JAR remappe est genere dans `build/libs`.
