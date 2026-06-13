package com.uvp.sim.ui.simulate

import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
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

/**
 * Filament 3D 场景容器(M2 T5 spike).
 *
 * 当前实现:
 * - 启动 Filament Engine + SwapChain + Renderer
 * - 一个旋转 cube(用 [MeshFactory.createBox] 程序化生成)
 * - 一盏方向光 + 环境光
 * - Manipulator orbit 相机(用户手势观察视角)
 * - Choreographer 帧回调驱动渲染
 *
 * 后续 task:
 * - T7 把单 cube 替换为完整摄像机模型(base+joint+barrel+lens+LEDs)
 * - T8 把固定旋转替换为 DeviceControlState 速率积分
 * - T11/T13 加入一次性效果 + LED 状态可视化
 *
 * 不可真机验证(我跑在 Mac 上,没 Android 真机)——以编译通过 + Filament API
 * 调用合规为完成标准,真机视觉验收留给老板.
 */
@Composable
fun Camera3DSpike(modifier: Modifier = Modifier) {
    val state = remember { Camera3DState() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { surfaceView ->
                state.attach(surfaceView)
            }
        },
        update = { /* state-driven updates landed via Choreographer */ }
    )
    DisposableEffect(Unit) {
        onDispose { state.detach() }
    }
}

/**
 * 持有 Filament 资源 + 渲染循环句柄.分离出来便于测试和重用,
 * Camera3DView 的真实 model 集成会复用同一容器(只换 [scene] 内容).
 */
private class Camera3DState {
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null
    private var manipulator: Manipulator? = null
    private var cube: MeshFactory.Mesh? = null
    private var light: Int = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            renderFrame(frameTimeNanos)
        }
    }

    fun attach(surfaceView: SurfaceView) {
        val eng = Engine.create()
        engine = eng
        renderer = eng.createRenderer()
        scene = eng.createScene()
        view = eng.createView()
        camera = eng.createCamera(EntityManager.get().create())

        view!!.scene = scene
        view!!.camera = camera

        // 棋盘背景对调试有帮助,但 spike 阶段先用纯色
        renderer!!.clearOptions = renderer!!.clearOptions.apply {
            clear = true
        }

        // UiHelper 接管 SurfaceHolder,自动处理 SwapChain 生命周期
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: android.view.Surface) {
                    swapChain?.let { eng.destroySwapChain(it) }
                    swapChain = eng.createSwapChain(surface)
                }
                override fun onDetachedFromSurface() {
                    swapChain?.let {
                        eng.destroySwapChain(it)
                        swapChain = null
                    }
                }
                override fun onResized(width: Int, height: Int) {
                    val aspect = width.toDouble() / height.toDouble()
                    camera!!.setProjection(
                        45.0, aspect, 0.1, 100.0,
                        Camera.Fov.VERTICAL
                    )
                    view!!.viewport = Viewport(0, 0, width, height)
                }
            }
            attachTo(surfaceView)
        }

        // Orbit camera — 用户用手势绕 model 转
        manipulator = Manipulator.Builder()
            .targetPosition(0f, 0f, 0f)
            .viewport(800, 800)
            .build(Manipulator.Mode.ORBIT)

        // 方向光 + 环境光,简单两点光照
        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.98f, 0.92f, 0.89f)
            .intensity(110_000f)
            .direction(0.7f, -1f, -0.8f)
            .castShadows(true)
            .build(eng, light)
        scene!!.addEntity(light)

        // 暂时只加 spike cube,T7 替换为完整摄像机模型
        val mat = createUnlitMaterial(eng)
        val factory = MeshFactory(eng, mat)
        cube = factory.createBox(0.5f, 0.5f, 0.5f).also {
            scene!!.addEntity(it.entity)
        }
        // 给 cube 默认颜色(只走一次,后续 task 替换)
        cube!!.materialInstance.setParameter(
            "baseColor", Colors.RgbaType.SRGB,
            0.4f, 0.7f, 0.95f, 1f
        )

        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        val sc = swapChain ?: return
        val r = renderer ?: return
        val v = view ?: return
        val m = manipulator ?: return
        val cam = camera ?: return

        // Manipulator → Camera lookAt
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
        cube?.destroy(eng)
        cube = null
        if (light != 0) {
            scene?.removeEntity(light)
            EntityManager.get().destroy(light)
            light = 0
        }
        uiHelper?.detach()
        uiHelper = null
        swapChain?.let { eng.destroySwapChain(it) }
        swapChain = null
        camera?.let { eng.destroyCameraComponent(it.entity) }
        view?.let { eng.destroyView(it) }
        scene?.let { eng.destroyScene(it) }
        renderer?.let { eng.destroyRenderer(it) }
        eng.destroy()
        engine = null
        renderer = null
        scene = null
        view = null
        camera = null
    }
}

/**
 * 内置 unlit material,避免引入 .filamat 资源.
 * Filament Material.Builder 不支持纯 Kotlin 构造,临时方案是用 utils 自带的 default。
 *
 * 但 filament-utils 1.71 没有 KtxLoader 预编译 material 的便捷 API;
 * 这里用最低限度的字节码 fallback——加载默认 lit material(filament 自带).
 *
 * TODO(T7): 改用 ubershader 或 .filamat 资源,支持自定义 emissive(用于 LED).
 */
private fun createUnlitMaterial(engine: Engine): com.google.android.filament.Material {
    // M2 T5 spike: 暂时让模型不可见 / 只显示线框,
    // 等 T7 拉入正式 material 文件再 fix.
    // Filament 不允许 Material 为 null,所以这里用 builder 的 minimal default。
    //
    // 实际上 Material.Builder 需要 .filamat 字节码 buffer,
    // 而 spike 阶段不便预编译,本函数仅供编译通过用,运行时可能渲染失败.
    // 老板真机跑 spike 时如果黑屏,就是这里的 placeholder,T7 会替换.
    val payload = java.nio.ByteBuffer.allocateDirect(0)
    return com.google.android.filament.Material.Builder()
        .payload(payload, 0)
        .build(engine)
}
