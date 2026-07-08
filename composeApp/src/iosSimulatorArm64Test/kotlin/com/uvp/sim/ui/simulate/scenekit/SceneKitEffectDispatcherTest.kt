package com.uvp.sim.ui.simulate.scenekit

import com.uvp.sim.ui.model.DeviceEffectDto
import com.uvp.sim.ui.model.PtzPoseDto
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.3-C · SceneKitEffectDispatcher 单测.
 *
 * 覆盖 tasks:
 * - T-C2-1 构造 + scene 未 ready 时 dispatch 不崩
 * - T-C2-2 syncPtz 三 pivot rotateTo/moveTo
 * - T-C2-4 mapSpeedToDuration 反比映射
 * - T-C3-1 IFrameFlash 挂 action key
 * - T-C3-2 SnapshotFlash 挂 action key
 * - T-C3-3 HomePosition/PresetRecall/PrecisePoseGoto 共享 easeToPose 路径
 * - T-C3-4 Reboot 点头 挂 tilt_pivot action key
 * - T-C3-5 syncRecordingDot on/off
 * - T-C3-7 dispatcher 9 variant 全覆盖
 */
class SceneKitEffectDispatcherTest {

    // --- helper ---
    private fun readyScene(): SceneKitCameraScene = SceneKitCameraScene().apply {
        loadFromBundle()
        bindPivots()
        setupLightsAndCamera()
    }

    // --- T-C2-1 ---

    @Test
    fun dispatcher_constructs_without_scene_attached() {
        val scene = SceneKitCameraScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        assertNotNull(dispatcher)
    }

    @Test
    fun dispatch_noop_when_scene_not_ready() {
        val dispatcher = SceneKitEffectDispatcher(SceneKitCameraScene())
        // 不崩即通过. 9 variant 都测.
        dispatcher.dispatch(DeviceEffectDto.IFrameFlash)
        dispatcher.dispatch(DeviceEffectDto.SnapshotFlash)
        dispatcher.dispatch(DeviceEffectDto.Reboot)
        dispatcher.dispatch(DeviceEffectDto.HomePositionReturn(PtzPoseDto(0f, 0f, 1f)))
        dispatcher.dispatch(DeviceEffectDto.PresetRecall(1, PtzPoseDto(0f, 0f, 1f)))
        dispatcher.dispatch(DeviceEffectDto.PrecisePoseGoto(PtzPoseDto(0f, 0f, 1f)))
        dispatcher.dispatch(DeviceEffectDto.ConfigChanged(listOf("f")))
        dispatcher.dispatch(DeviceEffectDto.DeviceUpgradeRequested("v1"))
        dispatcher.dispatch(DeviceEffectDto.FormatSDCardRequested(0))
    }

    // --- T-C2-2 ---

