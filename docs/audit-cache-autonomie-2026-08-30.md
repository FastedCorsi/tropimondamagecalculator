# Damage Calculator 0.3.38 : cache et autonomie

## Perimetre

Optimisation de la version de travail presente le 30 aout 2026, pas une reimplementation
des regles de combat. Les modifications preexistantes du depot sont conservees.
Ni installation dans le launcher, ni commit, ni push pendant cette intervention.

`DamageCalculator.java` n'a pas ete modifie pendant cette intervention.
SHA-256 avant/apres :
`915577A7215F17DD032907217D3D4E1D780289E1152A8A8829C29729FE6CF407`.
Les regles Random, les hypotheses sur l'adversaire et les formules Solo/Duo restent les memes.

## Diagnostic du cache existant

`refreshDamageCache()` recalculait les huit emplacements des deux Pokemon au premier
appel, meme lorsqu'UI Battle demandait seulement un emplacement. Un contexte temporaire
recreait ce cout. Le cache reposait sur une empreinte : elle ne distinguait pas toutes
les modifications de donnees d'espece/nature conservant le meme identifiant.
L'interface recalculait aussi les totaux statistiques a chaque image.

La reference de comparaison `LegacyDamageCache`, reservee aux tests, conserve l'ancien
algorithme eager et son empreinte. Elle appelle exactement le meme moteur de formules.

## Optimisations

- Cache local par cote et emplacement : un appel calcule uniquement le mouvement demande.
- Reutilisation entre contextes UI Battle via un LRU borne a 128 resultats.
- Aucun Pokemon ni terrain mutable partage entre contextes. Les listes de resultats
  partagees sont immuables ; les consommateurs UI Battle audites les lisent uniquement.
- Comparaison exacte des entrees, pas de decision fondee seulement sur un hash.
- Une validation commune par image dans DamageCalcScreen, puis lecture des emplacements.
- Totaux statistiques, texte, largeur et couleur reutilises tant que leurs entrees ne changent pas.
- Libelles et tooltips de degats reconstruits seulement si le resultat ou la langue change.
- Suppression de la liste temporaire de types dans le rendu, sans modifier les types calcules.
- Copie de PokemonSet sans initialiser les talents/attaques par defaut avant de les ecraser.

L'API historique `calculateMove(boolean, int)` valide toujours ses entrees. Les nouvelles
lectures `calculatePreparedMove` / `preparedStat` sont internes au lot UI : aucun changement
d'etat ne doit intervenir apres `prepareCalculations()` et avant ces lectures.

## Invalidation

Les instantanes comprennent les deux especes/formes (identifiants, noms, types, poids,
statistiques de base, aspects, evolution), niveau, nature complete, objet, talent,
informations connues/inconnues, PV actuels/max observes, statut, Tera, EV, IV, boosts,
historique de combat et tous les effets des deux terrains/partenaires utilises par le moteur.
L'attaque complete et son drapeau Z sont compares par emplacement.
La langue et une generation de rechargement des ressources font partie des entrees.

Les changements directs/par reflexion, les mutations en place des statistiques de base,
les collisions de hash, Swap, suppression d'attaque et changement de forme sont testes.
Les champs de provenance (UUID, noms de combat, probabilites ranked, saisies) ne sont pas
des entrees des formules et ne provoquent pas de recalcul inutile.
Une autre attaque ou son bouton Z n'invalide pas un emplacement independant.

## Autonomie

- Cadre graphique sous le namespace du calculateur, sans ressource TropimodClient requise.
- Aucun acces aux classes/services/configurations des autres mods Tropimon en production.
- Suppression de la lecture reflective des DTO prives de Team Preview.
- Preview d'inventaire via Minecraft, equipe/combats via Cobblemon : etat propre au calculateur.
- Recherche des sets dans les fichiers de donnees du jeu et son propre dossier de configuration ;
  plus de parcours des archives des autres mods ou de leurs configurations.
