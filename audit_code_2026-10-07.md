# Audit du code — 7 octobre 2026

La passe identifie des défauts résiduels de diagnostic, de corrélation des réponses,
d'appartenance des enregistrements et d'identité des graphiques sur **v0.15**.
Les corrections de **v0.16** sont suivies à la fin de ce rapport. Les constats et
preuves historiques ci-dessous restent rattachés à la version auditée ; ils ne
décrivent pas l'état de la version corrigée.

Révision auditée : **`ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04`**, tag `v0.15`.
Les liens de code ci-dessous se réfèrent à cette révision, même après des corrections
ultérieures. P2 désigne un défaut de fiabilité à corriger ; P3 une robustesse ou une
amélioration moins urgente. Aucune validation matérielle nouvelle n'est revendiquée.

## Méthode et preuves

- Inventaire de 40 fichiers Kotlin applicatifs, 8 815 lignes, et 23 fichiers de tests
  unitaires. Revue croisée des chemins critiques : transports et BLE, découverte,
  PID, DTC/MIL/freeze frame, historique, polling, CSV, lifecycle Android, permissions,
  navigation, cadrans, graphiques et analyse CAN/profils hors ligne. Manifeste,
  ressources, Gradle et publication examinés également.
- Comparaison avec les audits du 2 et du 3 octobre et les corrections historiques
  de septembre. Un constat « nouveau » signifie nouvellement identifié par cette
  passe, pas nécessairement introduit par la version 0.15.
- Reproductions sur les **sources de production sans réécriture**, compiler Kotlin
  installé et bibliothèques déjà en cache. Parseurs appelés directement ; ViewModel
  et vrai client ELM exercés avec des serveurs TCP liés uniquement à `127.0.0.1`.
  Les doubles remplacent Android, le service, le journal et l'historique persistant.
  Ils ne prouvent ni le lifecycle réel Android, ni la radio, ni un calculateur physique.
- Chaque exécution conserve commandes, empreintes des sources/dépendances, sorties
  et contrôle de stabilité des entrées dans `captures/audit_code_20261007/`.
  Le banc rejouable est versionné dans [tools/audit_20261007](tools/audit_20261007/README.md).
- Les recherches Trafic restent arrêtées. Aucun essai sur une sonde, aucun balayage
  constructeur et aucune commande à un véhicule pendant cette passe.

### Validation de la livraison UI

La compilation `assembleDebug`, le lint Android sans problème et 35 tests de rendu
ont réussi : 34 images statiques et une animation réelle de deux cycles. Les
différences entre les phases du clignotement concernent uniquement le pictogramme ;
le texte reste fixe. Wi-Fi, Bluetooth classique/BLE, portrait, paysage, grande
police, reprise du graphique et arrêt REC pendant reconnexion sont couverts.

