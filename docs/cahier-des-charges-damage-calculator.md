# Cahier des charges - Mod Damage Calculator Tropimon / Cobblemon

## 1. Objectif

Créer un mod Minecraft a part entiere pour Tropimon, dedie au calcul de dégâts Cobblemon, inspire du Damage Calculator de Pokemon Showdown.

Ce mod ne doit pas etre une fonctionnalite integree a Tropimon Companion. Il doit avoir sa propre base de code, son propre `mod_id`, sa propre configuration, ses propres ecrans et son propre cycle de build/release.

Le calculateur doit aider le joueur pendant ou hors combat a estimer les dégâts d'une attaque entre deux Pokemon, avec une precision proche de Showdown, tout en utilisant au maximum les donnees Cobblemon disponibles en jeu.

Objectif prioritaire : rendre le calcul utile en combat Cobblemon sans obliger le joueur a ressaisir manuellement toutes les informations visibles par le mod.

## 2. References

- Pokemon Showdown Damage Calculator : https://calc.pokemonshowdown.com/index.html
- Code officiel Smogon damage-calc : https://github.com/smogon/damage-calc
- Package de calcul `@smogon/calc`, utilise par l'UI Showdown : https://github.com/smogon/damage-calc
- Donnees Pokemon Showdown / `@pkmn/data`
- Documentation Cobblemon - species JSON : https://wiki.cobblemon.com/index.php/Tutorials/Creating_A_Custom_Pokemon
- Documentation Cobblemon - move/ability effects via datapacks : https://wiki.cobblemon.com/index.php/Datapackable_Move_Effects

Points verifies :

- Le depot Smogon indique que `@smogon/calc` expose le coeur de calcul et que la UI officielle est une couche separee.
- Cobblemon stocke les informations de species dans des JSON contenant notamment types, stats de base, moves et abilities.
- Depuis Cobblemon 1.7, les effets de moves et abilities peuvent etre ajoutes/remplaces via datapacks, et Cobblemon s'appuie sur une version transpilee du moteur Pokemon Showdown pour son battle engine.

## 3. Positionnement du produit

Le calculateur doit etre concu comme un mod autonome pour l'ecosysteme Tropimon/Cobblemon, sans contrainte forte liee a une implementation existante.

Le cahier des charges decrit donc :

- Le comportement attendu pour les joueurs.
- Les donnees Cobblemon a exploiter.
- Le niveau de precision attendu par rapport a Showdown.
- Les modules techniques recommandes.
- Les criteres de validation.

Les choix concrets de code, de classes, de fichiers et de framework UI restent a definir pendant la conception technique.

### 3.1 Nature du mod

Le projet cible est un mod separe :

- Nom de travail : `Tropimon Damage Calculator`.
- Type : mod client Minecraft avec integration Cobblemon.
- Plateforme cible recommandee : Fabric 1.21.1, sauf decision contraire du serveur.
- `mod_id` propose : `tropimon_damage_calc` ou `tropimon_calc`.
- Distribution : fichier `.jar` installe dans le dossier `mods`.
- Configuration : dossier dedie, par exemple `config/tropimon-damage-calc/`.
- Donnees embarquees : dex, moves, items, abilities, natures, type chart et fixtures de test.

Le mod ne doit pas dependre d'un autre mod Tropimon Companion pour fonctionner.

### 3.2 Relation avec Cobblemon

Deux strategies sont possibles :

1. Cobblemon obligatoire.
   - Plus simple si le serveur Tropimon impose toujours Cobblemon.
   - Permet d'utiliser directement l'API Cobblemon.
   - Le mod ne demarre pas sans Cobblemon.

2. Cobblemon optionnel mais fortement integre.
   - Le calculateur manuel fonctionne sans Cobblemon.
   - Les fonctions live se desactivent si Cobblemon est absent.
   - Plus robuste pour les tests et le developpement.

Decision recommandee : Cobblemon obligatoire si le mod est reserve au serveur Tropimon, optionnel si le mod doit etre partage plus largement.

## 4. Perimetre fonctionnel cible

### 4.1 Modes d'utilisation

Le calculateur doit proposer trois usages :

1. Calcul live en combat Cobblemon.
   - Detection automatique des Pokemon actifs.
   - Remplissage automatique des niveaux, types, HP, statuts et dernieres attaques detectees.
   - Selection rapide attaquant / defenseur depuis l'onglet Live ou Calc.

2. Calcul manuel hors combat.
   - Champs Pokemon 1, Pokemon 2, attaque, niveau, item, ability, nature, EV, IV, boosts.
   - Suggestions depuis les donnees chargees.
   - Resultat instantane apres modification.

