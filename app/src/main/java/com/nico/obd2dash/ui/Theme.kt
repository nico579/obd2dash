package com.nico.obd2dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Tableau de bord automobile, pas une appli grand public : fond anthracite mat plutôt que
// blanc/violet par défaut de Material3, aucun dégradé complexe ni texture "carbone"/métal
// brossé (gênent la lecture en conduisant), couleur réservée au SENS d'une valeur (bon/
// attention/anomalie) plutôt qu'une couleur par jauge sans signification (voir les usages
// de primary/tertiary/error dans les écrans, pas de nouvelle palette par PID ici).
//
// "Jour"/"nuit" ne peut pas suivre isSystemInDarkTheme() au sens habituel (clair vs sombre) :
// les deux variantes ci-dessous sont déjà sombres, seule leur intensité change (anthracite
// lisible de jour, quasi noir et très atténué de nuit pour ne pas éblouir). On fait
// correspondre système clair -> jour anthracite et système sombre -> nuit atténuée : la
// plupart des téléphones basculent déjà leur thème sombre au coucher du soleil (automatique
// ou programmé dans les réglages Android), ce qui donne un jour/nuit automatique sans ajouter
// de bouton ou d'horloge propre à cette appli.
//
// Palette revue une 2e fois sur retour direct (rendu réel jugé trop "halo visible au centre,
// écran qui semble vide" avec la 1re version) : fond légèrement bleuté et quasi uniforme,
// cartes/jauges à peine plus claires que le fond, contours discrets, turquoise réservé aux
// éléments actifs, orange/rouge réservés aux alertes (voir tertiary/error plus bas).
// secondaryContainer/onSecondaryContainer définis explicitement (au lieu de laisser Material3
// sur son violet par défaut) : c'est ce rôle que NavigationBarItem utilise pour le fond/texte
// de l'onglet sélectionné (voir MainActivity), donc le fixer ici suffit pour tout l'écran sans
// avoir à passer des couleurs au cas par cas à chaque NavigationBarItem.
//
// Revue une 3e fois après vérification en conditions réelles (retour direct : "en voiture,
// avec reflets/luminosité variable" les textes onSurfaceVariant et les contours de champs
// étaient trop faibles) : onBackground/onSurface et onSurfaceVariant identiques en jour ET en
// nuit (contrairement au reste de la palette, cf. commentaire sur NightColorScheme) - la
// lisibilité en conduite prime sur l'esthétique "nuit plus tamisée" pour le texte. outline
// renforcé pour les contours de champs, jugés "mous" visuellement.

private val TextPrimary = Color(0xFFE8EEF2)
private val TextSecondary = Color(0xFFA5B0BA)

private val DayColorScheme = darkColorScheme(
    background = Color(0xFF0B1015),
    onBackground = TextPrimary,
    surface = Color(0xFF111920),
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF172027),
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF31424F),
    outlineVariant = Color(0xFF31424F),
    primary = Color(0xFF2ED1C3),
    onPrimary = Color(0xFF04231F),
    secondary = Color(0xFF2ED1C3),
    onSecondary = Color(0xFF04231F),
    secondaryContainer = Color(0xFF1C5556),
    onSecondaryContainer = Color(0xFF2ED1C3),
    tertiary = Color(0xFFF5A623),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF3A2A05),
    onTertiaryContainer = Color(0xFFF5A623),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A0F11),
    onErrorContainer = Color(0xFFE5484D)
)

// Repli sur ~35-65% de la luminance du jour selon le rôle (le fond doit surtout perdre tout
// reflet, quitte à s'assombrir beaucoup plus que le texte/l'accent qui doivent rester
// lisibles/fonctionnels sans agresser un œil déjà adapté à l'obscurité de l'habitacle) :
// pas un facteur unique appliqué partout. Texte (onBackground/onSurface/onSurfaceVariant)
// exclu de cet assombrissement depuis la 3e revue (voir plus haut) : identique au jour.
private val NightColorScheme = darkColorScheme(
    background = Color(0xFF05080B),
    onBackground = TextPrimary,
    surface = Color(0xFF090D10),
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF0D1215),
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF1C2A33),
    outlineVariant = Color(0xFF1C2A33),
    primary = Color(0xFF1E887F),
    onPrimary = Color(0xFF021210),
    secondary = Color(0xFF1E887F),
    onSecondary = Color(0xFF021210),
    secondaryContainer = Color(0xFF113334),
    onSecondaryContainer = Color(0xFF1E887F),
    tertiary = Color(0xFFB97A1A),
    onTertiary = Color(0xFF1F1400),
    tertiaryContainer = Color(0xFF241800),
    onTertiaryContainer = Color(0xFFB97A1A),
    error = Color(0xFFA33236),
    onError = Color(0xFFE8E8E8),
    errorContainer = Color(0xFF260A0B),
    onErrorContainer = Color(0xFFA33236)
)

// Dégradé radial très atténué, 3e revue (retour direct sur le rendu réel : même après la 2e
// version, la forme circulaire restait perceptible - un dégradé radial reste par nature
// circulaire quel que soit son contraste, seul un rayon très supérieur à la distance
// centre-coin de l'écran rend sa portion visible presque plate). Rayon repoussé à 4200 (la
// diagonale visible depuis le centre d'un écran de téléphone dépasse rarement 1300px) et
// écart de couleur encore réduit d'environ 35% par rapport à la version précédente. Stop du
// milieu = exactement `background` : le dégradé s'écarte de cette teinte de base au lieu de
// s'en écarter dans les deux sens indépendamment.
private val DayBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF0E1419), Color(0xFF0B1015), Color(0xFF080C10)),
    radius = 4200f
)
private val NightBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF081018), Color(0xFF05080B), Color(0xFF030507)),
    radius = 4200f
)

// Fond dédié aux champs de saisie (OutlinedTextField) : à peine plus clair que `background`
// plutôt qu'un simple champ transparent posé dessus (retour direct : les champs IP/Port
// paraissaient "mous", sans définition propre), sans pour autant remonter jusqu'au ton des
// cartes/jauges (`surface`) qui doit rester réservé aux vraies cartes.
private val DayFieldContainer = Color(0xFF0D1319)
private val NightFieldContainer = Color(0xFF070B0F)

@Composable
fun fieldContainerColor(): Color = if (isSystemInDarkTheme()) NightFieldContainer else DayFieldContainer

// TopAppBar/NavigationBar n'ont pas de rôle dédié dans ColorScheme (Material3 les fait
// dériver de surface + une teinte d'élévation automatique, ce qui donnait un résultat non
// maîtrisé, ex. un bas de barre perçu ~#132C32 sans qu'aucune valeur pareille n'existe dans
// le code) : couleurs fixées explicitement ici plutôt que de dépendre de ce calcul.
private val DayTopBarContainer = Color(0xFF101820)
private val NightTopBarContainer = Color(0xFF080C10)
private val DayNavBarContainer = Color(0xFF10252A)
private val NightNavBarContainer = Color(0xFF091417)

@Composable
fun topBarContainerColor(): Color = if (isSystemInDarkTheme()) NightTopBarContainer else DayTopBarContainer

@Composable
fun navBarContainerColor(): Color = if (isSystemInDarkTheme()) NightNavBarContainer else DayNavBarContainer

@Composable
fun Obd2DashTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val colorScheme = if (night) NightColorScheme else DayColorScheme
    val backgroundBrush = if (night) NightBackgroundBrush else DayBackgroundBrush
    MaterialTheme(colorScheme = colorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(backgroundBrush)) {
            content()
        }
    }
}
