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

private val DayColorScheme = darkColorScheme(
    background = Color(0xFF0B1015),
    onBackground = Color(0xFFF2F4F5),
    surface = Color(0xFF111920),
    onSurface = Color(0xFFF2F4F5),
    surfaceVariant = Color(0xFF172027),
    onSurfaceVariant = Color(0xFF8E9AA6),
    outline = Color(0xFF26333D),
    outlineVariant = Color(0xFF26333D),
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
// pas un facteur unique appliqué partout.
private val NightColorScheme = darkColorScheme(
    background = Color(0xFF050708),
    onBackground = Color(0xFFB8BEC2),
    surface = Color(0xFF090D10),
    onSurface = Color(0xFFB8BEC2),
    surfaceVariant = Color(0xFF0D1215),
    onSurfaceVariant = Color(0xFF5C6670),
    outline = Color(0xFF171F25),
    outlineVariant = Color(0xFF171F25),
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

// Dégradé radial très atténué (retour direct : la 1re version formait un "cercle gris-noir"
// bien visible, donnant une impression d'écran vide plutôt que de tableau de bord). Deux
// corrections par rapport à avant : écart de couleur beaucoup plus faible entre le centre et
// le bord (contraste réduit d'environ 60%, cf. les 2 Color ci-dessous, très proches l'une de
// l'autre) et rayon bien plus grand que la diagonale d'un écran de téléphone (2800 vs 1400),
// pour que le dégradé reste "en cours" sur tout l'écran visible au lieu de se refermer sur
// lui-même en un anneau net avant d'atteindre les bords.
private val DayBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF10161B), Color(0xFF0B1015)),
    radius = 2800f
)
private val NightBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF070A0A), Color(0xFF050708)),
    radius = 2800f
)

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
