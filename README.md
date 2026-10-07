# OBD2 Dash

Application Android (Kotlin, Jetpack Compose) pour se connecter à une sonde ELM327 Wi-Fi, Bluetooth classique ou BLE
et lire les données OBD2 d'un véhicule en direct.

Projet à usage personnel, non publié sur le Play Store : installation
par sideload uniquement (`adb install` / `gradlew installDebug`). Le socle commun repose
sur la découverte et la lecture des PID SAE standard disponibles sur le véhicule connecté.
Depuis le 3 octobre 2026, le périmètre retenu est **multimarque, pour les véhicules
récents compatibles avec les lectures OBD standard de l'application**. Les données
disponibles sont celles effectivement annoncées et lues sur le véhicule connecté ;
l'année du véhicule seule ne garantit pas la compatibilité.
Les recherches spécifiques au Trafic II sont arrêtées à la demande de l'utilisateur.
La priorité porte sur la fiabilité de la connexion, les mesures standard, les
diagnostics, les enregistrements et le dashboard.

## Fonctionnalités

- **Dashboard temps réel** : jauges pour les PID réellement supportés par le véhicule
  connecté (auto-découverte à la connexion, pas de liste figée). Jusqu'à six cadrans
  choisis dans Réglages, avec grandes valeurs numériques, graduations et aiguilles
  rouges légèrement transparentes, du centre aux graduations et au-dessus des
  textes, en mode jour comme en mode nuit.
  Les valeurs numériques utilisent des chiffres rouges à **sept segments**, comme
  un afficheur automobile. Les libellés, unités et graduations conservent leur police.
  Les valeurs arrondies sans décimale sont agrandies et placées dans la partie basse
  du cadran, avec une marge entre leur unité et le cercle. Les aiguilles sont plus
  épaisses pour rester visibles sur le tableau de bord. La zone des valeurs reste
  dégagée des chiffres de graduation qui pourraient les chevaucher. La précision
  des mesures, de l'aiguille, des courbes et des captures est conservée.
  Un **appui long suivi d'un glissement** déplace un cadran dans l'ordre de lecture
  (gauche à droite, puis rangée suivante). L'ordre est enregistré par VIN lorsqu'il
  est lu, avec un réglage commun aux véhicules dont le VIN reste inconnu ;
  les choix existants et les cadrans temporairement indisponibles sont conservés.
  Leur grille maximise un diamètre identique pour tous les cadrans en portrait
  comme en paysage ; une dernière rangée incomplète est centrée, sans agrandissement.
  Une seule barre commune à tous les écrans regroupe **Dashboard, Graphique,
  enregistrement, plein écran et menu …**. Elle est en bas en portrait ou dans une
  fenêtre basse, et sur le côté en paysage. Les boutons mesurent 64 dp de haut,
  avec des pictogrammes de 32 dp. Le paysage ouvre par défaut le plein écran sur
  les cadrans et les courbes ; le bouton reste accessible en portrait.
  Le menu … contient **DTC, Sondage, Smoke test et Réglages**, ainsi que l'aide
  au déplacement, les autres mesures, les enregistrements et la déconnexion.
  Le choix des cadrans et du mode Wi-Fi/Bluetooth se fait dans Réglages.
  Le bouton d'enregistrement reste accessible, y compris pour arrêter pendant une
  reconnexion. Les échelles sont graphiques et ne constituent pas des seuils d'alerte.
  Pendant l'attente de connexion, le dashboard et le graphique affichent seulement
  **Attente de connexion** et un symbole Wi-Fi ou Bluetooth clignotant selon le mode
  choisi. Le menu et l'arrêt d'enregistrement restent accessibles. Le graphique
  retrouve ses points et sa sélection après la reconnexion.
- **Résultat de découverte explicite** : une sonde joignable ne prouve pas une réponse
  véhicule ; un bitmap PID nul confirme une réponse sans fournir de mesures standard.
  Dans ce dernier cas, l'app affiche la limite et ne lance pas de polling vide, de
  surveillance MIL non annoncée ni de lecture VIN automatique. Une absence de réponse
  véhicule conserve les tentatives de connexion automatiques.
- **Écran DTC** : codes stockés/en attente, statut MIL, moniteurs de préparation
  (readiness), freeze frame au moment du défaut, historique local par véhicule (indexé
  par VIN). Chaque lecture est indépendante : un refus du MIL ne bloque pas les codes
  stockés/en attente ou le code déclencheur du freeze frame. Les dates distinguent chaque
  résultat du dernier scan complet ; un service refusé garde son résultat précédent daté
  ou reste inconnu. Une réponse ambiguë ne confirme jamais une absence de défauts.
  Les CSV conservent aussi les dates de lecture du MIL et des codes : ces états sont
  échantillonnés depuis le cache, sans lecture supplémentaire à chaque ligne.
