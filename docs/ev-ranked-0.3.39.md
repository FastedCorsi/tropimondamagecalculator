# Tropimon Damage Calculator 0.3.39 : EV suggeres

## Comportement

- Hors Random Battle, le pre-remplissage adverse utilise aussi le champ `spreads` de la reponse Ranked Tropimon deja chargee.
- Une repartition complete est choisie selon sa frequence, sans melanger les maxima de plusieurs repartitions. Les statistiques absentes valent zero.
- Les repartitions invalides sont ignorees : valeurs hors de 0-252, total superieur a 510, statistiques inconnues ou repetees, syntaxe incorrecte, frequence non finie ou hors de 0-100.
- Sans repartition valide, aucun EV n'est invente.
- Les EV suggeres restent une estimation, pas des informations revelees. Une infobulle FR/EN sur la colonne EV indique leur frequence et cette limite.
- Les champs restent editables. Une saisie manuelle, y compris une remise a zero ou un preset, n'est pas remplacee par une synchronisation ulterieure.
- Les IV, les PV observes et les donnees connues du joueur ne sont pas modifies par la suggestion.
- Les regles Random Battle existantes restent prioritaires : 85 EV par statistique et nature Serious.
- Aucune requete HTTP supplementaire : le parsing reutilise la reponse du profil, son cache et le chargement asynchrone existants.

## Verification du 30 aout 2026

- `gradlew.bat test` et `gradlew.bat build` : succes.
- 164 tests passes, dont 14 nouveaux tests EV. Le benchmark optionnel est ignore dans cette execution.
- Couverture EV : reponse API absente/invalide, choix du plus frequent, egalites, reception tardive, synchronisation, copies, edition manuelle, presets, confidentialite, nettoyage, swap et priorite Random Battle.
- Tests existants de parite des calculs, cache, formes, autonomie et compatibilite conserves et passes. Les nouvelles valeurs EV invalident correctement les resultats ; leur simple provenance ne declenche pas de recalcul.
- Minecraft 1.21.1, Fabric Loader 0.17.2 : test de demarrage et captures en profil isole (Cobblemon, Fabric API, Kotlin) et en coexistence avec les mods Tropimon disponibles.
- Dans ces deux profils : pre-remplissage EV visible, effacement manuel d'un champ puis synchronisation sans ecrasement, parite des calculs en Duo avec neige et Helping Hand, et verification de la traduction de l'infobulle.
- Le profil de coexistence verifie egalement l'acces par reflexion de UI Battle au calculateur.
- Ces tests en jeu utilisent une reponse JSON de test ; ils ne remplacent pas un test de match sur le serveur Tropimon.

Captures conservees localement, exclues de la publication.

Les formules n'ont pas ete modifiees. SHA-256 de `DamageCalculator.java` conserve :
`915577A7215F17DD032907217D3D4E1D780289E1152A8A8829C29729FE6CF407`.

Le JAR 0.3.39 est exporte sans remplacer le JAR 0.3.38 installe dans le launcher. Aucun push.
