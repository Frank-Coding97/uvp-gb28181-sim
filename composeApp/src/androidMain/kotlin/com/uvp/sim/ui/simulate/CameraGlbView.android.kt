package com.uvp.sim.ui.simulate

import android.graphics.BitmapFactory
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.DeviceEffectDto
import com.uvp.sim.ui.model.PtzPoseDto
import com.uvp.sim.ui.UvpColor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android .glb 摄像机视图(C 方案 + 头部局部旋转,2026-06-13 老板真机定位).
 *
 * GLB 内部新增了两个空节点:
 *   PTZ_Yaw_Pivot   = 左右云台父节点 → 带 1/2/3/4 号位水平转动
 *   PTZ_Pitch_Pivot = 上下俯仰父节点 → 带 2 号位上下转动
 *
 * 老板用 debug 自转模式 14:47 确认了 mesh 命名:
 *   Sphere     = 头部主体  → 跟随 PTZ_Yaw_Pivot
 *   Sphere.002 = 镜头/俯仰 → 跟随 PTZ_Pitch_Pivot
 *   Sphere.001 = 外壳/装饰 → 跟随 PTZ_Yaw_Pivot
 *   Cylinder   = 光圈灯    → 挂在 Sphere.002 下,贴着镜头跟随
 *   Plane      = 地面      → 不动
 */
@Composable
actual fun CameraGlbView(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val sceneState = remember { GlbSceneState() }
    val currentState by rememberUpdatedState(state)
    val currentPoseTick by rememberUpdatedState(onPoseTick)
    val thumbnailBitmap = remember(context) { loadAssetImageBitmap(context, "ptz_scene_thumbnail.png") }

    // 订阅 pendingEffect:Reboot 触发自检 / HomePosition+PresetRecall+PrecisePoseGoto 触发 easeTo.
    // 其他 effect(IFrameFlash / SnapshotFlash / ConfigChanged / DeviceUpgrade / FormatSDCard)
    // 由 SimulateScreen 层订阅(全屏闪/角标/snackbar),consumeEffect() 也由 SimulateScreen 兜底清零.
    LaunchedEffect(state.pendingEffect) {
        when (val e = state.pendingEffect) {
            is DeviceEffectDto.Reboot -> sceneState.restartSelfTest()
            is DeviceEffectDto.HomePositionReturn -> sceneState.easeToPose(e.targetPose)
            is DeviceEffectDto.PresetRecall -> sceneState.easeToPose(e.targetPose)
            is DeviceEffectDto.PrecisePoseGoto -> sceneState.easeToPose(e.targetPose)
            else -> {}
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).also { textureView ->
                    sceneState.attach(
                        context = ctx,
                        textureView = textureView,
                        provider = { currentState },
                        poseSink = { p, t, z -> currentPoseTick(p, t, z) }
                    )
                }
            }
        )
        thumbnailBitmap?.let {
            PtzThumbnail(
                bitmap = it,
                pose = sceneState.pose,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { sceneState.detach() }
    }
}

internal class GlbSceneState {
    private var modelViewer: ModelViewer? = null
    private var stateProvider: (() -> DeviceControlDto)? = null
    private var poseSink: ((Float, Float, Float) -> Unit)? = null
    private var lastPoseSinkNanos: Long = 0L
    private var light: Int = 0

    /** PTZ 网格绑定: Sphere / Sphere.002 / Cylinder / Sphere.001 — 跟随 Yaw/Pitch pivot. */
    private val inspectionTargets = mutableListOf<InspectionTarget>()
    private var yawPivot: PtzPivot? = null
    private var pitchPivot: PtzPivot? = null
    /** 模型自带棚拍地面,隐藏后才能露出场景背景色。 */
    private val hiddenEntities = mutableSetOf<Int>()

    private var panAngle = 0f
    private var tiltAngle = 0f
    private var zoomLevel = 1f
    private var lastFrameNanos = 0L

    /** 开机自检: 进入页面后 6.5s 内由代码驱动 pan/tilt,演示左右+上下,完全覆盖平台积分. */
    private var selfTestActive: Boolean = true
    private var selfTestStartNanos: Long = 0L

    /** easeTo 动画: 平台 HomePosition / PresetRecall / PrecisePoseGoto 触发,
     *  从当前 pose 余弦缓动到 target,期间覆盖平台速度积分,完成后由平台命令接管. */
    private var easeAnimActive: Boolean = false
    private var easeAnimStartNanos: Long = 0L
    private var easeAnimDurationMs: Long = 1200L
    private var easeAnimFrom: PtzPoseDto = PtzPoseDto(0f, 0f, 1f)
    private var easeAnimTo: PtzPoseDto = PtzPoseDto(0f, 0f, 1f)

