package com.uvp.sim.ui.simulate.scenekit

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.SceneKit.SCNBox
import platform.SceneKit.SCNCylinder
import platform.SceneKit.SCNLight
import platform.SceneKit.SCNLightTypeAmbient
import platform.SceneKit.SCNLightTypeDirectional
import platform.SceneKit.SCNMaterial
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNSphere
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNView
import platform.UIKit.UIColor

/**
 * iOS v1.3-C · SceneKit 3D 摄像机场景容器(模块 B).
 *
 * 职责:
 * - 优先加载 `security_camera.usdz`(主 bundle,GLB 转出),兜底 `security_camera.scn`,再兜底纯代码占位
 * - 绑定 [panPivot] = GLB 内 `PTZ_Yaw_Pivot` / [tiltPivot] = `PTZ_Pitch_Pivot`
 *   GLB 内无 zoom pivot,由 [bindPivots] 自动插一层 zoom_wrapper 空节点作为 [zoomPivot]
 * - 隐藏 GLB 内 `PTZ_Background_Plane` / `Plane*` 让场景背景色露出(对齐 Android)
 * - 挂 SCNView(attach)→ pause/resume/detach 生命周期
 *
 * 非目标:
 * - 不消费 [com.uvp.sim.ui.model.DeviceEffectDto](交给 dispatcher)
 * - 不感知 Compose(纯 iOS SDK 层)
 *
 * 线程模型:
 * - 所有方法在主线程调用(Compose UI + UIKit 主线程约束)
 * - 不 own 任何 kotlinx.coroutines.CoroutineScope
 */
@OptIn(ExperimentalForeignApi::class)
class SceneKitCameraScene {

    private var scnScene: SCNScene? = null
    var scnView: SCNView? = null
        private set

    /** 场景 root(loadFromBundle 成功后非空). */
    var rootNode: SCNNode? = null
        private set

    /** T-C1-3: PTZ pivot 三节点引用. bindPivots() 后填充. */
    var panPivot: SCNNode? = null
        private set
    var tiltPivot: SCNNode? = null
        private set
    var zoomPivot: SCNNode? = null
        private set

    /** T-C1-3: 灯光节点(setupLightsAndCamera 后填充). */
    var keyLight: SCNNode? = null
        private set
    var ambientLight: SCNNode? = null
        private set
    var mainCamera: SCNNode? = null
        private set

    /** T-C4-2: 性能等级(L0/L1),外部可读. */
    var performanceLevel: Int = 0
        private set

    /** 记录是否走了 fallback 占位(单测用). */
    var isFallback: Boolean = false
        private set

    /**
     * 加载主 bundle 内 3D 模型.优先 `<resourceName>.usdz`(GLB 转 USDZ,与 Android 同源),
     * 兜底 `<resourceName>.scn`(手工 dae→scn 转出),都缺则回落纯代码占位场景.
     *
     * SCNScene.sceneNamed 支持 usdz / scn / dae 三种扩展名.
     *
     * @return 无论走真实模型还是 fallback,只要 rootNode 建起来都返回 true.
     */
    fun loadFromBundle(resourceName: String = "security_camera"): Boolean {
        // 先显式拿 bundle URL 再加载，避免 sceneNamed 在不同 iOS 版本/包结构下偶发返回
        // 一个没有完整几何的 scene。sceneWithURL 是同步加载，返回后才允许 UIKitView attach。
        val bundle = NSBundle.mainBundle
        val url: NSURL? = bundle.URLForResource(resourceName, withExtension = "usdz")
        SystemLogger.emit(LogLevel.Debug, LogTag.Resource, "SCENEKIT_BUNDLE_URL=$url path=${bundle.bundlePath}")
        if (url != null) {
            val loaded = runCatching {
                memScoped {
                    val err = alloc<kotlinx.cinterop.ObjCObjectVar<NSError?>>()
                    SCNScene.sceneWithURL(url, options = null, error = err.ptr)
                }
            }.onFailure { SystemLogger.emit(LogLevel.Warning, LogTag.Resource, "SCENEKIT_SCENE_WITH_URL_THREW: $it") }
                .getOrNull()
            SystemLogger.emit(LogLevel.Debug, LogTag.Resource, "SCENEKIT_SCENE_WITH_URL(usdz)=$loaded")
            if (loaded != null && adoptScene(loaded, tag = "sceneWithURL-usdz")) {
                return true
            }
        } else {
            // usdz 不在 bundle 说明 xcodegen / project.yml 没把资源打进去,直接 fallback.
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Resource,
                "SCENEKIT_USDZ_MISSING(security_camera.usdz not in main bundle, check project.yml Resources)"
            )
        }

