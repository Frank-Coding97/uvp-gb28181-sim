package com.uvp.sim.app

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmMethod
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.gb28181.VideoAlarmRecordState
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.recording.RecordSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A.2.1.16 报警录像的**设备侧行为**契约（2026-11-05）。
 *
 * 这里测的是"报警来时设备到底做了什么"，不是 UI：
 *  1. `RecordEnable=0`（出厂默认）⇒ 什么都不做，连标记都不打；
 *  2. 没在录 + 已启用 ⇒ 起一段 `RecordSource.Alarm` 录像；
 *  3. 正在录 + 已启用 ⇒ **不打断**，只打标记（"录像不停、事后标记"策略）。
 *
 * ⛔ 第 3 条是本次最容易回归的点：早期实现里"报警录像"等于"报警就另起一段"，
 * 一旦恢复成那样，计划录像会被切碎而**所有单元测试照样绿**（因为没人断言 start 次数）。
 */
class AppEngineAlarmRecordingTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = "35020000002000000001", domain = "3502000000",
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    private fun payload() = AlarmPayload(
        deviceId = "35020000001340000001",
        method = AlarmMethod.Video,
        type = AlarmType.DeviceTamper,
        description = "测试报警",
    )

    private fun newApp(
        scope: kotlinx.coroutines.CoroutineScope,
        svc: RecordingService,
    ): AppEngine {
        val runtime = FakePlatformRuntime().apply { recordingServiceOverride = svc }
        return AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(),
            parentScope = scope,
        ).also { it.ensureMediaBuilt(config()) }
    }

    /** `RecordEnable=0`：真机出厂就是这样，报警响了也不会自己开录。 */
    @Test
    fun alarm_whenRecordEnableOff_doesNothing() = runTest {
        val svc = CapturingRecordingService()
        val app = newApp(this, svc)
        // 出厂默认 = 不启用
        app.reportAlarm(payload())

        assertTrue(svc.starts.isEmpty(), "未启用时不该起录：${svc.starts}")
        assertTrue(svc.marks.isEmpty(), "未启用时也不该打标记：${svc.marks}")
    }

    /** 已启用 + 没在录 ⇒ 起一段 Alarm 源录像，并按配置的码流记账。 */
    @Test
    fun alarm_whenIdle_startsAlarmRecording() = runTest {
        val svc = CapturingRecordingService()
        val app = newApp(this, svc)
        // recordTime = 0 → 停录 job 立即到期，不会在 runTest 里留下未完成的 delay
        app.updateVideoAlarmRecord(VideoAlarmRecordState(recordEnable = 1, recordTime = 0, streamNumber = 1))

        app.reportAlarm(payload())

        assertEquals(1, svc.starts.size, "应起一段报警录像")
        assertEquals(RecordSource.Alarm, svc.starts[0].first)
        assertEquals(1, svc.starts[0].second, "码流号应取配置里的 StreamNumber")
        // 起录后立刻打标记：这样平台按 AlarmMethod 精确筛也能查到这一段
        assertEquals(1, svc.marks.size)
        assertEquals(AlarmMethod.Video.code.toString(), svc.marks[0].second)
        assertEquals(AlarmType.DeviceTamper.code.toString(), svc.marks[0].third)
    }

    /**
     * ⭐ 正在录（手动/计划）+ 报警 ⇒ **不打断**，只打标记。
     *
     * 这条一旦回归成"另起一段"，平台侧就会出现被人为切碎的录像缺口。
     */
    @Test
    fun alarm_whileRecording_marksWithoutInterrupting() = runTest {
        val svc = CapturingRecordingService().apply {
            // 模拟"已经有一段计划录像在跑"
            state.value = RecordingState.Recording(
                startMs = 1_000L, segmentIndex = 0, source = RecordSource.Schedule,
            )
        }
        val app = newApp(this, svc)
        app.updateVideoAlarmRecord(VideoAlarmRecordState(recordEnable = 1, recordTime = 0))

        app.reportAlarm(payload())

        assertTrue(svc.starts.isEmpty(), "已在录时绝不能另起一段：${svc.starts}")
        assertEquals(1, svc.marks.size, "应把报警挂到当前段上")
        // 原来那段还在跑（没被 stop）
        assertEquals(RecordSource.Schedule, (svc.state.value as RecordingState.Recording).source)
    }

    /** 恢复出厂（null）后回到"不录"，平台回读也回到默认。 */
    @Test
    fun restoringFactoryDefault_stopsRecordingAlarms() = runTest {
        val svc = CapturingRecordingService()
        val app = newApp(this, svc)
        app.updateVideoAlarmRecord(VideoAlarmRecordState(recordEnable = 1, recordTime = 0))
        app.updateVideoAlarmRecord(null)

        app.reportAlarm(payload())

        assertTrue(svc.starts.isEmpty(), "恢复出厂后不该再起录：${svc.starts}")
        assertTrue(svc.marks.isEmpty())
    }

    /**
     * 能记录 `start` / `markAlarm` 的录像服务替身。
     *
     * `markAlarm` 按真实实现的口径返回：只有当前处于 Recording 才挂得上（否则返回 false，
     * 让 AppEngine 走"另起一段"分支）。
     */
    private class CapturingRecordingService : RecordingService {
        val starts = mutableListOf<Pair<RecordSource, Int>>()
        val marks = mutableListOf<Triple<Long, String?, String?>>()

        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(emptyList())

        override suspend fun start(
            source: RecordSource,
            channelId: String,
            streamNumber: Int,
        ): Result<Unit> {
            starts += source to streamNumber
            state.value = RecordingState.Recording(
                startMs = 1_000L, segmentIndex = 0, source = source,
            )
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<RecordingFile?> {
            state.value = RecordingState.Idle
            return Result.success(null)
        }

        override suspend fun load() = Unit
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)

        override suspend fun markAlarm(
            atMs: Long,
            alarmMethod: String?,
            alarmType: String?,
        ): Boolean {
            if (state.value !is RecordingState.Recording) return false
            marks += Triple(atMs, alarmMethod, alarmType)
            return true
        }
    }
}