Le [run GitHub 37581908593](https://github.com/nico579/obd2dash/actions/runs/37581908593)
a réussi les tests unitaires et le build sur la révision auditée. Les XML unitaires
locaux existants contiennent 364 tests ; ils n'ont pas été relancés pour les nouveaux
probes d'audit. Ces derniers révèlent des cas absents de cette suite : un CI vert
ne démontre pas l'absence de ces bugs.

L'[APK v0.15 publié](https://github.com/nico579/obd2dash/releases/tag/v0.15)
fait 8 465 989 octets ; SHA-256
`4359b488719ff16b2f7127465b1ab7034ebbac2200794e61f201157328153720`.
Version, digest GitHub, certificat identique à v0.14, police et licence embarquées
vérifiés. Aucun téléphone USB détecté : aucune installation effectuée.

## Nouveaux constats

### N1 — P2 — Une réponse DTC incohérente ou vide devient un succès vide

Sources : [DtcPayloadDecoder.kt:40](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/DtcPayloadDecoder.kt#L40),
[Elm327Client.kt:722](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L722).

Déclencheurs : CAN `43010000` annonce un code mais contient le slot nul `0000` ;
`430200870000` annonce deux codes mais n'en fournit qu'un. En non-CAN, `43` ou `47`
sans aucune donnée est accepté. Le chemin connecté retourne respectivement une
liste vide ou partielle comme une liste complète.

Conséquence : affichage « aucun » ou liste incomplète, date de réussite publiée,
historique potentiellement marqué « non retrouvé ». Les appels à `historyStore.record`
après réussite sont présents à `ObdViewModel.kt:1352` et `:1531`. Cette conséquence
de persistance est établie par le code ; le banc remplace le magasin d'historique.

Correction minimale : refuser le slot nul **à l'intérieur du nombre annoncé CAN**,
indépendamment de l'option de padding strict réservée à l'analyse hors ligne ; exiger
au moins une paire en non-CAN. Garder `4300` CAN, `43000000000000` non-CAN et le
padding après les slots CAN valides. Le rejet strict existe déjà dans le panneau
CAN hors ligne, ce qui rend la différence avec le chemin connecté particulièrement
concrète. Régression à ajouter sur les deux chemins, avec les contrôles valides.

### N2 — P2 — Une erreur ELM voisine d'une réponse positive est ignorée

Sources : [HeaderlessObdResponse.kt:43](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/HeaderlessObdResponse.kt#L43),
[Elm327Client.kt:479](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L479).

Déclencheurs : `4300\rBUFFER FULL` et `4300\rCAN ERROR` donnent zéro DTC confirmé ;
`410100000000\rDATA ERROR` donne MIL éteint/compteur zéro. La présence du texte
d'échec ne rend pas l'échange incomplet dans le parseur commun.

Conséquence : une partie survivante de la réponse certifie à tort un résultat
complet. `BUFFER FULL` signifie que des données ont été perdues ; une réponse
positive restante ne suffit donc pas. Référence générale :
[ELM327DS, messages d'erreur, p. 88](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf#page=88).

Correction : reconnaître les statuts ELM d'échec et les propager comme échange
incomplet ; conserver l'écho autorisé et `SEARCHING...`. Tester l'erreur seule,
avant/après un succès et avec plusieurs réponses. Ne pas convertir une dernière
réussite datée en faux état neuf « éteint » lors de l'échec.

### N3 — P2 — NRC temporaire sur une mesure laisse la session réutilisable

Sources : [Elm327Client.kt:406](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L406),
`:425`, `:919`, comparés à [sendDiagnosticRaw:658](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L658).

Le diagnostic et la découverte ferment une session devant les NRC temporaires
corrélés. Les mesures ordinaires, groupées, certaines détections d'optimisation et
le VIN passent directement par `sendRaw`. Après `7F0178>`, la connexion peut rester
ouverte et accepter une requête suivante.

Le probe déclenche une réponse RPM tardive seulement après l'arrivée de la première
requête vitesse ; il permet d'observer le décalage sans temporisation fragile.
Il prouve le défaut sous ce contrat simulé, pas que la sonde physique de l'utilisateur émet
ce scénario. Correction : contrôle commun des NRC temporaires corrélés à toutes
les lectures véhicule, avec fermeture du transport capturé et conservation de la
politique spécifique NRC21 de découverte. Tester aussi groupage, VIN et annulation.

### N4 — P2 — L'optimisation « une réponse » masque un conflit entre ECU

Sources : [Elm327Client.kt:412](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L412),
[detectResponseCountSupport:891](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/Elm327Client.kt#L891).

Une réponse valide à `01001` démontre l'acceptation de la syntaxe, pas l'unicité
d'un calculateur. Le suffixe `1` est pourtant ensuite ajouté aux mesures. Deux
réponses RPM contradictoires refusées sans suffixe deviennent la première valeur
acceptée quand l'adaptateur applique cette limite ; inverser l'ordre des ECU change
alors la mesure. Le probe simule explicitement ce contrat dans les deux ordres.

Ce problème dépasse la limite déjà connue d'absence d'attribution des ECU :
l'optimisation retire les données nécessaires à leur contrôle de cohérence.
La [documentation ELM327DS, p. 33](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf#page=33)
présente cette fonction pour un nombre de réponses connu à l'avance.

Correction : conserver les réponses complètes pour les requêtes fonctionnelles
sans identité ECU, tant que le nombre attendu ou le ciblage physique n'est pas
établi. Garder le groupage validé ; sa cohérence multi-réponses doit rester contrôlée.
La pénalité éventuelle de temps doit être mesurée plutôt que compensée par un choix
arbitraire du premier calculateur.

### N5 — P2 — Le smoke test peut fermer un nouveau CSV manuel

Sources : [ObdViewModel.kt:2225](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L2225),
`:2233`, `:2281`, comparés à [stopRecording:2060](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L2060).

**Reproduit sur le vrai chemin du smoke test.** Il démarre son CSV, puis attend
dix secondes. L'utilisateur arrête REC et démarre un autre enregistrement manuel.
Le booléen `autoTestOwnsRecording` reste vrai ; arrêter le smoke test ferme alors
le nouveau fichier. Le nettoyage naturel après dix secondes possède le même défaut.

Sortie observée : `owner flag=true`, puis `manual recording=false` et
`same manual writer=false`. Compilation réussie, assertion de propriété échouée
comme attendu, empreintes des entrées inchangées. Le probe inspecte l'identité du
writer mais ne modifie aucun champ privé ni constante de production.

Correction : appartenance liée à un token/génération de l'enregistrement réellement
créé par le smoke test. Comparer ce token lors de tous ses nettoyages ; un booléen
global ne représente pas l'identité d'un fichier. Tester arrêt manuel/remplacement,
fin normale, annulation, diagnostic, reconnexion et conservation d'un REC préexistant.

### N6 — P2 — Une mesure groupée peut recevoir une date trop récente

Source : [ObdViewModel.kt:1259](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L1259).

Tous les octets du groupe sont déjà reçus avant la boucle. Des PID manquants placés
avant un PID réussi sont relus individuellement ; la mesure réussie du groupe n'est
datée qu'après ces replis. Des réponses lentes lui ajoutent donc une fraîcheur
fictive, retardant son masquage dans les cadrans et le CSV.

**Reproduit.** Le probe active réellement le groupage avec la réponse complète de détection,
puis renvoie seulement la vitesse et retarde le repli RPM de 750 ms, sous l'échéance
normale. Aucun échantillon vitesse ultérieur ne remplace celui observé. L'arrivée
de la requête de repli au serveur constitue une borne supérieure de l'instant où
la vitesse avait déjà été reçue par le client.
La mesure est datée **766 ms après** l'arrivée de cette requête de repli, alors
qu'elle avait nécessairement été reçue avant. Cette durée est celle de ce rejeu,
pas une mesure de latence sur une sonde réelle.

Correction : capturer immédiatement la date de réception du groupe ; la réutiliser
pour ses valeurs. Dater les replis au retour de leurs propres lectures. Ce défaut
est distinct du changement d'horloge civile décrit en K2.

### N7 — P2 — Le graphique conserve les points d'un autre véhicule

Sources : [ObdViewModel.kt:930](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L930),
`:1073`, `:1299`.

Toute nouvelle connexion conserve `graphPid` et `graphHistory`, même lorsqu'un
VIN différent est ensuite confirmé. Les points du nouveau véhicule rejoignent
donc ceux de l'ancien. Une rupture de tracé en cas de pause longue ne signale pas
ce changement d'identité et une reconnexion courte peut joindre les points.

**Reproduit.** Le probe sélectionne le régime, acquiert 1 000 tr/min sur un premier VIN valide,
se connecte par méthode publique à un second VIN valide fournissant 2 000 tr/min,
et examine l'historique sous ce second VIN.
Deux points du premier VIN restent dans le graphique publié sous le second.

Correction : associer l'historique à son identité de session et le vider ou le
segmenter lors d'un changement confirmé. Conserver la reprise sur un même véhicule
confirmé ; définir explicitement le traitement des VIN inconnus. La continuité
souhaitée lors d'une simple coupure reste utile.

### N8 — P2 — Le décodeur VIN impose le cycle 2010–2039

Source : [VinDecoder.kt:60](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/VinDecoder.kt#L60),
`:91`. Usages dans le diagnostic et son export.

Les caractères année `1` à `9` sont forcés sur 2031–2039. Le VIN synthétique du
probe avec le caractère `6` donne ainsi 2036 en octobre 2026. La conformité OBD
ne justifie pas l'exclusion du cycle précédent : une
[table constructeur déposée à la NHTSA](https://static.nhtsa.gov/nhtsa/downloads/MfrMail/01-022-N11B-7675.pdf)
emploie ces mêmes chiffres pour 2001–2009.

Correction : années candidates et contexte constructeur/marché fiable pour lever
l'ambiguïté, ou affichage explicitement incertain. La
[règle NHTSA](https://www.nhtsa.gov/document/final-rule-vehicle-identification-number-requirements)
possède un périmètre précis ; sa désambiguïsation ne doit pas être appliquée
aveuglément à tous les VIN européens. Le probe prouve l'année imposée par le code,
pas l'année réelle d'un véhicule synthétique. Le contrôle SEAT connu reste 2011.

### N9 — P2 — Le code déclencheur du freeze frame disparaît du partage

Sources : [ObdViewModel.kt:309](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L309),
[DtcScreen.kt:140](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ui/DtcScreen.kt#L140).

Cas légitime : `03` refusé, `020200` donne P0087, toutes les mesures freeze frame
restent indisponibles. L'état et l'écran conservent P0087 avec sa date ; le rapport
partagé ne crée pourtant sa section que si `freezeFrame` contient une mesure.
Le seul code observé peut disparaître de l'export.

Correction : condition trigger présent **ou** date de lecture présente **ou**
mesures présentes. Distinguer capture non lue, absence confirmée et mesures
partielles. Le probe appelle le vrai générateur de rapport avec trigger seul et
avec trigger + mesure ; il ne prétend pas avoir exécuté ce cas sur un véhicule.

### N10 — P2 — Sans polling, une fermeture du transport ne change pas l'état UI

Sources : [ObdViewModel.kt:1087](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/ObdViewModel.kt#L1087),
`:553`, [BleSerialTransport.kt:177](https://github.com/nico579/obd2dash/blob/ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04/app/src/main/java/com/nico/obd2dash/BleSerialTransport.kt#L177).

**Inspection, non reproduit dans cette passe.** Avec aucun PID de lecture automatique,
le job de polling est supprimé. Une fermeture BLE peut mettre le transport à fermé,
sans notifier le ViewModel. Son état reste CONNECTED ; la boucle de reconnexion
n'agit que sur les états déconnecté/erreur/reconnexion. Une action manuelle peut
encore révéler la fermeture, mais la reprise automatique n'est plus assurée.

Correction : événement de perte du transport et surveillance indépendante des
transports déjà fermés, même en l'absence de mesures. Cela n'exige aucune nouvelle
requête arbitraire au véhicule. La détection passive d'une fermeture TCP distante
non encore observée par IO est une limite différente, non résolue par `isConnected`.
Régression prévue : session bitmap nul, fermeture locale, état et retry après 5 s.

### Hypothèse réfutée — Tabulations dans le rejeu de profil

Un soupçon initial sur l'échappement de la regex de `ManufacturerProfiles.kt:252`
est **réfuté par l'exécution**. Le vrai parseur accepte tabulations, espaces et
forme compacte et renvoie dans les trois cas `[97, 66, 16, 0]`. Une lecture du
texte échappé avait conduit à surestimer le nombre d'antislashs du littéral.
Ce point n'est pas compté comme bug et ne demande aucune correction.

## Constats déjà connus, ouverts dans la version auditée

Ils sont réévalués, **pas présentés comme de nouvelles découvertes**.

| ID | Priorité et état | Preuve actuelle et correction recommandée |
|---|---|---|
| K1 | P2 — CSV susceptible de mélanger des véhicules | Métadonnées VIN/protocole/colonnes figées à `ObdViewModel.kt:714`, writer conservé puis repris à `:1099`, nouveau VIN non comparé. Lier le fichier à son identité ; interrompre ou ouvrir un segment/fichier explicite lors d'un changement. Traiter le VIN inconnu par une politique annoncée. Inspection actualisée, pas nouveau rejeu CSV à deux VIN. |
| K2 | P2 — Horloge civile pour l'âge des valeurs | `isUnavailable:94`, cadence lente `:1234`, valeur `:1269`, graphe `:1299`, CSV `:1999`. Un recul de l'heure peut prolonger des valeurs périmées ou dérégler la cadence. Employer une horloge monotone incluant la veille pour durées/fraîcheur ; conserver les dates civiles d'export. Transport et cadence MIL/alertes disposent déjà d'horloges monotones : ne pas les décrire comme affectés de la même façon. |
| K3 | P3 — Journal synchrone et crash hors verrou | `EventLog.kt:35` sérialise le journal normal ; `:68` partage writer et `SimpleDateFormat` hors verrou. La taille affichée est un instantané. Sérialiser toutes les écritures sur IO et prévoir un formatage local/sûr pour le crash. Aucune corruption effective ni ANR mesuré pendant cette passe. |
| K4 | P3 — Acquisition détenue par le ViewModel | `RecordingService.kt:14` n'acquiert pas les mesures ; `ObdViewModel.onCleared:2377` arrête CSV/service. Le service et wake lock aident en arrière-plan mais ne font pas survivre l'acquisition à la fermeture réelle de son propriétaire. Clarifier ce contrat ; déplacer le propriétaire si la capture doit survivre à cette fermeture. Ni simple navigation ni rotation gérée ne sont assimilées à `onCleared`. |

La surveillance des codes stockés dépend du MIL/compteur : un autre code avec
compteur et MIL inchangés peut rester en cache jusqu'au refresh manuel. Limite
déjà consignée, à traiter par une relecture périodique raisonnable si nécessaire.
Le profil BLE FFF0/FFF1/FFF2 est limité à ce contrat GATT, pas une garantie pour
toutes les sondes BLE. Attribution ECU connectée avec ATH0 et validation radio
Android restent des limites explicites.

## Optimisations et améliorations utiles

Ces propositions s'appuient sur le code ; aucun gain CPU, batterie, temps de
connexion ou latence n'a été mesuré sur téléphone dans cette passe.

1. **Éviter une reconnexion sans modification en quittant Réglages.**
   `SettingsScreen.kt:81` appelle toujours `onConnect` à la sortie en Wi-Fi ;
   `MainActivity.kt:229` appelle le vrai `connect`, qui redémarre la session à
   `ObdViewModel.kt:836`. Consulter les journaux ou changer un cadran suffit donc
   à suspendre les mesures/REC et refaire la découverte. Comparer la cible appliquée
   au formulaire ; reconnecter seulement si elle a changé ou sur action explicite.
   Conserver l'application différée d'une IP/port réellement modifié.
2. **Déplacer le cycle de vie des fichiers sur un propriétaire IO sérialisé.**
   Ouverture/en-tête/flush `ObdViewModel.kt:1918`, fermeture `:2064` et liste/tri
   `:671` sont synchrones depuis le thread UI. Une fermeture peut attendre une
   écriture IO. Garder la protection actuelle qui capture le writer de chaque job,
   et y ajouter les tokens de N5 ; mesurer la latence de REC et de la liste avec
   plusieurs centaines de fichiers avant/après.
3. **Mesurer le vrai cycle, ou renommer la métrique actuelle.**
   La moyenne `ObdViewModel.kt:1275` s'arrête avant MIL/codes/alertes `:1316` et
   `:1382`, malgré le commentaire « cycles complets ». Séparer coût des mesures,
   alertes, diagnostics et cycle total sur une horloge monotone. Une comparaison
   Wi-Fi/Bluetooth basée sur cette seule moyenne serait incomplète.
4. **Suspendre les observations UI en arrière-plan.**
   `MainActivity.kt:153` utilise `collectAsState`, les tickers de fraîcheur et
   animations restent des effets Compose tant que la composition vit. Employer
   la collecte lifecycle-aware recommandée par
   [Android](https://developer.android.com/develop/ui/compose/state#other-supported-types-of-state),
   et suspendre les tickers/animations quand l'écran n'est pas visible. La capture
   et son polling doivent continuer indépendamment. Choisir une version compatible
   avec Kotlin/SDK actuels, sans mise à jour aveugle de toutes les dépendances.
5. **Ne créer l'animation du petit point que lorsqu'il clignote.**
   `MainActivity.kt:350` démarre une transition infinie même pour CONNECTED ;
   `blinkAlpha` n'est alors pas utilisé pour l'opacité. Réduire ce travail inutile,
   puis mesurer ; le pictogramme de l'attente v0.15, lui, doit continuer à clignoter.
6. **Ajouter le lint à la CI et les régressions réellement manquantes.**
   La release exécute unitaires et build, pas lint. Inclure le lint déjà propre
   localement et les régressions N1–N10 après correction. Garder les tests sockets
   suffisamment tolérants au scheduling sans affaiblir leurs échéances réelles.
7. **Extraire progressivement les propriétaires de session et d'enregistrement.**
   Le ViewModel fait 2 411 lignes, le client ELM 1 071. Privilégier un contrôleur
   session, un recorder et un ordonnanceur de lectures testables, avec identités
   explicites. Réaliser cette extraction après les petites corrections reproduites,
   sans changer les commandes autorisées ni ajouter de profils non validés.
8. **Découvrir séparément les capacités freeze frame.**
   Les mesures mode 02 utilisent les PID annoncés en mode 01 (`:1556`). Une
   découverte spécifique peut améliorer une capture partielle, sans conditionner
   le code déclencheur à ces mesures. La première correction est N9, déjà précise.

## Ordre de traitement recommandé

1. N1/N2 : fiabilité du verdict DTC/MIL ; conserver l'inconnu et les dates d'erreur.
2. N3/N4 : corrélation des échanges et cohérence entre calculateurs.
3. N5 : propriété du fichier, puis N6/K2 : fraîcheur exacte des données.
4. N7/K1 : identité de véhicule pour courbes et captures ; N9 : export complet.
5. N10, N8 ; puis optimisations IO/lifecycle et contrôle de CI.

Pour chaque correction : faire échouer une régression sur la révision auditée,
appliquer le changement minimal, faire réussir la régression et les contrôles
positifs concernés, puis build/lint. Ne pas annoncer une compatibilité matérielle
supplémentaire à partir d'un simulateur. Les défauts de parsing, fichiers et état
se corrigent et se vérifient sans présence dans un véhicule.

## Résultats des probes exécutés

Compilation commune réussie. Sources et dépendances inchangées pendant chaque
exécution, vérifiées par empreintes. Les neuf nouveaux constats N1–N9 sont
reproduits, avec des preuves de niveau différent indiquées ci-dessous ; N10
reste établi par inspection. Aucun correctif backend n'a été appliqué dans cette
passe. Les anomalies étaient présentes dans la révision auditée.

| Probe et constat | Résultat exécuté sur v0.15 | Portée de la preuve |
|---|---|---|
| Parseur, N1 | CAN `43010000` → `[]` ; `430200870000` → `[P0087]` ; non-CAN `43` et `47` → `[]` | Appel du vrai parseur. Mise à jour de l'historique persisté déduite du chemin applicatif. |
| Parseur, N2 | `4300` + `BUFFER FULL` ou `CAN ERROR` → `[]` ; MIL off + `DATA ERROR` ou `BUFFER FULL` → `(false, 0)` | Appel du vrai parseur, entrée synthétique explicitement malformée/partielle. |
| Transport, N3 | Après NRC78 : `isConnected=true`. La deuxième requête vitesse accepte `[40]`, réponse de la première ; la réponse actuelle simulée est `[80]`. | Vrai client/transport TCP. Réponse tardive déclenchée causalement par la requête suivante, comportement matériel non vérifié. |
| Transport, N4 | Deux RPM contradictoires → `null` sans limite ; avec suffixe `1` → `[26,248]` ou `[46,224]` selon l'ordre ECU. | Contrat du simulateur conforme au principe de limite de réponses ; aucune mesure sur les sondes physiques. |
| Smoke, N5 | Nouveau CSV manuel actif avant `stopAutoTest`, fermé après, writer remplacé puis supprimé. | Vrai smoke test et méthodes REC, vrais fichiers ; champs privés observés, jamais modifiés. |
| ViewModel, N6 | Vitesse groupée `42 km/h` datée 766 ms après la réception du repli RPM par le serveur. | Vrai groupage détecté et polling ; délai de repli 750 ms, sous l'échéance production. |
| ViewModel, N7 | VIN `1D4GP00R55B123456` → `1D4GP00R55B123457` ; deux points à 1 000 tr/min conservés sous B qui mesure 2 000 tr/min. | Connexions publiques et VIN distincts décodés, aucun champ d'état injecté. |
| VIN, N8 | Caractère `6` → 2036 ; contrôle SEAT caractère `B` → 2011. | Prouve le cycle codé, pas l'année réelle d'un véhicule synthétique. |
| Export, N9 | Trigger P0087 seul : absent du rapport ; trigger + mesure : présent. | Vrai générateur de rapport appelé avec un état représentatif du diagnostic indépendant. |
| Contrôles positifs | CAN zéro, CAN avec padding après un code, non-CAN padding nul, `SEARCHING...`, hex tabulé/espacé/compact : acceptés. | Empêche de confondre les défauts ciblés avec un refus général du format. |

19 entrées étaient annoncées lors de la préparation du probe parseur ; sa version
finale exécutée en contient **20**, comprenant les contrôles positifs. Le
statut zéro de ce probe signifie que toutes les observations ont pu être recueillies,
pas que les contrats applicatifs sont corrects. Les probes VM et smoke sortent en
échec parce que leurs assertions du comportement requis sont violées : ce sont
des **reproductions de bugs**, pas des échecs de compilation ou du banc.

Preuves locales :

- `captures/audit_code_20261007/smoke_recording_ownership_runs/run_20261007_083701_9seui9qu/` :
  compilation 0, exécution 1, `inputs_unchanged=true`, CSV distincts et journal.
- `captures/audit_code_20261007/runs/run_20261007_084140_q0wn57a5/` : compilation 0,
  parseur 0, transport 0, VM 1 ; `inputs_unchanged=true`, aucune entrée modifiée.
  Historique, commandes et mesures temporelles conservés par scénario.

Le rapport et les probes sont versionnés ; les fichiers générés sous `captures/`
restent locaux et ignorés. Le banc permet de recréer les preuves sur le commit
audité, puis de vérifier les corrections sur une révision suivante.

## Suivi des corrections — version 0.16

Les preuves précédentes sont conservées comme état initial de v0.15. Cette
livraison corrige les dix nouveaux constats et les quatre défauts antérieurs
réévalués. La validation est effectuée sans sonde ni véhicule ; les propriétaires
Android réels et la radio restent hors du périmètre des doubles JVM.

| Constat | Comportement corrigé |
|---|---|
| N1 | Slot DTC nul dans le compte CAN et service non-CAN sans paire refusés. Zéro confirmé et padding valide restent acceptés. |
| N2 | Erreur ELM avant/après une réponse positive rend l'échange incomplet ; un succès survivant ne confirme plus un résultat complet. |
| N3 | NRC21/NRC78 corrélé vérifié et transport fermé sous mutex avant toute requête suivante. La découverte conserve son exception NRC21 avec bitmap complet. |
| N4 | Suppression du suffixe limitant à une réponse et de sa détection ; les contradictions entre calculateurs restent visibles. Groupage conservé. |
| N5 | Smoke test propriétaire d'un token exact de capture ; annulation et fin ne ferment pas un REC manuel de remplacement. Tokens uniques entre propriétaires dans le processus et action STOP distincte par URI. |
| N6 | Date civile et temps écoulé capturés dès réception du groupe, avant les replis individuels. |
| N7/K1 | Courbes et CSV liés à leur VIN. Même VIN connu : reprise ; VIN différent ou identité non vérifiable : courbes vidées et CSV arrêté avec motif, fichier conservé. Le motif survit aux reconnexions suivantes jusqu'au prochain REC explicite. Deux VIN inconnus ne prouvent pas une identité commune. |
| N8 | Cycles d'années candidats et borne de contexte ; année présentée comme estimée dans l'écran et le partage. Pas de règle nord-américaine appliquée à tous les VIN. |
| N9 | Déclencheur et date du freeze frame exportés même sans mesure, avec absence de mesure explicitement indiquée. |
| N10 | Surveillance du transport déjà fermé indépendante du polling, sans requête véhicule ajoutée ; état de reconnexion et retry exercés sur bitmap nul. |
| K2 | Horloge monotone `elapsedRealtime()` pour fraîcheur, cadences et durées des courbes ; heure civile conservée pour dates/export/CSV. L'affichage prend aussi l'heure du rendu : une valeur arrivée après le dernier tick UI n'est pas brièvement classée indisponible. |
| K3 | Journal et crash sérialisés sur un seul writer/formatter IO ; appels normaux non bloquants, attente de crash bornée. |
| K4 | Session détenue hors de l'Activity pendant REC, réutilisée au retour ; libération lorsqu'il n'y a plus d'interface ni de capture. Notification de retour et d'arrêt liée à la capture exacte. Aucune reconstruction automatique après terminaison du processus. |

Optimisations appliquées : pas de reconnexion en quittant Réglages sans changement
de cible Wi-Fi, collecte de l'état liée au lifecycle, animation du petit point créée
uniquement lorsqu'elle est utilisée, métrique renommée « mesures seules, hors
alertes ». Lint, banc assertif d'audit et suite connexion/CSV/voyants ajoutés avant
publication en CI ; les quatre probes d'audit utilisent une compilation commune.

La revue du correctif N3 a également identifié son cas manuel ATH1 : une SF CAN
`7E8037F0178` conservait encore la liaison et déclenchait `ATH0`. La capture vérifie
désormais ces NRC sous le même mutex, en CAN11/CAN29 selon le protocole complet
établi, sans déduction depuis la trame. La restauration n'utilise que le transport
capturé encore ouvert et ne masque pas l'erreur NRC. Un format inconnu/non-CAN
refuse cette capture manuelle avant ATH1 ; les lectures standard non-CAN restent
disponibles. Le réassemblage général ECU connecté n'est pas activé.

Les autres propositions restent des améliorations possibles : propriétaire IO du
cycle complet des CSV et de la liste des fichiers, suspension explicite des tickers
UI en arrière-plan, découverte séparée des capacités mode 02 et extraction du
recorder/ordonnanceur. Aucun gain de batterie ou de latence sur téléphone n'est
annoncé. Le cache DTC à MIL/compteur constants, les profils BLE pris en charge et
l'absence d'attribution ECU dans les lectures ATH0 restent des limites connues.

### Validation de v0.16

- `testDebugUnitTest` : **406 tests**, zéro échec/erreur/test ignoré. `assembleDebug`
  réussi, `lintDebug` : **No issues found**. Les tests incluent le gardien sous mutex,
  les NRC CAN11/CAN29 de capture manuelle, l'annulation et les contrôles positifs,
  la propriété des sessions et les écritures concurrentes du journal.
- Banc d'audit complet : compilation 0, parser/transport/VM/smoke **tous à 0**,
  `inputs_unchanged=true`. Les 20 contrats parseur/export passent ; les conflits ECU
  restent refusés dans les deux ordres, la session NRC78 ferme avant la requête
  suivante, le groupe est daté avant son repli, les cinq politiques VIN/CSV passent,
  la reconnexion sans polling et la conservation du REC manuel sont confirmées.
  Preuve : `captures/audit_code_20261007/runs/run_20261007_200744_0fqv0n96/`.
- Suite connexion/CSV/voyants : **19 scénarios, zéro échec**,
  `inputs_unchanged=true`. Le scénario bitmap nul vérifie pause pendant découverte,
  arrêt après identité non vérifiable, fichier conservé, absence de nouvelle lecture
  automatique, motif conservé après reconnexion et nouveau REC uniquement explicite.
  Preuve : `captures/audit_2026-09-13/recording_reconnect/run_20261007_195655_vb1t3w0u/`.
  L'ancien contrat de ce scénario autorisait la reprise sans identité ; il a été
  remplacé par le contrat annoncé N7/K1. Une compilation locale a dépassé son délai
  de 120 s avant les tests ; le rejeu réussi utilise 360 s pour la compilation.
  Les échéances de lecture véhicule ne sont pas concernées.
- **36 tests de rendu** : 35 images statiques et une animation réelle de deux cycles
  (25 frames). Les 34 images statiques antérieures et l'animation sont identiques
  aux images v0.15 ; seule l'image supplémentaire du motif d'arrêt CSV est nouvelle.
  Grandes polices, cadrans/menus et états frais/anciens/indisponibles inspectés.
  Le clignotement modifie uniquement le symbole, rectangle `(202,428)-(298,506)` ;
  son texte reste fixe. La fabrication des timestamps du banc se fait après la
  remise à zéro de l'horloge Layoutlib, sans modifier les timestamps de production.
  Rapport : `captures/dashboard_unified_20261003/build/reports/paparazzi/debug/`.

Inventaire des empreintes et résultats :
`captures/audit_fixes_20261007/validation.json`. Les artefacts générés restent locaux
et ignorés ; parseurs, tests unitaires, doubles et bancs backend sont versionnés.
La CI rejoue unitaires, build, lint, audit et les 19 scénarios sur le commit du tag
avant de publier l'APK. Signature/version/digest de l'APK publié sont contrôlés
séparément lors de sa récupération, avant une éventuelle installation USB.