- **Alertes automatiques dans les captures** : MIL, voyant de préchauffage (PID65),
  activation de l'alerte NOx (PID94) et modes d'indication de défaut WWH-OBD
  véhicule/calculateur (PID90/91), selon les paramètres annoncés et les réponses.
  Les alertes sont relues dès le premier cycle, puis toutes les 30 s hors capture
  ou à une cadence visée de 5 s pendant REC, plus le temps des lectures sérialisées.
  Chaque état conserve sa date de lecture et la date/erreur du dernier essai ;
  une lecture manquante n'est jamais assimilée à un voyant éteint.
  Les colonnes supplémentaires sont figées au démarrage du CSV. Un non-support
  explicite du préchauffage ou de l'alerte NOx arrête sa relecture pour cette connexion.
  Les modes WWH et l'alerte NOx restent des états OBD, sans conversion en une icône
  précise du combiné. Une régénération FAP n'est pas un voyant FAP allumé.
  ABS, airbag, frein, pression d'huile, charge batterie et les autres voyants des
  modules constructeur ne sont pas lus. Les allumages plus courts que la cadence
  peuvent échapper à la capture. Aucune saisie manuelle de voyant n'est ajoutée.
- **Détection de protocole** : CAN (ISO 15765-4) ou non-CAN (SAE J1850, ISO 9141-2,
  ISO 14230 KWP2000), pour décoder les DTC correctement dans les deux cas.
- **Bluetooth classique et BLE** : sélection Auto/Classique/BLE et recherche BLE
  de dix secondes, uniquement à la demande, pour les sondes non appairées. Le premier
  profil GATT pris en charge est FFF0/FFF1/FFF2, relevé sur la KONNWEI. L'abonnement
  aux réponses est confirmé avant les commandes ELM ; les paquets respectent le MTU
  minimal, sans réémission d'une écriture incertaine. Auto conserve SPP pour un appareil
  classique/double et choisit BLE pour un appareil BLE seul ; la liste issue de la
  recherche ouvre explicitement le BLE. La validation radio Android reste à réaliser.
- **VIN validé** : longueur et alphabet contrôlés, compteur CAN vérifié, assemblage
  du format non-CAN à cinq segments documenté par ELM. Aucun octet intrus n'est retiré
  pour fabriquer une identité ; une réponse ambiguë reste inconnue.
- **Analyse de capture hors ligne** dans Réglages : extraction d'une identité Renault
  STD_A depuis une réponse KWP enregistrée à `2180`, contrôle du checksum et recherche
  exacte d'un profil. Le protocole vient du journal, sans être déduit de la trame.
  Le catalogue constructeur intégré reste vide : aucune mesure supplémentaire annoncée.
- **Défauts d'une capture CAN** dans Réglages : lecture locale d'une réponse enregistrée
  à `03` ou `07`, attribution des codes par ECU et résultats partiels explicites.
  Les choix CAN11/CAN29 et stockés/en attente viennent du journal. L'analyse ne
  remplace pas un diagnostic actuel et ne modifie pas l'historique des défauts.

## Limites connues / choix assumés

- **Pas d'effacement de codes défaut.** Fonctionnalité volontairement non implémentée,
  en attente d'un accord explicite avant d'y toucher.
- **Réponses sans identité du calculateur.** Les headers sont désactivés (`ATH0`).
  La découverte réunit les bitmaps de capacités valides, mais ne peut pas les attribuer
  à un ECU. Les mesures contradictoires sont refusées, y compris dans les requêtes
  groupées ; les doublons identiques restent lisibles. Le MIL est combiné et les codes
  des réponses valides sont réunis. Une réponse malformée ou un refus voisin empêche
  de publier le diagnostic comme complet ; les codes observés restent dans l'erreur.
  L'attribution des DTC par ECU reste à faire dans le diagnostic connecté.
  Le réassembleur `ATH1` préparatoire vérifie les octets, longueurs et séquences par ECU,
  avec choix explicite CAN11/CAN29. Il dispose de rejeux réels CAN11 sur la sonde SEAT ;
  il reste inactif, sans validation matérielle CAN29 ni multi-ECU. Son format est limité
  au CAN classique avec adressage ISO-TP normal, sans affichage DLC.
