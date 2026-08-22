# Tropimon Damage Calculator - Guide joueur

## Installation

Placez le JAR `tropimon-damage-calc-0.3.5.jar` dans le dossier `mods` du client Fabric 1.21.1 qui contient Cobblemon 1.7.2.

Les sets Random Battle sont lus uniquement depuis un fichier `tropimon.json` installé par le jeu ou par un mod Tropimon. Ils sont relus automatiquement si ce fichier change. Aucun set distant ou embarqué n'est utilisé en secours : si le fichier n'existe pas, le calculateur attend la mise à jour du jeu et conserve seulement les informations observées en combat.

Lorsqu'un Pokémon possède plusieurs sets Random Battle, le calculateur compare le niveau, l'objet, le talent, le type Tera et les attaques du Pokémon live. Pour l'adversaire, les variantes sont éliminées à mesure que ses informations sont révélées ; les éléments communs sont préremplis immédiatement et le set complet ne l'est que lorsqu'une seule variante reste possible.

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