3. Calcul semi-automatique.
   - Le joueur choisit les Pokemon detectes en jeu, puis ajuste seulement les infos inconnues : item, ability, EV, IV, nature, Tera, terrain, meteo.

### 4.2 Interface inspiree Showdown

L'interface doit reprendre les concepts du screenshot Showdown, adaptes au format Minecraft :

- Deux colonnes Pokemon : attaquant et defenseur.
- Une zone centrale Field.
- Une zone resultats en haut.
- Liste des moves disponibles avec min/max en pourcentage.
- Details du calcul pour le move selectionne.
- Boutons compacts pour weather, terrain, screens, hazards, statut, doubles/singles, crit, Tera.

L'UI ne doit pas chercher a copier pixel-perfect Showdown. Elle doit rester lisible dans Minecraft, avec des boutons courts, des panneaux compacts et un mode plein ecran live.

## 5. Utilisation maximale de Cobblemon

### 5.1 Donnees Cobblemon prioritaires

Quand Cobblemon est present, les donnees suivantes doivent venir de Cobblemon avant Showdown :

- Pokemon actif en combat.
- Espece reelle et forme.
- Niveau.
- Types reels de la forme.
- HP courants et HP max.
- Statut.
- Moves connus si accessibles.
- Ability si accessible cote client.
- Item tenu si accessible cote client.
- Stats calculees ou stats visibles si l'API Cobblemon les expose.
- Tera type si Cobblemon ou un addon serveur l'expose.
- Etat du combat : singles/doubles, terrain, meteo, screens, boosts, hazards, tour.

Showdown doit rester le fallback ou la reference pour :

- Table des moves.
- Types et base stats si Cobblemon ne donne pas l'information.
- Items, abilities et natures.
- Formules standards de generations.

### 5.2 Strategie technique Cobblemon

Approche recommandee :

1. Decider le niveau de dependance Cobblemon des le depart.
   - Si le mod est reserve a Tropimon : declarer Cobblemon comme dependance obligatoire.
   - Si le mod doit aussi servir de calculateur manuel hors Cobblemon : declarer Cobblemon comme dependance optionnelle.
   - Dans les deux cas, isoler les appels Cobblemon dans une couche dediee.

2. Creer un adaptateur dedie.
   - Nouveau composant propose : `CobblemonBattleDataProvider`.
   - Role : convertir les objets Cobblemon en modele interne du damage calculator.
   - Le reste du calculateur ne doit pas dependre directement des classes Cobblemon.

3. Hydrater le modele interne Pokemon avec des donnees plus riches.
   - Ajouter nature, EV, IV, ability, item, moves exacts, formes, genre si utile.
   - Garder des champs `unknown` plutot que d'inventer une valeur.

4. Synchroniser les etats de terrain.
   - Lire le battle state Cobblemon si possible.
   - Sinon continuer le parsing chat comme fallback.

## 6. Moteur de calcul

### 6.1 Exigence de precision

Le moteur doit viser une divergence maximale acceptable de 1 point de degat sur les cas standards par rapport a Showdown/Smogon, sauf lorsque Cobblemon a des differences volontaires de datapack ou serveur.

Le calcul doit produire :

- Degats min et max en PV.
- Degats min et max en pourcentage.
- Les 16 rolls.
- Probabilite OHKO, 2HKO, 3HKO.
- Description textuelle courte : STAB, super efficace, burn, weather, terrain, item, ability, screen, Tera, hazards.

### 6.2 Donnees a supporter

MVP obligatoire :

- Generation 9 par defaut, avec selection possible Gen 1-9.
- Singles et Doubles.
- Categories Physical, Special, Status.
- STAB, type chart, immunites.
- Natures, EV, IV, boosts -6 a +6.
- Crit.
- Burn.
- Weather : None, Sun, Rain, Sand, Snow.
- Terrains : Electric, Grassy, Misty, Psychic.
- Screens : Reflect, Light Screen, Aurora Veil.
- Hazards : Stealth Rock, Spikes, Toxic Spikes, Sticky Web au moins pour l'affichage et les effets pertinents.
- Tera offensif et defensif.
- Items courants : Choice Band, Choice Specs, Life Orb, Expert Belt, Assault Vest, Eviolite, Heavy-Duty Boots, Leftovers, resist berries.
- Abilities courantes : Huge Power, Pure Power, Guts, Adaptability, Technician, Unaware, Mold Breaker, Levitate, Filter, Solid Rock, Prism Armor, Thick Fat, Friend Guard, Infiltrator, Ruin abilities.

Hors MVP / phase 2 :