- Le snapshot Random deja embarque reste identique. Le client HTTP ranked existant appartient
  au calculateur ; il ne delegue pas ses appels a TeamBuilder ou UI Battle.
- La lecture directe d'archive d'especes de secours est limitee a Cobblemon officiel.
  Les formes publiees dans ses registres ou ressources restent disponibles.
- Pas de bibliotheque commune obligatoire.

### Limite fonctionnelle explicite

Un Team Preview exclusivement affiche dans un ecran proprietaire, sans inventaire public
equivalent ni donnees Cobblemon, n'est plus inspecte. Les equipes publiques d'inventaire et
les informations visibles de combat restent prises en charge. Les sets complets caches
dans un DTO d'un autre mod ne sont pas recuperes. Ne pas reintroduire cette dependance
pour contourner cette limite ; un futur support doit decoder une source publique directement.

Les protections existantes du bouton de combat, du retour a BattleGUI, de la separation
des equipes, des informations inconnues et du nettoyage de fin de combat sont conservees.
La mutualisation porte exclusivement sur les valeurs de calcul, pas sur l'etat de combat.

## Compatibilite reflective UI Battle

Conservation et test de resolution des classes `fr.tropimon.damagecalc.*`, notamment :

- `DamageCalcState()` ; `attacker`, `defender`, `field` ; `setFromBattle`, `calculateMove`, `effectiveMove`.
- `PokemonSet.moveAt`, `itemKnown` ; `BattlePokemonSnapshot.player/opponent`.
- `CobblemonBattleDataProvider.activeBattlePokemon` et le prive `battlePokemonSet`.
- `DamageCalculator.typeEffectiveness`, `effectiveMoveType`, `effectivePower` et signatures exactes.
- `MoveData.id/name/type`, `DamageResult.minPercent/maxPercent/koChance/notes/warnings`.
- `FieldState.attackerSide/defenderSide`, `PokeType`, `SideConditions`.

`calculationFingerprint()` reste disponible, meme sans appel interne actuel.
Le test de coexistence charge le vrai JAR UI Battle installe et appelle son bridge `available()`.
Les references aux autres mods dans `src/smoke` sont uniquement le harnais de test,
exclu du JAR livre et de ses dependances.

## Code mort retire

Ancien remplissage eager des huit emplacements et ses variables de cache, `SideConditions.hasAny()`
sans consommateur, chemins/DTO de preview prives devenus interdits, initialisations de talents
en double. Les points d'entree utilises par reflexion ne sont pas consideres comme du code mort.

## Verification

- Build final reussi ; 151 tests, 0 echec, 0 ignore avec le benchmark active.
- Comparaison integrale de 680 DamageResult et 1 020 totaux sur 85 scenarios deterministes.
- Solo, Random (85 EV, Serious, niveaux variables), Duo, terrains, meteo, partenaires,
  Tera/Z, boosts, brulure, multi-coups et compteurs d'historique.
- Tests d'invalidation de chaque champ de modele utilise par les formules.
- Copie de chaque champ PokemonSet, independance des conteneurs, LRU et reload.
- Tests existants du moteur et resolution des formes Cobblemon conserves.
- Lancements Minecraft de production, monde plat jetable et screenshots du calculateur.
- Profil isole : Minecraft 1.21.1, Fabric Loader 0.17.2, Fabric API 0.116.6,
  Fabric Kotlin 1.13.7, Cobblemon 1.7.2, calculateur et harnais uniquement.
- Profil coexistence : UI Battle, TeamBuilder, TeamHunt, BidMaker, CatchPreview, ChatFilter,
  StocksManager installes, et leurs dependances TropimodClient/Xaero/Mega Showdown/Architectury/
  GeckoLib/Trinkets. Ces dependances appartiennent aux autres mods, pas au calculateur.
- Aucun JAR installe modifie. Le client utilisateur et ses sauvegardes n'ont pas ete utilises.

