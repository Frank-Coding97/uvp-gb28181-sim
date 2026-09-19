package com.uvp.sim.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 侧设备状态存档落盘(Robolectric)。
 *
 * 覆盖的是 **commonTest 覆盖不到的那一层**:DataStore 真的能写进去、读回来、以及
 * 存档坏掉时不把 App 拖下水。业务语义(存哪些字段 / 多久写一次)在
 * `shared/src/commonTest/.../DeviceStateStoreTest.kt` 里。
 *
 * 清空用 `dataStoreForTest().edit { it.clear() }` 而不是删文件 —— `preferencesDataStore`
 * 是进程级单例,删掉底层文件它的内存缓存还在,测试之间会串味。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceStateStoreAndroidTest {

    private lateinit var context: Context
    private lateinit var store: DeviceStateStoreAndroid

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = DeviceStateStoreAndroid(context)
    }

    @Test
    fun roundTrip_preservesPresetsAndCruiseTracks() = runTest {
        clearArchive()
        assertNull("清空后应视为全新设备", store.loadOnce())

        val snapshot = DeviceStateSnapshot(
            panAngle = 30f,
            tiltAngle = -10f,
            zoomLevel = 2.5f,
            presets = mapOf(2 to PoseSnapshot(30f, 0f, 1f), 3 to PoseSnapshot(90f, 0f, 1.5f)),
            currentPresetIndex = 2,
            cruiseTracks = mapOf(1 to CruiseTrackSnapshot(listOf(2, 3), speed = 128, dwellTime = 5)),
            auxStates = mapOf(1 to true),
            isGuarded = true,
        )
        store.save(snapshot)

        // 预置位与巡航轨迹是"重启后设备还能被平台驱动"的前提,必须逐字段原样回来。
        assertEquals(snapshot, store.loadOnce())
    }

    @Test
    fun corruptedArchive_degradesToFreshDeviceInsteadOfThrowing() = runTest {
        // 存档坏掉不该拦住 App 启动:loadOnce 返回 null,调用方按全新设备走,功能只是要重新下发。
        DeviceStateStoreAndroid.dataStoreForTest(context).edit {
            it[DeviceStateStoreAndroid.KEY_DEVICE_STATE_JSON_FOR_TEST] = "{ 这不是合法 JSON"
        }

        assertNull(store.loadOnce())
    }

    private suspend fun clearArchive() {
        DeviceStateStoreAndroid.dataStoreForTest(context).edit { it.clear() }
    }
}
