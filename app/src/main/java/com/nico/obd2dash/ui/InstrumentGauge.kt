package com.nico.obd2dash.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import com.nico.obd2dash.GaugeValue
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Cadran dessiné à sa taille réelle : textes et traits restent nets dans les deux orientations. */
@Composable
internal fun InstrumentGauge(def: PidCatalog.Def, value: GaugeValue?, nowMs: Long, modifier: Modifier = Modifier) {
    val (text, stale) = staleness(def.pid, value, nowMs)
    val unavailable = text == "--"
    val reading = remember(text) { dialReading(text) }
    val displayText = remember(text) { integerDialText(text) }
    val displayReading = remember(displayText) { dialReading(displayText) }
    val scale = remember(def.pid, reading.value) { dialScale(def.pid)?.including(reading.value) }
    val tickLabels = remember(scale) {
        scale?.let { s ->
            (0..s.divisions).map { tick ->
                val number = (s.min + (s.max - s.min) * tick / s.divisions) / s.divisor
                if (kotlin.math.abs(number - number.toInt()) < .001) number.toInt().toString()
                else "%.1f".format(Locale.FRANCE, number)
            }
        }.orEmpty()
    }
    val fraction by animateFloatAsState(
        targetValue = if (reading.value != null && scale != null) scale.fraction(reading.value) else 0f,
        animationSpec = tween(180), label = "dialNeedle"
    )
    val colors = MaterialTheme.colorScheme
    val textColor = if (stale || unavailable) colors.onSurfaceVariant else colors.onSurface
    val instrumentRed = Color(0xFFF04444)
    val valueColor = if (stale || unavailable) colors.onSurfaceVariant else instrumentRed
    val arcColor = if (stale) colors.onSurfaceVariant else colors.primary
    val needleColor = instrumentRed.copy(alpha = if (stale) .5f else .78f)
    val status = when {
        unavailable -> stringResource(R.string.dashboard_gauge_unavailable)
        stale -> stringResource(R.string.dashboard_gauge_stale)
        else -> ""
    }
    val description = listOf(def.label, if (unavailable) status else displayText, if (stale) status else "")
        .filter { it.isNotEmpty() }.joinToString(", ")
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val label = dialLabel(def.pid, def.label)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val textBounds = remember { Rect() }
    val valueBounds = remember { RectF() }
    val regularTypeface = remember { Typeface.create("sans-serif", Typeface.NORMAL) }
    val boldTypeface = remember { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    val context = LocalContext.current
    val segmentTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.dseg7_modern_bold) ?: boldTypeface
    }
    val tickTypeface = remember { Typeface.create("sans-serif-condensed", Typeface.NORMAL) }
    val rimBrush = remember {
        Brush.linearGradient(listOf(Color(0xFFCBD2D6), Color(0xFF52616C), Color(0xFFB7C0C5)))
    }
    val needle = remember { Path() }

    BoxWithConstraints(modifier = modifier.semantics { contentDescription = description }) {
        val diameter = min(maxWidth.value, maxHeight.value).coerceAtLeast(0f)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(androidx.compose.ui.unit.Dp(diameter))) {
                val d = size.minDimension
                if (d <= 0f) return@Canvas
                val c = center
                val faceColor = colors.surface
                drawCircle(
                    brush = rimBrush,
                    radius = d * .49f
                )
                drawCircle(color = colors.background, radius = d * .474f)
                drawCircle(color = faceColor, radius = d * .459f)
                drawCircle(color = colors.onSurfaceVariant.copy(alpha = .4f), radius = d * .45f, style = Stroke(d * .003f))

                fun polar(radius: Float, angle: Float): Offset {
                    val radians = Math.toRadians(angle.toDouble())
                    return Offset(c.x + cos(radians).toFloat() * radius, c.y + sin(radians).toFloat() * radius)
                }

                fun fitText(text: String, nominalSize: Float, maxWidth: Float, typeface: Typeface, maxHeight: Float) {
                    paint.typeface = typeface
                    paint.textSize = nominalSize
                    val measured = paint.measureText(text)
                    if (measured > maxWidth) paint.textSize *= maxWidth / measured
                    // Adapter aussi la hauteur réelle des glyphes : une grande taille
                    // système ne doit pas superposer le nombre, son libellé et son unité.
                    paint.getTextBounds(text, 0, text.length, textBounds)
                    if (textBounds.height() > maxHeight) {
                        paint.textSize *= maxHeight / textBounds.height()
                        paint.getTextBounds(text, 0, text.length, textBounds)
                    }
                }

                fun drawCentered(
                    text: String, y: Float, nominalSize: Float, maxWidth: Float, color: Color,
                    typeface: Typeface = regularTypeface, maxHeight: Float = d * .065f
                ) {
                    if (text.isEmpty()) return
                    fitText(text, nominalSize, maxWidth, typeface, maxHeight)
                    paint.color = color.toArgb()
                    val baseline = y - (textBounds.top + textBounds.bottom) / 2f
                    drawContext.canvas.nativeCanvas.drawText(text, c.x, baseline, paint)
                }

                val number = if (unavailable) "—" else if (def.pid == 0x4F) displayText else displayReading.number
                val detail = if (def.pid == 0x4F || unavailable) "" else displayReading.detail
                // La police segmentée couvre les entiers signés. Les valeurs composites
                // et le tiret d'indisponibilité gardent tous leurs caractères lisibles.
                val numberTypeface = if (number.all { it in '0'..'9' || it == '-' }) segmentTypeface else boldTypeface
                val valueY = d * .76f
                val valueSize = d * .27f * fontScale
                val valueWidth = d * .56f
                val valueHeight = d * .19f
                fitText(number, valueSize, valueWidth, numberTypeface, valueHeight)
                val halfWidth = paint.measureText(number) / 2f
                val halfHeight = textBounds.height() / 2f
                valueBounds.set(c.x - halfWidth, valueY - halfHeight, c.x + halfWidth, valueY + halfHeight)
                valueBounds.inset(-d * .012f, -d * .012f)

                if (scale != null) {
                    val tickCount = scale.divisions * 4
                    drawArc(
                        color = colors.onSurfaceVariant.copy(alpha = .22f), startAngle = 135f, sweepAngle = 270f,
                        useCenter = false, topLeft = Offset(d * .076f, d * .076f), size = Size(d * .848f, d * .848f),
                        style = Stroke(width = d * .022f, cap = StrokeCap.Round)
                    )
                    if (!unavailable && reading.value != null) {
                        drawArc(
                            color = arcColor.copy(alpha = if (stale) .35f else .85f), startAngle = 135f,
                            sweepAngle = 270f * fraction, useCenter = false,
                            topLeft = Offset(d * .076f, d * .076f), size = Size(d * .848f, d * .848f),
                            style = Stroke(width = d * .022f, cap = StrokeCap.Round)
                        )
                    }
                    for (tick in 0..tickCount) {
                        val major = tick % 4 == 0
                        val angle = 135f + 270f * tick / tickCount
                        drawLine(
                            color = colors.onSurface.copy(alpha = if (major) .95f else .5f),
                            start = polar(d * if (major) .37f else .394f, angle), end = polar(d * .418f, angle),
                            strokeWidth = d * if (major) .008f else .004f
                        )
                        if (major) {
                            val numberText = tickLabels[tick / 4]
                            val position = polar(d * .318f, angle)
                            paint.typeface = tickTypeface
                            paint.color = colors.onSurface.toArgb()
                            paint.textSize = d * .057f * fontScale.coerceAtMost(1.2f)
                            val baseline = position.y - (paint.ascent() + paint.descent()) / 2
                            val left = position.x - paint.measureText(numberText) / 2f
                            paint.getTextBounds(numberText, 0, numberText.length, textBounds)
                            // Garder les traits de graduation, mais réserver les chiffres
                            // de la valeur : un libellé entier est omis s'il les chevauche.
                            if (!valueBounds.intersects(left + textBounds.left, baseline + textBounds.top,
                                    left + textBounds.right, baseline + textBounds.bottom)) {
                                drawContext.canvas.nativeCanvas.drawText(numberText, position.x, baseline, paint)
                            }
                        }
                    }
                }
                drawCentered(label, d * .395f, d * .061f * fontScale, d * .55f, colors.onSurface, typeface = boldTypeface, maxHeight = d * .055f)
                // Affichage entier dans la partie basse du cadran, avec une marge
                // pour agrandir les chiffres et séparer leur unité du cercle.
                // L'aiguille utilise toujours la mesure précise, les composites gardent
                // leurs unités et leur seconde composante arrondie pour l'affichage.
                drawCentered(number, valueY, valueSize, valueWidth, valueColor, typeface = numberTypeface, maxHeight = valueHeight)
                drawCentered(detail, d * .90f, d * .065f * fontScale, d * .33f, textColor, maxHeight = d * .05f)
                val footer = if (status.isNotEmpty()) status else scale?.annotation.orEmpty()
                drawCentered(footer, d * .61f, d * .052f * fontScale, d * .53f, colors.onSurfaceVariant)
                if (scale != null && !unavailable && reading.value != null) {
                    // Dessin après les textes : l'aiguille part du centre et passe
                    // devant les chiffres, avec une légère transparence.
                    val angle = 135f + fraction * 270f
                    val tip = polar(d * .42f, angle)
                    val sideways = polar(d * .017f, angle + 90) - c
                    needle.reset()
                    needle.moveTo(tip.x, tip.y)
                    needle.lineTo(c.x + sideways.x, c.y + sideways.y)
                    needle.lineTo(c.x - sideways.x, c.y - sideways.y)
                    needle.close()
                    drawPath(needle, needleColor)
                    drawCircle(needleColor, radius = d * .020f, center = c)
                }
            }
        }
    }
}
