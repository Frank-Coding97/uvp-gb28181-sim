package com.uvp.sim.ui.simulate

import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.google.android.filament.TransformManager
import com.uvp.sim.domain.DeviceControlState
import kotlin.math.PI

/**
 * 摄像机模型(M2 §4 device-control 3D simulate center).
 *
 * 几何层级(由 [TransformManager] 管理):
 *
 *   root (固定)
 *     ├── base    扁圆柱(底座,深灰)
 *     ├── joint   球(云台球关节,绕 Y 轴旋转 = pan)
 *     │     └── barrel  长方体(镜筒,绕 X 轴旋转 = tilt)
 *     │           └── lens  圆锥(镜头,Z 缩放 = zoom)
 *     ├── ledPower    球(电源 LED)
 *     ├── ledRecord   球(录像 LED — 红 / 灰)
 *     └── ledAlarm    球(报警 LED — 红闪 / 灰)
 *
 * 调用方:
 *   1. 构造时把 [Scene] 传入,自动 `addEntity` 7 个主体
 *   2. 每帧 [updateTransform] 把 [DeviceControlState] 应用到层级
 *   3. dispose 时 [destroy] 释放所有 mesh 资源
 */
class FilamentCameraModel(
    private val engine: Engine,
    material: Material,
    scene: Scene,
) {

    private val factory = MeshFactory(engine, material)

    private val base: MeshFactory.Mesh = factory.createCylinder(
        radius = 0.55f, height = 0.18f, segments = 48
    )
    private val joint: MeshFactory.Mesh = factory.createSphere(
        radius = 0.32f, segments = 24, rings = 24
    )
    private val barrel: MeshFactory.Mesh = factory.createBox(
        width = 0.4f, height = 0.4f, depth = 0.95f
    )
    private val lens: MeshFactory.Mesh = factory.createCone(
        topRadius = 0.12f, bottomRadius = 0.22f, height = 0.18f, segments = 32
    )
    private val ledPower: MeshFactory.Mesh = factory.createSphere(0.05f, 12, 12)
    private val ledRecord: MeshFactory.Mesh = factory.createSphere(0.05f, 12, 12)
    private val ledAlarm: MeshFactory.Mesh = factory.createSphere(0.05f, 12, 12)

    private val transformManager: TransformManager = engine.transformManager

    init {
        applyMetal(base.materialInstance, baseColor = floatArrayOf(0.18f, 0.18f, 0.20f, 1f))
        applyMetal(joint.materialInstance, baseColor = floatArrayOf(0.55f, 0.55f, 0.58f, 1f))
        applyMetal(barrel.materialInstance, baseColor = floatArrayOf(0.18f, 0.18f, 0.20f, 1f))
        applyMetal(
            lens.materialInstance,
            baseColor = floatArrayOf(0.05f, 0.05f, 0.05f, 1f),
            roughness = 0.15f,
            metallic = 0.6f
        )
        applyLed(ledPower.materialInstance, on = true, color = floatArrayOf(0.2f, 1f, 0.4f))
        applyLed(ledRecord.materialInstance, on = false)
        applyLed(ledAlarm.materialInstance, on = false)

        setLocalTransform(base.entity, translateY = 0f)
        setLocalTransform(joint.entity, translateY = 0.4f)
        setLocalTransform(barrel.entity, translateZ = 0.55f)
        setLocalTransform(lens.entity, translateZ = 0.55f)
        setLocalTransform(ledPower.entity, translateX = -0.35f, translateY = 0.12f)
        setLocalTransform(ledRecord.entity, translateX = -0.15f, translateY = 0.12f)
        setLocalTransform(ledAlarm.entity, translateX = 0.05f, translateY = 0.12f)

        scene.addEntity(base.entity)
        scene.addEntity(joint.entity)
        scene.addEntity(barrel.entity)
        scene.addEntity(lens.entity)
        scene.addEntity(ledPower.entity)
        scene.addEntity(ledRecord.entity)
        scene.addEntity(ledAlarm.entity)
    }

    /**
     * 把 [state] 应用到层级:
     * - joint Y 轴 yaw   = panAngle°
     * - barrel X 轴 pitch = tiltAngle°
     * - lens Z 缩放      = 1 + (zoomLevel - 1) × 0.3
     * - LED 颜色:isRecording → 红, isAlarming → 红闪, isRebooting → 全灭
     */
    fun updateTransform(state: DeviceControlState) {
        val panRad = state.panAngle * PI.toFloat() / 180f
        val tiltRad = state.tiltAngle * PI.toFloat() / 180f
        val zoomScale = (1f + (state.zoomLevel - 1f) * 0.3f).coerceAtLeast(0.5f)

        setLocalTransform(joint.entity, translateY = 0.4f, rotateY = panRad)
        setLocalTransform(barrel.entity, translateZ = 0.55f, rotateX = tiltRad)
        setLocalTransform(lens.entity, translateZ = 0.55f, scaleZ = zoomScale)

        if (state.isRecording) {
            applyLed(ledRecord.materialInstance, on = true, color = floatArrayOf(1f, 0.2f, 0.2f))
        } else {
            applyLed(ledRecord.materialInstance, on = false)
        }
        if (state.isAlarming) {
            applyLed(ledAlarm.materialInstance, on = true, color = floatArrayOf(1f, 0.1f, 0.1f))
        } else {
            applyLed(ledAlarm.materialInstance, on = false)
        }
        if (state.isRebooting) {
            applyLed(ledPower.materialInstance, on = false)
        } else {
            applyLed(ledPower.materialInstance, on = true, color = floatArrayOf(0.2f, 1f, 0.4f))
        }
    }

    fun destroy() {
        listOf(base, joint, barrel, lens, ledPower, ledRecord, ledAlarm).forEach {
            it.destroy(engine)
        }
    }

    private fun applyMetal(
        mat: com.google.android.filament.MaterialInstance,
        baseColor: FloatArray,
        roughness: Float = 0.45f,
        metallic: Float = 0.7f,
    ) {
        mat.setParameter("baseColor", Colors.RgbaType.SRGB,
            baseColor[0], baseColor[1], baseColor[2], baseColor[3])
        mat.setParameter("emissive", 0f, 0f, 0f)
        mat.setParameter("roughness", roughness)
        mat.setParameter("metallic", metallic)
    }

    private fun applyLed(
        mat: com.google.android.filament.MaterialInstance,
        on: Boolean,
        color: FloatArray = floatArrayOf(0.4f, 0.4f, 0.4f),
    ) {
        if (on) {
            mat.setParameter("baseColor", Colors.RgbaType.SRGB,
                color[0] * 0.5f, color[1] * 0.5f, color[2] * 0.5f, 1f)
            mat.setParameter("emissive", color[0] * 6f, color[1] * 6f, color[2] * 6f)
        } else {
            mat.setParameter("baseColor", Colors.RgbaType.SRGB,
                0.25f, 0.25f, 0.25f, 1f)
            mat.setParameter("emissive", 0f, 0f, 0f)
        }
        mat.setParameter("roughness", 0.4f)
        mat.setParameter("metallic", 0.0f)
    }

    /**
     * 在 entity 的 [TransformManager] 上应用一次完整的 TRS.
     * 简化:只支持单轴旋转(plan §2.2.2 摄像机模型不需要复合旋转).
     */
    private fun setLocalTransform(
        entity: Int,
        translateX: Float = 0f, translateY: Float = 0f, translateZ: Float = 0f,
        rotateX: Float = 0f, rotateY: Float = 0f, rotateZ: Float = 0f,
        scaleX: Float = 1f, scaleY: Float = 1f, scaleZ: Float = 1f,
    ) {
        val ti = transformManager.getInstance(entity)
        if (ti == 0) return
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        when {
            rotateY != 0f -> {
                val c = kotlin.math.cos(rotateY); val s = kotlin.math.sin(rotateY)
                m[0] = c; m[2] = s; m[8] = -s; m[10] = c
            }
            rotateX != 0f -> {
                val c = kotlin.math.cos(rotateX); val s = kotlin.math.sin(rotateX)
                m[5] = c; m[6] = -s; m[9] = s; m[10] = c
            }
            rotateZ != 0f -> {
                val c = kotlin.math.cos(rotateZ); val s = kotlin.math.sin(rotateZ)
                m[0] = c; m[1] = s; m[4] = -s; m[5] = c
            }
        }
        m[0] *= scaleX; m[5] *= scaleY; m[10] *= scaleZ
        m[12] = translateX; m[13] = translateY; m[14] = translateZ
        transformManager.setTransform(ti, m)
    }
}