- Tous les effets complexes de moves.
- Tous les effets complexes d'abilities.
- Dynamax, Z-Moves, Megas si le serveur ne les utilise pas.
- Effets customs de datapacks serveur si non exportables.

### 6.3 Choix d'architecture pour le moteur

Deux architectures sont possibles :

Option A - Moteur natif Minecraft/Java/Kotlin.

- Avantage : simple a embarquer dans un mod client, pas de runtime JS.
- Inconvenient : il faut reimplementer et maintenir de nombreux cas speciaux.

Option B - Moteur Smogon embarque/adapte.

- Utiliser `@smogon/calc` ou une sortie compilee offline pour generer des fixtures et/ou un moteur embarque.
- Avantage : precision proche Showdown.
- Inconvenient : integration Java/Minecraft plus complexe.

Decision recommandee :

- Court terme : implementer un moteur natif simple mais teste contre `@smogon/calc`.
- Moyen terme : generer automatiquement des fixtures depuis `@smogon/calc` pour verrouiller les resultats.
- Long terme : envisager un bridge JS seulement si la maintenance Java devient trop couteuse.

## 7. Fonctionnalites detaillees

### 7.1 Selection Pokemon

- Recherche manuelle par nom avec suggestions.
- Selection depuis Pokemon visibles autour du joueur.
- Selection depuis combat actif Cobblemon.
- Clic gauche = attaquant, clic droit = defenseur.
- Affichage sprite ou modele si disponible.
- Affichage niveau, types, statut, HP.

### 7.2 Edition stats

Pour chaque Pokemon :

- Nature.
- EV par stat.
- IV par stat.
- Boost par stat.
- Item.
- Ability.
- Tera type.
- Statut.
- HP courant.

L'UI doit permettre un preset rapide :

- 0 EV.
- 252 EV.
- IV 31.
- Niveau 50 / 100.
- Nature neutre.
- Reset boosts.

### 7.3 Moves

- Afficher les moves detectes en live.
- Permettre la recherche de n'importe quel move Showdown.
- Afficher type, base power effective, category, accuracy si utile.
- Calculer tous les moves connus contre la cible et trier par degats max.
- Marquer les moves status a 0%.

### 7.4 Field

Parametres globaux :

- Singles/Doubles.
- Weather.
- Terrain.
- Gravity.
- Magic Room.
- Wonder Room.
- Protect.
- Helping Hand.
- Friend Guard.
- Spread move.

Parametres cote defenseur :

- Reflect.
- Light Screen.
- Aurora Veil.
- Stealth Rock.
- Spikes.
- Toxic Spikes.
- Leech Seed.
- End-turn recovery/damage.

### 7.5 Resultats

Format attendu :

- Ligne principale : `252 SpA Abomasnow Blizzard vs. 0 HP / 0 SpD Abomasnow: 162-192 (50.4 - 59.8%) -- guaranteed 2HKO`
- Ligne courte Minecraft : `Blizzard 162-192 (50-60%) | 2HKO 100% | STAB x2`
- Details : rolls, entry damage, end-turn damage, notes modificateurs.

## 8. Contraintes non fonctionnelles

- Le calcul doit etre instantane cote client.
- Pas d'appel reseau pendant le jeu.
- Toutes les donnees necessaires doivent etre embarquees ou lues depuis Cobblemon/local config.
- Le mod doit continuer a fonctionner si Cobblemon n'est pas installe.
- Les erreurs de lecture Cobblemon ne doivent pas crash le client.
- Les donnees inconnues doivent etre affichees clairement, pas silently remplacees par une valeur trompeuse.
- Le rendu doit rester lisible en 1280x720 minimum.

## 9. Donnees et synchronisation

### 9.1 Sources de donnees

Ordre de priorite :

1. Donnees live Cobblemon.
2. Donnees de team configurees par le joueur dans le mod.
3. Overrides locaux Tropimon.
4. Donnees exportees Showdown / `@pkmn/data`.
5. Fallback minimal uniquement pour eviter un crash.

### 9.2 Overrides serveur Tropimon

Le serveur peut avoir des modifications Cobblemon/datapack. Il faut donc prevoir :

- Fichier local de moves custom, par exemple `moves.tsv` ou `moves.json`.
- Fichier local de species custom, par exemple `species.tsv` ou `species.json`.
- Fichier local d'abilities custom, par exemple `abilities.tsv` ou `abilities.json`.
- Fichier local d'items custom, par exemple `items.tsv` ou `items.json`.
- Afficher la source active des donnees dans l'ecran.

## 10. Architecture proposee

Composants :

