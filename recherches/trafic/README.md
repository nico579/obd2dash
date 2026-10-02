# Recherche Trafic II — journal de travail

Mis en place le **3 octobre 2026**, à la demande de l'utilisateur, pour rendre les recherches
vérifiables et éviter de recommencer les mêmes pistes. Ce dossier est suivi par Git.
Les traces brutes restent dans `captures/`, exclu de Git ; leurs empreintes sont
conservées dans [sources.json](sources.json).

## Résultat actuel

Identité effectivement lue : **25 / 037 / 00CB / 1200**, en KWP2000 fast init,
réponse physique `7A`, testeur `F1`. `25` est décimal : l'octet Vdiag est `0x19`.
Véhicule : Trafic II phase 1, 2005, diesel 2,5 l ; ne pas lui attribuer par défaut
le moteur 1,9 l d'une définition voisine.

**Aucun dictionnaire de mesures correspondant exactement à cette identité n'est
validé.** Les identifications `2180` et `2181` ont répondu en septembre ; cela
n'établit pas la session requise pour les autres lectures. Le catalogue constructeur
de l'application reste vide. La version 0.9 a corrigé le transport BLE Android et
l'indépendance des diagnostics standard ; elle n'ajoute pas les mesures Renault.

## Règle de poursuite

1. Lire ce journal et [actions.jsonl](actions.jsonl) avant toute nouvelle recherche.
2. Inscrire la requête exacte ou l'URL, la date, le résultat, les preuves et la
   décision. Une consultation répétée doit nommer l'élément nouveau attendu.
3. Ne pas présenter une revérification comme une découverte. Une piste écartée
   ne se rouvre qu'avec une nouvelle donnée : autre révision, définition complète,
   identité exacte ou trace de référence.
4. Une absence de résultat dans un moteur de recherche ne prouve pas l'inexistence
   d'une définition. Une erreur HTTP n'est pas une preuve d'absence.
5. Ne pas envoyer de commande au véhicule pendant cette recherche documentaire.
   Ne pas élargir les lectures autorisées à partir d'une correspondance approximative.

La vérification locale du registre se lance par :

```powershell
rtk proxy python tools/validate_trafic_research.py
```

## Historique reconstitué — acquis, pas nouvelles découvertes

Les lignes ci-dessous proviennent des bilans datés existants. Les anciennes recherches
n'ont pas toutes conservé leurs requêtes exactes ni leurs heures : ces informations
ne sont pas inventées. Les nouvelles actions sont consignées séparément.

| ID | Date du bilan | Piste / preuve | Résultat et décision | Condition pour reprendre |
| --- | --- | --- | --- | --- |
| H01 | 12/09 | Deux captures `2180`, puis `2181` | Identité répétée et VIN valide ; profil de mesures inconnu. | Nouvelle définition ou trace, pas nouvelle lecture d'identité identique. |
| H02 | 12/09 ; revu 02/10 | Catalogue officiel RenCOM, EDC15C Vdiag 19 / programme CB | **63 mesures annoncées**, sans octets, formules ni session. Constat connu depuis septembre. | Publication des définitions ou d'une trace de communication applicable. |
| H03 | 12/09 | Manuel public RenCOM, §§ 6.1, 8.4 et 9.1 | Décrit l'interface et les exports, pas le dictionnaire. Téléchargement du logiciel soumis au numéro de série. | Nouvelle documentation technique accessible. |
| H04 | 12/09 ; revu 02/10 | Code DDT4All, scanner et comparateur | Offsets de l'identité confirmés ; code de reconnaissance sans définition applicable. Correspondances approximatives exclues. | Fichier ECU avec identité exacte et lectures documentées. |
| H05 | 12/09 ; revu 02/10 | Code PyRen, scanner, mnemonics et défauts | `17FF00` est une **piste documentaire STD_A**, avec session et décodage issus d'une base externe. Commande non testée ici. | Définition correspondant à notre ECU et conditions d'accès vérifiées. |
| H06 | 02/10 | Index public CLIP 166 | Références de documents identifiées ; **index seulement**, sans requêtes ni signature complète. | Contenu de ces documents et critères d'identité, pas une autre consultation de l'index. |
| H07 | 12/09 ; revu 02/10 | Extrait `EDC_15C_C___VB2___IMA_v1.3` sur un forum | **Écarté : Vdiag 24 décimal**, alors que la capture donne 25. Provenance non validée. | Autre définition explicitement Vdiag 25 ; ne pas retoucher la signature. |
| H08 | 13/09 | Projet FLOKHOME Vivaro EDC16C36 | **Écarté : ECU, CAN 250 kbit/s et session différents.** | Preuve directe concernant EDC15C KWP. |
| H09 | 13/09 | Bibliothèque K-Line / manuel et base fjvva EDC15 | Primitives utiles, mais pas de dictionnaire Renault ; base de fichiers de flash. | Contribution contenant de vraies définitions de lectures pour notre identité. |
| H10 | 13/09 | Adresses OBDb Opel-Vivaro / Nissan-Primastar | HTTP 404 à cette date ; cela ne prouve pas une absence générale de données. | Dépôt créé ou renommé, ou nouvelle source identifiée. |
| H11 | 02/10 | Autodiag2 EDC15C13 et Trafic F9Q762 | Métadonnées et variante 1,9 l ; pas de dictionnaire de notre 2,5 l. | Nouvelle définition complète avec correspondance exacte. |
| H12 | 02/10 | Sept journaux KONNWEI BLE PC | 101 échanges, 10 trames KWP physiques valides ; PID usuels refusés. `43000000` reste ambigu : aucune absence de défaut confirmée. | Définition du format et trace de référence ; pas suppression arbitraire d'un octet. |
| H13 | 03/10 | Inventaire ciblé du dépôt et de `Downloads` | Aucun fichier de base ECU trouvé avec les filtres DDT/CLIP/EDC15/archives utilisés. Portée limitée à ces noms et dossiers. | Nouveau fichier local identifié ou autre emplacement concret. |

