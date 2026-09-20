# Tropimon Damage Calculator

By FastedCorsi

Tropimon Damage Calculator is a client-side, in-game damage calculator for Cobblemon on Minecraft 1.21.1.

It reads Pokemon, forms, moves, abilities, held items, models, type icons, and localized descriptions from the installed Cobblemon content. It does not use an external Pokemon database or API.

## Features

- Compare damage in both directions between two Pokemon.
- Synchronize the player's active Pokemon and visible opponent data during battle.
- Import the player's party and supported PvP Team Preview rosters.
- Preserve regional, alternate, Mega, and battle-dependent forms.
- Search Pokemon, moves, items, abilities, and natures.
- Edit levels, IVs, EVs, stat stages, status, Tera type, and field conditions.
- Apply weather, terrain, screens, Tailwind, Trick Room, Gravity, items, and abilities.
- Track visible stat changes from setup moves such as Dragon Dance and Swords Dance.
- Handle supported multi-hit and history-dependent moves.
- Support Singles and Doubles, including spread damage, Helping Hand, Friend Guard, Wide Guard, and partner abilities.
- Display Cobblemon models, type icons, and localized English or French text.

Hidden opponent information is never guessed. Unknown moves, items, abilities, natures, and private stats remain editable.

## In-game integration

The calculator can be opened from the Cobblemon battle interface, with `/tropicalc`, or with its configurable key binding. `B` is the default for a fresh installation.

Change the binding in:

`Options > Controls > Key Binds > Tropimon Damage Calculator`

Minecraft keeps an existing user binding when the mod is updated, even if the default changes.

## Documentation

- [Guide complet en français](docs/guide-complet-fr.md)
- [Guide d'utilisation rapide](docs/damage-calculator-usage.md)
- [Présentation Discord](docs/discord-presentation.md)
- [Patch notes du 22 août 2026](docs/patch-notes-2026-08-22.md)
- [Documentation technique](docs/damage-calculator-tech.md)

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16 or newer
- Fabric API
- Cobblemon 1.7.2
- Java 21

## Installation

1. Install Fabric Loader, Fabric API, and Cobblemon.
2. Put the Tropimon Damage Calculator JAR in the client `mods` folder.
3. Start Minecraft and use the configured key or the battle interface.

## Building

```powershell
./gradlew.bat clean test build
```

The remapped mod JAR is generated in `build/libs`.

The build also checks publication privacy, including compiled constants and embedded
resources. Use `./gradlew.bat verifyDistribution` to repeat these checks and generate
the reviewed source ZIP in `build/distributions`. Do not publish the entire working
directory or old files from `exports`.

See [distribution privacy](docs/distribution-privacy.md) for the permanent rules,
optional private detection terms, exclusions, and Git identity check.

## Notes

This project is client-side and does not modify battle outcomes or automate player actions. Damage is calculated from the information exposed by Cobblemon and the values configured in the interface.

Tropimon Damage Calculator is an independent project and is not an official Cobblemon mod.

All rights reserved.


## Mises à jour avec consentement

Aucun téléchargement de mise à jour sans accord. Le premier écran propose uniquement d'autoriser la consultation des métadonnées GitHub (au démarrage, au plus toutes les six heures). Une seconde confirmation montre la version et demande explicitement le téléchargement du JAR et de son SHA-256. L'ancien réglage `enabled: true` ne donne aucune autorisation.

Après accord et vérification, un installateur local utilise le Java de Minecraft, attend la fermeture du jeu, sauvegarde l'ancien JAR hors des mods chargés et remplace uniquement ce mod. Aucun autre mod Tropimon ni changement de launcher n'est requis. Le dossier `mods` classique et le stockage géré Tropimon reconnu sont pris en charge ; une disposition inconnue, un fichier modifié/verrouillé ou une incompatibilité bloque l'installation sans forcer. Le nom du JAR installé est conservé pour rester enregistré par le launcher ; la version réelle se lit dans les métadonnées Fabric.

Pour modifier le choix en jeu : `/tropimonupdates tropimon_damage_calc`. Refuser laisse le mod utilisable. Les anciennes versions dont l'updater est défectueux nécessitent un premier remplacement manuel, jeu fermé. L'accord donné pour ce mod ne s'applique pas aux autres mods. Les tests automatisés sont exécutés sous Windows ; les autres systèmes doivent encore être validés en situation réelle.

Les versions à consentement utilisent un canal de releases distinct du lien GitHub « latest » historique : sélectionner la version par son tag. Cela évite de déclencher les anciens updaters sans accord.
