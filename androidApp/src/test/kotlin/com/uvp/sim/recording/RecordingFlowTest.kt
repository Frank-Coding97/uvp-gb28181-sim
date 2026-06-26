package com.uvp.sim.recording

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.uvp.sim.SipViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-TEST-1 T2 — 录像启停链路集成测试。
 *
 * 验证目标(SipViewModel.bindRecordingService 接线):
 *   1. bindRecordingService 后,svc.state 推送会同步到 vm.recordingState
 *   2. startRecording / stopRecording 委派到 service.start / service.stop
 *   3. delete 路径 emit toast(此处只验证 service.delete 被调,toast 走 SharedFlow 不抓)
 *   4. 失败 reason → state.Failed → vm.recordingState 同步
 *
 * 这里不跑真 CameraX(MediaRecorder + Surface 在 Robolectric 不可用),用 FakeRecordingService 替身,
 * 验证 ViewModel ↔ Service 的状态机契约,不验证 AndroidRecordingService 内部 finalize 逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordingFlowTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun bindRecordingService_propagates_state_changes() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()

        vm.bindRecordingService(fake)

        // 初始 Idle
        assertEquals(RecordingState.Idle, vm.recordingState.value)

        // service emit Recording → vm 同步
        fake.state.value = RecordingState.Recording(
            startMs = 1000L, segmentIndex = 0, source = RecordSource.Manual
        )
        assertTrue(
            "vm should be in Recording after fake emit",
            vm.recordingState.value is RecordingState.Recording
        )

        // service emit Idle 回 → vm 同步
        fake.state.value = RecordingState.Idle
        assertEquals(RecordingState.Idle, vm.recordingState.value)
    }

    @Test
    fun startRecording_invokes_service_start() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()
        vm.bindRecordingService(fake)

        vm.startRecording()
        assertEquals(1, fake.startCalls)
        // channelId 由 vm.config 注入(默认空字符串 — 不验证 channelId 具体内容,只验证转发触发)
    }

    @Test
    fun stopRecording_invokes_service_stop() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()
        vm.bindRecordingService(fake)

        vm.stopRecording()
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun deleteRecording_invokes_service_delete() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()
        vm.bindRecordingService(fake)

        vm.deleteRecording("rec-id-1")
        assertEquals(listOf("rec-id-1"), fake.deleteIds)
    }

    @Test
    fun failed_state_propagates_to_vm() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()
        vm.bindRecordingService(fake)

        fake.state.value = RecordingState.Failed("camera unavailable")

        val state = vm.recordingState.value
        assertTrue(
            "expected Failed, got $state",
            state is RecordingState.Failed
        )
        assertEquals("camera unavailable", (state as RecordingState.Failed).reason)
    }

    @Test
    fun files_state_propagates_to_vm() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())
        val fake = FakeRecordingService()
        vm.bindRecordingService(fake)

        val sample = RecordingFile(
            id = "f1",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            channelId = "ch1",
            filePath = "/tmp/f1.mp4",
            sizeBytes = 1024L,
            source = RecordSource.Manual,
        )
        fake.files.value = listOf(sample)

        assertEquals(listOf(sample), vm.recordingFiles.value)
    }
}

private class FakeRecordingService : RecordingService {
    override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val files = MutableStateFlow<List<RecordingFile>>(emptyList())

    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    val deleteIds = mutableListOf<String>()

    override suspend fun start(source: RecordSource, channelId: String): Result<Unit> {
        startCalls += 1
        return Result.success(Unit)
    }

    override suspend fun stop(): Result<RecordingFile?> {
        stopCalls += 1
        return Result.success(null)
    }

    override suspend fun load() = Unit

    override suspend fun delete(id: String): Result<Unit> {
        deleteIds += id
        return Result.success(Unit)
    }
}
