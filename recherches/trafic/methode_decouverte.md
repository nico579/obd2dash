# Méthode de recherche et possibilité de généralisation

**Recherche Trafic arrêtée le 3 octobre 2026 à la demande de l'utilisateur.** Ce document
conserve la méthode et ses limites comme historique. Le périmètre actuel du projet
porte sur les véhicules récents compatibles avec les lectures OBD standard ;
les étapes restantes du Trafic ne constituent plus un travail en cours.

État au **3 octobre 2026**. Cette procédure décrit la recherche effectuée et les
conditions nécessaires pour transformer une définition en support de l'application.
L'import de la base PyClip obtenue aujourd'hui n'est pas implémenté dans l'application.
Le catalogue constructeur installé reste vide.

## Comment la recherche a avancé pour le Trafic

1. **Partir de la réponse réelle du calculateur.** Les deux réponses `2180` déjà
   enregistrées donnent `25/037/00CB/1200`, adresse `7A`. Les offsets sont vérifiés
   dans le code du lecteur d'identité ; `0x19` vaut 25 décimal. Aucun nouvel essai
   n'est nécessaire pour relire cette identité inchangée.
2. **Chercher le contenu des définitions.** Les catalogues et l'index CLIP montrent
   des noms possibles, mais ne donnent pas les conversions. Cinq définitions DDT
   réellement obtenues sont exclues, car leur identité diffère. Le code PyClip
   indique ensuite où se trouve sa base externe : `pyrendata*.zip`.
3. **Obtenir et inventorier la base hors ligne.** L'annonce OldRenault et un lien
   public permettent d'obtenir la version 237. Taille et SHA-256 sont conservés.
   Le catalogue `EcuRenault/Uces.xml` référence les modèles et optimiseurs du dossier
   `EcuRenault/Sessions/`. Aucun programme de diagnostic téléchargé n'est lancé.
4. **Comparer les critères publiés aux octets enregistrés.** Parmi 47 entrées
   EDC15, quatre passent leurs masques. `INJ_EDC15_X83_19_A` teste trois octets qui
   concordent (`19/CB/12`), mais ne vérifie pas toute l'identité. Les autres entrées
   ont des critères plus larges. Les fiches véhicule montrent aussi des associations
   différentes : phases 2/3, `TRAFIC II B2`, Clio/Kangoo. Aucune variante n'est choisie
   automatiquement à partir de la seule ressemblance de son nom.
5. **Relier chaque information à ses données.** Dans le candidat 10496, le chemin
   est `paramètre → formule → mnemonic → service → emplacement dans la réponse`.
   Les définitions donnent notamment `21AA` pour régime/vitesse et `21A9` pour
   températures et pressions. Une autre variante utilise `21AB` pour la vitesse :
   le décodage dépend effectivement du modèle.
6. **Identifier ce qui manque avant une activation.** La base prévoit `10C0` ;
   l'accès aux mesures en session par défaut n'est pas établi. Il manque aussi
   une preuve suffisante pour choisir le bon modèle, des réponses de référence et
   les droits de réutilisation. Aucun de ces services n'est envoyé au camion pendant
   la recherche, et aucun profil n'est installé.

Les résultats détaillés et les empreintes se trouvent dans
[le journal](README.md), [le registre](actions.jsonl) et
[le bilan des candidats OldRenault](oldrenault_2026-10-03.json).

## Ce qui peut être généralisé

La **procédure de reconnaissance et de validation** peut être réutilisée pour
d'autres véhicules. Elle repose sur des étapes communes :

