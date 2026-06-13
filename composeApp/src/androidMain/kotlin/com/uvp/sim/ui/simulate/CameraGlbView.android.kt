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
 * Android .glb 摄像机视图(C 方案 + 头部局部旋转,2026-06-13).
 *
 * .glb 内部 5 mesh 命名(从 inspect 得知):
 *   Sphere.002 / Sphere / Sphere.001 = 头部组件(应用 PTZ 旋转)
 *   Cylinder                          = 底座(不旋转,固定地面)
 *   Plane                             = 地面装饰(不旋转)
 *
 * 实现方式:加载 .glb 后保存这 3 个头部 entity 的初始 transform,
 * 每帧用 final = initTransform × ptzRotation 写回,
 * 这样头部跟父节点的相对位置保留,只是叠加了 pan/tilt 旋转.
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

    /** 头部子 entity → .glb 加载时的初始 transform(列主序 4x4). */
    private val headInits = mutableMapOf<Int, FloatArray>()

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
        provider().also {
            panAngle = it.panAngle
            tiltAngle = it.tiltAngle
            zoomLevel = it.zoomLevel.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
        }

        val viewer = ModelViewer(surfaceView)
        modelViewer = viewer
        viewer.cameraNear = 0.05f
        viewer.cameraFar = 24f
        viewer.cameraFocalLength = 28f

        // 加载 .glb
        val buf: ByteBuffer = readAsset(context, "security_camera.glb")
        viewer.loadModelGlb(buf)
        viewer.transformToUnitCube()

        viewer.scene.skybox = Skybox.Builder()
            .color(0.94f, 0.95f, 0.98f, 1.0f)
            .build(viewer.engine)

        viewer.scene.indirectLight = IndirectLight.Builder()
            .intensity(40_000f)
            .irradiance(1, floatArrayOf(
                0.85f, 0.85f, 0.88f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
            ))
            .build(viewer.engine)

        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(50_000f)
            .direction(0.3f, -0.8f, -0.5f)
            .castShadows(false)
            .build(viewer.engine, light)
        viewer.scene.addEntity(light)

        // 找头部 3 个 entity + 保存初始 transform.同时找 RootNode 给它加基础朝向旋转,
        // 让镜头默认朝用户(原始 .glb 模型可能侧躺/朝里).
        viewer.asset?.let { asset ->
            val headNames = setOf("Sphere.002", "Sphere", "Sphere.001")
            val tm = viewer.engine.transformManager
            for (e in asset.entities) {
                val n = asset.getName(e) ?: continue
                if (n in headNames) {
                    val ti = tm.getInstance(e)
                    if (ti != 0) {
                        val initM = FloatArray(16)
                        tm.getTransform(ti, initM)
                        headInits[e] = initM
                    }
                }
                if (n == "RootNode") {
                    // 给 RootNode 叠加一次基础旋转(绕 Y 轴 -45°,让镜头朝前下方对用户)
                    val ti = tm.getInstance(e)
                    if (ti != 0) {
                        val cur = FloatArray(16)
                        tm.getTransform(ti, cur)
                        val baseRotY = 0f  // 0° 老板拍板,.glb 原朝向就是镜头朝前
                        val cy = cos(baseRotY); val sy = sin(baseRotY)
                        val rotY = FloatArray(16).apply {
                            this[0] = cy;  this[2] = sy;  this[5] = 1f
                            this[8] = -sy; this[10] = cy; this[15] = 1f
                        }
                        val newRoot = mat4Multiply(cur, rotY)
                        tm.setTransform(ti, newRoot)
                    }
                }
            }
        }

        updateCamera(viewer, zoomLevel)

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val viewer = modelViewer ?: return
        val getState = stateProvider ?: return

        val dt = if (lastFrameNanos == 0L) 0f
                 else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = frameTimeNanos

        val s = getState()
        panAngle = wrapDegrees(panAngle + s.panSpeed * dt)
        tiltAngle = (tiltAngle + s.tiltSpeed * dt).coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG)
        zoomLevel = (zoomLevel + s.zoomSpeed * dt).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
        updateCamera(viewer, zoomLevel)

        // PTZ 旋转矩阵: Ry(pan) * Rx(tilt)
        val panRad = panAngle * PI.toFloat() / 180f
        val tiltRad = tiltAngle * PI.toFloat() / 180f
        val cp = cos(panRad); val sp = sin(panRad)
        val ct = cos(tiltRad); val st = sin(tiltRad)
        val ptz = FloatArray(16).apply {
            this[0] = cp;       this[1] = 0f;       this[2] = sp;        this[3] = 0f
            this[4] = sp * st;  this[5] = ct;       this[6] = -cp * st;  this[7] = 0f
            this[8] = -sp * ct; this[9] = st;       this[10] = cp * ct;  this[11] = 0f
            this[12] = 0f; this[13] = 0f; this[14] = 0f; this[15] = 1f
        }

        // 只对头部应用 PTZ:final = init * ptz
        val tm = viewer.engine.transformManager
        for ((entity, init) in headInits) {
            val ti = tm.getInstance(entity)
            if (ti == 0) continue
            val finalM = mat4Multiply(init, ptz)
            tm.setTransform(ti, finalM)
        }

        viewer.render(frameTimeNanos)
    }

    fun detach() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val viewer = modelViewer ?: return
        if (light != 0) {
            viewer.scene.removeEntity(light)
            EntityManager.get().destroy(light)
            light = 0
        }
        viewer.destroyModel()
        modelViewer = null
        stateProvider = null
        lastFrameNanos = 0L
        headInits.clear()
    }

    private fun readAsset(context: android.content.Context, name: String): ByteBuffer {
        context.assets.open(name).use { stream ->
            val bytes = stream.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes); buf.flip()
            return buf
        }
    }

    /** 列主序 4x4 矩阵乘法: result = a * b. */
    private fun mat4Multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[k * 4 + row] * b[col * 4 + k]
                }
                r[col * 4 + row] = sum
            }
        }
        return r
    }

    private fun updateCamera(viewer: ModelViewer, zoom: Float) {
        val t = ((zoom - MIN_ZOOM_LEVEL) / (MAX_ZOOM_LEVEL - MIN_ZOOM_LEVEL)).coerceIn(0f, 1f)
        val eyeX = lerp(1.56f, 0.94f, t)
        val eyeY = lerp(1.04f, 0.76f, t)
        val eyeZ = lerp(2.12f, 1.08f, t)
        val targetY = lerp(0.02f, 0.10f, t)

        viewer.camera.lookAt(
            eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
            0.0, targetY.toDouble(), 0.0,
            0.0, 1.0, 0.0
        )
        viewer.cameraFocalLength = lerp(28f, 42f, t)
    }

    private fun wrapDegrees(value: Float): Float {
        var angle = value % 360f
        if (angle > 180f) angle -= 360f
        if (angle < -180f) angle += 360f
        return angle
    }

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    companion object {
        private const val MIN_ZOOM_LEVEL = 1f
        private const val MAX_ZOOM_LEVEL = 16f
        private const val MAX_TILT_DEG = 85f
    }
}
