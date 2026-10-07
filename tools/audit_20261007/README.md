# Régressions de l'audit du 7 octobre 2026

Depuis la racine, dans l'environnement Windows du projet :

```powershell
rtk proxy python -X utf8 tools/audit_20261007/run.py --include-smoke
```

Un cas ciblé évite d'exécuter les autres : `--case parser`, `--case transport`,
`--case vm`, `--case smoke`. Le runner compile les sources applicatives **sans réécriture**,
avec les doubles Android et le serveur du banc `tools/offline_viewmodel`.
Il utilise le JDK et les JAR Kotlin/coroutines du cache existant ; aucun téléchargement,
Gradle, ADB, Bluetooth, sélection réseau Android ou adresse de véhicule.
Tous les serveurs sont liés à `127.0.0.1` et refusent un pair non local.

Préconditions de cache/JDK et limites des doubles :
[banc ViewModel](../offline_viewmodel/README.md). Les arguments `--compile-timeout`
(120 s) et `--timeout` (90 s par processus) bornent les exécutions. Aucun délai ou
constante de production n'est modifié. Les runs lourds doivent rester séquentiels.

Chaque run écrit un dossier unique sous `captures/audit_code_20261007/runs/` :
commandes et empreintes, code de compilation, code de chaque processus, sorties,
vérification que les sources n'ont pas changé pendant le run. Les probes VM et
smoke ajoutent fichiers CSV et/ou commandes de la sonde simulée.

## Contrat courant, version 0.16

Le banc contient désormais des **assertions d'acceptation des corrections**.
La compilation et chaque probe sélectionné doivent terminer avec un code 0 ;
un contrat violé produit un code non nul. Le run sans `--case` exécute parser,
transport et VM ; `--case smoke` exerce séparément le cycle d'enregistrement du
smoke test. `--include-smoke` exécute les quatre probes en séquence après une
seule compilation ; c'est aussi la commande de la CI. Une validation doit citer le run et ses empreintes, pas seulement
l'existence de ce banc.

- **parser** : réponses DTC/MIL incomplètes ou comportant une erreur ELM,
  contrôles valides, estimation d'année VIN et export du déclencheur du freeze frame.
- **transport** : fermeture après réponse temporaire et refus des mesures
  contradictoires, y compris lorsque l'adaptateur sait limiter les réponses.
- **vm** : deux VIN confirmés, historique graphique, datation d'un groupe avant
  repli lent, cinq combinaisons d'identité pour les CSV et détection d'un transport
  déjà fermé sans polling, suivie d'une reconnexion automatique.
- **smoke** : le vrai smoke test démarre un CSV ; un arrêt manuel suivi d'un
  nouveau REC crée un autre token. Arrêter le smoke test doit conserver ce REC.

Les reprises CSV avec le même VIN connu servent de contrôles positifs. Un VIN
différent ou non vérifiable entraîne un arrêt explicite du CSV avant les mesures
suivantes ; deux VIN inconnus ne prouvent pas une identité commune. Le serveur
par défaut annonce un VIN stable, tandis que les cas inconnus répondent
explicitement `NO DATA` à la lecture du VIN.

## Preuve historique de la version 0.15

Les résultats ci-dessous concernent uniquement la révision auditée
`ab7a5b6d2f14bcd3c1274a39fe8981cdc5081b04`. À cette date, parser et transport
étaient des probes d'observation dont le code 0 ne signifiait pas absence de bug.
Le run complet terminait avec un code 1 car les deux contrats VM étaient violés ;
le cas smoke terminait également avec un code 1. Ces anciennes preuves et leurs
empreintes restent conservées dans les captures et le rapport ; elles ne sont
pas remplacées par les résultats de la version corrigée.

| Probe | Comportement exercé | Résultat v0.15 observé |
|---|---|---|
| parser | Vrais parseurs DTC/MIL/VIN et rapport FF ; entrées synthétiques, sans connexion | Réponses DTC incomplètes acceptées, erreurs ELM voisines ignorées, cycle VIN forcé, trigger FF seul omis. Les contrôles valides, dont les tabulations, passent. Les observations ne sont pas des assertions : code processus 0. |
| transport | Vrai client sur TCP local, NRC78/réponse retardée et limite d'une réponse sous deux ordres ECU | Session gardée ouverte après NRC78, ancienne vitesse acceptée pour la deuxième requête ; conflit RPM masqué par le suffixe 1. Sortie JSON, code 0 même si un défaut est observé. |
| vm | Vraies connexions entre deux VIN, sélection graphique, découverte du groupage et polling | Deux points A conservés sous VIN B ; valeur groupée datée 766 ms après le début d'un repli précédent de 750 ms. Deux assertions échouent, les deux cas sont exécutés. |
| smoke | Vrai smoke test et méthodes publiques REC, arrêt/remplacement du fichier | Nouveau fichier manuel fermé par l'arrêt du smoke test ; assertion échouée. Réflexion utilisée uniquement pour observer owner/writer, sans mutation. |

Les valeurs temporelles de 766 ms et les identités VIN citées sont celles du run
initial. Un autre run peut donner une autre durée ; l'invariant est que la valeur
groupée était déjà reçue **avant** la requête de repli, tandis que sa date est
postérieure au repli retardé. La tolérance de100ms de l'assertion concerne le
contrôle du banc, pas un changement de l'échéance véhicule.

Les probes ne prouvent ni la persistance Android réelle, ni le lifecycle,
ni la radio, ni que les sondes physiques produisent les réponses simulées.
Le probe transport indique explicitement `physical_adapter_behavior_verified=false`.

Rapport de référence : [audit_code_2026-10-07.md](../../audit_code_2026-10-07.md).
