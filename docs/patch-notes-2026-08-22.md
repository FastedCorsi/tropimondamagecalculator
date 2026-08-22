# Patch notes - 22 août 2026

## Random Battle Tropimon

La prise en charge des Random Battles a été reconstruite autour des informations réellement disponibles dans Cobblemon et des fichiers de sets Tropimon.

### Détection du format

- Détection depuis le message de file d'attente Random Battle.
- Lecture du format et des règles déclarées par le combat Cobblemon.
- Détection de secours d'une équipe générée de six Pokémon absente de l'équipe persistante du joueur.
- Isolation stricte de ces règles : aucun set Random Battle n'est appliqué aux combats classiques.

### Équipes et Team Preview

- Capture du Team Preview structuré lorsqu'il est exposé par l'écran de bataille.
- Capture du preview sous forme d'inventaire, y compris la sélection du lead Solo et Duo.
- Séparation correcte de l'équipe du joueur et de l'équipe adverse.
- Déduplication des Pokémon enregistrés après plusieurs combats ou plusieurs lectures du même écran.
- Conservation du roster adverse pendant les transitions temporaires de l'interface Cobblemon.
- Réconciliation entre la forme du preview et la forme réellement envoyée, par exemple les formes régionales.
- Préremplissage complet des Pokémon Random Battle du joueur : forme, niveau, EV, IV, nature, talent, objet et attaques disponibles côté client.

### Sets Tropimon

- Lecture prioritaire de `tropimon-random-battle-sets.json`, `tropimon-random-battle-sets` ou `tropimon.json` depuis les fichiers du jeu et les mods Tropimon installés.
- Vérification du fichier toutes les cinq secondes et rechargement automatique après modification.
- Snapshot embarqué utilisé par la version actuelle lorsqu'aucun fichier local compatible n'est trouvé.
- Prise en charge du niveau propre à chaque set au lieu d'imposer le niveau 100.
- Fusion des variantes strictement identiques pour éviter les doublons dans l'interface.
- Comparaison des variantes par niveau, objet, talent, Tera et attaques révélées.
- Déduction progressive des valeurs communes sans inventer une information qui différencie encore plusieurs sets.
- Résolution automatique du set complet lorsqu'une seule variante reste compatible.

### Sélecteur `Set N`

- Affichage uniquement pendant une Random Battle et uniquement lorsqu'il reste plusieurs variantes utiles.
- Chargement immédiat de `Set 1` dès l'apparition du sélecteur.
- Passage cyclique à `Set 2`, `Set 3` et aux variantes suivantes au clic.
- Nombre de boutons adapté au nombre réel de sets, sans limite codée à trois variantes.
- Tooltip indiquant le numéro, le nombre total de sets, l'objet, le talent et les quatre attaques.
- Placement compact à côté du niveau afin de ne pas masquer les types du Pokémon.

### Règles adverses Random Battle

- Les attaques utilisées par l'adversaire sont ajoutées à son set depuis les messages structurés Cobblemon.
- Chaque attaque révélée élimine les variantes incompatibles.
- Les objets, talents et types Tera révélés affinent la même déduction.
- Lorsque le preview n'expose pas le set exact, l'adversaire reçoit la même répartition d'EV que le Pokémon du joueur et une nature `Serious`, conformément aux règles configurées pour ce format.
- Les informations privées non révélées restent inconnues en dehors de ces règles et des données communes aux variantes.

## Refonte de l'interface

### Identité Cobblemon et Tropimon

- Remplacement de l'ancien contour par le panneau Cobblemon `battle_info_underlay.png` rendu en neuf zones pour préserver les coins.
- Ajout d'un fond sombre semi-transparent afin de conserver la visibilité du monde sans nuire à la lecture.
- Suppression de l'ancien visuel `VS` et recentrage des deux côtés du calculateur.
- Utilisation des modèles, animations, icônes de type et icônes d'objet fournis par les ressources Cobblemon actives.
- Compatibilité automatique avec les resource packs qui remplacent ces ressources.

### Portraits Pokémon

- Ajout de petits cadres cyan dérivés du style Tropimon.
- Ajustement de la taille des cadres et des modèles pour laisser une marge régulière.
- Ralentissement et stabilisation de l'animation des modèles dans le calculateur.
- Suppression du libellé `LIVE` puis du `?` superposé aux portraits.

### Mise en page

- Fenêtre réduite et colonnes rapprochées pour limiter les zones vides.
- Contrôles globaux regroupés en haut, conditions propres à chaque côté conservées avec leur Pokémon.
- En-têtes `Stat`, `EV`, `IV`, `Boost` et `Total` centrés sur leurs colonnes.
- Boutons de presets `Atk`, `SpA`, `Def` et `SpD` réduits et alignés à droite.
- Titre `Moves → Pokémon` mieux espacé par rapport aux statistiques et aux lignes d'attaques.
- Listes de recherche compactées et triées alphabétiquement, avec sélection immédiate au clic.
- Réinitialisation complète vers les valeurs génériques lors du passage d'un Pokémon de l'équipe à une entrée du catalogue : niveau 100, EV à 0, IV à 31, nature `Serious`, talent et attaques par défaut.

### Fermeture et navigation

- Remplacement du bouton `Fermer` par une petite croix dans l'angle supérieur droit.
- État rouge au survol ou au focus clavier.
- Retour automatique à l'interface de combat Cobblemon lorsque le calculateur a été ouvert pendant un combat.

## Intégration en jeu

- Bouton `Calc` intégré à l'écran principal des actions de combat Cobblemon.
- Commande client `/tropicalc` et diagnostic `/tropicalc debug`.
- Raccourci configurable dans les contrôles Minecraft, avec `B` comme valeur par défaut d'une nouvelle installation.
- Synchronisation automatique de l'écran ouvert à chaque changement détecté dans le combat.

## Validation

- Build Fabric Minecraft 1.21.1 validé.
- Suite automatisée : 101 tests réussis.
- Mod uniquement côté client : aucune action de combat n'est jouée automatiquement et aucune API Pokémon externe n'est appelée en jeu.