    var pose by mutableStateOf(PtzPoseDto(0f, 0f, 1f))
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            renderFrame(frameTimeNanos)
        }
    }

    fun attach(
        context: android.content.Context,
        textureView: TextureView,
        provider: () -> DeviceControlDto,
        poseSink: (Float, Float, Float) -> Unit = { _, _, _ -> },
    ) {
        Utils.init()
        stateProvider = provider
        this.poseSink = poseSink

        val viewer = ModelViewer(textureView)
        modelViewer = viewer
        viewer.renderer.setClearOptions(
            Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.149, 0.196, 0.220, 1.0)
            }
        )
        // 禁止用户拖动,只展示平台指令驱动的姿态。
        textureView.setOnTouchListener { _, _ -> true }

        val buf = readAsset(context, "security_camera.glb")
        viewer.loadModelGlb(buf)
        viewer.transformToUnitCube()

        viewer.scene.skybox = null
        viewer.scene.indirectLight = IndirectLight.Builder()
            .intensity(45_000f)
            .irradiance(1, floatArrayOf(0.74f, 0.82f, 0.92f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
            .build(viewer.engine)

        light = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.96f, 0.98f, 1f).intensity(75_000f)
            .direction(0.3f, -0.8f, -0.5f).castShadows(false)
            .build(viewer.engine, light)
        viewer.scene.addEntity(light)

        viewer.asset?.let { asset ->
            val discoveredTargets = mutableMapOf<String, InspectionTarget>()
            val tm = viewer.engine.transformManager
            for (e in asset.entities) {
                val n = asset.getName(e) ?: continue
                if (n.startsWith("Plane")) {
                    hiddenEntities += e
                    hideEntity(viewer, e)
                    continue
                }
                if (n == "PTZ_Background_Plane") {
                    hiddenEntities += e
                    hideEntity(viewer, e)
                    continue
                }
                val ti = tm.getInstance(e)
                if (ti == 0) continue
                if (n == "PTZ_Yaw_Pivot") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    yawPivot = PtzPivot(n, e, initM)
                }
                if (n == "PTZ_Pitch_Pivot") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    pitchPivot = PtzPivot(n, e, initM)
                }
                if (n == "Sphere") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    discoveredTargets[n] = InspectionTarget(
                        name = n,
                        friendlyName = "头部主体",
                        axis = InspectionAxis.YawZ,
                        entity = e,
                        initialTransform = initM
                    )
                }
                if (n == "Sphere.002") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    discoveredTargets[n] = InspectionTarget(
                        name = n,
                        friendlyName = "镜头/俯仰",
                        axis = InspectionAxis.PitchX,
                        entity = e,
                        initialTransform = initM
                    )
                }
                if (n == "Cylinder") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    discoveredTargets[n] = InspectionTarget(
                        name = n,
                        friendlyName = "光圈灯",
                        axis = InspectionAxis.RollY,
                        entity = e,
                        initialTransform = initM
                    )
                }
                if (n == "Sphere.001") {
                    val initM = FloatArray(16)
                    tm.getTransform(ti, initM)
                    discoveredTargets[n] = InspectionTarget(
                        name = n,
                        friendlyName = "外壳/装饰",
                        axis = InspectionAxis.RollX,
                        entity = e,
                        initialTransform = initM
                    )
                }
            }

            // 固定巡检编号,避免 glTF 内部实体顺序变化影响 PTZ 映射。
            listOf("Sphere", "Sphere.002", "Sphere.001", "Cylinder").forEach { name ->
                discoveredTargets[name]?.let { inspectionTargets += it }
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
        if (selfTestActive) {
            if (selfTestStartNanos == 0L) selfTestStartNanos = frameTimeNanos
            val tSec = (frameTimeNanos - selfTestStartNanos) / 1e9f
            val (testPan, testTilt, done) = selfTestSample(tSec)
            panAngle = testPan
            tiltAngle = testTilt
            zoomLevel = 1f
            if (done) selfTestActive = false
        } else if (easeAnimActive) {
            if (easeAnimStartNanos == 0L) easeAnimStartNanos = frameTimeNanos
            val tProg = ((frameTimeNanos - easeAnimStartNanos) / 1e6f) / easeAnimDurationMs.toFloat()
            if (tProg >= 1f) {
                panAngle = easeAnimTo.pan
                tiltAngle = easeAnimTo.tilt
                zoomLevel = easeAnimTo.zoom
                easeAnimActive = false
            } else {
                val eased = 0.5f - 0.5f * cos(tProg.coerceIn(0f, 1f) * PI.toFloat())
                panAngle  = easeAnimFrom.pan  + (easeAnimTo.pan  - easeAnimFrom.pan)  * eased
                tiltAngle = easeAnimFrom.tilt + (easeAnimTo.tilt - easeAnimFrom.tilt) * eased
                zoomLevel = easeAnimFrom.zoom + (easeAnimTo.zoom - easeAnimFrom.zoom) * eased
            }
        } else {
            panAngle = (panAngle + s.panSpeed * dt).coerceIn(-180f, 180f)
            tiltAngle = (tiltAngle + s.tiltSpeed * dt).coerceIn(-90f, 90f)
            zoomLevel = (zoomLevel + s.zoomSpeed * dt).coerceIn(1f, 16f)
        }
        pose = PtzPoseDto(panAngle, tiltAngle, zoomLevel)

        val tm = viewer.engine.transformManager
        val panTransform = inspectionRotation(InspectionAxis.YawZ, panAngle)
        val tiltTransform = inspectionRotation(InspectionAxis.PitchX, tiltAngle)
        val currentYawPivot = yawPivot
        val currentPitchPivot = pitchPivot

        if (currentYawPivot != null && currentPitchPivot != null) {
            // 正式 PTZ 绑定走 GLB 父子层级:
            // 左右 Pan: PTZ_Yaw_Pivot 带 1/2/3/4; 上下 Tilt: PTZ_Pitch_Pivot 带 2,4 号件作为 2 的子节点贴紧镜头。
            for (target in inspectionTargets) {
                val ti = tm.getInstance(target.entity)
                if (ti != 0) {
                    tm.setTransform(ti, target.initialTransform)
                }
            }
            tm.setTransform(
                tm.getInstance(currentYawPivot.entity),
                mat4Multiply(currentYawPivot.initialTransform, panTransform)
            )
            tm.setTransform(
                tm.getInstance(currentPitchPivot.entity),
                mat4Multiply(currentPitchPivot.initialTransform, tiltTransform)
            )
        } else {
            // 兜底: 旧模型没有 PTZ 空节点时,仍按巡检编号近似绑定。
            for ((index, target) in inspectionTargets.withIndex()) {
                val ti = tm.getInstance(target.entity)
                if (ti == 0) continue
                val number = index + 1
                val transform = when {
                    number == 1 || number == 3 -> mat4Multiply(target.initialTransform, panTransform)
                    number == 2 || number == 4 -> mat4Multiply(
                        mat4Multiply(target.initialTransform, panTransform),
                        tiltTransform
                    )
                    else -> target.initialTransform
                }
                tm.setTransform(ti, transform)
            }
        }

        viewer.render(frameTimeNanos)
        for (entity in hiddenEntities) {
            hideEntity(viewer, entity)
        }

        // 节流回写 pose 到 AppEngine.deviceControlState(每 ~166ms 一次).
        // 这样平台 SetPreset 取 state.panAngle/tiltAngle/zoomLevel 时能拿到真实姿态.
        if (frameTimeNanos - lastPoseSinkNanos > 166_000_000L) {
            poseSink?.invoke(panAngle, tiltAngle, zoomLevel)
            lastPoseSinkNanos = frameTimeNanos
        }
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
        poseSink = null
        lastPoseSinkNanos = 0L
        inspectionTargets.clear(); hiddenEntities.clear()
        yawPivot = null
        pitchPivot = null
        lastFrameNanos = 0L
        easeAnimActive = false
    }

    /** TeleBoot effect 触发: 重新跑开机自检序列(跟首次进页面动画完全一致). */
    fun restartSelfTest() {
        selfTestActive = true
        selfTestStartNanos = 0L
        // 互斥: 自检覆盖 easeAnim
        easeAnimActive = false
    }

    /** HomePosition / PresetRecall / PrecisePoseGoto effect 触发: 平滑过渡到 target.
     *  自检期间忽略(等自检结束再让平台命令接管). */
    fun easeToPose(target: PtzPoseDto, durationMs: Long = 1200L) {
        if (selfTestActive) return
        easeAnimFrom = PtzPoseDto(panAngle, tiltAngle, zoomLevel)
        easeAnimTo = target
        easeAnimDurationMs = durationMs
        easeAnimStartNanos = 0L
        easeAnimActive = true
    }

    private fun readAsset(context: android.content.Context, name: String): ByteBuffer {
        context.assets.open(name).use { stream ->
            val bytes = stream.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes); buf.flip(); return buf
        }
    }

    private fun hideEntity(viewer: ModelViewer, entity: Int) {
        val rcm = viewer.engine.renderableManager
        val ri = rcm.getInstance(entity)
        if (ri != 0) {
            rcm.setLayerMask(ri, 0xFF, 0x00)
        }
        viewer.scene.removeEntity(entity)
    }

    private fun inspectionRotation(axis: InspectionAxis, angleDeg: Float): FloatArray {
        val rad = angleDeg * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        return when (axis) {
            InspectionAxis.YawZ -> FloatArray(16).apply {
                this[0] = c; this[1] = s
                this[4] = -s; this[5] = c
                this[10] = 1f; this[15] = 1f
            }
            InspectionAxis.PitchX -> FloatArray(16).apply {
                this[0] = 1f
                this[5] = c; this[6] = -s
                this[9] = s; this[10] = c
                this[15] = 1f
            }
            InspectionAxis.RollX -> FloatArray(16).apply {
                this[0] = 1f
                this[5] = c; this[6] = -s
                this[9] = s; this[10] = c
                this[15] = 1f
            }
            InspectionAxis.RollY -> FloatArray(16).apply {
                this[0] = c; this[2] = -s
                this[8] = s; this[10] = c
                this[15] = 1f
            }
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

}

private enum class InspectionAxis {
    YawZ,
    PitchX,
    RollX,
    RollY
}

/**
 * 开机自检时间线: 左右 → 上下 → 落定. 返回 (pan°, tilt°, done).
 * 段位 (秒):
 *   0.0 ~ 0.5  停顿
 *   0.5 ~ 1.5  pan 0 → -50 (左)
 *   1.5 ~ 2.5  pan -50 → +50 (右)
 *   2.5 ~ 3.0  pan +50 → 0
 *   3.0 ~ 3.5  停顿
 *   3.5 ~ 4.5  tilt 0 → +25 (上)
 *   4.5 ~ 5.5  tilt +25 → -25 (下)
 *   5.5 ~ 6.0  tilt -25 → 0
 *   ≥ 6.0      done
 */
private data class SelfTestSample(val pan: Float, val tilt: Float, val done: Boolean)

private fun selfTestSample(t: Float): SelfTestSample {
    val panAmp = 50f
    val tiltAmp = 25f
    val pan: Float
    val tilt: Float
    when {
        t < 0.5f -> { pan = 0f; tilt = 0f }
        t < 1.5f -> { pan = lerpEase(0f, -panAmp, (t - 0.5f) / 1.0f); tilt = 0f }
        t < 2.5f -> { pan = lerpEase(-panAmp, panAmp, (t - 1.5f) / 1.0f); tilt = 0f }
        t < 3.0f -> { pan = lerpEase(panAmp, 0f, (t - 2.5f) / 0.5f); tilt = 0f }
        t < 3.5f -> { pan = 0f; tilt = 0f }
        t < 4.5f -> { pan = 0f; tilt = lerpEase(0f, tiltAmp, (t - 3.5f) / 1.0f) }
        t < 5.5f -> { pan = 0f; tilt = lerpEase(tiltAmp, -tiltAmp, (t - 4.5f) / 1.0f) }
        t < 6.0f -> { pan = 0f; tilt = lerpEase(-tiltAmp, 0f, (t - 5.5f) / 0.5f) }
        else -> return SelfTestSample(0f, 0f, done = true)
    }
    return SelfTestSample(pan, tilt, done = false)
}

/** 余弦缓动 — 起步慢/到位慢,模拟伺服电机. */
private fun lerpEase(from: Float, to: Float, phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    val eased = 0.5f - 0.5f * cos(p * PI.toFloat())
    return from + (to - from) * eased
}

private data class InspectionTarget(
    val name: String,
    val friendlyName: String,
    val axis: InspectionAxis,
    val entity: Int,
    val initialTransform: FloatArray
)

private data class PtzPivot(
    val name: String,
    val entity: Int,
    val initialTransform: FloatArray
)

@Composable
private fun PtzThumbnail(
    bitmap: ImageBitmap,
    pose: PtzPoseDto,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(1.dp, UvpColor.BorderLight)
    ) {
        // 外框 136×78,内 padding 2dp → 内容区 132×74 ≈ 16:9,严格匹配图 1600×900,
        // ContentScale.Crop 无额外裁切,平移到 clamp 边界视觉刚好对齐 view 边缘(消边缘 gap)。
        Box(
            modifier = Modifier
                .width(136.dp)
                .height(78.dp)
                .padding(2.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val scale = (1.16f + (pose.zoom - 1f) * 0.08f).coerceIn(1.16f, 1.55f)
                val maxX = widthPx * (scale - 1f) / 2f
                val maxY = heightPx * (scale - 1f) / 2f
                val x = (-pose.pan / 180f * maxX * 1.6f).coerceIn(-maxX, maxX)
                val y = (pose.tilt / 90f * maxY * 1.6f).coerceIn(-maxY, maxY)

                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = x
                            translationY = y
                        }
                )
            }
        }
    }
}

private fun loadAssetImageBitmap(context: android.content.Context, name: String): ImageBitmap? {
    return context.assets.open(name).use { stream ->
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }
}
