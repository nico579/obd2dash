# Mises à jour depuis l’application — 0.17

L’entrée **… → Mises à jour** reste accessible sans sonde. Elle vérifie la dernière
publication stable, indique les versions installée/publiée, télécharge l’APK avec
progression et annulation, puis ouvre la confirmation de l’installateur Android.
Les commandes de téléchargement/installation précèdent l’aide et sont placées
à côté du résumé en paysage. La barre commune conserve ses cinq commandes.

## Dépôt privé

`nico579/obd2dash` est privé, vérifié avec `gh repo view` le 7 octobre 2026.
L’API sans authentification renvoie 404 ; l’accès authentifié lit bien la release.
L’application utilise donc un jeton personnel saisi dans **Accès GitHub** :
propriétaire `nico579`, seul dépôt `obd2dash`, permission **Contents: read-only**.
Ce droit couvre la lecture des releases et de leurs fichiers selon
[GitHub](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).

Le jeton est chiffré par AES-GCM avec une clé Android Keystore, conservé dans les
préférences privées et exclu des sauvegardes ainsi que du transfert Android.
Le champ est masqué, sans `rememberSaveable`, sans préremplissage du secret.
Il peut être remplacé ou supprimé. Aucun jeton n’est committé, embarqué dans l’APK,
écrit dans les journaux ou communiqué au CDN après une redirection.
Un miroir public contenant uniquement les APK reste une décision de diffusion
distincte ; aucune visibilité GitHub n’a été modifiée.

## Téléchargement et installation

- HTTPS, liste explicite d’hôtes GitHub et maximum de cinq redirections.
- Authentification limitée à l’API du dépôt ; téléchargement via l’identifiant
  d’asset pour prendre en charge les fichiers privés.
- Métadonnées bornées à 1 Mio, APK à 100 Mio ; délais de connexion/lecture et
  durée globale configurés. Le téléchargement, la validation de l’APK et le
  stockage du jeton sont exécutés hors du fil UI.
- Taille et SHA-256 du fichier obligatoires et comparés à ceux de la publication.
- Paquet installé, ensemble des signataires, `versionCode` croissant et version
  correspondant au tag vérifiés par inspection de l’APK Android.
- Fichier partiel supprimé après échec ou annulation ; seul un fichier complet
  et validé devient installable. Le cache est de nouveau vérifié avant installation.
- Installation bloquée pendant REC, un sondage ou le Smoke test. La condition est
  contrôlée de nouveau après la vérification du fichier ; aucune capture n’est
  arrêtée automatiquement pour installer.
- Autorisation d’installation demandée à Android si nécessaire, puis second appui
  sur **Installer la mise à jour**. L’installation reste confirmée par le système,
  conformément à son [autorisation par application](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).
- Les réglages et captures sont conservés par la mise à jour du même paquet ;
  aucun changement du réseau OBD ou des commandes envoyées au véhicule.

## Validation

- **433 tests unitaires**, dont 27 nouveaux pour la publication, les versions,
  signatures, empreintes, téléchargements tronqués/corrompus, annulation, URLs,
  authentification et protection des captures.
- **47 rendus Paparazzi** : 11 nouveaux cas de mises à jour/accès GitHub, en plus
  des scénarios antérieurs, avec portrait/paysage, petits écrans et grands textes.
- `assembleDebug` réussi et `lintDebug` : zéro erreur et avertissement.
- Téléchargement réel du v0.16 depuis le dépôt privé avec le code HTTP/parseur/hash
  de production non modifié : **8 542 484 octets**, SHA-256
  `907b2286e7655cd92aecb3b5a51244b40c09e791dcc9615c75a0b8a20d9693e6`.
  L’annulation d’un second téléchargement laisse aucun APK ni fichier partiel.
  Preuve : `captures/updates_20261007/live_20261007_220808_0yf38mmj/validation.json`.
- Un premier lancement global a expiré pendant l’ATZ de préparation d’un test
  TCP local à 200 ms, avant la commande testée ATDPN. La suite seule, avec un
  worker Gradle, a ensuite passé ses 433 tests. Aucun délai véhicule ni test
  existant n’a été modifié.

Le test Internet est reproductible après les tests Gradle :

```powershell
rtk proxy python tools/update_release_check/run.py --use-gh-auth --timeout 240
```

Il conserve localement l’APK, les empreintes des sources et les résultats sous
`captures/updates_20261007/`. L’authentification du PC reste uniquement en mémoire.
Ce test JVM ne valide pas Android Keystore, l’allocation d’espace Android ni
l’interface de l’installateur sur un vrai téléphone. Aucun téléphone USB n’était
détecté au moment des contrôles locaux ; ces étapes nécessitent le prochain branchement.
L’APK 0.17 doit être installé une première fois pour disposer du menu.

## Publication et vérification du 8 octobre 2026

La [release v0.17](https://github.com/nico579/obd2dash/releases/tag/v0.17) est
publiée depuis le commit `d021e11f5923f781d4d0ab6f97711c07dee65209`.
Toutes les étapes du [workflow de publication](https://github.com/nico579/obd2dash/actions/runs/37681491187)
ont réussi : tests unitaires, compilation APK, lint, régressions d’audit/CSV,
régressions connexion/captures/voyants et publication. Les notes de release
expliquent l’accès au dépôt privé et la première installation nécessaire.

Le contrôle Internet utilisant le code de production a téléchargé cette release :
**8 612 551 octets**, SHA-256
`4660026d4f1d87d2b40ad0e4ac560604fe43134e4ca9e3b8e7ff2d9ec48ae73b`.
Le résultat correspond exactement à l’asset GitHub ; les sources sont restées
inchangées. Un second téléchargement annulé a bien supprimé son fichier partiel.
Preuve : `captures/updates_20261007/live_20261008_014819_ipf06v5y/validation.json`.

L’inspection de cet APK publié confirme le paquet `com.nico.obd2dash`,
`versionCode=17`, `versionName=0.17`, une signature valide identique à celle du
v0.16, la présence de la permission d’installation et les octets de la police
et de sa licence. Preuve :
`captures/updates_20261007/live_20261008_014819_ipf06v5y/apk_validation.json`.

Le nouveau contrôle `adb devices -l` ne détecte aucun téléphone. Aucune installation
n’a donc été effectuée ; le parcours Android et le stockage Keystore restent à
valider sur l’appareil. Le dépôt et les APK restent privés.
