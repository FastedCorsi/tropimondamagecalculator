# Tropimon Damage Calculator - Notes techniques

## Mod autonome

Le mod a son propre identifiant Fabric :

- `mod_id` : `tropimon_damage_calc`
- entrypoint client : `fr.tropimon.damagecalc.TropimonDamageCalcClient`
- jar : `build/libs/tropimon-damage-calc-0.1.0.jar`

Le code de calculateur est separe du package historique `fr.tropimon.companion`.

## Architecture MVP

- `TropimonDamageCalcClient` : keybind client et initialisation.
- `DamageCalcScreen` : interface Minecraft.
- `DamageCalcState` et modeles dans `CalcModels` : etat courant, Pokemon, field, resultats.
- `CobblemonBattleDataProvider` : couche isolee pour hydrater le modele depuis une cible Cobblemon.
- `TropimonDex` : dex embarque minimal.
- `DamageCalculator` : formule de degats Gen 9 simplifiee.

## Precision

Le moteur implemente :

- stats HP/Atk/Def/SpA/SpD/Spe avec EV, IV, nature et boosts ;
- categories Physical/Special/Status ;
- STAB, Tera offensif/defensif, type chart et immunites ;
- crit, burn, weather Sun/Rain, terrains Electric/Grassy/Psychic ;
- Snow avec bonus Defense des types Ice, Misty Terrain contre Dragon, Grassy Terrain contre Earthquake ;
- Reflect, Light Screen, Aurora Veil ;
- Stealth Rock et Spikes dans les chances de KO, avec Heavy-Duty Boots ;
- Choice Band/Specs, Life Orb, Expert Belt, Assault Vest, Eviolite, resist berries ;
- Huge Power, Pure Power, Guts, Adaptability, Technician, Unaware, Mold Breaker, Levitate, Filter/Solid Rock/Prism Armor, Thick Fat, Friend Guard, Infiltrator, Ruin abilities ;
- rolls 85-100 et estimation OHKO/2HKO/3HKO.

## Tests

Le projet contient des tests unitaires JUnit dans `src/test/java/fr/tropimon/damagecalc`.

Ils couvrent :

- la fixture Showdown du cahier des charges a 1 PV pres ;
- burn/Guts ;
- Light Screen/Infiltrator ;
- Snow ;
- Stealth Rock/Spikes/Heavy-Duty Boots ;
- Sword of Ruin et Beads of Ruin.

Commande :

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-24'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test
```

## Build

Fabric Loom 1.15.5 doit etre lance avec Java 21 ou plus. Le projet compile en bytecode Java 21 via `options.release = 21`.

Commande utilisee localement :

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-24'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
```

## Prochaines extensions recommandees

- Remplacer le dex minimal par un export complet `@pkmn/data`.
- Ajouter des fixtures generees depuis `@smogon/calc`.
- Lire le battle state Cobblemon quand l'API client expose les boosts, weather, terrain et sides.
- Ajouter les overrides locaux `config/tropimon-damage-calc/*.json`.
