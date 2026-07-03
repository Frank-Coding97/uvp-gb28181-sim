package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * T-P1-2 迁移语义:
 *   - IosCameraSessionHolder.publish 转发到 IosCameraController.publishExternalSession
 *   - PlatformCameraPreview 消费点已切到 controller.session(见文件本身 diff)
 *
 * Simulator 拿不到真 AVCaptureSession(依赖 back camera device)因此本文件不测
 * "publish 非 null session"路径,只测 mirror null 语义(publish null 后 controller.session 归 null)。
 *
 * 真机集成路径:
 *   - v1.2 IosCameraStreamer.stream() 路径的 wireCaptureSession 建 session 后调
 *     IosCameraSessionHolder.publish → 反向 mirror 到 IosCameraController.publishExternalSession
 *   - PlatformCameraPreview.ios collect IosCameraController.session,拿到 v1.2 路径的 session
 *   - 保 v1.2 keepalive → 预览上画路径(handoff bug 2 修复)不回退
 *
 * 上述真机集成验证在 T-V 阶段(AC-1)手工确认。
 */
class SessionHolderMigrationTest {

    @Test
    fun holder_publish_null_forwards_to_controller_null() {
        // controller 不在跑 preview 时 externalPublish 会更新 controller.session
        IosCameraSessionHolder.publish(null)
        assertNull(IosCameraController.session.value)
    }

    @Test
    fun holder_session_stateflow_reference_stable() {
        // holder 保留 own StateFlow,不 crash 兼容 legacy 消费者(即便本轮消费点已切走)
        val flow = IosCameraSessionHolder.session
        assertNull(flow.value)  // 初始未 publish 时为 null
    }
}
