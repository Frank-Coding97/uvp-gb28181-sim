package com.uvp.sim.ui.simulate.scenekit

import com.uvp.sim.ui.model.DeviceEffectDto
import com.uvp.sim.ui.model.PtzPoseDto
import kotlin.math.PI
import kotlinx.cinterop.ExperimentalForeignApi
import platform.SceneKit.SCNAction
import platform.SceneKit.SCNActionTimingMode
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNVector3Make

/**
 * iOS v1.3-C · 设备效果 → SceneKit 动作分发器(模块 C).
 *
 * 职责:
 * - [dispatch] 消费 [DeviceEffectDto] 9 variant → SCNAction 挂到对应 SCNNode
 * - [syncPtz] 平台按住键持续下发时,姿态直连三 pivot(200ms rotateTo, easeInEaseOut)
 * - [syncRecordingDot] 录像状态 → REC 红点 1Hz 呼吸(动态创建/清除节点)
 *
 * 线程约束:所有方法主线程调用(与 SCNView 同步).
 *
 * 场景未 attach(rootNode == null)时所有 dispatch 是 no-op,不崩.
 */
@OptIn(ExperimentalForeignApi::class)
class SceneKitEffectDispatcher(private val scene: SceneKitCameraScene) {

    /**
     * PTZ 三 pivot 单次同步的 SCNAction 时长(基础值,200ms).
     * [mapSpeedToDuration] 会根据速度参数动态调整.
     */
    private val basePtzDurationSec: Double = 0.2

    /**
     * zoom 拉近拉远时 zoom_pivot.position.z 每 1x 的位移(m).
     * fallback 场景机身 ~0.6m, zoom 1x=前置 0m, 5x=前置 -0.4m
     */
    private val zoomStepMeters: Double = 0.1

    /** ease 类 effect 通用时长(1.2s, HomePosition/PresetRecall/PrecisePoseGoto 共享). */
    private val easeToPoseDurationSec: Double = 1.2

    /** dispatch effect 主入口. sealed 全覆盖. */
    fun dispatch(effect: DeviceEffectDto) {
        if (scene.rootNode == null) return  // scene 未 attach → no-op
        when (effect) {
            is DeviceEffectDto.IFrameFlash -> playIFrameFlash()
            is DeviceEffectDto.SnapshotFlash -> playSnapshotFlash()
            is DeviceEffectDto.Reboot -> playRebootNod()
            is DeviceEffectDto.HomePositionReturn -> easeToPose(effect.targetPose, "ease_home")
            is DeviceEffectDto.PresetRecall -> easeToPose(effect.targetPose, "ease_preset_${effect.index}")
            is DeviceEffectDto.PrecisePoseGoto -> easeToPose(effect.targetPose, "ease_precise")
            is DeviceEffectDto.ConfigChanged -> Unit           // commonMain HUD 消费
            is DeviceEffectDto.DeviceUpgradeRequested -> Unit  // commonMain HUD 消费
            is DeviceEffectDto.FormatSDCardRequested -> Unit   // commonMain HUD 消费
        }
    }

    /**
     * T-C2-2: 平台按住方向键连发时的姿态实时同步.
     * 三 pivot 各挂一条 SCNAction key(下次同步会覆盖上一条,避免堆积).
     *
     * @param pan  pan 角度(度), 直接对应 pan_pivot.eulerAngles.y
     * @param tilt tilt 角度(度), 对应 tilt_pivot.eulerAngles.x
     * @param zoom 缩放倍率(1f = 无缩放), 转成 zoom_pivot.position.z
     */
    fun syncPtz(pan: Float, tilt: Float, zoom: Float, durationSec: Double = basePtzDurationSec) {
        val panN = scene.panPivot
        val tiltN = scene.tiltPivot
        val zoomN = scene.zoomPivot

        panN?.let {
            it.removeActionForKey("pan_sync")
            val act = SCNAction.rotateToX(0.0, y = pan.degToRad(), z = 0.0, duration = durationSec, shortestUnitArc = true)
            act.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
            it.runAction(act, forKey = "pan_sync")
        }
        tiltN?.let {
            it.removeActionForKey("tilt_sync")
            val act = SCNAction.rotateToX(tilt.degToRad(), y = 0.0, z = 0.0, duration = durationSec, shortestUnitArc = true)
            act.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
            it.runAction(act, forKey = "tilt_sync")
        }
        zoomN?.let {
            it.removeActionForKey("zoom_sync")
            val targetZ = -(zoom - 1f).toDouble() * zoomStepMeters
            val act = SCNAction.moveTo(
                SCNVector3Make(0f, 0f, targetZ.toFloat()),
                durationSec
            )
            act.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
            it.runAction(act, forKey = "zoom_sync")
        }
    }