Les sources primaires archivées le 2 octobre sont enregistrées avec leur URL,
révision quand elle existe, taille et SHA-256. Les rapports locaux utilisés pour
reconstituer l'historique sont également référencés dans `sources.json`.

## Pistes à examiner à partir de maintenant

| ID | Cible précise | Information nouvelle recherchée | Arrêt de la piste |
| --- | --- | --- | --- |
| P01 | `NJD_EDC15C_00CB_18_A` / ref. 1441, `NJD_EDC15C_CBB3_19_A` / 1472, `INJ_EDC15_X83_19_A` / 10496 | Contenu de définition, identité complète, conditions de lecture. Le nom et le modèle de la fiche ne suffisent pas. | Aucun contenu vérifiable ; ou identité/session incompatibles. |
| P02 | Contributions originales DDT4All / PyRen, fichiers ECU et traces KWP EDC15C | Autodent `25/037/00CB/1200`, commandes et réponses réellement documentées. | Seulement code générique, simulation, autre Vdiag ou archive sans provenance. |
| P03 | Sources originales Trafic/Vivaro/Primastar **2,5 l EDC15C KWP** | Trace de lecture existante permettant de relier une mesure à ses octets et sa formule. | CAN/EDC16, flash, simple liste de compatibilité ou valeurs sans trames. |

Une piste n'aboutit à l'intégration qu'avec une identité exacte, l'adressage, les
conditions de session, les requêtes, le format des réponses, les conversions, les
droits d'utilisation et des exemples vérifiables. Les étapes partielles sont utiles,
mais ne sont pas annoncées comme un support déjà disponible.

## Travail du 3 octobre

- Création de ce journal versionné et import des références existantes, sans nouvel
  essai sur le véhicule. Les consultations répétées de RenCOM sont regroupées sous H02.
- **R002–R008** : requêtes exactes sur les noms CLIP, puis recherche dans l'index de
  code GitHub. Pour les trois références ciblées, cet index retourne uniquement le
  CSV déjà connu. Aucun contenu de ces documents n'est obtenu par cette méthode.
- **R009–R019** : recherches complémentaires de noms et champs de définitions.
  Les recherches par nombres ou chaînes hexadécimales produisent beaucoup de bruit
  (UUID, couleurs, autres projets). Elles ne permettent pas de conclure qu'un profil
  existe ou n'existe pas. La recherche par nom `EDC_15C_C` identifie cinq fichiers.
- **R020–R024, R032** : archivage et comparaison des cinq fichiers ci-dessous avec
  l'identité réellement capturée. **19 déclarations d'identité comparées, dont cinq
  répétées dans la copie ; aucune correspondance exacte.** Rapport vérifiable dans
  [candidats_2026-10-03.json](candidats_2026-10-03.json).
