package com.uvp.sim

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.uvp.sim.app.ConfigStore
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * cross-review R1 #4:saveCatalogTree 持久化失败语义。
 *
 * 过去 `_lastCatalogSavedAt` 在协程外同步设置(save 还没跑就报成功),
 * 且 `runCatching { save }` 吞掉失败 → save 失败时 UI 仍显示"已保存"、
 * 重启后用户丢失编辑且无任何提示。
 *
 * 修复后:save 成功才更新时间戳;save 失败发 TransportError 事件且不更新时间戳。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SipViewModelCatalogSaveTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    /** save 抛异常的 ConfigStore — 模拟 keystore/DataStore 写失败。 */
    private class ThrowingSaveConfigStore : ConfigStore {
        override suspend fun loadOnce(fallback: SimConfig): SimConfig = fallback
        override suspend fun save(config: SimConfig) {
            throw IllegalStateException("模拟持久化失败")
        }
    }

    @Before
    fun setupMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun saveCatalogTree_persistence_failure_no_timestamp_and_emits_error() = runTest(testDispatcher) {
        val vm = SipViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            configStoreOverride = ThrowingSaveConfigStore(),
        )
        advanceUntilIdle()

        // 用当前合法 catalogTree(default 装配),只验证持久化失败语义,不触发 validation 失败
        // 显式合法 tree(写死合法国标 ID;default config 的 deviceId 可能为空过不了 validate)
        val dev = "35020000001310000001"
        val tree = listOf(
            com.uvp.sim.config.CatalogNode(dev, com.uvp.sim.config.CatalogNodeType.Device, "Dev", dev),
            com.uvp.sim.config.CatalogNode(
                "35020000001320000001",
                com.uvp.sim.config.CatalogNodeType.VideoChannel, "Cam", dev,
            ),
        )
        val baselineTree = vm.catalogTree.value
        val vr = vm.saveCatalogTree(tree)
        advanceUntilIdle()

        assertTrue("前置:tree 必须合法,实际 $vr", vr is com.uvp.sim.domain.ValidationResult.Ok)
        assertNull(
            "save 失败时不能更新 lastCatalogSavedAt(否则 UI 假报成功)",
            vm.lastCatalogSavedAt.value,
        )
        val errs = vm.events.value.filterIsInstance<SimEvent.TransportError>()
        assertTrue(
            "save 失败必须 emit TransportError,实际 events=${vm.events.value}",
            errs.isNotEmpty(),
        )
        // verify-1 补强(CodeX R1 verify):save 失败必须是本地/运行态 catalog 变更的硬停止 —
        // 不能 applyConfigPartial / updateCatalogTree 把未落盘的树推进运行态并 NOTIFY 下发。
        assertEquals(
            "save 失败时 catalogTree 不能被未落盘的新树覆盖(commit-on-success)",
            baselineTree,
            vm.catalogTree.value,
        )
    }
}
