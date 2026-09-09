# OBD2 Dash

Application Android (Kotlin, Jetpack Compose) pour se connecter à une sonde ELM327 WiFi
et lire les données OBD2 d'un véhicule en direct.

Projet à usage personnel, non publiée sur le Play Store : installation par sideload
uniquement (`adb install` / `gradlew installDebug`). Objectif : fonctionner sur n'importe
quel véhicule OBD2, pas seulement celui utilisé pour la mise au point (voir plus bas).

## Fonctionnalités

- **Dashboard temps réel** : jauges pour les PID réellement supportés par le véhicule
  connecté (auto-découverte à la connexion, pas de liste figée).
- **Écran DTC** : codes stockés/en attente, statut MIL, moniteurs de préparation
  (readiness), freeze frame au moment du défaut, historique local par véhicule (indexé
  par VIN).
- **Détection de protocole** : CAN (ISO 15765-4) ou non-CAN (SAE J1850, ISO 9141-2,
  ISO 14230 KWP2000), pour décoder les DTC correctement dans les deux cas.

## Limites connues / choix assumés

- **Pas d'effacement de codes défaut.** Fonctionnalité volontairement non implémentée,
  en attente d'un accord explicite avant d'y toucher.
- **Un seul calculateur identifiable à la fois.** Les headers CAN sont désactivés
  (`ATH0`) : si plusieurs ECU répondent à une même requête, l'app ne peut pas toujours
  les distinguer ni agréger leurs DTC. Un outil de diagnostic ponctuel (bouton
  "Capturer le format headers-on" sur l'écran DTC) sert à préparer un correctif complet
  sans deviner le format `ATH1`.
- **IP/port de la sonde saisis manuellement** : pas de découverte réseau automatique.

## Build et installation

```
./gradlew installDebug
```

Nécessite le SDK Android (minSdk 26) et un appareil connecté (débogage USB activé).

## Tests

```
./gradlew testDebugUnitTest
```

Couvre la logique de décodage pure d'`Elm327Client` (DTC, protocole, réassemblage de
trames) avec JUnit4, indépendamment de la couche réseau.

## Documentation

- [`audit_code_2026-09-09_20-18-33.md`](audit_code_2026-09-09_20-18-33.md) : audit du
  code, bugs trouvés et corrections appliquées. Tenu à jour au fil des sessions, c'est
  la référence pour comprendre pourquoi le code est écrit comme il l'est.
- [`rapport_obd2.html`](rapport_obd2.html) : rapport de recherche OBD2 (PID testés sur
  le véhicule de mise au point, décodage DTC, comparaison avec les applications du
  Play Store).

## Véhicules de test

Mis au point sur une SEAT diesel (CAN 11 bits confirmé). Le chemin non-CAN existe et est
testé unitairement, mais n'a pas encore été validé sur un véhicule réel plus ancien.
