package com.nico.obd2dash.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nico.obd2dash.ConnectionMode
import com.nico.obd2dash.R

/** Attente commune aux mesures et aux courbes, sans intervenir sur la connexion. */
@Composable
internal fun ConnectionWaitingScreen(mode: ConnectionMode, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waitingConnection")
    val iconAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = keyframes {
            durationMillis = 1_200
            1f at 0
            1f at 590
            .15f at 600
            .15f at 1_190
            1f at 1_200
        }),
        label = "waitingTransportAlpha"
    )
    val (icon, description) = when (mode) {
        ConnectionMode.WIFI -> R.drawable.ic_wifi to R.string.connection_transport_wifi
        ConnectionMode.BLUETOOTH -> R.drawable.ic_bluetooth to R.string.connection_transport_bluetooth
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 180.dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .testTag("connection_waiting")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)
        ) {
            Icon(
                painterResource(icon), contentDescription = stringResource(description),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (compact) 48.dp else 80.dp).alpha(iconAlpha)
            )
            Text(
                stringResource(R.string.dashboard_waiting_connection),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
            )
        }
    }
}
