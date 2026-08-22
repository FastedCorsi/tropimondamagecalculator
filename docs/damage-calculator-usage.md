# Tropimon Damage Calculator - Guide joueur

## Installation

Placez le JAR `tropimon-damage-calc-0.3.24.jar` dans le dossier `mods` du client Fabric 1.21.1 qui contient Cobblemon 1.7.2.

Les sets Random Battle sont d'abord lus depuis `tropimon-random-battle-sets.json` ou `tropimon.json` lorsqu'un de ces fichiers est installé par le jeu ou par un mod Tropimon. Le calculateur vérifie ces fichiers toutes les cinq secondes et les recharge automatiquement après une mise à jour. Si aucun fichier du jeu n'existe, il utilise la copie Tropimon incluse dans son JAR. Aucune API externe n'est appelée en jeu.

Lorsqu'un Pokémon possède plusieurs sets Random Battle, le calculateur compare le niveau, l'objet, le talent, le type Tera et les attaques du Pokémon live. Pour l'adversaire, les variantes sont éliminées à mesure que ses informations sont révélées ; les éléments communs sont préremplis immédiatement et le set complet ne l'est que lorsqu'une seule variante reste possible.

Au début d'une Random Battle, si Cobblemon n'expose encore aucun EV pour un Pokémon du joueur, le calculateur utilise temporairement `85 EV` dans les six statistiques. Il conserve les EV live dès qu'au moins une valeur réelle est disponible. Tous les adversaires, y compris ceux déjà connus depuis le Team Preview, reçoivent la même répartition d'EV et une nature `Serious` selon les règles configurées pour ce format.

Pendant une Random Battle, un bouton `Set N` apparaît pour un Pokémon dont plusieurs variantes restent possibles. Le `Set 1` est appliqué dès l'affichage, puis chaque clic applique la variante suivante. Le survol indique le nombre total de sets ainsi que l'objet, le talent et les quatre attaques de la variante. Les variantes identiques sont fusionnées à la lecture du fichier.

Les modèles animés, les icônes de type et les icônes d'objets sont chargés directement depuis les ressources Minecraft/Cobblemon actives. Les resource packs compatibles Cobblemon sont donc repris automatiquement et aucun sprite de type externe n'est embarqué.

Le cadre principal et les cadres des portraits utilisent le panneau de combat Cobblemon `battle_info_underlay.png`, agrandi en neuf zones pour conserver des coins nets.

## Ouvrir le calculateur

- Touche par defaut sur une nouvelle installation : `B`.
- Raccourci modifiable dans `Options > Controles > Assignation des touches > Tropimon Damage Calculator`.
- Commande client : `/tropicalc`.
- En combat Cobblemon : bouton `Calc` integre a l'interface de combat.

Minecraft conserve la touche deja choisie lors d'une mise a jour du mod.

## Utilisation rapide

1. Ouvrez le calculateur hors combat ou depuis l'interface Cobblemon.
2. Choisissez les deux Pokemon avec les champs de recherche.
3. Selectionnez les attaques de chaque cote.
4. Ajustez les informations connues : niveau, EV, IV, nature, talent, objet, statut et boosts.
5. Configurez la meteo, le terrain et les protections de chaque cote.
6. Lisez les fourchettes de degats affichees sur les lignes d'attaques.

Une option choisie dans une liste est appliquee immediatement. Il n'est pas necessaire de saisir son nom complet.

## Synchronisation Cobblemon

Le bouton `Sync` relit les informations visibles du combat. Le calculateur peut notamment recuperer :

- le Pokemon actif du joueur et son set prive disponible cote client ;
- le Pokemon adverse actif et les informations revelees ;
- les formes regionales et formes de combat ;
- les PV actuels, statuts et boosts visibles ;
- la meteo et certaines conditions de terrain ;
- l'equipe adverse exposee par les ecrans Team Preview pris en charge.

Les informations adverses cachees ne sont pas inventees et restent modifiables manuellement.

## Solo et Duo

Le mode Duo ajoute les mecanismes propres aux combats multiples : attaques de zone, nombre de cibles, Helping Hand, Friend Guard, Wide Guard et talent du partenaire.

## Source des donnees

Les Pokemon, formes, attaques, objets, talents, modeles et traductions sont lus depuis le contenu Cobblemon installe. Le mod n'utilise ni API Pokemon externe ni base de donnees distante.
