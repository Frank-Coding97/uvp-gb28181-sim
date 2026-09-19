package com.uvp.sim.app

import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备状态持久化契约(2026-09-17)。
 *
 * 这组用例守的重点不是"能不能存下来",而是**存哪些、不存哪些**,以及**多久写一次盘** ——
 * 前者决定冷启动后设备是"接着上次用"还是"恢复出一批假状态",后者决定会不会每帧写盘。
 */
class DeviceStateStoreTest {

    /** 一份"配置字段全非默认 + 运行态字段全非默认"的模型,用来同时验证两侧。 */
    private fun loadedModel() = DeviceControlModel(
        panAngle = 30f,
        tiltAngle = -10f,
        zoomLevel = 2.5f,
        irisLevel = 0.7f,
        focusLevel = 0.3f,
        // ↓↓↓ 运行态:一律不该进存档
        panSpeed = 5f,
        tiltSpeed = -3f,
        zoomSpeed = 1f,
        isRecording = true,
        isAlarming = true,
        isRebooting = true,
        activeCruiseTrack = 1,
        lastCommand = LastDeviceCommand("PTZCmd", "A50F0101000100xx", 1_700_000_000_000L),
        pendingEffect = DeviceEffect.PresetRecall(3, PtzPose(90f, 0f, 1.5f)),
        // ↑↑↑
        presets = mapOf(2 to PtzPose(30f, 0f, 1f), 3 to PtzPose(90f, 0f, 1.5f)),
        currentPresetIndex = 3,
        homePosition = PtzPose(30f, 0f, 1f),
        homePositionEnabled = false,
        homePositionPresetIndex = 2,
        homePositionResetTime = 60,
        cruiseTracks = mapOf(
            1 to CruiseTrackState(points = listOf(2, 3, 4), speed = 128, dwellTime = 5),
        ),
        auxStates = mapOf(1 to true, 2 to false),
        isGuarded = true,
    )

    // ---------- 存档 DTO ↔ Model ----------

    @Test
    fun snapshot_roundTripsEveryConfigField() {
        val restored = loadedModel().toDeviceStateSnapshot().restoreInto(DeviceControlModel())

        assertEquals(30f, restored.panAngle)
        assertEquals(-10f, restored.tiltAngle)
        assertEquals(2.5f, restored.zoomLevel)
        assertEquals(0.7f, restored.irisLevel)
        assertEquals(0.3f, restored.focusLevel)
        assertEquals(setOf(2, 3), restored.presets.keys, "预置位是巡航/看守位引用的本体,必须原样回来")
        assertEquals(PtzPose(90f, 0f, 1.5f), restored.presets[3])
        assertEquals(3, restored.currentPresetIndex)
        assertEquals(2, restored.homePositionPresetIndex)
        assertEquals(60, restored.homePositionResetTime)
        assertFalse(restored.homePositionEnabled)
        assertEquals(mapOf(1 to true, 2 to false), restored.auxStates)
        assertTrue(restored.isGuarded, "布防是配置性状态,重启后应保持")
        val track = assertNotNull(restored.cruiseTracks[1])
        assertEquals(listOf(2, 3, 4), track.points)
        assertEquals(128, track.speed)
        assertEquals(5, track.dwellTime)
    }

    @Test
    fun restore_doesNotReviveSessionState() {
        // 存档里**没有**任何运行态字段,所以从存档恢复出来的一定是"刚开机"的运行时状态。
        // 这条用例是"刻意不存"清单的可执行版本:谁哪天顺手把 activeCruiseTrack 加进快照,
        // 这里会立刻变红 —— 那正是「冷启动后凭空出现一条永远停在第一点的假巡航」的入口。
        val restored = loadedModel().toDeviceStateSnapshot().restoreInto(DeviceControlModel())

        assertNull(restored.lastCommand, "不该恢复出一条本次进程从未收到的命令")
        assertNull(restored.pendingEffect, "一次性动画触发器存档 = 每次冷启动重播一次动画")
        assertNull(restored.activeCruiseTrack, "进程重启后没有节拍在驱动,恢复它只能得到假巡航")
        assertEquals(0f, restored.panSpeed, "恢复非零速率会让镜头自己飘起来")
        assertEquals(0f, restored.tiltSpeed)
        assertEquals(0f, restored.zoomSpeed)
        assertFalse(restored.isRecording)
        assertFalse(restored.isAlarming)
        assertFalse(restored.isRebooting)
    }

