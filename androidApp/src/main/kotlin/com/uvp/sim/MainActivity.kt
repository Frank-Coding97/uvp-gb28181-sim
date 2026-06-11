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

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    /**
     * Single CameraCapture instance — shared with the ViewModel/engine.
     * We attach an AndroidCameraStreamer to it once camera permission is
     * granted. Until then start() returns an empty Flow.
     */
    private lateinit var cameraCapture: CameraCapture

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) attachStreamer()
            // Otherwise the camera stays mute; the engine will still reply 200
            // OK to INVITE but no RTP frames will go out.
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
