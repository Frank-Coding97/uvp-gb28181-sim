package com.uvp.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uvp.sim.camera.AndroidCameraStreamer
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    private lateinit var cameraCapture: CameraCapture

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) attachStreamer()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraCapture = CameraCapture(viewModel.newCaptureConfig())
        viewModel.bindCamera(cameraCapture)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            attachStreamer()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val sipState by viewModel.state.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val uiState = AppUiState(sip = sipState, config = config, events = events)
            val actions = object : AppActions {
                override fun onConnect() = viewModel.connect()
                override fun onDisconnect() = viewModel.disconnect()
                override fun onSnapshot() = viewModel.reportSnapshot()
                override fun onConfigSave(updated: com.uvp.sim.config.SimConfig) =
                    viewModel.updateConfig(updated)
            }
            App(state = uiState, actions = actions)
        }
    }

    private fun attachStreamer() {
        val streamer = AndroidCameraStreamer(
            context = applicationContext,
            lifecycleOwner = this,
            mainExecutor = mainExecutor,
            config = viewModel.newCaptureConfig()
        )
        cameraCapture.setStreamer(streamer)
    }
}
