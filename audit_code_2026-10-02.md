# Audit du code — 2 octobre 2026

Cette passe porte sur l'état actuel du dépôt, issu de `c678075`, avec le nouveau
dashboard et les correctifs ci-dessous. Le build résultant est **0.8 / code 8**.
L'ancien [audit de septembre](audit_code_2026-09-09_20-18-33.md) conserve l'historique
des constats ; ses listes de bugs ouverts ne décrivent pas toutes le code actuel.

Périmètre relu : transport ELM Wi-Fi/Bluetooth et échéances, découverte des PID,
décodage des réponses, polling et diagnostic, fichiers CSV et historique, cycle de
reconnexion, service d'enregistrement, navigation et cadrans, analyse CAN/KWP hors
ligne et validation des profils. Les tests et le manifeste ont été vérifiés.
Les essais utilisent des réponses en mémoire ou des serveurs TCP locaux. L'analyse
du Trafic demeure en attente ; aucun échange avec une sonde véhicule n'a été lancé.

## Bugs corrigés

**P2** désigne ici une donnée ou un résultat potentiellement trompeur, sans conclure
à un dommage matériel. Les références pointent vers le code corrigé.

| Référence | Constat et preuve | Correction |
|---|---|---|
| C1 — P2 | `responsePayloads()` filtrait les réponses non hexadécimales. `4300\r4301GGGG` devenait donc « aucun code », et `410100000000\r4101GG000000` confirmait un MIL éteint. Deux nouveaux tests ont échoué avant correction. | Toute réponse malformée au préfixe demandé invalide l'échange. Elle ne disparaît plus derrière une réponse valide. Voir [Elm327Client.kt](app/src/main/java/com/nico/obd2dash/Elm327Client.kt), `responsePayloads`. |
| C2 — P2 | Les requêtes groupées retenaient la première valeur par PID. Avec `410C0BB80D28\r410C17700D28`, le régime dépendait de l'ordre des calculateurs. Le test de conflit a échoué avant correction. | Un PID contradictoire est retiré du résultat groupé ; les autres PID cohérents restent disponibles. Le polling conserve son repli de lecture individuelle existant. Les doublons identiques sont acceptés. Voir `parseMultiPidResponse`. |
| C3 — P2 | Un refus voisin était toléré dès qu'un code positif existait. `43010087\r7F0311` pouvait alimenter l'historique comme une liste complète et faire déclarer un autre ancien code « non retrouvé ». `410101000000\r7F0111` confirmait aussi un MIL éteint malgré le répondant inconnu. Deux tests ont échoué avant correction. | Le diagnostic est signalé incomplet. La liste partielle ne remplace pas une liste complète dans l'historique. Les codes observés, par exemple P0087, restent dans le message d'erreur. Le MIL et son compteur ne sont pas confirmés à partir d'un échange comportant un refus. |
| C4 — P2 | `readVin()` supprimait tous les octets non imprimables puis ne contrôlait que la longueur. Un octet nul intrus pouvait ainsi disparaître, et 17 signes `!` devenaient une identité véhicule. Le format non-CAN à cinq segments était illisible. | Nouveau [VinPayloadDecoder.kt](app/src/main/java/com/nico/obd2dash/VinPayloadDecoder.kt) : longueurs et compteur CAN contrôlés, alphabet VIN strict, aucun octet intrus supprimé ; assemblage du format non-CAN documenté, avec contrôle des cinq indices et du remplissage initial. Les identités contradictoires sont refusées. Neuf tests couvrent ces cas. |
| C5 — P2 | Les premiers octets d'un PID4F tronqué pouvaient modifier les échelles O2 avant le contrôle de longueur d'affichage. Un PID50 réduit à son seul premier octet pouvait modifier le MAF. | Les quatre octets sont requis avant application des maxima. Les replis sont réinitialisés à chaque connexion. Trois tests vérifient troncature, annonces complètes et changement de véhicule. Voir [PidCatalog.kt](app/src/main/java/com/nico/obd2dash/PidCatalog.kt), `applyAnnouncedScales`. |
| C6 — P2 | La surveillance automatique relisait seulement les codes stockés, puis mettait à jour la date censée dater aussi les pending, moniteurs et freeze frame. Un ancien scan complet paraissait récent. | Trois dates indépendantes : lecture MIL, codes stockés, dernier scan complet. Elles sont exposées dans l'écran DTC et le rapport. Les CSV ajoutent `Lecture MIL` et `Lecture codes stockés`, avec fuseau, sans requête supplémentaire. Le scénario local `diagnostic_dates` vérifie qu'une mise à jour automatique conserve la date et les pending du scan précédent. |
| C7 — P2 | Une lecture MIL réussie n'était publiée qu'après la lecture des codes. Si `03` échouait, l'application cachait le nouveau statut MIL pourtant reçu. | Publication immédiate du MIL et de son compteur. Une demande de codes reste à retenter jusqu'à réussite, à la cadence existante. Le scénario `mil_retry` simule un MIL allumé, un premier `03` en `NO DATA`, puis une réussite ; le premier CSV contient déjà le MIL allumé et sa date. |