- Module UI : affichage Minecraft du calculateur.
- Module state : selection courante, options de field, filtres et presets.
- Module Cobblemon provider : lecture des donnees live Cobblemon.
- Module data dex : donnees Pokemon, moves, items, abilities, natures et types.
- Module calculator : formule de degats et cas speciaux.
- Module formatter : textes style Showdown et textes courts Minecraft.
- Module overrides : donnees custom serveur Tropimon.
- Module tests/fixtures : comparaison contre Showdown/Smogon.
- Module config : preferences utilisateur, raccourcis, chemin des overrides.
- Module packaging : metadata Fabric/NeoForge, assets, versioning et release `.jar`.

Principe :

- Le tracker collecte.
- Le provider Cobblemon enrichit.
- L'etat calc conserve les choix utilisateur.
- Le moteur calcule.
- Le formatter produit les textes.
- L'UI affiche sans contenir la logique metier.

## 11. Phasage

### Phase 1 - Stabilisation MVP

- Definir la liste des champs pris en charge par le MVP.
- Isoler la lecture Cobblemon dans un provider dedie.
- Ajouter format resultats style Showdown.
- Ajouter affichage des 16 rolls.
- Ajouter tests unitaires sur 20 cas classiques.
- Verifier build Gradle.

### Phase 2 - Integration Cobblemon forte

- Lire plus de donnees depuis l'objet Pokemon/Battle Cobblemon.
- Recuperer moves actifs et PP si accessible.
- Recuperer boosts, weather, terrain et sides depuis le battle state si accessible.
- Gerer formes Cobblemon correctement.
- Ajouter overrides serveur pour species/items/abilities.

### Phase 3 - Precision Showdown

- Generer des fixtures depuis `@smogon/calc`.
- Comparer Java vs Smogon sur plusieurs generations.
- Corriger les cas speciaux de moves/items/abilities les plus utilises sur Tropimon.
- Ajouter rapport de validation dans l'UI admin/test.

### Phase 4 - UX combat

- Vue live plein ecran plus dense.
- Tri automatique des meilleurs moves.
- Badges OHKO/2HKO/3HKO.
- Presets rapides PvP : bulky, sweeper, no EV, max HP, max Atk, max SpA.
- Raccourcis clavier pour ouvrir/fermer l'assistant.

## 12. Criteres d'acceptation

Le cahier des charges est considere satisfait pour le MVP si :

- En combat Cobblemon, deux Pokemon actifs sont detectes automatiquement dans au moins un combat test.
- Le joueur peut choisir attaquant et defenseur sans taper leurs noms.
- Le calcul affiche min/max, pourcentage, rolls et chance OHKO/2HKO/3HKO.
- Les changements weather/terrain/screen/item/ability/nature/EV/IV modifient le resultat immediatement.
- Le resultat de 20 fixtures standards est identique ou a 1 PV pres de Showdown.
- Le mod ne crash pas si Cobblemon est absent.
- Le build du mod autonome passe et produit un `.jar` installable.

## 13. Risques

- Certaines informations Cobblemon peuvent ne pas etre disponibles cote client selon le serveur.
- Les datapacks serveur peuvent modifier des moves/abilities sans que le client les connaisse.
- Reimplementer toute la logique Showdown en Java peut devenir couteux.
- Le parsing chat est fragile selon la langue et les messages custom.
- Les formes regionales/custom peuvent mal matcher avec les IDs Showdown.

Mitigation :

- Garder les donnees inconnues visibles.
- Autoriser les overrides locaux.
- Tester contre `@smogon/calc`.
- Centraliser le mapping d'IDs Cobblemon <-> Showdown.
- Ne jamais faire dependre le rendu UI d'un appel reflection non protege.

## 14. Livrables attendus

- Mod Minecraft autonome installe sous forme de `.jar`.
- Ecran in-game dedie au damage calculator.
- Donnees Showdown embarquees et regenerables.
- Provider Cobblemon isole.
- Tests de parite contre fixtures Showdown.
- Documentation joueur courte : ouvrir l'assistant, choisir attaquant/defenseur, lire les resultats.
- Documentation technique : comment ajouter un move/item/ability custom Tropimon.

## 15. Definition de "utiliser un max Cobblemon"

Pour ce projet, "utiliser un max Cobblemon" signifie :

- Cobblemon est la source de verite pour ce qui existe actuellement en jeu.
- Showdown est la source de verite pour les formules et donnees competitives standards.
- Les donnees Cobblemon priment des qu'elles sont disponibles.
- Les donnees Showdown ne remplacent Cobblemon que quand Cobblemon ne donne pas l'information.
- Les differences serveur doivent etre supportees par overrides locaux ou datapack export.
