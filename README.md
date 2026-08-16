# Tropimon Damage Calculator

Tropimon Damage Calculator is a client-side, in-game damage calculator for Cobblemon on Minecraft 1.21.1.

It reads Pokemon, forms, moves, abilities, items, sprites, and localized descriptions directly from the installed Cobblemon content. It does not require an external Pokemon database or API.

## Features

- Open the calculator with `B` or from the Cobblemon battle interface.
- Synchronize the player's active Pokemon and visible opponent information.
- Import player parties and supported PvP Team Preview rosters.
- Preserve regional, alternate, Mega, and battle-dependent forms.
- Search Pokemon, moves, items, abilities, and natures.
- Edit levels, IVs, EVs, stat stages, status, Tera type, and field conditions.
- Apply weather, terrain, screens, Tailwind, Trick Room, Gravity, items, and abilities.
- Track visible stat changes from setup moves such as Dragon Dance and Swords Dance.
- Handle multi-hit and history-dependent moves supported by the calculator.
- Support Singles and Doubles, including spread damage, Helping Hand, Friend Guard, Wide Guard, and partner abilities.
- Display Cobblemon models, type icons, and localized English or French text.

Hidden opponent information is never guessed. Unknown moves, items, abilities, natures, and private stats remain editable.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16 or newer
- Fabric API
- Cobblemon 1.7.2
- Java 21

## Installation

1. Install Fabric Loader, Fabric API, and Cobblemon.
2. Put the Tropimon Damage Calculator JAR in the client `mods` folder.
3. Start Minecraft and press `B` to open the calculator.

## Building

```powershell
./gradlew.bat clean test build
```

The remapped mod JAR is generated in `build/libs`.

## Notes

This project is client-side and does not modify battle outcomes or automate player actions. Calculated damage is an estimate based on the battle information exposed by Cobblemon and the values configured in the interface.

Tropimon Damage Calculator is an independent project and is not an official Cobblemon mod.

All rights reserved.