Les premieres tentatives de coexistence ont echoue sur les dependances transitives manquantes
des autres mods (TropimonCore, puis Trinkets). Le profil complet passe sans modifier ces mods.
Le profil isole charge 1 391 especes/formes, la coexistence 1 405 ; chacun charge 936 attaques.

Les tests runtime sont des smoke tests d'ouverture/rendu/calcul/liaison : ils ne remplacent
pas une campagne de combats classes sur serveur avec tous les protocoles Team Preview.
Le benchmark mesure le calcul et ses allocations, pas le FPS du jeu ni toutes les causes de saccades.

### Mesures finales

Java 21.0.6, 400 iterations de chauffe puis 600 mesures par cas, meme JVM et moteur de calcul.
Temps indicatifs, sensibles au JIT et a la charge de la machine ; pas un benchmark JMH.
Les octets sont les allocations du thread mesure, pas la memoire retenue par le cache.

| Cas | Avant (ns/appel) | Apres (ns/appel) | Avant (octets/appel) | Apres (octets/appel) |
| --- | ---: | ---: | ---: | ---: |
| Un slot nouveau, cache froid | 1 045 460 | 153 998 | 3 332 163 | 456 533 |
| Un slot, 50 etats reutilises | 2 138 956 | 31 766 | 3 304 625 | 952 |
| Huit slots modifies | 988 803 | 1 035 878 | 3 304 470 | 3 299 382 |
| Un slot deja en cache | 332 | 240 | 80 | 0 |
| Contextes temporaires identiques | 1 041 737 | 19 086 | 3 359 229 | 15 661 |
| Lot UI stable, huit slots + douze stats | 105 178 | 3 990 | 377 744 | 5 542 |

Le calcul froid d'un seul slot alloue environ 86 % de moins. La reutilisation de contextes
reduit les allocations du cas mesure d'environ 99,5 %, et le lot UI d'environ 98,5 %.
Le recalcul reel des huit attaques reste au meme ordre de cout : les formules sont inchangees,
et la validation exacte peut ajouter un peu de temps. Les deux derniers cas incluent le cout
de construction/boucle du harnais ; ils ne representent pas tout le rendu d'une image Minecraft.

Les donnees brutes du benchmark et captures sont conservees localement,
hors des fichiers destines a la publication.
Des captures a 40 et 80 ticks sont conservees pour chaque profil ; les deux modeles sont rendus.
La notification Minecraft du premier deplacement visible sur certaines captures appartient
au nouveau profil de test, pas au calculateur.

Le JAR exporte est `exports/TropimonDamageCalc-0.3.38+1.21.1.jar`.
Il contient le cadre propre au calculateur et aucune classe du harnais/tests/ancien cache.
Le JAR installe 0.3.34 conserve son SHA-256 initial :
`03F99656798C7807C61BEC65DB7A432703B5BCD1554442A9592F904FD9CA2992`.

## Reproduction

Java 21 requis. Commandes PowerShell depuis le depot :

```powershell
.\gradlew.bat build '-Ddamagecalc.benchmark=true' --console plain
$mods = Join-Path $env:APPDATA '.tropimon/mods'
.\gradlew.bat runSmoke "-PverificationMods=$mods" '-Pfabric_loader_version=0.17.2' '-Pfabric_api_version=0.116.6+1.21.1' --console plain
.\gradlew.bat runSmoke "-PverificationMods=$mods" '-Pcoexistence=true' '-Pfabric_loader_version=0.17.2' '-Pfabric_api_version=0.116.6+1.21.1' --console plain
```

Ne pas executer `installTropimonLocal` pour cette livraison.
Rapports : `build/reports/tests/test`, `build/reports/damage-cache-benchmark.json`.
Journaux/captures : `build/verification/{isolated,coexistence}`.
Ces profils ne lisent les JAR installes qu'en entree et ecrivent exclusivement sous `build/verification`.
