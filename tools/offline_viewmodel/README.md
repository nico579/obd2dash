# Régressions du ViewModel hors véhicule

Depuis la racine du dépôt :

```powershell
rtk proxy python tools/offline_viewmodel/run.py
rtk proxy python tools/offline_viewmodel/run.py --case manual_reconnect
```

Le runner utilise Python standard, un JDK déjà installé (`JAVA_HOME`, sinon
`~/.jdks/jbr-21.0.11`) et les JAR Kotlin 1.9.24/coroutines 1.8.1 déjà présents dans
le cache Gradle. `GRADLE_USER_HOME` est respecté. Il ne télécharge rien et ne lance
ni Gradle ni ADB. `--compile-only` permet de vérifier seulement la compilation ;
`--compile-timeout` borne sa durée (120 secondes par défaut, mémoire limitée à 768 Mo) ;
`--timeout` borne l'exécution des scénarios Kotlin (240 secondes par défaut).

Chaque exécution crée son propre dossier sous
`captures/audit_2026-09-13/recording_reconnect/`, avec classes, ressources générées,
journaux de compilation/exécution, CSV des scénarios et `provenance.json`.
Ce dernier conserve les commandes, codes de sortie et empreintes des sources,
doubles, ressources et dépendances. Une modification des entrées pendant le test
invalide le résultat. Le nom après `--case` est transmis au main Kotlin après le
dossier de sortie ; sans option, tous les scénarios sont exécutés.

Les sources Kotlin de production sont compilées sans réécriture. Les appels
du ViewModel utilisent son vrai client ELM et ses coroutines, contre les serveurs
TCP locaux de `fake_elm.kt`. Aucune adresse de sonde n'est acceptée par le runner.
Les préférences du double désactivent la connexion initiale sans cible ; le test
appelle ensuite explicitement la connexion locale. Le transport Bluetooth du
double lève une erreur s'il est sollicité.

Les seize scénarios couvrent : reconnexion explicite prolongée puis reprise dans le
même CSV ; arrêt manuel pendant la reconnexion ; connexion sans mesures ; échec de
connexion ; coupure TCP puis reprise automatique ; adresse Wi-Fi invalide avec une
session active ; choix Bluetooth sans appareil ; échec du transport Bluetooth
simulé ; conservation de la capture manuelle pendant un refresh DTC et son
nettoyage ; arrêt puis nouveau fichier pendant une écriture IO inachevée.
Les tests de pause dépassent l'intervalle CSV réel de cinq secondes et comparent
les octets du fichier ainsi que le compteur. Le dernier scénario interpose un
writer à verrou contrôlé et utilise la réflexion pour démarrer la vraie boucle
sur ce writer ; il ne modifie pas les sources applicatives. Les deux nouveaux cas
vérifient la publication du MIL malgré un premier échec des codes, leur relecture
après cet échec (`--case mil_retry`), et la conservation de la date du scan complet
après une relecture automatique des seuls codes stockés (`--case diagnostic_dates`).
Ils attendent la cadence MIL réelle de 30 secondes, sans la modifier pour le test.
Quatre cas supplémentaires vérifient les refus du dialogue standard Trafic sans
faux « aucun défaut », les dates après une lecture partielle, l'arrêt sur NRC78
et la lecture automatique de codes stockés malgré un refus du MIL.
Le double BLE interdit tout accès radio ; les tests unitaires de `BleSerialTransport`
vérifient séparément les callbacks et les flux avec un pilote GATT simulé.

Les doubles remplacent le contexte Android, la sélection réseau, les préférences,
les services, le journal applicatif et l'historique DTC. Un exécuteur à un seul
thread remplace le dispatcher UI. Les identifiants `R.string`/`R.plurals` réellement
référencés et leurs textes sont régénérés depuis les XML de base ; cette résolution
simplifiée ne teste pas les traductions, Android/aapt ou les règles de pluriel des
autres langues. Ces tests ne vérifient ni Compose, ni le cycle de vie réel Android,
ni les notifications, permissions, interruptions radio ou le comportement d'un
véhicule/adaptateur physique.
Le scénario de nettoyage ne lance pas de smoke test actif : la capture qui
appartient à ce smoke test et les autres entrelacements de diagnostic restent
hors de cette couverture.
