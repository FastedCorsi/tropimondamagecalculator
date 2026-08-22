# Présentation Discord

## Message prêt à publier

**Tropimon Damage Calculator 0.3.10 est disponible**

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

## Version courte

**Tropimon Damage Calculator 0.3.10** ajoute un calculateur de dégâts Cobblemon directement en jeu : synchronisation des combats, équipes et Team Preview, recherche complète, EV/IV, objets, talents, formes, météo, terrains, Solo et Duo. En Random Battle, il compare les informations adverses révélées avec les sets Tropimon et propose un sélecteur `Set N` lorsque plusieurs variantes restent possibles. Le premier set est chargé immédiatement et les doublons sont fusionnés. Les fichiers du jeu sont prioritaires et rechargés automatiquement après une mise à jour ; une copie embarquée reste disponible si le client ne contient pas encore le fichier. Aucune API externe n'est appelée en jeu. Compatible Minecraft 1.21.1 + Cobblemon 1.7.2.
