# Tropimon Damage Calculator - Guide joueur

## Installation

Copier le fichier `build/libs/tropimon-damage-calc-0.1.0.jar` dans le dossier `mods` du client Minecraft Fabric 1.21.1.

Le mod fonctionne sans Cobblemon, mais les boutons de capture live ne se remplissent que si une entite Cobblemon est pointee en jeu.

## Ouvrir l'assistant

- Touche par defaut : `O`.
- L'ecran ne met pas le jeu en pause.

## Utilisation rapide

1. Pointer un Pokemon Cobblemon en jeu.
2. Ouvrir l'assistant avec `O`.
3. Cliquer sur `Att <- cible` ou `Def <- cible`.
4. Ajuster les champs inconnus : item, ability, nature, EV, boosts, Tera, weather, terrain, screens.
5. Lire la ligne de resultat en haut et les 16 rolls dans le bloc `Detail`.

## Options de terrain utiles

L'ecran central permet maintenant de changer rapidement :

- Singles/Doubles ;
- Sun/Rain/Sand/Snow ;
- Electric/Grassy/Misty/Psychic Terrain ;
- crit, Helping Hand, Friend Guard ;
- Reflect, Light Screen, Aurora Veil ;
- Stealth Rock et 0 a 3 couches de Spikes.

Les hazards modifient la chance de KO affichee comme un scenario apres entree sur le terrain. Heavy-Duty Boots annule ces degats d'entree.

## Donnees live Cobblemon

Le MVP lit cote client :

- l'entite Cobblemon pointee ;
- le nom affiche ;
- le niveau si le nom contient `Lv`, `Lvl`, `Nv` ou `Niveau`.

Les autres informations restent editables manuellement, car elles ne sont pas toujours disponibles cote client selon le serveur.

## Limites MVP

- Le dex embarque contient une selection de Pokemon, moves, items et abilities courants.
- Les calculs couvrent les cas standards Gen 9, mais pas encore tous les effets speciaux Showdown.
- Les chances de KO ne tiennent pas encore compte des degats/residus de fin de tour.
