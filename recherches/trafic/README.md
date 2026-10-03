# Recherche Trafic II — journal de travail

Mis en place le **3 octobre 2026**, à la demande de l'utilisateur, pour rendre les recherches
vérifiables et éviter de recommencer les mêmes pistes. Ce dossier est suivi par Git.
Les traces brutes restent dans `captures/`, exclu de Git ; leurs empreintes sont
conservées dans [sources.json](sources.json).

## Statut — recherche arrêtée

Le **3 octobre 2026**, l'utilisateur demande : « laisse tomber, on va rester sur les véhicules
récents compatibles ». Les recherches spécifiques au Trafic II sont arrêtées et
ne seront reprises que sur sa demande explicite. L'application se concentre sur les
véhicules récents compatibles avec ses lectures OBD standard.

Les résultats ci-dessous sont conservés comme historique. Aucun support de mesures
constructeur du Trafic n'a été validé ; l'arrêt de la recherche ne signifie pas que
ce support a été terminé. Les pistes et conditions restantes ne sont plus un plan
de travail actif.

## Résultat actuel

Identité effectivement lue : **25 / 037 / 00CB / 1200**, en KWP2000 fast init,
réponse physique `7A`, testeur `F1`. `25` est décimal : l'octet Vdiag est `0x19`.
Véhicule : Trafic II phase 1, 2005, diesel 2,5 l ; ne pas lui attribuer par défaut
le moteur 1,9 l d'une définition voisine.

**Avancée de la deuxième passe du 3 octobre : une archive OldRenault a été obtenue
et inspectée.** Elle fournit maintenant des modèles, requêtes et conversions pour
des candidats EDC15C. Quatre entrées passent leurs masques publiés sur notre réponse
enregistrée ; ces masques ne contrôlent pas toute l'identité. Les variantes divergent,
et la base déclare une session `10C0`. Voir le
[bilan OldRenault](oldrenault_2026-10-03.json) et la deuxième passe ci-dessous.
La [méthode et ses possibilités de généralisation](methode_decouverte.md) sont
expliquées séparément.

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

## Pistes identifiées avant l'arrêt — historique

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

## Deuxième passe du 3 octobre — fichiers OldRenault obtenus

Cette passe **R033–R093** reprend P02 avec de nouvelles sources et une distribution
séparée. Elle n'annonce pas RenCOM, `17FF00` ou le CSV CLIP connu comme des découvertes.

### Recherches terminées dans leur périmètre

- **R033–R044, R052–R055** : nouvelles recherches Web et GitHub. Les chaînes courtes
  et nombres produisent beaucoup de résultats sans rapport ; les résultats de flash,
  pinout et autres véhicules n'établissent pas de lecture applicable. Les résultats
  paginés ne couvrent pas tous les dépôts. Une erreur réseau ou HTTP reste distincte
  d'une absence de définition.
- **R047–R051, R056–R059** : neuf autres dépôts ciblés ; six arborescences obtenues
  sans troncature, deux dépôts signalés vides par GitHub, un arbre inaccessible
  en HTTP 404. Les fichiers de code PyClip chargent une base externe `pyrendata*.zip`.
  Le fichier GPL de Clio2-fixer a une adresse `79` et aucune identité déclarée : exclu.
- **R060, R065–R067** : les pistes de distribution DDT4All sont suivies. La branche
  `3` de KarelSvo ne fournit pas de définition ECU dans ses chemins ; la description
  de sa release 5.6.0 annonce explicitement l'absence de `ecu.zip` et du dossier
  `ecus`. L'installateur et le portable MEGA ne sont pas téléchargés ou exécutés.
- **R068–R084** : l'annonce originale PyClip nomme les bases OldRenault. La page
  4PDA directe répond HTTP 403 ; son annonce reste consultable dans l'index du moteur.
  Un lien public vers la version 237 est trouvé dans une autre discussion. L'API des
  releases `andru666/pyclip3` ne fournit aucun asset ; la recherche de code par chemin
  n'apporte pas `Uces.xml`. Ces recherches ne sont pas répétées pour obtenir la base.

### Nouvelle preuve effectivement obtenue