    @Test
    fun restoreInto_keepsTargetRuntimeFieldsUntouched() {
        // 恢复是"补一段历史",不是"把 Model 换掉" —— 目标上已有的运行态必须原样活着。
        val target = DeviceControlModel(
            lastCommand = LastDeviceCommand("TeleBoot", "reboot", 42L),
            pendingEffect = DeviceEffect.Reboot,
            isRecording = true,
        )
        val restored = loadedModel().toDeviceStateSnapshot().restoreInto(target)

        assertNotNull(restored.lastCommand)
        assertEquals("TeleBoot", restored.lastCommand?.type)
        assertEquals(DeviceEffect.Reboot, restored.pendingEffect)
        assertTrue(restored.isRecording)
        // 而配置字段被覆盖成了存档值
        assertEquals(30f, restored.panAngle)
        assertEquals(2, restored.homePositionPresetIndex)
    }

    @Test
    fun hasContent_distinguishesFreshInstallFromRealArchive() {
        assertFalse(DeviceStateSnapshot().hasContent, "全默认 = 全新安装,恢复时不该打日志/推效果")
        assertTrue(DeviceStateSnapshot(presets = mapOf(2 to PoseSnapshot(1f, 2f, 1f))).hasContent)
        assertTrue(DeviceStateSnapshot(cruiseTracks = mapOf(1 to CruiseTrackSnapshot(listOf(2)))).hasContent)
        assertTrue(DeviceStateSnapshot(panAngle = 15f).hasContent, "镜头停在非零位也算有内容")
        assertTrue(DeviceStateSnapshot(isGuarded = true).hasContent)
    }

    // ---------- 节流落盘 ----------

    @Test
    fun persister_writesNothingWhileStateIsQuiet() = runTest {
        val store = FakeDeviceStateStore()
        val source = MutableStateFlow(DeviceControlModel())
        val persister = DeviceStatePersister(source, store, intervalMs = 1_000)
        persister.start(backgroundScope)
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()

        // 上游静默时既不该写盘,也不该在调度器里留一个永不停的节拍 —— 后者会让 runTest 收尾挂死。
        assertEquals(0, store.saveCount, "状态没变过就不该写盘")
        persister.stop()
    }

    @Test
    fun persister_writesAfterQuietWindow() = runTest {
        val store = FakeDeviceStateStore()
        val source = MutableStateFlow(DeviceControlModel())
        val persister = DeviceStatePersister(source, store, intervalMs = 1_000)
        persister.start(backgroundScope)
        runCurrent()

        source.value = DeviceControlModel(presets = mapOf(2 to PtzPose(30f, 0f, 1f)))
        runCurrent()
        assertEquals(0, store.saveCount, "静默窗口内不该写盘")

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, store.saveCount)
        assertEquals(1, store.peek()?.presets?.size)

        persister.stop()
    }

    @Test
    fun persister_coalescesBurstIntoSingleWrite() = runTest {
        // 姿态每 166ms 回写一次,真按变化写就是每秒 6 次盘 —— 这条用例守的就是"别那么干"。
        val store = FakeDeviceStateStore()
        val source = MutableStateFlow(DeviceControlModel())
        val persister = DeviceStatePersister(source, store, intervalMs = 1_000)
        persister.start(backgroundScope)
        runCurrent()

        repeat(30) { i ->
            source.value = DeviceControlModel(panAngle = i.toFloat())
            runCurrent()
            advanceTimeBy(166)
            runCurrent()
        }
        assertEquals(0, store.saveCount, "持续变化期间一次都不该写(每次都被新值取消)")

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, store.saveCount, "安静下来后只补一次,写的是最后一个值")
        assertEquals(29f, store.peek()?.panAngle)

        persister.stop()
    }

    @Test
    fun persister_skipsWriteWhenSnapshotIsUnchanged() = runTest {
        val store = FakeDeviceStateStore()
        val source = MutableStateFlow(DeviceControlModel())
        val persister = DeviceStatePersister(source, store, intervalMs = 1_000)
        persister.start(backgroundScope)
        runCurrent()

        source.value = DeviceControlModel(panAngle = 5f)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, store.saveCount)

        // flush 在"没脏"时必须是空操作 —— 否则切后台会变成一次无谓写。
        assertFalse(persister.flush(), "没有新变化时 flush 不该写盘")
        assertEquals(1, store.saveCount)

        persister.stop()
    }

    @Test
    fun persister_keepsDirtyOnFailureAndRetriesLater() = runTest {
        val store = FakeDeviceStateStore()
        store.saveFailure = IllegalStateException("disk full")
        val source = MutableStateFlow(DeviceControlModel())
        val persister = DeviceStatePersister(source, store, intervalMs = 1_000)
        persister.start(backgroundScope)
        runCurrent()

        source.value = DeviceControlModel(presets = mapOf(2 to PtzPose(30f, 0f, 1f)))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(0, store.saveCount, "写失败不该记成成功")

        // 故障解除后,脏标记必须还在 —— 下次 flush / 结算就能补上,不用等用户再改一次。
        store.saveFailure = null
        assertTrue(persister.flush(), "失败后 flush 应重试成功")
        assertEquals(1, store.saveCount)
        assertEquals(1, store.peek()?.presets?.size)

        persister.stop()
    }
}
