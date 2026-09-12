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

private val DayColorScheme = darkColorScheme(
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFF2F4F5),
    surface = Color(0xFF151B22),
    onSurface = Color(0xFFF2F4F5),
    surfaceVariant = Color(0xFF1B2229),
    onSurfaceVariant = Color(0xFF8E9AA6),
    outline = Color(0xFF26313C),
    outlineVariant = Color(0xFF26313C),
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF04231F),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF04231F),
    tertiary = Color(0xFFF5A623),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF3A2A05),
    onTertiaryContainer = Color(0xFFF5A623),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A0F11),
    onErrorContainer = Color(0xFFE5484D)
)

// Repli sur ~45-55% de la luminance du jour pour ce qui compte le plus (texte, accent),
// pas juste un facteur uniforme sur tout : le fond doit surtout perdre tout reflet, le
// texte doit rester lisible sans agresser un œil déjà adapté à l'obscurité de l'habitacle.
private val NightColorScheme = darkColorScheme(
    background = Color(0xFF050607),
    onBackground = Color(0xFFB8BEC2),
    surface = Color(0xFF0A0D10),
    onSurface = Color(0xFFB8BEC2),
    surfaceVariant = Color(0xFF0D1114),
    onSurfaceVariant = Color(0xFF5C6670),
    outline = Color(0xFF171D22),
    outlineVariant = Color(0xFF171D22),
    primary = Color(0xFF1B8F86),
    onPrimary = Color(0xFF02100D),
    secondary = Color(0xFF1B8F86),
    onSecondary = Color(0xFF02100D),
    tertiary = Color(0xFFB97A1A),
    onTertiary = Color(0xFF1F1400),
    tertiaryContainer = Color(0xFF241800),
    onTertiaryContainer = Color(0xFFB97A1A),
    error = Color(0xFFA33236),
    onError = Color(0xFFE8E8E8),
    errorContainer = Color(0xFF260A0B),
    onErrorContainer = Color(0xFFA33236)
)

// Dégradé radial léger (voir plus haut) : un fond plat uniforme rend l'écran "plat", ce
// dégradé donne une impression de profondeur type cadran sans que l'œil le remarque comme
// un effet à part entière. Rayon large (1.4x) pour que le bord le plus sombre reste hors
// champ sur la plupart des tailles d'écran plutôt que de former un cercle visible.
private val DayBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF141A20), Color(0xFF080B0E)),
    radius = 1400f
)
private val NightBackgroundBrush = Brush.radialGradient(
    colors = listOf(Color(0xFF0A0D10), Color(0xFF020304)),
    radius = 1400f
)

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