L'[annonce originale d'andru666 du 24 octobre 2023](https://4pda.to/forum/index.php?showtopic=944879&st=8040)
nomme `pyrendata_231_and_Lada_and_OldRenault.zip`. Un
[lien public reposté](https://www.renaultfanclub.com/threads/turkce-pyclip-ve-datasi.112250/page-2)
permet d'obtenir la
[version 237](https://drive.google.com/file/d/1pcm8q1O_mnqeh-E7dmCWtMwFjWcJJmfK/view?usp=drive_link).
L'annonce et le repost prouvent l'existence de ces publications ; ils ne garantissent
ni leur authenticité Renault, ni leur compatibilité avec le camion.

**R085–R087** : téléchargement public normal, sans compte, de **81 317 636 octets** ;
7 215 entrées ZIP, dont `EcuRenault/Uces.xml` (2 516 entrées ECU) et des fichiers
`EcuRenault/Sessions/`. SHA-256 de l'archive :
`3a32c3e07606d9b5c28d000607aa8e13e57d0ddf9d511000a5183bae611dae47`.
Le ZIP et les définitions tierces restent dans `captures/`, hors Git. Le bilan dérivé
[oldrenault_2026-10-03.json](oldrenault_2026-10-03.json) est versionné.

**R088** : comparaison des **47 entrées EDC15** avec la réponse `2180` déjà capturée.
Les rangs `IdByte` sont pris après suppression du service `61`, conformément au code
PyRen archivé (S05). Quatre entrées passent les masques effectivement publiés :

| Entrée | Critères testés par la base | Modèle obtenu | Association véhicule obtenue R093 |
| --- | --- | --- | --- |
| 23015 `EDC15C_CB` | `CB` au rang 16 seulement | `FG0110192.xml` | TCOM_089 Clio/Kangoo ; critère trop large pour choisir le profil. |
| **10496 `INJ_EDC15_X83_19_A`** | **`19`, `CB`, `12` aux rangs 6, 16, 17** | **`FG0110496.xml`** | TCOM_130 `TRAFICIIp2/3`, titre interne « X83II ph2-0012-19 ». |
| 12433 `EDC15C_Vdiag19_VB0_IMA` | `19`, `CB` aux rangs 6, 16 | `FG0110304.xml` | TCOM_199 `TRAFIC II B2`. |
| 12499 `EDC 15C - Vd 19- X83 B2` | `CB`, `19` aux rangs 16, 6 | `FG0112263.xml` | TCOM_199 `TRAFIC II B2`. |

Les quatre déclarent l'adresse d'initialisation `7A` et `StartDiagSession=10C0`.
Le candidat 10496 est le plus discriminant de ces quatre ; il ne contrôle toutefois
pas le fournisseur `037`, l'octet haut `00` du logiciel ni le dernier octet `00` de
la version. Son association phases 2/3 doit aussi être expliquée pour notre phase 1.
**Une concordance des masques n'est donc pas une identité complète validée.**

**R089–R091** : les modèles XML et optimiseurs existent réellement. Les optimiseurs
sont examinés comme des paires de chaînes Unicode à l'aide de `pickletools`, avec
une grammaire limitée ; aucun `pickle.load/loads`, appel ou opcode exécutable.
Le modèle 10496 contient **57 paramètres hors contexte de défaut**, **48 paramètres
de contexte de défaut**, 51 états et 50 définitions de défauts. Ces nombres ne sont
pas des mesures réussies sur le Trafic.

Huit paramètres du candidat 10496 sont reliés à leurs requêtes et conversions :

| Information candidate | Code | Requête documentée | Conversion déclarée |
| --- | --- | --- | --- |
| Régime | PR055 | `21AA` | valeur brute, tr/min |
| Vitesse | PR089 | `21AA` | valeur brute × 0,01, km/h |
| Température d'eau | PR064 | `21A9` | valeur brute × 0,1 − 273, °C |
| Température d'air | PR058 | `21A9` | valeur brute × 0,1 − 273, °C |
| Pression rail | PR038 | `21A9` | valeur brute ÷ 10, bar |
| Pression de suralimentation | PR041 | `21A9` | valeur brute, mbar |
| Débit d'air | PR132 | `21A9` | valeur brute × 0,1, kg/h |
| Tension batterie | PR074 | `21A9` | valeur brute × 20,4 ÷ 1 000, V |

Les types et emplacements publiés figurent dans le bilan JSON. La définition ne
suffit pas à qualifier la pression de suralimentation comme relative ou absolue.
Le modèle 12263 utilise **`21AB` pour PR089**, contre `21AA` dans les trois autres :
ces fichiers ne sont pas interchangeables. Les codes manquants dans la comparaison
par `agcdRef` ne prouvent pas l'absence de ces informations dans un autre modèle.

**R092–R093** : recherche bornée de `21A9`/`21AA` dans les anciens fichiers de traces
sélectionnés, sans correspondance trouvée ; cela ne couvre pas tous les formats.
Les 122 fiches véhicule de l'archive sont ensuite examinées. TCOM_066 `TRAFIC II`
ne référence aucun des quatre candidats. Ses anciennes références 1426/1441/1452/1464
ne passent pas les masques de notre capture et n'ont pas de modèle/optimiseur dans
cette base. Cela explique une limite de ce catalogue ; cela ne démontre pas une
impossibilité générale de lire le camion.

### Conditions identifiées avant l'arrêt — historique

1. Établir quel modèle de mesures s'applique à **25/037/00CB/1200**, avec des critères
   complets ou une trace de référence ; expliquer l'association phase 1 / phases 2/3.
2. Trouver des conditions de lecture documentées **sans session `10C0`**. La base
   obtenue ne fournit pas cette preuve. La présence d'une requête de lecture ne
   prouve pas son accès en session par défaut.
3. Vérifier les réponses, emplacements, unités et conversions sur des traces
   applicables, puis établir les droits de réutilisation avant une intégration.

**Cette passe apporte enfin le contenu de définitions candidates. Aucun profil
n'est installé dans l'application et aucune commande n'a été envoyée au véhicule.**

## Clarification du blocage — R098

Le 3 octobre, les **88 définitions d'identification** des quatre modèles obtenus
sont examinées pour chercher un critère supplémentaire. Les quatre décrivent le
même décodage des champs principaux : Vdiag (`_NIDIAG`), fournisseur (`_NFOUR`),
numéro de programme (`_NPROG`) et version logiciel (`_NVERS`). Ils ne fournissent
pas de valeurs attendues supplémentaires permettant de choisir le bon modèle.
Cette vérification est archivée sous A098 dans le registre.

**Relire ces mêmes champs `2180` sur le camion ne résout donc pas l'ambiguïté.**
Les nouveaux fichiers donnent le contenu de mesures candidates, mais l'activation
des mesures du Trafic reste bloquée par l'absence de preuve suffisante du modèle
applicable et des conditions d'accès. Cela ne démontre pas que le véhicule serait
illisible avec un outil adapté ; cela ne garantit pas non plus que notre application
pourra le prendre en charge.

La donnée qui ferait avancer la recherche est une **définition plus discriminante
ou une trace de référence du même calculateur**, contenant l'identité complète,
les échanges de mesures et les conditions de session. Les seuls fichiers actuels,
un nouveau relevé d'identité identique ou un test de décodage sur des données
synthétiques ne remplacent pas cette preuve. Aucune nouvelle commande véhicule
n'a été envoyée ; aucun fonctionnement réel n'est annoncé comme validé.

## Bilans locaux utilisés

- `captures/recherche_profil_signature_25_037_00CB_1200.md` : signature, profil voisin
  rejeté et manuel RenCOM.
- `captures/recherche_profils_legacy_2026-09-13.md` : exclusions Vivaro/K-Line/flash.
- `captures/bilan_trafic_recherche_constructeur_2026-10-02.md` : synthèse documentaire
  et limites de l'application à cette date, avant les corrections de la version 0.9.
- `captures/identification_trafic_verifiee_2026-09-12.json` et
  `captures/bilan_trafic_ble_20261002_verifie.json` : preuves issues des traces.

Le VIN complet et les journaux radio bruts ne sont pas recopiés dans ce dossier.
