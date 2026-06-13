package com.uvp.sim.ui.simulate

import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.Utils
import com.uvp.sim.domain.DeviceControlState

/**
 * Android Camera3DView (M2 §4 device-control 3D simulate center).
 *
 * 真实现:Filament Engine + UiHelper SwapChain + FilamentCameraModel + Choreographer
 * 帧回调驱动 PTZ 速率积分.
 *
 * - Manipulator orbit camera(用户单指旋转视角,不影响 PTZ 姿态)
 * - 每帧从 [DeviceControlState] 读 panSpeed/tiltSpeed/zoomSpeed 积分到 panAngle/tiltAngle/zoomLevel
 * - clamp pan -180..180, tilt -90..90, zoom 1..16
 * - 状态写回 model.updateTransform(state) 渲染
 *
 * 不可真机验证(Mac 上没 Android 真机)——以编译通过 + Filament API 调用合规为完成标准.
 */
@Composable
actual fun Camera3DView(state: DeviceControlState, modifier: Modifier) {
    val currentState by rememberUpdatedState(state)
    val sceneState = remember { CameraSceneState() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { surfaceView ->
                sceneState.attach(surfaceView) { currentState }
            }
        },
        update = { /* state 通过 lambda 闭包,update 块不需做事 */ }
    )
    DisposableEffect(Unit) {
        onDispose { sceneState.detach() }
    }
}

/**
 * 持有 Filament 资源 + 渲染循环句柄 + 累积的 PTZ pose.
 */
private class CameraSceneState {
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null
    private var manipulator: Manipulator? = null
    private var model: FilamentCameraModel? = null
    private var light: Int = 0
    private var stateProvider: (() -> DeviceControlState)? = null

    // 累积量(由帧回调里 panSpeed * dt 积分得到)
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

    fun attach(surfaceView: SurfaceView, provider: () -> DeviceControlState) {
        // Filament 的 native 库不会自动 load,显式 init() 触发
        // System.loadLibrary("filament-jni") + filamat + utils
        Utils.init()
        stateProvider = provider
        val eng = Engine.create()
        engine = eng
        renderer = eng.createRenderer()
        scene = eng.createScene()
        view = eng.createView()
        camera = eng.createCamera(EntityManager.get().create())
        view!!.scene = scene
        view!!.camera = camera

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: android.view.Surface) {
                    swapChain?.let { eng.destroySwapChain(it) }
                    swapChain = eng.createSwapChain(surface)
                }
                override fun onDetachedFromSurface() {
                    swapChain?.let { eng.destroySwapChain(it); swapChain = null }
                }
                override fun onResized(width: Int, height: Int) {
                    val aspect = width.toDouble() / height.toDouble()
                    camera!!.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
                    view!!.viewport = Viewport(0, 0, width, height)
                }
            }
            attachTo(surfaceView)
        }

        manipulator = Manipulator.Builder()
            .targetPosition(0f, 0.4f, 0f)
            .orbitHomePosition(2.5f, 1.5f, 2.5f)
            .viewport(800, 800)
            .build(Manipulator.Mode.ORBIT)

        // 方向光 + 环境光
        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.98f, 0.92f, 0.89f)
            .intensity(110_000f)
            .direction(0.7f, -1f, -0.8f)
            .castShadows(true)
            .build(eng, light)
        scene!!.addEntity(light)

        // 摄像机模型(complex object — 7 mesh + LED + PBR)
        val mat = createUnlitMaterial(eng)
        model = FilamentCameraModel(eng, mat, scene!!)

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        val r = renderer ?: return
        val v = view ?: return
        val m = manipulator ?: return
        val cam = camera ?: return
        val mdl = model ?: return
        val getState = stateProvider ?: return

        // 1. dt
        val dt = if (lastFrameNanos == 0L) 0f
                 else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = frameTimeNanos

        // 2. 速率积分 → 累积姿态
        val s = getState()
        panAngle = (panAngle + s.panSpeed * dt).coerceIn(-180f, 180f)
        tiltAngle = (tiltAngle + s.tiltSpeed * dt).coerceIn(-90f, 90f)
        zoomLevel = (zoomLevel + s.zoomSpeed * dt).coerceIn(1f, 16f)

        // 3. 把累积姿态 + 状态灯回写,让 model 渲染
        val effective = s.copy(
            panAngle = panAngle,
            tiltAngle = tiltAngle,
            zoomLevel = zoomLevel
        )
        mdl.updateTransform(effective)

        // 4. Manipulator → Camera lookAt
        val eye = FloatArray(3)
        val target = FloatArray(3)
        val up = FloatArray(3)
        m.getLookAt(eye, target, up)
        cam.lookAt(
            eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(),
            target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
            up[0].toDouble(), up[1].toDouble(), up[2].toDouble()
        )

        if (r.beginFrame(sc, frameTimeNanos)) {
            r.render(v)
            r.endFrame()
        }
    }

    fun detach() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val eng = engine ?: return
        model?.destroy()
        model = null
        if (light != 0) {
            scene?.removeEntity(light)
            EntityManager.get().destroy(light)
            light = 0
        }
        uiHelper?.detach(); uiHelper = null
        swapChain?.let { eng.destroySwapChain(it) }; swapChain = null
        camera?.let { eng.destroyCameraComponent(it.entity) }
        view?.let { eng.destroyView(it) }
        scene?.let { eng.destroyScene(it) }
        renderer?.let { eng.destroyRenderer(it) }
        eng.destroy()
        engine = null
        stateProvider = null
        lastFrameNanos = 0L
    }
}

/**
 * filamat-android 运行时编译 lit material(G2 方案 1).
 *
 * material 源:最小 lit 模型(baseColor + emissive + roughness + metallic).
 * 第一次构造 100-300ms 内完成,之后跑通 surface shading.
 */
private fun createUnlitMaterial(engine: Engine): com.google.android.filament.Material {
    com.google.android.filament.filamat.MaterialBuilder.init()
    val builder = com.google.android.filament.filamat.MaterialBuilder()
        .name("CameraDeviceMat")
        .targetApi(com.google.android.filament.filamat.MaterialBuilder.TargetApi.OPENGL)
        .platform(com.google.android.filament.filamat.MaterialBuilder.Platform.MOBILE)
        .shading(com.google.android.filament.filamat.MaterialBuilder.Shading.LIT)
        .require(com.google.android.filament.filamat.MaterialBuilder.VertexAttribute.TANGENTS)
        .uniformParameter(com.google.android.filament.filamat.MaterialBuilder.UniformType.FLOAT4, "baseColor")
        .uniformParameter(com.google.android.filament.filamat.MaterialBuilder.UniformType.FLOAT3, "emissive")
        .uniformParameter(com.google.android.filament.filamat.MaterialBuilder.UniformType.FLOAT, "metallic")
        .uniformParameter(com.google.android.filament.filamat.MaterialBuilder.UniformType.FLOAT, "roughness")
        .material("""
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = materialParams.baseColor;
                material.emissive = vec4(materialParams.emissive, 1.0);
                material.metallic = materialParams.metallic;
                material.roughness = materialParams.roughness;
            }
        """.trimIndent())
    val pkg = builder.build(java.util.concurrent.Executors.newSingleThreadExecutor())
    val buffer = pkg.buffer
    com.google.android.filament.filamat.MaterialBuilder.shutdown()
    return com.google.android.filament.Material.Builder()
        .payload(buffer, buffer.remaining())
        .build(engine)
}
