# Migration du dépôt — 9 octobre 2026

Le dépôt public [nico579/obd2dash](https://github.com/nico579/obd2dash) est un nouveau
dépôt GitHub, indépendant du dépôt d'origine. Il conserve la même adresse pour que
l'application déjà installée retrouve les prochaines mises à jour.

Le dépôt d'origine a été renommé et reste **privé**, avec ses releases et ses
anciennes demandes de fusion. La demande de retrait de ses références et objets
conservés reste ouverte auprès du support GitHub. Cette migration ne constitue
pas une confirmation de leur purge et ne supprime pas les copies externes.

## Contenu transféré et contrôles

- Les **deux branches et 17 tags nettoyés** ont été transférés explicitement.
  Aucun ancien historique de demande de fusion n'a été copié.
- Un clone neuf a été contrôlé sur tous ses objets accessibles : documents,
  sources, commits, tags et métadonnées GPS des images. Les valeurs personnelles
  identifiées pendant le nettoyage et les anciens fichiers retirés sont absents.
  Les quatre anciens objets contenant les données identifiées et les deux anciennes
  demandes de fusion sont inaccessibles sur le nouveau dépôt.
- Les **17 releases et 18 APK** ont été sauvegardés puis transférés. Les tailles
  et SHA-256 des nouveaux assets correspondent à ceux des fichiers d'origine.
  Le contenu des APK a également été contrôlé pour les identifiants retirés,
  les chemins de profil Windows et les métadonnées GPS.
- Les Actions ont été désactivées avant le transfert des tags pour éviter les
  reconstructions. Elles sont réactivées sur le nouveau dépôt pour les futures
  publications. La clé de signature debug est inchangée.
- L'accès anonyme à la dernière release et le téléchargement avec le code HTTP,
  le parseur et le contrôle d'empreinte de production ont été vérifiés. L'annulation
  supprime le fichier partiel. Aucun accès à une sonde ou installation Android
  n'a été nécessaire.

La release [v0.17](https://github.com/nico579/obd2dash/releases/tag/v0.17) conserve
**OBD2-Dash-v0.17.apk** et son alias identique **app-debug.apk**. Chacun fait
**8 612 551 octets**, SHA-256 :

```text
4660026d4f1d87d2b40ad0e4ac560604fe43134e4ca9e3b8e7ff2d9ec48ae73b
```

Les dates, identifiants et compteurs de téléchargement des releases recréées
diffèrent de ceux d'origine. Les commits et tags transférés sont ceux de
l'historique nettoyé ; les notes de publication historiques peuvent citer
les identifiants de commits d'avant la réécriture.

Les preuves détaillées, les APK sauvegardés et la correspondance entre les deux
dépôts sont conservés localement dans une sauvegarde privée hors du dépôt publié.
Le [contrôle préventif de confidentialité](confidentialite_2026-10-08.md#prévention)
reste obligatoire après mise en index, avant les prochaines publications.