Le format VIN ajouté utilise la **même requête standard `0902`** déjà présente.
Son format CAN et son format non-CAN à cinq segments sont vérifiés sur les exemples
de la [documentation ELM327, p. 43](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf#page=43).
L'alphabet et la longueur de 17 caractères sont également explicités dans
[49 CFR 565.13](https://www.ecfr.gov/current/title-49/subtitle-B/chapter-V/part-565/subpart-B/section-565.13).
Ce contrôle syntaxique n'impose pas un chiffre de contrôle américain aux VIN
européens. Il ne constitue pas une validation matérielle sur tous les protocoles.

## Affichage et optimisations appliquées

Les cadrans choisis occupent une grille bornée par la surface disponible, en
portrait et en paysage. La dernière rangée utilise toute sa largeur. En plein écran
paysage, les contrôles occupent un rail de 48 dp ; les cadrans exploitent la hauteur.
Les valeurs anciennes sont grisées puis masquées selon les seuils existants ; une
mesure absente ne devient ni zéro ni une aiguille numérique inventée.

Le rendu réutilise désormais les polices, la brosse du contour et le tracé de
l'aiguille. Les graduations sont formatées lorsque leur échelle change, plutôt
qu'à chaque image de l'animation. Cela retire du travail répété du dessin ; aucun
gain chiffré de fluidité ou d'autonomie n'est revendiqué sans mesure sur téléphone.

L'ajustement des textes tient compte de la largeur et de la hauteur réelle des
glyphes. Les rendus incluent six cadrans en portrait, six en paysage, les grandes
polices en mode nuit et les valeurs périmées/absentes. Les graduations sont des
échelles graphiques, pas des seuils de danger ou un diagnostic de panne.

## Validation exécutée

- Avant correction : **5 échecs sur 62 tests** de `Elm327ClientTest`, reproduisant
  C1, C2 et C3. Le XML est conservé dans
  [regressions_avant_correction.xml](captures/audit_code_20261002/regressions_avant_correction.xml).
- Après correction : **321 tests unitaires**, aucun échec, erreur ou test ignoré.
- **12 scénarios du vrai ViewModel**, avec doubles Android et TCP local : pauses,
  reconnexions manuelles/automatiques, arrêt manuel, absence de mesures, échec de
  connexion, changements de transport, conservation du CSV pendant un diagnostic,
  arrêt/reprise pendant une écriture, puis les deux scénarios MIL/dates nouveaux.
  Tous passent ; la provenance confirme que les entrées n'ont pas changé pendant
  l'exécution.
- **10 rendus Android Compose** via Paparazzi, tous réussis. Ce banc séparé n'ajoute
  aucune dépendance à l'application.
- APK debug compilé ; **lint : zéro problème** ; `git diff --check` réussi.
- **Version 0.8 installée sur le Moto g8 power**, avec remplacement de l'APK existant.
  La version et le SHA-256 de l'APK installé correspondent au build validé ; la
  vérification ne lance pas l'application. Preuve :
  [installation_telephone.json](captures/audit_code_20261002/installation_telephone.json).

Les comptes, empreintes de sources et d'APK, résultats et chemins de reproduction
sont regroupés dans [validation.json](captures/audit_code_20261002/validation.json).
Les originaux des captures SEAT n'ont pas été modifiés. Leur
[analyse distincte](captures/analyse_SEAT_20261002/analyse_SEAT_2026-10-02.md)
concerne les lectures historiques, pas les scénarios simulés de cet audit.

Reproduction depuis la racine :

```powershell
rtk proxy .\gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
rtk proxy python tools/offline_viewmodel/run.py
rtk proxy .\gradlew.bat --offline -p captures/dashboard_preview_20261002 testDebugUnitTest --tests com.nico.obd2dash.ui.DashboardRenderTest
rtk git diff --check
```

Le banc des scénarios est versionné dans [tools/offline_viewmodel](tools/offline_viewmodel/README.md).
Le banc de rendu et ses images demeurent locaux dans `captures/`.

## Constats restants et améliorations prioritaires

| Priorité | Point confirmé par inspection | Suite concrète |
|---|---|---|
| P2 | Un CSV en pause reprend dans le même fichier après une nouvelle connexion. Le VIN/protocole du préambule et les colonnes restent ceux du démarrage ; aucun contrôle ne compare le nouveau véhicule à celui du fichier. Changer de véhicule peut mélanger les sessions. Ce changement de VIN n'a pas été rejoué dans les 12 scénarios, qui utilisent une identité inconnue constante. | Conserver l'identité de l'enregistrement ; ouvrir un nouveau fichier lors d'un changement confirmé. Définir une politique explicite si le VIN reste inconnu, et marquer les segments de reprise. Tester deux VIN distincts et une identité qui devient indisponible. |
| P2 | La fraîcheur des jauges et du CSV, les points du graphique et la cadence des PID lents utilisent encore l'horloge civile. Un recul de l'heure peut garder une mesure ancienne présentée fraîche et retarder les lectures lentes. Les échéances transport et le contrôle MIL utilisent déjà un temps monotone. | Séparer instant civil d'export et temps monotone d'acquisition ; tester un changement d'heure pendant une session. Ne pas simplement borner l'âge négatif à zéro. |
| P3 | Le journal flush synchroniquement à chaque événement, notamment depuis le thread principal. Son gestionnaire de crash écrit sur le writer et utilise le `SimpleDateFormat` hors du verrou de `log()`. La taille de la liste des journaux peut rester ancienne. | Sérialiser les écritures normales et de crash ; sortir les écritures normales du thread UI, borner la file et prévoir un flush de fermeture. Identifier le journal actif et actualiser sa taille. Aucune corruption réelle de journal n'est revendiquée. |
| P3 | `ObdViewModel` dépasse 2 200 lignes et détient transport, diagnostic, fichiers, service et autotest. Le service garde CPU/processus actifs mais ne possède pas le polling : fermer réellement l'activité ferme aussi son ViewModel et l'enregistrement. | Extraire d'abord le gestionnaire d'enregistrement et la coordination du diagnostic. Confier l'acquisition au service si elle doit survivre à la fermeture de l'activité. Vérifier alors le vrai cycle de vie Android et l'écran éteint. |
| Couverture | Le remplacement rapide de plusieurs diagnostics pendant une restauration et la propriété des fichiers d'un smoke test actif ne sont pas couverts par les scénarios actuels. Une date précise ne fait pas d'un état diagnostique mis en cache une lecture à chaque ligne CSV. Un autre code avec le même compteur/MIL peut attendre un refresh manuel. | Ajouter les entrelacements manquants ; conserver une indication explicite de fraîcheur du diagnostic. Documenter la surveillance toutes les 30 s et le déclenchement de la relecture des codes. |

Les réponses sans headers ne permettent toujours pas d'attribuer une mesure à un
ECU précis. Le diagnostic CAN par ECU et les profils constructeur restent des
analyses de captures hors ligne. Le catalogue constructeur installé demeure vide
et le sondage exploratoire reste désactivé. Les résultats locaux de cette passe
ne valident ni l'APK sur le Trafic ni une compatibilité matérielle « tout véhicule ».