- **R025–R031** : élargissement aux noms voisins et vérification de l'arborescence
  du dépôt, pour compléter l'index de recherche. L'arborescence retourne 14 435
  entrées, sans troncature ; les noms EDC15 dans `public/ecus/` désignent les mêmes
  cinq fichiers. Les recherches de dépôts supplémentaires n'apportent pas de
  définition applicable dans les résultats retournés. Le reste du Web et les
  fichiers portant un autre nom ne sont pas couverts par cette conclusion.

### Fichiers candidats nouvellement inspectés

Publication : [laravelcompany/ecudocs.com, révision
3a006068dc8908a0d71c1867547ce406d8fdab8e](https://github.com/laravelcompany/ecudocs.com/tree/3a006068dc8908a0d71c1867547ce406d8fdab8e/public/ecus).
Ce dépôt tiers fournit des fichiers consultables, **pas une validation Renault**.
Leur provenance constructeur et leurs droits de réutilisation ne sont pas établis
par cette inspection ; les fichiers restent dans les captures locales.

| Source | Fichier | Vdiag déclaré (décimal) | Versions déclarées | Décision pour `25/037/00CB/1200` |
| --- | --- | --- | --- | --- |
| D01 | `EDC_15C_C___VC1__IMA_evol1.json` | 35 | C000, C100 | Vdiag et version différents : exclu. |
| D02 | `EDC_15C_C___VB2___IMA_v1.3.json` | 24 | B000, **1200**, B100, B200, 1300 | Trois champs proches ; Vdiag différent : exclu. Le nom était déjà rencontré, son contenu est maintenant archivé. |
| D03 | `EDC_15C_C___VB3___IMA_evol1.json` | 24 | B300 | Vdiag et version différents : exclu. |
| D04 | `EDC_15C_C___VA1_a_VA5___IMA_evol1.json` | 20 | A000, A200, A400, A100, A300, A500 | Vdiag et version différents : exclu. |
| D05 | `EDC_15C_C___VB2___IMA_v1.3 - Copy.json` | 24 | Identités de D02 | Exclu ; adresse déclarée **12**, contre **7A** dans les autres fichiers et la capture. Ce n'est pas une copie identique. |

Ces cinq fichiers contiennent des requêtes et données, mais **aucune n'est
transposée au camion** : l'identité échoue avant l'étude de session et des formules.
Les champs `kw1`/`kw2` de ces fichiers ne sont pas utilisés comme preuve des
keywords physiques observés par `ATKW`.

### État des pistes après cette passe

- **P01 : terminée pour le moteur de recherche et l'index GitHub consultés.** Les
  documents CLIP recherchés ne sont pas fournis par ces résultats. Pour la rouvrir,
  il faut une source contenant réellement le document, et non son nom dans un index.
- **P02 : terminée pour les cinq fichiers et la révision du dépôt ci-dessus.** Les
  identités sont incompatibles. Une autre révision ou définition doit apporter
  explicitement Vdiag 25 ; ne pas recommencer la comparaison des mêmes octets.
- **P03 : ouverte.** Une trace originale de lectures constructeur, avec l'identité
  de l'ECU, pourrait fournir une preuve différente des catalogues et profils voisins.
  Aucune trace applicable n'a été obtenue dans cette passe. La question restante est
  précisément « quelles requêtes et quelles conditions pour cet ECU ? ».

**Bilan : nouvelles exclusions vérifiables et suivi durable ; aucun nouveau support
de mesure validé, aucune commande envoyée au véhicule.** Un nouveau test du camion
n'est pas justifié par les seuls fichiers trouvés aujourd'hui.

## Bilans locaux utilisés

- `captures/recherche_profil_signature_25_037_00CB_1200.md` : signature, profil voisin
  rejeté et manuel RenCOM.
- `captures/recherche_profils_legacy_2026-09-13.md` : exclusions Vivaro/K-Line/flash.
- `captures/bilan_trafic_recherche_constructeur_2026-10-02.md` : synthèse documentaire
  et limites de l'application à cette date, avant les corrections de la version 0.9.
- `captures/identification_trafic_verifiee_2026-09-12.json` et
  `captures/bilan_trafic_ble_20261002_verifie.json` : preuves issues des traces.

Le VIN complet et les journaux radio bruts ne sont pas recopiés dans ce dossier.
