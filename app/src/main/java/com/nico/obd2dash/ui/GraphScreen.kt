package com.nico.obd2dash.ui

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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionState
import com.nico.obd2dash.GraphPoint
import com.nico.obd2dash.ObdUiState
import com.nico.obd2dash.PidCatalog

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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Graphique", style = MaterialTheme.typography.headlineSmall)

        // RECONNECTING inclus, pas seulement CONNECTED : une coupure transitoire ne doit
        // pas effacer la courbe déjà tracée ni forcer à recommencer (voir
        // ObdViewModel.handleConnectionLost, "le stop doit être manuel"). graphPid/
        // graphHistory/supportedPids survivent à une coupure par design (.copy()), la
        // courbe continue donc de s'afficher, en pause, jusqu'à la reprise du polling.
        if (state.connectionState != ConnectionState.CONNECTED && state.connectionState != ConnectionState.RECONNECTING) {
            Text(
                "Connecte-toi à la sonde depuis le Dashboard pour tracer une courbe.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val options = PidCatalog.defs.filter { it.pid in state.supportedPids }
            val selectedDef = options.firstOrNull { it.pid == state.graphPid }

            PidPicker(
                options = options,
                selectedLabel = selectedDef?.label,
                onSelect = { onSelectPid(it) }
            )

            if (state.connectionState == ConnectionState.RECONNECTING) {
                Text(
                    "Reconnexion en cours, la courbe reprendra automatiquement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                selectedDef == null -> Text(
                    "Choisis un paramètre ci-dessus pour voir sa courbe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.graphHistory.size < 2 -> Text(
                    "Collecte des données...",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    LineChart(
                        points = state.graphHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                    Text(
                        "Actuel : ${state.values[selectedDef.pid]?.text ?: "--"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
            value = selectedLabel ?: "Choisir un paramètre",
            onValueChange = {},
            readOnly = true,
            label = { Text("Paramètre") },
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
    val minTime = points.first().atMs
    val timeRange = (points.last().atMs - minTime).takeIf { it > 0L } ?: 1L

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)) {
            // Repères de plafond/plancher discrets : suffisent à lire l'échelle sans une
            // grille dense qui alourdirait une simple tendance en direct.
            drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            val path = Path()
            points.forEachIndexed { index, point ->
                val x = (point.atMs - minTime).toFloat() / timeRange.toFloat() * size.width
                val yFraction = ((point.value - minValue) / valueRange).toFloat()
                val y = size.height - yFraction * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
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

private fun formatGraphValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