        // 兼容旧 .scn 资源。若 USDZ 能打开但没有可渲染几何,也继续走这里。
        val scn: SCNScene? = SCNScene.sceneNamed("$resourceName.scn")
        SystemLogger.emit(LogLevel.Debug, LogTag.Resource, "SCENEKIT_SCENE_NAMED(scn)=$scn")
        if (scn != null && adoptScene(scn, tag = "sceneNamed-scn")) {
            return true
        }

        SystemLogger.emit(LogLevel.Info, LogTag.Resource, "SCENEKIT_FALLBACK_PROCEDURAL")
        return loadFallbackScene()
    }

    /**
     * load 路径共用: 只接受确实包含几何的 scene，避免 SceneKit 解析失败时挂上空场景。
     * 返回 false 时调用方会继续尝试下一个资源/程序化 fallback。
     */
    private fun adoptScene(scene: SCNScene, tag: String): Boolean {
        val geometryCount = countRenderableGeometry(scene.rootNode)
        if (geometryCount == 0) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Resource, "SCENEKIT_ADOPT_REJECTED tag=$tag reason=no_geometry")
            return false
        }
        scnScene = scene
        rootNode = scene.rootNode
        isFallback = false
        // 对齐 Android CameraGlbView.android.kt 里的 filament 配置:
        //   irradiance SH0 = (0.74, 0.82, 0.92) — 冷调蓝白, intensity 45000
        // SceneKit 侧 lightingEnvironment.contents 支持 UIColor 作简易均匀环境,
        // 相当于常数 SH0, 视觉观感接近 filament 那种冷色调白塑料反射.
        scene.lightingEnvironment.contents = UIColor.colorWithRed(
            0.74, green = 0.82, blue = 0.92, alpha = 1.0
        )
        scene.lightingEnvironment.intensity = 2.0
        val children = scene.rootNode.childNodes
        SystemLogger.emit(
            LogLevel.Info, LogTag.Resource,
            "SCENEKIT_ADOPT_OK tag=$tag rootChildren=${children.size} geometryCount=$geometryCount"
        )
        return true
    }

    private fun countRenderableGeometry(node: SCNNode): Int {
        val own = if (node.geometry != null && !node.hidden) 1 else 0
        return own + node.childNodes.sumOf { child ->
            (child as? SCNNode)?.let(::countRenderableGeometry) ?: 0
        }
    }

    /**
     * fallback 场景:一个 SCNBox 机身 + SCNCylinder 镜筒 + 3 个空 SCNNode 作为 pivot.
     * 保证 scn 缺失时也能跑 dispatcher 单测 / 真机看到可辨识的 3D 物体.
     */
    private fun loadFallbackScene(): Boolean {
        val scene = SCNScene()
        val root = scene.rootNode

        // 机身: 深灰色 box (0.6 x 0.4 x 0.4)
        val bodyBox = SCNBox.boxWithWidth(0.6, 0.4, 0.4, 0.02)
        val bodyMat = SCNMaterial().apply {
            diffuse.contents = UIColor.darkGrayColor
        }
        bodyBox.setMaterials(listOf(bodyMat))
        val bodyNode = SCNNode.nodeWithGeometry(bodyBox).apply { name = "body" }

        // 镜筒: 白色 cylinder (radius 0.12, height 0.35), 沿 z 前突
        val lensCyl = SCNCylinder.cylinderWithRadius(0.12, 0.35)
        val lensMat = SCNMaterial().apply {
            diffuse.contents = UIColor.lightGrayColor
        }
        lensCyl.setMaterials(listOf(lensMat))
        val lensNode = SCNNode.nodeWithGeometry(lensCyl).apply {
            name = "camera_lens"
            // Cylinder 默认沿 Y 轴, 旋转 90 度到 Z 轴前指
            eulerAngles = SCNVector3Make(1.5707964f, 0f, 0f)
            position = SCNVector3Make(0f, 0f, 0.3f)
        }

        // 3 pivot 层级: pan → tilt → zoom → 镜筒
        val panN = SCNNode().apply { name = "pan_pivot" }
        val tiltN = SCNNode().apply { name = "tilt_pivot" }
        val zoomN = SCNNode().apply { name = "zoom_pivot" }

        zoomN.addChildNode(lensNode)
        tiltN.addChildNode(zoomN)
        panN.addChildNode(tiltN)
        panN.addChildNode(bodyNode)  // 机身跟随 pan 但不跟 tilt/zoom
        root.addChildNode(panN)

        scnScene = scene
        rootNode = root
        isFallback = true
        return true
    }

    /**
     * T-C1-3: 从场景图查找 3 pivot 节点(按 spec 命名 pan_pivot / tilt_pivot / zoom_pivot).
     * fallback 场景已保证节点在;真 scn 需 Blender 转换时命名一致.
     *
     * @return 三 pivot 都找到返回 true, 否则 false(降级到 root 旋转).
     */
    fun bindPivots(): Boolean {
        val root = rootNode ?: return false

        // 优先按 GLB(→USDZ)真实命名找;找不到再退回 fallback 场景的 pan_pivot/tilt_pivot/zoom_pivot.
        val yaw = root.childNodeWithName("PTZ_Yaw_Pivot", recursively = true)
            ?: root.childNodeWithName("pan_pivot", recursively = true)
        val pitch = root.childNodeWithName("PTZ_Pitch_Pivot", recursively = true)
            ?: root.childNodeWithName("tilt_pivot", recursively = true)
        panPivot = yaw
        tiltPivot = pitch

        // GLB 里没有 zoom pivot(Android 侧 zoom 走 Compose graphicsLayer,不动 3D 场景),
        // iOS dispatcher.syncPtz 仍用 SCNAction 平移 zoomPivot.position.z 表达拉近拉远;
        // 缺失时,在 yaw 之上插一层空 SCNNode 作 zoom_wrapper,dispatcher 现有逻辑不用改.
        zoomPivot = root.childNodeWithName("zoom_pivot", recursively = true)
            ?: wrapZoomPivotAround(yaw)

        hideBackgroundPlanes(root)

        // 现实约束 (2026-07-09 老板前后 3 次诊断确认):
        //   usdz UsdPreviewSurface material 在 SceneKit 上加载后 diffuse.contents = nil,
        //   走 shader graph 引用, PBR pipeline 也拿不到 UIColor. 唯一能显示的方法是
        //   重建 SCNMaterial 强制覆盖 diffuse. 尝试"保留原 PBR + IBL"路线全部白屏,
        //   不再走这条. Android filament 侧不同, 那里 UsdPreviewSurface 是原生支持的.
        // 按 mesh 名字重建材质, 区分镜头筒(深灰) vs 外壳(白塑料).
        rebuildMaterialsByMeshName(root)

        return panPivot != null && tiltPivot != null && zoomPivot != null
    }

    /**
     * 按 mesh 节点名字重建 SCNMaterial. usdz 里已知 mesh 命名:
     *   Sphere001 / Sphere001__0 → 头部半球(外壳白)
     *   Sphere / Sphere__0 → 脖颈球(外壳白)
     *   Sphere002 / Sphere002__0 → 底部球体(外壳白)
     *   Cylinder / Cylinder__0 → 镜头筒(深灰 + 高反光)
     * 其他 fallback 走白塑料.
     */
    private fun rebuildMaterialsByMeshName(node: SCNNode) {
        node.geometry?.let { geom ->
            val name = node.name ?: ""
            val mat = when {
                name.contains("Cylinder") -> lensMaterial()
                else -> shellMaterial()
            }
            geom.setMaterials(listOf(mat))
        }
        node.childNodes.forEach { child ->
            (child as? SCNNode)?.let { rebuildMaterialsByMeshName(it) }
        }
    }

    /** 白塑料外壳:PBR lightingModel + IBL 支撑, 才有明显反光高光. */
    private fun shellMaterial(): SCNMaterial = SCNMaterial().apply {
        diffuse.contents = UIColor.colorWithWhite(0.95, alpha = 1.0)
        metalness.contents = UIColor.colorWithWhite(0.05, alpha = 1.0)
        roughness.contents = UIColor.colorWithWhite(0.35, alpha = 1.0)
        lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
        doubleSided = true
    }

    /** 镜头筒:深灰 + 更光滑, 让镜头面反光明显. */
    private fun lensMaterial(): SCNMaterial = SCNMaterial().apply {
        diffuse.contents = UIColor.colorWithWhite(0.12, alpha = 1.0)
        metalness.contents = UIColor.colorWithWhite(0.35, alpha = 1.0)
        roughness.contents = UIColor.colorWithWhite(0.15, alpha = 1.0)
        lightingModelName = platform.SceneKit.SCNLightingModelPhysicallyBased
        doubleSided = true
    }

    /**
     * 在 [target] 与其原父节点之间插入一个 `zoom_wrapper` 空节点,让 dispatcher 通过
     * `zoomPivot.position.z` 平移整个 pan/tilt 子树来实现 zoom 视觉.
     * target 为 null 或已在 zoom_wrapper 下时返回既有 wrapper.
     */
    private fun wrapZoomPivotAround(target: SCNNode?): SCNNode? {
        if (target == null) return null
        val parent = target.parentNode ?: return null
        if (parent.name == "zoom_wrapper") return parent
        val wrapper = SCNNode().apply { name = "zoom_wrapper" }
        target.removeFromParentNode()
        wrapper.addChildNode(target)
        parent.addChildNode(wrapper)
        return wrapper
    }

    /**
     * 隐藏 GLB 内 sketchfab 自带的地面 / 背景板(`Plane` / `PTZ_Background_Plane`),
     * 让 SCNView.backgroundColor(Compose 侧 UvpColor.PrimaryLight)透出来,与 Android 观感一致.
     */
    private fun hideBackgroundPlanes(root: SCNNode) {
        val candidates = listOf("Plane", "Plane__0", "PTZ_Background_Plane")
        for (name in candidates) {
            root.childNodeWithName(name, recursively = true)?.hidden = true
        }
    }

    /**
     * T-C1-3: 建 key light + ambient light + camera node.
     * fallback 场景本身无光,直接建;真 scn 里通常已带光,重复挂无害.
     */
    fun setupLightsAndCamera() {
        val root = rootNode ?: return

        // Key light — 对齐 Android filament DirectionalLight:
        //   color (0.96, 0.98, 1.0), intensity 75000, direction (0.3, -0.8, -0.5).
        // SceneKit directional light intensity 单位是 lumen, 白 sunlight ~1000-2000.
        val keyL = SCNLight().apply {
            type = SCNLightTypeDirectional
            color = UIColor.colorWithRed(0.96, green = 0.98, blue = 1.0, alpha = 1.0)
            intensity = 1500.0
        }
        // 方向向量 (0.3, -0.8, -0.5) 单位化后近似指向右下前方; SceneKit 里 light node
        // eulerAngles 让 -Z 轴指向 direction, 从 (0.3, -0.8, -0.5) 反推:
        //   yaw = atan2(0.3, -0.5) ≈ 2.6 rad (149°) — 关键光从右上后方照
        //   pitch = asin(-(-0.8)) = -asin(0.8) ≈ -0.93 rad
        // 直接用 look-at 方式建光节点: 放在方向反向的 3 米处, 瞄准原点.
        val keyNode = SCNNode().apply {
            name = "key_light"
            light = keyL
            position = SCNVector3Make(-0.9f, 2.4f, 1.5f)
        }
        val keyTarget = SCNNode().apply {
            name = "key_light_target"
            position = SCNVector3Make(0f, 0f, 0f)
        }
        root.addChildNode(keyTarget)
        val keyLookAt = platform.SceneKit.SCNLookAtConstraint.lookAtConstraintWithTarget(keyTarget)
        keyNode.constraints = listOf(keyLookAt)
        root.addChildNode(keyNode)
        keyLight = keyNode

        // Ambient — filament irradiance SH 已经提供均匀底光, SceneKit 侧简单挂个低强度即可.
        val ambL = SCNLight().apply {
            type = SCNLightTypeAmbient
            color = UIColor.colorWithRed(0.85, green = 0.88, blue = 0.95, alpha = 1.0)
            intensity = 200.0
        }
        val ambNode = SCNNode().apply {
            name = "ambient_light"
            light = ambL
        }
        root.addChildNode(ambNode)
        ambientLight = ambNode

        // Camera. USDZ 里 sketchfab_model 有多层 xform (Y-Z swap + 0.01× + 100× 累积),
        // 手工估相机距离容易失准. 改用 boundingSphere 自动定位:
        //   相机放在 (cx, cy, cz + r / tan(fov/2) * 1.3)  → 保证整体入画且留边距.
        // fov 60° → tan(30°) ≈ 0.577.
        val (center, radius) = computeSceneBounds(root)
        val fovDeg = 60.0
        val fovHalfRad = fovDeg * 0.5 * kotlin.math.PI / 180.0
        val distance = (radius / kotlin.math.tan(fovHalfRad)) * 1.3
        SystemLogger.emit(
            LogLevel.Debug, LogTag.Resource,
            "SCENEKIT_CAMERA_FIT center=(${center.x},${center.y},${center.z}) r=$radius dist=$distance"
        )

        val cam = platform.SceneKit.SCNCamera().apply {
            zNear = kotlin.math.max(0.01, radius * 0.01)
            zFar = kotlin.math.max(100.0, radius * 10.0)
            fieldOfView = fovDeg
            automaticallyAdjustsZRange = true
        }
        // 摄像机姿态对齐 Android: 半球顶朝观察者方向(即 +Y_scenekit, 因为 usdz Y-Z swap
        // 后模型的"顶"落在 +Y 一侧, 早期红球截图 (0,cy,cz+dist) 显示的顶视图 == 头部半球).
        //   Android 那种"半球在上、镜头朝斜前下方"的观感:
        //   相机水平位在模型侧前方, 略高于中心, 从 (0,cy,cz+dist) 起绕 X 轴向上抬起 60°.
        // 用球坐标: azimuth=0 (纯前方), elevation=60° (从上往下俯 60°) — 让相机基本
        // 位于模型正上方偏后, 看下去时半球朝观察者、镜头朝画面下方.
        val el = 55.0 * kotlin.math.PI / 180.0
        val camX = center.x
        val camY = center.y + (distance * kotlin.math.sin(el)).toFloat()
        val camZ = center.z + (distance * kotlin.math.cos(el)).toFloat()
        val camNode = SCNNode().apply {
            name = "main_camera"
            camera = cam
            position = SCNVector3Make(camX, camY, camZ)
        }
        root.addChildNode(camNode)
        mainCamera = camNode

        // LookAt anchor 放在包围球中心.
        val target = SCNNode().apply {
            name = "camera_anchor"
            position = SCNVector3Make(center.x, center.y, center.z)
        }
        root.addChildNode(target)
        val lookAt = platform.SceneKit.SCNLookAtConstraint.lookAtConstraintWithTarget(target)
        lookAt.gimbalLockEnabled = true
        camNode.constraints = listOf(lookAt)
    }

    private data class Vec3d(val x: Float, val y: Float, val z: Float)

    /**
     * 用 SCNNode.boundingSphere 拿世界坐标下的模型包围球.
     * SCNNode 上的 boundingSphere 是 (center: SCNVector3, radius: CGFloat),
     * 返回 local 坐标; 需 convertPosition:toNode:nil 转到世界坐标.
     * 若 radius 为 0(空 root / 只有 empty), 退化到默认值让相机不至于贴在 (0,0,0).
     */
    private fun computeSceneBounds(root: SCNNode): Pair<Vec3d, Double> {
        return memScoped {
            // iOS 上 SCNFloat = CGFloat = Double (arm64), radius 拿 Double 变量.
            // SCNVector3 里 x/y/z 也是 Double, useContents 时按 Double 读.
            val centerVar = alloc<platform.SceneKit.SCNVector3>()
            val radiusVar = alloc<kotlinx.cinterop.DoubleVar>()
            root.getBoundingSphereCenter(centerVar.ptr, radiusVar.ptr)
            val radius = radiusVar.value  // 先在 memScoped 上下文取, useContents 会切换 this
            val localCenter = centerVar.readValue()
            val world = root.convertPosition(localCenter, toNode = null)
            world.useContents {
                val safeR = if (radius > 0.001) radius else 1.5
                Pair(Vec3d(x.toFloat(), y.toFloat(), z.toFloat()), safeR)
            }
        }
    }

    /**
     * T-C1-4: 挂 SCNView. Compose UIKitView.factory 内首次调用.
     */
    fun attach(view: SCNView) {
        val previousView = scnView
        if (previousView !== view) {
            previousView?.let {
                it.playing = false
                it.scene = null
            }
        }
        scnView = view
        view.scene = scnScene
        // USDZ 场景本身无 SCNCamera 节点,不显式绑定的话 SCNView 会用默认 camera(位置原点看-z),
        // 大概率看不到 sketchfab 模型(其世界坐标经多层 Y-Z swap + scale 后不在原点).
        mainCamera?.let { view.pointOfView = it }
        view.playing = true
        view.setNeedsDisplay()
    }

    /** T-C4-3: 暂停渲染(tab 隐藏). */
    fun pause() {
        scnView?.playing = false
    }

    /** T-C4-3: 恢复渲染(tab 显示). */
    fun resume() {
        scnView?.playing = true
    }

    /**
     * T-C1-5: 释放路径. Compose DisposableEffect.onDispose 调用.
     * - SCNView 停止播放
     * - SCNScene 引用切断(让 SceneKit runtime 有机会 GC)
     * - pivot / light / camera 引用清空
     */
    fun detach() {
        scnView?.playing = false
        scnView?.scene = null
        scnView = null
        scnScene = null
        rootNode = null
        panPivot = null
        tiltPivot = null
        zoomPivot = null
        keyLight = null
        ambientLight = null
        mainCamera = null
    }

    /**
     * T-C4-2: 应用性能等级. L0=高质量 / L1=低画质(关多重采样).
     * v1.3-C 首版只实现 L0/L1, L2-L4 留 TODO.
     */
    fun applyPerformanceLevel(level: Int) {
        performanceLevel = level.coerceIn(0, 1)
        val view = scnView ?: return
        when (performanceLevel) {
            0 -> {
                view.preferredFramesPerSecond = 60
                view.antialiasingMode = platform.SceneKit.SCNAntialiasingMode.SCNAntialiasingModeMultisampling2X
            }
            1 -> {
                view.preferredFramesPerSecond = 60
                view.antialiasingMode = platform.SceneKit.SCNAntialiasingMode.SCNAntialiasingModeNone
            }
            // L2 (30fps + 关阴影) / L3 (关光) / L4 (静态 preview) 留 follow-up
            else -> Unit
        }
    }
}
