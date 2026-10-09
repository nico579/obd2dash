# OBD2 Dash

[**FR — Français**](README.md) | [EN — English](README.en.md)

**Version stable : [0.17](https://github.com/nico579/obd2dash/releases/tag/v0.17)**
(`versionCode = 17`) · **Android 8.0 minimum** (API 26).
Ce README décrit les fonctions de la 0.17 et l'état du dépôt au 9 octobre 2026.

Application Android (Kotlin, Jetpack Compose) pour se connecter à une sonde ELM327 Wi-Fi, Bluetooth classique ou BLE
et lire les données OBD2 d'un véhicule en direct.

Projet à usage personnel, non publié sur le Play Store : première installation
par APK, puis mises à jour depuis le menu de l’application.
Le socle commun repose sur la découverte et la lecture des PID SAE standard
disponibles sur le véhicule connecté. L'application est **multimarque, pour les
véhicules récents compatibles avec ses lectures OBD standard**. Les données
disponibles sont celles effectivement annoncées et lues sur le véhicule connecté ;
l'année du véhicule seule ne garantit pas la compatibilité.

## Installer et démarrer

1. Télécharger [**OBD2-Dash-v0.17.apk**](https://github.com/nico579/obd2dash/releases/download/v0.17/OBD2-Dash-v0.17.apk)
   depuis la [release 0.17](https://github.com/nico579/obd2dash/releases/tag/v0.17)
   et l'ouvrir sur le téléphone. Autoriser l'installation pour l'application qui
   ouvre l'APK si Android le demande. Aucun compte ni jeton GitHub n'est nécessaire.
2. Ouvrir **… → Réglages**, puis choisir la connexion à la sonde :

   | Connexion | Préparation et choix dans Réglages |
   |---|---|
   | Wi-Fi | Connecter le téléphone au réseau de la sonde, renseigner son IP/port, puis quitter Réglages pour appliquer les changements. |
   | Bluetooth classique | Appairer la sonde dans Android, actualiser la liste dans l'application, puis utiliser **Connecter** sur l'appareil choisi. |
   | BLE | Utiliser **Rechercher une sonde BLE**, autoriser les permissions demandées, puis choisir sa sonde parmi les résultats. Le profil BLE doit être pris en charge. |

3. Une fois les mesures découvertes, choisir jusqu'à six cadrans dans **Réglages**,
   puis les ordonner par appui long et glissement. **Graphique** affiche l'évolution
   d'une mesure ; **REC** démarre ou arrête une capture CSV. Les enregistrements
   se retrouvent dans le menu **…**.

La connexion se relance automatiquement vers la cible enregistrée. Si la sonde
répond mais que le véhicule ne fournit aucune mesure standard, l'application
affiche cette limite. Elle ne crée pas de valeurs pour les données absentes.

## Mises à jour

### Depuis l'application — à partir de la 0.17

1. Ouvrir **… → Mises à jour**, puis **Vérifier maintenant**. La sonde n'a
   pas besoin d'être connectée ; un accès Internet est nécessaire.
2. Si une version plus récente est disponible, appuyer sur **Télécharger la mise
   à jour** et attendre la fin du téléchargement et de la vérification. La
   progression est affichée ; l'annulation et un nouvel essai sont possibles.
3. Terminer tout enregistrement, sondage ou Smoke test en cours, puis appuyer sur
   **Installer la mise à jour**. L'application bloque l'installation pendant ces
   opérations ; la vérification et le téléchargement restent disponibles.
4. À la première installation depuis l'application, autoriser **OBD2 Dash** à
   installer ses mises à jour dans l'écran Android, revenir dans l'application et
   appuyer de nouveau sur **Installer la mise à jour**. Confirmer ensuite
   l'installation dans Android.

Si la version installée est déjà la dernière, l'application l'indique et ne
propose pas de la réinstaller. Les réglages et captures sont conservés lors
d'une mise à jour du même paquet et de la même signature.

### Installation manuelle ou version sans le menu Mises à jour

Ouvrir la [dernière release](https://github.com/nico579/obd2dash/releases/latest),
télécharger **`OBD2-Dash-vX.Y.apk`** et ouvrir le fichier sur le téléphone.
Autoriser l'installation pour l'application qui ouvre l'APK si Android le demande,
puis confirmer la mise à jour. Conserver l'application installée pour garder ses
réglages et captures. Une version antérieure à la 0.17 doit être mise à jour
manuellement une première fois pour obtenir le nouveau menu.

Le dépôt est **public** : aucun jeton GitHub n'est nécessaire.
**Accès GitHub** reste facultatif dans la 0.17, dont l'aide
décrit encore l'ancien dépôt privé ; supprimer un ancien jeton s'il est refusé.
Les jetons saisis sont chiffrés avec Android Keystore, exclus des sauvegardes et
transferts Android et ne suivent pas les redirections vers le CDN.

Le client 0.17 cherche exactement **`app-debug.apk`** : cette copie identique est
conservée à côté du fichier portant le nom de l'application. Le nom de l'asset
n'affecte ni le paquet Android ni sa signature. Avant installation, le fichier
doit correspondre à la taille et au SHA-256 publiés, au paquet installé, à ses
signataires et à une version strictement plus récente correspondant à la release.
Le cache est revalidé avant installation. Les téléchargements utilisent HTTPS,
avec des redirections limitées aux hôtes GitHub, sans modifier le réseau de la
sonde. Une interruption ou un fichier invalide permet un nouvel essai.

Les versions publiées et leurs nouveautés sont disponibles dans les
[releases](https://github.com/nico579/obd2dash/releases).

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
  Le menu … contient **DTC, Sondage, Smoke test, Réglages et Mises à jour**, ainsi que l'aide
  au déplacement, les autres mesures, les enregistrements et la déconnexion.
  Le choix des cadrans et du mode Wi-Fi/Bluetooth se fait dans Réglages.
  Le bouton d'enregistrement reste accessible, y compris pour arrêter pendant une
  reconnexion. Les échelles sont graphiques et ne constituent pas des seuils d'alerte.
  Pendant l'attente de connexion, le dashboard et le graphique affichent seulement
  **Attente de connexion** et un symbole Wi-Fi ou Bluetooth clignotant selon le mode
  choisi. Le menu et l'arrêt d'enregistrement restent accessibles. Le graphique
  retrouve ses points et sa sélection lorsque le même VIN est confirmé après la
  reconnexion. Si le véhicule change ou si son identité ne peut plus être vérifiée,
  l'historique est effacé avant les nouvelles mesures.
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
  Le code déclencheur du freeze frame et sa date restent exportés même lorsque
  aucune mesure exploitable n'accompagne la capture du défaut.
- **Captures CSV** : une coupure met la capture en pause. La reprise dans le même
  fichier exige un VIN connu identique ; un VIN différent ou non vérifiable arrête
  la capture avec un motif explicite et conserve le fichier précédent. Un nouvel
  enregistrement reste possible, y compris lorsque le véhicule ne fournit pas son VIN.
  Le smoke test ne peut arrêter que le fichier qu'il a démarré : un REC manuel
  créé ensuite reste actif.
  La session de capture est conservée lorsque l'interface est fermée, tant que
  l'enregistrement et le processus restent actifs. Sa notification permet de
  **revenir à l'application** ou **arrêter la capture**. Les mesures utilisent le
  temps écoulé, y compris pendant la veille, pour leur fraîcheur ; les dates du
  CSV conservent l'heure civile. Une réponse groupée garde sa date de réception
  même lorsqu'une autre mesure nécessite une nouvelle lecture plus lente.
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
  recherche ouvre explicitement le BLE. La validation radio BLE sous Android reste
  à réaliser.
- **VIN validé** : longueur et alphabet contrôlés, compteur CAN vérifié, assemblage
  du format non-CAN à cinq segments documenté par ELM. Aucun octet intrus n'est retiré
  pour fabriquer une identité ; une réponse ambiguë reste inconnue. L'année-modèle
  déduite du code VIN est affichée comme une **estimation** : ce code se répète par
  cycles et ne prouve pas, seul, l'année du véhicule.
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
- **Arrêt du processus** : la fermeture de l'interface conserve une capture active,
  mais une terminaison du processus ou un arrêt forcé ne permet pas de reconstruire
  automatiquement sa session. Les doubles hors ligne ne valident pas le comportement
  réel d'un téléphone, des notifications ou des sondes physiques.

## Build et installation

Pour compiler : **JDK 17**, **SDK Android 34** et le wrapper Gradle du dépôt.
Les exemples suivent la convention RTK du projet. Sous Windows / PowerShell :

```powershell
rtk proxy ./gradlew.bat assembleDebug
rtk proxy ./gradlew.bat installDebug
```

`installDebug` nécessite un appareil Android 8.0 ou ultérieur connecté avec le
débogage USB activé. L'APK local est produit dans
`app/build/outputs/apk/debug/app-debug.apk`.
Sous Linux/macOS, utiliser `./gradlew` à la place de `./gradlew.bat`.

Les APK GitHub utilisent la clé **debug** versionnée pour conserver leur signature
d'une version à l'autre. Cette clé est publique avec le dépôt ; elle ne constitue
pas une identité de signature protégée pour une distribution de production.

La police embarquée [DSEG7 Modern Bold](https://github.com/keshikan/DSEG/releases/tag/v0.46)
(DSEG 0.46), créée par keshikan, est distribuée sous SIL Open Font License 1.1.
Sa licence est incluse dans l'APK et dans
[`app/src/main/assets/licenses/DSEG-LICENSE.txt`](app/src/main/assets/licenses/DSEG-LICENSE.txt).

## Tests

```powershell
rtk proxy ./gradlew.bat testDebugUnitTest
rtk proxy ./gradlew.bat lintDebug
```

La validation de la **0.17** comprend **433 tests unitaires**, **47 rendus**,
une compilation réussie et un lint sans erreur ni avertissement. Le téléchargement
réel et son annulation ont été vérifiés depuis le dépôt public avec le code de
mise à jour de production.

Les tests couvrent notamment les mesures, les diagnostics, les reconnexions,
les captures et les mises à jour. Ils utilisent des sondes simulées sur l'adresse
locale, sans connexion à un véhicule. Ils ne remplacent pas une validation
matérielle sur un téléphone et une sonde physique.