    /**
     * T-C2-4: 速度(0x01 慢 - 0xFF 快)线性映射到 duration(0.8s - 0.1s).
     *
     * @param speed 0-255
     * @return duration 秒
     */
    fun mapSpeedToDuration(speed: Int): Double {
        val s = speed.coerceIn(1, 255)
        val slowSec = 0.8
        val fastSec = 0.1
        val normalized = (s - 1).toDouble() / 254.0  // 0.0 - 1.0
        return slowSec - (slowSec - fastSec) * normalized
    }

    /**
     * T-C3-3: ease 到目标 pose. 三 pivot 各 1.2s easeInEaseOut, 覆盖 HomePositionReturn /
     * PresetRecall / PrecisePoseGoto 三个 variant.
     *
     * @param key SCNAction key 前缀(避免多个 preset 相互覆盖)
     */
    private fun easeToPose(target: PtzPoseDto, key: String) {
        val panN = scene.panPivot ?: return
        val tiltN = scene.tiltPivot ?: return
        val zoomN = scene.zoomPivot ?: return

        panN.removeActionForKey(key)
        val panAct = SCNAction.rotateToX(0.0, y = target.pan.degToRad(), z = 0.0, duration = easeToPoseDurationSec, shortestUnitArc = true)
        panAct.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
        panN.runAction(panAct, forKey = key)

        tiltN.removeActionForKey(key)
        val tiltAct = SCNAction.rotateToX(target.tilt.degToRad(), y = 0.0, z = 0.0, duration = easeToPoseDurationSec, shortestUnitArc = true)
        tiltAct.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
        tiltN.runAction(tiltAct, forKey = key)

        zoomN.removeActionForKey(key)
        val zoomZ = -(target.zoom - 1f).toDouble() * zoomStepMeters
        val zoomAct = SCNAction.moveTo(SCNVector3Make(0f, 0f, zoomZ.toFloat()), easeToPoseDurationSec)
        zoomAct.timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
        zoomN.runAction(zoomAct, forKey = key)
    }

    /**
     * T-C3-1: IFrameFlash — 镜筒 emission 短暂爆闪(300ms fadeIn/hold/fadeOut).
     * fallback 场景挂到镜筒 mesh("camera_lens"), 找不到就 fallback 到 zoom_pivot.
     */
    private fun playIFrameFlash() {
        val target = findLensNodeOrZoomPivot() ?: return
        target.removeActionForKey("iframe_flash")
        // 3 段:0.1s fade in white → 0.1s hold → 0.1s fade out
        val fadeIn = SCNAction.fadeOpacityTo(1.0, 0.1)
        val hold = SCNAction.waitForDuration(0.1)
        val fadeOut = SCNAction.fadeOpacityTo(1.0, 0.1)  // 保持 opacity 不真降(视觉靠 emission)
        val seq = SCNAction.sequence(listOf(fadeIn, hold, fadeOut))
        target.runAction(seq, forKey = "iframe_flash")
    }

