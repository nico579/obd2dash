# OBD2 Dash

Application Android (Kotlin, Jetpack Compose) pour se connecter à une sonde ELM327 Wi-Fi, Bluetooth classique ou BLE
et lire les données OBD2 d'un véhicule en direct.

Projet à usage personnel, non publié sur le Play Store : première installation
par APK (`adb install` / `gradlew installDebug`), puis mises à jour depuis le menu de l’application.
Le socle commun repose
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
- **Mises à jour** : le menu **… → Mises à jour** vérifie la dernière release stable
  du dépôt GitHub, affiche les versions installée/publiée et permet de télécharger
  l’APK avec progression et annulation, puis de lancer l’installation Android.
  La première fois, Android demande d’autoriser OBD2 Dash à installer ses mises à jour ;
  une fois l’autorisation accordée, appuyer de nouveau sur **Installer la mise à jour**.
  Android conserve la confirmation d’installation. Les réglages et captures restent
  en place. L’installation est bloquée pendant REC, un sondage ou un Smoke test ;
  la vérification et le téléchargement restent disponibles.
  Le fichier doit correspondre à la taille et au SHA-256 publiés par GitHub, au paquet
  installé, à ses signataires et à une version strictement plus récente, avec le même
  numéro de version que la publication. Le cache est revalidé avant installation.
  Les téléchargements utilisent HTTPS, avec redirections limitées aux hôtes GitHub,
  sans modifier le réseau de la sonde. Internet est nécessaire ; les interruptions
  et fichiers invalides donnent un message et permettent un nouvel essai.
  Le dépôt GitHub est **privé** : configurer **Accès GitHub** avec un jeton personnel
  à permissions fines, propriétaire `nico579`, seul dépôt `obd2dash`, permission
  **Contents: read-only**. Le bouton de création ouvre les réglages GitHub ; le jeton
  est saisi uniquement dans l’application, chiffré avec Android Keystore et exclu des
  sauvegardes/transferts Android. Aucun secret n’est embarqué dans l’APK ni écrit au
  journal. Le jeton reste sur l’hôte API GitHub du dépôt et ne suit pas les redirections
  vers le CDN. Son expiration/refus permet de le remplacer ; il peut aussi être supprimé.
  Les permissions de lecture nécessaires aux releases sont documentées par
  [GitHub](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).
  L’APK doit être installé une première fois par le PC ou manuellement pour obtenir
  ce nouveau menu. Un miroir d’APK public serait une autre diffusion, à décider explicitement.
  [Détails et validation de la mise à jour 0.17](mises_a_jour_2026-10-07.md).
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
  recherche ouvre explicitement le BLE. La validation radio Android reste à réaliser.
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
Dans les lectures individuelles, groupées et DTC, NRC21/NRC78 ferme la connexion
avant toute autre requête. Les mesures automatiques conservent toutes les réponses,
même si l'adaptateur accepte une limite d'une réponse ; un conflit entre calculateurs
ne doit pas disparaître derrière cette optimisation.
La surveillance de connexion tolère un échec MIL isolé et les pauses de diagnostic ;
elle distingue le cas où PID01 est la seule lecture automatique du polling de mesures.
Un transport déjà fermé est également détecté sans polling, pour permettre une
reconnexion normale sans ajouter de lecture véhicule arbitraire. Cela ne détecte
pas une fermeture TCP distante que le transport local n'a pas encore observée.

La capture manuelle des en-têtes protège également les réponses temporaires CAN
et n'envoie aucune restauration sur un transport fermé. Elle exige un protocole
CAN 11/29 bits établi (6/7/8/9) ; elle reste indisponible si ce format est inconnu
ou non-CAN. Les lectures OBD standard non-CAN restent inchangées.

Le [banc de tests du ViewModel](tools/offline_viewmodel/README.md) complète ces tests
avec le vrai cycle de connexion et les fichiers CSV, sur une sonde simulée locale.
Il vérifie les pauses, reprises, arrêts manuels et changements de transport ; les
services et le contexte Android sont remplacés par des doubles de test.

Les [reproductions de l'audit du 7 octobre](tools/audit_20261007/README.md) exercent
des cas supplémentaires de diagnostic, transport, graphique et propriété des CSV.
Le banc courant de la version 0.16 impose des assertions d'acceptation des corrections.
Les observations et contrats échoués de la version 0.15 restent conservés comme preuves
historiques. Ces essais locaux ne constituent pas une validation matérielle Android
ou véhicule.

## Documentation

- [`audit_code_2026-10-07.md`](audit_code_2026-10-07.md) : audit courant sur la
  version 0.15, nouveaux défauts reproduits, limites antérieures réévaluées et
  optimisations prioritaires, avec suivi des corrections et de leur validation.

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
- [`audit_code_2026-10-02.md`](audit_code_2026-10-02.md) : audit précédent, correctifs de
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
