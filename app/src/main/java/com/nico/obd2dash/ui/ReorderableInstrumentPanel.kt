package com.nico.obd2dash.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nico.obd2dash.GaugeValue
import com.nico.obd2dash.PidCatalog
import com.nico.obd2dash.R
import kotlin.math.roundToInt

private data class GaugeDrag(val source: Int, val pointer: Offset, val displacement: Offset = Offset.Zero)

@Composable
internal fun InstrumentPanel(
    defs: List<PidCatalog.Def>,
    values: Map<Int, GaugeValue>,
    nowMs: Long,
    modifier: Modifier = Modifier,
    onReorder: (List<Int>) -> Unit = {}
) {
    if (defs.isEmpty()) return
    val onReorderCurrent by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val beforeLabel = stringResource(R.string.dashboard_move_before)
    val afterLabel = stringResource(R.string.dashboard_move_after)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val pids = remember(defs) { defs.map { it.pid } }
        val slots = remember(pids.size, maxWidth, maxHeight, density) {
            with(density) { dashboardSlots(pids.size, maxWidth.toPx(), maxHeight.toPx(), 8.dp.toPx()) }
        }
        if (slots.isEmpty()) return@BoxWithConstraints
        // Un changement de sélection/taille annule le geste ; les mises à jour des valeurs
        // n'interrompent pas le déplacement. Rien n'est persisté avant de relâcher.
        var drag by remember(pids, slots) { mutableStateOf<GaugeDrag?>(null) }
        val gestures = if (pids.size < 2) Modifier else Modifier.pointerInput(pids, slots) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val source = slots.indexOfFirst { it.contains(position) }
                    if (source >= 0) {
                        drag = GaugeDrag(source, position)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    drag?.let {
                        change.consume()
                        drag = it.copy(pointer = it.pointer + amount, displacement = it.displacement + amount)
                    }
                },
                onDragCancel = { drag = null },
                onDragEnd = {
                    drag?.let {
                        val target = slots.indexOfFirst { slot -> slot.contains(it.pointer) }
                        if (target >= 0 && target != it.source) {
                            onReorderCurrent(moveDashboardGauge(pids, it.source, target))
                        }
                    }
                    drag = null
                }
            )
        }
        val target = drag?.let { current -> slots.indexOfFirst { it.contains(current.pointer) } }
        val highlight = MaterialTheme.colorScheme.primary
        Box(Modifier.fillMaxSize().then(gestures).testTag("dashboard_gauges")) {
            for ((index, def) in defs.withIndex()) {
                key(def.pid) {
                    val slot = slots[index]
                    val lifted = drag?.source == index
                    val positionLabel = stringResource(R.string.dashboard_gauge_position, index + 1, pids.size)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(slot.left.roundToInt(), slot.top.roundToInt()) }
                            .size(with(density) { slot.width.toDp() }, with(density) { slot.height.toDp() })
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer {
                                if (lifted) {
                                    translationX = drag?.displacement?.x ?: 0f
                                    translationY = drag?.displacement?.y ?: 0f
                                    scaleX = 1.035f
                                    scaleY = 1.035f
                                }
                            }
                            .testTag("dashboard_gauge_${def.pid}")
                            .semantics(mergeDescendants = true) {
                                stateDescription = positionLabel
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(beforeLabel) {
                                        onReorderCurrent(moveDashboardGauge(pids, index, index - 1)); true
                                    })
                                    if (index < pids.lastIndex) add(CustomAccessibilityAction(afterLabel) {
                                        onReorderCurrent(moveDashboardGauge(pids, index, index + 1)); true
                                    })
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        InstrumentGauge(def, values[def.pid], nowMs, Modifier.fillMaxSize())
                        if (lifted || (target == index && target != drag?.source)) {
                            Box(
                                Modifier.size(with(density) { slot.size.minDimension.toDp() })
                                    .border(3.dp, highlight, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}