- **IP/port de la sonde saisis manuellement** : pas de découverte réseau automatique.
- **Sondage constructeur exploratoire désactivé**, y compris dans le smoke test.
  Les captures existantes restent consultables ; une plage arbitraire d'identifiants
  ne remplace pas un profil de lectures documenté.
- **Enregistrement des échecs automatiques** et des réponses de découverte dans le
  journal, sans afficher les détails techniques sur le tableau de bord.

## Build et installation

```
./gradlew installDebug
```

Nécessite le SDK Android (minSdk 26) et un appareil connecté (débogage USB activé).

La police embarquée [DSEG7 Modern Bold](https://github.com/keshikan/DSEG/releases/tag/v0.46)
(DSEG 0.46), créée par keshikan, est distribuée sous SIL Open Font License 1.1.
Sa licence est incluse dans l'APK et dans
[`app/src/main/assets/licenses/DSEG-LICENSE.txt`](app/src/main/assets/licenses/DSEG-LICENSE.txt).

## Tests

```
./gradlew testDebugUnitTest
```

Couvre le décodage (DTC, protocole, réassemblage), les décisions de disponibilité et
le transport au moyen de sondes TCP simulées sur l'adresse locale. Les tests ne se
connectent pas à un véhicule. La première requête OBD dispose d'une marge de 45 s,
les commandes d'adaptateur de 10 s, puis les lectures établies de 3 s ; ces marges sont
des choix applicatifs, pas des délais constructeur. L'échéance porte sur l'échange
entier, même lorsqu'une réponse arrive lentement par fragments.
Si l'hôte configuré est un nom de domaine, sa résolution DNS Java peut dépasser
la marge de connexion ; cette limite ne concerne pas l'adresse IP habituelle de la sonde.
Une réponse temporaire « occupé » ou « en attente » ne valide pas une absence de PID.
Dans la séquence DTC aussi, NRC21/NRC78 ferme la connexion avant toute autre requête.
La surveillance de connexion tolère un échec MIL isolé et les pauses de diagnostic ;
elle distingue le cas où PID01 est la seule lecture automatique du polling de mesures.

Le [banc de tests du ViewModel](tools/offline_viewmodel/README.md) complète ces tests
avec le vrai cycle de connexion et les fichiers CSV, sur une sonde simulée locale.
Il vérifie les pauses, reprises, arrêts manuels et changements de transport ; les
services et le contexte Android sont remplacés par des doubles de test.

## Documentation

- [`recherches/trafic/README.md`](recherches/trafic/README.md) : journal de recherche
  Trafic arrêté le 3 octobre 2026, sources archivées et résultats non validés.
  Conservé comme historique ; reprise uniquement sur demande explicite de l'utilisateur.
- [`audit_ble_diagnostic_2026-10-02.md`](audit_ble_diagnostic_2026-10-02.md) :
  Bluetooth BLE, lectures de diagnostic indépendantes et limite du profil Renault.
- [`audit_voyants_2026-10-03.md`](audit_voyants_2026-10-03.md) : définitions des
  alertes standard, protection contre les faux états éteints et validation du CSV.

- [`architecture_multimarque_2026-09-10_20-17.md`](architecture_multimarque_2026-09-10_20-17.md) :
  étude historique d'un moteur de profils ; les décisions de périmètre qu'elle conserve
  précèdent la demande multimarque et les essais Trafic du 12 septembre.
- [`audit_code_2026-10-02.md`](audit_code_2026-10-02.md) : audit courant, correctifs de
  la version 0.8, validations locales et améliorations prioritaires restantes.
- [`audit_code_2026-09-09_20-18-33.md`](audit_code_2026-09-09_20-18-33.md) : historique
  de l'audit de septembre ; certains constats ouverts ont été corrigés depuis.
- [`rapport_obd2.html`](rapport_obd2.html) : rapport de recherche OBD2 (PID testés sur
  le véhicule de mise au point, décodage DTC, comparaison avec les applications du
  Play Store).

## Véhicules de test

Mis au point sur une SEAT diesel (CAN 11 bits confirmé). Des essais PC indépendants ont
établi une liaison KWP avec un Renault Trafic II de 2005 et lu son identité et son VIN
constructeur, sans changement de session. Ils ne valident pas l'APK sur le Trafic :
ses mesures constructeur n'ont pas été validées. Cette recherche est arrêtée ;
le Trafic ne fait pas partie du périmètre de validation actuel.
