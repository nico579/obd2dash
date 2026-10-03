# Alertes automatiques des captures — 3 octobre 2026

La demande porte sur les voyants lus automatiquement. L'ajout utilise uniquement
des lectures du service OBD 01 annoncées par les bitmaps du véhicule. Il n'ajoute
ni annotation manuelle, ni sondage constructeur, ni changement de session ECU.

## Définitions retenues

| État | PID | Octets de données requis | Interprétation |
| --- | --- | --- | --- |
| MIL | 01 | 4 | A7 indique le voyant moteur ; les sept autres bits donnent le nombre de codes stockés. |
| Préchauffage | 65 | 2 | A3 doit annoncer la fonction ; B3 indique allumé/éteint. |
| Mode MI véhicule WWH-OBD | 90 | 3 | A5..2 : 0 éteint, 1 à la demande, 2 mode court, 3 mode continu. |
| Mode MI calculateur WWH-OBD | 91 | 5 | A3..0, mêmes modes. |
| Alerte NOx | 94 | 12 | A0 annonce le système d'alerte ; B0 indique son activation. |

Pour les modes WWH, E indique une erreur et 4..D sont réservés : la lecture est
rejetée. F reste « indisponible », jamais « éteint ». Pour PID65/94, une fonction
non prise en charge reste distincte d'une fonction prise en charge et inactive.
Les autres bits de ces PID ne sont pas convertis en voyants.

Source primaire consultée le 3 octobre 2026 :
[documentation des PID du fabricant DashLogic](https://www.dashlogic.com/docs/technical/obdii_pids).
Les longueurs portent sur l'ensemble du payload après service/PID, y compris les
compteurs non utilisés. PID94 nécessite donc un assemblage multi-trame complet.

## Limites de couverture

Cette lecture ne couvre pas ABS, airbag, frein, pression d'huile, alternateur,
ceinture ou les autres modules constructeur. Une tension de batterie, une
température ou un DTC ne prouvent pas l'état du voyant correspondant.
PID8B décrit une régénération FAP, pas un voyant FAP. L'alerte NOx n'est pas
étiquetée « voyant AdBlue » ; un mode WWH à la demande n'est pas transformé en
« allumé maintenant ».

Le Bluetooth de la KONNWEI et le Wi-Fi de l'ancienne sonde transmettent les
mêmes requêtes standard. La couverture dépend du véhicule et des définitions
disponibles, pas du Bluetooth seul.
[OBDLink distingue diagnostics OBD standard et diagnostics des modules constructeur](https://support.obdlink.com/support/solutions/articles/43000713278).

## Lecture et conservation

- Première lecture dès le cycle initial ; cadence visée de 30 s hors REC, 5 s
  pendant REC, plus la durée des échanges. Les pauses de diagnostic suspendent
  aussi ces lectures. Un allumage bref peut être manqué.
- Une requête par PID, sans suffixe « une réponse » : tous les répondants restent
  visibles. Une réponse négative, malformée, incomplète ou contradictoire ne
  confirme pas un état éteint. Une fonction absente d'un répondant ne masque pas
  la réponse d'un autre qui la prend en charge.
- Un non-support explicite de PID65/94 arrête les tentatives de cette fonction
  pour la connexion courante. Une reconnexion redécouvre les possibilités et
  efface les anciens états ; une lecture refusée est retentée à la cadence normale.
- Les NRC21/NRC78 et les erreurs de transport ferment la liaison avant toute
  commande suivante. Un refus permanent isolé ne bloque pas les autres mesures.

Les six premières colonnes historiques restent inchangées. « Tentative MIL » et
« Erreur MIL » les suivent. Chaque alerte supplémentaire annoncée possède quatre
colonnes : valeur, Lecture, Tentative, Erreur. La valeur est la dernière lecture
réussie, pas une observation au moment de l'écriture de la ligne. Les dates des
alertes comprennent le décalage horaire ; un échec conserve l'ancienne date et
l'ancien état, avec une nouvelle tentative et son erreur. Sans succès, l'état
reste « non lu ». L'export diagnostic conserve la même distinction.

Le CSV lit exclusivement le cache, sans nouvelle requête depuis son writer. Ses
colonnes restent figées au démarrage, y compris après une reconnexion. Une alerte
absente de la nouvelle connexion produit « non lu » et des dates vides dans les
colonnes existantes. La portée et la cadence figurent dans les métadonnées du CSV.

## Validation

Les tests utilisent uniquement des réponses synthétiques et TCP sur 127.0.0.1.
Les tests unitaires couvrent les bits de support/état, les modes WWH, les longueurs,
les refus, contradictions multi-ECU, assemblages NOx et les dates des champs CSV.
Les tests de transport vérifient les commandes 01 exactes et l'arrêt sur réponse
temporaire. Le banc du ViewModel exerce les changements d'états, les échecs puis
la reprise, les vrais fichiers CSV, les diagnostics exclusifs et la reconnexion.
Cela valide le traitement logiciel ; la disponibilité réelle sur un véhicule
reste à vérifier avec les paramètres qu'il annonce.

Résultats locaux pour la version 0.12 : 364 tests unitaires réussis, aucun problème
Android lint, APK compilé. Le banc complet a validé 18 scénarios sur 19. Le nouveau
scénario CSV avait une assertion incorrecte : une réponse MIL déjà en vol pouvait
encore réussir après le changement de phase simulée. Le test compare désormais
deux échecs confirmés successifs ; son rejeu isolé passe. Les anciens états et
dates restent inchangés entre ces échecs, et une réussite suivante efface l'erreur.
Les deux exécutions et leurs empreintes sont conservées dans
`captures/recording_lamps_20261003/validation.json` (preuves locales ignorées par Git).

## Ajustement des cadrans de la version 0.12

À la demande de l'utilisateur, la valeur descend près du cercle inférieur et s'affiche
sans décimale, avec un arrondi à l'entier le plus proche (demi-arrondis éloignés
de zéro). Les unités et les composantes secondaires restent présentes.
Le pivot de l'aiguille reste au centre ; la mesure précise pilote toujours son
angle, les courbes et le CSV. La taille commune des cadrans est conservée.

Les 21 rendus Paparazzi passent. La revue visuelle couvre six cadrans paysage,
trois et six portrait, le mode nuit avec grands caractères et une fenêtre basse
640×240 pendant REC. Les images sont archivées sous
`captures/recording_lamps_20261003/rendus/`. Aucun test radio ou véhicule n'est
effectué par cette validation.
