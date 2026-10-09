# Retrait des données personnelles — 8 octobre 2026

Le dépôt a été remis en privé après identification de données personnelles dans
des documents, des fixtures de test et des images anciennes. La suppression couvre
les fichiers actuels et l'historique des branches et des tags publiés :

- retrait des anciennes captures d'écran et photos, dont des métadonnées GPS ;
- retrait du rapport HTML contenant l'identité d'un véhicule réel ;
- remplacement des VIN et numéros de série de terrain par des valeurs fictives ;
- suppression des références au profil utilisateur et du prénom dans les documents.

Les caractères de constructeur et d'année nécessaires aux tests de VIN sont
conservés ; les numéros de série sont fictifs. Le code exécuté par l'application,
sa version et sa clé de signature debug restent identiques. Les APK déjà publiés
sont conservés ; la réécriture des tags ne doit pas déclencher leur reconstruction.
Une sauvegarde privée de l'ancien historique et les preuves détaillées restent
hors du dépôt publié. Ne pas les réintroduire dans un commit.

Validation locale : 433 tests unitaires réussis, rejeux d'audit et de propriété des
captures réussis, 111 sources documentaires contrôlées, aucun accès à un véhicule.
Le contrôle préventif a été éprouvé dans un dépôt isolé avec des exemples fictifs
et des cas de refus, y compris une valeur retirée du fichier mais encore dans l'index.

## Références conservées par GitHub

La réécriture change les identifiants de commits. Les deux anciennes demandes de
fusion et les vues en cache peuvent encore référencer des objets antérieurs.
Le **dépôt d’origine**, renommé le 9 octobre 2026, reste privé tant que ce nettoyage
côté GitHub n'est pas terminé. L'adresse publique `nico579/obd2dash` désigne depuis
cette migration un **nouveau dépôt**, créé uniquement à partir des branches et
tags nettoyés. Les anciennes demandes de fusion n'ont pas été transférées.
Le support doit intervenir sur le dépôt d'origine ; la migration ne vaut pas
confirmation d'une purge de ses objets conservés. Voir le
[suivi de migration](migration_depot_2026-10-09.md).
La [procédure GitHub de retrait des données sensibles](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
prévoit une demande au support pour les références de demandes de fusion et les
vues conservées. Une réécriture ne supprime pas les copies déjà téléchargées ailleurs.

Les clones existants doivent être remplacés ou nettoyés avant de pousser de nouveau.
Un merge de l'ancien historique réintroduirait les données retirées. Les liens vers
les anciens commits restent des références historiques ; ils ne désignent plus
nécessairement un commit accessible dans l'historique nettoyé.

## Prévention

`captures/`, `screenshots/` et le rapport HTML sont ignorés par Git. Les images brutes
de terrain restent locales. Pour partager un exemple, préparer une version anonymisée
et vérifier également les métadonnées du fichier.

Après mise en index, exécuter :

```text
rtk proxy python tools/check_repository_privacy.py
```

Le workflow de release effectue ce contrôle avant compilation. Il refuse les
répertoires de captures, les images hors ressources applicatives, les chemins de
profil Windows et les VIN potentiels dont le numéro de série n'est pas réservé
aux fixtures. Ce contrôle heuristique ne détecte pas toutes les données personnelles
et ne remplace pas une revue. Les chaînes entièrement hexadécimales sont exclues
de la détection de VIN pour éviter de confondre les trames CAN avec des identifiants.
Les données encodées et les documents binaires nécessitent une inspection spécifique.
