package com.uvp.sim.app

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.network.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 冷启动恢复设备侧状态(2026-09-17)。
 *
 * 背景:这些状态原先只在内存里,App 进程一重启设备侧就是一台**全新设备**,而平台侧完全看不出
 * 异常(轨迹还躺在库里、`enabled` 是 NULL、点「开始巡航」照样 `sent`),现场只会说
 * 「点了设备不动」。这组用例守的是:能恢复、恢复得对、且**只恢复一次**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStateRestoreTest {

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

    private fun newApp(store: DeviceStateStore, scope: CoroutineScope) = AppEngine(
        resources = FakePlatformResources(deviceStateStore = store),
        runtime = FakePlatformRuntime(),
        initialConfig = config(),
        parentScope = scope,
    )

    /** 一台"上次运行到一半被杀"的设备:三个预置位 + 一条轨迹 + 停在与开机位不同的姿态。 */
    private fun archivedDevice() = DeviceStateSnapshot(
        panAngle = 30f,
        tiltAngle = 0f,
        zoomLevel = 1f,
        presets = mapOf(
            2 to PoseSnapshot(30f, 0f, 1f),
            3 to PoseSnapshot(90f, 0f, 1.5f),
            4 to PoseSnapshot(-60f, -10f, 2f),
        ),
        currentPresetIndex = 2,
        cruiseTracks = mapOf(1 to CruiseTrackSnapshot(points = listOf(2, 3, 4), speed = 128, dwellTime = 5)),
        // 扫描边界:**平台设过就再也读不回来**的那类数据(标准无查询命令),
        // 所以"重启后还在不在"是这组用例里最该被守住的一条。
        scanGroups = mapOf(
            0 to ScanGroupSnapshot(
                leftBoundary = PoseSnapshot(-40f, 0f, 1f),
                rightBoundary = PoseSnapshot(50f, 0f, 1.2f),
                speed = 300,
            ),
        ),
        auxStates = mapOf(2 to true),
        isGuarded = true,
    )

    @Test
    fun restore_bringsBackPresetsTracksAndPose() = runTest {
        val store = FakeDeviceStateStore().apply { seed(archivedDevice()) }
        val app = newApp(store, this)

        app.restoreDeviceState()
        runCurrent()

        val m = app.deviceControlState.value
        assertEquals(setOf(2, 3, 4), m.presets.keys, "预置位是巡航与看守位引用的本体,缺了它们设备就是不动")
        assertEquals(listOf(2, 3, 4), m.cruiseTracks[1]?.points, "轨迹要在恢复后立刻能被执行链消费")
        assertEquals(128, m.cruiseTracks[1]?.speed)
        assertEquals(5, m.cruiseTracks[1]?.dwellTime)
        assertEquals(30f, m.panAngle)
        assertEquals(2, m.currentPresetIndex)
        assertEquals(mapOf(2 to true), m.auxStates)
        assertTrue(m.isGuarded)
    }

    @Test
    fun restore_pushesLocalPoseGotoSoMountedSceneFollows() = runTest {
        // 3D 场景持有自己的 pan/tilt 且只**单向**回写 Model。光写 Model 的话,已挂载的渲染端
        // 会在 166ms 后用场景的 0° 把恢复值覆盖掉 —— 必须推一个落位 effect。
        // 断言用 LocalPoseGoto 而非 PresetRecall:后者在 Android 走 easeToPose,而它有条
        // "开机自检中直接 return" 的守卫,冷启动恰好落在自检窗口内 → 恢复会被静默丢弃。
        val store = FakeDeviceStateStore().apply { seed(archivedDevice()) }
        val app = newApp(store, this)

        app.restoreDeviceState()
        runCurrent()

        val effect = app.deviceControlState.value.pendingEffect
        assertTrue(effect is DeviceEffect.LocalPoseGoto, "实际=$effect")
        assertEquals(30f, effect.targetPose.pan)
    }

    @Test
    fun restore_doesNotPushPoseEffectWhenPoseIsDefault() = runTest {
        // 只有预置位/轨迹、姿态还是 0°:不该推落位动画(那会在冷启动时闪一下"本地模拟 → 0°")。
        val store = FakeDeviceStateStore().apply {
            seed(DeviceStateSnapshot(presets = mapOf(2 to PoseSnapshot(30f, 0f, 1f))))
        }
        val app = newApp(store, this)

        app.restoreDeviceState()
        runCurrent()

        assertEquals(1, app.deviceControlState.value.presets.size)
        assertNull(app.deviceControlState.value.pendingEffect)
    }

    @Test
    fun restore_isIdempotentAcrossRepeatedCalls() = runTest {
        // engine 会随"改配置 → disconnect/connect"重建,恢复却只该在冷启动发生一次 ——
        // 否则会把操作员刚做的现场操作回退成磁盘上的旧快照。
        val store = FakeDeviceStateStore().apply { seed(archivedDevice()) }
        val app = newApp(store, this)

        app.restoreDeviceState()
        runCurrent()
        // 磁盘换了内容再调一次:幂等闩应让它读都不读
        store.seed(DeviceStateSnapshot(presets = mapOf(9 to PoseSnapshot(1f, 0f, 1f))))
        app.restoreDeviceState()
        runCurrent()

        assertEquals(setOf(2, 3, 4), app.deviceControlState.value.presets.keys, "第二次调用不得重读存档")
    }

    @Test
    fun restore_withoutArchive_keepsFreshDevice() = runTest {
        val app = newApp(FakeDeviceStateStore(), this)

        app.restoreDeviceState()
        runCurrent()

        val m = app.deviceControlState.value
        assertEquals(emptyMap(), m.presets)
        assertEquals(emptyMap(), m.cruiseTracks)
        assertNull(m.pendingEffect)
    }

    @Test
    fun restore_survivesDegenerateArchiveRead() = runTest {
        // 持久化层坏掉不该拦住 App 启动 —— 退化成"全新设备"即可(功能不受影响,只是要重新下发)。
        val app = newApp(
            object : DeviceStateStore {
                override suspend fun loadOnce(): DeviceStateSnapshot =
                    throw IllegalStateException("模拟持久化读失败")
                override suspend fun save(snapshot: DeviceStateSnapshot) = Unit
            },
            this,
        )

        app.restoreDeviceState()
        runCurrent()

        assertEquals(emptyMap(), app.deviceControlState.value.presets)
    }
}
