package com.uvp.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uvp.sim.camera.AndroidAudioStreamer
import com.uvp.sim.camera.AndroidCameraStreamer
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.ui.App
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.CameraPreviewBinder

class MainActivity : ComponentActivity() {

    private val viewModel: SipViewModel by viewModels()

    private lateinit var cameraCapture: CameraCapture
    private lateinit var audioCapture: AudioCapture
    private var streamer: AndroidCameraStreamer? = null
    private var audioStreamer: AndroidAudioStreamer? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) attachStreamer()
            if (result[Manifest.permission.RECORD_AUDIO] == true) attachAudioStreamer()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraCapture = CameraCapture(viewModel.newCaptureConfig())
        audioCapture = AudioCapture(viewModel.newAudioCaptureConfig())
        viewModel.bindCamera(cameraCapture)
        viewModel.bindAudio(audioCapture)

        val needs = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) attachStreamer() else needs += Manifest.permission.CAMERA

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) attachAudioStreamer() else needs += Manifest.permission.RECORD_AUDIO

        if (needs.isNotEmpty()) requestPermissions.launch(needs.toTypedArray())

        setContent {
            val sipState by viewModel.state.collectAsStateWithLifecycle()
            val events by viewModel.events.collectAsStateWithLifecycle()
            val config by viewModel.config.collectAsStateWithLifecycle()
            val videoVersion by viewModel.videoConfigVersion.collectAsStateWithLifecycle()
            val uiState = AppUiState(sip = sipState, config = config, events = events)
            val actions = object : AppActions {
                override fun onConnect() = viewModel.connect()
                override fun onCancelConnect() = viewModel.cancelConnect()
                override fun onDisconnect() = viewModel.disconnect()
                override fun onSnapshot() = viewModel.reportSnapshot()
                override fun onConfigSave(updated: com.uvp.sim.config.SimConfig) =
                    viewModel.updateConfig(updated)
            }
            // Rebuild encoder/streamer whenever video profile bumps.
            LaunchedEffect(videoVersion) {
                if (videoVersion > 0) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) attachStreamer()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) attachAudioStreamer()
                }
            }
            App(state = uiState, actions = actions)
        }
    }

    override fun onDestroy() {
        CameraPreviewBinder.setBinder(null)
        super.onDestroy()
    }

    private fun attachStreamer() {
        streamer?.let { old ->
            runCatching { old.detachPreviewView() }
            kotlinx.coroutines.runBlocking { runCatching { old.stop() } }
        }
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

    private fun attachAudioStreamer() {
        audioStreamer?.let { old ->
            kotlinx.coroutines.runBlocking { runCatching { old.stop() } }
        }
        val s = AndroidAudioStreamer(viewModel.newAudioCaptureConfig())
        audioStreamer = s
        audioCapture.setStreamer(s)
    }
}
