# Runtime assets

The calculator does not package external Pokemon type icons. It resolves
`cobblemon:textures/gui/types_small.png` through Minecraft's resource manager at runtime.

Pokemon previews use Cobblemon's own model renderer and animations. Minecraft widgets and
Tropimon-specific data remain local to the mod.

The navigator frame is packaged at
`assets/tropimon_damage_calc/textures/gui/navigator_frame.png` (345 x 205).
It is a byte-identical copy of the Tropimon navigator frame previously resolved from
`tropimodclient:guis/navigator/navmain/navigator.png`, supplied in the local Tropimon client.
The original Tropimon artwork remains subject to its original rights; copying it does
not grant a separate asset license. No TropimodClient resource or class is needed at runtime.
SHA-256: `314929311012F3688597FF5FE160ADE70ACB3080C74431F70A64E21BE0EC875B`.
