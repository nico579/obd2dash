package com.nico.obd2dash.ui

import androidx.compose.ui.geometry.Rect
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.min

/** Répartition selon la surface réellement disponible, y compris en fenêtre partagée. */
internal data class DashboardGrid(val columns: Int, val rows: Int)

internal fun dashboardGrid(count: Int, width: Float, height: Float, gap: Float = 8f): DashboardGrid {
    if (count <= 0 || width <= 0f || height <= 0f) return DashboardGrid(1, 1)
    // Maximiser un diamètre COMMUN. Une rangée incomplète ne doit jamais agrandir
    // ses cadrans par rapport aux autres rangées.
    return (1..count).map { columns ->
        val rows = ceil(count.toDouble() / columns).toInt()
        val rowHeight = ((height - gap * (rows - 1)) / rows).coerceAtLeast(0f)
        val diameter = min((width - gap * (columns - 1)) / columns, rowHeight).coerceAtLeast(0f)
        GridCandidate(DashboardGrid(columns, rows), diameter, columns * rows - count)
    }.maxWithOrNull(compareBy<GridCandidate> { it.diameter }.thenBy { -it.emptyCells })!!.grid
}

private data class GridCandidate(val grid: DashboardGrid, val diameter: Float, val emptyCells: Int)

/** Carrés de même taille pour le dessin et le geste ; la dernière rangée est centrée. */
internal fun dashboardSlots(count: Int, width: Float, height: Float, gap: Float = 8f): List<Rect> {
    if (count <= 0 || width <= 0f || height <= 0f) return emptyList()
    val grid = dashboardGrid(count, width, height, gap)
    val rowHeight = ((height - gap * (grid.rows - 1)) / grid.rows).coerceAtLeast(0f)
    val cellWidth = ((width - gap * (grid.columns - 1)) / grid.columns).coerceAtLeast(0f)
    val diameter = min(cellWidth, rowHeight)
    return (0 until count).map { index ->
        val row = index / grid.columns
        val column = index % grid.columns
        val rowCount = min(grid.columns, count - row * grid.columns)
        val rowWidth = rowCount * cellWidth + gap * (rowCount - 1)
        val left = (width - rowWidth) / 2 + column * (cellWidth + gap) + (cellWidth - diameter) / 2
        val top = row * (rowHeight + gap) + (rowHeight - diameter) / 2
        Rect(left, top, left + diameter, top + diameter)
    }
}

/** Déplacement dans l'ordre de lecture (gauche à droite, puis rangée suivante). */
internal fun moveDashboardGauge(pids: List<Int>, from: Int, to: Int): List<Int> {
    if (from !in pids.indices || to !in pids.indices || from == to) return pids
    return pids.toMutableList().apply { add(to, removeAt(from)) }
}

/** Sépare l'affichage déjà décodé, sans créer un second décodeur OBD. */
internal data class DialReading(val number: String, val detail: String, val value: Double?)

private val readingPattern = Regex("""^(-?\d+(?:[.,]\d+)?)(.*)$""")
private val decimalDisplayPattern = Regex("""-?\d+[.,]\d+""")

/** Arrondi visuel uniquement ; le cache, l'aiguille, les courbes et le CSV restent précis. */
internal fun integerDialText(text: String): String = decimalDisplayPattern.replace(text) { match ->
    BigDecimal(match.value.replace(',', '.')).setScale(0, RoundingMode.HALF_UP).toPlainString()
}

internal fun dialReading(text: String): DialReading {
    val match = readingPattern.matchEntire(text.trim()) ?: return DialReading(text, "", null)
    return DialReading(
        number = match.groupValues[1],
        detail = match.groupValues[2].trim().replace("rpm", "tr/min"),
        value = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    )
}

/** Échelle d'affichage uniquement : les extrémités ne sont pas des seuils d'alerte. */
internal data class DialScale(
    val min: Double,
    val max: Double,
    val divisions: Int = 5,
    val divisor: Double = 1.0,
    val annotation: String = ""
) {
    fun fraction(value: Double): Float = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()

    // Une mesure hors plage reste affichée exactement et l'échelle s'élargit pour
    // l'inclure. Aucune valeur 24 V, haut régime ou haute pression n'est plafonnée.
    fun including(value: Double?): DialScale {
        if (value == null || !value.isFinite() || value in min..max) return this
        val step = (max - min) / divisions
        return copy(
            min = if (value < min) min - ceil((min - value) / step) * step else min,
            max = if (value > max) max + ceil((value - max) / step) * step else max
        )
    }
}