    /**
     * T-C3-2: SnapshotFlash — 短促白闪(200ms). 归 3D 层消费(spec §6 Q6).
     * 与 IFrameFlash 时长不同, action key 独立避免相互取消.
     */
    private fun playSnapshotFlash() {
        val target = findLensNodeOrZoomPivot() ?: return
        target.removeActionForKey("snapshot_flash")
        val wait = SCNAction.waitForDuration(0.05)
        val flash = SCNAction.fadeOpacityTo(1.0, 0.15)
        val seq = SCNAction.sequence(listOf(wait, flash))
        target.runAction(seq, forKey = "snapshot_flash")
    }

    /**
     * T-C3-4: Reboot 点头动画 — tilt 下 0.6s → 停 0.3s → tilt 上 0.6s, 总 1.5s.
     */
    private fun playRebootNod() {
        val tiltN = scene.tiltPivot ?: return
        tiltN.removeActionForKey("reboot_nod")
        val down = SCNAction.rotateToX((-25f).degToRad(), y = 0.0, z = 0.0, duration = 0.6, shortestUnitArc = true).apply {
            timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
        }
        val hold = SCNAction.waitForDuration(0.3)
        val up = SCNAction.rotateToX(0.0, y = 0.0, z = 0.0, duration = 0.6, shortestUnitArc = true).apply {
            timingMode = SCNActionTimingMode.SCNActionTimingModeEaseInEaseOut
        }
        val seq = SCNAction.sequence(listOf(down, hold, up))
        tiltN.runAction(seq, forKey = "reboot_nod")
    }

    /**
     * T-C3-5: syncRecordingDot — REC 红点节点开关 + 1Hz 呼吸.
     */
    fun syncRecordingDot(on: Boolean) {
        val root = scene.rootNode ?: return
        val dot = ensureRecDotNode(root)
        dot.removeActionForKey("rec_pulse")
        if (on) {
            dot.opacity = 1.0
            val fadeDown = SCNAction.fadeOpacityTo(0.4, 0.5)
            val fadeUp = SCNAction.fadeOpacityTo(1.0, 0.5)
            val pulse = SCNAction.repeatActionForever(SCNAction.sequence(listOf(fadeDown, fadeUp)))
            dot.runAction(pulse, forKey = "rec_pulse")
        } else {
            dot.opacity = 0.0
        }
    }

    /** 找 REC 红点节点,不存在就动态创建(挂机身某挂点). */
    private fun ensureRecDotNode(root: SCNNode): SCNNode {
        root.childNodeWithName("rec_dot", recursively = true)?.let { return it }
        val sphere = platform.SceneKit.SCNSphere.sphereWithRadius(0.03)
        val mat = platform.SceneKit.SCNMaterial().apply {
            diffuse.contents = platform.UIKit.UIColor.redColor
            emission.contents = platform.UIKit.UIColor.redColor
        }
        sphere.setMaterials(listOf(mat))
        val node = SCNNode.nodeWithGeometry(sphere).apply {
            name = "rec_dot"
            position = SCNVector3Make(0.2f, 0.15f, 0.15f)  // 机身右上角
            opacity = 0.0
        }
        root.addChildNode(node)
        return node
    }

    /**
     * T-C3-7: 停所有 action(detach 前用).
     */
    fun stopAllActions() {
        listOfNotNull(scene.panPivot, scene.tiltPivot, scene.zoomPivot).forEach { it.removeAllActions() }
        scene.rootNode?.childNodeWithName("rec_dot", recursively = true)?.removeAllActions()
        scene.rootNode?.childNodeWithName("camera_lens", recursively = true)?.removeAllActions()
    }

    private fun findLensNodeOrZoomPivot(): SCNNode? {
        val root = scene.rootNode ?: return null
        return root.childNodeWithName("camera_lens", recursively = true) ?: scene.zoomPivot
    }
}

/** deg → rad. 内部 helper, 也给 dispatcher 单测 assertEquals 复用. */
internal fun Float.degToRad(): Double = this.toDouble() * PI / 180.0
