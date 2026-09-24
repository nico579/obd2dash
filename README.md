# OBD2 Dash

Application Android (Kotlin, Jetpack Compose) pour se connecter à une sonde ELM327 Wi-Fi ou Bluetooth
et lire les données OBD2 d'un véhicule en direct.

Projet à usage personnel, non publié sur le Play Store : installation
par sideload uniquement (`adb install` / `gradlew installDebug`). Le socle commun repose
sur la découverte et la lecture des PID SAE standard disponibles sur le véhicule connecté.
L'objectif est multimarque. Les lectures constructeur restent désactivées tant qu'un
profil applicable, ses commandes et leurs conditions d'accès ne sont pas validés.

## Fonctionnalités

- **Dashboard temps réel** : jauges pour les PID réellement supportés par le véhicule
  connecté (auto-découverte à la connexion, pas de liste figée).
- **Résultat de découverte explicite** : une sonde joignable ne prouve pas une réponse
  véhicule ; un bitmap PID nul confirme une réponse sans fournir de mesures standard.
  Dans ce dernier cas, l'app affiche la limite et ne lance pas de polling vide, de
  surveillance MIL non annoncée ni de lecture VIN automatique. Une absence de réponse
  véhicule conserve les tentatives de connexion automatiques.
- **Écran DTC** : codes stockés/en attente, statut MIL, moniteurs de préparation
  (readiness), freeze frame au moment du défaut, historique local par véhicule (indexé
  par VIN).
- **Détection de protocole** : CAN (ISO 15765-4) ou non-CAN (SAE J1850, ISO 9141-2,
  ISO 14230 KWP2000), pour décoder les DTC correctement dans les deux cas.
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
  à un ECU. Plusieurs résultats différents pour une même mesure ou un même diagnostic
  sont refusés : le premier « zéro défaut » ne masque plus une seconde réponse.
  Les doublons identiques restent lisibles. L'agrégation des DTC par ECU reste à faire.
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
La surveillance de connexion tolère un échec MIL isolé et les pauses de diagnostic ;
elle distingue le cas où PID01 est la seule lecture automatique du polling de mesures.

Le [banc de tests du ViewModel](tools/offline_viewmodel/README.md) complète ces tests
avec le vrai cycle de connexion et les fichiers CSV, sur une sonde simulée locale.
Il vérifie les pauses, reprises, arrêts manuels et changements de transport ; les
services et le contexte Android sont remplacés par des doubles de test.

## Documentation

- [`architecture_multimarque_2026-09-10_20-17.md`](architecture_multimarque_2026-09-10_20-17.md) :
  étude historique d'un moteur de profils ; les décisions de périmètre qu'elle conserve
  précèdent la demande multimarque et les essais Trafic du 12 septembre.
- [`audit_code_2026-09-09_20-18-33.md`](audit_code_2026-09-09_20-18-33.md) : audit du
  code, bugs trouvés et corrections appliquées. Tenu à jour au fil des sessions, c'est
  la référence pour comprendre pourquoi le code est écrit comme il l'est.
- [`rapport_obd2.html`](rapport_obd2.html) : rapport de recherche OBD2 (PID testés sur
  le véhicule de mise au point, décodage DTC, comparaison avec les applications du
  Play Store).

## Véhicules de test

Mis au point sur une SEAT diesel (CAN 11 bits confirmé). Des essais PC indépendants ont
établi une liaison KWP avec un Renault Trafic II de 2005 et lu son identité et son VIN
constructeur, sans changement de session. Ils ne valident pas l'APK sur le Trafic :
ses mesures constructeur et leur profil exact restent à documenter.
