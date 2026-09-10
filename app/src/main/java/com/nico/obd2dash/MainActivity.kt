package com.nico.obd2dash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import com.nico.obd2dash.ui.DashboardScreen
import com.nico.obd2dash.ui.DtcScreen
import com.nico.obd2dash.ui.ProbeScreen
import java.io.File

private enum class Screen { DASHBOARD, DTC, PROBE }

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    val state by viewModel.state.collectAsState()

                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.DASHBOARD,
                                    onClick = { screen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    label = { Text("Dashboard") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.DTC,
                                    onClick = { screen = Screen.DTC },
                                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                                    label = { Text("DTC") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.PROBE,
                                    onClick = { screen = Screen.PROBE },
                                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                                    label = { Text("Sondage") }
                                )
                            }
                        }
                    ) { padding ->
                        when (screen) {
                            Screen.DASHBOARD -> DashboardScreen(
                                state = state,
                                onConnect = { host, port -> viewModel.connect(host, port) },
                                onDisconnect = { viewModel.disconnect() },
                                onHostChange = { viewModel.updateHost(it) },
                                onPortChange = { viewModel.updatePort(it) },
                                onToggleRecording = {
                                    if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording()
                                },
                                onShareRecording = { path -> shareCsvFile(path, "Partager l'enregistrement") },
                                modifier = Modifier.padding(padding)
                            )
                            Screen.DTC -> DtcScreen(
                                state = state,
                                onRefresh = { viewModel.refreshDtcs() },
                                onProbeHeaders = { viewModel.probeHeaderFormat() },
                                modifier = Modifier.padding(padding)
                            )
                            Screen.PROBE -> ProbeScreen(
                                state = state,
                                onStartScan = { startDid, endDid -> viewModel.startFapScan(startDid, endDid) },
                                onStopScan = { viewModel.stopFapScan() },
                                onShareProbe = { path -> shareCsvFile(path, "Partager le sondage") },
                                modifier = Modifier.padding(padding)
                            )
                        }
                    }
                }
            }
        }
    }

    /** Partage Android standard (sharesheet) : identique pour un enregistrement ou un sondage. */
    private fun shareCsvFile(path: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }
}
