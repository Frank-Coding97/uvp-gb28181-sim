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
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.uvp.sim.domain.DeviceControlState
import java.nio.ByteBuffer
import kotlin.math.PI

/**
 * Android 真摄像机 .glb 视图(C 方案,2026-06-13).
 *
 * 加载 assets/security_camera.glb 模型,Filament ModelViewer 渲染.
 * PTZ 控制 → 整体绕 Y 轴(pan)+ X 轴(tilt)旋转 root entity.
 * Manipulator orbit 提供视角控制.
 *
 * Material / 光照 / 几何全部由 .glb 自带,我们只控制 transform.
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
    private var rootEntity: Int = 0

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

        // 触摸事件交给 ModelViewer(orbit camera)
        surfaceView.setOnTouchListener { _, event ->
            viewer.onTouchEvent(event)
            true
        }

        // 加载 .glb
        val buf: ByteBuffer = readAsset(context, "security_camera.glb")
        viewer.loadModelGlb(buf)
        viewer.transformToUnitCube()

        // 浅亮 skybox(背景),消除黑屏感
        viewer.scene.skybox = Skybox.Builder()
            .color(0.94f, 0.95f, 0.98f, 1.0f)
            .build(viewer.engine)

        // IBL 环境光(用 skybox 颜色填充全方位漫反射,消除背光面死黑)
        // 1.0 = 全亮,值越大越亮
        val iblIntensity = 40_000f
        // 不用 KTX1Loader(没准备 .ktx 资源),改用纯色 IBL → 软化阴影
        viewer.scene.indirectLight = com.google.android.filament.IndirectLight.Builder()
            .intensity(iblIntensity)
            .irradiance(1, floatArrayOf(
                // L0
                0.85f, 0.85f, 0.88f,
                // L1 (3 bands)
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f,
            ))
            .build(viewer.engine)

        // 主方向光(柔和,不投影,避免硬阴影)
        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(50_000f)
            .direction(0.3f, -0.8f, -0.5f)
            .castShadows(false)
            .build(viewer.engine, light)
        viewer.scene.addEntity(light)

        // 强制相机初始视角:斜上方俯视模型(透视感 + 看到全貌)
        // transformToUnitCube 后模型在 [-0.5, 0.5]³

        // 拿到 root entity 用于 PTZ 旋转
        rootEntity = viewer.asset?.root ?: 0

        // 设初始相机视角 — 斜上方俯视,透视感强,看到全模型
        // transformToUnitCube 后模型在 [-0.5, 0.5]³,相机距离 2.5
        viewer.camera.lookAt(
            1.5, 1.0, 2.0,    // eye: 斜上方
            0.0, 0.0, 0.0,    // target: 模型中心
            0.0, 1.0, 0.0     // up
        )

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

        // 应用 PTZ 旋转到 root entity
        if (rootEntity != 0) {
            val tm = viewer.engine.transformManager
            val ti = tm.getInstance(rootEntity)
            if (ti != 0) {
                val panRad = panAngle * PI.toFloat() / 180f
                val tiltRad = tiltAngle * PI.toFloat() / 180f
                val cp = kotlin.math.cos(panRad); val sp = kotlin.math.sin(panRad)
                val ct = kotlin.math.cos(tiltRad); val st = kotlin.math.sin(tiltRad)
                // 复合旋转: Ry(pan) * Rx(tilt) — 列主序
                val m = FloatArray(16)
                m[0] = cp;          m[1] = 0f;        m[2] = sp;         m[3] = 0f
                m[4] = sp * st;     m[5] = ct;        m[6] = -cp * st;   m[7] = 0f
                m[8] = -sp * ct;    m[9] = st;        m[10] = cp * ct;   m[11] = 0f
                m[12] = 0f;         m[13] = 0f;       m[14] = 0f;        m[15] = 1f
                tm.setTransform(ti, m)
            }
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
    }

    private fun readAsset(context: android.content.Context, name: String): ByteBuffer {
        context.assets.open(name).use { stream ->
            val bytes = stream.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.flip()
            return buf
        }
    }
}
