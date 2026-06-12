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
import com.uvp.sim.ui.CameraPreviewBinder

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    private lateinit var cameraCapture: CameraCapture
    private var streamer: AndroidCameraStreamer? = null

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
                override fun onCancelConnect() = viewModel.cancelConnect()
                override fun onDisconnect() = viewModel.disconnect()
                override fun onSnapshot() = viewModel.reportSnapshot()
                override fun onConfigSave(updated: com.uvp.sim.config.SimConfig) =
                    viewModel.updateConfig(updated)
            }
            App(state = uiState, actions = actions)
        }
    }

    override fun onDestroy() {
        CameraPreviewBinder.setBinder(null)
        super.onDestroy()
    }

    private fun attachStreamer() {
        val s = AndroidCameraStreamer(
            context = applicationContext,
            lifecycleOwner = this,
            mainExecutor = mainExecutor,
            config = viewModel.newCaptureConfig()
        )
        streamer = s
        cameraCapture.setStreamer(s)
        CameraPreviewBinder.setBinder { view ->
            if (view != null) s.attachPreviewView(view) else s.detachPreviewView()
        }
    }
}