internal fun dialScale(pid: Int): DialScale? = when (pid) {
    0x0C -> DialScale(0.0, 8_000.0, 8, 1_000.0, "×1000")
    0x0D -> DialScale(0.0, 240.0, 6)
    0x05 -> DialScale(-40.0, 160.0)
    0x5C -> DialScale(-40.0, 200.0, 6)
    0x0F, 0x46 -> DialScale(-40.0, 80.0, 6)
    0x42 -> DialScale(0.0, 20.0)
    0x04, 0x11, 0x2F, 0x52, 0x2C, 0x2E, 0x45, 0x49, 0x4A, 0x4C, 0x5A -> DialScale(0.0, 100.0)
    0x06, 0x07, 0x08, 0x09, 0x2D -> DialScale(-100.0, 100.0, 4)
    0x0B -> DialScale(0.0, 300.0, 6)
    0x33 -> DialScale(0.0, 150.0)
    0x10 -> DialScale(0.0, 200.0)
    0x50 -> DialScale(0.0, 1_000.0)
    0x0A -> DialScale(0.0, 900.0, 6)
    0x22 -> DialScale(0.0, 6_000.0, 6, 1_000.0, "×1000")
    0x23 -> DialScale(0.0, 200_000.0, 5, 1_000.0, "×1000")
    0x5E -> DialScale(0.0, 50.0)
    0x14, 0x15, 0x16, 0x17 -> DialScale(0.0, 1.5)
    0x24 -> DialScale(0.0, 2.0)
    0x0E -> DialScale(-30.0, 60.0, 6)
    0x32 -> DialScale(-8_000.0, 8_000.0, 4, 1_000.0, "×1000")
    // Compteurs et contexte composite : un nombre lisible, sans inventer une
    // limite de compteur ou transformer « ratio / tension » en une seule mesure.
    else -> null
}

internal fun dialLabel(pid: Int, fallback: String): String = when (pid) {
    0x0C -> "RÉGIME MOTEUR"
    0x0D -> "VITESSE"
    0x05 -> "TEMP. MOTEUR"
    0x04 -> "CHARGE MOTEUR"
    0x10 -> "DÉBIT D’AIR"
    0x42 -> "TENSION ECU"
    0x0F -> "TEMP. ADMISSION"
    0x0B -> "PRESSION ADM."
    0x11 -> "PAPILLON"
    0x2F -> "CARBURANT"
    0x46 -> "TEMP. EXTÉRIEURE"
    0x1F -> "TEMPS MOTEUR"
    0x06 -> "CORRECTION CT B1"
    0x07 -> "CORRECTION LT B1"
    0x08 -> "CORRECTION CT B2"
    0x09 -> "CORRECTION LT B2"
    0x0A -> "PRESSION CARB."
    0x52 -> "ÉTHANOL"
    0x5E -> "DÉBIT CARBURANT"
    0x14 -> "O2 B1 / SONDE 1"
    0x15 -> "O2 B1 / SONDE 2"
    0x16 -> "O2 / POSITION 3"
    0x17 -> "O2 / POSITION 4"
    0x24 -> "O2 LARGE BANDE"
    0x4F -> "MAX. RATIO / O2"
    0x50 -> "MAX. DÉBIT D’AIR"
    0x0E -> "AVANCE ALLUMAGE"
    0x22, 0x23 -> "PRESSION RAIL"
    0x2C -> "EGR COMMANDÉE"
    0x2D -> "ERREUR EGR"
    0x2E -> "PURGE EVAP"
    0x32 -> "PRESSION EVAP"
    0x21 -> "DISTANCE / MIL"
    0x30 -> "CYCLES / EFFAC."
    0x31 -> "DISTANCE / EFFAC."
    0x33 -> "PRESSION ATM."
    0x5C -> "TEMP. HUILE"
    0x45 -> "PAPILLON RELATIF"
    0x49 -> "PÉDALE D"
    0x4A -> "PÉDALE E"
    0x4C -> "COMMANDE PAPILLON"
    0x5A -> "PÉDALE RELATIVE"
    else -> fallback
}
