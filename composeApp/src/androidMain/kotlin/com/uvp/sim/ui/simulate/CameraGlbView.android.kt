package com.uvp.sim.ui.simulate

import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.uvp.sim.domain.DeviceControlState
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android .glb 摄像机视图(C 方案 + 头部局部旋转,2026-06-13 老板真机定位).
 *
 * 老板用 debug 自转模式 14:47 确认了 mesh 命名:
 *   Sphere     = 头部主体  → 接 pan(左右)
 *   Sphere.002 = 镜头/俯仰 → 接 pan + tilt(左右 + 上下)
 *   Sphere.001 = 不参与    → 不动(估计是装饰)
 *   Cylinder   = 底座      → 不动
 *   Plane      = 地面      → 不动
 */
@Composable
actual fun CameraGlbView(state: DeviceControlState, modifier: Modifier) {
    val context = LocalContext.current
    val sceneState = remember { GlbSceneState() }
    val currentState by rememberUpdatedState(state)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { surfaceView ->
                sceneState.attach(ctx, surfaceView) { currentState }
            }
        }
    )
    DisposableEffect(Unit) {
        onDispose { sceneState.detach() }
    }
}

private class GlbSceneState {
    private var modelViewer: ModelViewer? = null
    private var stateProvider: (() -> DeviceControlState)? = null
    private var light: Int = 0

    /** Pan(左右,绕 Z)目标 entity → 初始 transform.含 Sphere(头部)+ Sphere.002(镜头). */
    private val panInits = mutableMapOf<Int, FloatArray>()
    /** Tilt(上下,绕 X)目标 entity → 初始 transform.只含 Sphere.002. */
    private val tiltInits = mutableMapOf<Int, FloatArray>()
    /** Sphere(头部)的世界中心,Sphere.002 绕这个点 pan 旋转. */
    private var sphereCenter: FloatArray? = null

    private var panAngle = 0f
    private var tiltAngle = 0f
    private var zoomLevel = 1f
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            renderFrame(frameTimeNanos)
        }
    }

    fun attach(
        context: android.content.Context,
        surfaceView: SurfaceView,
        provider: () -> DeviceControlState
    ) {
        Utils.init()
        stateProvider = provider

        val viewer = ModelViewer(surfaceView)
        modelViewer = viewer
        // 老板要求禁止用户拖动 — 吃掉所有 touch 事件不传给 ModelViewer
        surfaceView.setOnTouchListener { _, _ -> true }

        val buf = readAsset(context, "security_camera.glb")
        viewer.loadModelGlb(buf)
        viewer.transformToUnitCube()

        // 老板要求加强对比 — 暖米黄背景让深灰金属摄像头跳出来
        viewer.scene.skybox = Skybox.Builder()
            .color(0.97f, 0.93f, 0.85f, 1.0f)  // 米黄棚拍风
            .build(viewer.engine)
        viewer.scene.indirectLight = IndirectLight.Builder()
            .intensity(45_000f)
            .irradiance(1, floatArrayOf(0.95f, 0.90f, 0.78f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
            .build(viewer.engine)

        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.95f, 0.88f).intensity(75_000f)  // 提强 50k → 75k,加强金属高光
            .direction(0.3f, -0.8f, -0.5f).castShadows(false)
            .build(viewer.engine, light)
        viewer.scene.addEntity(light)

        // 老板验证最终方案:Sphere 接 pan(水平),Sphere.002 接 tilt(俯仰)
        // 不让 Sphere.002 跟随 Sphere 一起 pan,因为两者是平级 mesh,
        // 强行让 Sphere.002 绕 Sphere 中心 pan 会出现反向旋转或钟表画圈,
        // 暂留 Sphere.002 只接 tilt,镜头位置固定但能上下抬头.
        // (下次会话用 Blender 验证 mesh 父子关系后再精修)
        viewer.asset?.let { asset ->
            val panNames = setOf("Sphere")
            val tiltNames = setOf("Sphere.002")
            val tm = viewer.engine.transformManager
            for (e in asset.entities) {
                val n = asset.getName(e) ?: continue
                val ti = tm.getInstance(e)
                if (ti == 0) continue
                if (n in panNames) {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    panInits[e] = initM
                }
                if (n in tiltNames) {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    tiltInits[e] = initM
                }
            }
        }

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val viewer = modelViewer ?: return
        val getState = stateProvider ?: return

        val dt = if (lastFrameNanos == 0L) 0f
                 else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = frameTimeNanos

        val s = getState()
        panAngle = (panAngle + s.panSpeed * dt).coerceIn(-180f, 180f)
        tiltAngle = (tiltAngle + s.tiltSpeed * dt).coerceIn(-90f, 90f)
        zoomLevel = (zoomLevel + s.zoomSpeed * dt).coerceIn(1f, 16f)

        val panRad = panAngle * PI.toFloat() / 180f
        val tiltRad = tiltAngle * PI.toFloat() / 180f
        val cp = cos(panRad); val sp = sin(panRad)
        val ct = cos(tiltRad); val st = sin(tiltRad)
        // .glb 模型可能是 Z-up,pan 改用 Rz(绕 Z 轴)做水平旋转
        val rotPan = FloatArray(16).apply {
            this[0] = cp; this[1] = sp;
            this[4] = -sp; this[5] = cp;
            this[10] = 1f; this[15] = 1f
        }
        val rotTilt = FloatArray(16).apply {
            this[0] = 1f
            this[5] = ct; this[6] = -st
            this[9] = st; this[10] = ct
            this[15] = 1f
        }

        val tm = viewer.engine.transformManager
        // Sphere(头部)绕自己中心水平转 pan
        for ((entity, init) in panInits) {
            val ti = tm.getInstance(entity)
            if (ti == 0) continue
            tm.setTransform(ti, mat4Multiply(init, rotPan))
        }
        // Sphere.002(镜头)绕自己中心上下转 tilt(独立,不跟随 Sphere)
        for ((entity, init) in tiltInits) {
            val ti = tm.getInstance(entity)
            if (ti == 0) continue
            tm.setTransform(ti, mat4Multiply(init, rotTilt))
        }

        viewer.render(frameTimeNanos)
    }

    fun detach() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val viewer = modelViewer ?: return
        try {
            if (light != 0) {
                viewer.scene.removeEntity(light)
                EntityManager.get().destroy(light); light = 0
            }
            viewer.destroyModel()
            // 关键 fix: 彻底销毁 Filament Engine + 所有原生资源,
            // 不然切 tab 时 Compose 拆掉 SurfaceView,但 Engine 还在跑帧 → native crash.
            viewer.engine.destroy()
        } catch (_: Throwable) {
            // engine 已经被销毁过的容错
        }
        modelViewer = null; stateProvider = null
        panInits.clear(); tiltInits.clear()
        lastFrameNanos = 0L
    }

    private fun readAsset(context: android.content.Context, name: String): ByteBuffer {
        context.assets.open(name).use { stream ->
            val bytes = stream.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes); buf.flip(); return buf
        }
    }

    private fun mat4Multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            var sum = 0f
            for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        return r
    }

    private fun identity(): FloatArray = FloatArray(16).apply {
        this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
    }
}
