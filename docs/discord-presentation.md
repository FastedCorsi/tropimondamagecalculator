# Présentation Discord

## Message prêt à publier

**Tropimon Damage Calculator 0.3.24 est disponible**

Un calculateur de dégâts Cobblemon directement intégré à Minecraft, pensé pour préparer un duel et vérifier rapidement un match-up sans quitter le jeu.

**Fonctionnalités principales**

- calcul des dégâts dans les deux sens ;
- synchronisation du Pokémon actif, des PV, statuts et boosts visibles ;
- import de votre équipe et prise en charge des Team Preview compatibles ;
- recherche des Pokémon, formes, attaques, objets, talents et natures ;
- EV, IV, niveaux, boosts, Tera, météo, terrains et protections éditables ;
- modes Solo et Duo avec attaques de zone, Helping Hand, Friend Guard et Wide Guard ;
- formes régionales, Mega et formes de combat ;
- modèles et icônes de types Cobblemon affichés dans l'interface ;
- interface disponible en français et en anglais selon la langue du jeu.

Le catalogue vient directement du contenu Cobblemon installé : aucune API Pokémon externe et aucune base de données distante.

**Ouverture**

- bouton `Calc` pendant un combat Cobblemon ;
- commande `/tropicalc` ;
- raccourci configurable dans les contrôles Minecraft (`B` par défaut sur une nouvelle installation).

**Compatibilité**

Minecraft 1.21.1, Fabric et Cobblemon 1.7.2. Le mod est uniquement côté client.

Code et informations : https://github.com/FastedCorsi/tropimondamagecalculator

Interface :
https://raw.githubusercontent.com/FastedCorsi/tropimondamagecalculator/main/docs/screenshots/calculator-overview.png

Team Preview :
https://raw.githubusercontent.com/FastedCorsi/tropimondamagecalculator/main/docs/screenshots/team-preview.png

Tropimon Damage Calculator est un projet indépendant et non officiel.

## Patch note du 22 août 2026 - prêt à publier

**Tropimon Damage Calculator - Random Battle + UI Rework**

La mise à jour du jour améliore fortement l'intégration aux combats Tropimon.

**Random Battle**

- détection automatique du format Random Battle ;
- récupération de l'équipe générée du joueur avec formes, niveaux, EV, IV, nature, talent, objet et attaques ;
- fallback de `85 EV` par statistique lorsque les EV live ne sont pas encore disponibles au début du combat ;
- lecture et rechargement automatique des sets Tropimon installés dans les fichiers du jeu ;
- capture du Team Preview, y compris l'écran d'inventaire de sélection du lead ;
- déduplication des équipes et correction des formes régionales ;
- déduction progressive du set adverse avec le niveau, l'objet, le talent, le Tera et les attaques révélées ;
- bouton `Set N` lorsqu'il reste plusieurs variantes, avec `Set 1` chargé immédiatement et détail du set au survol ;
- fusion des sets identiques et prise en charge de toutes les variantes disponibles.

**Interface**

- nouveau grand cadre inspiré des interfaces Cobblemon/Tropimon ;
- fond sombre semi-transparent et fenêtre plus compacte ;
- modèles Pokémon animés dans des cadres cyan ;
- icônes de type et d'objet provenant des ressources Cobblemon ;
- colonnes de statistiques centrées et presets EV plus petits ;
- listes recherchables appliquées immédiatement au clic ;
- petite croix de fermeture en haut à droite, rouge au survol ;
- retour automatique à l'interface de combat après fermeture du calculateur.

**Corrections importantes**

- les attaques adverses révélées sont conservées et utilisées pour identifier leur set ;
- les Pokémon de l'équipe gardent leurs vraies données privées côté client ;
- sélectionner ensuite un Pokémon générique réinitialise correctement EV, IV, niveau, nature, objet, talent et attaques ;
- les équipes et adversaires ne sont plus dupliqués après plusieurs combats ;
- les formes du preview sont réconciliées avec la forme réellement envoyée.

Ouverture avec le bouton `Calc` en combat, `/tropicalc` ou la touche configurable (`B` par défaut sur une nouvelle installation).

## Version courte

**Tropimon Damage Calculator 0.3.24** ajoute un calculateur de dégâts Cobblemon directement en jeu : synchronisation des combats, équipes et Team Preview, recherche complète, EV/IV, objets, talents, formes, météo, terrains, Solo et Duo. En Random Battle, il compare les informations adverses révélées avec les sets Tropimon et propose un sélecteur `Set N` lorsque plusieurs variantes restent possibles. Le premier set est chargé immédiatement, les doublons sont fusionnés et un fallback de `85 EV` par statistique est utilisé si Cobblemon ne fournit encore aucun EV au début du combat. Cette répartition est maintenant appliquée à tous les adversaires, y compris ceux provenant d'un Team Preview déjà résolu. L'interface compacte utilise les modèles animés, les icônes de type et d'objet ainsi que le grand cadre du navigateur Tropimon avec un fond sombre semi-transparent et des cadres cyan autour des portraits. Les modèles disposent d'une marge régulière et l'ancien indicateur `?` superposé aux portraits a été retiré. La fermeture utilise maintenant une petite croix ancrée dans l'angle supérieur droit, mise en évidence en rouge au survol. Les colonnes de statistiques sont centrées, les presets EV sont plus compacts et les titres d'attaques mieux espacés. La sélection d'un Pokémon générique réinitialise correctement son set au lieu de conserver les EV, IV et réglages d'un Pokémon de l'équipe. Les fichiers du jeu sont prioritaires et rechargés automatiquement après une mise à jour ; une copie embarquée reste disponible si le client ne contient pas encore le fichier. Aucune API externe n'est appelée en jeu. Compatible Minecraft 1.21.1 + Cobblemon 1.7.2.
