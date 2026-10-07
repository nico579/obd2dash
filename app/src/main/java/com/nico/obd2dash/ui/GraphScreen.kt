package com.nico.obd2dash.ui

import android.os.SystemClock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.GraphPoint
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R
import com.nico.obd2dash.VALUE_UNAVAILABLE_AFTER_MS
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Courbe en direct d'un paramètre choisi (voir ObdViewModel.selectGraphPid/GraphPoint).
 * Fenêtre glissante des derniers points (GRAPH_HISTORY_MAX_POINTS), pas un historique de
 * session complet : pour ça, l'enregistrement CSV existe déjà et garde tout sur disque.
 * Changer de paramètre repart d'une courbe vide.
 */
@Composable
fun GraphScreen(
    state: ObdUiState,
    onSelectPid: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.connectionState != ConnectionState.CONNECTED) {
        // Le ViewModel conserve la sélection et les points pour la reprise.
        ConnectionWaitingScreen(state.connectionMode, modifier)
        return
    }
    // Même ticker que DashboardScreen (voir staleness) : sans lui, "Actuel" resterait figé
    // sur la valeur du dernier recomposition déclenché par autre chose que le temps, au
    // lieu de retomber sur "--" une fois VALUE_UNAVAILABLE_AFTER_MS dépassé (voir audit B5).
    var tickNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tickNowMs = SystemClock.elapsedRealtime()
        }
    }
    val nowMs = maxOf(tickNowMs, SystemClock.elapsedRealtime())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.graph_title), style = MaterialTheme.typography.headlineSmall)

        // CONTEXT_ONLY_PIDS (PID4F/PID50) exclus : jamais repollés après la connexion (voir
        // ObdViewModel.startPolling), leur sélection ici restait bloquée sur "Collecte des
        // données..." pour toujours, un seul point ne suffisant jamais à tracer une courbe
        // (voir audit B5, P3 associé).
        val options = PidCatalog.defs.filter { it.pid in state.supportedPids && it.pid !in PidCatalog.CONTEXT_ONLY_PIDS }

        if (options.isEmpty()) {
            Text(
                stringResource(R.string.graph_no_standard_measurements),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val selectedDef = options.firstOrNull { it.pid == state.graphPid }

            PidPicker(
                options = options,
                selectedLabel = selectedDef?.label,
                onSelect = { onSelectPid(it) }
            )

            when {
                selectedDef == null -> Text(
                    stringResource(R.string.graph_choose_param),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.graphHistory.size < 2 -> Text(
                    stringResource(R.string.graph_collecting),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    LineChart(
                        points = state.graphHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                    // Même seuil de péremption que les jauges du Dashboard (voir B5) : sans
                    // lui, une valeur figée depuis une coupure ou une pause diagnostique
                    // s'affichait "Actuel" indéfiniment, alors que le Dashboard masquait déjà
                    // cette même valeur après VALUE_UNAVAILABLE_AFTER_MS.
                    val (text, _) = staleness(selectedDef.pid, state.values[selectedDef.pid], nowMs)
                    Text(stringResource(R.string.graph_current_value, text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PidPicker(options: List<PidCatalog.Def>, selectedLabel: String?, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel ?: stringResource(R.string.graph_picker_placeholder),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.graph_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (def in options) {
                DropdownMenuItem(
                    text = { Text(def.label) },
                    onClick = {
                        onSelect(def.pid)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Couleur unique, choisie pour ne pas se confondre avec le vert/ambre/rouge déjà réservés
// à l'indicateur de connexion (MainActivity.ConnectionIndicator) : une seule série ici,
// pas besoin d'une palette catégorielle, juste un trait net et lisible sur fond clair.
private val CHART_LINE_COLOR = Color(0xFF1E88E5)

@Composable
private fun LineChart(points: List<GraphPoint>, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val minValue = points.minOf { it.value }
    val maxValue = points.maxOf { it.value }
    val valueRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val minTime = points.first().atElapsedMs
    val timeRange = (points.last().atElapsedMs - minTime).takeIf { it > 0L } ?: 1L

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)) {
            // Repères de plafond/plancher discrets : suffisent à lire l'échelle sans une
            // grille dense qui alourdirait une simple tendance en direct.
            drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = (point.atElapsedMs - minTime).toFloat() / timeRange.toFloat() * size.width
                val yFraction = ((point.value - minValue) / valueRange).toFloat()
                val y = size.height - yFraction * size.height
                // Une coupure réelle (perte réseau, pause diagnostique prolongée) ne doit
                // pas se lire comme une transition continue entre deux valeurs sans rapport
                // (voir audit B5) : on relève le crayon plutôt que relier deux points de
                // part et d'autre d'un trou. Même seuil que la péremption des jauges.
                val gapBeforeThis = index > 0 && point.atElapsedMs - points[index - 1].atElapsedMs > VALUE_UNAVAILABLE_AFTER_MS
                if (index == 0 || gapBeforeThis) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = CHART_LINE_COLOR,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        Text(
            formatGraphValue(maxValue),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
        )
        Text(
            formatGraphValue(minValue),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
        )
    }
}

internal fun formatGraphValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(Locale.FRANCE, v)
