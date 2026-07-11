package com.uvp.sim.camera

import com.uvp.sim.app.PlatformRuntimeIos
import com.uvp.sim.app.RecordingEncoderConfig
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.RecordingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

class IosOsdFlowWiringTest {

    @AfterTest
    fun resetControllerFlow() {
        IosCameraController.resetOsdConfigFlowForTest()
    }

    @Test
    fun controller_and_encoding_session_keep_the_installed_flow_reference() {
        val flow = MutableStateFlow(OsdConfig())

        IosCameraController.installOsdConfigFlow(flow)
        val session = EncodingSession(
            config = CaptureConfig(),
            osdConfigFlow = IosCameraController.osdConfigFlowForEncoding(),
            onFrame = { /* no-op */ },
        )

        assertSame(flow, IosCameraController.osdConfigFlowForTest())
        assertSame(flow, session.osdConfigFlow)
    }

    @Test
    fun runtime_installs_same_flow_when_camera_is_built_first() = runTest {
        val runtime = PlatformRuntimeIos()
        val flow = MutableStateFlow(OsdConfig())
        try {
            runtime.buildCameraCapture(CaptureConfig())
            runtime.buildRecordingServiceForTest(flow, this)
            assertSame(flow, IosCameraController.osdConfigFlowForTest())
        } finally {
            runtime.release()
        }
    }

    @Test
    fun runtime_installs_same_flow_when_recording_is_built_first() = runTest {
        val runtime = PlatformRuntimeIos()
        val flow = MutableStateFlow(OsdConfig())
        try {
            runtime.buildRecordingServiceForTest(flow, this)
            runtime.buildCameraCapture(CaptureConfig())
            assertSame(flow, IosCameraController.osdConfigFlowForTest())
        } finally {
            runtime.release()
        }
    }

    private fun PlatformRuntimeIos.buildRecordingServiceForTest(
        flow: MutableStateFlow<OsdConfig>,
        scope: TestScope,
    ) {
        buildRecordingService(
            scope = scope,
            deviceIdSupplier = { "34020000001320000001" },
            encoderConfigSupplier = {
                RecordingEncoderConfig(
                    widthPx = 1280,
                    heightPx = 720,
                    frameRate = 25,
                    bitrateBps = 2_000_000,
                    keyframeIntervalSeconds = 1,
                )
            },
            osdConfigSupplier = { flow },
            profileSupplier = { RecordingProfile() },
        )
    }
}
