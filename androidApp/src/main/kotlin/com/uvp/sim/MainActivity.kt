package com.uvp.sim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uvp.sim.ui.App

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            App(
                state = state,
                serverLabel = viewModel.serverLabel,
                deviceLabel = viewModel.deviceLabel,
                events = events,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect
            )
        }
    }
}
