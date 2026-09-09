package com.nico.obd2dash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import com.nico.obd2dash.ui.DashboardScreen
import com.nico.obd2dash.ui.DtcScreen

private enum class Screen { DASHBOARD, DTC }

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
                                modifier = Modifier.padding(padding)
                            )
                            Screen.DTC -> DtcScreen(
                                state = state,
                                onRefresh = { viewModel.refreshDtcs() },
                                onProbeHeaders = { viewModel.probeHeaderFormat() },
                                modifier = Modifier.padding(padding)
                            )
                        }
                    }
                }
            }
        }
    }
}
