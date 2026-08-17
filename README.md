# Tropimon Damage Calculator

Tropimon Damage Calculator is a client-side, in-game damage calculator for Cobblemon on Minecraft 1.21.1.

It reads Pokemon, forms, moves, abilities, held items, models, type icons, and localized descriptions from the installed Cobblemon content. It does not use an external Pokemon database or API.

![Calculator overview](docs/screenshots/calculator-overview.png)

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

![Team Preview](docs/screenshots/team-preview.png)

## Documentation

- [Guide complet en français](docs/guide-complet-fr.md)
- [Guide d'utilisation rapide](docs/damage-calculator-usage.md)
- [Présentation Discord](docs/discord-presentation.md)
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

## Notes

This project is client-side and does not modify battle outcomes or automate player actions. Damage is calculated from the information exposed by Cobblemon and the values configured in the interface.

Tropimon Damage Calculator is an independent project and is not an official Cobblemon mod.

All rights reserved.
