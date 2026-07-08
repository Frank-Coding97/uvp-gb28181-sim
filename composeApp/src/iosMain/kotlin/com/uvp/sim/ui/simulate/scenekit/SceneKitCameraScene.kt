package com.uvp.sim.ui.simulate.scenekit

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSBundle
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
 * - 加载 `security_camera.scn`(主 bundle)→ SCNScene
 *   - 缺失 fallback:纯代码建占位场景(SCNBox 机身 + SCNCylinder 镜筒 + 3 pivot)
 * - 绑定 [panPivot] / [tiltPivot] / [zoomPivot] 三个 SCNNode 供 [SceneKitEffectDispatcher] 消费
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
     * 加载 main bundle 内 `<resourceName>.scn`,失败则用纯代码占位场景 fallback.
     *
     * @return 无论走真实 scn 还是 fallback,只要 rootNode 建起来都返回 true.
     *         只有内部严重异常(比如 SceneKit 平台缺失)才返回 false.
     */
    fun loadFromBundle(resourceName: String = "security_camera"): Boolean {
        // 尝试主 bundle 加载(T-C1-0/1-1 未完成时 .scn 不存在, 走 fallback)
        val loaded: SCNScene? = SCNScene.sceneNamed("$resourceName.scn")
        if (loaded != null) {
            scnScene = loaded
            rootNode = loaded.rootNode
            isFallback = false
            return true
        }
        // fallback: 纯代码占位场景
        return loadFallbackScene()
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
        panPivot = root.childNodeWithName("pan_pivot", recursively = true)
        tiltPivot = root.childNodeWithName("tilt_pivot", recursively = true)
        zoomPivot = root.childNodeWithName("zoom_pivot", recursively = true)
        return panPivot != null && tiltPivot != null && zoomPivot != null
    }

    /**
     * T-C1-3: 建 key light + ambient light + camera node.
     * fallback 场景本身无光,直接建;真 scn 里通常已带光,重复挂无害.
     */
    fun setupLightsAndCamera() {
        val root = rootNode ?: return

        // Key light (方向光)
        val keyL = SCNLight().apply {
            type = SCNLightTypeDirectional
            color = UIColor.whiteColor
        }
        val keyNode = SCNNode().apply {
            name = "key_light"
            light = keyL
            position = SCNVector3Make(0.5f, 1f, 0.5f)
            eulerAngles = SCNVector3Make(-0.6f, 0.3f, 0f)
        }
        root.addChildNode(keyNode)
        keyLight = keyNode

        // Ambient (环境光)
        val ambL = SCNLight().apply {
            type = SCNLightTypeAmbient
            color = UIColor.grayColor
        }
        val ambNode = SCNNode().apply {
            name = "ambient_light"
            light = ambL
        }
        root.addChildNode(ambNode)
        ambientLight = ambNode

        // Camera
        val cam = platform.SceneKit.SCNCamera().apply {
            zNear = 0.1
            zFar = 100.0
        }
        val camNode = SCNNode().apply {
            name = "main_camera"
            camera = cam
            position = SCNVector3Make(0f, 0.3f, 1.6f)
            eulerAngles = SCNVector3Make(-0.1f, 0f, 0f)
        }
        root.addChildNode(camNode)
        mainCamera = camNode
    }

    /**
     * T-C1-4: 挂 SCNView. Compose UIKitView.factory 内首次调用.
     */
    fun attach(view: SCNView) {
        scnView = view
        view.scene = scnScene
        view.playing = true
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