    @Test
    fun syncPtz_attaches_action_to_three_pivots() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.syncPtz(30f, 15f, 1.5f)
        assertNotNull(scene.panPivot!!.actionForKey("pan_sync"))
        assertNotNull(scene.tiltPivot!!.actionForKey("tilt_sync"))
        assertNotNull(scene.zoomPivot!!.actionForKey("zoom_sync"))
    }

    @Test
    fun syncPtz_second_call_replaces_previous_action() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.syncPtz(30f, 15f, 1.5f)
        dispatcher.syncPtz(-40f, 20f, 2.0f)
        // action key 一致, 后一次覆盖前一次(避免堆积)
        assertNotNull(scene.panPivot!!.actionForKey("pan_sync"))
    }

    // --- T-C2-4 ---

    @Test
    fun mapSpeedToDuration_slow_to_fast() {
        val dispatcher = SceneKitEffectDispatcher(SceneKitCameraScene())
        val slow = dispatcher.mapSpeedToDuration(1)
        val mid = dispatcher.mapSpeedToDuration(128)
        val fast = dispatcher.mapSpeedToDuration(255)
        assertTrue(slow > mid && mid > fast, "duration decreases as speed increases")
        assertTrue(abs(slow - 0.8) < 0.05, "speed=1 near 0.8s, actual=$slow")
        assertTrue(abs(fast - 0.1) < 0.05, "speed=255 near 0.1s, actual=$fast")
    }

    @Test
    fun mapSpeedToDuration_clamps_out_of_range() {
        val dispatcher = SceneKitEffectDispatcher(SceneKitCameraScene())
        val zero = dispatcher.mapSpeedToDuration(0)      // 0 → clamp to 1
        val large = dispatcher.mapSpeedToDuration(999)   // clamp to 255
        val negative = dispatcher.mapSpeedToDuration(-5)
        assertEquals(dispatcher.mapSpeedToDuration(1), zero)
        assertEquals(dispatcher.mapSpeedToDuration(255), large)
        assertEquals(dispatcher.mapSpeedToDuration(1), negative)
    }

    // --- T-C3-1 IFrameFlash ---

    @Test
    fun dispatch_IFrameFlash_attaches_action() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.IFrameFlash)
        // fallback 场景内有 camera_lens 节点
        val lens = scene.rootNode!!.childNodeWithName("camera_lens", recursively = true) ?: scene.zoomPivot!!
        assertNotNull(lens.actionForKey("iframe_flash"))
    }

    // --- T-C3-2 SnapshotFlash ---

    @Test
    fun dispatch_SnapshotFlash_attaches_action() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.SnapshotFlash)
        val lens = scene.rootNode!!.childNodeWithName("camera_lens", recursively = true) ?: scene.zoomPivot!!
        assertNotNull(lens.actionForKey("snapshot_flash"))
    }

    @Test
    fun IFrameFlash_and_SnapshotFlash_have_independent_keys() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.IFrameFlash)
        dispatcher.dispatch(DeviceEffectDto.SnapshotFlash)
        val lens = scene.rootNode!!.childNodeWithName("camera_lens", recursively = true) ?: scene.zoomPivot!!
        assertNotNull(lens.actionForKey("iframe_flash"))
        assertNotNull(lens.actionForKey("snapshot_flash"))
    }

    // --- T-C3-3 easeToPose ---

    @Test
    fun dispatch_HomePositionReturn_attaches_action_on_three_pivots() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.HomePositionReturn(PtzPoseDto(0f, 0f, 1f)))
        assertNotNull(scene.panPivot!!.actionForKey("ease_home"))
        assertNotNull(scene.tiltPivot!!.actionForKey("ease_home"))
        assertNotNull(scene.zoomPivot!!.actionForKey("ease_home"))
    }

    @Test
    fun dispatch_PresetRecall_uses_index_scoped_key() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.PresetRecall(3, PtzPoseDto(10f, 5f, 1.2f)))
        assertNotNull(scene.panPivot!!.actionForKey("ease_preset_3"))
    }

    @Test
    fun dispatch_PrecisePoseGoto_uses_precise_key() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.PrecisePoseGoto(PtzPoseDto(45f, -15f, 2f)))
        assertNotNull(scene.panPivot!!.actionForKey("ease_precise"))
        assertNotNull(scene.tiltPivot!!.actionForKey("ease_precise"))
        assertNotNull(scene.zoomPivot!!.actionForKey("ease_precise"))
    }

    // --- T-C3-4 Reboot ---

    @Test
    fun dispatch_Reboot_attaches_nod_action_on_tilt_pivot() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.Reboot)
        assertNotNull(scene.tiltPivot!!.actionForKey("reboot_nod"))
    }

    // --- T-C3-5 REC dot ---

    @Test
    fun syncRecordingDot_on_creates_node_and_attaches_pulse() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.syncRecordingDot(true)
        val dot = scene.rootNode!!.childNodeWithName("rec_dot", recursively = true)
        assertNotNull(dot, "rec_dot node created")
        assertNotNull(dot.actionForKey("rec_pulse"), "pulse action attached")
        assertEquals(1.0, dot.opacity, 0.01)
    }

    @Test
    fun syncRecordingDot_off_removes_action_and_hides() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.syncRecordingDot(true)
        dispatcher.syncRecordingDot(false)
        val dot = scene.rootNode!!.childNodeWithName("rec_dot", recursively = true)!!
        assertNull(dot.actionForKey("rec_pulse"), "pulse action removed")
        assertEquals(0.0, dot.opacity, 0.01)
    }

    // --- T-C3-7 dispatcher 全覆盖 ---

    @Test
    fun dispatch_all_9_variants_does_not_crash() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        val effects = listOf(
            DeviceEffectDto.IFrameFlash,
            DeviceEffectDto.SnapshotFlash,
            DeviceEffectDto.Reboot,
            DeviceEffectDto.HomePositionReturn(PtzPoseDto(0f, 0f, 1f)),
            DeviceEffectDto.PresetRecall(1, PtzPoseDto(20f, 10f, 1.5f)),
            DeviceEffectDto.PrecisePoseGoto(PtzPoseDto(-30f, 5f, 2f)),
            DeviceEffectDto.ConfigChanged(listOf("panLimit")),
            DeviceEffectDto.DeviceUpgradeRequested("v1.4.0"),
            DeviceEffectDto.FormatSDCardRequested(0),
        )
        effects.forEach { dispatcher.dispatch(it) }
    }

    @Test
    fun ConfigChanged_DeviceUpgrade_Format_do_not_attach_any_action() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.dispatch(DeviceEffectDto.ConfigChanged(listOf("test")))
        dispatcher.dispatch(DeviceEffectDto.DeviceUpgradeRequested("v1"))
        dispatcher.dispatch(DeviceEffectDto.FormatSDCardRequested(0))
        // 这 3 类 delegated 到 commonMain, 不动 SceneKit action.
        // 验证方式:不该出现的 key 缺席.
        assertNull(scene.panPivot!!.actionForKey("ease_home"))
        assertNull(scene.tiltPivot!!.actionForKey("reboot_nod"))
        assertNull(scene.zoomPivot!!.actionForKey("iframe_flash"))
    }

    // --- stopAllActions (dispatcher lifecycle 辅助) ---

    @Test
    fun stopAllActions_clears_pivot_and_dot_actions() {
        val scene = readyScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        dispatcher.syncPtz(20f, 10f, 1.3f)
        dispatcher.syncRecordingDot(true)
        dispatcher.stopAllActions()
        // 通过 actionForKey 判断具体 key 是否被移除,避免 actionKeys collection crossroad
        assertNull(scene.panPivot!!.actionForKey("pan_sync"))
        assertNull(scene.tiltPivot!!.actionForKey("tilt_sync"))
        assertNull(scene.zoomPivot!!.actionForKey("zoom_sync"))
        val dot = scene.rootNode!!.childNodeWithName("rec_dot", recursively = true)!!
        assertNull(dot.actionForKey("rec_pulse"))
    }
}
