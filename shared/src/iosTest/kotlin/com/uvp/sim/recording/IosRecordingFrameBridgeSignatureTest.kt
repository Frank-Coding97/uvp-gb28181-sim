package com.uvp.sim.recording

import com.uvp.sim.media.H264Frame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-P5-2: 反射 / 编译期类型断言 IosRecordingFrameBridge 契约不变。
 *
 * v1.3-A 明确"H264Frame 结构不变 + bridge sink 语义不变"。任何未来对下面签名的修改必须
 * 先跟本文件 sync,否则 CI 红。守 v1.2 → v1.3 契约的最后一道防线。
 *
 * 用编译期类型断言(强类型 val 引用),签名变(参数增/删/改类型)本文件编译不过,信号明确。
 */
class IosRecordingFrameBridgeSignatureTest {

    @Test
    fun bridge_onVideoFrame_signature_stable() {
        // v1.3-A 契约:onVideoFrame(H264Frame) -> Unit
        val ref: (H264Frame) -> Unit = IosRecordingFrameBridge::onVideoFrame
        assertTrue(true, "IosRecordingFrameBridge.onVideoFrame signature: (H264Frame) -> Unit")
        // 用 ref 避免 unused warning
        val _ignore = ref
    }

    @Test
    fun bridge_publish_signature_stable() {
        // v1.3-A 契约:publish(IosVideoFrameSink?) -> Unit
        val ref: (IosVideoFrameSink?) -> Unit = IosRecordingFrameBridge::publish
        assertTrue(true, "IosRecordingFrameBridge.publish signature: (IosVideoFrameSink?) -> Unit")
        val _ignore = ref
    }

    @Test
    fun sink_feedVideoFrame_signature_stable() {
        // v1.3-A 契约:IosVideoFrameSink.feedVideoFrame(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) -> Unit
        val fake = object : IosVideoFrameSink {
            override fun feedVideoFrame(
                nalUnits: List<ByteArray>,
                ptsUs: Long,
                isKeyFrame: Boolean,
            ) {}
        }
        val ref: (List<ByteArray>, Long, Boolean) -> Unit = fake::feedVideoFrame
        assertTrue(true, "IosVideoFrameSink.feedVideoFrame signature: (List<ByteArray>, Long, Boolean) -> Unit")
        val _ignore = ref
    }
}