| Étape réutilisable | Élément qui doit rester spécifique au calculateur |
| --- | --- |
| Vérifier le transport et la trame avant le décodage | Protocole, adressage et conditions d'initialisation documentés. |
| Lire ou exploiter une identité documentée | Service d'identification, format et critères de compatibilité. `2180` n'est pas une commande universelle. |
| Trouver des définitions et conserver leur provenance | Source disponible, version, contenu et droits d'utilisation. |
| Comparer les critères de toutes les variantes | Identité complète, masques autorisés et gestion des ambiguïtés. |
| Relier mesure, requête, octets, formule et unité | Identifiants et conversions du bon modèle ; pas de formule déduite du seul numéro. |
| Vérifier le décodeur sur des réponses de référence | Exemples applicables, session et limites de la mesure. |
| Activer uniquement un profil vérifié | Lectures explicitement autorisées et conditions d'accès documentées. |

Pour une application multimarque, cela mène à un **moteur commun et des profils
vérifiés par calculateur**, avec des lecteurs de bases adaptés à chaque format.
Le transport Bluetooth/Wi-Fi et la logique d'affichage peuvent être partagés ;
les commandes et formules constructeur restent des données spécifiques.
Une base Renault utilisable ne fournirait pas automatiquement les autres marques.

## Ce qui ne peut pas être promis

Cette méthode ne garantit pas une découverte automatique de toutes les mesures de
tout véhicule ancien. Une identité seule ne révèle pas les requêtes ni les formules.
Une base peut manquer, omettre des variantes, utiliser des critères ambigus ou
demander une session qui dépasse le périmètre de lecture retenu pour l'application.

Dans ces cas, le résultat doit rester explicite : **profil inconnu**, **plusieurs
candidats**, ou **conditions d'accès non validées**. Un score de ressemblance ne doit
pas remplacer une preuve de compatibilité. Un balayage arbitraire d'identifiants
ne fait pas partie de cette procédure.

La généralisation se mesure donc par le nombre de profils correctement documentés
et vérifiés. Pour le Trafic, nous disposons maintenant du contenu de plusieurs
candidats et de huit relations mesure/requête/conversion ; les conditions manquantes
ci-dessus empêchent encore de considérer ces mesures comme prises en charge.

## Prochaine preuve attendue pour ce Trafic

Une trace de référence ou une définition plus discriminante doit relier
**25/037/00CB/1200** au modèle de mesures applicable. Il faut également des conditions
documentées de lecture sans `10C0`, puis des réponses permettant de vérifier les
emplacements et conversions. Une nouvelle consultation des catalogues déjà connus
ne répondrait pas à ces questions.

## Validation sur véhicule et risque

**Il n'est pas nécessaire de retourner maintenant dans le Trafic.** Le choix du
profil, l'analyse des captures et les tests du décodeur peuvent avancer hors ligne.
Pour confirmer ensuite le fonctionnement réel, il faudra des réponses de cet ECU,
issues d'un essai préparé ou de traces existantes applicables.

La [documentation originale ACTIA sur KWP](https://www.ime-actia.de/en/vehicle-diagnostics/kwp-2000-on-k-line/)
distingue le service de session `10` des services de lecture `21` et d'effacement
`14`. `10C0` n'est donc pas à lui seul une commande d'effacement ou de reprogrammation.
Cette source ne décrit toutefois pas le mode `C0` de notre ECU : ses effets restent
non établis. On ne doit le qualifier ni d'inoffensif ni de mode de programmation
sur la seule valeur de son paramètre.

**Le risque d'un nouvel essai constructeur n'est pas suffisamment établi pour
promettre un risque nul.** Aucun essai `10C0` n'est proposé à ce stade. Un éventuel
essai de lecture devrait porter sur des requêtes connues, avec conditions d'accès
documentées, véhicule stationné et d'abord moteur arrêté, sans effacement,
actionneur, écriture ou changement de session. Ces précautions ne constituent pas
une garantie absolue d'absence de risque.

Le moteur de profils actuel accepte seulement un profil exact, vérifié en rejeu
et documenté en session par défaut ; son catalogue installé est vide. Cette
restriction logicielle ne fournit pas une preuve sur les effets d'une commande
qui serait envoyée par un autre outil. Les recherches de clarification R094–R097
sont documentaires : aucune commande au véhicule.
