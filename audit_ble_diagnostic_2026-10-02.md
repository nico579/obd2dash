# Bluetooth BLE et diagnostic indépendant — 2 octobre 2026

Version applicative : **0.9 / code 9**. Les modifications de cette étape sont
vérifiées hors véhicule. La validation radio Android sur la KONNWEI reste à faire.

## Corrections

- Les aiguilles du dashboard sont rouges en mode jour et nuit. La couleur de
  l'arc de progression reste celle du thème. Une valeur vieillissante atténue
  l'aiguille, tandis qu'une valeur indisponible ne dessine aucune aiguille.

- La lecture du MIL ne conditionne plus les autres services du diagnostic manuel.
  Codes stockés, codes en attente, moniteurs et capture du défaut publient leurs
  réussites séparément, avec leur propre date. Les échecs restent visibles ; ils
  ne remplacent pas une ancienne lecture par une absence de défauts.
- La surveillance automatique peut lire les codes stockés même lorsque le MIL
  est refusé. Une lecture 03 ratée reste à reprendre à la cadence prévue.
- NRC21/NRC78 arrête la séquence de diagnostic et ferme son transport avant la
  commande suivante. Les refus définitifs peuvent laisser les autres services
  répondre ; une perte de connexion ou une annulation ne sont pas absorbées.
- `020200` est indépendant du succès de `03`. Seule une réponse valide avec DTC
  `0000` indique l'absence de code déclencheur fourni. Un refus ou une longueur
  invalide conserve l'état inconnu. Les mesures du freeze frame ne sont demandées
  que lorsqu'un code déclencheur a été obtenu.

## Bluetooth

Le client texte ELM accepte maintenant le BLE en plus de TCP et de RFCOMM/SPP.
Le profil implémenté correspond au relevé de la KONNWEI : service FFF0,
notifications FFF1, écriture FFF2, abonnement CCCD 2902 confirmé avant les commandes.
Les propriétés GATT et le descripteur sont contrôlés ; un service inconnu est refusé.
Les écritures sont séquentielles, limitées à 20 octets, sans réémission automatique.
Le tampon de réception est borné ; fermeture, erreur radio, échéance et annulation
réveillent les appels bloquants. Les callbacks d'une connexion fermée sont ignorés.

Réglages : Auto / Classique / BLE, préférence conservée pour la reconnexion.
Auto utilise BLE pour un appareil BLE seul et garde SPP pour les appareils
classiques/doubles. La recherche radio de dix secondes permet de sélectionner
une sonde BLE non appairée ; elle ne se connecte à aucun appareil automatiquement
et n'envoie aucune commande ELM. Elle s'arrête en quittant les réglages.

Les API Android 33+ utilisent les valeurs transmises aux callbacks et aux
écritures ; les anciennes API sont limitées aux versions antérieures. Les
permissions de scan sont demandées seulement lors d'une recherche. Android
12+ utilise SCAN/CONNECT avec `neverForLocation` ; les permissions de localisation
nécessaires sur Android 11 et antérieur sont bornées à API 30.
Références : [API GATT](https://developer.android.com/reference/android/bluetooth/BluetoothGatt),
[callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback),
[permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

## Validation

- **337 tests unitaires**, aucun échec ni test ignoré.
- **16 scénarios du ViewModel**, aucun échec, entrées inchangées. Provenance :
  `captures/audit_2026-09-13/recording_reconnect/run_20261002_233918_rw8glze5/`.
- **12 rendus Compose/Paparazzi**, dont réglages BLE et DTC partiel avec grands
  textes, puis cadrans rouges jour/nuit, portrait/paysage et péremption. Les
  images jour/nuit et les deux nouveaux écrans ont été inspectés visuellement.
- **Lint Android : zéro erreur, zéro avertissement**. APK debug compilé et signé.
- Le téléphone n'apparaît pas dans `adb devices -l` : aucune installation effectuée.

Les artefacts et leurs empreintes sont archivés sous
`captures/audit_ble_diagnostic_20261002/`. SHA256 de l'APK local :
`99c8be09dbf9497e3c7533f07cd09dcba699e184a7e768e3cca03498aed60d79`.
L'APK construit par GitHub peut avoir une empreinte différente.
Aucune commande n'est envoyée à une sonde physique par ces tests : TCP sur
loopback, pilote GATT simulé et doubles Android.

Le banc du ViewModel compile les sources de production sans les réécrire et
conserve les empreintes des entrées. Les essais vérifient notamment les refus
standard du Trafic, les dates d'un diagnostic partiel, l'arrêt sur NRC78 et la
lecture automatique 03 malgré le refus MIL. Les doubles BLE de ce banc interdisent
tout accès radio ; le flux BLE est vérifié séparément par les tests unitaires.

## Limite Renault Trafic II

Les échanges enregistrés confirment une réponse KWP et un bitmap de mesures
standard nul. `7F0112` ne renseigne pas le voyant ; `43000000` contient trois octets
après le service 43 et reste ambigu pour le décodeur non-CAN documenté. Le correctif
ne transforme pas cette réponse en « aucun défaut ». `4202000000` indique seulement
qu'aucun code déclencheur n'est fourni par cette capture standard.

L'identité constructeur extraite de la capture antérieure est
**25 / 037 / 00CB / 1200**. Les noms du catalogue CLIP public sont des pistes,
pas des dictionnaires de commandes ou de conversions : le profil Vdiag 19
référencé pour une phase différente ne constitue pas une preuve d'applicabilité.
La liste [RenCOM Trafic phase 1](https://www.obdtester.com/rencom-eculist/renault/trafic_ii_ph1_%5B2000_2005%5D)
annonce des mesures EDC15C, sans publier leurs requêtes et conditions de lecture.

Aucun profil complet applicable à cette signature n'a été obtenu. Le catalogue
constructeur installé reste vide ; aucun changement de session, essai arbitraire
d'identifiant, effacement ou commande d'actionneur n'est ajouté. Une référence
CLIP/RenCOM correspondant à cette identité, avec les requêtes/réponses et leurs
conditions, reste nécessaire pour préparer un essai constructeur ciblé.
